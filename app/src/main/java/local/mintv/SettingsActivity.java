package local.mintv;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ChannelSubscriptions;
import local.mintv.data.CredentialStore;
import local.mintv.data.LocalePreference;
import local.mintv.data.Session;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgRepository;
import local.mintv.player.QualityPreference;
import local.mintv.ui.Shell;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/**
 * Settings as a two-pane TV screen: destinations on the left, one quiet sheet on the
 * right. Everything shown is wired: account, quality, cache and what this app is.
 */
public final class SettingsActivity extends BaseActivity {

    private static final int SECTION_ACCOUNT = 0;
    private static final int SECTION_LANGUAGE = 1;
    private static final int SECTION_PLAYBACK = 2;
    private static final int SECTION_GUIDE = 3;
    private static final int SECTION_ABOUT = 4;

    private static final String STATE_SECTION = "settings_section";

    /** 236dp rail + 24dp gap + 540dp sheet = 800dp, the body width at 960dp. */
    private static final int SETTINGS_GAP_DP = 24;

    /**
     * Inset that gives the focus lift room to draw. Category rows scale 1.02 (2.36dp
     * per side), so 6dp is enough; option rows are inside the ScrollView and get 10dp
     * because the ScrollView has to keep clipping vertically.
     */
    private static final int CATEGORY_INSET_DP = 6;
    private static final int SHEET_INSET_DP = 10;

    private TvRail rail;
    private LinearLayout categoryList;
    private final List<CategoryRow> categories = new ArrayList<>();
    private LinearLayout contentHost;
    private int section = SECTION_ACCOUNT;
    private String version = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        version = readVersion();
        setContentView(buildUi());
        if (savedInstanceState != null) {
            section = savedInstanceState.getInt(STATE_SECTION, SECTION_ACCOUNT);
        }
        if (getIntent() != null && Shell.SECTION_ACCOUNT.equals(
                getIntent().getStringExtra(Shell.EXTRA_SECTION))) {
            section = SECTION_ACCOUNT;
        }
        showSection(section);
        categories.get(section).post(() -> categories.get(section).requestFocus());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SECTION, section);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccountFoot();
        if (section == SECTION_ACCOUNT) {
            showSection(SECTION_ACCOUNT);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Tv.BG);

        rail = new TvRail(this);
        rail.setActive(TvRail.Tab.SETTINGS);
        rail.setListener(new TvRail.Listener() {
            @Override
            public void onTabSelected(TvRail.Tab tab) {
                Shell.open(SettingsActivity.this, tab);
            }

            @Override
            public void onAccount() {
                showSection(SECTION_ACCOUNT);
                categories.get(SECTION_ACCOUNT).post(
                        () -> categories.get(SECTION_ACCOUNT).requestFocus());
            }
        });
        root.addView(rail, new LinearLayout.LayoutParams(
                Tv.dp(this, 96), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        main.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 88)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        // A focused row scales past its own bounds. Let that overflow travel into the
        // body's 32dp padding instead of being cut at the padded edge, so the 2dp white
        // rule is never cropped by the first parent that clips.
        body.setClipChildren(false);
        body.setClipToPadding(false);
        Tv.pad(body, 32, 4, 32, 24);
        main.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.VERTICAL);
        categoryList.setClipChildren(false);
        categoryList.setClipToPadding(false);
        // Safe inset for the 1.02 category lift (236dp * 0.02 / 2 = 2.36dp per side).
        Tv.pad(categoryList, CATEGORY_INSET_DP, CATEGORY_INSET_DP,
                CATEGORY_INSET_DP, 0);
        body.addView(categoryList, new LinearLayout.LayoutParams(
                Tv.dp(this, 236), ViewGroup.LayoutParams.MATCH_PARENT));

        CategoryRow account = new CategoryRow(SECTION_ACCOUNT, R.drawable.ic_account,
                getString(R.string.settings_account), getString(R.string.settings_account_hint));
        CategoryRow language = new CategoryRow(SECTION_LANGUAGE, R.drawable.ic_language,
                getString(R.string.settings_language), getString(R.string.settings_language_hint));
        CategoryRow playback = new CategoryRow(SECTION_PLAYBACK, R.drawable.ic_tv,
                getString(R.string.settings_playback), getString(R.string.settings_playback_hint));
        CategoryRow guide = new CategoryRow(SECTION_GUIDE, R.drawable.ic_guide,
                getString(R.string.settings_guide), getString(R.string.settings_guide_hint));
        CategoryRow about = new CategoryRow(SECTION_ABOUT, R.drawable.ic_info,
                getString(R.string.settings_about), getString(R.string.settings_about_hint));
        categories.add(account);
        categories.add(language);
        categories.add(playback);
        categories.add(guide);
        categories.add(about);
        for (CategoryRow row : categories) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 64));
            lp.bottomMargin = Tv.dp(this, 6);
            categoryList.addView(row, lp);
        }

        // 236dp category rail + 24dp gap + the sheet filling the rest. At rest that is
        // 540dp; when the source rail widens, the sheet shrinks with the body instead of
        // pushing its right edge (and focus rings) off screen.
        View gap = new View(this);
        body.addView(gap, new LinearLayout.LayoutParams(
                Tv.dp(this, SETTINGS_GAP_DP), 1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        // Vertical inset: the sheet still clips while scrolling (it must), but at the
        // end of the scroll the last row keeps 8dp of room for its 1.015 lift.
        Tv.pad(scroll, 0, SHEET_INSET_DP, 0, SHEET_INSET_DP);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setClipChildren(false);
        contentHost.setClipToPadding(false);
        // Horizontal inset: the ScrollView cannot have clipChildren=false without
        // painting over the header, so the row lift has to happen inside its bounds.
        // 10dp per side leaves ~6dp after a 520dp option row scales to 1.015.
        Tv.pad(contentHost, SHEET_INSET_DP, 0, SHEET_INSET_DP, 16);
        scroll.addView(contentHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(header, 32, 0, 32, 0);

        TextView title = Tv.displayText(this, 32f, Tv.TEXT);
        title.setText(R.string.settings_title);
        title.setLetterSpacing(0.01f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private void showSection(int index) {
        section = index;
        for (CategoryRow row : categories) {
            row.setActive(row.section == index);
        }
        contentHost.removeAllViews();
        switch (index) {
            case SECTION_LANGUAGE:
                buildLanguage();
                break;
            case SECTION_PLAYBACK:
                buildPlayback();
                break;
            case SECTION_GUIDE:
                buildGuide();
                break;
            case SECTION_ABOUT:
                buildAbout();
                break;
            default:
                buildAccount();
                break;
        }
        updateAccountFoot();
    }

    // ------------------------------------------------------------------ account

    private void buildAccount() {
        contentHost.addView(sectionTitle(getString(R.string.settings_account_section),
                getString(R.string.settings_account_body)));

        Session session = Session.get(this);
        boolean signedIn = session.isLoggedIn();
        String name = session.displayName();
        if (TextUtils.isEmpty(name)) {
            name = session.username();
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_panel);
        Tv.pad(card, 20, 20, 20, 20);

        ImageView avatar = Tv.icon(this, R.drawable.ic_account,
                signedIn ? Tv.BRAND_BRIGHT : Tv.TEXT_3, 26f);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setPadding(Tv.dp(this, 8), Tv.dp(this, 8),
                Tv.dp(this, 8), Tv.dp(this, 8));
        card.addView(avatar, new LinearLayout.LayoutParams(
                Tv.dp(this, 54), Tv.dp(this, 54)));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView state = Tv.displayText(this, 22f, Tv.TEXT);
        state.setText(signedIn ? getString(R.string.settings_account_signed_in)
                : getString(R.string.settings_account_signed_out));
        identity.addView(state);
        TextView detail = Tv.text(this, 13f, Tv.TEXT_2, false);
        detail.setText(signedIn && !TextUtils.isEmpty(name)
                ? getString(R.string.settings_account_signed_in_as, name)
                : getString(R.string.settings_account_signed_out_as));
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = Tv.dp(this, 4);
        identity.addView(detail, detailLp);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        identityLp.leftMargin = Tv.dp(this, 14);
        card.addView(identity, identityLp);

        if (!signedIn) {
            TextView signIn = Tv.button(this, getString(R.string.settings_account_sign_in), true);
            signIn.setOnClickListener(view -> startActivity(
                    new Intent(this, LoginActivity.class)));
            card.addView(signIn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 48)));
        }
        contentHost.addView(card, topMargin(16));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (signedIn) {
            TextView signOut = Tv.ghost(this, getString(R.string.settings_account_sign_out));
            signOut.setOnClickListener(view -> confirmSignOut());
            actions.addView(signOut, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46)));
        }
        if (!TextUtils.isEmpty(CredentialStore.username(this))) {
            TextView forget = Tv.ghost(this, getString(R.string.settings_forget_password));
            LinearLayout.LayoutParams forgetLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
            forgetLp.leftMargin = Tv.dp(this, 10);
            forget.setOnClickListener(view -> {
                CredentialStore.clear(this);
                showSection(SECTION_ACCOUNT);
                Toast.makeText(this, R.string.settings_password_forgotten,
                        Toast.LENGTH_SHORT).show();
            });
            actions.addView(forget, forgetLp);
        }
        if (actions.getChildCount() > 0) {
            contentHost.addView(actions, topMargin(14));
        }

        if (signedIn) {
            LinearLayout library = new LinearLayout(this);
            library.setOrientation(LinearLayout.HORIZONTAL);
            TextView watchlist = Tv.ghost(this, getString(R.string.watchlist_title));
            watchlist.setOnClickListener(view -> startActivity(
                    new Intent(this, WatchlistActivity.class)));
            library.addView(watchlist, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46)));
            TextView continueWatching = Tv.ghost(this, getString(R.string.cw_title));
            LinearLayout.LayoutParams cwLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
            cwLp.leftMargin = Tv.dp(this, 10);
            continueWatching.setOnClickListener(view -> startActivity(
                    new Intent(this, ContinueWatchingActivity.class)));
            library.addView(continueWatching, cwLp);
            contentHost.addView(library, topMargin(10));
        }

    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_sign_out_title)
                .setMessage(R.string.settings_sign_out_body)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.settings_sign_out_confirm, (dialog, which) -> {
                    Session.get(this).clear();
                    ChannelSubscriptions.clear();
                    showSection(SECTION_ACCOUNT);
                    Toast.makeText(this, R.string.settings_signed_out_toast,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ------------------------------------------------------------------ language

    private void buildLanguage() {
        contentHost.addView(sectionTitle(getString(R.string.settings_language_section),
                getString(R.string.settings_language_body)));

        TextView label = Tv.label(this, getString(R.string.settings_language_option), Tv.TEXT_3);
        contentHost.addView(label, topMargin(18));

        final String[] tags = {LocalePreference.HR, LocalePreference.EN};
        final int[] titles = {R.string.language_croatian, R.string.language_english};
        final int[] bodies = {R.string.language_croatian_body, R.string.language_english_body};
        final OptionRow[] holders = new OptionRow[tags.length];
        String selected = LocalePreference.get(this);
        for (int i = 0; i < tags.length; i++) {
            final String tag = tags[i];
            OptionRow row = new OptionRow(getString(titles[i]), getString(bodies[i]),
                    tag.equals(selected));
            row.setOnClickListener(view -> selectLanguage(tag, tags, holders));
            holders[i] = row;
            contentHost.addView(row, topMargin(10));
        }

        TextView note = Tv.text(this, 13f, Tv.TEXT_3, false);
        note.setText(R.string.settings_language_note);
        note.setLineSpacing(Tv.dp(this, 2), 1f);
        contentHost.addView(note, topMargin(18));
    }

    private void selectLanguage(String tag, String[] tags, OptionRow[] holders) {
        if (tag.equals(LocalePreference.get(this))) {
            return;
        }
        LocalePreference.set(this, tag);
        for (int i = 0; i < holders.length; i++) {
            holders[i].setSelected(tags[i].equals(tag));
        }
        // Rebuild this screen so every label switches immediately. The new instance
        // wraps its context with the new preference; BaseActivity does not loop.
        recreate();
    }

    // ------------------------------------------------------------------ playback

    private void buildPlayback() {
        contentHost.addView(sectionTitle(getString(R.string.settings_playback_section),
                getString(R.string.settings_playback_body)));

        TextView label = Tv.label(this, getString(R.string.settings_playback_quality), Tv.TEXT_3);
        contentHost.addView(label, topMargin(18));

        final QualityPreference.Quality[] values = {
                QualityPreference.Quality.AUTO,
                QualityPreference.Quality.MAX,
                QualityPreference.Quality.SAVER
        };
        final OptionRow[] holders = new OptionRow[3];
        QualityPreference.Quality selected = QualityPreference.get(this);
        for (int i = 0; i < values.length; i++) {
            final QualityPreference.Quality value = values[i];
            int title;
            int body;
            if (value == QualityPreference.Quality.MAX) {
                title = R.string.quality_max_title;
                body = R.string.quality_max_body;
            } else if (value == QualityPreference.Quality.SAVER) {
                title = R.string.quality_saver_title;
                body = R.string.quality_saver_body;
            } else {
                title = R.string.quality_auto_title;
                body = R.string.quality_auto_body;
            }
            OptionRow row = new OptionRow(getString(title), getString(body),
                    value == selected);
            row.setOnClickListener(view -> {
                QualityPreference.set(this, value);
                QualityPreference.Quality now = QualityPreference.get(this);
                for (int j = 0; j < holders.length; j++) {
                    holders[j].setSelected(values[j] == now);
                }
                Toast.makeText(this, R.string.settings_quality_applied,
                        Toast.LENGTH_SHORT).show();
            });
            holders[i] = row;
            contentHost.addView(row, topMargin(10));
        }

        TextView note = Tv.text(this, 13f, Tv.TEXT_3, false);
        note.setText(R.string.settings_playback_note);
        contentHost.addView(note, topMargin(18));
    }

    // ------------------------------------------------------------------ guide

    private void buildGuide() {
        contentHost.addView(sectionTitle(getString(R.string.settings_guide_section),
                getString(R.string.settings_guide_body)));

        RowInfo day = new RowInfo(getString(R.string.settings_guide_day),
                getString(R.string.settings_guide_current_day));
        contentHost.addView(day, topMargin(16));

        RowInfo stored = new RowInfo(getString(R.string.settings_guide_status),
                getString(R.string.settings_guide_stored));
        contentHost.addView(stored, topMargin(8));

        TextView refreshGuide = Tv.ghost(this,
                getString(R.string.settings_guide_refresh));
        refreshGuide.setOnClickListener(view -> refreshGuide(refreshGuide));
        contentHost.addView(refreshGuide, topMargin(18));

        TextView refreshChannels = Tv.ghost(this, getString(R.string.settings_cache_channels));
        refreshChannels.setOnClickListener(view -> refreshChannels(refreshChannels));
        contentHost.addView(refreshChannels, topMargin(10));

        TextView note = Tv.text(this, 13f, Tv.TEXT_3, false);
        note.setText(R.string.settings_cache_note);
        note.setLineSpacing(Tv.dp(this, 2), 1f);
        contentHost.addView(note, topMargin(18));
    }

    private void refreshGuide(TextView button) {
        button.setEnabled(false);
        button.setText(R.string.settings_guide_refreshing);
        new EpgRepository(this).refresh(new EpgRepository.Callback() {
            @Override
            public void onLoaded(List<EpgChannel> channels) {
                button.setEnabled(true);
                button.setText(R.string.settings_guide_refresh);
                Toast.makeText(SettingsActivity.this, R.string.settings_guide_refreshed,
                        Toast.LENGTH_SHORT).show();
            }
        }, message -> {
            button.setEnabled(true);
            button.setText(R.string.settings_guide_refresh);
            Toast.makeText(SettingsActivity.this, R.string.settings_guide_failed,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshChannels(TextView button) {
        button.setEnabled(false);
        button.setText(R.string.settings_refresh_started);
        ChannelRepository.get(this).load(true, new ChannelRepository.Listener() {
            @Override
            public void onChannels(List<Channel> channels, boolean fromCache) {
                button.setEnabled(true);
                button.setText(R.string.settings_cache_channels);
                Toast.makeText(SettingsActivity.this, R.string.settings_refresh_done,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message, Throwable cause) {
                button.setEnabled(true);
                button.setText(R.string.settings_cache_channels);
                Toast.makeText(SettingsActivity.this, R.string.settings_refresh_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ about

    private void buildAbout() {
        contentHost.addView(Tv.brand(this, 38f));
        TextView versionLabel = Tv.text(this, 12f, Tv.TEXT_3, false);
        versionLabel.setText(getString(R.string.settings_about_version, version));
        contentHost.addView(versionLabel, topMargin(8));

        TextView body = Tv.text(this, 14f, Tv.TEXT_2, false);
        body.setText(R.string.settings_about_body);
        body.setLineSpacing(Tv.dp(this, 2), 1f);
        contentHost.addView(body, topMargin(16));

        contentHost.addView(infoRow(getString(R.string.settings_about_privacy_label),
                getString(R.string.settings_about_privacy_value)), topMargin(22));
        contentHost.addView(infoRow(getString(R.string.settings_about_vpn_label),
                getString(R.string.settings_about_vpn_value)), topMargin(1));
        contentHost.addView(infoRow(getString(R.string.settings_about_data_label),
                getString(R.string.settings_about_data_value)), topMargin(1));

        TextView privacy = Tv.text(this, 12.5f, Tv.TEXT_3, false);
        privacy.setText(R.string.settings_about_privacy_body);
        privacy.setLineSpacing(Tv.dp(this, 2), 1f);
        contentHost.addView(privacy, topMargin(18));

        TextView legal = Tv.text(this, 11.5f, Tv.TEXT_3, false);
        legal.setText(R.string.settings_about_legal);
        contentHost.addView(legal, topMargin(18));
    }

    private View sectionTitle(String title, String body) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Tv.displayText(this, 27f, Tv.TEXT);
        heading.setText(title);
        block.addView(heading);
        TextView sub = Tv.text(this, 13f, Tv.TEXT_2, false);
        sub.setText(body);
        sub.setLineSpacing(Tv.dp(this, 2), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Tv.dp(this, 6);
        block.addView(sub, subLp);
        return block;
    }

    private View infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Tv.BG_RAISED);
        Tv.pad(row, 16, 12, 16, 12);
        TextView left = Tv.label(this, label, Tv.TEXT_3);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView right = Tv.displayText(this, 15f, Tv.TEXT);
        right.setText(value);
        right.setGravity(Gravity.END);
        row.addView(right);
        return row;
    }

    private void updateAccountFoot() {
        Session session = Session.get(this);
        if (session.isLoggedIn()) {
            String name = session.displayName();
            if (TextUtils.isEmpty(name)) {
                name = session.username();
            }
            rail.setAccount(TextUtils.isEmpty(name) ? getString(R.string.nav_settings) : name,
                    true);
        } else {
            rail.setAccount(getString(R.string.action_sign_in), false);
        }
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Tv.dp(this, dp);
        return lp;
    }

    /**
     * The default focus scroll only guarantees the row's layout bounds, which parks the
     * 1.015 lift exactly on the ScrollView's clip line. Asking for an 8dp margin keeps
     * the whole white rule and the lift visible at the top and bottom of the sheet.
     */
    private void keepRowVisible(View row) {
        row.post(() -> {
            if (!row.isFocused() || row.getWidth() == 0) {
                return;
            }
            int margin = Tv.dp(this, 8);
            Rect rect = new Rect(0, 0, row.getWidth(), row.getHeight());
            rect.inset(-margin, -margin);
            row.requestRectangleOnScreen(rect, false);
        });
    }

    private String readVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "—" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "—";
        }
    }

    // ------------------------------------------------------------------ rows

    private final class CategoryRow extends LinearLayout {

        final int section;
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;
        private final View tally;

        CategoryRow(int section, int iconRes, String text, String sub) {
            super(SettingsActivity.this);
            this.section = section;
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setBackgroundResource(R.drawable.bg_settings_row);
            Tv.pad(this, 12, 0, 12, 0);

            tally = new View(SettingsActivity.this);
            tally.setBackgroundColor(Tv.BRAND);
            addView(tally, new LinearLayout.LayoutParams(
                    Math.max(2, Tv.dp(SettingsActivity.this, 3)), Tv.dp(SettingsActivity.this, 34)));

            icon = Tv.icon(SettingsActivity.this, iconRes, Tv.TEXT_3, 22f);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    Tv.dp(SettingsActivity.this, 22), Tv.dp(SettingsActivity.this, 22));
            iconLp.leftMargin = Tv.dp(SettingsActivity.this, 12);
            iconLp.rightMargin = Tv.dp(SettingsActivity.this, 12);
            addView(icon, iconLp);

            LinearLayout texts = new LinearLayout(SettingsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = Tv.displayText(SettingsActivity.this, 16f, Tv.TEXT);
            title.setText(text);
            texts.addView(title);
            subtitle = Tv.text(SettingsActivity.this, 10.5f, Tv.TEXT_3, false);
            subtitle.setText(sub);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Tv.dp(SettingsActivity.this, 2);
            texts.addView(subtitle, subLp);
            addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            setOnClickListener(view -> showSection(section));
            setOnFocusChangeListener((view, hasFocus) -> {
                view.animate().cancel();
                view.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                        .setDuration(hasFocus ? 140L : 110L).start();
                paint(hasFocus);
            });
        }

        void setActive(boolean active) {
            setSelected(active);
            tally.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            paint(isFocused());
        }

        private void paint(boolean focused) {
            int color = focused ? Tv.TEXT : isSelected() ? Tv.BRAND_BRIGHT : Tv.TEXT_3;
            icon.setColorFilter(color);
            title.setTextColor(focused ? Tv.TEXT : isSelected() ? Tv.BRAND_BRIGHT : Tv.TEXT);
            subtitle.setTextColor(isSelected() ? Tv.TEXT_2 : Tv.TEXT_3);
        }
    }

    private final class OptionRow extends LinearLayout {

        private final View radio;
        private final TextView title;
        private final TextView body;

        OptionRow(String titleText, String bodyText, boolean selected) {
            super(SettingsActivity.this);
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setBackgroundResource(R.drawable.bg_option_row);
            Tv.pad(this, 14, 12, 16, 12);
            setSelected(selected);
            Tv.focusScale(this, 1.015f);

            radio = new View(SettingsActivity.this);
            radio.setBackground(Tv.box(SettingsActivity.this,
                    selected ? Tv.BRAND : 0x00000000, 99f,
                    selected ? Tv.BRAND : Tv.TEXT_3, 2f));
            addView(radio, new LinearLayout.LayoutParams(
                    Tv.dp(SettingsActivity.this, 18), Tv.dp(SettingsActivity.this, 18)));

            LinearLayout texts = new LinearLayout(SettingsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = Tv.displayText(SettingsActivity.this, 18f, Tv.TEXT);
            title.setText(titleText);
            texts.addView(title);
            body = Tv.text(SettingsActivity.this, 12.5f, Tv.TEXT_2, false);
            body.setText(bodyText);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = Tv.dp(SettingsActivity.this, 3);
            texts.addView(body, bodyLp);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.leftMargin = Tv.dp(SettingsActivity.this, 14);
            addView(texts, textsLp);

            setOnFocusChangeListener((view, hasFocus) -> {
                view.animate().cancel();
                view.animate().scaleX(hasFocus ? 1.015f : 1f)
                        .scaleY(hasFocus ? 1.015f : 1f)
                        .setDuration(hasFocus ? 140L : 110L).start();
                title.setTextColor(Tv.TEXT);
                if (hasFocus) {
                    keepRowVisible(this);
                }
            });
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            if (radio != null) {
                radio.setBackground(Tv.box(SettingsActivity.this,
                        selected ? Tv.BRAND : 0x00000000, 99f,
                        selected ? Tv.BRAND : Tv.TEXT_3, 2f));
            }
        }
    }

    private final class RowInfo extends LinearLayout {
        RowInfo(String label, String value) {
            super(SettingsActivity.this);
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundColor(Tv.BG_RAISED);
            Tv.pad(this, 16, 12, 16, 12);
            TextView left = Tv.label(SettingsActivity.this, label, Tv.TEXT_3);
            addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView right = Tv.displayText(SettingsActivity.this, 15f, Tv.TEXT);
            right.setText(value);
            right.setGravity(Gravity.END);
            addView(right);
        }
    }
}
