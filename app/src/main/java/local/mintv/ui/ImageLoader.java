package local.mintv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import local.mintv.R;
import local.mintv.net.Http;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Tiny logo loader: OkHttp + a bounded in-memory {@link android.util.LruCache} backed by a
 * shared on-disk cache.
 *
 * <p>No Coil/Glide/Picasso. Logos are small PNGs on a public CDN, so a 4-thread pool and
 * a 12 MB LRU are plenty for a 5-column TV grid. Each {@link ImageView} is tagged with the
 * URL it is currently showing; a late response for a recycled holder is dropped instead of
 * painting the wrong logo.</p>
 *
 * <p>{@link ImageDiskCache} makes a cold start cheap: an image cached within the last day is
 * decoded from disk with no request at all, and an older one is painted immediately and then
 * refreshed in the background. Call {@link #init(Context)} once per process (the app class
 * does this) before the first load.</p>
 */
public final class ImageLoader {

    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    private static final int TARGET_WIDTH_PX = 400;
    private static final int TARGET_HEIGHT_PX = 240;
    private static final int HERO_WIDTH_PX = 1280;
    private static final int HERO_HEIGHT_PX = 720;
    private static final String HERO_KEY_PREFIX = "hero:1280x720:";
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "logo-loader");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final android.util.LruCache<String, Bitmap> MEMORY =
            new android.util.LruCache<String, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private ImageLoader() {
    }

    /** Binds the shared disk cache to a context. Idempotent; call once from the app class. */
    public static void init(Context context) {
        ImageDiskCache.init(context);
    }

    public static void load(ImageView view, String url) {
        load(view, url, TARGET_WIDTH_PX, TARGET_HEIGHT_PX, false);
    }

    /**
     * Home hero artwork: a 16:9 landscape frame decoded toward 1280x720. Unlike the logo
     * loader this never paints {@code ic_channel_placeholder} — an unresolved hero keeps
     * its designed plate behind the image instead of a grey icon.
     */
    public static void loadHero(ImageView view, String url) {
        load(view, url, HERO_WIDTH_PX, HERO_HEIGHT_PX, true);
    }

    private static void load(ImageView view, String url, int targetWidth, int targetHeight,
                             boolean hero) {
        if (view == null) {
            return;
        }
        String remote = url == null ? "" : url.trim();
        String key = remote.isEmpty() ? "" : (hero ? HERO_KEY_PREFIX + remote : remote);
        view.setTag(key);
        if (key.isEmpty()) {
            if (hero) {
                view.setImageDrawable(null);
            } else {
                view.setImageResource(R.drawable.ic_channel_placeholder);
            }
            return;
        }

        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        if (hero) {
            // The hero plate stays visible until real artwork arrives; no broken-image state.
            view.setImageDrawable(null);
        } else {
            view.setImageResource(R.drawable.ic_channel_placeholder);
        }
        POOL.execute(() -> {
            ImageDiskCache.Entry stored = ImageDiskCache.read(remote);
            if (stored != null) {
                Bitmap bitmap = decode(stored.bytes, targetWidth, targetHeight);
                if (bitmap != null) {
                    MEMORY.put(key, bitmap);
                    paint(view, key, bitmap);
                }
                if (stored.fresh) {
                    return;
                }
            }

            byte[] bytes = downloadBytes(remote);
            if (bytes == null) {
                return;
            }
            Bitmap bitmap = decode(bytes, targetWidth, targetHeight);
            if (bitmap != null) {
                MEMORY.put(key, bitmap);
                paint(view, key, bitmap);
            }
            ImageDiskCache.write(remote, bytes);
        });
    }

    private static void paint(ImageView view, String key, Bitmap bitmap) {
        MAIN.post(() -> {
            if (key.equals(view.getTag())) {
                view.setImageBitmap(bitmap);
            }
        });
    }

    private static byte[] downloadBytes(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "image/*")
                .build();
        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_DOWNLOAD_BYTES) {
                return null;
            }
            byte[] bytes = body.bytes();
            if (bytes.length == 0 || bytes.length > MAX_DOWNLOAD_BYTES) {
                return null;
            }
            return bytes;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] bytes, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                || bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (OutOfMemoryError e) {
            MEMORY.evictAll();
            return null;
        }
    }
}
