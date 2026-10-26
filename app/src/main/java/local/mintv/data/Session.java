package local.mintv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import local.mintv.net.BffJson;

/**
 * Token storage for the future authenticated MAXtv session.
 *
 * <p>Milestone 1 only needs a stable, documented place for the login implementation to
 * write its result. {@link local.mintv.net.MinTvApi} implementations are expected to
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
    private static final String KEY_CHANNEL_MAP_ID = "channel_map_id";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_ACCOUNT_DETAILS = "account_details";
    private static final String KEY_ADULT_TOKEN = "adult_token";

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

    /** {@code X-Channel-Map-Id}; empty until the account payload has been parsed. */
    public String channelMapId() {
        return prefs.getString(KEY_CHANNEL_MAP_ID, "");
    }

    /** {@code x-user-id}; empty until the account payload has been parsed. */
    public String userId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    /** Serialized {@code x-account-details} object; empty when the login payload lacked it. */
    public String accountDetails() {
        return prefs.getString(KEY_ACCOUNT_DETAILS, "");
    }

    public String adultToken() {
        return prefs.getString(KEY_ADULT_TOKEN, "");
    }

    public void saveAdultToken(String token) {
        prefs.edit().putString(KEY_ADULT_TOKEN, safe(token)).apply();
    }

    /**
     * Extracts the account headers from a login/authenticate response.
     *
     * <p>The web client keeps the login response as {@code LOGIN.data} and builds
     * {@code x-account-details} from it: {@code account_type}, {@code user_id},
     * {@code is_recording_enabled}, {@code channelMap_id}, {@code rightsGroup_ids},
     * {@code release_slot}, {@code account_id} and an optional {@code vodEnabled} flag from
     * {@code managed_settings}. The object sits at a different depth for single- vs
     * multi-subscription accounts, so it is located by its keys rather than by path.</p>
     */
    public void saveAccount(JSONObject payload) {
        if (payload == null) {
            return;
        }
        JSONObject account = BffJson.find(payload, "channelMap_id", "channelMapId",
                "account_type", "accountType", "account_id", "accountId");
        if (account == null) {
            account = BffJson.find(payload, "user_id", "userId");
        }
        if (account == null) {
            return;
        }

        String channelMap = BffJson.str(account, "channelMap_id", "channelMapId");
        String userId = BffJson.str(account, "user_id", "userId");
        String accountType = BffJson.str(account, "account_type", "accountType");
        boolean recordingDvr = BffJson.bool(account,
                "is_recording_enabled", "recordingEnabledDVR");
        String releaseSlot = BffJson.str(account, "release_slot", "releaseSlot");
        String accountId = BffJson.str(account, "account_id", "accountId");
        JSONArray rights = account.optJSONArray("rightsGroup_ids");
        if (rights == null) {
            rights = account.optJSONArray("rightsGroupIds");
        }
        JSONObject managed = BffJson.obj(account, "managed_settings", "managedSettings");
        String vod = managed == null
                ? "" : BffJson.str(managed, "TVSOA-setting-VodEnabled");

        try {
            JSONObject details = new JSONObject();
            if (!accountType.isEmpty()) {
                details.put("accountType", accountType);
            }
            if (!userId.isEmpty()) {
                details.put("userId", userId);
            }
            details.put("recordingEnabledDVR", recordingDvr);
            if (!channelMap.isEmpty()) {
                details.put("channelMapId", channelMap);
            }
            if (rights != null) {
                details.put("rightsGroupIds", rights);
            }
            if (!releaseSlot.isEmpty()) {
                details.put("releaseSlot", releaseSlot);
            }
            if (!accountId.isEmpty()) {
                details.put("accountId", accountId);
            }
            if (!vod.isEmpty()) {
                details.put("vodEnabled", vod);
            }

            SharedPreferences.Editor editor = prefs.edit();
            if (!channelMap.isEmpty()) {
                editor.putString(KEY_CHANNEL_MAP_ID, channelMap);
            }
            if (!userId.isEmpty()) {
                editor.putString(KEY_USER_ID, userId);
            }
            editor.putString(KEY_ACCOUNT_DETAILS, details.toString());
            String adult = BffJson.deepString(payload, "adultToken", "adult_token");
            if (!adult.isEmpty()) {
                editor.putString(KEY_ADULT_TOKEN, adult);
            }
            editor.apply();
        } catch (JSONException ignored) {
            // All values are strings/arrays we just read; a failure here is not recoverable
            // and must not break login.
        }
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
