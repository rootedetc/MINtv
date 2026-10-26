package local.mintv;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
public final class LoginActivity extends BaseActivity {

    /** An IME overlap taller than this is treated as an open keyboard, not a system bar. */
    private static final int IME_SHOW_THRESHOLD_DP = 72;
    /** Breathing room between the compact form and the top of the keyboard. */
    private static final int IME_EDGE_GAP_DP = 12;

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
    private ScrollView scroll;

    private View loginRoot;
    private View brandColumn;
    private View brandDivider;
    private View subtitle;
    private TextView title;
    private View form;
    private View actions;
    private View secure;
    private View usernameLabel;
    private View passwordLabel;

    private int formPaddingTop;
    private int formPaddingBottom;
    private int usernameLabelMarginTop;
    private int passwordLabelMarginTop;
    private int actionsMarginTop;
    private float titleTextSizePx;

    /** The panel is currently in its keyboard-friendly state. */
    private boolean compact;
    /** The XML already starts in the non-compact state; avoid re-applying it on every layout. */
    private boolean compactApplied = true;
    private ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener;
    private final Rect visibleFrame = new Rect();

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
        scroll = findViewById(R.id.login_scroll);

        installImeHandling();

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

    /**
     * Android TV keyboards come in two shapes: a docked IME that resizes the window and
     * reports its height through {@link WindowInsets.Type#ime()}, and a floating IME that
     * leaves the window alone (and can report an IME inset of 0) but still shrinks the
     * visible display frame. Neither source is reliable on its own, so measure both and
     * take the larger overlap. While it is up the panel switches to a compact form that
     * fits above a ~250dp keyboard on the 540dp baseline, and the ScrollView reserves the
     * remaining overlap so the field and the sign-in row can be scrolled clear of it.
     */
    private void installImeHandling() {
        loginRoot = findViewById(R.id.login_root);
        brandColumn = findViewById(R.id.login_brand_column);
        brandDivider = findViewById(R.id.login_brand_divider);
        subtitle = findViewById(R.id.login_subtitle);
        title = findViewById(R.id.login_title);
        form = findViewById(R.id.login_form);
        actions = findViewById(R.id.login_actions);
        secure = findViewById(R.id.login_secure);
        usernameLabel = findViewById(R.id.login_username_label);
        passwordLabel = findViewById(R.id.login_password_label);

        formPaddingTop = form.getPaddingTop();
        formPaddingBottom = form.getPaddingBottom();
        titleTextSizePx = title.getTextSize();
        usernameLabelMarginTop = topMargin(usernameLabel);
        passwordLabelMarginTop = topMargin(passwordLabel);
        actionsMarginTop = topMargin(actions);

        imeLayoutListener = this::measureIme;
        loginRoot.getViewTreeObserver().addOnGlobalLayoutListener(imeLayoutListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loginRoot.setOnApplyWindowInsetsListener((view, insets) -> {
                view.post(this::measureIme);
                return insets;
            });
            loginRoot.requestApplyInsets();
        }

        View.OnFocusChangeListener fieldFocus = (view, hasFocus) -> {
            if (hasFocus) {
                // The keyboard can appear a moment after focus, and its height changes
                // once it is fully laid out. Re-measure at both points.
                view.post(this::measureIme);
                view.postDelayed(this::measureIme, 240L);
                view.postDelayed(this::measureIme, 650L);
            }
        };
        usernameInput.setOnFocusChangeListener(fieldFocus);
        passwordInput.setOnFocusChangeListener(fieldFocus);
        loginRoot.post(this::measureIme);
    }

    private static int topMargin(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).topMargin : 0;
    }

    /**
     * Measures how much of this window the IME currently covers. The visible display frame
     * (API 1) catches floating IMEs; the window bottom against the display catches a
     * resized window; the API 30 IME inset is the exact answer for docked/inset-aware IMEs.
     */
    private void measureIme() {
        if (loginRoot == null || scroll == null || isFinishing() || isDestroyed()
                || loginRoot.getWindowToken() == null) {
            return;
        }
        int rootHeight = loginRoot.getHeight();
        if (rootHeight <= 0) {
            return;
        }

        int[] location = new int[2];
        loginRoot.getLocationOnScreen(location);
        int windowBottom = location[1] + rootHeight;
        loginRoot.getWindowVisibleDisplayFrame(visibleFrame);

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int byFrame = Math.max(0, windowBottom - visibleFrame.bottom);
        int byWindow = Math.max(0, screenHeight - windowBottom);
        int byInsets = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = loginRoot.getRootWindowInsets();
            if (insets != null) {
                byInsets = insets.getInsets(WindowInsets.Type.ime()).bottom;
            }
        }

        int keyboard = Math.max(byFrame, Math.max(byWindow, byInsets));
        keyboard = Math.min(keyboard, screenHeight);
        boolean shown = keyboard > Tv.dp(this, IME_SHOW_THRESHOLD_DP);

        // A docked IME has already taken the space out of this window; only the part that
        // still overlaps the (now shorter) ScrollView needs bottom padding.
        int windowShrink = Math.max(0, screenHeight - windowBottom);
        int overlap = shown
                ? Math.max(0, keyboard - Math.min(keyboard, windowShrink))
                : 0;
        applyImeState(shown, overlap);
    }

    private void applyImeState(boolean shown, int overlapPx) {
        boolean wasCompact = compact;
        setCompact(shown);

        int previousPadding = scroll.getPaddingBottom();
        int padding = shown && overlapPx > 0
                ? overlapPx + Tv.dp(this, IME_EDGE_GAP_DP)
                : 0;
        if (previousPadding != padding) {
            scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                    scroll.getPaddingRight(), padding);
        }

        if (shown) {
            // The layout pass that follows the padding/visibility change will ask for the
            // scroll again; this post covers the first frame where the IME is still moving.
            scroll.post(this::scrollIntoImeView);
        } else if (wasCompact || previousPadding > 0) {
            scroll.post(() -> {
                if (!compact) {
                    scroll.scrollTo(0, 0);
                }
            });
        }
    }

    /**
     * The compact state: drop everything that is not needed while typing and shrink the
     * remaining gaps so title + labels + two fields + actions are about 270dp tall.
     * The XML values are captured in {@link #installImeHandling()} so the restore is exact.
     */
    private void setCompact(boolean value) {
        if (compactApplied && compact == value) {
            return;
        }
        compact = value;
        compactApplied = true;

        int visibility = value ? View.GONE : View.VISIBLE;
        brandColumn.setVisibility(visibility);
        brandDivider.setVisibility(visibility);
        subtitle.setVisibility(visibility);
        rememberPassword.setVisibility(visibility);
        secure.setVisibility(visibility);
        loginStatus.setVisibility(visibility);

        if (value) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
            form.setPadding(form.getPaddingLeft(), Tv.dp(this, 12),
                    form.getPaddingRight(), Tv.dp(this, 12));
            setTopMargin(usernameLabel, Tv.dp(this, 8));
            setTopMargin(passwordLabel, Tv.dp(this, 6));
            setTopMargin(actions, Tv.dp(this, 8));
        } else {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleTextSizePx);
            form.setPadding(form.getPaddingLeft(), formPaddingTop,
                    form.getPaddingRight(), formPaddingBottom);
            setTopMargin(usernameLabel, usernameLabelMarginTop);
            setTopMargin(passwordLabel, passwordLabelMarginTop);
            setTopMargin(actions, actionsMarginTop);
        }
    }

    private static void setTopMargin(View view, int px) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).topMargin = px;
            view.setLayoutParams(params);
        }
    }

    /**
     * Scrolls title-through-actions (or the focused field plus actions when the title
     * cannot fit) above the reserved keyboard band. Coordinates are taken relative to the
     * ScrollView, so the focused field is never moved when it is already visible.
     */
    private void scrollIntoImeView() {
        if (!compact || scroll == null || scroll.getHeight() <= 0
                || isFinishing() || isDestroyed()) {
            return;
        }
        int limit = scroll.getHeight() - scroll.getPaddingBottom();
        int topLimit = scroll.getPaddingTop() + Tv.dp(this, 8);
        if (limit <= topLimit) {
            return;
        }

        Rect actionsRect = rectInScroll(actions);
        if (actionsRect == null) {
            return;
        }
        View field = usernameInput.hasFocus() ? usernameInput
                : passwordInput.hasFocus() ? passwordInput : null;
        Rect fieldRect = rectInScroll(field);
        Rect titleRect = rectInScroll(title);

        int bottom = actionsRect.bottom;
        if (fieldRect != null) {
            bottom = Math.max(bottom, fieldRect.bottom);
        }
        int delta = bottom - limit;
        if (delta <= 0) {
            // The focused field and the sign-in row are already fully visible.
            return;
        }

        boolean blockFits = titleRect != null
                && actionsRect.bottom - titleRect.top <= limit - topLimit;
        if (blockFits) {
            // Move the compact panel as one unit, keeping the title on screen.
            delta = Math.min(delta, Math.max(0, titleRect.top - topLimit));
        } else if (fieldRect != null) {
            // A very tall keyboard wins: keep the caret visible rather than pushing the
            // field off the top, even if the action row is then partially covered.
            delta = Math.min(delta, Math.max(0, fieldRect.top - topLimit));
        }
        if (delta > 0) {
            scroll.scrollBy(0, delta);
        }
    }

    private Rect rectInScroll(View view) {
        if (view == null || !isInsideScroll(view)) {
            return null;
        }
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private boolean isInsideScroll(View view) {
        if (view == null) {
            return false;
        }
        for (ViewParent parent = view.getParent(); parent != null;
                parent = parent.getParent()) {
            if (parent == scroll) {
                return true;
            }
        }
        return false;
    }

    /**
     * A validation message adds a line under the actions. Keep it above the sheet's
     * bottom edge (and above the keyboard when one is reserved) instead of letting the
     * panel grow past the visible area.
     */
    private void keepStatusVisible() {
        if (scroll == null) {
            return;
        }
        loginStatus.post(() -> {
            if (isFinishing() || !isInsideScroll(loginStatus)
                    || loginStatus.getVisibility() != View.VISIBLE) {
                return;
            }
            Rect rect = new Rect();
            loginStatus.getDrawingRect(rect);
            scroll.offsetDescendantRectToMyCoords(loginStatus, rect);
            int limit = scroll.getHeight() - scroll.getPaddingBottom() - Tv.dp(this, 12);
            int delta = rect.bottom - limit;
            if (delta > 0) {
                scroll.scrollBy(0, delta);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (loginRoot != null) {
            // Re-measure on both edges: some TV IMEs drop their inset only after the
            // activity loses focus, and the keyboard is already gone when we regain it.
            loginRoot.post(this::measureIme);
            loginRoot.postDelayed(this::measureIme, 200L);
        }
    }

    @Override
    protected void onDestroy() {
        if (loginRoot != null && imeLayoutListener != null) {
            ViewTreeObserver observer = loginRoot.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(imeLayoutListener);
            }
            imeLayoutListener = null;
        }
        super.onDestroy();
    }

    private void signIn() {
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            // Close the keyboard first so the compact form restores and the error line is
            // actually on screen; the field keeps focus for the next edit.
            hideKeyboard();
            loginStatus.setTextColor(getColor(R.color.danger));
            loginStatus.setText(R.string.login_missing_fields);
            keepStatusVisible();
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
                keepStatusVisible();
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
