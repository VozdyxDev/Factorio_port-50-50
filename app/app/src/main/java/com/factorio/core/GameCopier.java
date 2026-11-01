package com.factorio.core;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Copies the game folder and, depending on the chosen mode, shrinks its PNG textures on the way. */
public final class GameCopier {
    private static final String TAG = "GameCopier";
    private static final int MAX_LISTED_SKIPS = 3000;

    public interface Progress {
        void onProgress(int percent);
    }

    /** What happened: the numbers for the toast and the text for compression-report.txt. */
    public static final class Result {
        public boolean ok;
        public int files;
        public int pngTotal, pngShrunk, skipFormat, skipSmall, skipGain, skipProtected, pngErrors;
        public long inBytes, outBytes;
        public String report = "";
        public String error;       // short reason for the user when ok == false (archive install)
    }

    private GameCopier() {}

    private static void collect(File dir, List<File> dirs, List<File> files) {
        String[] names = dir.list();
        if (names == null) return;
        for (String name : names) {
            File file = new File(dir, name);
            if (FileUtils.isSymlink(file)) continue; // like FileUtils.copy: symlinks are skipped
            if (file.isDirectory()) {
                dirs.add(file);
                collect(file, dirs, files);
            }
            else files.add(file);
        }
    }

    private static File target(File srcDir, File dstDir, File file) {
        String relative = file.getPath().substring(srcDir.getPath().length());
        while (relative.startsWith("/")) relative = relative.substring(1);
        return new File(dstDir, relative);
    }

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "(no ext)";
    }

    private static void addStat(Map<String, long[]> stats, String key, long size) {
        long[] entry = stats.computeIfAbsent(key, (k) -> new long[2]);
        synchronized (entry) {
            entry[0]++;
            entry[1] += size;
        }
    }

    /**
     * Shrinks / copies single files and keeps the statistics for the report. Shared by the folder copy below and by
     * {@link GameArchive} (archive install), so both give the same textures and the same compression-report.txt.
     */
    static final class Job {
        final int mode, ratio, minColors, kmeans, dither;
        final boolean shrink;
        final AtomicLong inBytes = new AtomicLong(), outBytes = new AtomicLong();
        final AtomicInteger files = new AtomicInteger();
        final AtomicInteger pngTotal = new AtomicInteger(), pngShrunk = new AtomicInteger(), skipFormat = new AtomicInteger();
        final AtomicInteger skipSmall = new AtomicInteger(), skipGain = new AtomicInteger(), skipProtected = new AtomicInteger(), pngErrors = new AtomicInteger();
        final AtomicBoolean failed = new AtomicBoolean();
        final ConcurrentLinkedQueue<String> skipped = new ConcurrentLinkedQueue<>();
        final Map<String, long[]> untouched = new ConcurrentHashMap<>(); // extension -> {count, bytes} of files kept as they are

        Job(int mode) {
            this.mode = mode;
            ratio = GameCompression.textureRatio(mode);
            minColors = GameCompression.textureMinColors(mode);
            kmeans = GameCompression.textureKmeans(mode);
            dither = GameCompression.textureDither(mode);
            shrink = ratio > 0 && TextureCompressor.isAvailable();
        }

        int threads() {
            int cpus = Runtime.getRuntime().availableProcessors();
            return shrink ? Math.max(2, Math.min(3, cpus / 2)) : 2; // shrinking needs memory: keep few workers
        }

        /** true when this file goes through the texture compressor (worth a worker thread). */
        boolean wantsShrink(String relativePath) {
            return shrink && relativePath.toLowerCase(Locale.ROOT).endsWith(".png") && !GameCompression.isProtected(relativePath);
        }

        /**
         * Writes dst from src: shrunk PNG or a plain copy / move.
         * @param moveSource src is a temporary file: it is renamed (or deleted) instead of copied
         */
        void process(File src, File dst, String relativePath, boolean moveSource) {
            if (failed.get()) return;
            long size = src.length();
            files.incrementAndGet();

            boolean isPng = relativePath.toLowerCase(Locale.ROOT).endsWith(".png");
            boolean written = false;
            String why = null;
            if (isPng) pngTotal.incrementAndGet();
            if (shrink && isPng && GameCompression.isProtected(relativePath)) {
                why = "protected (menu / GUI / LUT / icons)";
                skipProtected.incrementAndGet();
            }
            else if (shrink && isPng) {
                int rc;
                try {
                    rc = TextureCompressor.shrinkPng(src.getPath(), dst.getPath(), ratio, minColors, kmeans, dither);
                }
                catch (Throwable e) {
                    Log.e(TAG, "Cannot shrink "+src, e);
                    rc = -1;
                }
                switch (rc) {
                    case TextureCompressor.OK: written = true; pngShrunk.incrementAndGet(); break;
                    case TextureCompressor.SKIP_FORMAT: why = "format (not 8-bit PNG / interlaced / too big)"; skipFormat.incrementAndGet(); break;
                    case TextureCompressor.SKIP_SMALL: why = "tiny file"; skipSmall.incrementAndGet(); break;
                    case TextureCompressor.SKIP_GAIN: why = "gain too small"; skipGain.incrementAndGet(); break;
                    default: why = "error "+rc; pngErrors.incrementAndGet(); break;
                }
            }
            if (written) {
                if (moveSource) src.delete();
            }
            else {
                boolean ok = moveSource ? (src.renameTo(dst) || (FileUtils.copy(src, dst) && src.delete())) : FileUtils.copy(src, dst);
                if (!ok) failed.set(true);
                addStat(untouched, extension(dst), size);
                if (why != null && skipped.size() < MAX_LISTED_SKIPS) skipped.add(why+"\t"+size+"\t"+relativePath);
            }

            inBytes.addAndGet(size);
            outBytes.addAndGet(dst.length());
        }

        /** A file that was written directly (no compressor): only counted. */
        void countPlain(File dst, long size) {
            files.incrementAndGet();
            if (dst.getName().toLowerCase(Locale.ROOT).endsWith(".png")) pngTotal.incrementAndGet();
            addStat(untouched, extension(dst), size);
            inBytes.addAndGet(size);
            outBytes.addAndGet(size);
        }

        Result result(String source) {
            Result result = new Result();
            result.ok = !failed.get();
            result.files = files.get();
            result.pngTotal = pngTotal.get(); result.pngShrunk = pngShrunk.get();
            result.skipFormat = skipFormat.get(); result.skipSmall = skipSmall.get(); result.skipGain = skipGain.get(); result.skipProtected = skipProtected.get(); result.pngErrors = pngErrors.get();
            result.inBytes = inBytes.get(); result.outBytes = outBytes.get();

            StringBuilder sb = new StringBuilder();
            sb.append("Game copy report\n");
            if (source != null) sb.append("source: ").append(source).append('\n');
            sb.append("mode: ").append(mode == GameCompression.HIGH ? "high (x"+ratio+")" : (mode == GameCompression.LOW ? "low (x"+ratio+")" : "original")).append('\n');
            sb.append("files: ").append(result.files).append(", size: ").append(result.inBytes >> 20).append(" MB -> ").append(result.outBytes >> 20).append(" MB\n");
            if (ratio > 0) {
                sb.append("texture compressor available: ").append(shrink).append('\n');
                sb.append("PNG files: ").append(result.pngTotal).append(", shrunk: ").append(result.pngShrunk)
                  .append(", kept as they are: format ").append(result.skipFormat).append(", tiny ").append(result.skipSmall)
                  .append(", small gain ").append(result.skipGain).append(", protected (menu/GUI/LUT/icons) ").append(result.skipProtected).append(", errors ").append(result.pngErrors).append('\n');
            }
            sb.append("\nCopied unchanged (extension: files, MB):\n");
            List<Map.Entry<String, long[]>> entries = new ArrayList<>(untouched.entrySet());
            Collections.sort(entries, (a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
            for (Map.Entry<String, long[]> entry : entries) {
                sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()[0]).append(" files, ").append(entry.getValue()[1] >> 20).append(" MB\n");
            }
            if (!skipped.isEmpty()) {
                sb.append("\nPNG files kept as they are (reason, bytes, path):\n");
                for (String line : skipped) sb.append(line).append('\n');
            }
            result.report = sb.toString();

            Log.i(TAG, "game copy: "+result.files+" files, "+(result.inBytes >> 20)+" MB -> "+(result.outBytes >> 20)+" MB, png shrunk "+result.pngShrunk+"/"+result.pngTotal);
            return result;
        }
    }

    /**
     * @param mode one of GameCompression.ORIGINAL / LOW / HIGH
     */
    public static Result copy(final File srcDir, final File dstDir, final int mode, final Progress progress) {
        final Job job = new Job(mode);
        final List<File> dirs = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        collect(srcDir, dirs, files);

        if (!dstDir.exists() && !dstDir.mkdirs()) return job.result(srcDir.getPath());
        for (File dir : dirs) {
            File dst = target(srcDir, dstDir, dir);
            if (!dst.exists() && !dst.mkdirs()) return job.result(srcDir.getPath());
        }

        long sum = 0;
        for (File file : files) sum += file.length();
        final long totalBytes = Math.max(1, sum);
        final AtomicLong doneBytes = new AtomicLong();
        final AtomicInteger lastPercent = new AtomicInteger(-1);

        ExecutorService pool = Executors.newFixedThreadPool(job.threads());
        List<Future<?>> futures = new ArrayList<>();
        for (final File src : files) {
            futures.add(pool.submit(() -> {
                long size = src.length();
                job.process(src, target(srcDir, dstDir, src), FileUtils.toRelativePath(srcDir.getPath(), src.getPath()), false);
                int percent = (int)(doneBytes.addAndGet(size) * 100 / totalBytes);
                if (progress != null && percent != lastPercent.getAndSet(percent)) progress.onProgress(Math.min(percent, 100));
            }));
        }
        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            }
            catch (Exception e) {
                Log.e(TAG, "Copy task failed", e);
                job.failed.set(true);
            }
        }
        return job.result(srcDir.getPath());
    }
}
