package com.factorio.xserver.extensions;

import static com.factorio.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseBooleanArray;

import com.factorio.xconnector.XInputStream;
import com.factorio.xconnector.XOutputStream;
import com.factorio.xconnector.XStreamLock;
import com.factorio.xserver.XClient;
import com.factorio.xserver.XServer;
import com.factorio.xserver.errors.BadFence;
import com.factorio.xserver.errors.BadIdChoice;
import com.factorio.xserver.errors.BadImplementation;
import com.factorio.xserver.errors.BadMatch;
import com.factorio.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension extends Extension {
    private final SparseBooleanArray fences = new SparseBooleanArray();
    // fences created by DRI3 FenceFromFD: an xshmfence in shared memory that the client waits on
    private final android.util.SparseArray<java.nio.ByteBuffer> shmFences = new android.util.SparseArray<>();
    private static final int SHM_FENCE_MAP_SIZE = 4096;

    private static abstract class ClientOpcodes {
        private static final byte INITIALIZE = 0;
        private static final byte LIST_SYSTEM_COUNTERS = 1;
        private static final byte CREATE_COUNTER = 2;
        private static final byte SET_COUNTER = 3;
        private static final byte CHANGE_COUNTER = 4;
        private static final byte QUERY_COUNTER = 5;
        private static final byte DESTROY_COUNTER = 6;
        private static final byte AWAIT = 7;
        private static final byte CREATE_ALARM = 8;
        private static final byte CHANGE_ALARM = 9;
        private static final byte DESTROY_ALARM = 11;
        private static final byte SET_PRIORITY = 12;
        private static final byte GET_PRIORITY = 13;
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    public SyncExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) fences.put(id, true);
            java.nio.ByteBuffer shm = shmFences.get(id);
            if (shm != null) com.factorio.sysvshm.SysVSharedMemory.xshmfenceTrigger(shm);
        }
    }

    /** DRI3 FenceFromFD: a fence backed by the client's xshmfence (fd of its shared memory). */
    public void createShmFence(int id, int fd, boolean initiallyTriggered) throws XRequestError {
        java.nio.ByteBuffer shm = fd >= 0 ? com.factorio.sysvshm.SysVSharedMemory.mapSHMSegment(fd, SHM_FENCE_MAP_SIZE, 0, false) : null;
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) {
                if (shm != null) com.factorio.sysvshm.SysVSharedMemory.unmapSHMSegment(shm, SHM_FENCE_MAP_SIZE);
                throw new BadIdChoice(id);
            }
            fences.put(id, initiallyTriggered);
            if (shm != null) {
                shmFences.put(id, shm);
                if (initiallyTriggered) com.factorio.sysvshm.SysVSharedMemory.xshmfenceTrigger(shm);
            }
        }
    }

    private void releaseShmFence(int id) {
        java.nio.ByteBuffer shm = shmFences.get(id);
        if (shm != null) {
            shmFences.remove(id);
            com.factorio.sysvshm.SysVSharedMemory.unmapSHMSegment(shm, SHM_FENCE_MAP_SIZE);
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            inputStream.skip(4);
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);

            fences.put(id, initiallyTriggered);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.put(id, true);
            java.nio.ByteBuffer shm = shmFences.get(id);
            if (shm != null) com.factorio.sysvshm.SysVSharedMemory.xshmfenceTrigger(shm);
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            boolean triggered = fences.get(id);
            if (!triggered) throw new BadMatch();

            fences.put(id, false);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.delete(id);
            releaseShmFence(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    anyTriggered = fences.get(id);
                    if (anyTriggered) break;
                }

                Thread.yield();
            }
            while (!anyTriggered);
        }
    }

    private void initialize(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)3);
            outputStream.writeByte((byte)1);
            outputStream.writePad(22);
        }
    }

    private void listSystemCounters(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writePad(20);
        }
    }

    private void queryCounter(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.skipRequest();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void getPriority(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.skipRequest();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writePad(20);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.INITIALIZE:
                initialize(client, inputStream, outputStream);
                break;
            case ClientOpcodes.LIST_SYSTEM_COUNTERS:
                listSystemCounters(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_COUNTER:
                queryCounter(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_PRIORITY:
                getPriority(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CREATE_COUNTER:
            case ClientOpcodes.SET_COUNTER:
            case ClientOpcodes.CHANGE_COUNTER:
            case ClientOpcodes.DESTROY_COUNTER:
            case ClientOpcodes.AWAIT:
            case ClientOpcodes.CREATE_ALARM:
            case ClientOpcodes.CHANGE_ALARM:
            case ClientOpcodes.DESTROY_ALARM:
            case ClientOpcodes.SET_PRIORITY:
                // Counters and alarms are not implemented: accept the request and ignore it.
                client.skipRequest();
                break;
            default:
                throw new BadImplementation();
        }
    }
}
