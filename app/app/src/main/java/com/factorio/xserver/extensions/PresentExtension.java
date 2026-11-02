package com.factorio.xserver.extensions;

import static com.factorio.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.factorio.renderer.GPUImage;
import com.factorio.renderer.Texture;
import com.factorio.xconnector.XInputStream;
import com.factorio.xconnector.XOutputStream;
import com.factorio.xconnector.XStreamLock;
import com.factorio.core.Bitmask;
import com.factorio.xserver.Drawable;
import com.factorio.xserver.Pixmap;
import com.factorio.xserver.Window;
import com.factorio.xserver.XClient;
import com.factorio.xserver.XLock;
import com.factorio.xserver.XServer;
import com.factorio.xserver.errors.BadImplementation;
import com.factorio.xserver.errors.BadMatch;
import com.factorio.xserver.errors.BadWindow;
import com.factorio.xserver.errors.XRequestError;
import com.factorio.xserver.events.PresentCompleteNotify;
import com.factorio.xserver.events.PresentIdleNotify;

import com.factorio.sysvshm.SysVSharedMemory;
import java.nio.ByteBuffer;
import java.io.IOException;

public class PresentExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 0;
    /** Present 1.2 together with DRI3 1.2 (Zink kopper / Vulkan WSI modifiers path). */
    public static final byte MINOR_VERSION_MODERN = 2;
    private static final int FAKE_INTERVAL = 1000000 / 60;

    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte NOTIFY_MSC = 2;
        private static final byte SELECT_INPUT = 3;
        private static final byte QUERY_CAPABILITIES = 4;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    public PresentExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "Present";
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);
        if (events.size() == 0) return;

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(this, event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        if (events.size() == 0) return;

        if (ust == 0 && msc == 0) {
            ust = System.nanoTime() / 1000;
            msc = ust / FAKE_INTERVAL;
        }

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(this, event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();
        int minor = xServer.activity != null && xServer.activity.useModernDri3() ? MINOR_VERSION_MODERN : MINOR_VERSION;
        if (clientMajor == MAJOR_VERSION && clientMinor < minor) minor = clientMinor;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(minor);
            outputStream.writePad(16);
        }
    }

    // NotifyMSC (2): there is no real vblank counter; answer at once with a CompleteNotify of kind MSC_NOTIFY
    private void notifyMSC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        sendCompleteNotify(window, serial, Kind.MSC_NOTIFY, Mode.COPY, 0, 0);
    }

    // QueryCapabilities (4): no async / flip / fence capabilities, every present is a copy
    private void queryCapabilities(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4); // target
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0); // capabilities
            outputStream.writePad(20);
        }
    }

    private static final int SAMPLES = 128;
    private int presentCount = 0;
    private int skippedBlank = 0;
    private int blankRun = 0;

    /** Counts non-zero 32-bit pixels among SAMPLES evenly spaced positions of the buffer (diagnostics and blank-frame guard). */
    private static int sampleNonZero(ByteBuffer data) {
        if (data == null) return SAMPLES;   // unknown storage: never skip
        int words = data.capacity() / 4;
        if (words <= 0) return SAMPLES;
        int nonZero = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long idx = (long)i * (words - 1) / (SAMPLES - 1);
            if (data.getInt((int)(idx * 4)) != 0) nonZero++;
        }
        return nonZero;
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);

        Drawable content = window.getContent();
        if (pixmap != null && content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        long perfWait = com.factorio.core.PerfStats.now();
        synchronized (content.renderLock) {
            if (perfWait != 0) com.factorio.core.PerfStats.presentRenderLockWaitNanos.addAndGet(System.nanoTime() - perfWait);
            if (pixmap != null) {
                Drawable src = pixmap.drawable;
                int syncFd = xServer.activity == null || xServer.activity.isPresentDmabufSync() ? src.syncFd : -1;
                long perfSync = com.factorio.core.PerfStats.now();
                if (syncFd >= 0) SysVSharedMemory.dmabufSync(syncFd, true);   // invalidate CPU cache before reading GPU-written memory
                if (perfSync != 0) com.factorio.core.PerfStats.presentSyncNanos.addAndGet(System.nanoTime() - perfSync);

                int nonZero = sampleNonZero(src.getData());
                presentCount++;

                // dma-buf (native OpenGL) only: a buffer that is still completely zero was not rendered yet.
                // Keep the previous picture for up to 3 such frames in a row instead of flashing black; a real black
                // screen (longer than that) is still shown.
                if (nonZero > 0) blankRun = 0; else blankRun++;
                boolean skip = syncFd >= 0 && nonZero == 0 && blankRun <= 3;
                if (skip) {
                    skippedBlank++;
                    if (perfSync != 0) com.factorio.core.PerfStats.presentSkipped.incrementAndGet();
                }
                else {
                    long perfCopy = com.factorio.core.PerfStats.now();
                    content.copyArea((short)0, (short)0, xOff, yOff, src.width, src.height, src);
                    if (perfCopy != 0) com.factorio.core.PerfStats.presentCopyNanos.addAndGet(System.nanoTime() - perfCopy);
                }

                long perfSyncEnd = com.factorio.core.PerfStats.now();
                if (syncFd >= 0) SysVSharedMemory.dmabufSync(syncFd, false);
                if (perfSyncEnd != 0) com.factorio.core.PerfStats.presentSyncNanos.addAndGet(System.nanoTime() - perfSyncEnd);
                sendIdleNotify(window, pixmap, serial, idleFence);
            }
            else content.forceUpdate();
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, 0, 0);
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Drawable content = window.getContent();
        final Texture texture = content.getTexture();

        if (!(texture instanceof GPUImage)) {
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
            content.setTexture(new GPUImage(content));
        }

        if (eventId > 0) {
            synchronized (events) {
                Event event = events.get(eventId);
                if (event != null) {
                    if (event.window != window || event.client != client) throw new BadMatch();

                    if (!mask.isEmpty()) {
                        event.mask = mask;
                    }
                    else events.remove(eventId);
                }
                else {
                    event = new Event();
                    event.id = eventId;
                    event.window = window;
                    event.client = client;
                    event.mask = mask;
                    events.put(eventId, event);
                }
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = (SyncExtension)xServer.getExtensionByName("SYNC");

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP: {
                long perfStart = com.factorio.core.PerfStats.now();
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    if (perfStart != 0) com.factorio.core.PerfStats.presentLockWaitNanos.addAndGet(System.nanoTime() - perfStart);
                    presentPixmap(client, inputStream, outputStream);
                }
                if (perfStart != 0) {
                    com.factorio.core.PerfStats.presents.incrementAndGet();
                    com.factorio.core.PerfStats.presentNanos.addAndGet(System.nanoTime() - perfStart);
                }
                break;
            }
            case ClientOpcodes.NOTIFY_MSC:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    notifyMSC(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.QUERY_CAPABILITIES:
                queryCapabilities(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
