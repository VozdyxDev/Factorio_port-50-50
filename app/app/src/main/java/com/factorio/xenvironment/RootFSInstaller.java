package com.factorio.xenvironment;

import com.factorio.core.AppExecutors;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;


import com.factorio.MainActivity;
import com.factorio.R;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.core.AppUtils;
import com.factorio.core.DownloadProgressDialog;
import com.factorio.core.FileUtils;
import com.factorio.core.GameCompression;
import com.factorio.core.ScreenResolution;
import com.factorio.core.TarCompressorUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    public static final byte LATEST_VERSION = 21; // TODO increment it on rootfs update (21: + ALSA android_aserver plugin and cursors that used to come from rootfs_patches)
    public static final String FILENAME = "rootfs.tzst";

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }

    // True on the very first start, before the default container exists: the user picks 720p / 1080p / native.
    private static boolean needsFirstRunResolution(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return !preferences.getBoolean("default_container_created", false) && new ContainerManager(context).getContainers().isEmpty();
    }

    public static void install(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();
        final boolean askResolution = needsFirstRunResolution(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);

        // UI-thread state: the copy runs in the background while the user picks the resolution, then the game compression.
        final int[] resolutionChoice = {-1};
        final int[] compressionChoice = {-1};
        final boolean[] extracted = {false};
        final Runnable finish = () -> {
            dialog.hideChoice();
            dialog.close();
            createDefaultContainerIfNeeded(activity);
        };

        if (askResolution) {
            // order on screen: High, Low, Original (button index 0..2 -> GameCompression.HIGH..ORIGINAL)
            final String[] compressionLabels = {
                activity.getString(R.string.compression_high) + "\nx5",
                activity.getString(R.string.compression_low) + "\nx2",
                activity.getString(R.string.compression_original) + "\n" + activity.getString(R.string.compression_original_hint)
            };
            final Runnable askCompression = () -> dialog.showChoice(R.string.choose_compression, compressionLabels, (index) -> {
                int mode = GameCompression.HIGH - index;
                compressionChoice[0] = mode;
                PreferenceManager.getDefaultSharedPreferences(activity).edit().putInt(GameCompression.PREF_KEY, mode).apply();
                if (extracted[0]) finish.run();
            });

            String[] labels = {
                "720p\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_720P),
                "1080p\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_1080P),
                activity.getString(R.string.resolution_native) + "\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_NATIVE)
            };
            dialog.showChoice(R.string.choose_resolution, labels, (mode) -> {
                resolutionChoice[0] = mode;
                PreferenceManager.getDefaultSharedPreferences(activity).edit().putInt(ScreenResolution.PREF_FIRST_RUN_MODE, mode).apply();
                askCompression.run(); // step 2
            });
        }

        AppExecutors.execute(() -> {
            clearRootDir(rootDir);
            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir);
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
                activity.runOnUiThread(() -> {
                    extracted[0] = true;
                    if (!askResolution || compressionChoice[0] >= 0) finish.run();
                    else dialog.setChoiceHint(resolutionChoice[0] >= 0 ? R.string.choose_compression_waiting : R.string.choose_resolution_waiting); // wait for the taps
                });
            }
            else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                activity.runOnUiThread(activity::hideStartupCover);
                dialog.closeOnUiThread();
            }
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        RootFS rootFS = RootFS.find(activity);
        if (!rootFS.isValid() || rootFS.getVersion() < LATEST_VERSION) install(activity);
        else createDefaultContainerIfNeeded(activity);
    }

    // Creates one ready-to-run container (Turnip + Zink + Factorio variables) on the very first start.
    // It is created only once: if the user later deletes all containers, it is not recreated.
    public static void createDefaultContainerIfNeeded(final MainActivity activity) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (preferences.getBoolean("default_container_created", false)) {
            activity.autoStartContainer();
            return;
        }

        final ContainerManager manager = new ContainerManager(activity);
        if (!manager.getContainers().isEmpty()) {
            preferences.edit().putBoolean("default_container_created", true).apply();
            activity.autoStartContainer();
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", "Factorio");
            int resolutionMode = preferences.getInt(ScreenResolution.PREF_FIRST_RUN_MODE, ScreenResolution.MODE_720P);
            data.put("screenSize", ScreenResolution.forMode(activity, resolutionMode)); // follows the real screen aspect ratio
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(activity));
            manager.createContainerAsync(data, (container) -> {
                if (container != null) {
                    preferences.edit().putBoolean("default_container_created", true).apply();
                    activity.reloadSettings(); // show the new container in the settings right away
                    activity.autoStartContainer();   // and start it
                }
                else activity.hideStartupCover();
            });
        }
        catch (JSONException e) {
            activity.hideStartupCover();
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) continue; // containers (and the game inside) are kept
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}
