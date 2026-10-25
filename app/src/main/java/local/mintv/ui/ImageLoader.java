package local.mintv.ui;

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
 * Tiny logo loader: OkHttp + a bounded in-memory {@link android.util.LruCache}.
 *
 * <p>No Coil/Glide/Picasso. Logos are small PNGs on a public CDN, so a 4-thread pool and
 * a 12 MB LRU are plenty for a 5-column TV grid. Each {@link ImageView} is tagged with the
 * URL it is currently showing; a late response for a recycled holder is dropped instead of
 * painting the wrong logo.</p>
 */
public final class ImageLoader {

    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    private static final int TARGET_WIDTH_PX = 400;
    private static final int TARGET_HEIGHT_PX = 240;
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

    public static void load(ImageView view, String url) {
        if (view == null) {
            return;
        }
        String key = url == null ? "" : url.trim();
        view.setTag(key);
        if (key.isEmpty()) {
            view.setImageResource(R.drawable.ic_channel_placeholder);
            return;
        }

        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageResource(R.drawable.ic_channel_placeholder);
        POOL.execute(() -> {
            Bitmap bitmap = download(key);
            if (bitmap == null) {
                return;
            }
            MEMORY.put(key, bitmap);
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap download(String url) {
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
            return decode(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH_PX
                || bounds.outHeight / (sample * 2) >= TARGET_HEIGHT_PX) {
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
