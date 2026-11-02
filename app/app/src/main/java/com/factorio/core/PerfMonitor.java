package com.factorio.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Choreographer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Performance log: once a second everything that can slow the game down is sampled and written to perf.csv
 *  - frames: window-sized updates that reach the screen (all paths), Present / MIT-SHM / PutImage / Vortek counts,
 *    Android renderer frames, display vsyncs (Choreographer)
 *  - times: Present handler (lock wait, waiting for the Android renderer, cache sync, CPU copy), Android frame / upload,
 *    X request processing
 *  - CPU: the game process and the app, per thread (name, % of one core, core it last ran on), core frequencies
 *  - GPU busy / clock (Adreno kgsl), thermal status, battery temperature, game memory
 * stop() writes perf-summary.txt: averages over the "measure" phase (or the whole run), the busiest threads,
 * the most expensive X requests and hints where the time goes.
 */
public final class PerfMonitor {
    private static final String TAG = "PerfMonitor";
    private static PerfMonitor current;

    private final Context context;
    private final File dir;
    private final String label;
    private final long startNanos = System.nanoTime();
    private volatile boolean running = true;
    private volatile String phase = "start";
    private Thread thread;
    private BufferedWriter csv;
    private final long clockTicks;
    private final int cpuCount = Runtime.getRuntime().availableProcessors();
    private final int myPid = android.os.Process.myPid();
    private int gamePid = -1;

    // vsync counter (main thread)
    private final java.util.concurrent.atomic.AtomicLong vsyncs = new java.util.concurrent.atomic.AtomicLong();
    private volatile long maxVsyncGapNanos = 0;
    private long lastVsync = 0;
    private Choreographer.FrameCallback frameCallback;

    // previous values for deltas
    private final Map<String, Long> prev = new HashMap<>();
    private final Map<Integer, long[]> prevThreadTicks = new HashMap<>();   // tid -> {ticks}
    private long[][] reqAtMeasureStart, reqAtMeasureEnd;
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    // summary accumulators (measure phase, or everything if never marked)
    private final Map<String, double[]> sums = new HashMap<>();     // column -> {sum, count, max}
    private final Map<String, double[]> threadSums = new HashMap<>(); // "game/name" -> {sum %, samples, max %}
    private boolean measureSeen = false;
    private boolean measuring = false;

    private PerfMonitor(Context context, File dir, String label) {
        this.context = context.getApplicationContext();
        this.dir = dir;
        this.label = label;
        long ticks = 100;
        try {
            ticks = Os.sysconf(OsConstants._SC_CLK_TCK);
        }
        catch (Throwable e) {}
        this.clockTicks = ticks > 0 ? ticks : 100;
    }

    /** Starts the monitor (one at a time: a running one is stopped first). */
    public static PerfMonitor start(Context context, File dir, String label) {
        PerfMonitor old;
        synchronized (PerfMonitor.class) {
            old = current;
            current = null;
        }
        if (old != null) old.stop();
        dir.mkdirs();
        PerfMonitor monitor = new PerfMonitor(context, dir, label);
        synchronized (PerfMonitor.class) {
            current = monitor;
        }
        monitor.begin();
        return monitor;
    }

    public static synchronized PerfMonitor getCurrent() {
        return current;
    }

    /** Marks a phase in the log ("loading", "settle", "measure", "end"): the summary averages the "measure" phase. */
    public void setPhase(String phase) {
        this.phase = phase;
        event("phase -> "+phase);
        if (phase.equals("measure")) {
            measureSeen = true;
            measuring = true;
            synchronized (sums) {
                sums.clear();
                threadSums.clear();
            }
            reqAtMeasureStart = PerfStats.snapshotRequests();
        }
        else if (measuring) {
            measuring = false;
            reqAtMeasureEnd = PerfStats.snapshotRequests();
        }
    }

    public void event(String text) {
        events.add(String.format(Locale.US, "%7.1f s  %s", elapsedSeconds(), text));
    }

    private double elapsedSeconds() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    private void begin() {
        PerfStats.enabled = true;
        try {
            csv = new BufferedWriter(new FileWriter(new File(dir, "perf.csv")));
            csv.write("t_s,phase,frames_big_s,presents_s,shm_put_s,put_image_s,vortek_s,gladio_s,android_frames_s,vsync_s,max_vsync_gap_ms,"+
                      "present_ms,present_lockwait_ms,present_renderwait_ms,present_sync_ms,present_copy_ms,present_skipped_s,"+
                      "shm_put_ms,put_image_ms,android_frame_ms,android_renderwait_ms,android_upload_ms,"+
                      "xreq_s,xreq_busy_pct,game_cpu_pct,game_threads,app_cpu_pct,app_threads,cpu_mhz,gpu_busy_pct,gpu_mhz,thermal,battery_c,game_rss_mb\n");
        }
        catch (Exception e) {
            Log.e(TAG, "cannot open perf.csv", e);
        }
        reqAtMeasureStart = PerfStats.snapshotRequests();
        event("monitor started: "+label);

        new Handler(Looper.getMainLooper()).post(() -> {
            frameCallback = (frameTimeNanos) -> {
                if (!running) return;
                vsyncs.incrementAndGet();
                if (lastVsync != 0 && frameTimeNanos - lastVsync > maxVsyncGapNanos) maxVsyncGapNanos = frameTimeNanos - lastVsync;
                lastVsync = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(frameCallback);
            };
            Choreographer.getInstance().postFrameCallback(frameCallback);
        });

        thread = new Thread(() -> {
            long next = System.nanoTime();
            while (running) {
                next += 1_000_000_000L;
                long sleep = (next - System.nanoTime()) / 1_000_000L;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    }
                    catch (InterruptedException e) {
                        break;
                    }
                }
                if (!running) break;
                try {
                    sample();
                }
                catch (Throwable e) {
                    Log.w(TAG, "sample failed", e);
                }
            }
        }, "perf-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    // -------------------------------------------------------------------------------------------------------------
    private long delta(String key, long value) {
        Long old = prev.put(key, value);
        return old != null ? value - old : 0;
    }

    private boolean primed = false; // the first sample has no previous values: its deltas are not counted

    private void acc(String column, double value) {
        if (!primed) return;
        if (measureSeen && !measuring) return; // after the measure phase: not part of the summary
        synchronized (sums) {
            double[] s = sums.get(column);
            if (s == null) sums.put(column, s = new double[]{0, 0, Double.NEGATIVE_INFINITY});
            s[0] += value;
            s[1]++;
            if (value > s[2]) s[2] = value;
        }
    }

    private static double ms(long nanos, long count) {
        return count > 0 ? nanos / 1e6 / count : 0;
    }

    private void sample() throws Exception {
        long frames = delta("frames", PerfStats.bigFrames.get());
        long presents = delta("presents", PerfStats.presents.get());
        long shm = delta("shm", PerfStats.shmPutImages.get());
        long put = delta("put", PerfStats.putImages.get());
        long vortek = delta("vortek", PerfStats.vortekUpdates.get());
        long gladio = delta("gladio", PerfStats.gladioUpdates.get());
        long glFrames = delta("gl", PerfStats.glFrames.get());
        long vsync = delta("vsync", vsyncs.get());
        double vsyncGap = maxVsyncGapNanos / 1e6;
        maxVsyncGapNanos = 0;

        double presentMs = ms(delta("presentNs", PerfStats.presentNanos.get()), presents);
        double lockMs = ms(delta("lockNs", PerfStats.presentLockWaitNanos.get()), presents);
        double renderWaitMs = ms(delta("rwNs", PerfStats.presentRenderLockWaitNanos.get()), presents);
        double syncMs = ms(delta("syncNs", PerfStats.presentSyncNanos.get()), presents);
        double copyMs = ms(delta("copyNs", PerfStats.presentCopyNanos.get()), presents);
        long skipped = delta("skipped", PerfStats.presentSkipped.get());
        double shmMs = ms(delta("shmNs", PerfStats.shmPutImageNanos.get()), shm);
        double putMs = ms(delta("putNs", PerfStats.putImageNanos.get()), put);
        double glMs = ms(delta("glNs", PerfStats.glFrameNanos.get()), glFrames);
        double glWaitMs = ms(delta("glWaitNs", PerfStats.glRenderLockWaitNanos.get()), glFrames);
        double glUploadMs = ms(delta("glUpNs", PerfStats.glTextureUploadNanos.get()), glFrames);
        long xreq = delta("xreq", PerfStats.requests.get());
        double xbusy = delta("xreqNs", PerfStats.requestNanos.get()) / 1e7; // % of one second

        // ---- CPU per thread ----
        if (gamePid <= 0 || !new File("/proc/"+gamePid).exists()) gamePid = findGamePid();
        double[] gameTotal = {0};
        String gameThreads = gamePid > 0 ? threads(gamePid, "game", gameTotal) : "";
        double[] appTotal = {0};
        String appThreads = threads(myPid, "app", appTotal);

        String cpuMhz = cpuFrequencies();
        String gpuBusy = gpuBusy();
        String gpuMhz = gpuClock();
        String thermal = thermalStatus();
        String battery = batteryTemperature();
        String rss = gamePid > 0 ? gameRssMb(gamePid) : "";

        long frameCount = Math.max(frames, Math.max(presents, Math.max(vortek, gladio)));
        acc("frames_s", frameCount);
        acc("presents_s", presents);
        acc("shm_put_s", shm);
        acc("android_frames_s", glFrames);
        acc("vsync_s", vsync);
        acc("max_vsync_gap_ms", vsyncGap);
        if (presents > 0) {
            acc("present_ms", presentMs);
            acc("present_lockwait_ms", lockMs);
            acc("present_renderwait_ms", renderWaitMs);
            acc("present_sync_ms", syncMs);
            acc("present_copy_ms", copyMs);
        }
        if (shm > 0) acc("shm_put_ms", shmMs);
        if (glFrames > 0) {
            acc("android_frame_ms", glMs);
            acc("android_renderwait_ms", glWaitMs);
            acc("android_upload_ms", glUploadMs);
        }
        acc("xreq_s", xreq);
        acc("xreq_busy_pct", xbusy);
        acc("game_cpu_pct", gameTotal[0]);
        acc("app_cpu_pct", appTotal[0]);
        try {
            if (!gpuBusy.isEmpty()) acc("gpu_busy_pct", Double.parseDouble(gpuBusy));
        }
        catch (Exception e) {}

        String row = String.format(Locale.US,
            "%.1f,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.1f,%.0f,%s,%.0f,%s,%s,%s,%s,%s,%s,%s\n",
            elapsedSeconds(), phase, frames, presents, shm, put, vortek, gladio, glFrames, vsync, vsyncGap,
            presentMs, lockMs, renderWaitMs, syncMs, copyMs, skipped, shmMs, putMs, glMs, glWaitMs, glUploadMs,
            xreq, xbusy, gameTotal[0], quote(gameThreads), appTotal[0], quote(appThreads), quote(cpuMhz), gpuBusy, gpuMhz, thermal, battery, rss);
        synchronized (this) {
            if (csv != null) {
                csv.write(row);
                csv.flush();
            }
        }
        primed = true;
    }

    private static String quote(String text) {
        return "\""+text.replace("\"", "'")+"\"";
    }

    // ---- /proc helpers ----
    private static String readSmall(String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            byte[] buffer = new byte[4096];
            int n = file.read(buffer);
            return n > 0 ? new String(buffer, 0, n) : "";
        }
        catch (Exception e) {
            return null;
        }
    }

    private int findGamePid() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return -1;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            String cmdline = readSmall("/proc/"+name+"/cmdline");
            if (cmdline != null && cmdline.contains("/bin/arm64/factorio") && !cmdline.contains("factorio-start.sh\0")) {
                try {
                    int pid = Integer.parseInt(name);
                    if (pid != myPid) {
                        event("game process found: pid "+pid);
                        return pid;
                    }
                }
                catch (NumberFormatException e) {}
            }
        }
        return -1;
    }

    /** "name:%:core ..." of the 4 busiest threads; total[0] = sum of all threads in % of one core. */
    private String threads(int pid, String owner, double[] total) {
        File[] tasks = new File("/proc/"+pid+"/task").listFiles();
        if (tasks == null) return "";
        List<Object[]> busy = new ArrayList<>();
        double sum = 0;
        for (File task : tasks) {
            String stat = readSmall(task.getPath()+"/stat");
            if (stat == null) continue;
            int close = stat.lastIndexOf(')');
            int open = stat.indexOf('(');
            if (close < 0 || open < 0) continue;
            String comm = stat.substring(open + 1, close);
            String[] f = stat.substring(close + 2).split(" ");
            if (f.length < 37) continue;
            long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            int core = Integer.parseInt(f[36]);
            int tid;
            try {
                tid = Integer.parseInt(task.getName());
            }
            catch (NumberFormatException e) {
                continue;
            }
            long[] old = prevThreadTicks.get(tid);
            prevThreadTicks.put(tid, new long[]{ticks});
            if (old == null) continue;
            double pct = (ticks - old[0]) * 100.0 / clockTicks;
            sum += pct;
            if (pct > 0) busy.add(new Object[]{comm, pct, core});
            if (pct > 0) {
                String key = owner+"/"+comm;
                if (!(measureSeen && !measuring)) {
                    synchronized (sums) {
                        double[] s = threadSums.get(key);
                        if (s == null) threadSums.put(key, s = new double[]{0, 0, 0});
                        s[0] += pct;
                        s[1]++;
                        if (pct > s[2]) s[2] = pct;
                    }
                }
            }
        }
        total[0] = sum;
        Collections.sort(busy, (a, b) -> Double.compare((double)b[1], (double)a[1]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, busy.size()); i++) {
            Object[] t = busy.get(i);
            sb.append(i > 0 ? " " : "").append(t[0]).append(':').append(String.format(Locale.US, "%.0f", (double)t[1])).append(":c").append(t[2]);
        }
        return sb.toString();
    }

    private String cpuFrequencies() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cpuCount; i++) {
            String value = readSmall("/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq");
            if (i > 0) sb.append('/');
            try {
                sb.append(value != null ? Long.parseLong(value.trim()) / 1000 : "?");
            }
            catch (NumberFormatException e) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String gpuBusy() {
        String value = readSmall("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (value != null) return value.replace("%", "").trim();
        value = readSmall("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (value != null) {
            String[] parts = value.trim().split("\\s+");
            try {
                if (parts.length >= 2 && Long.parseLong(parts[1]) > 0) {
                    return String.format(Locale.US, "%.0f", Long.parseLong(parts[0]) * 100.0 / Long.parseLong(parts[1]));
                }
            }
            catch (NumberFormatException e) {}
        }
        return "";
    }

    private static String gpuClock() {
        String value = readSmall("/sys/class/kgsl/kgsl-3d0/gpuclk");
        if (value == null) value = readSmall("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
        try {
            return value != null ? String.valueOf(Long.parseLong(value.trim()) / 1000000) : "";
        }
        catch (NumberFormatException e) {
            return "";
        }
    }

    private String thermalStatus() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
                String status = pm != null ? String.valueOf(pm.getCurrentThermalStatus()) : "";
                if (Build.VERSION.SDK_INT >= 30 && pm != null) {
                    float headroom = pm.getThermalHeadroom(0);
                    if (!Float.isNaN(headroom)) status += String.format(Locale.US, " h%.2f", headroom);
                }
                return status;
            }
        }
        catch (Throwable e) {}
        return "";
    }

    private String batteryTemperature() {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) return String.format(Locale.US, "%.1f", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        }
        catch (Throwable e) {}
        return "";
    }

    private static String gameRssMb(int pid) {
        String statm = readSmall("/proc/"+pid+"/statm");
        if (statm == null) return "";
        String[] parts = statm.trim().split("\\s+");
        try {
            return String.valueOf(Long.parseLong(parts[1]) * 4096 / (1024 * 1024));
        }
        catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    /** Stops sampling and writes perf-summary.txt. Returns the summary text (also when called twice). */
    public synchronized String stop() {
        if (!running) return summaryText != null ? summaryText : "";
        running = false;
        if (thread != null) thread.interrupt();
        if (measuring) setPhase("end");
        if (reqAtMeasureEnd == null) reqAtMeasureEnd = PerfStats.snapshotRequests();
        clearCurrent(this);
        try {
            if (csv != null) csv.close();
        }
        catch (Exception e) {}
        csv = null;
        summaryText = buildSummary();
        FileUtils.writeString(new File(dir, "perf-summary.txt"), summaryText);
        return summaryText;
    }

    private String summaryText;

    private static synchronized void clearCurrent(PerfMonitor monitor) {
        if (current == monitor || current == null) {
            current = null;
            PerfStats.enabled = false;
        }
    }

    private double avg(String column) {
        double[] s = sums.get(column);
        return s != null && s[1] > 0 ? s[0] / s[1] : Double.NaN;
    }

    private double max(String column) {
        double[] s = sums.get(column);
        return s != null && s[1] > 0 ? s[2] : Double.NaN;
    }

    /** One line for the run summary: frames/s and the busiest thread. */
    public String shortLine() {
        String top = topThread("game/");
        return String.format(Locale.US, "frames/s %.1f (max %.0f), Android frames/s %.1f, vsync/s %.0f, game CPU %.0f%%, busiest game thread %s, GPU busy %s",
            avg("frames_s"), max("frames_s"), avg("android_frames_s"), avg("vsync_s"), avg("game_cpu_pct"), top,
            Double.isNaN(avg("gpu_busy_pct")) ? "n/a" : String.format(Locale.US, "%.0f%%", avg("gpu_busy_pct")));
    }

    private String topThread(String prefix) {
        String best = "n/a";
        double bestValue = -1;
        for (Map.Entry<String, double[]> entry : threadSums.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            double a = entry.getValue()[0] / Math.max(1, entry.getValue()[1]);
            if (a > bestValue) {
                bestValue = a;
                best = entry.getKey().substring(prefix.length())+String.format(Locale.US, " %.0f%%", a);
            }
        }
        return best;
    }

    private String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance summary: ").append(label).append('\n');
        sb.append(measureSeen ? "averages over the MEASURE phase\n" : "averages over the whole run (no measure phase)\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", SoC ").append(Build.HARDWARE)
          .append(", ").append(cpuCount).append(" cores, max MHz per core: ").append(maxFrequencies()).append("\n\n");

        String[] columns = {"frames_s", "presents_s", "shm_put_s", "android_frames_s", "vsync_s", "max_vsync_gap_ms",
            "present_ms", "present_lockwait_ms", "present_renderwait_ms", "present_sync_ms", "present_copy_ms", "shm_put_ms",
            "android_frame_ms", "android_renderwait_ms", "android_upload_ms", "xreq_s", "xreq_busy_pct", "game_cpu_pct", "app_cpu_pct", "gpu_busy_pct"};
        sb.append(String.format(Locale.US, "%-24s %10s %10s\n", "metric", "average", "max"));
        for (String column : columns) {
            double a = avg(column);
            if (Double.isNaN(a)) continue;
            sb.append(String.format(Locale.US, "%-24s %10.2f %10.2f\n", column, a, max(column)));
        }

        sb.append("\nbusiest threads (% of one core: average / max):\n");
        List<Map.Entry<String, double[]>> threads = new ArrayList<>(threadSums.entrySet());
        Collections.sort(threads, (a, b) -> Double.compare(b.getValue()[0] / Math.max(1, b.getValue()[1]), a.getValue()[0] / Math.max(1, a.getValue()[1])));
        for (int i = 0; i < Math.min(16, threads.size()); i++) {
            Map.Entry<String, double[]> t = threads.get(i);
            sb.append(String.format(Locale.US, "  %-34s %6.1f / %6.1f\n", t.getKey(), t.getValue()[0] / Math.max(1, t.getValue()[1]), t.getValue()[2]));
        }

        sb.append("\nmost expensive X requests (count, total ms, avg us, errors):\n");
        if (reqAtMeasureStart != null && reqAtMeasureEnd != null) {
            List<long[]> rows = new ArrayList<>();
            for (int key = 0; key < PerfStats.KEYS; key++) {
                long count = reqAtMeasureEnd[0][key] - reqAtMeasureStart[0][key];
                if (count <= 0) continue;
                rows.add(new long[]{key, count, reqAtMeasureEnd[1][key] - reqAtMeasureStart[1][key], reqAtMeasureEnd[2][key] - reqAtMeasureStart[2][key]});
            }
            Collections.sort(rows, (a, b) -> Long.compare(b[2], a[2]));
            for (int i = 0; i < Math.min(15, rows.size()); i++) {
                long[] r = rows.get(i);
                sb.append(String.format(Locale.US, "  %-28s %8d %10.1f %8.1f %6d\n", PerfStats.requestName((int)r[0]), r[1], r[2] / 1e6, r[2] / 1e3 / r[1], r[3]));
            }
        }

        sb.append("\nhints:\n").append(hints());
        sb.append("\nevents:\n");
        synchronized (events) {
            for (String e : events) sb.append("  ").append(e).append('\n');
        }
        return sb.toString();
    }

    private String maxFrequencies() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cpuCount; i++) {
            String value = readSmall("/sys/devices/system/cpu/cpu"+i+"/cpufreq/cpuinfo_max_freq");
            if (i > 0) sb.append('/');
            try {
                sb.append(value != null ? Long.parseLong(value.trim()) / 1000 : "?");
            }
            catch (NumberFormatException e) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /** Rough automatic reading of the numbers; the raw data is in perf.csv. */
    private String hints() {
        StringBuilder sb = new StringBuilder();
        double frames = avg("frames_s");
        double vsync = avg("vsync_s");
        if (!Double.isNaN(frames) && !Double.isNaN(vsync) && vsync > 0) {
            double ratio = frames / vsync;
            if (Math.abs(ratio - 0.5) < 0.04) sb.append("- frames/s is almost exactly HALF of the display rate: frames miss every second vsync (pacing / vsync wait), not a smooth CPU or GPU limit\n");
            else if (Math.abs(ratio - 1.0 / 3) < 0.03) sb.append("- frames/s is about a third of the display rate: vsync pacing\n");
        }
        for (Map.Entry<String, double[]> entry : threadSums.entrySet()) {
            double a = entry.getValue()[0] / Math.max(1, entry.getValue()[1]);
            if (a >= 85) sb.append(String.format(Locale.US, "- thread %s is busy %.0f%% of one core: it can be the limit (CPU bound on that thread)\n", entry.getKey(), a));
        }
        double gpu = avg("gpu_busy_pct");
        if (!Double.isNaN(gpu) && gpu >= 90) sb.append(String.format(Locale.US, "- GPU busy %.0f%%: GPU bound\n", gpu));
        double copy = avg("present_copy_ms"), sync = avg("present_sync_ms"), lock = avg("present_lockwait_ms"), renderWait = avg("present_renderwait_ms");
        if (!Double.isNaN(copy) && copy + (Double.isNaN(sync) ? 0 : sync) > 8) sb.append(String.format(Locale.US, "- Present spends %.1f ms per frame copying (+%.1f ms cache sync): the CPU copy limits the frame rate\n", copy, Double.isNaN(sync) ? 0 : sync));
        if (!Double.isNaN(lock) && lock > 3) sb.append(String.format(Locale.US, "- Present waits %.1f ms per frame for X server locks\n", lock));
        if (!Double.isNaN(renderWait) && renderWait > 3) sb.append(String.format(Locale.US, "- Present waits %.1f ms per frame for the Android renderer (renderLock)\n", renderWait));
        double shm = avg("shm_put_ms");
        if (!Double.isNaN(shm) && shm > 8) sb.append(String.format(Locale.US, "- MIT-SHM PutImage takes %.1f ms per frame (xlib path)\n", shm));
        double xbusy = avg("xreq_busy_pct");
        if (!Double.isNaN(xbusy) && xbusy > 60) sb.append(String.format(Locale.US, "- the X server is busy %.0f%% of the time with requests\n", xbusy));
        if (sb.length() == 0) sb.append("- nothing obvious: compare perf.csv of the variants\n");
        return sb.toString();
    }
}
