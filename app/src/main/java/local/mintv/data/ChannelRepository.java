package local.mintv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import local.mintv.net.ApiException;
import local.mintv.net.GuestAuth;
import local.mintv.net.Http;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the public channel line-up and keeps the last successful raw JSON on disk.
 *
 * <p>Behaviour on {@link #load(boolean, Listener)}:</p>
 * <ol>
 *   <li>cached JSON is parsed on a background thread and delivered immediately, so the
 *       grid paints on a cold start with no network;</li>
 *   <li>if the cache is younger than {@link #CACHE_TTL_MS} and a refresh was not forced,
 *       the network is skipped;</li>
 *   <li>otherwise the guest endpoint is called with a fresh header set, and on success
 *       the raw JSON replaces the cache and the parsed list is delivered again.</li>
 * </ol>
 *
 * <p>Callbacks always run on the main thread. Errors after a cache hit are delivered as
 * well, letting the UI keep the cached list and only downgrade its status line.</p>
 */
public final class ChannelRepository {

    public interface Listener {
        /**
         * @param fromCache true for the immediate cache delivery, false for a fresh fetch
         */
        void onChannels(List<Channel> channels, boolean fromCache);

        void onError(String message, Throwable cause);
    }

    private static final String TAG = "ChannelRepository";
    private static final String PREFS = "channel_cache";
    private static final String KEY_JSON = "channels_json";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;

    private static final String ENDPOINT =
            "https://tv-hr-prod.yo-digital.com/hr-bifrost/epg/channel"
                    + "?channelMap_id="
                    + "&includeVirtualChannels=false"
                    + "&natco_key=" + GuestAuth.NATCO_KEY
                    + "&app_language=" + GuestAuth.APP_LANGUAGE
                    + "&natco_code=" + GuestAuth.NATCO_CODE;

    private static volatile ChannelRepository instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private volatile List<Channel> lastDelivered = Collections.emptyList();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-repo");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChannelRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ChannelRepository get(Context context) {
        ChannelRepository current = instance;
        if (current != null) {
            return current;
        }
        synchronized (ChannelRepository.class) {
            if (instance == null) {
                instance = new ChannelRepository(context);
            }
            return instance;
        }
    }

    public void load(boolean forceRefresh, Listener listener) {
        io.execute(() -> {
            List<Channel> cached = readCache();
            if (!cached.isEmpty()) {
                lastDelivered = cached;
                mainHandler.post(() -> listener.onChannels(cached, true));
            }
            long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0);
            boolean fresh = !cached.isEmpty()
                    && fetchedAt > 0
                    && System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS;
            if (!forceRefresh && fresh) {
                return;
            }
            fetch(listener);
        });
    }

    private void fetch(Listener listener) {
        Request.Builder request = new Request.Builder().url(ENDPOINT);
        for (java.util.Map.Entry<String, String> header
                : GuestAuth.headers(appContext).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }

        try (Response response = Http.client().newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException(
                        ApiException.SERVER,
                        "HTTP " + response.code(),
                        response.code(),
                        null);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ApiException(ApiException.PARSE, "Empty response body");
            }
            String json = body.string();
            List<Channel> channels = parse(json);
            if (channels.isEmpty()) {
                throw new ApiException(ApiException.PARSE, "No channels in response");
            }
            prefs.edit()
                    .putString(KEY_JSON, json)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply();
            lastDelivered = channels;
            mainHandler.post(() -> listener.onChannels(channels, false));
        } catch (IOException e) {
            mainHandler.post(() -> listener.onError("Mreža nije dostupna.", e));
        } catch (JSONException e) {
            mainHandler.post(() -> listener.onError("Neispravan odgovor poslužitelja.", e));
        } catch (ApiException e) {
            mainHandler.post(() -> listener.onError(e.getMessage(), e));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unexpected channel fetch failure", e);
            mainHandler.post(() -> listener.onError("Neočekivana pogreška.", e));
        }
    }

    private List<Channel> readCache() {
        String json = prefs.getString(KEY_JSON, null);
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return parse(json);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Cached channel JSON is corrupt; ignoring", e);
            prefs.edit().remove(KEY_JSON).remove(KEY_FETCHED_AT).apply();
            return Collections.emptyList();
        }
    }

    /** Visible for tests / future callers that already have a payload. */
    public static List<Channel> parse(String json) throws JSONException {
        JSONArray array = new JSONObject(json).optJSONArray("channels");
        if (array == null) {
            return Collections.emptyList();
        }
        List<Channel> channels = new ArrayList<>(array.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            Channel channel = Channel.fromJson(object);
            if (channel.getTitle().isEmpty()) {
                continue;
            }
            String key = channel.getStationId().isEmpty()
                    ? channel.getTitle() + "#" + channel.getNumber()
                    : channel.getStationId();
            if (seen.add(key)) {
                channels.add(channel);
            }
        }
        channels.sort((left, right) -> {
            int byNumber = Integer.compare(left.getNumber(), right.getNumber());
            return byNumber != 0
                    ? byNumber
                    : left.getTitle().compareToIgnoreCase(right.getTitle());
        });
        return channels;
    }

    public long lastFetchedAt() {
        return prefs.getLong(KEY_FETCHED_AT, 0);
    }

    /** Last line-up delivered by this process, empty before the first load. */
    public List<Channel> cachedChannels() {
        return lastDelivered;
    }

    public void clearCache() {
        prefs.edit().clear().apply();
    }
}
