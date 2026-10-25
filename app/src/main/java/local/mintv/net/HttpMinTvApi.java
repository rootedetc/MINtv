package local.mintv.net;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

import local.mintv.data.Session;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Real MAXtv login: the two-step "native login" used by the web/IDM stack.
 *
 * <pre>
 *   1. POST {IDM}/onboarding/login
 *        body: {appVersion, channel, natco, type, context, device, telekomLogin}
 *        telekomLogin.password = base64(RSA-OAEP-SHA1(utf8 password, CMS key))
 *        -> {accessToken, tvAccountIds:[{id}], ...}
 *
 *   2. POST {IDM}/onboarding/authenticate?legacyFlow=false
 *        header: ms_access_token: &lt;accessToken&gt;
 *        body:   {appVersion, channel, natco, type, context, device, tvAccountId, concurrencyLimitParam}
 *        -> BFF token / session blob
 * </pre>
 *
 * <p>The envelope (app_key / x-txn-id / tenant ...) is the same as the guest endpoints, plus
 * a mandatory {@code requestId} header on the IDM host. Step 1 is verified: a wrong password
 * yields {@code 401 central-auth.login.unauthorized}, i.e. the server decrypts our OAEP
 * blob successfully. Step 2's token field name is not yet confirmed, so parsing is tolerant
 * and the response key set is logged (values never are).</p>
 */
public final class HttpMinTvApi implements MinTvApi {

    private static final String TAG = "MinTvLogin";

    private static final String IDM = "https://gateway-hr-proxy.tv.yo-digital.com/hr-idm/P";
    private static final String CHANNEL_ID = "Tv";
    private static final String STREAM_LIMIT_KEY = "TVSOA-restriction-unmanagedDeviceStreamLimit";

    /** CMS {@code modules.auth.rsaPublicKey} (1024-bit SPKI), used only to encrypt the password. */
    private static final String LOGIN_PUBLIC_KEY_B64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDMmKReuzuaCk10Wa6vv4ybcqjVN3cruj27IRp9"
            + "YhdgEw9jcG728Aj9s60mY8B/czzW5ntKJQktyBRBZ98BKznRWBrVN/n9JR/m1UDc38PW4BPe4z5"
            + "VtBe99dyFcJQ1VJij6HG0BFtw3isPR5NAUAAyGnXpNWKCat5TtBckqVatBQIDAQAB";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maxtv-login");
        t.setDaemon(true);
        return t;
    });

    public HttpMinTvApi(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void login(String username, String password, LoginCallback callback) {
        io.execute(() -> {
            try {
                Session session = doLogin(username, password);
                mainHandler.post(() -> callback.onSuccess(session));
            } catch (ApiException e) {
                mainHandler.post(() -> callback.onFailure(e));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onFailure(
                        new ApiException(ApiException.SERVER, String.valueOf(t), 0, t)));
            }
        });
    }

    private Session doLogin(String username, String password) throws ApiException {
        String cipherB64 = encryptPassword(password);

        JSONObject step1 = baseBody();
        try {
            JSONObject tel = new JSONObject();
            tel.put("username", username);
            tel.put("password", cipherB64);
            step1.put("telekomLogin", tel);
            step1.put("context", "login");
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "cannot build login body", 0, e);
        }

        JSONObject loginResp = postJson(IDM + "/onboarding/login", step1, null);
        Log.i(TAG, "step1 keys=" + keysOf(loginResp));

        if (loginResp.optBoolean("deviceLimitExceed", false)) {
            throw new ApiException(ApiException.AUTH,
                    "Device limit reached for this account — sign out another device first.");
        }

        String accessToken = firstString(loginResp, "accessToken", "access_token", "token");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new ApiException(ApiException.PARSE,
                    "login succeeded but no access token in " + keysOf(loginResp));
        }
        String refresh = firstString(loginResp, "refreshToken", "refresh_token");
        long expiresIn = loginResp.optLong("accessExpiresIn", 0L);

        // Single-subscription accounts (the common case) use the login token as the BFF
        // session token directly. Only a multi-subscription account needs the second call.
        int accounts = countAccounts(loginResp);
        Log.i(TAG, "step1 token=" + redact(accessToken) + " accounts=" + accounts);
        JSONObject sessionJson = loginResp;
        if (accounts >= 2) {
            String accountId = firstAccountId(loginResp);
            JSONObject step2 = baseBody();
            try {
                step2.put("tvAccountId", accountId);
                step2.put("concurrencyLimitParam", STREAM_LIMIT_KEY);
                step2.put("context", "login");
            } catch (JSONException e) {
                throw new ApiException(ApiException.PARSE, "cannot build authenticate body", 0, e);
            }
            JSONObject authResp = postJson(IDM + "/onboarding/authenticate?legacyFlow=false",
                    step2, accessToken);
            Log.i(TAG, "step2 keys=" + keysOf(authResp));
            accessToken = firstString(authResp, "accessToken", "token", "bffToken");
            if (accessToken == null || accessToken.isEmpty()) {
                throw new ApiException(ApiException.PARSE,
                        "authenticate returned no token in " + keysOf(authResp));
            }
            String r2 = firstString(authResp, "refreshToken", "refresh_token");
            if (r2 != null && !r2.isEmpty()) {
                refresh = r2;
            }
            expiresIn = authResp.optLong("accessExpiresIn", expiresIn);
            sessionJson = authResp;
        }

        String claims = jwtPayload(accessToken);
        if (claims != null) {
            try {
                JSONObject c = new JSONObject(claims);
                Log.i(TAG, "bffToken claims=" + keysOf(c)
                        + " dc_cts_personaToken=" + (c.has("dc_cts_personaToken") ? "yes" : "no")
                        + " dc_cts_accountId=" + (c.has("dc_cts_accountId") ? "yes" : "no"));
            } catch (JSONException ignored) {
                // not a JWT payload we understand; the token may still be valid
            }
        }

        long expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000L : 0L;
        Session session = Session.get(appContext);
        session.save(accessToken, refresh == null ? "" : refresh, "Bearer", expiresAt, username, "");
        session.saveRaw(sessionJson.toString());
        Log.i(TAG, "login complete, token=" + redact(accessToken)
                + " expiresAt=" + expiresAt);
        return session;
    }

    private static int countAccounts(JSONObject json) {
        Object accounts = json.opt("tvAccountIds");
        return accounts instanceof JSONArray ? ((JSONArray) accounts).length() : 0;
    }

    /** Decodes the payload segment of a JWT without validating it. */
    private static String jwtPayload(String jwt) {
        int first = jwt.indexOf('.');
        int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
        if (first < 0 || second < 0) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(jwt.substring(first + 1, second),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- requests

    private JSONObject baseBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("appVersion", GuestAuth.APP_VERSION);
            JSONObject channel = new JSONObject();
            channel.put("id", CHANNEL_ID);
            body.put("channel", channel);
            body.put("natco", GuestAuth.NATCO_CODE);
            body.put("type", "telekom");
            body.put("forceRegister", false);
            body.put("device", device());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return body;
    }

    private JSONObject device() throws JSONException {
        JSONObject d = new JSONObject();
        d.put("id", GuestAuth.deviceId(appContext));
        d.put("model", "WEB");
        d.put("os", "Android " + Build.VERSION.RELEASE);
        d.put("deviceName", "WEB");
        d.put("manageDevice", false);
        d.put("deviceType", "WEB");
        d.put("deviceOS", "Android " + Build.VERSION.RELEASE);
        d.put("deviceModel", "MINtv");
        d.put("deviceManufacturer", Build.MANUFACTURER);
        d.put("concurrencyLimitParam", STREAM_LIMIT_KEY);
        d.put("broadcastingStreamLimitationApplies", false);
        return d;
    }

    private JSONObject postJson(String url, JSONObject body, String bearer) throws ApiException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON));
        for (Map.Entry<String, String> e : GuestAuth.headers(appContext).entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        builder.header("requestId", java.util.UUID.randomUUID().toString());
        if (bearer != null) {
            builder.header("ms_access_token", bearer);
        }
        Request request = builder.build();
        try (Response response = Http.client().newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String message = errorMessage(text, response.code());
                int code = response.code() == 401 || response.code() == 403
                        ? ApiException.AUTH : ApiException.SERVER;
                throw new ApiException(code, message, response.code(), null);
            }
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException(ApiException.PARSE,
                        "non-JSON response (" + response.code() + ")", response.code(), e);
            }
        } catch (IOException e) {
            throw new ApiException(ApiException.NETWORK, String.valueOf(e.getMessage()), 0, e);
        }
    }

    // ---------------------------------------------------------------- crypto

    private static String encryptPassword(String password) throws ApiException {
        try {
            byte[] der = Base64.decode(LOGIN_PUBLIC_KEY_B64, Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] out = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new ApiException(ApiException.SERVER, "password encryption failed: " + e, 0, e);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static String errorMessage(String body, int status) {
        try {
            JSONObject json = new JSONObject(body);
            String display = json.optString("displayMessage", "");
            String message = json.optString("message", "");
            String code = json.optString("code", "");
            if (message.contains("Unauthorized_login") || code.endsWith("login.unauthorized")) {
                return "Wrong username or password.";
            }
            if (display.isEmpty() && message.isEmpty() && code.isEmpty()) {
                return "HTTP " + status;
            }
            String detail = !display.isEmpty() ? display : message;
            if (code.isEmpty()) {
                return detail + " (HTTP " + status + ")";
            }
            return detail + " [" + code + "]";
        } catch (JSONException ignored) {
            // fall through
        }
        return "HTTP " + status;
    }

    /** Depth-first search for the first non-empty string value under any of {@code names}. */
    private static String firstString(Object node, String... names) {
        for (String name : names) {
            String direct = directString(node, name);
            if (direct != null && !direct.isEmpty()) {
                return direct;
            }
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String value = firstString(obj.opt(it.next()), names);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String value = firstString(array.opt(i), names);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String directString(Object node, String name) {
        if (!(node instanceof JSONObject)) {
            return null;
        }
        Object value = ((JSONObject) node).opt(name);
        return value instanceof String ? (String) value : null;
    }

    private static String firstAccountId(JSONObject json) {
        Object accounts = json.opt("tvAccountIds");
        if (accounts instanceof JSONArray) {
            JSONArray array = (JSONArray) accounts;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    Object id = ((JSONObject) item).opt("id");
                    if (id instanceof String && !((String) id).isEmpty()) {
                        return (String) id;
                    }
                } else if (item instanceof String) {
                    return (String) item;
                }
            }
        }
        return firstString(json, "tvAccountId");
    }

    private static String keysOf(JSONObject json) {
        StringBuilder out = new StringBuilder("[");
        Iterator<String> it = json.keys();
        while (it.hasNext()) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(it.next());
        }
        return out.append(']').toString();
    }

    private static String redact(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 10 ? "***"
                : token.substring(0, 4) + "…" + token.substring(token.length() - 4)
                        + " (" + token.length() + ")";
    }
}
