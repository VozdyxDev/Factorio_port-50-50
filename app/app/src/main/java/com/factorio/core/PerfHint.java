package com.factorio.core;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/**
 * Asks Android for more CPU / GPU speed (X_PERF_HINT=1).
 * Measured in r50: the GPU stays at its lowest clock (389 of 1200 MHz) while the game's main thread waits for it 43 % of
 * the time. The GPU governor only sees "busy 45 %" and does not clock up. Two official ways to tell the system that a
 * game is too slow:
 *  - ADPF (PerformanceHintManager, Android 12+): a hint session with the game's threads; every frame we report how long
 *    it really took against a 16.7 ms target. Being late makes the system boost (on Snapdragon the perf HAL also raises
 *    GPU clocks for such sessions on many devices).
 *  - GameManager.setGameState(gameplay) (Android 13+).
 * Everything is logged (logcat tag PerfHint and the perf summary events), because whether a device reacts is up to it.
 */
public final class PerfHint {
    private static final String TAG = "PerfHint";
    private static final long TARGET_NANOS = 16_666_667L;
    public static volatile boolean active = false;
    private static Object session; // PerformanceHintManager.Session (API 31)
    private static long lastFrame = 0;
    private static Thread watcher;

    private PerfHint() {}

    private static void log(String text) {
        Log.i(TAG, text);
        PerfMonitor monitor = PerfMonitor.getCurrent();
        if (monitor != null) monitor.event("perf hint: "+text);
    }

    /** Starts in the background: waits for the game process, then opens the hint session. */
    public static synchronized void start(final Context context) {
        if (watcher != null) return;
        final Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                GameManager gameManager = app.getSystemService(GameManager.class);
                if (gameManager != null) {
                    gameManager.setGameState(new GameState(false, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE));
                    log("GameManager game state: gameplay");
                }
            }
            catch (Throwable e) {
                log("GameManager.setGameState failed: "+e);
            }
        }
        watcher = new Thread(() -> {
            try {
                int pid = -1;
                for (int i = 0; i < 240 && pid <= 0; i++) {
                    pid = findGamePid();
                    if (pid <= 0) Thread.sleep(500);
                }
                if (pid <= 0) {
                    log("game process not found");
                    return;
                }
                openSession(app, pid);
                // the game creates its threads while loading: update the list for 3 minutes
                for (int i = 0; i < 18 && session != null; i++) {
                    Thread.sleep(10000);
                    updateThreads(pid);
                }
            }
            catch (InterruptedException e) {}
        }, "perf-hint");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static synchronized void stop() {
        active = false;
        if (watcher != null) watcher.interrupt();
        watcher = null;
        if (session != null && Build.VERSION.SDK_INT >= 31) {
            try {
                ((PerformanceHintManager.Session)session).close();
            }
            catch (Throwable e) {}
        }
        session = null;
    }

    private static void openSession(Context context, int pid) {
        if (Build.VERSION.SDK_INT < 31) {
            log("ADPF needs Android 12");
            return;
        }
        try {
            PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                log("no PerformanceHintManager");
                return;
            }
            log("preferred update rate "+manager.getPreferredUpdateRateNanos()+" ns");
            int[] tids = gameThreads(pid);
            PerformanceHintManager.Session s = manager.createHintSession(tids, TARGET_NANOS);
            if (s == null) {
                // not supported, or the system refuses threads of another process: try our own X server threads
                log("session with "+tids.length+" game threads refused; trying the app's own threads");
                s = manager.createHintSession(new int[]{android.os.Process.myTid()}, TARGET_NANOS);
            }
            if (s == null) {
                log("ADPF not supported on this device (createHintSession returned null)");
                return;
            }
            synchronized (PerfHint.class) {
                session = s;
                active = true;
            }
            log("session open: "+tids.length+" threads, target "+TARGET_NANOS / 1000+" us");
        }
        catch (Throwable e) {
            log("createHintSession failed: "+e);
        }
    }

    private static void updateThreads(int pid) {
        if (Build.VERSION.SDK_INT < 34 || session == null) return;
        try {
            int[] tids = gameThreads(pid);
            ((PerformanceHintManager.Session)session).setThreads(tids);
        }
        catch (Throwable e) {
            log("setThreads failed: "+e);
        }
    }

    /** Called for every frame that reaches the screen (Present / window update). */
    public static void onFrame() {
        long now = System.nanoTime();
        long last = lastFrame;
        lastFrame = now;
        Object s = session;
        if (s == null || last == 0 || Build.VERSION.SDK_INT < 31) return;
        long duration = now - last;
        if (duration <= 0 || duration > 500_000_000L) return; // pauses (menu, loading) are not frames
        try {
            ((PerformanceHintManager.Session)s).reportActualWorkDuration(duration);
        }
        catch (Throwable e) {
            session = null;
            active = false;
            log("reportActualWorkDuration failed: "+e);
        }
    }

    /** The game's main thread and its busy helpers (update, driver, glthread, render workers). */
    private static int[] gameThreads(int pid) {
        ArrayList<Integer> list = new ArrayList<>();
        list.add(pid);
        File[] tasks = new File("/proc/"+pid+"/task").listFiles();
        if (tasks != null) {
            for (File task : tasks) {
                String comm = read(task.getPath()+"/comm");
                if (comm == null) continue;
                comm = comm.trim();
                if (comm.startsWith("GameUpdate") || comm.contains("gdrv") || comm.contains(":gl") || comm.startsWith("ld-linux")) {
                    try {
                        int tid = Integer.parseInt(task.getName());
                        if (!list.contains(tid)) list.add(tid);
                    }
                    catch (NumberFormatException e) {}
                }
            }
        }
        int[] result = new int[Math.min(list.size(), 16)];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
    }

    private static String read(String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            byte[] buffer = new byte[4096];
            int n = file.read(buffer);
            return n > 0 ? new String(buffer, 0, n) : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    static int findGamePid() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return -1;
        int myPid = android.os.Process.myPid();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            String cmdline = read("/proc/"+name+"/cmdline");
            if (cmdline != null && (cmdline.contains("/bin/arm64/factorio\0") || cmdline.endsWith("/bin/arm64/factorio"))) {
                try {
                    int pid = Integer.parseInt(name);
                    if (pid != myPid) return pid;
                }
                catch (NumberFormatException e) {}
            }
        }
        return -1;
    }
}
