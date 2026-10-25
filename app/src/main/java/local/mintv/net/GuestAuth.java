package local.mintv.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Headers for the public (GUEST_USER) MAXtv endpoints.
 *
 * <p>All values are derived per request except the device id, which is generated once and
 * persisted so the backend sees a stable client identity across launches.</p>
 *
 * <pre>
 * x-txn-id = sha256(trackingId + sessionId + deviceId + callTime)[0:32]
 * </pre>
 */
public final class GuestAuth {

    public static final String APP_KEY = "GWaBW4RTloLwpUgYVzOiW5zUxFLmoMj5";
    public static final String APP_VERSION = "02.0.1470";
    public static final String NATCO_KEY = "l2lyvGVbUm2EKJE96ImQgcc8PKMZWtbE";
    public static final String NATCO_CODE = "hr";
    public static final String APP_LANGUAGE = "hr";
    public static final String USER_AGENT = "web|web|Chrome-126|02.0.1470|1";

    private static final String PREFS = "guest_identity";
    private static final String KEY_DEVICE_ID = "device_id";

    private GuestAuth() {
    }

    /** Returns a stable lower-case UUID v4 device id, creating and persisting it if needed. */
    public static synchronized String deviceId(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_DEVICE_ID, "");
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    /** Builds the full guest header set for one logical request. */
    public static Map<String, String> headers(Context context) {
        String deviceId = deviceId(context);
        String sessionId = UUID.randomUUID().toString();
        String trackingId = UUID.randomUUID().toString();
        long callTime = System.currentTimeMillis();
        String txnId = sha256Hex(trackingId + sessionId + deviceId + callTime)
                .substring(0, 32);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("app_key", APP_KEY);
        headers.put("app_version", APP_VERSION);
        headers.put("device-id", deviceId);
        headers.put("tenant", "tv");
        headers.put("x-call-type", "GUEST_USER");
        headers.put("x-call-time", Long.toString(callTime));
        headers.put("x-request-session-id", sessionId);
        headers.put("x-request-tracking-id", trackingId);
        headers.put("x-txn-id", txnId);
        headers.put("x-user-agent", USER_AGENT);
        headers.put("Accept", "application/json");
        return headers;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Android version", e);
        }
    }
}
