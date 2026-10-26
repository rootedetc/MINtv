package local.mintv.net;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import local.mintv.data.Session;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shared BFF transport for every non-playback MAXtv endpoint.
 *
 * <p>The guest endpoints use {@link GuestAuth#headers(Context)}. Authenticated calls add the
 * account-derived headers required by the web client: {@code bff_token}, {@code x-channel-map-id},
 * {@code x-user-id}, {@code x-account-details}, and {@code x-call-type: AUTH_USER}.</p>
 *
 * <p>The BFF URL builder always appends {@code app_language=hr} and {@code natco_code=hr}. Callers
 * pass only the endpoint-specific query parameters. A missing token fails locally with
 * {@link ApiException#AUTH} instead of spending a round trip on a guaranteed 401.</p>
 */
public final class BffApi {

    public static final String BASE_URL = MinTvApi.BASE_URL;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private BffApi() {
    }

    public static HttpUrl.Builder url(String path) {
        HttpUrl base = HttpUrl.parse(BASE_URL + (path.startsWith("/") ? path : "/" + path));
        if (base == null) {
            throw new IllegalArgumentException("Bad BFF path: " + path);
        }
        return base.newBuilder();
    }

    public static Map<String, String> query() {
        return new LinkedHashMap<>();
    }

    public static Map<String, String> query(String key, Object value) {
        Map<String, String> map = query();
        if (value != null) {
            map.put(key, String.valueOf(value));
        }
        return map;
    }

    public static JSONObject get(Context context, String path, Map<String, String> query,
                                 boolean auth) throws ApiException {
        return get(context, path, query, auth, "", false, "", "", "");
    }

    public static JSONObject get(Context context, String path, Map<String, String> query,
                                 boolean auth, String density, boolean adult,
                                 String flow, String step, String cacheIgnore)
            throws ApiException {
        return execute(newCall(context, "GET", path, query, null, auth, adult, density,
                flow, step, cacheIgnore));
    }

    public static JSONObject post(Context context, String path, Map<String, String> query,
                                  JSONObject body, boolean auth) throws ApiException {
        return post(context, path, query, body, auth, "", false, "", "", "");
    }

    public static JSONObject post(Context context, String path, Map<String, String> query,
                                  JSONObject body, boolean auth, String density, boolean adult,
                                  String flow, String step, String cacheIgnore)
            throws ApiException {
        return execute(newCall(context, "POST", path, query, body, auth, adult, density,
                flow, step, cacheIgnore));
    }

    public static JSONObject put(Context context, String path, Map<String, String> query,
                                 JSONObject body, boolean auth, String density, boolean adult,
                                 String flow, String step, String cacheIgnore)
            throws ApiException {
        return execute(newCall(context, "PUT", path, query, body, auth, adult, density,
                flow, step, cacheIgnore));
    }

    /**
     * Builds one call without executing it. Search keeps the {@link Call} so a stale query can be
     * cancelled while the user is still typing.
     */
    public static Call newCall(Context context, String method, String path,
                               Map<String, String> query, JSONObject body,
                               boolean auth, boolean adult, String density,
                               String flow, String step, String cacheIgnore)
            throws ApiException {
        Session session = Session.get(context);
        if (auth && session.accessToken().isEmpty()) {
            throw new ApiException(ApiException.AUTH, "Prijava je potrebna.");
        }
        if (adult && session.adultToken().isEmpty()) {
            throw new ApiException(ApiException.AUTH, "Potreban je PIN za sadržaj za odrasle.");
        }

        HttpUrl.Builder url = url(path);
        url.addQueryParameter("app_language", GuestAuth.APP_LANGUAGE);
        url.addQueryParameter("natco_code", GuestAuth.NATCO_CODE);
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                url.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        if (adult) {
            url.addQueryParameter("is_adult_token", "true");
        }

        Request.Builder request = new Request.Builder().url(url.build());
        for (Map.Entry<String, String> header : GuestAuth.headers(context).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        request.header("x-call-type", auth ? "AUTH_USER" : "GUEST_USER");
        if (auth) {
            request.header("bff_token", session.accessToken());
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
        if (density != null && !density.isEmpty()) {
            request.header("Device-Density", density);
        }
        if (adult && !session.adultToken().isEmpty()) {
            request.header("X-Adult-Token", session.adultToken());
        }
        if (flow != null && !flow.isEmpty()) {
            request.header("x-tv-flow", flow);
        }
        if (step != null && !step.isEmpty()) {
            request.header("x-tv-step", step);
        }
        if (cacheIgnore != null && !cacheIgnore.isEmpty()) {
            request.header("x-cache-ignore", cacheIgnore);
        }

        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, RequestBody.create(body.toString(), JSON));
        } else {
            request.method(method, null);
        }
        return Http.client().newCall(request.build());
    }

    public static JSONObject execute(Call call) throws ApiException {
        Request request = call.request();
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw failure(text, response.code(), request.url().encodedPath());
            }
            if (text.trim().isEmpty()) {
                return new JSONObject();
            }
            JSONObject parsed = new JSONObject(text);
            logShape(request, response.code(), parsed);
            return parsed;
        } catch (IOException e) {
            throw new ApiException(ApiException.NETWORK,
                    e.getMessage() == null ? "Mreža nije dostupna." : e.getMessage(), 0, e);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE,
                    "Neispravan odgovor poslužitelja.", 0, e);
        }
    }

    /**
     * Debug-only response shape: path, status, top-level keys and the keys of the first element
     * of the first object array. Values are never logged, so tokens, account ids and personal
     * viewing history cannot leak through this line.
     */
    private static void logShape(Request request, int status, JSONObject body) {
        if (!Log.isLoggable("BffApi", Log.DEBUG)) {
            return;
        }
        String path = request.url().encodedPath();
        StringBuilder out = new StringBuilder();
        out.append(request.method()).append(' ').append(path).append(" -> ").append(status)
                .append(" keys=").append(keys(body));
        JSONArray names = body.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object value = body.opt(key);
                if (value instanceof JSONArray && ((JSONArray) value).length() > 0) {
                    JSONObject first = ((JSONArray) value).optJSONObject(0);
                    out.append(' ').append(key).append("[]=")
                            .append(first == null ? "scalar" : keys(first));
                } else if (value instanceof JSONObject) {
                    out.append(' ').append(key).append("={")
                            .append(keys((JSONObject) value)).append('}');
                }
            }
        }
        Log.d("BffApi", out.toString());
    }

    private static String keys(JSONObject object) {
        JSONArray names = object == null ? null : object.names();
        if (names == null) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < names.length(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(names.optString(i));
        }
        return out.append(']').toString();
    }

    public static ApiException failure(String body, int status, String path) {        String message = "HTTP " + status + " " + path;
        String code = "";
        try {
            JSONObject json = new JSONObject(body);
            code = json.optString("code", "");
            String display = json.optString("displayMessage", "");
            if (display.isEmpty()) {
                display = json.optString("error_message", "");
            }
            if (display.isEmpty()) {
                display = json.optString("debugMessage", "");
            }
            if (!display.isEmpty()) {
                message = display;
            }
            if (!code.isEmpty()) {
                message = message + " [" + code + "]";
            }
        } catch (JSONException ignored) {
            // keep the HTTP line
        }
        int kind = status == 401 || status == 403
                ? ApiException.AUTH
                : ApiException.SERVER;
        return new ApiException(kind, message, status, null);
    }
}
