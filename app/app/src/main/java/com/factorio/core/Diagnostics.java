package com.factorio.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;

import com.factorio.MainActivity;
import com.factorio.XServerDisplayActivity;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.widget.LogView;
import com.factorio.widget.XServerView;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automatic diagnostics. Runs the game with a list of graphics setups one after another (every setup is a fresh start of the
 * game), and records for each one: the game log, logcat of the app, driver / Vortek reports, screenshots and a short result.
 * Everything ends up in one folder + one zip next to the other logs (Documents/Factorio/diagnostics-*.zip).
 *
 *  - "Diagnostics":       SHORT_SECONDS per setup (start-up / driver initialisation matrix)
 *  - "Diagnostics long":  LONG_SECONDS per setup (the game gets to the menu, screenshots every minute)
 *
 * The user's own settings are saved before the run and restored at the end (and at the next app start if the run was interrupted).
 * To add a setup, add one line in buildVariants().
 */
public class Diagnostics {
    public static final int SHORT_SECONDS = 15;
    public static final int LONG_SECONDS = 300;
    public static final int MODE_SHORT = 0, MODE_LONG = 1, MODE_PERF = 2;
    /** Performance tests: the save is loaded, then SETTLE s of waiting and MEASURE s of measuring; MAX is the limit. */
    public static final int PERF_SETTLE_SECONDS = 15;
    public static final int PERF_MEASURE_SECONDS = 45;
    public static final int PERF_MAX_SECONDS = 480;
    private static final String TAG = "Diagnostics";
    private static final String STATE_FILE = "diagnostics-state.json";
    public static final String EXTRA_RUN = "diagnostics_run";
    public static final String EXTRA_DONE = "diagnostics_done";

    public static class Variant {
        public final String id;
        public final String title;
        public final String driver;       // "vortek,zinkold", "turnip,zink", ...
        public final String vortekConfig; // "bcnMode=1,resourceMemoryType=3" (only for Vortek setups)
        public final String nativeGfx;    // value for NATIVE_GFX or null (= game settings are reset to the defaults)
        public final String extraEnv;     // "A=1;B=2" additional container variables or null
        // performance tests only
        public boolean perf = false;
        public String zinkVersion;        // Zink package for this setup (e.g. "26.2.0-dri"), null = the user's
        public int screenMode = -1;       // ScreenResolution.MODE_*, -1 = the user's resolution
        public boolean loadGame = false;  // start the newest save with --load-game (else: main menu)
        public String gfxPatch;           // NATIVE_GFX_PATCH: keys changed in the user's own game config

        Variant(String id, String title, String driver, String vortekConfig, String nativeGfx) {
            this(id, title, driver, vortekConfig, nativeGfx, null);
        }

        Variant(String id, String title, String driver, String vortekConfig, String nativeGfx, String extraEnv) {
            this.extraEnv = extraEnv;
            this.id = id;
            this.title = title;
            this.driver = driver;
            this.vortekConfig = vortekConfig;
            this.nativeGfx = nativeGfx;
        }
    }

    private static Variant perfVariant(String id, String title, String driver, String zinkVersion, int screenMode, boolean loadGame,
                                       String gfxPatch, String extraEnv) {
        Variant v = new Variant(id, title, driver, null, null, extraEnv);
        v.perf = true;
        v.zinkVersion = zinkVersion;
        v.screenMode = screenMode;
        v.loadGame = loadGame;
        v.gfxPatch = gfxPatch;
        return v;
    }

    /** The newest installed Zink DRI package ("26.2.0-dri"), or null. */
    public static String findZinkDri(Context context) {
        String best = null;
        for (String name : GeneralComponents.getInstalledComponentNames(GeneralComponents.Type.ZINK, context)) {
            if (name.endsWith("-dri") && (best == null || name.compareTo(best) > 0)) best = name;
        }
        return best;
    }

    /**
     * Performance tests: every setup loads the newest save, waits PERF_SETTLE_SECONDS, measures PERF_MEASURE_SECONDS
     * (frames/s, CPU per thread, GPU, X server times, see PerfMonitor). The user's own game config is used; only the keys
     * a setup names are changed. To add a test, add one line here.
     */
    private static List<Variant> buildPerfVariants(Context context) {
        ArrayList<Variant> list = new ArrayList<>();
        final String tz = "turnip,zink";
        final int nat = ScreenResolution.MODE_NATIVE;
        String dri = findZinkDri(context);
        String zink = dri != null ? dri : DefaultVersion.ZINK;
        String name = "Zink "+zink;
        list.add(perfVariant("P01-base", name+", native, game VSync as set (baseline)", tz, zink, nat, true, null, null));
        list.add(perfVariant("P02-vsync-off", name+", native, game VSync OFF", tz, zink, nat, true, "vsync=false", null));
        list.add(perfVariant("P03-720p", name+", 720p", tz, zink, ScreenResolution.MODE_720P, true, null, null));
        list.add(perfVariant("P04-menu", name+", native, main menu only (no save)", tz, zink, nat, false, null, null));
        list.add(perfVariant("P05-wsi-immediate", name+", native, Vulkan present mode IMMEDIATE (no driver vsync)", tz, zink, nat, true, null, "MESA_VK_WSI_PRESENT_MODE=immediate"));
        list.add(perfVariant("P06-no-glthread", name+", native, mesa_glthread=false", tz, zink, nat, true, null, "mesa_glthread=false"));
        list.add(perfVariant("P07-vsync-off-720p", name+", 720p, game VSync OFF", tz, zink, ScreenResolution.MODE_720P, true, "vsync=false", null));
        if (dri != null) list.add(perfVariant("P08-zink-xlib", "Zink "+DefaultVersion.ZINK+" (xlib), native", tz, DefaultVersion.ZINK, nat, true, null, null));
        if (GPUHelper.isAdreno(context)) list.add(perfVariant("P09-opengl-kgsl", "OpenGL (Freedreno KGSL), native", "turnip,opengl", null, nat, true, null, null));
        return list;
    }

    public static List<Variant> buildVariants(Context context, int mode) {
        if (mode == MODE_PERF) return buildPerfVariants(context);
        return buildVariants(context, mode == MODE_LONG);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the list of setups
    // ---------------------------------------------------------------------------------------------------------------
    public static List<Variant> buildVariants(Context context, boolean longMode) {
        ArrayList<Variant> list = new ArrayList<>();
        final String zink = "vortek,zinkold";
        final String gladio = "vortek,gladio";
        final String noCompression = "texture-compression-level=none";
        boolean adreno = GPUHelper.isAdreno(context);

        if (!longMode) {
            // start-up matrix (15 s each): does the driver start at all. Variants known to crash or hang come last.
            list.add(new Variant("01-zink-bcn-gpu-new", "Vortek + Zink 22.2.5, BCn: GPU layer (new build)", zink, "bcnMode=1", null));
            list.add(new Variant("02-zink-sync1", "Zink, BCn new, CPU cache sync: flush/invalidate", zink, "bcnMode=1,memorySync=1", null));
            list.add(new Variant("03-zink-sync2", "Zink, BCn new, CPU cache sync: also before every submit", zink, "bcnMode=1,memorySync=2", null));
            list.add(new Variant("04-zink-dmabuf-sync2", "Zink, BCn new, DMA Buffer + cache sync 2", zink, "bcnMode=1,memorySync=2,resourceMemoryType=2", null));
            list.add(new Variant("05-zink-bcn-gpu-astc", "Vortek + Zink 22.2.5, BCn: GPU layer legacy ASTC", zink, "bcnMode=2", null));
            list.add(new Variant("06-zink-bcn-gpu-etc2", "Vortek + Zink 22.2.5, BCn: GPU layer legacy ETC2", zink, "bcnMode=3", null));
            list.add(new Variant("07-gladio-bcn-gpu-new", "Vortek + Gladio, BCn: GPU layer (new build)", gladio, "bcnMode=1", null));
            list.add(new Variant("08-gladio-bcn-cpu", "Vortek + Gladio, BCn: CPU decode", gladio, "bcnMode=0", null));
            list.add(new Variant("09-zink-vk11", "Zink, BCn new, Vulkan 1.1", zink, "bcnMode=1,vkMaxVersion=1.1", null));
            list.add(new Variant("10-zink-vk12", "Zink, BCn new, Vulkan 1.2", zink, "bcnMode=1,vkMaxVersion=1.2", null));
            list.add(new Variant("11-zink-mem4g", "Zink, BCn new, Max device memory 4096 MB", zink, "bcnMode=1,maxDeviceMemory=4096", null));
            list.add(new Variant("12-zink-nocompr", "Zink, BCn new, game: texture compression none", zink, "bcnMode=1", noCompression));
            list.add(new Variant("13-zink-unthreaded", "Zink, BCn new, ZINK_CONTEXT_THREADED=0", zink, "bcnMode=1", null, "ZINK_CONTEXT_THREADED=0"));
            list.add(new Variant("14-zink-vram-high", "Zink, BCn new, game: video memory usage high", zink, "bcnMode=1", "video-memory-usage=high"));
            list.add(new Variant("15-gladio-nocompr", "Gladio, BCn new, game: texture compression none", gladio, "bcnMode=1", noCompression));
            list.add(new Variant("16-zink-bcn-cpu", "Vortek + Zink 22.2.5, BCn: CPU decode (hangs on Mali-G610)", zink, "bcnMode=0", null));
            list.add(new Variant("17-zink-mem-dmabuf", "Zink, BCn new, Resource Memory: DMA Buffer", zink, "bcnMode=1,resourceMemoryType=2", null));
            list.add(new Variant("18-zink-mem-opaquefd", "Zink, BCn new, Resource Memory: Opaque FD (no GL context on Mali-G610)", zink, "bcnMode=1,resourceMemoryType=1", null));
            list.add(new Variant("19-zink-mem-ahb", "Zink, BCn new, Resource Memory: Native Hardware Buffer (crashes on Mali-G610)", zink, "bcnMode=1,resourceMemoryType=3", null));
            if (adreno) {
                list.add(new Variant("20-turnip-zink", "Turnip + Zink (current block)", "turnip,zink", null, null));
                list.add(new Variant("21-turnip-opengl", "Turnip + OpenGL native (current block)", "turnip,opengl", null, null));
            }
        }
        else {
            // Mali stripes: what is left after the CPU cache sync hypothesis failed (5 min each, screenshots every minute).
            // Every setup starts with the default game settings (see beginVariant).
            list.add(new Variant("L1-control-layer", "Zink, BCn GPU layer (control)", zink, "bcnMode=1", null));
            list.add(new Variant("L2-nolayer-nocompr", "Zink, NO BCn layer, game: texture compression none (no BC textures at all)", zink, "bcnMode=0", noCompression));
            list.add(new Variant("L3-layer-nocompr", "Zink, BCn GPU layer, game: texture compression none", zink, "bcnMode=1", noCompression));
            list.add(new Variant("L4-layer-zink-unthreaded", "Zink, BCn GPU layer, ZINK_CONTEXT_THREADED=0", zink, "bcnMode=1", null, "ZINK_CONTEXT_THREADED=0"));
            list.add(new Variant("L5-nolayer-nocompr-unthreaded", "Zink, NO layer, compression none, ZINK_CONTEXT_THREADED=0", zink, "bcnMode=0", noCompression, "ZINK_CONTEXT_THREADED=0"));
            list.add(new Variant("L6-dmabuf-sync2-unthreaded", "Zink, BCn layer, DMA Buffer + cache sync 2, ZINK_CONTEXT_THREADED=0", zink, "bcnMode=1,memorySync=2,resourceMemoryType=2", null, "ZINK_CONTEXT_THREADED=0"));
            list.add(new Variant("L7-gladio", "Vortek + Gladio (fixed glXQueryExtension)", gladio, "bcnMode=1", null));
            if (adreno) {
                list.add(new Variant("L9-turnip-zink", "Turnip + Zink (current block)", "turnip,zink", null, null));
                list.add(new Variant("L10-turnip-opengl", "Turnip + OpenGL native (current block)", "turnip,opengl", null, null));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // state (a file, so it survives restarts of the screen)
    // ---------------------------------------------------------------------------------------------------------------
    private static File stateFile(Context context) {
        return new File(context.getFilesDir(), STATE_FILE);
    }

    private static JSONObject readState(Context context) {
        try {
            String text = FileUtils.readString(stateFile(context));
            if (text != null && !text.trim().isEmpty()) return new JSONObject(text);
        }
        catch (Exception e) {}
        return null;
    }

    private static void writeState(Context context, JSONObject state) {
        try {
            FileUtils.writeString(stateFile(context), state.toString());
        }
        catch (Exception e) {
            Log.w(TAG, "unable to write state", e);
        }
    }

    public static boolean isActive(Context context) {
        return stateFile(context).isFile();
    }

    private static File logDir() {
        File dir = LogView.getLogFile().getParentFile();
        if (dir != null) dir.mkdirs();
        return dir;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // start / restore
    // ---------------------------------------------------------------------------------------------------------------
    public static int estimateMinutes(Context context, boolean longMode) {
        return estimateMinutes(context, longMode ? MODE_LONG : MODE_SHORT);
    }

    public static int estimateMinutes(Context context, int mode) {
        int variants = buildVariants(context, mode).size();
        // + loading of the app / unpacking / shutting down; a big save needs 1.5-2 min to load
        int perVariant = mode == MODE_PERF ? 150 + PERF_SETTLE_SECONDS + PERF_MEASURE_SECONDS : (mode == MODE_LONG ? LONG_SECONDS : SHORT_SECONDS) + 25;
        return Math.max(1, variants * perVariant / 60);
    }

    /** gameRoot/saves: the newest save that is not an autosave (or the newest autosave). Null when there is none. */
    public static File findNewestSave(Context context) {
        try {
            Container container = (new ContainerManager(context)).getMainContainer();
            String nativeSrc = container != null ? new EnvVars(container.getEnvVars()).get("NATIVE_SRC") : null;
            String gameDirName = nativeSrc != null && !nativeSrc.isEmpty() ? new File(nativeSrc).getName() : "factorio";
            File saves = new File(com.factorio.xenvironment.RootFS.find(context).getRootDir(), com.factorio.xenvironment.RootFS.HOME_PATH+"/"+gameDirName+"/saves");
            File[] files = saves.listFiles((dir, n) -> n.endsWith(".zip") && !n.endsWith(".tmp.zip"));
            if (files == null || files.length == 0) return null;
            File best = null, bestAuto = null;
            for (File file : files) {
                boolean auto = file.getName().startsWith("_autosave");
                if (auto) {
                    if (bestAuto == null || file.lastModified() > bestAuto.lastModified()) bestAuto = file;
                }
                else if (best == null || file.lastModified() > best.lastModified()) best = file;
            }
            return best != null ? best : bestAuto;
        }
        catch (Exception e) {
            return null;
        }
    }

    public static void start(Activity activity, boolean longMode) {
        start(activity, longMode ? MODE_LONG : MODE_SHORT);
    }

    public static void start(Activity activity, int mode) {
        boolean longMode = mode == MODE_LONG;
        try {
            Container container = (new ContainerManager(activity)).getMainContainer();
            File dir = logDir();
            if (container == null || dir == null) return;

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File runDir = new File(dir, (mode == MODE_PERF ? "perftest-" : "diagnostics-")+stamp);
            runDir.mkdirs();

            JSONObject snapshot = new JSONObject();
            snapshot.put("graphicsDriver", container.getGraphicsDriver());
            snapshot.put("graphicsDriverConfig", container.getGraphicsDriverConfig());
            snapshot.put("envVars", container.getEnvVars());
            snapshot.put("block1", container.getExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP));
            snapshot.put("block2", container.getExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK));
            snapshot.put("config2", container.getExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK));
            snapshot.put("screenSize", container.getScreenSize());

            JSONObject state = new JSONObject();
            state.put("longMode", longMode);
            state.put("mode", mode);
            File save = mode == MODE_PERF ? findNewestSave(activity) : null;
            String savePath = save != null ? save.getPath() : "";
            if (save != null && !save.getName().matches("[A-Za-z0-9._-]+")) {
                // the game command line is split at spaces: give the save a plain name for the run (removed at the end)
                File link = new File(save.getParentFile(), "perftest-save.zip");
                link.delete();
                FileUtils.symlink(save.getPath(), link.getPath());
                savePath = link.getPath();
            }
            state.put("save", savePath);
            state.put("index", 0);
            state.put("runDir", runDir.getPath());
            state.put("startedAt", System.currentTimeMillis());
            state.put("snapshot", snapshot);
            writeState(activity, state);

            appendSummary(runDir, (mode == MODE_PERF ? "Performance tests" : "Diagnostics "+(longMode ? "LONG" : "SHORT"))+" started "+new Date()+"\n"+deviceInfo(activity)+
                (mode == MODE_PERF ? "save: "+(save != null ? save.getName() : "NONE (main menu only)")+"\n"+
                    "each test: the save loads, "+PERF_SETTLE_SECONDS+" s wait, "+PERF_MEASURE_SECONDS+" s measuring; details in <test>/perf-summary.txt and perf.csv\n" : "")+"\n");
            launchVariant(activity, runDir.getPath());
        }
        catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    private static String deviceInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
          .append(" (").append(android.os.Build.DEVICE).append("), SoC: ").append(android.os.Build.HARDWARE)
          .append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("app version code: ").append(AppUtils.getVersionCode(context)).append("\n");
        try {
            sb.append("gpu: ").append(GPUHelper.glGetRenderer(context)).append("\n");
        }
        catch (Throwable e) {}
        return sb.toString();
    }

    private static void launchVariant(Context context, String runDir) {
        Container container = (new ContainerManager(context)).getMainContainer();
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        if (container != null) intent.putExtra("container_id", container.id);
        intent.putExtra(EXTRA_RUN, runDir);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    /** Called by the launcher before it (re)writes the game config: the user's own file is kept and given back after the run. */
    public static void backupConfig(Context context, File configFile) {
        try {
            File backup = new File(context.getFilesDir(), "diagnostics-config-backup.ini");
            if (!isActive(context) || backup.exists() || !configFile.isFile()) return;
            copy(configFile, backup);
            FileUtils.writeString(new File(context.getFilesDir(), "diagnostics-config-path.txt"), configFile.getPath());
        }
        catch (Exception e) {
            Log.w(TAG, "config backup failed", e);
        }
    }

    /** Puts the user's config back without ending the run (the backup stays for the next test). */
    private static void resetConfigFromBackup(Context context) {
        File backup = new File(context.getFilesDir(), "diagnostics-config-backup.ini");
        String path = FileUtils.readString(new File(context.getFilesDir(), "diagnostics-config-path.txt"));
        if (backup.isFile() && path != null && !path.trim().isEmpty()) copy(backup, new File(path.trim()));
        File dir = logDir();
        if (dir != null) new File(dir, "config-patch-result.txt").delete();
    }

    private static void restoreConfig(Context context) {
        File backup = new File(context.getFilesDir(), "diagnostics-config-backup.ini");
        File pathFile = new File(context.getFilesDir(), "diagnostics-config-path.txt");
        try {
            String path = FileUtils.readString(pathFile);
            if (backup.isFile() && path != null && !path.trim().isEmpty()) copy(backup, new File(path.trim()));
        }
        catch (Exception e) {}
        backup.delete();
        pathFile.delete();
    }

    /** Puts the user's own settings back and removes the state. Returns the run folder (or null). */
    public static File restoreSnapshot(Context context) {
        JSONObject state = readState(context);
        if (state == null) return null;
        File runDir = null;
        try {
            runDir = new File(state.getString("runDir"));
            JSONObject snapshot = state.getJSONObject("snapshot");
            Container container = (new ContainerManager(context)).getMainContainer();
            if (container != null) {
                container.setGraphicsDriver(snapshot.optString("graphicsDriver"));
                container.setGraphicsDriverConfig(snapshot.optString("graphicsDriverConfig"));
                container.setEnvVars(snapshot.optString("envVars"));
                container.putExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP, snapshot.optString("block1"));
                container.putExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK, snapshot.optString("block2"));
                container.putExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK, snapshot.optString("config2"));
                String screenSize = snapshot.optString("screenSize");
                if (!screenSize.isEmpty()) container.setScreenSize(screenSize);
                container.putExtra("graphicsDriver", ""); // force re-extraction of the user's driver
                container.saveData();
            }
        }
        catch (Exception e) {
            Log.e(TAG, "restore failed", e);
        }
        restoreConfig(context);
        try {
            String save = state.optString("save");
            if (save.endsWith("/perftest-save.zip")) new File(save).delete();
        }
        catch (Exception e) {}
        stateFile(context).delete();
        return runDir;
    }

    /** Called at app start: if a run was interrupted (app killed / crashed) the user's settings are restored. */
    public static void recoverIfInterrupted(Context context) {
        if (!isActive(context) || XServerDisplayActivity.isRunning()) return;
        File runDir = restoreSnapshot(context);
        if (runDir != null) {
            appendSummary(runDir, "\nRun was interrupted (app restarted). User settings restored. "+new Date()+"\n");
            zip(runDir);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // one setup = one session (lives in XServerDisplayActivity)
    // ---------------------------------------------------------------------------------------------------------------
    public static Session beginVariant(XServerDisplayActivity activity, Container container) {
        if (!isActive(activity)) return null;
        JSONObject state = readState(activity);
        String requested = activity.getIntent().getStringExtra(EXTRA_RUN);
        try {
            if (state == null || requested == null || !requested.equals(state.getString("runDir"))) {
                recoverIfInterrupted(activity); // normal start while a stale run is recorded
                return null;
            }
            boolean longMode = state.getBoolean("longMode");
            int mode = state.optInt("mode", longMode ? MODE_LONG : MODE_SHORT);
            int index = state.getInt("index");
            List<Variant> variants = buildVariants(activity, mode);
            if (index >= variants.size()) {
                finishRun(activity);
                return null;
            }
            Variant variant = variants.get(index);
            JSONObject snapshot = state.getJSONObject("snapshot");

            container.setGraphicsDriver(variant.driver);
            if (variant.vortekConfig != null) container.putExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK, variant.vortekConfig+"|");
            EnvVars envVars = new EnvVars(snapshot.optString("envVars"));
            envVars.remove("NATIVE_GFX");
            envVars.remove("NATIVE_GFX_PATCH");
            if (variant.perf) {
                // every test starts from the user's own game config: undo the keys a previous test changed
                resetConfigFromBackup(activity);
                // the user's own game config (graphics quality, ...) is used; only gfxPatch keys change
                if (variant.gfxPatch != null) envVars.put("NATIVE_GFX_PATCH", variant.gfxPatch);
                // the container variables are one string split at spaces: the save goes in its own variable (no spaces)
                envVars.remove("NATIVE_LOAD_GAME");
                String save = state.optString("save");
                if (variant.loadGame && !save.isEmpty() && new File(save).isFile()) envVars.put("NATIVE_LOAD_GAME", save);
                // driver package (Zink version) and resolution of this test
                if (variant.zinkVersion != null) {
                    com.factorio.core.KeyValueSet[] configs = GraphicsDrivers.parseConfigs(variant.driver, snapshot.optString("graphicsDriverConfig"));
                    configs[1].put("version", variant.zinkVersion);
                    container.setGraphicsDriverConfig(configs[0]+"|"+configs[1]);
                }
                else container.setGraphicsDriverConfig(snapshot.optString("graphicsDriverConfig"));
                String screenSize = variant.screenMode >= 0 ? ScreenResolution.forMode(activity, variant.screenMode) : snapshot.optString("screenSize");
                if (!screenSize.isEmpty()) container.setScreenSize(screenSize);
            }
            else {
                // the game config file is kept between launches: every setup starts from the defaults, otherwise the keys of the
                // previous setup would leak into this one ("reset" rewrites the file)
                envVars.put("NATIVE_GFX", variant.nativeGfx != null ? variant.nativeGfx : "reset");
            }
            if (variant.extraEnv != null) {
                for (String item : variant.extraEnv.split(";")) {
                    int eq = item.indexOf('=');
                    if (eq > 0) envVars.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
                }
            }
            container.setEnvVars(envVars.toString());
            container.saveData();

            appendSummary(new File(state.getString("runDir")), String.format(Locale.US, "  starting %s: driver %s, config %s, screen %s, NATIVE_LOAD_GAME=%s, NATIVE_GFX_PATCH=%s, extra %s",
                variant.id, container.getGraphicsDriver(), container.getGraphicsDriverConfig(), container.getScreenSize(),
                envVars.get("NATIVE_LOAD_GAME"), envVars.get("NATIVE_GFX_PATCH"), variant.extraEnv));
            return new Session(activity, state, variant, index, variants.size(), longMode);
        }
        catch (Throwable e) {
            Log.e(TAG, "beginVariant failed", e);
            try {
                if (state != null) appendSummary(new File(state.getString("runDir")), "  ERROR: setup could not start: "+Log.getStackTraceString(e));
            }
            catch (Exception ignored) {}
            return null;
        }
    }

    private static android.os.HandlerThread workerThread;

    private static synchronized Handler workerHandler() {
        if (workerThread == null) {
            workerThread = new android.os.HandlerThread("diagnostics-worker");
            workerThread.start();
        }
        return new Handler(workerThread.getLooper());
    }

    public static class Session {
        private final XServerDisplayActivity activity;
        private final JSONObject state;
        private final Variant variant;
        private final int index, total;
        private final boolean longMode;
        private final File runDir, variantDir;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long createdAt = System.currentTimeMillis();
        private long startedAt = 0;
        private BufferedWriter logWriter;
        private Callback<String> logCallback;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private XServerView xServerView;
        private PerfMonitor monitor;
        private volatile boolean measureScheduled = false;

        Session(XServerDisplayActivity activity, JSONObject state, Variant variant, int index, int total, boolean longMode) throws Exception {
            this.activity = activity;
            this.state = state;
            this.variant = variant;
            this.index = index;
            this.total = total;
            this.longMode = longMode;
            this.runDir = new File(state.getString("runDir"));
            this.variantDir = new File(runDir, variant.id);
            variantDir.mkdirs();

            FileUtils.writeString(new File(variantDir, "variant.txt"),
                "id: "+variant.id+"\ntitle: "+variant.title+"\ndriver: "+variant.driver+"\nvortek config: "+variant.vortekConfig+
                "\nNATIVE_GFX: "+variant.nativeGfx+"\nextra env: "+variant.extraEnv+"\nstarted: "+new Date()+"\n");

            // everything the game prints goes to this variant's log (replaces the optional debug log of the app)
            logWriter = new BufferedWriter(new FileWriter(new File(variantDir, "log.txt")));
            final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            logCallback = (line) -> {
                if (line == null || line.startsWith("xserver:")) return;
                if (variant.perf) onGameLine(line);
                synchronized (Session.this) {
                    try {
                        if (logWriter != null) logWriter.write("["+time.format(new Date())+"] "+line+"\n");
                    }
                    catch (Exception e) {}
                }
            };
            ProcessHelper.addDebugCallback(logCallback);
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}); // own logcat starts empty for this variant
            }
            catch (Exception e) {}

            long seconds = variant.perf ? PERF_MAX_SECONDS : (longMode ? LONG_SECONDS : SHORT_SECONDS);
            // watchdog: the game never started (stuck unpacking, missing files, ...)
            handler.postDelayed(() -> finish("timeout: game environment did not start within "+(seconds + 120)+" s"), (seconds + 120) * 1000);
        }

        /**
         * Performance tests: the measurement starts when the game is ready. With a save: after "Loading script.dat" (the
         * map is loaded); main menu: after "Factorio initialised". Then PERF_SETTLE_SECONDS wait, PERF_MEASURE_SECONDS measure.
         */
        private void onGameLine(String line) {
            if (measureScheduled) return;
            boolean saveLoaded = line.contains("Loading script.dat");
            boolean menuReady = line.contains("Factorio initialised") && !willLoadSave();
            if (!saveLoaded && !menuReady) return;
            measureScheduled = true;
            handler.post(() -> {
                if (monitor != null) monitor.setPhase("settle");
                handler.postDelayed(() -> {
                    if (monitor != null) monitor.setPhase("measure");
                    handler.postDelayed(() -> {
                        if (monitor != null) monitor.setPhase("end");
                        finish("completed");
                    }, PERF_MEASURE_SECONDS * 1000L);
                }, PERF_SETTLE_SECONDS * 1000L);
            });
        }

        private boolean willLoadSave() {
            String save = state.optString("save");
            return variant.loadGame && !save.isEmpty() && new File(save).isFile();
        }


        // ---- automatic picture analysis: stripe score + "frozen picture" detection -------------------------------------
        private long lastSignature = 0;
        private String pictureNote = "";
        private int frozenCount = 0;

        /**
         * stripe score = mean |pixel - pixel below| / mean |pixel - pixel to the right| on sampled rows. A normal picture is about 1
         * (a bit more for pipes and rails), horizontal tearing / stale-cache stripes give clearly more than 1.5.
         * signature: a hash of sampled pixels; the same value in consecutive screenshots means the picture does not change (game hung or loading).
         */
        private void analyzeScreenshot(String name, Bitmap bitmap) {
            try {
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                if (width < 64 || height < 64) return;

                int[] row = new int[width];
                int[] below = new int[width];
                double vSum = 0, hSum = 0;
                long vCount = 0, hCount = 0, signature = 1469598103934665603L;
                int yStart = height / 8, yEnd = height - height / 8;
                for (int y = yStart; y < yEnd - 1; y += 7) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1);
                    bitmap.getPixels(below, 0, width, 0, y + 1, width, 1);
                    for (int x = width / 8; x < width - width / 8 - 1; x += 3) {
                        int a = luma(row[x]);
                        vSum += Math.abs(a - luma(below[x]));
                        hSum += Math.abs(a - luma(row[x + 1]));
                        vCount++;
                        hCount++;
                        signature = (signature ^ row[x]) * 1099511628211L;
                    }
                }

                double v = vCount > 0 ? vSum / vCount : 0, h = hCount > 0 ? hSum / hCount : 0;
                double score = h > 0.01 ? v / h : 0;
                boolean frozen = lastSignature != 0 && signature == lastSignature;
                if (frozen) frozenCount++;
                lastSignature = signature;

                appendLine(new File(variantDir, "result.txt"), String.format(Locale.US,
                    "picture %-9s stripe score %.2f (vertical %.1f / horizontal %.1f)%s", name, score, v, h,
                    frozen ? "   SAME AS THE PREVIOUS SCREENSHOT (game hung or still loading)" : ""));
                if (name.equals("final")) {
                    pictureNote = String.format(Locale.US, "      picture: stripe score %.2f%s\n", score, frozenCount > 0 ? ", frozen picture "+frozenCount+"x" : "");
                }
            }
            catch (Throwable e) {
                appendLine(new File(variantDir, "result.txt"), "picture analysis failed: "+e);
            }
        }

        private int luma(int color) {
            return ((color >> 16 & 0xFF) * 3 + (color >> 8 & 0xFF) * 6 + (color & 0xFF)) / 10;
        }

        /** The game process was started: the timer of this setup starts now. */
        public void onEnvironmentStarted(XServerView view) {
            this.xServerView = view;
            this.startedAt = System.currentTimeMillis();
            // performance log for every setup (perf.csv / perf-summary.txt in the setup's folder)
            monitor = PerfMonitor.start(activity, variantDir, variant.id+"  "+variant.title);
            monitor.setPhase("loading");
            if (variant.perf) {
                // no fixed end: the game decides when it is ready (onGameLine); PERF_MAX_SECONDS is the limit
                handler.postDelayed(() -> finish("timeout: the game was not ready for measuring within "+PERF_MAX_SECONDS+" s"), PERF_MAX_SECONDS * 1000L);
                return;
            }
            final int seconds = longMode ? LONG_SECONDS : SHORT_SECONDS;

            if (longMode) {
                for (int t = 60; t < seconds - 2; t += 60) {
                    final int at = t;
                    handler.postDelayed(() -> screenshot("shot-"+at+"s", null), t * 1000L);
                }
            }
            handler.postDelayed(() -> finish("completed"), seconds * 1000L);
        }

        private void screenshot(final String name, final Runnable done) {
            final AtomicBoolean called = new AtomicBoolean(false);
            final Runnable once = () -> {
                if (called.compareAndSet(false, true) && done != null) done.run();
            };
            try {
                if (xServerView == null || xServerView.getWidth() <= 0 || xServerView.getHeight() <= 0) {
                    once.run();
                    return;
                }
                final Bitmap bitmap = Bitmap.createBitmap(xServerView.getWidth(), xServerView.getHeight(), Bitmap.Config.ARGB_8888);
                handler.postDelayed(once, 20000); // PixelCopy sometimes never answers
                // The copy result, the picture analysis and the compression run on a worker thread: doing this on the main thread
                // for a few seconds makes MIUI (and others) treat the app as hung and kill it.
                PixelCopy.request(xServerView, bitmap, (result) -> {
                    if (result == PixelCopy.SUCCESS) {
                        analyzeScreenshot(name, bitmap);
                        try (FileOutputStream out = new FileOutputStream(new File(variantDir, name+".jpg"))) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                        }
                        catch (Exception e) {}
                    }
                    else appendLine(new File(variantDir, "result.txt"), "screenshot "+name+": PixelCopy result "+result);
                    bitmap.recycle();
                    handler.post(once);
                }, workerHandler());
            }
            catch (Throwable e) {
                appendLine(new File(variantDir, "result.txt"), "screenshot "+name+" failed: "+e);
                once.run();
            }
        }

        /** Ends this setup (also called when the game exits early) and moves on to the next one. */
        public void finish(final String status) {
            if (!finished.compareAndSet(false, true)) return;
            handler.removeCallbacksAndMessages(null);
            screenshot("final", () -> afterScreenshot(status));
        }

        private void afterScreenshot(String status) {
            try {
                ProcessHelper.removeDebugCallback(logCallback);
                synchronized (this) {
                    if (logWriter != null) logWriter.close();
                    logWriter = null;
                }
            }
            catch (Exception e) {}

            long elapsed = startedAt > 0 ? (System.currentTimeMillis() - startedAt) / 1000 : -1;
            String perfLine = "";
            if (monitor != null) {
                monitor.event("finish: "+status);
                monitor.stop();
                perfLine = "      perf: "+monitor.shortLine()+"\n";
            }
            try {
                File dir = logDir();
                if (dir != null) {
                    for (String name : new String[]{"driver-report.txt", "vortek-report.txt", "factorio-native.log", "config-patch-result.txt"}) {
                        File file = new File(dir, name);
                        if (file.isFile()) copy(file, new File(variantDir, name));
                    }
                }
                saveLogcat(new File(variantDir, "logcat.txt"));

                StringBuilder result = new StringBuilder();
                result.append(variant.id).append("  ").append(variant.title).append("\n");
                result.append("status: ").append(status).append("   game ran: ").append(elapsed).append(" s\n\n");
                result.append("key lines of the game log:\n").append(extractKeyLines(new File(variantDir, "log.txt")));
                appendLine(new File(variantDir, "result.txt"), result.toString());

                appendSummary(runDir, String.format(Locale.US, "[%d/%d] %-26s %-48s -> %s (%d s)\n", index + 1, total, variant.id, variant.title, status, elapsed)+pictureNote+perfLine);
                state.put("index", index + 1);
                writeState(activity, state);
            }
            catch (Exception e) {
                Log.e(TAG, "finish failed", e);
            }

            activity.diagShutdown();
            // give the game, the X server and Vortek time to close before the next start
            handler.postDelayed(() -> {
                if (index + 1 < total) launchVariant(activity, runDir.getPath());
                else finishRun(activity);
            }, 3000);
        }
    }

    private static void finishRun(Context context) {
        File runDir = restoreSnapshot(context);
        String zipPath = "";
        if (runDir != null) {
            appendSummary(runDir, "\nFinished "+new Date()+"\n");
            File zip = zip(runDir);
            if (zip != null) zipPath = zip.getPath();
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(EXTRA_DONE, zipPath);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------
    private static void appendSummary(File runDir, String text) {
        appendLine(new File(runDir, "summary.txt"), text);
    }

    private static void appendLine(File file, String text) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(text.endsWith("\n") ? text : text+"\n");
        }
        catch (Exception e) {}
    }

    private static void copy(File from, File to) {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        catch (Exception e) {}
    }

    private static void saveLogcat(File target) {
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "--pid="+android.os.Process.myPid()});
            try (InputStream in = process.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                long written = 0;
                while ((n = in.read(buffer)) > 0 && written < 4L * 1024 * 1024) {
                    out.write(buffer, 0, n);
                    written += n;
                }
            }
            process.destroy();
        }
        catch (Exception e) {
            appendLine(target, "logcat not available: "+e);
        }
    }

    private static String extractKeyLines(File log) {
        StringBuilder sb = new StringBuilder();
        String text = FileUtils.readString(log);
        if (text == null) return "(no game log)\n";
        int count = 0;
        for (String line : text.split("\n")) {
            String l = line.toLowerCase(Locale.US);
            if (l.contains("initialised opengl") || l.contains("graphics options") || l.contains("texture processor") || l.contains("factorio initialised") ||
                l.contains("sprites loaded") || l.contains("error") || l.contains("zink:") || l.contains("mesa:") || l.contains("warning") ||
                l.contains("vortek") || l.contains("gladio") || l.contains("unimplemented") || l.contains("segmentation") || l.contains("signal") || l.contains("cannot") || l.contains("failed") || l.contains("dedicated video memory")) {
                sb.append(line.length() > 260 ? line.substring(0, 260) : line).append('\n');
                if (++count >= 80) break;
            }
        }
        return count == 0 ? "(nothing recognised; see log.txt)\n" : sb.toString();
    }

    private static File zip(File runDir) {
        File zipFile = new File(runDir.getParentFile(), runDir.getName()+".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
            addToZip(out, runDir, runDir.getName());
            return zipFile;
        }
        catch (Exception e) {
            Log.e(TAG, "zip failed", e);
            return null;
        }
    }

    private static void addToZip(ZipOutputStream out, File file, String path) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) addToZip(out, child, path+"/"+child.getName());
        }
        else {
            out.putNextEntry(new ZipEntry(path));
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            out.closeEntry();
        }
    }
}
