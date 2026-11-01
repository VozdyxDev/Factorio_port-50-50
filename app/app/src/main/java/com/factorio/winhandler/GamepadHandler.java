package com.factorio.winhandler;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.factorio.XServerDisplayActivity;
import com.factorio.core.ArrayUtils;
import com.factorio.inputcontrols.ControlsProfile;
import com.factorio.inputcontrols.ExternalController;
import com.factorio.inputcontrols.GamepadSlot;
import com.factorio.widget.InputControlsView;

import java.io.File;
import java.util.ArrayList;

public class GamepadHandler {
    private static final byte GAMEPAD_MAX_COUNT = 4;
    private final XServerDisplayActivity activity;
    private final GamepadSlot[] gamepadSlots = new GamepadSlot[GAMEPAD_MAX_COUNT];
    private final ArrayList<ExternalController> connectedControllers = new ArrayList<>(GAMEPAD_MAX_COUNT);
    private GamepadPlayerConfig[] gamepadPlayerConfigs;

    public GamepadHandler(XServerDisplayActivity activity) {
        this.activity = activity;
    }

    // ---- Bridge for NATIVE (non-Wine) games: Selkies input interposer sockets ----
    private InterposerGamepadServer bridge;
    private InputManager.InputDeviceListener bridgeDeviceListener;

    public boolean isBridgeActive() {
        return bridge != null;
    }

    public synchronized void startBridge(File socketDir) {
        if (bridge != null) return;
        InterposerGamepadServer server = new InterposerGamepadServer(socketDir, (slot, strongMotor, weakMotor) -> {
            GamepadSlot gamepadSlot = gamepadSlots[slot];
            if (gamepadSlot != null) gamepadSlot.getGamepadVibration().vibrate(strongMotor, weakMotor);
        });
        bridge = server;

        // NOTE: this is called from a background thread (no Looper), so the listener must be given
        // the main looper explicitly, otherwise registerInputDeviceListener throws.
        try {
            bridgeDeviceListener = new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { refreshBridge(); }
                @Override public void onInputDeviceRemoved(int deviceId) { refreshBridge(); }
                @Override public void onInputDeviceChanged(int deviceId) { }
            };
            InputManager inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) inputManager.registerInputDeviceListener(bridgeDeviceListener, new Handler(Looper.getMainLooper()));
        }
        catch (Exception e) {
            Log.e("NativeGamepad", "device listener registration failed: "+e);
            bridgeDeviceListener = null;
        }
        refreshBridge();
    }

    public synchronized void stopBridge() {
        if (bridge == null) return;
        InputManager inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
        try { if (inputManager != null && bridgeDeviceListener != null) inputManager.unregisterInputDeviceListener(bridgeDeviceListener); } catch (Exception e) {}
        bridgeDeviceListener = null;
        bridge.stop();
        bridge = null;
    }

    /** Re-reads the connected pads and creates / removes the virtual device of each slot. */
    public synchronized void refreshBridge() {
        if (bridge == null) return;
        try {
            updateGamepadSlots();
        }
        catch (Exception e) {
            Log.e("NativeGamepad", "updateGamepadSlots failed: "+e);
            return;
        }
        int count = 0;
        for (int i = 0; i < GAMEPAD_MAX_COUNT; i++) {
            boolean present = gamepadSlots[i] != null;
            if (present) count++;
            bridge.setSlotPresent(i, present);
        }
        Log.i("NativeGamepad", "refresh: "+count+" gamepad(s) attached");
    }

    private void updateGamepadSlots() {
        if (gamepadPlayerConfigs == null) {
            gamepadPlayerConfigs = new GamepadPlayerConfig[GAMEPAD_MAX_COUNT];
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) gamepadPlayerConfigs[i] = new GamepadPlayerConfig(""); // default configuration
        }

        InputControlsView controlsView = activity.getInputControlsView();
        ControlsProfile profile = controlsView != null ? controlsView.getProfile() : null;
        boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) gamepadSlots[i] = null;

        synchronized (connectedControllers) {
            ExternalController.updateConnectedControllers(connectedControllers);
        }

        boolean autoAssign = true;
        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
            GamepadPlayerConfig config = gamepadPlayerConfigs[i];
            if (config.name.isEmpty()) continue;
            if (config.mode == GamepadPlayerConfig.MODE_EXTERNAL_CONTROLLER) {
                for (ExternalController controller : connectedControllers) {
                    if (controller.getName().equals(config.name)) {
                        gamepadSlots[i] = controller;
                        autoAssign = false;
                        break;
                    }
                }
            }
            else if (useVirtualGamepad && profile.getName().equals(config.name)) {
                gamepadSlots[i] = profile;
                autoAssign = false;
            }
        }

        if (autoAssign) {
            if (useVirtualGamepad) gamepadSlots[0] = profile;
            int index = 0;
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                if (gamepadSlots[i] != null) continue;
                gamepadSlots[i] = index < connectedControllers.size() ? connectedControllers.get(index) : null;
                index++;
            }
        }
    }

    public void sendGamepadState(final GamepadSlot gamepadSlot) {
        final InterposerGamepadServer bridgeServer = bridge;
        if (bridgeServer != null) {
            int bridgeSlot = ArrayUtils.indexOf(gamepadSlots, gamepadSlot);
            if (bridgeSlot != ArrayUtils.INDEX_NOT_FOUND) bridgeServer.publish(bridgeSlot, gamepadSlot.getGamepadState());
        }
    }

    private ExternalController findConnectedControllerById(int deviceId) {
        synchronized (connectedControllers) {
            for (ExternalController controller : connectedControllers) {
                if (controller.getDeviceId() == deviceId) return controller;
            }

            return null;
        }
    }

    private long lastBridgeRefreshTime = 0;

    private ExternalController getConnectedControllerById(int deviceId) {
        ExternalController controller = findConnectedControllerById(deviceId);
        if (controller == null && bridge != null && ExternalController.isGameController(InputDevice.getDevice(deviceId))) {
            long now = System.currentTimeMillis();
            if (now - lastBridgeRefreshTime > 1000) {
                lastBridgeRefreshTime = now;
                refreshBridge();
                controller = findConnectedControllerById(deviceId);
            }
        }
        return controller;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null) {
            handled = controller.updateStateFromMotionEvent(event);
            if (handled) sendGamepadState(controller);
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = controller.updateStateFromKeyEvent(event);
            }
            else if (action == KeyEvent.ACTION_UP) {
                handled = controller.updateStateFromKeyEvent(event);
            }

            if (handled) sendGamepadState(controller);
        }
        return handled;
    }
}
