package com.factorio.core;

import android.system.Os;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "What is the thread waiting for?" 100 times a second every thread of the game is looked at:
 *  - running (R) or sleeping (S/D)
 *  - when sleeping: the system call it sleeps in (/proc/.../syscall: futex = waits for another thread, ioctl on
 *    /dev/kgsl-3d0 = waits for the GPU driver, recvmsg / ppoll on a socket = waits for the X server, nanosleep = the
 *    game's own frame pacing, ...) and for calls on a file descriptor, what that descriptor is.
 * A thread that is never at 100 % CPU but slow spends its time in one of these: the summary shows where.
 */
final class WaitSampler {
    private final int pid;
    private volatile boolean running = true;
    private Thread thread;
    private final Map<String, Map<String, int[]>> reasons = new HashMap<>(); // thread -> reason -> {count}
    private final Map<String, int[]> totals = new HashMap<>();
    private final Map<String, String> fdNames = new HashMap<>();
    private int rounds = 0;

    WaitSampler(int pid) {
        this.pid = pid;
    }

    void start() {
        thread = new Thread(() -> {
            long next = System.nanoTime();
            while (running) {
                sampleOnce();
                next += 10_000_000L; // 100 Hz
                long sleep = (next - System.nanoTime()) / 1_000_000L;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    }
                    catch (InterruptedException e) {
                        break;
                    }
                }
                else next = System.nanoTime();
            }
        }, "perf-waits");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            }
            catch (InterruptedException e) {}
        }
    }

    private static String read(String path) {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            byte[] buffer = new byte[512];
            int n = file.read(buffer);
            return n > 0 ? new String(buffer, 0, n).trim() : "";
        }
        catch (Exception e) {
            return null;
        }
    }

    private void sampleOnce() {
        File[] tasks = new File("/proc/"+pid+"/task").listFiles();
        if (tasks == null) return;
        rounds++;
        for (File task : tasks) {
            String stat = read(task.getPath()+"/stat");
            if (stat == null) continue;
            int open = stat.indexOf('('), close = stat.lastIndexOf(')');
            if (open < 0 || close < 0 || close + 2 >= stat.length()) continue;
            String name = stat.substring(open + 1, close)+"#"+task.getName();
            char state = stat.charAt(close + 2);
            String reason;
            if (state == 'R') reason = "RUNNING";
            else {
                String syscall = read(task.getPath()+"/syscall");
                reason = describe(state, syscall);
            }
            synchronized (this) {
                Map<String, int[]> map = reasons.get(name);
                if (map == null) reasons.put(name, map = new HashMap<>());
                int[] count = map.get(reason);
                if (count == null) map.put(reason, count = new int[1]);
                count[0]++;
                int[] total = totals.get(name);
                if (total == null) totals.put(name, total = new int[1]);
                total[0]++;
            }
        }
    }

    private String describe(char state, String syscall) {
        String prefix = state == 'D' ? "D:" : "";
        if (syscall == null) return prefix+"sleep (syscall not readable)";
        if (syscall.equals("running")) return "RUNNING";
        String[] f = syscall.split("\\s+");
        int nr;
        try {
            nr = Integer.parseInt(f[0]);
        }
        catch (Exception e) {
            return prefix+"sleep";
        }
        String name = syscallName(nr);
        // calls on a file descriptor: show what it is (GPU device, X socket, pipe, sync file ...)
        if (nr == 29 && f.length > 2) {
            // ioctl: which request (KGSL: wait for a GPU timestamp = waiting for the GPU to finish, gpu command = submit)
            try {
                int fd = (int)(long)Long.decode(f[1]);
                long request = Long.decode(f[2]);
                String target = fdName(fd);
                String what;
                int type = (int)((request >> 8) & 0xff), number = (int)(request & 0xff);
                if (type == 0x09) what = kgslName(number);
                else if (type == 'b') what = String.format(Locale.US, "dma-buf 0x%02x", number);
                else what = String.format(Locale.US, "type 0x%02x nr 0x%02x", type, number);
                return prefix+"ioctl "+target+" "+what;
            }
            catch (Exception e) {}
        }
        if ((nr == 29 || nr == 63 || nr == 64 || nr == 65 || nr == 66 || nr == 67 || nr == 207 || nr == 212 || nr == 211 || nr == 206) && f.length > 1) {
            try {
                long fd = Long.decode(f[1]);
                name += " "+fdName((int)fd);
            }
            catch (Exception e) {}
        }
        if (nr == 98 && f.length > 2) {
            // futex op: 0/128 = WAIT, 9/137 = WAIT_BITSET
            try {
                long op = Long.decode(f[2]) & 127;
                name += op == 0 || op == 9 ? " WAIT (another thread / fence)" : " op"+op;
            }
            catch (Exception e) {}
        }
        return prefix+name;
    }

    private String fdName(int fd) {
        String key = String.valueOf(fd);
        String cached = fdNames.get(key);
        if (cached != null) return cached;
        String target;
        try {
            target = Os.readlink("/proc/"+pid+"/fd/"+fd);
        }
        catch (Exception e) {
            target = "fd"+fd;
        }
        if (target.startsWith("socket:")) target = "socket";            // X server / audio / input sockets
        else if (target.startsWith("pipe:")) target = "pipe";
        else if (target.startsWith("anon_inode:")) target = target.substring(11);
        else if (target.startsWith("/dev/")) target = target;
        else if (target.length() > 40) target = "..."+target.substring(target.length() - 37);
        fdNames.put(key, target);
        return target;
    }

    private static String kgslName(int number) {
        switch (number) {
            case 0x02: return "GETPROPERTY";
            case 0x06: return "WAITTIMESTAMP (waits for the GPU)";
            case 0x07: return "WAITTIMESTAMP_CTXTID (waits for the GPU)";
            case 0x10: return "READTIMESTAMP";
            case 0x11: return "READTIMESTAMP_CTXTID";
            case 0x13: return "DRAWCTXT_CREATE";
            case 0x14: return "DRAWCTXT_DESTROY";
            case 0x4A: return "GPU_COMMAND (submit)";
            default: return String.format(Locale.US, "kgsl 0x%02x", number);
        }
    }

    private static String syscallName(int nr) {
        switch (nr) {
            case 22: return "epoll_pwait";
            case 29: return "ioctl";
            case 56: return "openat";
            case 57: return "close";
            case 63: return "read";
            case 64: return "write";
            case 65: return "readv";
            case 66: return "writev";
            case 67: return "pread64";
            case 72: return "pselect6";
            case 73: return "ppoll";
            case 98: return "futex";
            case 101: return "nanosleep";
            case 115: return "clock_nanosleep";
            case 124: return "sched_yield";
            case 137: return "rt_sigtimedwait";
            case 206: return "sendto";
            case 207: return "recvfrom";
            case 211: return "sendmsg";
            case 212: return "recvmsg";
            case 215: return "munmap";
            case 222: return "mmap";
            case 226: return "mprotect";
            case 260: return "wait4";
            case 441: return "epoll_pwait2";
            case 449: return "futex_waitv";
            default: return "syscall "+nr;
        }
    }

    /** Text for perf-summary.txt: per busy thread the share of samples per reason. */
    synchronized String summary(int maxThreads) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "what the game threads do (%d samples per thread at 100 Hz; RUNNING = on a CPU core):\n", rounds));
        List<Map.Entry<String, Map<String, int[]>>> threads = new ArrayList<>(reasons.entrySet());
        // threads with the most RUNNING first, then the rest (idle helper threads at the end)
        Collections.sort(threads, (a, b) -> Integer.compare(count(b.getValue(), "RUNNING"), count(a.getValue(), "RUNNING")));
        int shown = 0;
        for (Map.Entry<String, Map<String, int[]>> entry : threads) {
            if (shown++ >= maxThreads) break;
            int total = totals.get(entry.getKey())[0];
            List<Map.Entry<String, int[]>> list = new ArrayList<>(entry.getValue().entrySet());
            Collections.sort(list, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            sb.append("  ").append(entry.getKey()).append(":\n");
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                Map.Entry<String, int[]> r = list.get(i);
                sb.append(String.format(Locale.US, "      %5.1f %%  %s\n", r.getValue()[0] * 100.0 / total, r.getKey()));
            }
        }
        return sb.toString();
    }

    private static int count(Map<String, int[]> map, String key) {
        int[] c = map.get(key);
        return c != null ? c[0] : 0;
    }
}
