package com.factorio.core;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/**
 * Private Mesa for the "Zink DRI3" test (libGL over DRI + Zink + Turnip, frames presented as DRI3 dma-bufs).
 * Built by .github/workflows/build-zinkdri.yml. Never replaces rootfs or system graphics libraries:
 * it is unpacked to rootfs/opt/zinkdri-* and put first in LD_LIBRARY_PATH only while ZINKDRI=1.
 *
 * Package source, in this order:
 *   1. container variable ZINKDRI_FILE=/storage/emulated/0/Download/zinkdri-1.tzst  (no APK rebuild needed)
 *   2. asset graphics_driver/zinkdri-1.tzst
 */
public final class ZinkDri {
    private static final String ASSET = "graphics_driver/zinkdri-1.tzst";
    private static final long MAX_FILE_SIZE = 128L * 1024 * 1024;
    private ZinkDri() {}

    /** @return the unpacked folder: lib/, dri/ and vulkan/icd.json (written here with absolute paths). */
    public static synchronized File prepare(Context context, File root, String externalFile) throws IOException {
        File source = (externalFile != null && !externalFile.isEmpty()) ? new File(externalFile) : null;
        if (source != null && !source.isFile()) throw new IOException("ZINKDRI_FILE not found or not readable: " + externalFile);

        String id = source != null ? "ext-" + source.length() + "-" + source.lastModified() : "asset-1";
        File optDir = new File(root, "opt");
        File target = new File(optDir, "zinkdri-" + id);
        File ready = new File(target, ".ready");
        if (ready.isFile() && valid(target)) return target;

        File[] old = optDir.listFiles((dir, name) -> name.startsWith("zinkdri-"));
        if (old != null) for (File f : old) FileUtils.delete(f);

        File stage = new File(optDir, "zinkdri-stage");
        FileUtils.delete(stage);
        if (!stage.mkdirs()) throw new IOException("Cannot create staging directory for zinkdri");
        try {
            final String stagePath = stage.getCanonicalPath() + File.separator;
            TarCompressorUtils.OnExtractFileListener listener = (file, size) -> {
                try {
                    if (!file.getCanonicalPath().startsWith(stagePath)) return null; // invalid archive path
                } catch (IOException e) {
                    return null;
                }
                if (size > MAX_FILE_SIZE) return null;
                return file;
            };
            boolean ok = source != null
                    ? TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, stage, listener)
                    : TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, ASSET, stage, listener);
            if (!ok) throw new IOException("Cannot extract the zinkdri package" + (source == null ? " (asset " + ASSET + " missing? set ZINKDRI_FILE)" : ""));
            if (!valid(stage)) throw new IOException("Incomplete zinkdri package (need lib/libGL.so.1 and lib/libvulkan_freedreno.so)");

            // The Vulkan ICD points at the real place the package ends up in.
            File vulkanDir = new File(stage, "vulkan");
            vulkanDir.mkdirs();
            String driverPath = new File(target, "lib/libvulkan_freedreno.so").getPath();
            FileUtils.writeString(new File(vulkanDir, "icd.json"),
                    "{\"file_format_version\":\"1.0.1\",\"ICD\":{\"library_path\":\"" + driverPath + "\",\"api_version\":\"1.4.0\"}}\n");

            if (!new File(stage, ".ready").createNewFile()) throw new IOException("Cannot mark zinkdri ready");
            FileUtils.delete(target);
            if (!stage.renameTo(target)) throw new IOException("Cannot activate the zinkdri package");
            return target;
        } finally {
            FileUtils.delete(stage);
        }
    }

    private static boolean valid(File directory) {
        return new File(directory, "lib/libGL.so.1").length() > 100000
            && new File(directory, "lib/libvulkan_freedreno.so").length() > 1000000;
    }
}
