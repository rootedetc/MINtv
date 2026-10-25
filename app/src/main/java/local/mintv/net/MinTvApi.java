package local.mintv.net;

import local.mintv.data.Session;

/**
 * The single seam between the UI and the MAXtv account protocol.
 *
 * <p><b>Milestone 1 uses {@link StubMinTvApi}</b>, which always reports
 * {@link ApiException#NOT_IMPLEMENTED}. The UI is already written against this interface,
 * so replacing the stub does not require touching {@code LoginActivity}.</p>
 *
 * <h2>Protocol to implement (documented target, not yet wired)</h2>
 * <p>Base URL: {@code https://tv-hr-prod.yo-digital.com/hr-bifrost}</p>
 * <ol>
 *   <li>{@code POST /onboarding/login} with the account identifier. The response carries
 *       the onboarding state / RSA public key or key id needed for step two.</li>
 *   <li>{@code POST /onboarding/authenticate} with the RSA-OAEP encrypted password.
 *       Encryption details to confirm against the real client: RSA-OAEP with SHA-256 and
 *       MGF1-SHA256, Base64 encoding. Never send the plaintext password.</li>
 * </ol>
 *
 * <p>Both calls should reuse the GUEST_USER header set from {@link GuestAuth} (app_key,
 * app_version, device-id, tenant, x-call-type, x-call-time, x-request-session-id,
 * x-request-tracking-id, x-txn-id, x-user-agent) and add the onboarding-specific values
 * the extracted protocol requires.</p>
 *
 * <p>On success the implementation must persist the tokens via
 * {@link Session#save} <em>before</em> calling
 * {@link LoginCallback#onSuccess(Session)}, then hand back that same Session. The callback
 * is delivered on the main thread. Failures should use {@link ApiException#NETWORK},
 * {@link ApiException#AUTH} or {@link ApiException#SERVER} so the UI can show a useful
 * message.</p>
 */
public interface MinTvApi {

    String BASE_URL = "https://tv-hr-prod.yo-digital.com/hr-bifrost";

    /**
     * Authenticates the user.
     *
     * @param username account identifier as entered on the login screen
     * @param password plaintext only inside this process; it must be encrypted before
     *                 leaving the device
     * @param callback invoked exactly once on the main thread
     */
    void login(String username, String password, LoginCallback callback);

    interface LoginCallback {
        void onSuccess(Session session);

        void onFailure(ApiException error);
    }
}
