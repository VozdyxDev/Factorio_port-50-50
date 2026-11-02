package com.factorio.xenvironment.components;

import android.os.Process;


import androidx.preference.PreferenceManager;

import com.factorio.R;
import com.factorio.container.Container;
import com.factorio.container.GraphicsDrivers;
import com.factorio.core.AppUtils;
import com.factorio.core.BundledOpenGL;
import com.factorio.core.Callback;
import com.factorio.core.DownloadProgressDialog;
import com.factorio.core.EnvVars;
import com.factorio.core.FileUtils;
import com.factorio.core.GameArchive;
import com.factorio.core.GameCompression;
import com.factorio.core.GameCopier;
import com.factorio.core.LocaleHelper;
import com.factorio.core.ProcessHelper;
import com.factorio.widget.LogView;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xenvironment.EnvironmentComponent;
import com.factorio.xenvironment.RootFS;
import com.factorio.xserver.XServer;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static int pid = -1;
    private EnvVars envVars;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        // NATIVE_EXEC: run the native ARM64 glibc game binary directly (there is no Wine / Box64 in this app).
        // Container Settings -> Environment Variables:
        //   NATIVE_SRC  = /storage/emulated/0/Download/factorio   (game folder on phone storage, optional)
        //   NATIVE_EXEC = /home/xuser/factorio/bin/arm64/factorio (path inside rootfs)
        // NATIVE_SRC is copied once into rootfs/home/xuser/ because binaries cannot
        // be executed from shared storage. The binary is started through the rootfs
        // glibc loader because Android has no /lib/ld-linux-aarch64.so.1.
        String nativeExec = envVars.get("NATIVE_EXEC");
        if (nativeExec == null || nativeExec.isEmpty()) nativeExec = Container.DEFAULT_NATIVE_EXEC;
        String command;
        String nativeSrc = envVars.get("NATIVE_SRC");
        final android.app.Activity activity = (android.app.Activity)environment.getContext();
        final int compression = PreferenceManager.getDefaultSharedPreferences(activity).getInt(GameCompression.PREF_KEY, GameCompression.ORIGINAL);
        String gameDirName = nativeSrc != null && !nativeSrc.isEmpty() ? new File(nativeSrc).getName() : "factorio";
        File gameDir = new File(rootDir, RootFS.HOME_PATH+"/"+gameDirName);

        // 1) an archive the user picked (or, if the game is not installed and there is no folder, the newest
        //    factorio*linux-arm64*_X.Y.Z.tar.xz in Download): unpacked straight into the rootfs
        GameArchive.Source archive = GameArchive.sourceToInstall(activity, gameDir, nativeSrc);
        if (archive != null) {
            final DownloadProgressDialog installDialog = new DownloadProgressDialog(activity);
            activity.runOnUiThread(() -> installDialog.show(R.string.installing_game_archive));
            GameCopier.Result installResult = GameArchive.install(activity, archive, gameDir, compression, (percent) -> activity.runOnUiThread(() -> installDialog.setProgress(percent)));
            installDialog.closeOnUiThread();
            writeCopyReport(activity, gameDir, installResult);
            final String message = installResult.ok
                ? activity.getString(R.string.game_archive_installed, archive.version != null ? archive.version : archive.name)
                : activity.getString(R.string.game_archive_failed, installResult.error);
            activity.runOnUiThread(() -> AppUtils.showToast(activity, message));
            if (installResult.ok && compression != GameCompression.ORIGINAL) showCompressionSummary(activity, installResult);
        }

        // 2) the game folder (NATIVE_SRC, e.g. Download/factorio) is copied once, as before
        if (nativeSrc != null && !nativeSrc.isEmpty()) {
            File srcDir = new File(nativeSrc);
            File dstDir = gameDir;
            File doneMark = new File(dstDir, GameArchive.DONE_MARK);
            if (srcDir.isDirectory() && !doneMark.exists()) {
                // copy was never finished (or first run): start clean, then mark as done
                FileUtils.delete(dstDir);

                // One-time copy with a progress screen; the texture compression level was chosen on the first start.
                final DownloadProgressDialog copyDialog = new DownloadProgressDialog(activity);
                activity.runOnUiThread(() -> copyDialog.show(R.string.copying_game));
                GameCopier.Result copyResult = GameCopier.copy(srcDir, dstDir, compression, (percent) -> activity.runOnUiThread(() -> copyDialog.setProgress(percent)));
                boolean copied = copyResult.ok;
                copyDialog.closeOnUiThread();

                writeCopyReport(activity, dstDir, copyResult);
                if (compression != GameCompression.ORIGINAL) showCompressionSummary(activity, copyResult);
                if (copied) {
                    try { doneMark.createNewFile(); } catch (Exception e) {}
                }
            }
        }

        File nativeFile = new File(nativeExec.startsWith("/") && !nativeExec.startsWith("/storage") ? rootDir+nativeExec : nativeExec);
        if (!nativeFile.isFile()) {
            // Without this check the glibc loader only prints "cannot open shared object file" for the game binary.
            final String reason = describeMissingGame(nativeSrc, nativeFile);
            android.util.Log.e("Factorio", reason);
            File missingLogDir = LogView.getLogFile().getParentFile();
            if (missingLogDir != null && missingLogDir.isDirectory() && missingLogDir.canWrite()) {
                FileUtils.writeString(new File(missingLogDir, "factorio-native.log"), reason+"\n");
            }
            final android.app.Activity missingActivity = (android.app.Activity)environment.getContext();
            missingActivity.runOnUiThread(() -> AppUtils.showToast(missingActivity, reason));
            return -1;
        }
        nativeFile.setExecutable(true);
        String loader = rootFS.getLibDir()+"/ld-linux-aarch64.so.1";
        // Because the game is started through the glibc loader, /proc/self/exe points to the
        // loader (rootfs/usr/lib), so Factorio cannot find its data folder by itself.
        // Give it an explicit config with absolute read-data / write-data paths.
        String extraArgs = "";
        File binDir = nativeFile.getParentFile();
        File gameRoot = (binDir != null && binDir.getParentFile() != null) ? binDir.getParentFile().getParentFile() : null;
        if (gameRoot != null && (new File(gameRoot, "data")).isDirectory()) {
            File configFile = new File(gameRoot, "config-native.ini");

            // The config file is kept between launches so in-game graphics settings persist.
            // It is (re)generated when it does not exist, is not a valid config with absolute paths
            // (e.g. after a reset in the game), or when the container variable NATIVE_GFX is set:
            //   NATIVE_GFX=video-memory-usage=high;graphics-quality=medium   (override keys, rewrite the file)
            //   NATIVE_GFX=reset                                              (rewrite the file with defaults)
            String gfxExtra = envVars.get("NATIVE_GFX");
            boolean forceWrite = gfxExtra != null && !gfxExtra.isEmpty();
            boolean keepExisting = false;
            if (!forceWrite && configFile.isFile()) {
                byte[] currentBytes = FileUtils.read(configFile);
                String current = currentBytes != null ? new String(currentBytes, java.nio.charset.StandardCharsets.UTF_8) : null;
                keepExisting = current != null && current.contains("read-data=") && !current.contains("__PATH__");
            }

            if (!keepExisting) {
                com.factorio.core.Diagnostics.backupConfig(environment.getContext(), configFile);
                java.util.LinkedHashMap<String, String> gfx = new java.util.LinkedHashMap<>();
                gfx.put("max-sprite-loading-threads", "2");
                gfx.put("texture-compression-level", "low-quality");
                gfx.put("gpu-accelerated-compression", "false");
                gfx.put("video-memory-usage", "medium");
                gfx.put("graphics-quality", "medium");

                if (forceWrite && !gfxExtra.trim().equals("reset")) {
                    for (String item : gfxExtra.split(";")) {
                        int eq = item.indexOf('=');
                        if (eq > 0) gfx.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
                    }
                }

                StringBuilder config = new StringBuilder("[path]\nread-data="+gameRoot+"/data\nwrite-data="+gameRoot+"\n[graphics]\n");
                for (java.util.Map.Entry<String, String> entry : gfx.entrySet()) {
                    config.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
                }
                FileUtils.writeString(configFile, config.toString());
            }
            // NATIVE_GFX_PATCH=key=value;key2=value2 (performance tests): changes only keys that already are in the
            // game's own config (the user's settings stay; an unknown key is never added, which would make the game
            // reject the file). The result is written to config-patch-result.txt next to the logs.
            String gfxPatch = envVars.get("NATIVE_GFX_PATCH");
            if (gfxPatch != null && !gfxPatch.isEmpty() && configFile.isFile()) {
                com.factorio.core.Diagnostics.backupConfig(environment.getContext(), configFile);
                String text = FileUtils.readString(configFile);
                StringBuilder report = new StringBuilder("NATIVE_GFX_PATCH="+gfxPatch+"\n");
                if (text != null) {
                    for (String item : gfxPatch.split(";")) {
                        int eq = item.indexOf('=');
                        if (eq <= 0) continue;
                        String key = item.substring(0, eq).trim(), value = item.substring(eq + 1).trim();
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^"+java.util.regex.Pattern.quote(key)+"=.*$").matcher(text);
                        if (matcher.find()) {
                            report.append("  ").append(matcher.group()).append(" -> ").append(key).append('=').append(value).append('\n');
                            text = matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(key+"="+value));
                        }
                        else report.append("  ").append(key).append(": NOT in the config, not changed\n");
                    }
                    FileUtils.writeString(configFile, text);
                }
                File reportDir = LogView.getLogFile().getParentFile();
                if (reportDir != null && reportDir.isDirectory()) FileUtils.writeString(new File(reportDir, "config-patch-result.txt"), report.toString());
            }
            extraArgs = " --config "+configFile.getPath();
        }
        // Factorio saves its fullscreen window size into the very same --config ini file
        // (which we now keep between launches so in-game graphics settings persist), so an
        // old window size silently sticks even after the container's screen resolution is
        // changed later. --window-size is an official CLI flag that always wins over any
        // saved value, without touching (and discarding) the rest of the saved settings.
        XServerComponent xServerComponent = environment.getComponent(XServerComponent.class);
        if (xServerComponent != null) {
            XServer nativeXServer = xServerComponent.getXServer();
            if (nativeXServer != null) {
                extraArgs += " --window-size "+nativeXServer.screenInfo.width+"x"+nativeXServer.screenInfo.height;
            }
        }

        String nativeArgs = envVars.get("NATIVE_ARGS");
        if (nativeArgs != null && !nativeArgs.isEmpty()) extraArgs += " "+nativeArgs;
        // NATIVE_LOAD_GAME=<save path> (performance tests): start straight into that save
        String loadGame = envVars.get("NATIVE_LOAD_GAME");
        if (loadGame != null && !loadGame.isEmpty() && new File(loadGame).isFile()) extraArgs += " --load-game "+loadGame;

        // NATIVE_MESA_SRC: use a Mesa build with the Freedreno/KGSL OpenGL driver (direct OpenGL, no Zink).
        //   NATIVE_MESA_SRC = /storage/emulated/0/Download/mesa   (folder that contains lib/ and dri/)
        // The folder is copied once into rootfs/opt/mesa-kgsl and put first in the library path.
        // Variables set by the container (MESA_LOADER_DRIVER_OVERRIDE, LIBGL_DRIVERS_PATH, ...) win over the defaults below.
        String libPath = rootFS.getLibDir().getPath();
        // Libraries a driver package brought along (see build-turnip.yml): searched LAST, so rootfs libraries always win
        File driverDepsDir = new File(rootFS.getLibDir(), "turnip-deps");
        if (driverDepsDir.isDirectory()) libPath += ":"+driverDepsDir.getPath();
        Container selectedContainer = ((com.factorio.XServerDisplayActivity)environment.getContext()).getContainer();
        boolean nativeGL = GraphicsDrivers.OPENGL.equals(
                GraphicsDrivers.parseIdentifiers(selectedContainer.getGraphicsDriver())[1]);
        // Legacy overrides must never make the selected Zink backend load Freedreno.
        envVars.remove("LIBGL_DRIVERS_PATH");
        envVars.remove("MESA_LOADER_DRIVER_OVERRIDE");
        envVars.remove("__GLX_VENDOR_LIBRARY_NAME");
        if (nativeGL) {
            File mesaDir;
            try {
                mesaDir = BundledOpenGL.prepare(environment.getContext(), rootDir);
            } catch (java.io.IOException e) {
                android.util.Log.e("Factorio", "Cannot prepare bundled OpenGL", e);
                return -1;
            }
            libPath = mesaDir+"/lib:"+libPath;
            envVars.put("LIBGL_DRIVERS_PATH", mesaDir+"/dri");
            envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
            envVars.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
            envVars.remove("GALLIUM_DRIVER");
            envVars.remove("ZINK_CONTEXT_THREADED");
            envVars.remove("ZINK_DESCRIPTORS");
            envVars.remove("ZINK_DEBUG");
            envVars.remove("MESA_GL_VERSION_OVERRIDE");
        }
        // Zink DRI package (build-zink-dri.yml): its own Mesa in usr/lib/zink-dri. Zink then shows the frames through
        // Turnip's Vulkan swapchain (kopper -> DRI3 / Present) instead of reading every frame back like the xlib build.
        File zinkDriDir = new File(rootFS.getLibDir(), "zink-dri");
        boolean zinkDri = !nativeGL && GraphicsDrivers.ZINK.equals(GraphicsDrivers.parseIdentifiers(selectedContainer.getGraphicsDriver())[1])
                && new File(zinkDriDir, "libGL.so.1").exists();
        if (zinkDri) {
            libPath = zinkDriDir.getPath()+":"+libPath;
            File zinkDriDeps = new File(zinkDriDir, "deps");
            if (zinkDriDeps.isDirectory()) libPath += ":"+zinkDriDeps.getPath(); // after the rootfs: its libraries win
            envVars.put("LIBGL_DRIVERS_PATH", zinkDriDir.getPath()+"/dri");
            envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envVars.put("GALLIUM_DRIVER", "zink");
            // GLX through drisw = kopper (Vulkan swapchain). DRI3 in GLX would need a DRM device the X server does not give.
            // A container variable LIBGL_DRI3_DISABLE (e.g. =0 for tests) wins.
            if (!envVars.has("LIBGL_DRI3_DISABLE")) envVars.put("LIBGL_DRI3_DISABLE", "1");
            envVars.remove("ZINK_CONTEXT_THREADED"); // only the patched xlib build reads it
        }
        envVars.put("LD_LIBRARY_PATH", libPath);

        // X_UNSET=A,B,C (tests): these variables are removed last, whoever set them (container, driver dialogs, defaults)
        String unset = envVars.get("X_UNSET");
        if (unset != null && !unset.isEmpty()) {
            for (String name : unset.split(",")) {
                if (!name.trim().isEmpty()) envVars.remove(name.trim());
            }
            envVars.remove("X_UNSET");
        }

        // Factorio does not know that it runs through the glibc loader:
        //  - /proc/self/exe points to the loader, so it detects a wrong binaries path (rootfs/usr) and the updater fails
        //    with "File .../usr/arm64/factorio not found"  -> --executable-path tells it where the executable is;
        //  - after an update it restarts itself with execv(<executable-path>), and the real binary cannot be started
        //    directly on Android (its ELF interpreter does not exist).
        // So --executable-path points to a tiny launcher script located next to the real binary
        // (bin/arm64/): it starts the real binary through the loader with the same arguments.
        File launcherScript = new File(nativeFile.getParentFile(), "factorio-start.sh");
        FileUtils.writeString(launcherScript, "#!/system/bin/sh\nexec "+loader+" --library-path "+libPath+" "+nativeFile.getPath()+" \"$@\"\n");
        launcherScript.setExecutable(true, false);
        if (extraArgs.indexOf("--executable-path") < 0) extraArgs += " --executable-path "+launcherScript.getPath();

        // NATIVE_PRELOAD: glibc libraries preloaded into the game only (input interposer + fake libudev for gamepads).
        // --preload is used instead of LD_PRELOAD because LD_PRELOAD would also hit Android's /system/bin/sh
        // (factorio-start.sh) and break it.
        String nativePreload = envVars.get("NATIVE_PRELOAD");
        // Gladio's libGL.so.1 uses libX11 functions (XGetVisualInfo ...) but is not linked against libX11, and SDL opens libX11 privately,
        // so without this SDL fails with "Failed loading libGL.so.1: undefined symbol: XGetVisualInfo". Preloading libX11 makes the symbols global.
        if (envVars.get("GLADIO_NO_ERROR") != null) {
            for (String dir : libPath.split(":")) {
                File x11 = new File(dir, "libX11.so.6");
                if (x11.isFile()) {
                    nativePreload = x11.getPath()+(nativePreload != null && !nativePreload.isEmpty() ? ":"+nativePreload : "");
                    break;
                }
            }
        }
        String preloadArg = nativePreload != null && !nativePreload.isEmpty() ? " --preload "+nativePreload : "";
        command = loader+preloadArg+" --library-path "+libPath+" "+nativeFile.getPath()+extraArgs;
        File workDir = nativeFile.getParentFile();
        if (workDir != null && workDir.isDirectory()) rootDir = workDir;
        envVars.remove("LD_PRELOAD");
        // Factorio's updater restarts through factorio-start.sh, whose interpreter is
        // Android's /system/bin/sh. Do not expose the rootfs glibc directory through
        // LD_LIBRARY_PATH to Android executables: Android's linker would try to load
        // rootfs/usr/lib/libc.so and fail with "bad ELF magic" before the script runs.
        // The game and its restart script already pass the guest libraries explicitly
        // with the glibc loader's --library-path argument.
        envVars.remove("LD_LIBRARY_PATH");

        // Log the game output to Documents/Factorio/factorio-native.log (cheap, works without the debug window)
        File logDir = LogView.getLogFile().getParentFile();
        if (logDir != null && logDir.isDirectory() && logDir.canWrite()) {
            File nativeLogFile = new File(logDir, "factorio-native.log");
            FileUtils.delete(nativeLogFile);
            envVars.put("NATIVE_LOG_FILE", nativeLogFile.getPath());
        }
    

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }

    /** file count + total size + newest modification time: cheap, and changes when a new build replaces the old one */
    private static String folderSignature(File dir) {
        long[] acc = new long[3];
        collectSignature(dir, acc);
        return acc[0]+"/"+acc[1]+"/"+acc[2];
    }

    private static void collectSignature(File file, long[] acc) {
        File[] children = file.listFiles();
        if (children == null) {
            acc[0]++;
            acc[1] += file.length();
            acc[2] = Math.max(acc[2], file.lastModified());
            return;
        }
        for (File child : children) collectSignature(child, acc);
    }

    /** compression-report.txt next to the logs and next to the game folder (visible in the file manager, root "Game"). */
    private static void writeCopyReport(android.app.Activity activity, File gameDir, GameCopier.Result result) {
        File reportDir = LogView.getLogFile().getParentFile();
        if (reportDir != null && reportDir.isDirectory() && reportDir.canWrite()) {
            FileUtils.writeString(new File(reportDir, "compression-report.txt"), result.report);
        }
        File homeDir = gameDir.getParentFile();
        if (homeDir != null && homeDir.isDirectory()) FileUtils.writeString(new File(homeDir, "compression-report.txt"), result.report);
    }

    private static void showCompressionSummary(android.app.Activity activity, GameCopier.Result result) {
        final String summary = activity.getString(R.string.compression_done, result.pngShrunk, result.pngTotal,
            (int)(result.inBytes >> 20), (int)(result.outBytes >> 20));
        activity.runOnUiThread(() -> AppUtils.showToast(activity, summary));
    }

    /** Explains why the game executable is not inside the rootfs (see NATIVE_SRC / NATIVE_EXEC in the container variables). */
    private static String describeMissingGame(String nativeSrc, File nativeFile) {
        String head = "Game not found: "+nativeFile.getPath()+". ";
        if (nativeSrc == null || nativeSrc.isEmpty()) {
            return head+"NATIVE_SRC is not set, so nothing was copied into the app. Set NATIVE_SRC to the game folder (it must contain bin/arm64/factorio and data/).";
        }
        File srcDir = new File(nativeSrc);
        if (!srcDir.exists()) return head+"The folder from NATIVE_SRC does not exist or the app cannot see it: "+nativeSrc+". Check the path and give the app access to all files / storage.";
        if (!srcDir.isDirectory()) return head+"NATIVE_SRC is not a folder: "+nativeSrc;
        String[] children = srcDir.list();
        if (children == null) return head+"The app has no permission to read "+nativeSrc+". Grant storage / all-files access, or move the game to another folder.";
        File srcBinary = new File(srcDir, "bin/arm64/factorio");
        if (!srcBinary.isFile()) return head+"There is no bin/arm64/factorio inside "+nativeSrc+" (this build needs the Linux ARM64 game folder).";
        return head+"The copy into the app did not finish (free storage space? interrupted copy?). Start the game again; if it repeats, delete rootfs/home/xuser/"+srcDir.getName()+" and retry.";
    }
}
