package local.mintv.epg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import local.mintv.net.GuestAuth;
import local.mintv.net.Http;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * One programme's artwork and long synopsis, resolved lazily from the same public service
 * the native client uses. Results are memory-cached: moving the guide focus back and forth
 * must not hammer the endpoint, and a failed id is not retried for the life of the process.
 */
public final class ProgramDetailsRepository {

    public interface Callback {
        void onDetails(EpgProgramDetails details);
    }

    private static final String BASE_URL = "https://tv-hr-prod.yo-digital.com/hr-bifrost";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "programme-details");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final LruCache<String, EpgProgramDetails> CACHE =
            new LruCache<String, EpgProgramDetails>(160) {
                @Override
                protected int sizeOf(String key, EpgProgramDetails value) {
                    return 1;
                }
            };
    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> FAILED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private ProgramDetailsRepository() {
    }

    public static void load(Context context, String programId, Callback callback) {
        if (programId == null || programId.trim().isEmpty()) {
            callback.onDetails(null);
            return;
        }
        String id = programId.trim();
        EpgProgramDetails cached = CACHE.get(id);
        if (cached != null) {
            callback.onDetails(cached);
            return;
        }
        if (FAILED.contains(id) || !IN_FLIGHT.add(id)) {
            callback.onDetails(null);
            return;
        }
        Context app = context.getApplicationContext();
        POOL.execute(() -> {
            EpgProgramDetails details = fetch(app, id);
            IN_FLIGHT.remove(id);
            if (details == null) {
                FAILED.add(id);
            } else {
                CACHE.put(id, details);
            }
            EpgProgramDetails result = details;
            MAIN.post(() -> callback.onDetails(result));
        });
    }

    private static final String TAG = "ProgrammeDetails";

    private static EpgProgramDetails fetch(Context context, String id) {
        HttpUrl url;
        try {
            url = HttpUrl.get(BASE_URL).newBuilder()
                    .addPathSegment("details")
                    .addPathSegment("program")
                    .addPathSegment(id)
                    .build();
        } catch (IllegalArgumentException e) {
            return null;
        }
        Request.Builder request = new Request.Builder().url(url).get();
        for (java.util.Map.Entry<String, String> header : GuestAuth.headers(context).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        try (Response response = Http.client().newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                android.util.Log.w(TAG, "details " + id + " HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                android.util.Log.w(TAG, "details " + id + " empty body");
                return null;
            }
            return EpgProgramDetails.fromJson(new JSONObject(body.string()));
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            android.util.Log.w(TAG, "details " + id + " failed: " + e);
            return null;
        }
    }
}
