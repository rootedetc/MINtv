package local.mintv.net;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import local.mintv.data.Session;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * CLPP concurrency lock for playback (PROTOCOL.md §3.11).
 *
 * <p>Every {@code /media} answer carries a {@code lock} object. The lock is the account's
 * stream reservation: the client must send a heartbeat to {@code /web/Concurrency/update}
 * every {@code update_lock_interval} seconds and must release the lock with
 * {@code /web/Concurrency/unlock} when it stops playing or switches channel. Skipping
 * either half leaves the reservation behind, and after a few zaps the account hits its
 * concurrent-stream limit and every new tune is refused with a concurrency error.</p>
 *
 * <p>This class owns exactly one lock per process, because the app only ever plays one
 * stream at a time. {@link PlaybackApi} releases the previous lock before the next
 * selector call and holds the new one, and {@code PlayerActivity.onDestroy} releases it
 * when the player is closed. A plain stop keeps the lock, so resuming the same player
 * from the background or from Login still has its reservation. The heartbeat runs on a
 * single daemon thread and dies with the process.</p>
 *
 * <p>The concurrency endpoints live on their own host ({@code lock.concurrency_service_url}),
 * not on the BFF, and must be called without {@code bff_token} and without the player
 * flow/step headers. The three lock values are {@code encodeURIComponent}-ed by the web
 * client and then encoded again by its query serializer, so they are double-encoded on
 * the wire; {@link #jsEncode(String)} reproduces the first pass.</p>
 */
public final class ConcurrencyLock {

    private static final String TAG = "MinTvConcurrency";
    private static final String UPDATE_PATH = "/web/Concurrency/update";
    private static final String UNLOCK_PATH = "/web/Concurrency/unlock";
    private static final long DEFAULT_INTERVAL_SECONDS = 60L;
    private static final long RETRY_INTERVAL_SECONDS = 8L;
    private static final int MAX_UPDATE_FAILURES = 3;
    private static final int FORBIDDEN = 403;
    private static final int UNPROCESSABLE = 422;

    /** Lock material returned with a selector response. */
    public static final class Ticket {
        final String serviceUrl;
        final String lock;
        final String lockId;
        final String sequenceToken;
        final long intervalSeconds;

        Ticket(String serviceUrl, String lock, String lockId, String sequenceToken,
               long intervalSeconds) {
            this.serviceUrl = serviceUrl;
            this.lock = lock;
            this.lockId = lockId;
            this.sequenceToken = sequenceToken;
            this.intervalSeconds = intervalSeconds;
        }

        /** A lock is usable only when the heartbeat has every field it needs. */
        public boolean isValid() {
            return !isEmpty(serviceUrl) && !isEmpty(lock)
                    && !isEmpty(lockId) && !isEmpty(sequenceToken);
        }

        Ticket updated(String newLock, String newId, String newToken) {
            return new Ticket(serviceUrl,
                    isEmpty(newLock) ? lock : newLock,
                    isEmpty(newId) ? lockId : newId,
                    isEmpty(newToken) ? sequenceToken : newToken,
                    intervalSeconds);
        }

        boolean sameAs(Ticket other) {
            return other != null
                    && serviceUrl.equals(other.serviceUrl)
                    && lock.equals(other.lock)
                    && lockId.equals(other.lockId)
                    && sequenceToken.equals(other.sequenceToken);
        }
    }

    private static final Object STATE = new Object();
    private static final ScheduledExecutorService IO =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "maxtv-concurrency");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * The beat and the unlock sit on the critical path of a channel switch, so they get a
     * much shorter timeout than the shared client's 30 s API budget.
     */
    private static volatile okhttp3.OkHttpClient client;

    private static volatile Context appContext;
    private static Ticket current;
    private static ScheduledFuture<?> heartbeat;
    /** Bumped on every hold/release/drop so stale heartbeat callbacks become no-ops. */
    private static int generation;
    private static int updateFailures;

    private ConcurrencyLock() {
    }

    /**
     * Extracts the lock from a {@code /media} answer. Accepts both the flat
     * {@code {lock, video}} body and the wrapped {@code {data: {lock, video}}} shape.
     */
    public static Ticket parse(JSONObject media) {
        if (media == null) {
            return null;
        }
        JSONObject lock = media.optJSONObject("lock");
        if (lock == null) {
            JSONObject data = media.optJSONObject("data");
            if (data != null) {
                lock = data.optJSONObject("lock");
            }
        }
        if (lock == null) {
            return null;
        }
        long interval = lock.optLong("update_lock_interval", DEFAULT_INTERVAL_SECONDS);
        if (interval <= 0L) {
            interval = DEFAULT_INTERVAL_SECONDS;
        }
        Ticket ticket = new Ticket(
                lock.optString("concurrency_service_url", ""),
                lock.optString("lock", ""),
                lock.optString("lock_id", ""),
                lock.optString("lock_sequence_token", ""),
                interval);
        return ticket.isValid() ? ticket : null;
    }

    /**
     * Takes over the lock for the stream that is about to play and starts its heartbeat.
     * Any previous lock is released first, so a rapid zap never leaves two reservations.
     */
    public static void hold(Context context, Ticket ticket) {
        if (context == null || ticket == null || !ticket.isValid()) {
            return;
        }
        Context app = context.getApplicationContext();
        Ticket abandoned;
        synchronized (STATE) {
            appContext = app;
            abandoned = current != null && !current.sameAs(ticket) ? current : null;
            current = ticket;
            generation++;
            updateFailures = 0;
            cancelHeartbeatLocked();
            scheduleLocked(generation, ticket.intervalSeconds);
        }
        if (abandoned != null) {
            IO.execute(() -> unlock(abandoned));
        }
        Log.i(TAG, "lock held; heartbeat every " + ticket.intervalSeconds + "s");
    }

    /**
     * Releases the current lock and stops its heartbeat. Safe to call from the main thread
     * and safe to call when nothing is held.
     */
    public static void release() {
        Ticket ticket;
        synchronized (STATE) {
            ticket = current;
            current = null;
            generation++;
            cancelHeartbeatLocked();
        }
        if (ticket != null) {
            IO.execute(() -> unlock(ticket));
        }
    }

    /**
     * Releases the current lock before the next selector call, waiting for the unlock so
     * the account slot is free by the time {@code /media} is asked for a new one. Must be
     * called from a background thread.
     */
    public static void releaseBlocking() {
        Ticket ticket;
        synchronized (STATE) {
            ticket = current;
            current = null;
            generation++;
            cancelHeartbeatLocked();
        }
        try {
            // Run it on the heartbeat thread so an in-flight update can never overtake the
            // unlock, and wait for it so the slot is free before /media is called. The task
            // is submitted even without a lock: it also waits for an unlock that a closing
            // player queued a moment ago.
            IO.submit(() -> {
                if (ticket != null) {
                    unlock(ticket);
                }
            }).get(7, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Best effort: a stale lock expires on the server.
        }
    }

    // ---------------------------------------------------------------- heartbeat

    private static void scheduleLocked(int forGeneration, long delaySeconds) {
        heartbeat = IO.schedule(() -> beat(forGeneration),
                Math.max(1L, delaySeconds), TimeUnit.SECONDS);
    }

    private static void cancelHeartbeatLocked() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    private static void beat(int forGeneration) {
        Ticket ticket;
        synchronized (STATE) {
            if (forGeneration != generation || current == null) {
                return;
            }
            ticket = current;
        }

        boolean lost = false;
        boolean success = false;
        try {
            JSONObject response = call(ticket, UPDATE_PATH);
            JSONObject update = response.optJSONObject("updateResponse");
            if (update != null) {
                Ticket refreshed = ticket.updated(
                        update.optString("encryptedLock", ""),
                        update.optString("id", ""),
                        update.optString("sequenceToken", ""));
                synchronized (STATE) {
                    if (forGeneration != generation || current != ticket) {
                        return;
                    }
                    current = refreshed;
                    updateFailures = 0;
                    cancelHeartbeatLocked();
                    scheduleLocked(forGeneration, refreshed.intervalSeconds);
                }
                success = true;
            } else {
                int code = response.optInt("responseCode", 0);
                lost = code == FORBIDDEN || code == UNPROCESSABLE;
            }
        } catch (ApiException error) {
            int status = error.httpStatus();
            lost = status == FORBIDDEN || status == UNPROCESSABLE;
            Log.w(TAG, "heartbeat failed: HTTP " + status + " " + error.getMessage());
        }

        if (success) {
            return;
        }
        synchronized (STATE) {
            if (forGeneration != generation) {
                return;
            }
            if (lost) {
                Log.w(TAG, "lock lost; heartbeat stopped");
                current = null;
                generation++;
                cancelHeartbeatLocked();
                return;
            }
            updateFailures++;
            if (updateFailures > MAX_UPDATE_FAILURES) {
                Log.w(TAG, "heartbeat gave up after " + updateFailures + " failures");
                current = null;
                generation++;
                cancelHeartbeatLocked();
                return;
            }
            cancelHeartbeatLocked();
            scheduleLocked(forGeneration, RETRY_INTERVAL_SECONDS);
        }
    }

    // ---------------------------------------------------------------- transport

    private static void unlock(Ticket ticket) {
        if (ticket == null || !ticket.isValid()) {
            return;
        }
        try {
            JSONObject response = call(ticket, UNLOCK_PATH);
            Log.i(TAG, "unlock responseCode=" + response.optInt("responseCode", 0));
        } catch (ApiException error) {
            Log.w(TAG, "unlock failed: HTTP " + error.httpStatus() + " " + error.getMessage());
        }
    }

    private static JSONObject call(Ticket ticket, String path) throws ApiException {
        Context context = appContext;
        if (context == null) {
            throw new ApiException(ApiException.SERVER, "no context for concurrency call");
        }
        String base = ticket.serviceUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpUrl parsed = HttpUrl.parse(base + path);
        if (parsed == null) {
            throw new ApiException(ApiException.SERVER, "bad concurrency URL");
        }
        HttpUrl url = parsed.newBuilder()
                .addQueryParameter("_clientId", GuestAuth.deviceId(context))
                .addQueryParameter("form", "json")
                .addQueryParameter("schema", "1.0")
                .addQueryParameter("natco_key", GuestAuth.NATCO_KEY)
                .addQueryParameter("_encryptedLock", jsEncode(ticket.lock))
                .addQueryParameter("_id", jsEncode(ticket.lockId))
                .addQueryParameter("_sequenceToken", jsEncode(ticket.sequenceToken))
                .addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE)
                .build();

        Request.Builder request = new Request.Builder().url(url);
        for (java.util.Map.Entry<String, String> header
                : GuestAuth.headers(context).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        // The web client builds these calls with token:false, so they deliberately carry
        // no bff_token / x-tv-flow / x-tv-step. The rest of the account envelope is still
        // sent, which is what marks the caller as AUTH_USER.
        Session session = Session.get(context);
        request.header("x-call-type", session.isLoggedIn() ? "AUTH_USER" : "GUEST_USER");
        if (session.isLoggedIn()) {
            if (!session.channelMapId().isEmpty()) {
                request.header("x-channel-map-id", session.channelMapId());
            }
            if (!session.userId().isEmpty()) {
                request.header("x-user-id", session.userId());
            }
            if (!session.accountDetails().isEmpty()) {
                request.header("x-account-details", session.accountDetails());
            }
        }

        try (Response response = http().newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw BffApi.failure(text, response.code(), path);
            }
            if (text.trim().isEmpty()) {
                return new JSONObject();
            }
            try {
                return new JSONObject(text);
            } catch (JSONException error) {
                throw new ApiException(ApiException.PARSE,
                        "Neispravan odgovor poslužitelja.", response.code(), error);
            }
        } catch (IOException error) {
            throw new ApiException(ApiException.NETWORK,
                    error.getMessage() == null ? "Mreža nije dostupna." : error.getMessage(),
                    0, error);
        }
    }

    private static okhttp3.OkHttpClient http() {
        okhttp3.OkHttpClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (ConcurrencyLock.class) {
            if (client == null) {
                client = Http.client().newBuilder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .writeTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(6, TimeUnit.SECONDS)
                        .build();
            }
            return client;
        }
    }

    /**
     * {@code encodeURIComponent} from the web client, used for the first of the two
     * encoding passes the concurrency endpoints expect. OkHttp adds the second pass when
     * the value goes into the query string.
     */
    private static String jsEncode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '!'
                    || b == '~' || b == '*' || b == '\'' || b == '(' || b == ')') {
                out.append((char) b);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
