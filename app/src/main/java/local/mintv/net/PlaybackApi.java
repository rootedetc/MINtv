package local.mintv.net;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import local.mintv.data.Channel;
import local.mintv.data.Session;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgProgram;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Authenticated playback lookup: playinfo -> /media -> Widevine licence material.
 *
 * <p>All three shapes are transcribed from the shipped web client (see
 * {@code /tmp/maxtv/re/web/PROTOCOL.md}):</p>
 * <pre>
 * GET /hr-bifrost/player/playinfo/channel?channel_number&amp;natco_key&amp;device_type=WEB…
 *     header bff_token: &lt;jwt&gt;      (note: bff_token, not Authorization)
 * GET /hr-bifrost/media?client_id&amp;media_id&amp;natco_key&amp;src_format=MPEG-DASH&amp;content_type=live…
 *     -> { lock, video: { video_src, pid } }
 * </pre>
 * <p>The Widevine licence needs the {@code dc_cts_personaToken} / {@code dc_cts_accountId}
 * claims of the session JWT and is authorised with HTTP Basic
 * {@code b64(accountUrl/accountId:personaToken)}.</p>
 *
 * <p><b>Catch-up / start-over</b> (PROTOCOL.md §3.9–§3.10). Both use the same three calls, only
 * the playinfo route and the selector {@code content_type} differ:</p>
 * <pre>
 * GET /hr-bifrost/player/playinfo/catchup
 *       ?program_id&amp;natco_key&amp;device_type=WEB&amp;station_id
 *       &amp;start_time&amp;end_time[&amp;channel_number][&amp;listing_guid][&amp;app_language][&amp;natco_code]
 * GET /hr-bifrost/media?…&amp;content_type=catchup
 * </pre>
 * <p>{@code start_time}/{@code end_time} are the API's own wire format
 * ({@code yyyy-MM-dd'T'HH:mm:ss.SS'Z'}, UTC), rendered from the EPG epoch millis. Start-over of a
 * live programme is the same flow: the BFF resolves the on-air listing to its timeshift asset and
 * answers with {@code type=catchup}, which is then also the {@code /media} content type
 * (§3.10: {@code content_type} is {@code playBackInfoResponse.type}).</p>
 */
public final class PlaybackApi {

    private static final String TAG = "MinTvPlayback";
    /** Log tag for the catch-up flow, kept separate so it is easy to filter out of live logs. */
    private static final String CATCHUP_TAG = "MinTvCatchup";
    private static final String WIDEVINE_LICENSE =
            "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense";
    private static final String ACCOUNT_URL = "http://access.auth.theplatform.com/data/Account";
    private static final String MSG_NOT_CATCHUP = "This programme is not available for catch-up.";
    private static final String MSG_NOT_START_OVER = "This programme is not available for restart.";
    private static final String MSG_SESSION_EXPIRED =
            "Your session has expired. Please sign in again.";

    /** The EPG wire format for playinfo times: {@code 2026-10-25T10:03:00.00Z}, UTC. */
    private static final DateTimeFormatter WIRE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SS'Z'", Locale.US)
                    .withZone(ZoneOffset.UTC);

    /** Everything the player needs for one channel. */
    public static final class Playback {
        public final String manifestUrl;
        public final String licenseUrl;
        public final String licenseAuthorization;

        Playback(String manifestUrl, String licenseUrl, String licenseAuthorization) {
            this.manifestUrl = manifestUrl;
            this.licenseUrl = licenseUrl;
            this.licenseAuthorization = licenseAuthorization;
        }

        public boolean isDrm() {
            return licenseUrl != null && !licenseUrl.isEmpty();
        }
    }

    public interface Callback {
        void onReady(Playback playback);

        void onFailure(ApiException error);
    }

    private interface Task {
        Playback call() throws ApiException;
    }

    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maxtv-playback");
        t.setDaemon(true);
        return t;
    });

    public PlaybackApi(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Live channel playback (unchanged path: playinfo/channel -> /media). */
    public void resolve(Channel channel, Callback callback) {
        run(() -> doResolve(channel), callback);
    }

    /**
     * Catch-up playback of a listing that has already aired.
     *
     * <p>Fails with a readable {@link ApiException} (not a bare HTTP error) when the listing has no
     * catch-up flag or the account is not entitled to it.</p>
     */
    public void resolveCatchup(EpgChannel channel, EpgProgram program, Callback callback) {
        run(() -> doResolveCatchup(channel, program, false), callback);
    }

    /**
     * "Start from the beginning" for the programme currently on air: the same catch-up route with
     * the live listing's own start/end, i.e. the server-side timeshift copy of the broadcast.
     */
    public void resolveTimeshift(EpgChannel channel, EpgProgram program, Callback callback) {
        run(() -> doResolveCatchup(channel, program, true), callback);
    }

    private void run(Task task, Callback callback) {
        io.execute(() -> {
            try {
                callback.onReady(task.call());
            } catch (ApiException e) {
                callback.onFailure(e);
            } catch (Throwable t) {
                callback.onFailure(new ApiException(ApiException.SERVER, String.valueOf(t), 0, t));
            }
        });
    }

    private Playback doResolve(Channel channel) throws ApiException {
        Session session = Session.get(appContext);
        String token = session.accessToken();
        if (token.isEmpty()) {
            throw new ApiException(ApiException.AUTH, "not signed in");
        }

        JSONObject playinfo = get(url("/player/playinfo/channel")
                .addQueryParameter("natco_key", GuestAuth.NATCO_KEY)
                .addQueryParameter("device_type", "WEB")
                .addQueryParameter("channel_number", Integer.toString(channel.getNumber()))
                .addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE)
                .build(), token);

        JSONObject data = playinfo.optJSONObject("data");
        JSONObject info = data == null ? null : data.optJSONObject("playBackInfoResponse");
        String type = info == null ? "live" : info.optString("type", "live");
        String mediaId = firstServiceMediaId(info);
        if (mediaId.isEmpty()) {
            mediaId = channel.getMediaPid();
        }
        if (mediaId.isEmpty()) {
            throw new ApiException(ApiException.PARSE, "no media_id for channel " + channel.getNumber());
        }
        Log.i(TAG, "playinfo type=" + type + " mediaId=" + mediaId);

        return fetchPlayback(mediaId, type, token);
    }

    /**
     * Resolves a past (catch-up) or currently airing (start-over) listing.
     *
     * @param startOver {@code true} for "start from the beginning": requires the listing to be on
     *                  air, otherwise a plain catch-up of a finished listing is requested.
     */
    private Playback doResolveCatchup(EpgChannel channel, EpgProgram program, boolean startOver)
            throws ApiException {
        String notAvailable = startOver ? MSG_NOT_START_OVER : MSG_NOT_CATCHUP;
        if (channel == null || program == null) {
            throw new ApiException(ApiException.PARSE, notAvailable);
        }

        Session session = Session.get(appContext);
        String token = session.accessToken();
        if (token.isEmpty()) {
            throw new ApiException(ApiException.AUTH, "not signed in");
        }

        long now = System.currentTimeMillis();
        if (startOver) {
            // §3.9: start-over is offered only while the programme is on air and catch-up is
            // enabled for the listing.
            if (!program.isLive(now) || !program.catchup) {
                throw new ApiException(ApiException.SERVER, notAvailable);
            }
        } else if (!program.catchup) {
            // is_catchup_enabled=false (or the listing is too old) -> no entitlement.
            throw new ApiException(ApiException.SERVER, notAvailable);
        }

        String programId = nullToEmpty(program.id);
        String stationId = nullToEmpty(channel.stationId);
        if (programId.isEmpty() || stationId.isEmpty()
                || program.startMs <= 0L || program.endMs <= program.startMs) {
            Log.w(CATCHUP_TAG, "incomplete listing program=" + programId + " station=" + stationId
                    + " start=" + program.startMs + " end=" + program.endMs);
            throw new ApiException(ApiException.PARSE, notAvailable);
        }

        HttpUrl.Builder playinfoUrl = url("/player/playinfo/catchup")
                .addQueryParameter("program_id", programId)
                .addQueryParameter("natco_key", GuestAuth.NATCO_KEY)
                .addQueryParameter("device_type", "WEB")
                .addQueryParameter("station_id", stationId)
                .addQueryParameter("start_time", wireTime(program.startMs))
                .addQueryParameter("end_time", wireTime(program.endMs));
        if (channel.number > 0) {
            playinfoUrl.addQueryParameter("channel_number", Integer.toString(channel.number));
        }
        if (!nullToEmpty(program.listingGuid).isEmpty()) {
            // §3.9 lists listing_guid as an optional catch-up parameter. It identifies the exact
            // EPG listing when the same programme_id airs more than once; send it when present.
            playinfoUrl.addQueryParameter("listing_guid", program.listingGuid);
        }
        playinfoUrl.addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE);
        HttpUrl requestUrl = playinfoUrl.build();
        Log.i(CATCHUP_TAG, "playinfo " + (startOver ? "start-over" : "catch-up")
                + " program=" + programId + " station=" + stationId
                + " start=" + wireTime(program.startMs) + " end=" + wireTime(program.endMs)
                + " listing_guid=" + nullToEmpty(program.listingGuid)
                + " media_id_hint=" + nullToEmpty(program.mediaId));

        JSONObject response;
        try {
            response = getDetailed(requestUrl, token);
        } catch (ApiException e) {
            throw catchupFailure(e, notAvailable);
        }

        JSONObject data = response.optJSONObject("data");
        JSONObject info = data == null ? null : data.optJSONObject("playBackInfoResponse");
        if (info == null) {
            String serverMessage = serverMessage(response);
            Log.w(CATCHUP_TAG, "no playBackInfoResponse; server message=" + serverMessage);
            // A 200 without playback info means the listing could not be resolved for this
            // account; surface the same entitlement message rather than a parse error.
            throw new ApiException(ApiException.SERVER, notAvailable);
        }

        String type = info.optString("type", "catchup");
        if (type == null || type.isEmpty()) {
            type = "catchup";
        }
        JSONArray serviceItems = info.optJSONArray("service_items");
        boolean itemsEmpty = serviceItems == null || serviceItems.length() == 0;
        String mediaId = firstServiceMediaId(info);
        boolean usedProgrammeMediaId = false;
        if (mediaId.isEmpty()) {
            mediaId = nullToEmpty(program.mediaId);
            usedProgrammeMediaId = !mediaId.isEmpty();
        }
        Log.i(CATCHUP_TAG, "playinfo type=" + type + " service_items_empty=" + itemsEmpty
                + " media_id=" + mediaId + (usedProgrammeMediaId ? " (from EPG listing)" : ""));
        if (mediaId.isEmpty()) {
            throw new ApiException(ApiException.SERVER, notAvailable);
        }

        // §3.10 getContentType(): for VOD the selector wants playBackInfoResponse.content_type,
        // for everything else (live/catchup/recording) the playBackInfoResponse.type itself.
        // A catch-up request may legitimately be fulfilled as VOD ("watch" item), so honour the
        // returned type instead of forcing "catchup".
        String contentType = "vod".equals(type) ? info.optString("content_type", type) : type;
        if (contentType == null || contentType.isEmpty()) {
            contentType = type;
        }
        return fetchPlayback(mediaId, contentType, token);
    }

    /**
     * The selector + licence half of the flow, shared by live and catch-up so the DRM material is
     * built exactly once.
     */
    private Playback fetchPlayback(String mediaId, String contentType, String token)
            throws ApiException {
        JSONObject media = get(url("/media")
                .addQueryParameter("client_id", GuestAuth.deviceId(appContext))
                .addQueryParameter("media_id", mediaId)
                .addQueryParameter("natco_key", GuestAuth.NATCO_KEY)
                .addQueryParameter("src_format", "MPEG-DASH")
                .addQueryParameter("content_type", contentType)
                .addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE)
                .build(), token);

        JSONObject video = media.optJSONObject("video");
        if (video == null && media.optJSONObject("data") != null) {
            video = media.optJSONObject("data").optJSONObject("video");
        }
        String src = video == null ? "" : video.optString("video_src", "");
        String pid = video == null ? "" : video.optString("pid", "");
        if (src.isEmpty()) {
            throw new ApiException(ApiException.SERVER, "no video_src in /media response");
        }
        Log.i(TAG, "media pid=" + pid + " src=" + src);

        String licenseUrl = "";
        String authorization = "";
        if (!pid.isEmpty()) {
            JSONObject claims = jwtClaims(token);
            String persona = claims == null ? "" : claims.optString("dc_cts_personaToken", "");
            String account = claims == null ? "" : claims.optString("dc_cts_accountId", "");
            if (!persona.isEmpty() && !account.isEmpty()) {
                licenseUrl = WIDEVINE_LICENSE + "?schema=1.0&form=json&releasePid="
                        + java.net.URLEncoder.encode(pid, StandardCharsets.UTF_8);
                String userInfo = ACCOUNT_URL + "/" + account + ":" + persona;
                authorization = "Basic " + Base64.encodeToString(
                        userInfo.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            } else {
                Log.w(TAG, "session JWT has no dc_cts_* claims; playing clear");
            }
        }
        return new Playback(src, licenseUrl, authorization);
    }

    // ---------------------------------------------------------------- helpers

    private static String wireTime(long epochMs) {
        return WIRE_TIME.format(Instant.ofEpochMilli(epochMs));
    }

    private static HttpUrl.Builder url(String path) {
        return HttpUrl.parse(MinTvApi.BASE_URL + path).newBuilder();
    }

    private Request buildGet(HttpUrl url, String bffToken) {
        return new Request.Builder()
                .url(url)
                .header("bff_token", bffToken)
                .header("Device-Density", "xhdpi")
                .header("tenant", "tv")
                .header("app_key", GuestAuth.APP_KEY)
                .header("app_version", GuestAuth.APP_VERSION)
                .header("x-call-type", "AUTH_USER")
                .header("x-user-agent", GuestAuth.USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build();
    }

    private JSONObject get(HttpUrl url, String bffToken) throws ApiException {
        return send(buildGet(url, bffToken), url, false);
    }

    /** Same as {@link #get} but appends the server's {@code displayMessage} to failures. */
    private JSONObject getDetailed(HttpUrl url, String bffToken) throws ApiException {
        return send(buildGet(url, bffToken), url, true);
    }

    private JSONObject send(Request request, HttpUrl url, boolean detailed) throws ApiException {
        try (Response response = Http.client().newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                int code = response.code() == 401 || response.code() == 403
                        ? ApiException.AUTH : ApiException.SERVER;
                String message = "HTTP " + response.code() + " " + url.encodedPath();
                if (detailed) {
                    String detail = serverMessage(text);
                    if (!detail.isEmpty()) {
                        message = message + ": " + detail;
                    }
                }
                throw new ApiException(code, message, response.code(), null);
            }
            return new JSONObject(text);
        } catch (IOException e) {
            throw new ApiException(ApiException.NETWORK, String.valueOf(e.getMessage()), 0, e);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "bad JSON from " + url.encodedPath(), 0, e);
        }
    }

    /**
     * Turns a failed catch-up call into a user-facing reason. 4xx on this route means "this
     * listing is not in the catch-up catalogue / not entitled" rather than a transport problem,
     * so it must not surface as a bare HTTP status.
     */
    private static ApiException catchupFailure(ApiException cause, String notAvailable) {
        int status = cause.httpStatus();
        // 403 is what the BFF returns for "not in the catch-up catalogue / no entitlement", so it
        // must become the readable message rather than an auth failure.
        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.US);
        if (status == 400 || status == 403 || status == 404 || status == 422
                || message.contains("catchup not available")
                || message.contains("catch-up not available")) {
            return new ApiException(ApiException.SERVER, notAvailable, status, cause);
        }
        if (status == 401 || cause.code() == ApiException.AUTH) {
            return new ApiException(ApiException.AUTH, MSG_SESSION_EXPIRED, status, cause);
        }
        return cause;
    }

    /** Reads the BFF error envelope's human readable field, empty when there is none. */
    private static String serverMessage(JSONObject object) {
        if (object == null) {
            return "";
        }
        String message = object.optString("displayMessage", "");
        if (message.isEmpty()) {
            message = object.optString("debugMessage", "");
        }
        if (message.isEmpty()) {
            message = object.optString("error_message", "");
        }
        return nullToEmpty(message);
    }

    private static String serverMessage(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            return serverMessage(new JSONObject(body));
        } catch (JSONException e) {
            return "";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstServiceMediaId(JSONObject info) {
        if (info == null) {
            return "";
        }
        Object items = info.opt("service_items");
        if (items instanceof JSONArray) {
            JSONArray array = (JSONArray) items;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    String id = item.optString("media_id", "");
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        }
        return info.optString("media_id", "");
    }

    static JSONObject jwtClaims(String jwt) {
        int first = jwt.indexOf('.');
        int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
        if (first < 0 || second < 0) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(jwt.substring(first + 1, second),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }
}
