package local.mintv.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import local.mintv.R;

/**
 * Milestone 1 placeholder for the real MAXtv authentication.
 *
 * <p>Kept intentionally dumb: no network, no crypto, no token writes. It exists so the
 * login screen, error handling and threading contract can be exercised end to end. Swap
 * the construction in {@code LoginActivity} for the real implementation when the protocol
 * extraction is complete; nothing else changes.</p>
 */
public final class StubMinTvApi implements MinTvApi {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public StubMinTvApi(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void login(String username, String password, LoginCallback callback) {
        String message = appContext.getString(R.string.login_stub_error);
        // Match the async contract of the real implementation: never call back inline.
        mainHandler.postDelayed(
                () -> callback.onFailure(new ApiException(ApiException.NOT_IMPLEMENTED, message)),
                250L);
    }
}
