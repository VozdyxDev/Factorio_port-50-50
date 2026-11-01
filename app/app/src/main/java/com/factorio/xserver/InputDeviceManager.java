package com.factorio.xserver;

import com.factorio.core.Bitmask;
import com.factorio.renderer.FullscreenTransformation;
import com.factorio.xserver.events.ButtonPress;
import com.factorio.xserver.events.ButtonRelease;
import com.factorio.xserver.events.EnterNotify;
import com.factorio.xserver.events.Event;
import com.factorio.xserver.events.KeyPress;
import com.factorio.xserver.events.KeyRelease;
import com.factorio.xserver.events.LeaveNotify;
import com.factorio.xserver.events.MappingNotify;
import com.factorio.xserver.events.MotionNotify;
import com.factorio.xserver.events.PointerWindowEvent;

public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private Window pointWindow;
    private final XServer xServer;

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);
    }

    @Override
    public void onMapWindow(Window window) {
        updatePointWindowAndNotify();
    }

    @Override
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY(), true);
        this.pointWindow = pointWindow != null ? pointWindow : xServer.windowManager.rootWindow;
    }

    public Window getPointWindow() {
        return pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, xServer.grabManager.getClient());
            }
            else if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
            }
            else if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        if (windowA == windowB) return;

        boolean sameScreenAndFocus = windowB.isAncestorOf(xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB = PointerWindowEvent.Detail.NONLINEAR;

        // X11 protocol: pointer moves from A to its descendant B -> Leave(A, Inferior), Enter(B, Ancestor);
        // from A to its ancestor B -> Leave(A, Ancestor), Enter(B, Inferior). It was reversed, and SDL ignores
        // EnterNotify with detail=Inferior, so the game never got the mouse focus.
        if (windowA.isAncestorOf(windowB)) {
            detailA = PointerWindowEvent.Detail.INFERIOR;
            detailB = PointerWindowEvent.Detail.ANCESTOR;
        }
        else if (windowB.isAncestorOf(windowA)) {
            detailA = PointerWindowEvent.Detail.ANCESTOR;
            detailB = PointerWindowEvent.Detail.INFERIOR;
        }

        Bitmask keyButMask = getKeyButMask();

        short xA = xServer.pointer.getX();
        short yA = xServer.pointer.getY();
        FullscreenTransformation fullscreenTransformationB = windowB.getFullscreenTransformation();
        if (fullscreenTransformationB != null) {
            short[] transformedPoint = fullscreenTransformationB.transformPointerCoords(xA, yA);
            xA = transformedPoint[0];
            yA = transformedPoint[1];
        }
        short[] localPointA = windowA.rootPointToLocal(xA, yA);

        short xB = xServer.pointer.getX();
        short yB = xServer.pointer.getY();
        FullscreenTransformation fullscreenTransformationA = windowA.getFullscreenTransformation();
        if (fullscreenTransformationA != null) {
            short[] transformedPoint = fullscreenTransformationA.transformPointerCoords(xB, yB);
            xB = transformedPoint[0];
            yB = transformedPoint[1];
        }
        short[] localPointB = windowB.rootPointToLocal(xB, yB);

        sendEvent(windowA, Event.LEAVE_WINDOW, new LeaveNotify(detailA, xServer.windowManager.rootWindow, windowA, null, xA, yA, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, Event.ENTER_WINDOW, new EnterNotify(detailB, xServer.windowManager.rootWindow, windowB, null, xB, yB, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
    }

    private int inputLogCount = 0;
    private int inputOkCount = 0;
    private int keyOkCount = 0;

    private void inputDbg(String message) {
    }

    private int motionSent = 0;
    private int motionDropped = 0;
    private boolean maskLogged = false;

    private static String listens(Window w) {
        if (w == null) return "null";
        StringBuilder sb = new StringBuilder();
        int[] ids = {Event.KEY_PRESS, Event.KEY_RELEASE, Event.BUTTON_PRESS, Event.BUTTON_RELEASE, Event.ENTER_WINDOW, Event.LEAVE_WINDOW,
            Event.POINTER_MOTION, Event.BUTTON_MOTION, Event.FOCUS_CHANGE, Event.STRUCTURE_NOTIFY, Event.EXPOSURE, Event.PROPERTY_CHANGE};
        String[] names = {"KeyPress", "KeyRelease", "ButtonPress", "ButtonRelease", "Enter", "Leave", "PointerMotion", "ButtonMotion", "FocusChange", "StructureNotify", "Exposure", "PropertyChange"};
        for (int i = 0; i < ids.length; i++) if (w.hasEventListenerFor(ids[i])) sb.append(names[i]).append(' ');
        return sb.toString().trim();
    }

    /** Recomputes the window under the pointer and, like a real X server, sends Leave/EnterNotify when it changed.
     *  (SDL sets its mouse focus from EnterNotify; this server used to send them only for pointer grabs.) */
    private void updatePointWindowAndNotify() {
        Window previous = pointWindow;
        updatePointWindow();
        if (previous != null && previous != pointWindow) {
            try {
                inputDbg("pointer window changed: " + describe(previous) + " -> " + describe(pointWindow) + " (listens: " + listens(pointWindow) + ")");
                sendEnterLeaveNotify(previous, pointWindow, PointerWindowEvent.Mode.NORMAL);
            }
            catch (Throwable t) {
                inputDbg("Enter/Leave failed: " + t);
            }
        }
    }

    private static String describe(Window w) {
        if (w == null) return "null";
        return "id=" + w.id + " name='" + w.getName() + "' class='" + w.getClassName() + "' mapped=" + w.attributes.isMapped() + " " + w.getWidth() + "x" + w.getHeight();
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        {
            Window grabWindow = xServer.grabManager.getWindow();
            if (grabWindow == null) {
                grabWindow = pointWindow.getAncestorWithEventId(Event.BUTTON_PRESS);
                if (grabWindow != null) xServer.grabManager.activatePointerGrab(grabWindow);
            }

            if (grabWindow == null) inputDbg("button " + button + " dropped: no window under the pointer (" + describe(pointWindow) + ") or its parents listens for ButtonPress");
            else if (!grabWindow.attributes.isEnabled()) inputDbg("button " + button + " dropped: window is disabled (" + describe(grabWindow) + ")");
            else if (inputOkCount++ < 20) {
                inputDbg("button " + button + " delivered to " + describe(grabWindow) + " (listens: " + listens(grabWindow) + ")");
                if (!maskLogged) {
                    maskLogged = true;
                    inputDbg("focus window: " + describe(xServer.windowManager.getFocusedWindow()) + " (listens: " + listens(xServer.windowManager.getFocusedWindow()) + ")");
                }
            }

            if (grabWindow != null && grabWindow.attributes.isEnabled()) {
                Bitmask eventMask = createPointerEventMask();
                eventMask.unset(button.flag());

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();

                FullscreenTransformation fullscreenTransformation = grabWindow.getFullscreenTransformation();
                if (fullscreenTransformation != null) {
                    short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                    x = transformedPoint[0];
                    y = transformedPoint[1];
                }

                short[] localPoint = grabWindow.rootPointToLocal(x, y);
                Window child = grabWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                grabWindow.sendEvent(Event.BUTTON_PRESS, new ButtonPress(button.code(), xServer.windowManager.rootWindow, grabWindow, child, x, y, localPoint[0], localPoint[1], eventMask));
            }
        }
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        {
            Bitmask eventMask = createPointerEventMask();
            Window grabWindow = xServer.grabManager.getWindow();
            Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

            if (grabWindow != null || window != null) {
                Window eventWindow = window != null ? window : grabWindow;

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();

                FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
                if (fullscreenTransformation != null) {
                    short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                    x = transformedPoint[0];
                    y = transformedPoint[1];
                }

                short[] localPoint = eventWindow.rootPointToLocal(x, y);
                Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                ButtonRelease buttonRelease = new ButtonRelease(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], eventMask);
                sendEvent(window, eventMask, buttonRelease);
            }

            if (xServer.pointer.getButtonMask().isEmpty() && xServer.grabManager.isReleaseWithButtons()) {
                xServer.grabManager.deactivatePointerGrab();
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        updatePointWindowAndNotify();
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = xServer.grabManager.getWindow();
        Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

        if (grabWindow == null && window == null) {
            if (motionDropped++ < 10) inputDbg("motion dropped: nobody listens for PointerMotion under the pointer (" + describe(pointWindow) + ", listens: " + listens(pointWindow) + ")");
        }
        else {
            motionSent++;
            if (motionSent <= 10 || motionSent % 200 == 0) inputDbg("motion #" + motionSent + " sent to " + describe(window != null ? window : grabWindow) + " (dropped so far: " + motionDropped + ")");
        }

        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;

            FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
            if (fullscreenTransformation != null) {
                short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                x = transformedPoint[0];
                y = transformedPoint[1];
            }

            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
            sendEvent(window, eventMask, new MotionNotify(false, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override
    public void onKeyPress(byte keycode, int keysym) {
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) { inputDbg("key " + (keycode & 0xff) + " dropped: no window has the input focus"); return; }
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_PRESS, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_PRESS)) { inputDbg("key " + (keycode & 0xff) + " dropped: focused window listens for no KeyPress (" + describe(focusedWindow) + ")"); return; }
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) { inputDbg("key " + (keycode & 0xff) + " dropped: window is disabled (" + describe(eventWindow) + ")"); return; }
        if (keyOkCount++ < 20) inputDbg("key " + (keycode & 0xff) + " delivered to " + describe(eventWindow));

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
        if (fullscreenTransformation != null) {
            short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
            x = transformedPoint[0];
            y = transformedPoint[1];
        }

        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        if (keysym != 0 && !xServer.keyboard.hasKeysym(keycode, keysym)) {
            xServer.keyboard.setKeysyms(keycode, keysym, keysym);
            eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
        }

        eventWindow.sendEvent(Event.KEY_PRESS, new KeyPress(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    @Override
    public void onKeyRelease(byte keycode) {
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_RELEASE, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_RELEASE)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
        if (fullscreenTransformation != null) {
            short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
            x = transformedPoint[0];
            y = transformedPoint[1];
        }

        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        eventWindow.sendEvent(Event.KEY_RELEASE, new KeyRelease(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(Event.POINTER_MOTION);

        Bitmask buttonMask = xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(Event.BUTTON_MOTION);

            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(Event.BUTTON1_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(Event.BUTTON2_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(Event.BUTTON3_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(Event.BUTTON4_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(Event.BUTTON5_MOTION);
            }
        }
        return eventMask;
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(xServer.pointer.getButtonMask());
        keyButMask.join(xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
