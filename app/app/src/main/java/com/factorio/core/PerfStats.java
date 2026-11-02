package com.factorio.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cheap counters filled by the X server, the Android renderer and the drivers' callbacks while a {@link PerfMonitor}
 * runs (enabled == false costs one volatile read per hook). PerfMonitor turns them into per-second numbers.
 */
public final class PerfStats {
    public static volatile boolean enabled = false;

    // ---- X requests: key = major opcode << 8 | minor opcode (minor only for extensions) ----
    static final int KEYS = 1 << 16;
    static final long[] reqCount = new long[KEYS];
    static final long[] reqNanos = new long[KEYS];
    static final long[] reqErrors = new long[KEYS];
    static final String[] extensionNames = new String[256];
    public static final AtomicLong requests = new AtomicLong();
    public static final AtomicLong requestNanos = new AtomicLong();

    // ---- frames that reach the screen ----
    /** window-sized content updates, whatever path brought them (Present, MIT-SHM, PutImage, Vortek, Gladio) */
    public static final AtomicLong bigFrames = new AtomicLong();
    public static volatile int bigFrameMinPixels = 640 * 360;

    // ---- Present (DRI3 path: OpenGL KGSL, Zink DRI / kopper, Turnip WSI) ----
    public static final AtomicLong presents = new AtomicLong();
    public static final AtomicLong presentNanos = new AtomicLong();          // whole PresentPixmap handler
    public static final AtomicLong presentLockWaitNanos = new AtomicLong();  // waiting for the X server locks
    public static final AtomicLong presentRenderLockWaitNanos = new AtomicLong(); // waiting for the Android renderer
    public static final AtomicLong presentSyncNanos = new AtomicLong();      // DMA_BUF_IOCTL_SYNC (CPU cache)
    public static final AtomicLong presentCopyNanos = new AtomicLong();      // CPU copy of the frame
    public static final AtomicLong presentSkipped = new AtomicLong();        // blank frames not shown

    // ---- MIT-SHM / core PutImage (xlib Zink, software paths) ----
    public static final AtomicLong shmPutImages = new AtomicLong();
    public static final AtomicLong shmPutImageNanos = new AtomicLong();
    public static final AtomicLong putImages = new AtomicLong();
    public static final AtomicLong putImageNanos = new AtomicLong();
    public static final AtomicLong putImageBytes = new AtomicLong();

    // ---- Vortek / Gladio window updates ----
    public static final AtomicLong vortekUpdates = new AtomicLong();
    public static final AtomicLong gladioUpdates = new AtomicLong();

    // ---- Android side (GLSurfaceView renderer thread) ----
    public static final AtomicLong glFrames = new AtomicLong();
    public static final AtomicLong glFrameNanos = new AtomicLong();
    public static final AtomicLong glRenderLockWaitNanos = new AtomicLong();
    public static final AtomicLong glTextureUploadNanos = new AtomicLong();

    private PerfStats() {}

    public static long now() {
        return enabled ? System.nanoTime() : 0;
    }

    /** Elapsed time since a now() value (0 when disabled). */
    public static long since(long start) {
        return start != 0 ? System.nanoTime() - start : 0;
    }

    public static void request(int major, int minor, String extensionName, long nanos, boolean error) {
        int key = major < 128 ? major << 8 : (major << 8) | (minor & 255);
        synchronized (reqCount) {
            reqCount[key]++;
            reqNanos[key] += nanos;
            if (error) reqErrors[key]++;
            if (extensionName != null && major >= 128 && extensionNames[major] == null) extensionNames[major] = extensionName;
        }
        requests.incrementAndGet();
        requestNanos.addAndGet(nanos);
    }

    public static void frameUpdate(int width, int height) {
        if (width * height >= bigFrameMinPixels) bigFrames.incrementAndGet();
    }

    static long[][] snapshotRequests() {
        synchronized (reqCount) {
            return new long[][]{reqCount.clone(), reqNanos.clone(), reqErrors.clone()};
        }
    }

    static String requestName(int key) {
        int major = key >> 8, minor = key & 255;
        if (major < 128) return coreName(major);
        String ext = extensionNames[major];
        return (ext != null ? ext : "ext"+major)+":"+minor;
    }

    private static String coreName(int opcode) {
        switch (opcode) {
            case 1: return "CreateWindow"; case 2: return "ChangeWindowAttributes"; case 3: return "GetWindowAttributes";
            case 4: return "DestroyWindow"; case 8: return "MapWindow"; case 12: return "ConfigureWindow";
            case 14: return "GetGeometry"; case 15: return "QueryTree"; case 16: return "InternAtom";
            case 18: return "ChangeProperty"; case 19: return "DeleteProperty"; case 20: return "GetProperty";
            case 22: return "SetSelectionOwner"; case 23: return "GetSelectionOwner"; case 24: return "ConvertSelection";
            case 25: return "SendEvent"; case 26: return "GrabPointer"; case 38: return "QueryPointer";
            case 40: return "TranslateCoordinates"; case 42: return "SetInputFocus"; case 43: return "GetInputFocus";
            case 53: return "CreatePixmap"; case 54: return "FreePixmap"; case 55: return "CreateGC"; case 56: return "ChangeGC";
            case 60: return "FreeGC"; case 62: return "CopyArea"; case 70: return "PolyFillRectangle";
            case 72: return "PutImage"; case 73: return "GetImage"; case 93: return "CreateCursor"; case 98: return "QueryExtension";
            case 101: return "GetKeyboardMapping"; case 119: return "GetModifierMapping"; case 127: return "NoOperation";
            default: return "core"+opcode;
        }
    }
}
