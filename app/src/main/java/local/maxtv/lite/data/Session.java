package local.maxtv.lite.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Token storage for the future authenticated MAXtv session.
 *
 * <p>Milestone 1 only needs a stable, documented place for the login implementation to
 * write its result. {@link local.maxtv.lite.net.MaxTvApi} implementations are expected to
 * call {@link #save} before invoking their success callback, so the UI never has to know
 * anything about token formats.</p>
 *
 * <p>Storage is plain {@link SharedPreferences} inside the app sandbox. When the real
 * protocol lands, refresh tokens should move to Keystore-backed encryption (the same
 * approach already used for the optional saved password in
 * {@link CredentialStore}); do not put long-lived refresh tokens in world-readable
 * storage or logs.</p>
 */
public final class Session {

    private static final String PREFS = "session";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_TOKEN_TYPE = "token_type";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "display_name";

    private final SharedPreferences prefs;

    private Session(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static Session get(Context context) {
        return new Session(context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    /**
     * Persists the result of a successful authentication.
     *
     * @param accessToken  bearer token used for authorised API calls
     * @param refreshToken refresh token, may be empty if the protocol does not issue one
     * @param expiresAt    epoch millis, or 0 when unknown / non-expiring
     * @param username     account identifier
     * @param displayName  human readable name, may be empty
     */
    public void save(String accessToken,
                     String refreshToken,
                     String tokenType,
                     long expiresAt,
                     String username,
                     String displayName) {
        prefs.edit()
                .putString(KEY_ACCESS, safe(accessToken))
                .putString(KEY_REFRESH, safe(refreshToken))
                .putString(KEY_TOKEN_TYPE, safe(tokenType))
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .putString(KEY_USERNAME, safe(username))
                .putString(KEY_DISPLAY_NAME, safe(displayName))
                .apply();
    }

    public boolean isLoggedIn() {
        String token = accessToken();
        if (token.isEmpty()) {
            return false;
        }
        long expiresAt = expiresAt();
        return expiresAt == 0 || expiresAt > System.currentTimeMillis();
    }

    public String accessToken() {
        return prefs.getString(KEY_ACCESS, "");
    }

    public String refreshToken() {
        return prefs.getString(KEY_REFRESH, "");
    }

    public String tokenType() {
        return prefs.getString(KEY_TOKEN_TYPE, "Bearer");
    }

    public long expiresAt() {
        return prefs.getLong(KEY_EXPIRES_AT, 0);
    }

    public String username() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String displayName() {
        return prefs.getString(KEY_DISPLAY_NAME, "");
    }

    /**
     * Opaque session payload from the second login step. The BFF later needs values from
     * it (account details / {@code dc_cts_*} tokens), so keep the raw JSON in the sandbox.
     */
    public void saveRaw(String json) {
        prefs.edit().putString("raw", safe(json)).apply();
    }

    public String raw() {
        return prefs.getString("raw", "");
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
