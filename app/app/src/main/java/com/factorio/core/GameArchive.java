package com.factorio.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs the game from the official Linux ARM64 archive, e.g. {@code factorio-space-age_linux-arm64_2.1.24.tar.xz}.
 * The version is taken from the file name, so any later release works the same way.
 *
 * The archive is unpacked straight into the rootfs (no temporary full copy): PNG textures go through the same
 * compressor as the folder copy ({@link GameCopier.Job}). Everything is written to "factorio.part" first and swapped in
 * only when the archive was read to the end, so an interrupted install never leaves half a game behind; on an update
 * the old game stays until the new one is complete. The user data (.factorio: saves, mods, config) is not touched.
 */
public final class GameArchive {
    private static final String TAG = "GameArchive";
    /** Archive chosen by the user (content:// uri or a file path): installed on the next game start. */
    public static final String PREF_PENDING = "game_archive_pending";
    /** Version the user did not want to update to (asked once per version). */
    public static final String PREF_DECLINED_VERSION = "game_archive_declined";
    public static final String DONE_MARK = ".copy_done";
    public static final String VERSION_FILE = ".game_version";
    /** Must exist in the unpacked game, otherwise the archive is not a Linux ARM64 Factorio. */
    public static final String REQUIRED_FILE = "bin/arm64/factorio";
    /** factorio[-space-age|-alpha|...]_linux-arm64_X.Y.Z.tar.xz (also "linux_arm64", "-" instead of "_" before the version) */
    private static final Pattern NAME = Pattern.compile("(?i)^factorio[^/]*linux[-_]?arm64[^/]*?[-_ ]v?(\\d+(?:\\.\\d+){1,3})(?: ?\\(\\d+\\))?\\.tar\\.xz$");
    /** Free space needed before the start, relative to the archive size (the game unpacks to about 2-2.5x). */
    private static final double SPACE_FACTOR = 2.6;

    private GameArchive() {}

    /** One archive: a file in Download or a document picked by the user. */
    public static final class Source {
        public final File file;
        public final Uri uri;
        public final String name;
        public final long size;       // -1 if unknown
        public final String version;  // null if the name has no version

        private Source(File file, Uri uri, String name, long size) {
            this.file = file;
            this.uri = uri;
            this.name = name != null ? name : "";
            this.size = size;
            this.version = versionOf(this.name);
        }

        InputStream open(Context context) throws IOException {
            if (file != null) return new FileInputStream(file);
            InputStream inStream = context.getContentResolver().openInputStream(uri);
            if (inStream == null) throw new IOException("cannot open "+uri);
            return inStream;
        }

        public String describe() {
            return file != null ? file.getPath() : name+" ("+uri+")";
        }
    }

    public static boolean isArchiveName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** "2.1.24" from "factorio-space-age_linux-arm64_2.1.24.tar.xz", null when there is no version. */
    public static String versionOf(String name) {
        if (name == null) return null;
        Matcher matcher = NAME.matcher(name);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Compares versions number by number ("2.1.9" < "2.1.24"); null is the oldest. */
    public static int compareVersions(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long va = i < pa.length ? parse(pa[i]) : 0;
            long vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static long parse(String part) {
        try {
            return Long.parseLong(part);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Source fromFile(File file) {
        return new Source(file, null, file.getName(), file.length());
    }

    public static Source fromUri(Context context, Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        }
        catch (Exception e) {
            Log.w(TAG, "cannot query "+uri+": "+e);
        }
        if (name == null) name = uri.getLastPathSegment();
        return new Source(null, uri, name, size);
    }

    /** The newest game archive in the folder (by the version in the name), or null. */
    public static File findNewest(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;
        File best = null;
        String bestVersion = null;
        for (File file : files) {
            if (!file.isFile() || !isArchiveName(file.getName())) continue;
            String version = versionOf(file.getName());
            int cmp = compareVersions(version, bestVersion);
            if (best == null || cmp > 0 || (cmp == 0 && file.lastModified() > best.lastModified())) {
                best = file;
                bestVersion = version;
            }
        }
        return best;
    }

    /** Where the archives are looked for: the folder that holds NATIVE_SRC (Download), or the system Download folder. */
    public static File downloadDir(String nativeSrc) {
        if (nativeSrc != null && !nativeSrc.isEmpty()) {
            File parent = new File(nativeSrc).getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    public static boolean isInstalled(File gameDir) {
        return new File(gameDir, DONE_MARK).exists();
    }

    /** Version written by the last archive install (null after a folder copy). */
    public static String installedVersion(File gameDir) {
        File file = new File(gameDir, VERSION_FILE);
        if (!file.isFile()) return null;
        ArrayList<String> lines = FileUtils.readLines(file);
        return lines.isEmpty() ? null : lines.get(0).trim();
    }

    // ---- archive chosen by the user ----

    public static void setPending(Context context, String value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        if (value != null) editor.putString(PREF_PENDING, value);
        else editor.remove(PREF_PENDING);
        editor.apply();
    }

    public static boolean hasPending(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_PENDING, null) != null;
    }

    /** System file picker for the archive (.tar.xz has no reliable MIME type, so everything is shown). */
    public static android.content.Intent pickIntent() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /** Keeps the read access to a picked document (needed across restarts) and remembers it for the next start. */
    public static void choose(Context context, Uri uri) {
        try {
            context.getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        catch (Exception e) {
            Log.w(TAG, "no persistable permission for "+uri+": "+e);
        }
        setPending(context, uri.toString());
    }

    private static Source pendingSource(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_PENDING, null);
        if (value == null) return null;
        if (value.startsWith("/")) return fromFile(new File(value));
        return fromUri(context, Uri.parse(value));
    }

    private static void clearPending(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_PENDING, null);
        if (value != null && !value.startsWith("/")) {
            try {
                context.getContentResolver().releasePersistableUriPermission(Uri.parse(value), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch (Exception e) {}
        }
        setPending(context, null);
    }

    /**
     * Which archive should be installed now: the one the user picked (always wins), otherwise, only when the game is
     * not installed yet and there is no game folder to copy, the newest archive in Download. null = nothing to do.
     */
    public static Source sourceToInstall(Context context, File gameDir, String nativeSrc) {
        Source pending = pendingSource(context);
        if (pending != null) return pending;
        if (isInstalled(gameDir)) return null;
        if (nativeSrc != null && !nativeSrc.isEmpty() && new File(nativeSrc).isDirectory()) return null; // the folder copy does it
        File newest = findNewest(downloadDir(nativeSrc));
        return newest != null ? fromFile(newest) : null;
    }

    // ---- install ----

    /** Counts the compressed bytes read: the unpacked size of a .tar.xz is unknown, the progress follows the archive. */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }

    private static GameCopier.Result fail(GameCopier.Job job, Source source, String error) {
        GameCopier.Result result = job.result(source.describe());
        result.ok = false;
        result.error = error;
        result.report = "ERROR: "+error+"\n\n"+result.report;
        Log.e(TAG, error);
        return result;
    }

    /** Entry name inside the game folder: the top folder of the archive ("factorio/") is dropped; null = unsafe name. */
    private static String relativeName(String entryName, String[] topFolder) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        if (name.startsWith("/")) return null;
        int slash = name.indexOf('/');
        if (topFolder[0] == null) {
            // official archives keep everything in "factorio/"; an archive without that folder is used as it is
            String first = slash > 0 ? name.substring(0, slash + 1) : "";
            topFolder[0] = first.toLowerCase(Locale.ROOT).startsWith("factorio") ? first : "";
        }
        if (!topFolder[0].isEmpty() && name.startsWith(topFolder[0])) name = name.substring(topFolder[0].length());
        for (String part : name.split("/")) {
            if (part.equals("..")) return null; // would write outside the game folder
        }
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name;
    }

    /**
     * Unpacks the archive into gameDir (e.g. rootfs/home/xuser/factorio). Runs on the calling (background) thread.
     * On success the pending choice is cleared, .copy_done and .game_version are written.
     */
    public static GameCopier.Result install(Context context, Source source, File gameDir, int mode, GameCopier.Progress progress) {
        final GameCopier.Job job = new GameCopier.Job(mode);
        File homeDir = gameDir.getParentFile();
        if (homeDir == null) return fail(job, source, "bad target folder "+gameDir);
        homeDir.mkdirs();

        if (source.size > 0) {
            long free = new StatFs(homeDir.getPath()).getAvailableBytes();
            long needed = (long)(source.size * SPACE_FACTOR);
            if (free < needed) {
                clearPending(context);
                return fail(job, source, String.format(Locale.ROOT, "not enough free space: %d MB needed, %d MB free", needed >> 20, free >> 20));
            }
        }

        final File partDir = new File(homeDir, gameDir.getName()+".part");
        FileUtils.delete(partDir);
        final File tempDir = new File(partDir, ".extract-tmp");
        if (!tempDir.mkdirs()) return fail(job, source, "cannot create "+tempDir);

        final int threads = job.threads();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final Semaphore slots = new Semaphore(threads * 2); // PNG files waiting for the compressor (bounds the temp space)
        final List<Future<?>> futures = new ArrayList<>();
        final List<String[]> hardLinks = new ArrayList<>(); // {target, link} relative names, done at the end
        final AtomicInteger tempCounter = new AtomicInteger();
        final String[] topFolder = {null};
        String error = null;
        int lastPercent = -1;

        try (CountingInputStream counting = new CountingInputStream(new BufferedInputStream(source.open(context), 1 << 16));
             InputStream xz = new XZCompressorInputStream(counting);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz, "UTF-8")) {
            byte[] buffer = new byte[1 << 16];
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (job.failed.get()) break;
                String relative = relativeName(entry.getName(), topFolder);
                if (relative == null) {
                    error = "unsafe path in the archive: "+entry.getName();
                    break;
                }
                if (relative.isEmpty()) continue;
                File out = new File(partDir, relative);

                if (entry.isDirectory()) {
                    out.mkdirs();
                }
                else if (entry.isSymbolicLink()) {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileUtils.symlink(entry.getLinkName(), out.getPath());
                }
                else if (entry.isLink()) {
                    String target = relativeName(entry.getLinkName(), topFolder);
                    if (target == null) {
                        error = "unsafe link in the archive: "+entry.getLinkName();
                        break;
                    }
                    hardLinks.add(new String[]{target, relative});
                }
                else if (entry.isFile()) {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    final boolean executable = (entry.getMode() & 0111) != 0;

                    if (job.wantsShrink(relative)) {
                        // the tar stream is sequential: park the PNG in a temp file and let a worker shrink it
                        slots.acquire();
                        final File temp = new File(tempDir, tempCounter.incrementAndGet()+".png");
                        writeEntry(tar, temp, buffer);
                        final File dst = out;
                        final String rel = "/"+relative;
                        futures.add(pool.submit(() -> {
                            try {
                                job.process(temp, dst, rel, true);
                            }
                            finally {
                                slots.release();
                            }
                        }));
                    }
                    else {
                        writeEntry(tar, out, buffer);
                        if (executable) FileUtils.chmod(out, 0771);
                        job.countPlain(out, out.length());
                    }
                }

                if (progress != null && source.size > 0) {
                    int percent = (int)Math.min(99, counting.count * 100 / source.size);
                    if (percent != lastPercent) progress.onProgress(lastPercent = percent);
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        catch (Exception e) {
            Log.e(TAG, "archive read failed", e);
            error = "cannot read the archive ("+e.getClass().getSimpleName()+": "+e.getMessage()+")";
        }

        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            }
            catch (Exception e) {
                Log.e(TAG, "texture task failed", e);
                job.failed.set(true);
            }
        }
        FileUtils.delete(tempDir);

        if (error == null && job.failed.get()) error = "cannot write the game files (free space?)";
        if (error == null) {
            for (String[] link : hardLinks) {
                File target = new File(partDir, link[0]);
                File linkFile = new File(partDir, link[1]);
                if (!target.isFile() || !FileUtils.copy(target, linkFile)) {
                    error = "cannot create "+link[1];
                    break;
                }
            }
        }
        if (error == null && !new File(partDir, REQUIRED_FILE).isFile()) {
            error = "the archive has no "+REQUIRED_FILE+" (a Linux ARM64 build of the game is needed)";
        }
        if (error != null) {
            FileUtils.delete(partDir);
            clearPending(context);
            return fail(job, source, error);
        }

        // swap: the old game is removed only now (saves / mods are in .factorio next to it, not inside)
        File oldDir = new File(homeDir, gameDir.getName()+".old");
        FileUtils.delete(oldDir);
        if (gameDir.exists() && !gameDir.renameTo(oldDir)) FileUtils.delete(gameDir);
        if (!partDir.renameTo(gameDir)) {
            if (oldDir.exists()) oldDir.renameTo(gameDir);
            FileUtils.delete(partDir);
            clearPending(context);
            return fail(job, source, "cannot move the unpacked game into place");
        }
        FileUtils.delete(oldDir);

        FileUtils.writeString(new File(gameDir, VERSION_FILE), (source.version != null ? source.version : "?")+"\n"+source.name+"\n");
        try {
            new File(gameDir, DONE_MARK).createNewFile();
        }
        catch (IOException e) {}
        new File(gameDir, REQUIRED_FILE).setExecutable(true, false);
        clearPending(context);
        if (progress != null) progress.onProgress(100);

        GameCopier.Result result = job.result(source.describe());
        result.ok = true;
        return result;
    }

    private static void writeEntry(InputStream tar, File out, byte[] buffer) throws IOException {
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            int n;
            while ((n = tar.read(buffer)) > 0) outStream.write(buffer, 0, n);
        }
    }
}
