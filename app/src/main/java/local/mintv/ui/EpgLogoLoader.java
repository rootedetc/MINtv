package local.mintv.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import local.mintv.epg.EpgChannel;
import local.mintv.net.Http;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Asynchronous channel-logo loader for the canvas-drawn EPG grid.
 *
 * <p>{@link #get(EpgChannel)} is the hot path used from {@code onDraw}: it only does a
 * normalised-URL lookup in an {@link LruCache} (plus a couple of small set lookups on a
 * miss) and never touches disk, the network or the decoder. On the first miss for a URL it
 * schedules one background fetch and returns {@code null} immediately, so the grid can keep
 * drawing its channel-number placeholder.</p>
 *
 * <p>Fetches run on a small bounded daemon pool (3 threads, 64 queued) through the shared
 * {@link Http#client()}. In-flight URLs are deduplicated, the decoded bitmap is downsampled
 * to the grid's drawn logo box (46x30 dp by default, i.e. ~92x60 px on a 2x TV) and kept in
 * an 8 MB memory cache. {@link ImageDiskCache} sits underneath: logos cached within the last
 * day are decoded from disk without a request, older ones are painted from disk first and
 * refreshed in the background. Arrivals are coalesced into a single main-thread
 * {@code onLoaded} callback per batch, which the caller wires to {@code grid::invalidate}.</p>
 *
 * <p>When constructed with a {@link View}, the loader observes attach state: detaching
 * cancels queued/in-flight work, and no callback is posted to a detached view. It only ever
 * keeps the <em>application</em> context plus a weak reference to the view, never an
 * Activity. With the plain {@link Context} constructor, call {@link #pause()} /
 * {@link #resume()} from the host lifecycle if you want the same behaviour.</p>
 *
 * <p>All public methods are safe to call from the UI thread. {@link #clear()} may be called
 * when the line-up changes; it cancels work and drops the cache.</p>
 */
public final class EpgLogoLoader {

    /** Default memory budget for decoded logos. */
    private static final int CACHE_BYTES = 8 * 1024 * 1024;

    /** Matches EpgGridView.LOGO_WIDTH_DP / LOGO_HEIGHT_DP. */
    private static final float LOGO_WIDTH_DP = 46f;
    private static final float LOGO_HEIGHT_DP = 30f;

    private static final int POOL_SIZE = 3;
    private static final int QUEUE_CAPACITY = 64;
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

    /** Failed URLs are retried after this delay instead of on every frame. */
    private static final long RETRY_AFTER_MS = 60_000L;

    private final Context appContext;
    private final Runnable onLoaded;
    private final WeakReference<View> viewRef;

    private final int targetWidthPx;
    private final int targetHeightPx;
    private final LruCache<String, Bitmap> cache;

    /** URL -> generation of the queued/running fetch (also the in-flight dedupe marker). */
    private final ConcurrentHashMap<String, Integer> pending = new ConcurrentHashMap<>();
    /** URL -> elapsedRealtime at which its last attempt failed. */
    private final ConcurrentHashMap<String, Long> failed = new ConcurrentHashMap<>();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();

    private final ThreadPoolExecutor executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean notifyScheduled = new AtomicBoolean();

    private volatile boolean active = true;

    /**
     * Loader for a host that manages lifecycle itself (or is happy with the
     * {@link #pause()}/{@link #resume()} hooks). Uses 46x30 dp logos and an 8 MB cache.
     */
    public EpgLogoLoader(Context context, Runnable onLoaded) {
        this(context, null, onLoaded, 0, 0);
    }

    /**
     * Same as {@link #EpgLogoLoader(Context, Runnable)} with an explicit decoded size in
     * pixels. Pass e.g. {@code 92, 60} for a 46x30 dp box at 2x density.
     */
    public EpgLogoLoader(Context context, Runnable onLoaded,
                         int targetWidthPx, int targetHeightPx) {
        this(context, null, onLoaded, targetWidthPx, targetHeightPx);
    }

    /**
     * Recommended for the EPG: the loader follows the view's attach state and silently
     * stops scheduling/cancels work while it is detached.
     */
    public EpgLogoLoader(View view, Runnable onLoaded) {
        this(view.getContext(), view, onLoaded, 0, 0);
    }

    /** View-aware constructor with an explicit decoded size in pixels. */
    public EpgLogoLoader(View view, Runnable onLoaded,
                         int targetWidthPx, int targetHeightPx) {
        this(view.getContext(), view, onLoaded, targetWidthPx, targetHeightPx);
    }

    private EpgLogoLoader(Context context, View view, Runnable onLoaded,
                          int targetWidthPx, int targetHeightPx) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        ImageDiskCache.init(appContext);
        this.onLoaded = onLoaded;

        Resources resources = appContext.getResources();
        float density = resources.getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        this.targetWidthPx = Math.max(1, targetWidthPx > 0
                ? targetWidthPx : Math.round(LOGO_WIDTH_DP * density));
        this.targetHeightPx = Math.max(1, targetHeightPx > 0
                ? targetHeightPx : Math.round(LOGO_HEIGHT_DP * density));

        cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        executor = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 15L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "epg-logo-loader");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
        // Do not keep idle worker threads alive for the lifetime of the process.
        executor.allowCoreThreadTimeOut(true);

        if (view != null) {
            viewRef = new WeakReference<>(view);
            active = view.isAttachedToWindow();
            // The listener captures only this loader; the loader holds the view weakly.
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    resume();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    pause();
                }
            });
        } else {
            viewRef = null;
        }
    }

    /**
     * Cached logo for {@code channel}, or {@code null} if it is not decoded yet (or the
     * channel has no URL). Never blocks and never performs I/O: on a miss it merely
     * schedules one background fetch for that URL.
     *
     * <p>Hot path: one {@link LruCache#get} on a hit, no allocation for clean URLs.</p>
     */
    public Bitmap get(EpgChannel channel) {
        if (channel == null) {
            return null;
        }
        String url = normalize(channel.logoUrl);
        if (url == null) {
            return null;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            return cached.isRecycled() ? null : cached;
        }

        if (!active) {
            return null;
        }

        Long failedAt = failed.get(url);
        if (failedAt != null) {
            if (SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS) {
                return null;
            }
            failed.remove(url, failedAt);
        }

        if (pending.containsKey(url)) {
            return null;
        }
        schedule(url);
        return null;
    }

    /** Drops all cached logos, failed markers and queued/in-flight work. */
    public void clear() {
        generation.incrementAndGet();
        cache.evictAll();
        pending.clear();
        failed.clear();
        executor.getQueue().clear();
        for (Call call : calls) {
            call.cancel();
        }
        calls.clear();
    }

    /** Stops scheduling new fetches and cancels queued/in-flight ones. Cached bitmaps stay. */
    public void pause() {
        active = false;
        // Abandon fetches captured before the pause; queued/in-flight ones are cancelled.
        generation.incrementAndGet();
        executor.getQueue().clear();
        pending.clear();
        for (Call call : calls) {
            call.cancel();
        }
    }

    /** Re-enables fetching after {@link #pause()}. */
    public void resume() {
        active = true;
    }

    // ------------------------------------------------------------------------------------
    // Background work
    // ------------------------------------------------------------------------------------

    private void schedule(String url) {
        final int taskGeneration = generation.get();
        if (pending.putIfAbsent(url, taskGeneration) != null) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            pending.remove(url, taskGeneration);
            failed.put(url, SystemClock.elapsedRealtime());
            return;
        }

        Runnable task = () -> {
            try {
                if (!isCurrent(taskGeneration)) {
                    return;
                }

                boolean painted = false;
                ImageDiskCache.Entry cached = ImageDiskCache.read(url);
                if (cached != null) {
                    Bitmap bitmap = decode(cached.bytes);
                    if (!isCurrent(taskGeneration)) {
                        return;
                    }
                    if (bitmap != null) {
                        cache.put(url, bitmap);
                        notifyLoaded();
                        painted = true;
                        if (cached.fresh) {
                            return;
                        }
                    }
                }

                byte[] bytes = download(url, taskGeneration);
                if (!isCurrent(taskGeneration)) {
                    return;
                }
                if (bytes == null) {
                    if (!painted) {
                        failed.put(url, SystemClock.elapsedRealtime());
                    }
                    return;
                }
                Bitmap bitmap = decode(bytes);
                if (bitmap == null) {
                    if (!painted) {
                        failed.put(url, SystemClock.elapsedRealtime());
                    }
                    return;
                }
                cache.put(url, bitmap);
                notifyLoaded();
                ImageDiskCache.write(url, bytes);
            } finally {
                pending.remove(url, taskGeneration);
            }
        };

        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Pool/queue saturated: let a later frame retry instead of blocking the UI.
            pending.remove(url, taskGeneration);
        }
    }

    private boolean isCurrent(int taskGeneration) {
        return active && taskGeneration == generation.get();
    }

    private byte[] download(String url, int taskGeneration) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "image/*")
                .build();
        Call call = Http.client().newCall(request);
        calls.add(call);
        try {
            if (!isCurrent(taskGeneration)) {
                call.cancel();
            }
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return null;
                }
                long length = body.contentLength();
                if (length > MAX_DOWNLOAD_BYTES) {
                    return null;
                }
                byte[] bytes = body.bytes();
                if (bytes.length == 0 || bytes.length > MAX_DOWNLOAD_BYTES) {
                    return null;
                }
                return isCurrent(taskGeneration) ? bytes : null;
            }
        } catch (IOException | RuntimeException e) {
            // 404s, timeouts, cancellation, malformed images: draw the placeholder.
            return null;
        } finally {
            calls.remove(call);
        }
    }

    private Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            return null;
        }

        // Power-of-two sample so huge logos never allocate at full CDN resolution.
        int sample = 1;
        while (width / (sample * 2) >= targetWidthPx
                && height / (sample * 2) >= targetHeightPx) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        try {
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) {
                return null;
            }
            return scaleToFit(decoded);
        } catch (OutOfMemoryError e) {
            cache.evictAll();
            return null;
        }
    }

    /** Scales the sampled bitmap down to the drawn box, preserving the aspect ratio. */
    private Bitmap scaleToFit(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return source;
        }
        float scale = Math.min(targetWidthPx / (float) width,
                targetHeightPx / (float) height);
        if (scale >= 1f) {
            return source;
        }
        int drawWidth = Math.max(1, Math.round(width * scale));
        int drawHeight = Math.max(1, Math.round(height * scale));
        if (drawWidth == width && drawHeight == height) {
            return source;
        }
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(source, drawWidth, drawHeight, true);
            if (scaled != source) {
                source.recycle();
            }
            return scaled;
        } catch (OutOfMemoryError e) {
            cache.evictAll();
            return source;
        }
    }

    /** Coalesces a burst of decoded logos into one main-thread redraw. */
    private void notifyLoaded() {
        if (!notifyScheduled.compareAndSet(false, true)) {
            return;
        }
        main.post(() -> {
            notifyScheduled.set(false);
            if (!active) {
                return;
            }
            if (viewRef != null) {
                View view = viewRef.get();
                if (view == null || !view.isAttachedToWindow()) {
                    return;
                }
            }
            if (onLoaded != null) {
                onLoaded.run();
            }
        });
    }

    /** Returns {@code url} trimmed, or {@code null} when blank; allocation-free for clean URLs. */
    private static String normalize(String url) {
        if (url == null) {
            return null;
        }
        int start = 0;
        int end = url.length();
        while (start < end && url.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && url.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start >= end) {
            return null;
        }
        if (start == 0 && end == url.length()) {
            return url;
        }
        return url.substring(start, end);
    }
}
