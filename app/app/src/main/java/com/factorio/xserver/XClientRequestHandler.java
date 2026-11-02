package com.factorio.xserver;

import com.factorio.xconnector.ConnectedClient;
import com.factorio.xconnector.RequestHandler;
import com.factorio.xconnector.XInputStream;
import com.factorio.xconnector.XOutputStream;
import com.factorio.xconnector.XStreamLock;
import com.factorio.xserver.errors.BadImplementation;
import com.factorio.xserver.errors.XRequestError;
import com.factorio.xserver.extensions.Extension;
import com.factorio.xserver.requests.AtomRequests;
import com.factorio.xserver.requests.CursorRequests;
import com.factorio.xserver.requests.DrawRequests;
import com.factorio.xserver.requests.ExtensionRequests;
import com.factorio.xserver.requests.FontRequests;
import com.factorio.xserver.requests.GrabRequests;
import com.factorio.xserver.requests.GraphicsContextRequests;
import com.factorio.xserver.requests.KeyboardRequests;
import com.factorio.xserver.requests.PixmapRequests;
import com.factorio.xserver.requests.SelectionRequests;
import com.factorio.xserver.requests.WindowRequests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class XClientRequestHandler implements RequestHandler {
    public static final byte RESPONSE_CODE_ERROR = 0;
    public static final byte RESPONSE_CODE_SUCCESS = 1;
    public static final int MAX_REQUEST_LENGTH = 65535;

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        XClient xClient = (XClient)client;
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (xClient.isAuthenticated()) {
            return handleNormalRequest(xClient, inputStream, outputStream);
        }
        else return handleAuthRequest(xClient, inputStream, outputStream);
    }

    private void sendServerInformation(XClient client, XOutputStream outputStream) throws IOException {
        short vendorNameLength = (short)XServer.VENDOR_NAME.length();
        byte pixmapFormatCount = (byte)client.xServer.pixmapManager.supportedPixmapFormats.length;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(XServer.VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeInt(1);
            outputStream.writeInt(client.resourceIDBase);
            outputStream.writeInt(client.xServer.resourceIDs.idMask);
            outputStream.writeInt(256);
            outputStream.writeShort(vendorNameLength);
            outputStream.writeShort((short)MAX_REQUEST_LENGTH);
            outputStream.writeByte((byte)1);
            outputStream.writeByte(pixmapFormatCount);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)Keyboard.MIN_KEYCODE);
            outputStream.writeByte((byte)Keyboard.MAX_KEYCODE);
            outputStream.writeInt(0);
            outputStream.writeString8(XServer.VENDOR_NAME);

            for (PixmapFormat pixmapFormat : client.xServer.pixmapManager.supportedPixmapFormats) {
                outputStream.writeByte(pixmapFormat.depth);
                outputStream.writeByte(pixmapFormat.bitsPerPixel);
                outputStream.writeByte(pixmapFormat.scanlinePad);
                outputStream.writePad(5);
            }

            Visual rootVisual = client.xServer.windowManager.rootWindow.getContent().visual;

            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(0);
            outputStream.writeInt(0xffffff);
            outputStream.writeInt(0x000000);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.getAllEventMasks().getBits());
            outputStream.writeShort(client.xServer.screenInfo.width);
            outputStream.writeShort(client.xServer.screenInfo.height);
            outputStream.writeShort(client.xServer.screenInfo.getWidthInMillimeters());
            outputStream.writeShort(client.xServer.screenInfo.getHeightInMillimeters());
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeInt(rootVisual.id);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte(rootVisual.depth);
            outputStream.writeByte((byte)client.xServer.pixmapManager.supportedVisuals.length);

            for (Visual visual : client.xServer.pixmapManager.supportedVisuals) {
                outputStream.writeByte(visual.depth);
                outputStream.writeByte((byte)0);
                outputStream.writeShort((short)(visual.displayable ? 1 : 0));
                outputStream.writeInt(0);

                if (visual.displayable) {
                    outputStream.writeInt(visual.id);
                    outputStream.writeByte((byte)visual.type.ordinal());
                    outputStream.writeByte(visual.bitsPerRGBValue);
                    outputStream.writeShort(visual.colormapEntries);
                    outputStream.writeInt(visual.redMask);
                    outputStream.writeInt(visual.greenMask);
                    outputStream.writeInt(visual.blueMask);
                    outputStream.writeInt(0);
                }
            }

            short additionalDataLength = (short)((outputStream.length() - 8) / 4);
            outputStream.writeAt(6, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(additionalDataLength).array());
        }
    }

    private boolean handleAuthRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 12) return false;
        inputStream.skip(2);

        short majorVersion = inputStream.readShort();
        if (majorVersion != 11) throw new UnsupportedOperationException("Unsupported major X protocol version "+majorVersion+".");

        inputStream.skip(2);
        short nameLength = inputStream.readShort();
        short dataLength = inputStream.readShort();
        inputStream.skip(2);

        if (nameLength > 0) inputStream.readString8(nameLength);
        if (dataLength > 0) inputStream.readString8(dataLength);

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            sendServerInformation(client, outputStream);
        }

        client.setAuthenticated(true);
        return true;
    }

    private boolean handleNormalRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 4) return false;
        byte opcode = inputStream.readByte();
        byte requestData = inputStream.readByte();

        int requestLength = inputStream.readUnsignedShort();
        if (requestLength != 0) {
            requestLength = requestLength * 4 - 4;
        }
        else if (inputStream.available() < 4) {
            return false;
        }
        else requestLength = inputStream.readInt() * 4 - 8;
        if (inputStream.available() < requestLength) return false;

        client.generateSequenceNumber();
        client.setRequestData(requestData);
        client.setRequestLength(requestLength);
        client.traceRequest(opcode & 255, requestData & 255, requestLength);

        final long perfStart = com.factorio.core.PerfStats.now();
        boolean perfError = false;
        String perfExtension = null;
        try {
            if (opcode < 0) {
                Extension extension = client.xServer.getExtension(opcode);
                if (perfStart != 0 && extension != null) perfExtension = extension.getName();
                // Unknown extension: report an error instead of leaving the request unread and unanswered
                // (the client would wait on the socket forever).
                if (extension == null) throw new BadImplementation();
                extension.handleRequest(client, inputStream, outputStream);

                // Safety net: a handler that read less than the whole request body (e.g. GLXCreateContext
                // is 24 bytes but was parsed as 21) would leave the rest in the stream, and the next request
                // would be parsed from the middle of it, so the server stops answering that client.
                int unread = client.getRemainingRequestLength();
                if (unread > 0) {
                    inputStream.skip(unread);
                }
            }
            else {
                switch (opcode) {
                    case ClientOpcodes.CREATE_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                            WindowRequests.createWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CHANGE_WINDOW_ATTRIBUTES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            WindowRequests.changeWindowAttributes(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_WINDOW_ATTRIBUTES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getWindowAttributes(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DESTROY_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.destroyWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DESTROY_SUB_WINDOWS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.destroySubWindows(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.REPARENT_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.reparentWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.MAP_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.mapWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.MAP_SUB_WINDOWS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.mapSubWindows(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.UNMAP_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.unmapWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CONFIGURE_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.configureWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_GEOMETRY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            WindowRequests.getGeometry(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_TREE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.queryTree(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.INTERN_ATOM:
                        AtomRequests.internAtom(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_ATOM_NAME:
                        AtomRequests.getAtomName(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.CHANGE_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.changeProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DELETE_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.deleteProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_SELECTION_OWNER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            SelectionRequests.setSelectionOwner(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_SELECTION_OWNER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            SelectionRequests.getSelectionOwner(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SEND_EVENT:
                        try (XLock lock = client.xServer.lockAll()) {
                            WindowRequests.sendEvent(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GRAB_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                            GrabRequests.grabPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.UNGRAB_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            GrabRequests.ungrabPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.queryPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.TRANSLATE_COORDINATES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.translateCoordinates(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.WARP_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.warpPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_INPUT_FOCUS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.setInputFocus(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_INPUT_FOCUS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getInputFocus(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.OPEN_FONT:
                        FontRequests.openFont(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.LIST_FONTS:
                        FontRequests.listFonts(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.CREATE_PIXMAP:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            PixmapRequests.createPixmap(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.FREE_PIXMAP:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            PixmapRequests.freePixmap(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.createGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CHANGE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.changeGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_DASHES:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.SET_CLIP_RECTANGLES:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.freeGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CLEAR_AREA:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            DrawRequests.clearArea(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.COPY_AREA:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.copyArea(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_LINE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyLine(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_SEGMENT:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.POLY_RECTANGLE:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.POLY_FILL_RECTANGLE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyFillRectangle(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.PUT_IMAGE:
                        {
                            long perfPut = com.factorio.core.PerfStats.now();
                            int perfBytes = client.getRemainingRequestLength();
                            try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                                DrawRequests.putImage(client, inputStream, outputStream);
                            }
                            if (perfPut != 0) {
                                com.factorio.core.PerfStats.putImages.incrementAndGet();
                                com.factorio.core.PerfStats.putImageNanos.addAndGet(System.nanoTime() - perfPut);
                                com.factorio.core.PerfStats.putImageBytes.addAndGet(perfBytes);
                            }
                        }
                        break;
                    case ClientOpcodes.GET_IMAGE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            DrawRequests.getImage(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_COLORMAP:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_COLORMAP:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.CREATE_CURSOR:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            CursorRequests.createCursor(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_GLYPH_CURSOR:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_CURSOR:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            CursorRequests.freeCursor(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_EXTENSION:
                        ExtensionRequests.queryExtension(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_KEYBOARD_MAPPING:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.INPUT_DEVICE)) {
                            KeyboardRequests.getKeyboardMapping(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.BELL:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.SET_SCREEN_SAVER:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.GET_SCREEN_SAVER:
                        WindowRequests.getScreenSaver(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.FORCE_SCREEN_SAVER:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.GET_POINTER_MAPPING:
                        CursorRequests.getPointerMapping(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_MODIFIER_MAPPING:
                        KeyboardRequests.getModifierMapping(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.NO_OPERATION:
                        client.skipRequest();
                        break;
                    // Requests without a reply that native Xlib/SDL programs may send. Accepted and ignored.
                    case 6: case 11: case 13: case 28: case 29: case 30: case 32: case 33: case 34: case 35:
                    case 36: case 37: case 46: case 51: case 57: case 63: case 64: case 68: case 69: case 71:
                    case 74: case 75: case 76: case 77: case 80: case 81: case 82: case 88: case 89: case 90:
                    case 96: case 100: case 102: case 105: case 109: case 111: case 112: case 114:
                        client.skipRequest();
                        break;
                    case 31: // GrabKeyboard -> status Success
                        client.skipRequest();
                        try (XStreamLock lock = outputStream.lock()) {
                            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                            outputStream.writeByte((byte)0);
                            outputStream.writeShort(client.getSequenceNumber());
                            outputStream.writeInt(0);
                            outputStream.writePad(24);
                        }
                        break;
                    case 44: // QueryKeymap -> no keys pressed
                        client.skipRequest();
                        try (XStreamLock lock = outputStream.lock()) {
                            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                            outputStream.writeByte((byte)0);
                            outputStream.writeShort(client.getSequenceNumber());
                            outputStream.writeInt(2);
                            outputStream.writePad(32);
                        }
                        break;
                    case 97: { // QueryBestSize -> echo the requested size
                        inputStream.skip(4);
                        short bestWidth = inputStream.readShort();
                        short bestHeight = inputStream.readShort();
                        client.skipRequest();
                        try (XStreamLock lock = outputStream.lock()) {
                            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                            outputStream.writeByte((byte)0);
                            outputStream.writeShort(client.getSequenceNumber());
                            outputStream.writeInt(0);
                            outputStream.writeShort(bestWidth);
                            outputStream.writeShort(bestHeight);
                            outputStream.writePad(20);
                        }
                        break;
                    }
                    case 103: // GetKeyboardControl
                        client.skipRequest();
                        try (XStreamLock lock = outputStream.lock()) {
                            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                            outputStream.writeByte((byte)1);
                            outputStream.writeShort(client.getSequenceNumber());
                            outputStream.writeInt(5);
                            outputStream.writeInt(0);
                            outputStream.writeByte((byte)0);
                            outputStream.writeByte((byte)50);
                            outputStream.writeShort((short)400);
                            outputStream.writeShort((short)100);
                            outputStream.writePad(2);
                            outputStream.writePad(32);
                        }
                        break;
                    case 106: // GetPointerControl
                        client.skipRequest();
                        try (XStreamLock lock = outputStream.lock()) {
                            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                            outputStream.writeByte((byte)0);
                            outputStream.writeShort(client.getSequenceNumber());
                            outputStream.writeInt(0);
                            outputStream.writeShort((short)2);
                            outputStream.writeShort((short)1);
                            outputStream.writeShort((short)4);
                            outputStream.writePad(18);
                        }
                        break;
                    default:
                        // Unknown request: report it to the client (and the debug log) instead of
                        // leaving the request unread, which would desynchronize the connection.
                        throw new BadImplementation();
                }
            }
        }
        catch (XRequestError e) {
            perfError = true;
            client.skipRequest();
            e.sendError(client, opcode);
        }
        finally {
            if (perfStart != 0) com.factorio.core.PerfStats.request(opcode & 255, requestData & 255, perfExtension, System.nanoTime() - perfStart, perfError);
        }

        return true;
    }
}
