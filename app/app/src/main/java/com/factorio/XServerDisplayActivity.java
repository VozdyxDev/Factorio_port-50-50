package com.factorio;

import com.factorio.core.AppExecutors;
import com.factorio.core.GameArchive;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.factorio.alsaserver.ALSAClient;
import com.factorio.container.AudioDrivers;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.contentdialog.AudioDriverConfigDialog;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.contentdialog.DebugDialog;
import com.factorio.contentdialog.ScreenEffectDialog;
import com.factorio.contentdialog.TurnipConfigDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.DefaultVersion;
import com.factorio.core.EnvVars;
import com.factorio.core.DriverReport;
import com.factorio.core.FileUtils;
import com.factorio.core.GeneralComponents;
import com.factorio.core.KeyValueSet;
import com.factorio.core.LocaleHelper;
import com.factorio.core.PreloaderDialog;
import com.factorio.core.ProcessHelper;
import com.factorio.core.TarCompressorUtils;
import com.factorio.inputcontrols.ControlsProfile;
import com.factorio.inputcontrols.ExternalController;
import com.factorio.inputcontrols.InputControlsManager;
import com.factorio.math.Mathf;
import com.factorio.renderer.GLRenderer;
import com.factorio.widget.InputControlsView;
import com.factorio.widget.LogView;
import com.factorio.widget.MagnifierView;
import com.factorio.widget.TouchpadView;
import com.factorio.widget.XServerView;
import com.factorio.winhandler.GamepadHandler;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xenvironment.RootFS;
import com.factorio.xenvironment.XEnvironment;
import com.factorio.xenvironment.components.ALSAServerComponent;
import com.factorio.xenvironment.components.GuestProgramLauncherComponent;
import com.factorio.xenvironment.components.NetworkInfoUpdateComponent;
import com.factorio.xenvironment.components.PulseAudioComponent;
import com.factorio.xenvironment.components.SysVSharedMemoryComponent;
import com.factorio.xenvironment.components.VortekRendererComponent;
import com.factorio.xenvironment.components.XServerComponent;
import com.factorio.xserver.ScreenInfo;
import com.factorio.xserver.Window;
import com.factorio.xserver.WindowManager;
import com.factorio.xserver.XServer;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private com.factorio.core.Diagnostics.Session diag = null;

    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] graphicsDriverConfig;
    private KeyValueSet audioDriverConfig;
    private final EnvVars envVars = new EnvVars();
    private SharedPreferences preferences;
    private final GamepadHandler gamepadHandler = new GamepadHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private String screenEffectProfile;
    private static boolean running = false;

    // on-screen controls follow the gamepad: connected -> hidden, disconnected -> shown again
    private InputManager.InputDeviceListener gamepadListener;
    private boolean controlsHiddenByGamepad = false;
    private PreloaderDialog preloaderDialog;
    private volatile boolean firstWindowShown = false;
    private boolean showControlsWithGamepad = false; // user opt-in (input controls dialog)

    // true while the game screen exists (MainActivity uses it to know that settings changes need a restart)
    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        running = true;
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_debug_logs", false);
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);
        findViewById(R.id.LLExit).setOnClickListener((v) -> exit()); // "Exit" is pinned to the bottom of the drawer

        rootFS = RootFS.find(this);

        ContainerManager containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
        if (container == null) container = containerManager.getMainContainer();
        if (container == null) {
            finish();
            return;
        }
        containerManager.activateContainer(container);

        // automatic diagnostics (settings -> Diagnostics): this start belongs to one test setup, the container is adjusted here
        diag = com.factorio.core.Diagnostics.beginVariant(this, container);

        checkNativeGame(this::startEnvironment);
    }

    /** Second half of onCreate: runs at once, or after the "game not found" / "update" dialog. */
    private void startEnvironment() {
        String graphicsDriver = container.getGraphicsDriver();
        audioDriver = container.getAudioDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
        screenInfo = new ScreenInfo(container.getScreenSize());

        this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
        this.graphicsDriverConfig = GraphicsDrivers.getActiveConfigs(container);

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setGamepadHandler(gamepadHandler);
        final boolean[] flags = {false};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {}

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    firstWindowShown = true;
                    flags[0] = true;
                }
            }

            @Override
            public void onUnmapWindow(Window window) {}
        });

        setupUI();

        AppExecutors.execute(() -> {
            setupSystemFiles();
            extractGraphicsDriverFiles();
            writeDriverReport();
            setupXEnvironment();
        });
    }


    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            applyInputControlsScreenResult(data);
        }
        else if (requestCode == PICK_GAME_ARCHIVE_REQUEST_CODE) {
            Runnable onReady = onGameArchivePicked;
            onGameArchivePicked = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && onReady != null) {
                GameArchive.choose(this, data.getData());
                onReady.run();
            }
            else finish(); // nothing picked: the game cannot start
        }
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && capturePointerOnExternalMouse && touchpadView != null) touchpadView.requestPointerCapture();
    }


    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }


    @Override
    protected void onDestroy() {
        running = false;
        if (gamepadListener != null) {
            InputManager inputManager = (InputManager)getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) inputManager.unregisterInputDeviceListener(gamepadListener);
            gamepadListener = null;
        }
        gamepadHandler.stopBridge();
        if (environment != null) environment.stopEnvironmentComponents();
        super.onDestroy();
    }


    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                openInputControlsScreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_settings:
                // unified settings (container + app) live in MainActivity; the game keeps running underneath
                Intent settingsIntent = new Intent(this, MainActivity.class);
                settingsIntent.putExtra("open_settings", true);
                settingsIntent.putExtra("from_game", true);
                startActivity(settingsIntent);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }


    public SharedPreferences getPreferences() {
        return preferences;
    }


    /** Documents/Factorio/driver-report.txt: which driver files are REALLY in the rootfs (size, hash, Mesa version inside). */
    private void writeDriverReport() {
        try {
            File rootDir = rootFS.getRootDir();
            File libDir = rootFS.getLibDir();
            StringBuilder sb = new StringBuilder();
            sb.append("Driver report ").append(new java.util.Date()).append("\n");
            sb.append("App version code: ").append(AppUtils.getVersionCode(this)).append("\n\n");

            if (GraphicsDrivers.isLegacyMode(graphicsDriver)) {
                sb.append("BLOCK: Vortek + ").append(GraphicsDrivers.getName(graphicsDriver[1])).append(" (original Winlator stack)\n");
                sb.append("  Vortek config: ").append(graphicsDriverConfig[0]).append("\n");
                if (graphicsDriver[1].equals(GraphicsDrivers.GLADIO)) {
                    String gladioVersion = graphicsDriverConfig[1].get("version", DefaultVersion.GLADIO);
                    sb.append("  Gladio version: '").append(gladioVersion).append("'");
                    if (GeneralComponents.isBuiltinComponent(GeneralComponents.Type.GLADIO, gladioVersion)) sb.append(" (built-in)\n");
                    else {
                        File gladioFile = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.GLADIO, this), "gladio-"+gladioVersion+".tzst");
                        sb.append(" (imported file ").append(gladioFile.length()).append(" bytes, ").append(new java.util.Date(gladioFile.lastModified())).append(")\n");
                    }
                }
                sb.append("  cacheId: ").append(container.getExtra("graphicsDriver")).append("\n");
                sb.append(DriverReport.describe(new File(libDir, "libvulkan_vortek.so"))).append("\n");
                String icd = FileUtils.readString(new File(rootDir, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json"));
                sb.append("  ICD: ").append(icd != null ? icd.replaceAll("\\s+", " ").trim() : "MISSING").append("\n");
                File[] legacyGL = libDir.listFiles((dir, name) -> name.startsWith("libGL.so"));
                if (legacyGL != null) {
                    java.util.Arrays.sort(legacyGL);
                    for (File file : legacyGL) sb.append(DriverReport.describe(file)).append("\n");
                }
                else sb.append("  libGL.so*: MISSING\n");
                sb.append("  details of the Vulkan driver: vortek-report.txt (next to factorio-native.log)\n");
            }

            if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                sb.append("VULKAN: Turnip, selected version '").append(version).append("'");
                if (GeneralComponents.isBuiltinComponent(GeneralComponents.Type.TURNIP, version)) sb.append(" (built-in)\n");
                else {
                    File file = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.TURNIP, this), "turnip-"+version+".tzst");
                    sb.append(" (imported file ").append(file.length()).append(" bytes, ").append(new java.util.Date(file.lastModified())).append(")\n");
                }
                sb.append(DriverReport.describe(new File(libDir, "libvulkan_freedreno.so"))).append("\n");
                String icd = FileUtils.readString(new File(rootDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"));
                sb.append("  ICD: ").append(icd != null ? icd.replaceAll("\\s+", " ").trim() : "MISSING").append("\n");
                String[] deps = new File(libDir, "turnip-deps").list();
                sb.append("  turnip-deps: ").append(deps != null ? java.util.Arrays.toString(deps) : "none").append("\n");
                File zinkDri = new File(libDir, "zink-dri/libGL.so.1");
                sb.append("  Zink build: ").append(zinkDri.exists() ? "DRI (kopper, frames via the Vulkan swapchain / DRI3)" : "xlib (frames read back to CPU memory)").append("\n");
            }

            if (graphicsDriver[1].equals(GraphicsDrivers.ZINK)) {
                String version = graphicsDriverConfig[1].get("version", DefaultVersion.ZINK);
                sb.append("\nOPENGL: Zink, selected version '").append(version).append("'");
                sb.append(GeneralComponents.isBuiltinComponent(GeneralComponents.Type.ZINK, version) ? " (built-in)\n" : " (imported)\n");
                File[] gl = libDir.listFiles((dir, name) -> name.startsWith("libGL.so."));
                if (gl != null && gl.length > 0) {
                    java.util.Arrays.sort(gl);
                    sb.append(DriverReport.describe(gl[gl.length - 1])).append("\n");
                }
                else sb.append("  libGL.so.*: MISSING\n");
            }

            // direct OpenGL (NATIVE_MESA_SRC): without this section the report only described Zink while the game ran on something else
            EnvVars containerVars = new EnvVars(container.getEnvVars());
            String mesaSrc = containerVars.get("NATIVE_MESA_SRC");
            if (GraphicsDrivers.OPENGL.equals(graphicsDriver[1])) {
                sb.append("\nNATIVE OPENGL: bundled Freedreno KGSL r7\n");
                File src = new File(rootDir, "opt/mesa-kgsl-r7");
                sb.append("  source folder: ").append(src.isDirectory() ? "found" : "NOT FOUND / not readable").append("\n");
                File copy = new File(rootDir, "opt/mesa-kgsl-r7");
                sb.append("  copy in rootfs: ").append(copy.isDirectory() ? "yes" : "NO").append("\n");
                sb.append("  env: MESA_LOADER_DRIVER_OVERRIDE=").append(containerVars.has("MESA_LOADER_DRIVER_OVERRIDE") ? containerVars.get("MESA_LOADER_DRIVER_OVERRIDE") : "(default: kgsl)")
                  .append("  LIBGL_DRIVERS_PATH=").append(containerVars.has("LIBGL_DRIVERS_PATH") ? containerVars.get("LIBGL_DRIVERS_PATH") : "(default: "+copy+"/dri)").append("\n");
                for (String sub : new String[]{"lib", "dri"}) {
                    File[] files = new File(copy, sub).listFiles();
                    sb.append("  ").append(sub).append("/: ");
                    if (files == null || files.length == 0) { sb.append("EMPTY or missing\n"); continue; }
                    java.util.Arrays.sort(files);
                    sb.append("\n");
                    for (File file : files) sb.append("    ").append(file.getName()).append("  ").append(file.length()).append(" bytes\n");
                }
                File[] dri = new File(copy, "dri").listFiles();
                if (dri != null) for (File file : dri) if (file.isFile() && file.length() > 1000000) sb.append(DriverReport.describe(file)).append("\n");
                File[] gl = new File(copy, "lib").listFiles((dir, name) -> name.startsWith("libGL.so."));
                if (gl != null && gl.length > 0) { java.util.Arrays.sort(gl); sb.append(DriverReport.describe(gl[gl.length - 1])).append("\n"); }
            }

            File logDir = LogView.getLogFile().getParentFile();
            DriverReport.write(logDir, sb.toString());
        }
        catch (Exception e) {
            android.util.Log.w("DriverReport", e);
        }
    }

    private void showEarlyExitDialog(int status) {
        if (diag != null) { // diagnostics: record it and go on with the next setup instead of showing a dialog
            if (preloaderDialog != null) preloaderDialog.close();
            diag.finish("game exited early (status "+status+")");
            return;
        }
        if (preloaderDialog != null) preloaderDialog.close();

        String tail = "";
        try {
            File logDir = LogView.getLogFile().getParentFile();
            String log = logDir != null ? FileUtils.readString(new File(logDir, "factorio-native.log")) : null;
            if (log != null && !log.trim().isEmpty()) {
                String[] lines = log.trim().split("\n");
                StringBuilder sb = new StringBuilder();
                for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) sb.append(lines[i].length() > 220 ? lines[i].substring(0, 220) : lines[i]).append('\n');
                tail = "\n\n"+sb;
            }
        }
        catch (Exception e) {}

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.game_exited_early)
            .setMessage(getString(R.string.game_exited_early_message, status)+tail)
            .setCancelable(false)
            .setPositiveButton(R.string.exit, (dialog, which) -> exit())
            .show();
    }

    /** Used by the diagnostics: stops the game and the X server without closing the app. */
    public void diagShutdown() {
        try {
            gamepadHandler.stopBridge();
            if (environment != null) environment.stopEnvironmentComponents();
        }
        catch (Exception e) {}
        environment = null;
    }

    private void exit() {
        gamepadHandler.stopBridge();
        if (environment != null) environment.stopEnvironmentComponents();

        // "Exit" closes the whole app. It must NOT restart the app, otherwise MainActivity starts again
        // and the auto-start launches the game again (endless loop).
        finishAndRemoveTask();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    private void setupSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
            container.putExtra("graphicsDriver", null); // forces the graphics driver files to be extracted again
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            container.saveData();
        }
    }


    private static final int PICK_GAME_ARCHIVE_REQUEST_CODE = 41;
    private Runnable onGameArchivePicked;

    /**
     * The game must be installed in the rootfs already, or come from the game folder (NATIVE_SRC, copied once) or
     * from an archive (factorio*linux-arm64*_X.Y.Z.tar.xz in Download or picked by the user, unpacked once).
     * Without any of them the user is asked to pick the archive. onReady continues the start.
     */
    private void checkNativeGame(Runnable onReady) {
        if (container == null) {
            onReady.run();
            return;
        }
        EnvVars containerVars = new EnvVars(container.getEnvVars());
        String nativeSrc = containerVars.get("NATIVE_SRC");
        String gameDirName = nativeSrc != null && !nativeSrc.isEmpty() ? new File(nativeSrc).getName() : "factorio";
        File gameDir = new File(rootFS.getRootDir(), RootFS.HOME_PATH+"/"+gameDirName);
        File downloadDir = GameArchive.downloadDir(nativeSrc);

        if (GameArchive.hasPending(this)) {
            android.widget.Toast.makeText(this, R.string.installing_game_archive, android.widget.Toast.LENGTH_LONG).show();
            onReady.run();
            return;
        }

        if (GameArchive.isInstalled(gameDir)) {
            // a newer archive in Download: offer the update once per version (not during the automatic diagnostics)
            File newest = diag == null ? GameArchive.findNewest(downloadDir) : null;
            String installed = GameArchive.installedVersion(gameDir);
            String found = newest != null ? GameArchive.versionOf(newest.getName()) : null;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (found != null && installed != null && GameArchive.compareVersions(found, installed) > 0 &&
                !found.equals(prefs.getString(GameArchive.PREF_DECLINED_VERSION, ""))) {
                new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.game_update_title)
                    .setMessage(getString(R.string.game_update_message, installed, found, newest.getName()))
                    .setCancelable(false)
                    .setPositiveButton(R.string.game_update_install, (dialog, which) -> {
                        GameArchive.setPending(this, newest.getPath());
                        onReady.run();
                    })
                    .setNegativeButton(R.string.game_update_later, (dialog, which) -> {
                        prefs.edit().putString(GameArchive.PREF_DECLINED_VERSION, found).apply();
                        onReady.run();
                    })
                    .show();
                return;
            }
            onReady.run();
            return;
        }

        if (nativeSrc != null && !nativeSrc.isEmpty() && (new File(nativeSrc)).isDirectory()) {
            android.widget.Toast.makeText(this, R.string.copying_game, android.widget.Toast.LENGTH_LONG).show();
            onReady.run();
            return;
        }

        if (GameArchive.findNewest(downloadDir) != null) {
            android.widget.Toast.makeText(this, R.string.installing_game_archive, android.widget.Toast.LENGTH_LONG).show();
            onReady.run();
            return;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.game_not_found_title)
            .setMessage(getString(R.string.game_not_found_message, nativeSrc != null ? nativeSrc : downloadDir+"/factorio", downloadDir.getPath()))
            .setCancelable(false)
            .setPositiveButton(R.string.choose_game_archive, (dialog, which) -> {
                onGameArchivePicked = onReady;
                startActivityForResult(GameArchive.pickIntent(), PICK_GAME_ARCHIVE_REQUEST_CODE);
            })
            .setNegativeButton(R.string.exit, (dialog, which) -> finish())
            .show();
    }

    // The native game (Factorio) cannot use the Windows xinput/dinput path. Instead the Selkies
    // input interposer (LD_PRELOAD) gives the game a virtual "Xbox 360 pad" and we feed it from GamepadHandler.
    // Needs assets/input_interposer/{selkies_input_interposer.so, libudev.so.1} built for aarch64 glibc.
    // Disable with container env var NATIVE_GAMEPAD=0.
    private void setupNativeGamepadBridge() {
        try {
            String nativeExec = envVars.get("NATIVE_EXEC");
            if (nativeExec == null || nativeExec.isEmpty() || "0".equals(envVars.get("NATIVE_GAMEPAD"))) return;

            String[] assets = getAssets().list("input_interposer");
            boolean hasLibs = false;
            if (assets != null) {
                boolean a = false, b = false;
                for (String name : assets) {
                    if (name.equals("selkies_input_interposer.so")) a = true;
                    else if (name.equals("libudev.so.1")) b = true;
                }
                hasLibs = a && b;
            }
            if (!hasLibs) {
                Log.w("NativeGamepad", "assets/input_interposer/*.so not found, gamepad bridge disabled");
                return;
            }

            File rootDir = rootFS.getRootDir();
            File libDir = new File(rootDir, "opt/input_interposer");
            FileUtils.copy(this, "input_interposer/selkies_input_interposer.so", new File(libDir, "selkies_input_interposer.so"));
            FileUtils.copy(this, "input_interposer/libudev.so.1", new File(libDir, "libudev.so.1"));

            File socketDir = new File(rootDir, "tmp/selkies-js");
            FileUtils.delete(socketDir);
            socketDir.mkdirs();

            envVars.put("NATIVE_PRELOAD", libDir.getPath()+"/selkies_input_interposer.so:"+libDir.getPath()+"/libudev.so.1");
            envVars.put("SELKIES_JS_SOCKET_PATH", socketDir.getPath());
            envVars.put("SELKIES_REAL_LIBUDEV", "none");
            envVars.put("JS_LOG", "1"); // interposer prints every gamepad open/read to the game log

            gamepadHandler.startBridge(socketDir);
        }
        catch (Exception e) {
            Log.e("NativeGamepad", "bridge setup failed: "+e);
        }
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        envVars.putAll(container.getEnvVars());
        xServer.setWireTraceEnabled("1".equals(envVars.get("X11_WIRE_TRACE")));

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (GraphicsDrivers.isLegacyMode(graphicsDriver)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            environment.addComponent(new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(() -> {
            // diagnostics: the game ended (by itself or because the diagnostics stopped it). Never close the app here,
            // the diagnostics go on with the next setup (finish() ignores a second call)
            if (diag != null) {
                diag.finish("game exited early (status "+status+")");
                return;
            }
            // The game died before it opened a window (usually a broken graphics driver): do not hang on the loading
            // animation / silently kill the app, show what happened.
            if (!firstWindowShown && status != null && status != 0 && !isFinishing()) showEarlyExitDialog(status);
            else exit();
        }));
        environment.addComponent(guestProgramLauncherComponent);

        setupNativeGamepadBridge();
        environment.startEnvironmentComponents();
        if (diag != null) diag.onEnvironmentStarted(xServerView);

        envVars.clear();
        graphicsDriver = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setShowTouchscreenControls(preferences.getBoolean("show_touchscreen_controls", true));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        // The RTS controls profile is enabled automatically on start.
        if (isNativeGameContainer()) {
            ControlsProfile defaultProfile = inputControlsManager.getProfile(DEFAULT_NATIVE_CONTROLS_PROFILE_ID);
            if (defaultProfile != null) showInputControls(defaultProfile);
        }
        setupGamepadAutoControls();

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    /**
     * "Input controls" in the side menu opens the input controls screen directly: profile selection and editing,
     * import / export and the three switches. When it is closed the game applies the result.
     */
    private void openInputControlsScreen() {
        ControlsProfile activeProfile = inputControlsView.getProfile();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("edit_input_controls", true);
        intent.putExtra("from_game", true);
        intent.putExtra("selected_profile_id", activeProfile != null ? activeProfile.id : 0);
        startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
    }

    private void applyInputControlsScreenResult(@Nullable Intent data) {
        int profileId = data != null ? data.getIntExtra("selected_profile_id", 0) : 0;

        // mouse and cursor settings are changed on that screen too
        GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));

        // the profile may have been edited (or imported / duplicated) there: read everything from the files again
        inputControlsManager.loadProfiles();
        ControlsProfile profile = profileId > 0 ? inputControlsManager.getProfile(profileId) : null;

        if (profile != null) showInputControls(profile);
        else hideInputControls();

        showControlsWithGamepad = preferences.getBoolean("show_controls_with_gamepad", false);
        inputControlsView.setShowTouchscreenControls(preferences.getBoolean("show_touchscreen_controls", true));
        updateControlsForGamepad(-1);
    }

    // id of the profile that is switched on automatically for native games:
    // 1 = "control" (assets/inputcontrols/profiles/controls-1.icp), the only bundled profile
    private static final int DEFAULT_NATIVE_CONTROLS_PROFILE_ID = 1;

    private boolean isNativeGameContainer() {
        return container != null; // every container of this app runs the native game
    }


    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        // one cursor speed for everything: the common one (the profile's own value is kept in sync for the stick-driven cursor)
        profile.setCursorSpeed(globalCursorSpeed);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        controlsHiddenByGamepad = false;
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    // ------------------------------------------------------------------ gamepad <-> on-screen controls

    private void setupGamepadAutoControls() {
        showControlsWithGamepad = preferences.getBoolean("show_controls_with_gamepad", false);

        InputManager inputManager = (InputManager)getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null && gamepadListener == null) {
            gamepadListener = new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { updateControlsForGamepad(-1); }
                @Override public void onInputDeviceRemoved(int deviceId) { updateControlsForGamepad(deviceId); }
                @Override public void onInputDeviceChanged(int deviceId) {}
            };
            inputManager.registerInputDeviceListener(gamepadListener, new Handler(Looper.getMainLooper()));
        }
        updateControlsForGamepad(-1);
    }

    private static boolean isGamepadConnected(int ignoredDeviceId) {
        for (int deviceId : InputDevice.getDeviceIds()) {
            if (deviceId == ignoredDeviceId) continue; // the device that is being removed may still be in the list
            if (ExternalController.isGameController(InputDevice.getDevice(deviceId))) return true;
        }
        return false;
    }

    /**
     * Gamepad connected    -> the on-screen controls are hidden (the screen works as a plain touchpad).
     * Gamepad disconnected -> the controls come back.
     * "Show controls with gamepad" in the input controls dialog turns the hiding off.
     */
    private void updateControlsForGamepad(int ignoredDeviceId) {
        if (inputControlsView == null || touchpadView == null) return;

        boolean hide = isGamepadConnected(ignoredDeviceId) && !showControlsWithGamepad;
        if (hide) {
            if (inputControlsView.getVisibility() == View.VISIBLE && inputControlsView.getProfile() != null) {
                controlsHiddenByGamepad = true;
                inputControlsView.setVisibility(View.GONE);

                touchpadView.setSensitivity(globalCursorSpeed);
                touchpadView.setPointerButtonLeftEnabled(true);
                touchpadView.setPointerButtonRightEnabled(true);
                touchpadView.setEnabled(true);
                xServerView.getRenderer().setCursorVisible(true);
            }
        }
        else if (controlsHiddenByGamepad) {
            controlsHiddenByGamepad = false;
            ControlsProfile profile = inputControlsView.getProfile();
            if (profile != null) showInputControls(profile);
        }
    }

    /** true when the Vortek + Gladio / Zink 22.2.5 block is selected (used by XServer to pick the GLX implementation). */
    public boolean isLegacyGraphics() {
        return graphicsDriver != null && GraphicsDrivers.isLegacyMode(graphicsDriver);
    }

    /**
     * Vortek + Gladio / Zink 22.2.5 (original Winlator stack). Completely separate from the Turnip / Zink code below:
     * own cache id, own files, own environment variables. Switching between the two blocks re-extracts the files.
     */
    private void extractLegacyGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        // Gladio is versioned like Turnip / Zink: the built-in build is the known-good one, builds imported from a file live next to it
        String gladioVersion = graphicsDriverConfig[1].get("version", DefaultVersion.GLADIO);
        String glVersion = graphicsDriver[1].equals(GraphicsDrivers.GLADIO) ? gladioVersion : DefaultVersion.valueOf(graphicsDriver[1]);
        String cacheId = "legacy-"+GraphicsDrivers.VORTEK+"-"+DefaultVersion.VORTEK+"-"+graphicsDriver[1]+"-"+glVersion;
        if (graphicsDriver[1].equals(GraphicsDrivers.GLADIO)) {
            // an imported build re-installed under the same name must be extracted again
            File gladioFile = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.GLADIO, this), "gladio-"+gladioVersion+".tzst");
            if (gladioFile.isFile() && !GeneralComponents.isBuiltinComponent(GeneralComponents.Type.GLADIO, gladioVersion)) cacheId += "-"+gladioFile.lastModified();
        }
        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "turnip-deps"));
            FileUtils.delete(new File(libDir, "zink-dri"));
            File[] oldGL = libDir.listFiles((dir, name) -> name.startsWith("libGL.so"));
            if (oldGL != null) for (File file : oldGL) FileUtils.delete(file);

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            container.putExtra("graphicsDriver", cacheId);
            container.saveData();

            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-"+DefaultVersion.VORTEK+".tzst", rootDir);

            boolean hasGLPackage = false;
            if (graphicsDriver[1].equals(GraphicsDrivers.GLADIO)) {
                GeneralComponents.extractFile(GeneralComponents.Type.GLADIO, this, gladioVersion, DefaultVersion.GLADIO);
                hasGLPackage = true;
            }
            else if (graphicsDriver[1].equals(GraphicsDrivers.ZINK_LEGACY)) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK_LEGACY+".tzst", rootDir);
                hasGLPackage = true;
            }
            if (hasGLPackage) {
                // these packages only carry libGL.so.1.7.0: the links that the rootfs loader needs are created here
                FileUtils.symlink("libGL.so.1.7.0", new File(libDir, "libGL.so.1").getPath());
                FileUtils.symlink("libGL.so.1", new File(libDir, "libGL.so").getPath());
            }
        }

        // Mali preset: game graphics keys of the preset, unless the container sets NATIVE_GFX itself
        com.factorio.core.MaliPresets.Preset maliPreset = com.factorio.core.MaliPresets.find(graphicsDriverConfig[0].get("preset", ""));
        if (maliPreset != null && maliPreset.nativeGfx != null) {
            String ownGfx = envVars.get("NATIVE_GFX");
            if (ownGfx == null || ownGfx.isEmpty()) envVars.put("NATIVE_GFX", maliPreset.nativeGfx);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");
                break;
            case GraphicsDrivers.ZINK_LEGACY:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
                break;
        }
    }

    private void extractGraphicsDriverFiles() {
        if (GraphicsDrivers.isLegacyMode(graphicsDriver)) {
            extractLegacyGraphicsDriverFiles();
            return;
        }
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            String turnipVersion = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
            cacheId += graphicsDriver[0]+"-"+turnipVersion;

            // an imported build that was re-installed under the same name must be extracted again
            File turnipFile = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.TURNIP, this), "turnip-"+turnipVersion+".tzst");
            if (turnipFile.isFile() && !GeneralComponents.isBuiltinComponent(GeneralComponents.Type.TURNIP, turnipVersion)) cacheId += "-"+turnipFile.lastModified();
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        // Zink is versioned like Turnip: the built-in one is the known-good build, imported builds live next to it
        String zinkVersion = graphicsDriverConfig[1].get("version", DefaultVersion.ZINK);
        cacheId += "-"+graphicsDriver[1]+"-"+zinkVersion;
        File zinkFile = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.ZINK, this), "zink-"+zinkVersion+".tzst");
        if (zinkFile.isFile() && !GeneralComponents.isBuiltinComponent(GeneralComponents.Type.ZINK, zinkVersion)) cacheId += "-"+zinkFile.lastModified();

        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so")); // left over from the Vortek block
            FileUtils.delete(new File(libDir, "turnip-deps")); // libraries that come with a Turnip build: never keep the ones of the previous build
            FileUtils.delete(new File(libDir, "zink-dri")); // Mesa of a Zink DRI package (own folder, see build-zink-dri.yml)
            File[] oldGL = libDir.listFiles((dir, name) -> name.startsWith("libGL.so")); // any libGL version of a previous Zink
            if (oldGL != null) for (File file : oldGL) FileUtils.delete(file);

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            container.putExtra("graphicsDriver", cacheId);
            container.saveData();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                if (!envVars.has("ZINK_CONTEXT_THREADED")) envVars.put("ZINK_CONTEXT_THREADED", "1");

                if (changed) GeneralComponents.extractFile(GeneralComponents.Type.ZINK, this, zinkVersion, DefaultVersion.ZINK);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (touchpadView == null) return super.dispatchGenericMotionEvent(event); // start waits for the "game not found" dialog
        return !gamepadHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (xServer == null || inputControlsView == null) return super.dispatchKeyEvent(event);
        // Native game with the gamepad bridge: pad buttons go to the game first, not to cursor/keyboard bindings
        if (gamepadHandler.isBridgeActive() && event.getKeyCode() != KeyEvent.KEYCODE_BACK && ExternalController.isGameController(event.getDevice())) {
            gamepadHandler.onKeyEvent(event);
            return true; // never let pad buttons navigate the Android UI while a native game runs
        }
        return (!inputControlsView.onKeyEvent(event) && !gamepadHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public GamepadHandler getGamepadHandler() {
        return gamepadHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }
}
