package com.factorio.xserver;

import androidx.collection.ArrayMap;


import java.util.Map;

public abstract class DesktopHelper {
    public static void attachTo(final XServer xServer) {
        setupXResources(xServer);

        xServer.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener() {
            @Override
            public void onPointerButtonPress(Pointer.Button button) {
                updateFocusedWindow(xServer);
            }
        });

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                focusDbg(xServer, "map " + describe(window) + " applicationWindow=" + window.isApplicationWindow() + " topLevel=" + (window.getParent() == xServer.windowManager.rootWindow));
                setFocusedWindow(xServer, window);
            }
        });
    }

    private static int focusLogCount = 0;

    private static void focusDbg(XServer xServer, String message) {
    }

    private static String describe(Window w) {
        if (w == null) return "null";
        return "id=" + w.id + " name='" + w.getName() + "' class='" + w.getClassName() + "' mapped=" + w.attributes.isMapped() + " " + w.getWidth() + "x" + w.getHeight();
    }

    private static void updateFocusedWindow(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window focusedWindow = xServer.windowManager.getFocusedWindow();
            Window child = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
            if (child == null && focusedWindow != xServer.windowManager.rootWindow) {
                xServer.windowManager.setFocus(xServer.windowManager.rootWindow, WindowManager.FocusRevertTo.NONE);
            }
            else if (child != null && child != focusedWindow) {
                setFocusedWindow(xServer, child);
            }
        }
    }

    private static void setFocusedWindow(XServer xServer, Window window) {
        // (Wine's winhandler.exe used to raise the window here; a native game needs only the X focus.)
        if (window.isApplicationWindow()) {
            boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
            xServer.windowManager.setFocus(window, parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);
        }
        else if (window.isDialogBox()) {
            // nothing to do without winhandler.exe
        }
        else if (window.getParent() == xServer.windowManager.rootWindow && window.isRenderable() && !window.getClassName().isEmpty()) {
            // Fallback: a top-level window with a class that does not satisfy isApplicationWindow() (no WM_NAME / window group yet)
            // would never get the keyboard focus, and the game would ignore the keys. Only used while nothing useful has the focus.
            Window current = xServer.windowManager.getFocusedWindow();
            if (current == null || current == xServer.windowManager.rootWindow) {
                focusDbg(xServer, "fallback focus -> " + describe(window));
                xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT);
            }
        }
        else focusDbg(xServer, "not focusable: " + describe(window));
    }

    private static void setupXResources(XServer xServer) {
        ArrayMap<String, String> values = new ArrayMap<>();
        values.put("size", "20");
        values.put("theme", "dmz");
        values.put("theme_core", "true");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("Xcursor")
              .append('.')
              .append(entry.getKey())
              .append(':')
              .append('\t')
              .append(entry.getValue())
              .append('\n');
        }

        byte[] data = sb.toString().getBytes(XServer.LATIN1_CHARSET);
        xServer.windowManager.rootWindow.modifyProperty(Atom.RESOURCE_MANAGER, Atom.STRING, Property.Format.BYTE_ARRAY, Property.Mode.APPEND, data);
    }
}