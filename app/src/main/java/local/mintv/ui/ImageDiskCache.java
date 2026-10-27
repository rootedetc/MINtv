package local.mintv.ui;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared on-disk cache for remote artwork: channel logos, posters, avatars and hero art.
 *
 * <p>Raw bytes are stored under {@code filesDir/image-cache}, one file per URL, so the two
 * image loaders ({@link ImageLoader} and {@link EpgLogoLoader}) share the same entries even
 * though they decode at different sizes. Entries are written atomically through a temporary
 * file and are keyed by a hash of the URL.</p>
 *
 * <p>Freshness mirrors the EPG cache: an entry younger than {@link #FRESH_MS} is used
 * without touching the network, while an older entry is still returned immediately (marked
 * stale) so callers can paint it and refresh in the background. Nothing older than
 * {@link #MAX_AGE_MS} is served, and the directory is kept under {@link #MAX_BYTES} by
 * evicting the least recently written entries. All methods are safe from any thread; I/O is
 * performed by the caller's background worker.</p>
 */
final class ImageDiskCache {

    private static final String TAG = "ImageDiskCache";
    private static final String CACHE_DIR = "image-cache";
    private static final String EXTENSION = ".img";
    private static final String TEMP_EXTENSION = ".tmp";

    /** A cached image is used without a network request for this long. */
    static final long FRESH_MS = TimeUnit.HOURS.toMillis(24);
    /** Older images are ignored (and deleted) instead of being shown. */
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);
    /** Upper bound for the whole directory; the oldest files are evicted first. */
    private static final long MAX_BYTES = 64L * 1024L * 1024L;
    /** Abandoned temporary files are removed after this delay. */
    private static final long TEMP_MAX_AGE_MS = TimeUnit.HOURS.toMillis(1);

    private static final AtomicBoolean pruning = new AtomicBoolean();
    private static volatile File directory;

    private ImageDiskCache() {
    }

    /** Binds the cache to the application's private storage. Idempotent. */
    static void init(Context context) {
        if (directory != null || context == null) {
            return;
        }
        synchronized (ImageDiskCache.class) {
            if (directory != null) {
                return;
            }
            Context application = context.getApplicationContext();
            Context base = application != null ? application : context;
            File target = new File(base.getFilesDir(), CACHE_DIR);
            if (!target.isDirectory() && !target.mkdirs()) {
                Log.w(TAG, "Unable to create " + target);
                return;
            }
            directory = target;
        }
        pruneAsync();
    }

    /**
     * Returns the cached bytes for {@code url}, or {@code null} when there is no usable
     * entry. {@link Entry#fresh} is false for an entry older than {@link #FRESH_MS}; such an
     * entry should be shown now and refreshed in the background.
     */
    static Entry read(String url) {
        File file = file(url);
        if (file == null) {
            return null;
        }
        long age = System.currentTimeMillis() - file.lastModified();
        if (age > MAX_AGE_MS) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        try {
            byte[] bytes = readAll(file);
            if (bytes.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            return new Entry(bytes, age < FRESH_MS);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unreadable cache entry; deleting it", e);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    /** Stores {@code bytes} for {@code url} and refreshes its timestamp. */
    static void write(String url, byte[] bytes) {
        File target = file(url);
        if (target == null || bytes == null || bytes.length == 0) {
            return;
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return;
        }
        File temp = null;
        try {
            temp = File.createTempFile(target.getName(), TEMP_EXTENSION, parent);
            try (OutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.flush();
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!temp.renameTo(target)) {
                    Log.w(TAG, "Unable to replace " + target.getName());
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to write cache entry", e);
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            return;
        }
        pruneAsync();
    }

    /** Runs at most one background cleanup at a time. */
    static void pruneAsync() {
        if (directory == null || !pruning.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                prune();
            } finally {
                pruning.set(false);
            }
        }, "image-cache-prune");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void prune() {
        File current = directory;
        if (current == null) {
            return;
        }
        File[] files = current.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long total = 0L;
        int deleted = 0;
        for (File file : files) {
            long age = now - file.lastModified();
            boolean expired = age > MAX_AGE_MS
                    || (file.getName().endsWith(TEMP_EXTENSION) && age > TEMP_MAX_AGE_MS);
            if (expired) {
                if (file.delete()) {
                    deleted++;
                }
            } else if (!file.getName().endsWith(TEMP_EXTENSION)) {
                total += file.length();
            }
        }

        if (total > MAX_BYTES) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File file : files) {
                if (total <= MAX_BYTES || !file.isFile()
                        || file.getName().endsWith(TEMP_EXTENSION)) {
                    continue;
                }
                long length = file.length();
                if (file.delete()) {
                    total -= length;
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "Pruned " + deleted + " image cache file(s)");
        }
    }

    private static File file(String url) {
        File current = directory;
        if (current == null || url == null || url.isEmpty()) {
            return null;
        }
        return new File(current, hash(url) + EXTENSION);
    }

    private static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length <= 0L || length > Integer.MAX_VALUE) {
            return new byte[0];
        }
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) length);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String hash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xf, 16));
                builder.append(Character.forDigit(value & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required on every Android release; keep a stable fallback anyway.
            return Integer.toHexString(url.hashCode());
        }
    }

    /** One cached image and whether it is young enough to skip the network. */
    static final class Entry {
        final byte[] bytes;
        final boolean fresh;

        Entry(byte[] bytes, boolean fresh) {
            this.bytes = bytes;
            this.fresh = fresh;
        }
    }
}
