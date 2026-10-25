package local.mintv;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import local.mintv.data.CredentialStore;
import local.mintv.data.Session;
import local.mintv.net.ApiException;
import local.mintv.net.MinTvApi;
import local.mintv.net.HttpMinTvApi;
import local.mintv.ui.Tv;

/**
 * Login screen. The only backend dependency is {@link MinTvApi}; milestone 1 injects
 * {@link HttpMinTvApi}. To connect the real protocol, replace the single field assignment
 * in {@link #onCreate(Bundle)} - no layout or callback changes are required.
 */
public final class LoginActivity extends Activity {

    /**
     * The real implementation will be constructed here, e.g.
     * {@code new HttpMinTvApi(this, Http.client())}.
     */
    private MinTvApi api;

    private EditText usernameInput;
    private EditText passwordInput;
    private CompoundButton rememberPassword;
    private Button loginButton;
    private ProgressBar loginProgress;
    private TextView loginStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        api = new HttpMinTvApi(this);

        usernameInput = findViewById(R.id.input_username);
        passwordInput = findViewById(R.id.input_password);
        rememberPassword = findViewById(R.id.checkbox_remember);
        loginButton = findViewById(R.id.button_login);
        loginProgress = findViewById(R.id.login_progress);
        loginStatus = findViewById(R.id.login_status);

        TextView skip = findViewById(R.id.button_skip);
        skip.setOnClickListener(view -> finish());
        Tv.focusScale(skip, 1.03f);

        String savedUser = CredentialStore.username(this);
        String savedPassword = CredentialStore.password(this);
        usernameInput.setText(savedUser);
        if (!savedPassword.isEmpty()) {
            passwordInput.setText(savedPassword);
            rememberPassword.setChecked(true);
        }

        loginButton.setOnClickListener(view -> signIn());
        passwordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                signIn();
                return true;
            }
            return false;
        });
        rememberPassword.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                // Removing the opt-in must remove the stored secret immediately.
                CredentialStore.clear(this);
            }
        });

        if (savedUser.isEmpty()) {
            usernameInput.requestFocus();
        } else if (savedPassword.isEmpty()) {
            passwordInput.requestFocus();
        } else {
            loginButton.requestFocus();
        }
    }

    private void signIn() {
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            loginStatus.setTextColor(getColor(R.color.danger));
            loginStatus.setText(R.string.login_missing_fields);
            (username.isEmpty() ? usernameInput : passwordInput).requestFocus();
            return;
        }

        hideKeyboard();
        setBusy(true);
        loginStatus.setTextColor(getColor(R.color.text_tertiary));
        loginStatus.setText(R.string.login_in_progress);

        final AtomicBoolean delivered = new AtomicBoolean(false);
        api.login(username, password, new MinTvApi.LoginCallback() {
            @Override
            public void onSuccess(Session session) {
                if (!delivered.compareAndSet(false, true) || isFinishing()) {
                    return;
                }
                if (rememberPassword.isChecked()) {
                    // CredentialStore encrypts with an Android Keystore key; no plaintext
                    // password ever reaches SharedPreferences.
                    CredentialStore.remember(LoginActivity.this, username, password);
                } else {
                    CredentialStore.clear(LoginActivity.this);
                }
                setBusy(false);
                loginStatus.setTextColor(getColor(R.color.success));
                loginStatus.setText(R.string.login_success);
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onFailure(ApiException error) {
                if (!delivered.compareAndSet(false, true) || isFinishing()) {
                    return;
                }
                setBusy(false);
                loginStatus.setTextColor(getColor(R.color.danger));
                loginStatus.setText(TextUtils.isEmpty(error.getMessage())
                        ? getString(R.string.login_stub_error)
                        : error.getMessage());
                if (error.code() == ApiException.AUTH) {
                    passwordInput.requestFocus();
                    passwordInput.selectAll();
                } else {
                    loginButton.requestFocus();
                }
            }
        });
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        usernameInput.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
        rememberPassword.setEnabled(!busy);
        loginProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
        }
    }
}
