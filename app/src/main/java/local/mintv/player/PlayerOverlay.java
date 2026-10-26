package local.mintv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgProgram;
import local.mintv.ui.Tv;

/**
 * Picture-first player chrome: a quiet channel identity at the top-left, one transport plate
 * at the bottom, a compact channel switcher and a playback settings sheet.
 *
 * <p>Nothing shows a quality <em>preference</em>: both the identity state row and the STREAM
 * readout in the transport plate show the resolution the decoder is actually producing.
 * Quality, subtitles and audio language are changed in the settings sheet (DOWN), never by
 * re-tuning the stream.</p>
 */
public final class PlayerOverlay {

    /** One selectable audio or subtitle track. {@code id} is stable for the current media. */
    public static final class TrackOption {
        public final String id;
        public final String label;
        public final boolean selected;

        public TrackOption(String id, String label) {
            this(id, label, false);
        }

        public TrackOption(String id, String label, boolean selected) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.selected = selected;
        }
    }

    public interface Callback {
        void onZap(int delta);

        void onChannelPicked(Channel channel);

        void onPlayPause();

        void onQualityPicked(QualityPreference.Quality quality);

        /** Empty id means "leave audio as the stream default". */
        void onAudioTrackPicked(String optionId);

        /** Empty id means subtitles off. */
        void onSubtitleTrackPicked(String optionId);
    }

    private static final long AUTO_HIDE_MS = 4_000L;
    private static final long FADE_MS = 150L;
    private static final int SWITCHER_ROW_HEIGHT_DP = 44;
    private static final int SWITCHER_MAX_ROWS = 5;
    private static final int SWITCHER_WIDTH_DP = 360;
    private static final int SETTINGS_WIDTH_DP = 420;
    private static final int PANEL_BOTTOM_MARGIN_DP = 160;
    private static final int SETTINGS_QUALITY = 0;
    private static final int SETTINGS_SUBTITLES = 1;
    private static final int SETTINGS_AUDIO = 2;

    private static final QualityPreference.Quality[] QUALITIES = {
            QualityPreference.Quality.AUTO,
            QualityPreference.Quality.MAX,
            QualityPreference.Quality.SAVER
    };

    private final Context context;
    private final FrameLayout overlay;

    private final TextView channelNumber;
    private final TextView channelName;
    private final TextView channelState;
    private final TextView channelQuality;

    private final LinearLayout sheet;
    private final ImageView playPause;
    private final TextView programmeTitle;
    private final TextView programmeTime;
    private final TextView next;
    private final TextView streamValue;
    private final TextView hint;
    private final ProgressBar progress;

    private final LinearLayout switcher;
    private final LinearLayout switcherRows;
    private final TextView switcherHint;

    private final LinearLayout settings;
    private final LinearLayout[] settingsRows = new LinearLayout[3];
    private final TextView[] qualityChips = new TextView[3];
    private final TextView qualityActual;
    private final TextView subtitleValue;
    private final TextView audioValue;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final List<Channel> channels = new ArrayList<>();
    private final List<TrackOption> audioOptions = new ArrayList<>();
    private final List<TrackOption> subtitleOptions = new ArrayList<>();

    private Callback callback;
    private boolean visible;
    private boolean released;
    private boolean live = true;
    private boolean playing;

    private boolean switcherOpen;
    private int switcherFocus = -1;
    private int switcherWindowStart;
    private int switcherVisibleCount = SWITCHER_MAX_ROWS;

    private boolean settingsOpen;
    private int settingsFocus = SETTINGS_QUALITY;

    private boolean playbackOptionsKnown;
    private QualityPreference.Quality selectedQuality = QualityPreference.Quality.AUTO;
    private String audioLabel = "";
    private String subtitleLabel = "";
    private String audioSelectedId = "";
    private String subtitleSelectedId = "";

    private int channelNumberValue = -1;
    private String actualQuality = "";
    private int listIndex = -1;
    private String programmeTitleText = "";
    private String programmeTimeText = "";
    private long programmeStartMs;
    private long programmeEndMs;
    private long positionMs;
    private long durationMs;
    private int lastHandledDown = KeyEvent.KEYCODE_UNKNOWN;

    private final Runnable hideRunnable = this::hide;

    public PlayerOverlay(android.app.Activity activity, ViewGroup root, Callback callback) {
        if (activity == null || root == null) {
            throw new IllegalArgumentException("activity/root == null");
        }
        this.context = root.getContext();
        this.callback = callback;
        this.selectedQuality = QualityPreference.get(context);

        overlay = new FrameLayout(context);
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        View topScrim = new View(context);
        topScrim.setBackgroundResource(R.drawable.bg_scrim_top);
        overlay.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 230),
                Gravity.TOP));

        View bottomScrim = new View(context);
        bottomScrim.setBackgroundResource(R.drawable.bg_scrim_bottom);
        overlay.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 260),
                Gravity.BOTTOM));

        // ---------------------------------------------------------- identity
        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        channelNumber = Tv.numeral(context, 21f, 0xFFFFFFFF);
        channelNumber.setGravity(Gravity.CENTER);
        channelNumber.setBackground(Tv.box(context, Tv.BRAND, 4f, 0, 0f));
        channelNumber.setPadding(Tv.dp(context, 10), Tv.dp(context, 5),
                Tv.dp(context, 10), Tv.dp(context, 5));
        channelNumber.setText("—");
        identity.addView(channelNumber);

        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        channelName = Tv.displayText(context, 31f, 0xFFFFFFFF);
        channelName.setSingleLine(true);
        channelName.setEllipsize(TextUtils.TruncateAt.END);
        // No min width: a short name such as "HRT 1" must render in full. The only cap is
        // 760dp, applied both to the view and to the text itself in applyChannelName().
        channelName.setMaxWidth(Tv.dp(context, 760));
        channelName.setShadowLayer(Tv.dpf(context, 7f), 0f, Tv.dpf(context, 2f), 0xCC000000);
        channelName.setText("—");
        channelName.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        names.addView(channelName);

        LinearLayout stateRow = new LinearLayout(context);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(context);
        dot.setBackground(Tv.box(context, Tv.BRAND_BRIGHT, 99f, 0, 0f));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 6), Tv.dp(context, 6));
        dotLp.rightMargin = Tv.dp(context, 7);
        stateRow.addView(dot, dotLp);
        channelState = Tv.label(context, "", Tv.BRAND_BRIGHT);
        channelState.setShadowLayer(Tv.dpf(context, 5f), 0f, Tv.dpf(context, 1f), 0xCC000000);
        stateRow.addView(channelState);
        channelQuality = Tv.label(context, "", Tv.TEXT_2);
        channelQuality.setAllCaps(false);
        channelQuality.setShadowLayer(Tv.dpf(context, 5f), 0f, Tv.dpf(context, 1f), 0xCC000000);
        LinearLayout.LayoutParams qualityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qualityLp.leftMargin = Tv.dp(context, 8);
        stateRow.addView(channelQuality, qualityLp);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateLp.topMargin = Tv.dp(context, 5);
        names.addView(stateRow, stateLp);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        namesLp.leftMargin = Tv.dp(context, 14);
        identity.addView(names, namesLp);

        FrameLayout.LayoutParams identityLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        identityLp.leftMargin = Tv.dp(context, 36);
        identityLp.topMargin = Tv.dp(context, 28);
        overlay.addView(identity, identityLp);

        // ---------------------------------------------------------- transport
        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Tv.box(context, 0xE60B0D11, 14f, 0x26FFFFFF, 1f));
        sheet.setElevation(Tv.dpf(context, 8f));
        sheet.setPadding(Tv.dp(context, 16), Tv.dp(context, 14),
                Tv.dp(context, 18), Tv.dp(context, 12));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playPause = new ImageView(context);
        playPause.setImageResource(R.drawable.ic_pause);
        playPause.setBackgroundResource(R.drawable.bg_play_button);
        playPause.setPadding(Tv.dp(context, 16), Tv.dp(context, 16),
                Tv.dp(context, 16), Tv.dp(context, 16));
        playPause.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playPause.setClickable(true);
        playPause.setOnClickListener(view -> {
            scheduleHide();
            firePlayPause();
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 58), Tv.dp(context, 58));
        playLp.rightMargin = Tv.dp(context, 18);
        row.addView(playPause, playLp);

        LinearLayout programme = new LinearLayout(context);
        programme.setOrientation(LinearLayout.VERTICAL);
        programme.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(programme, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        programmeTitle = Tv.displayText(context, 26f, 0xFFFFFFFF);
        programmeTitle.setSingleLine(true);
        programmeTitle.setEllipsize(TextUtils.TruncateAt.END);
        programmeTitle.setText(R.string.player_loading);
        programme.addView(programmeTitle);

        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Tv.dp(context, 4);
        programme.addView(meta, metaLp);

        programmeTime = Tv.text(context, 12.5f, Tv.TEXT_2, false);
        programmeTime.setFontFeatureSettings("tnum");
        programmeTime.setSingleLine(true);
        meta.addView(programmeTime);

        next = Tv.text(context, 11.5f, Tv.TEXT_3, false);
        next.setSingleLine(true);
        next.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nextLp.leftMargin = Tv.dp(context, 16);
        meta.addView(next, nextLp);

        // Actual stream resolution, never the preference.
        LinearLayout stream = new LinearLayout(context);
        stream.setOrientation(LinearLayout.VERTICAL);
        stream.setGravity(Gravity.END);
        LinearLayout.LayoutParams streamLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 58), ViewGroup.LayoutParams.WRAP_CONTENT);
        streamLp.leftMargin = Tv.dp(context, 18);
        row.addView(stream, streamLp);

        TextView streamLabel = Tv.label(context, getString(R.string.player_stream_actual),
                Tv.TEXT_3);
        streamLabel.setGravity(Gravity.END);
        streamLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
        streamLabel.setLetterSpacing(0.04f);
        streamLabel.setSingleLine(true);
        stream.addView(streamLabel);
        streamValue = Tv.displayText(context, 15f, Tv.TEXT);
        streamValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams streamValueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        streamValueLp.topMargin = Tv.dp(context, 2);
        stream.addView(streamValue, streamValueLp);

        ImageView settingsButton = new ImageView(context);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setColorFilter(Tv.TEXT_2);
        settingsButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settingsButton.setPadding(Tv.dp(context, 11), Tv.dp(context, 11),
                Tv.dp(context, 11), Tv.dp(context, 11));
        settingsButton.setBackground(Tv.box(context, 0x1AFFFFFF, 99f, Tv.STROKE, 1f));
        settingsButton.setClickable(true);
        settingsButton.setContentDescription(getString(R.string.player_settings_title));
        settingsButton.setOnClickListener(view -> openSettings());
        LinearLayout.LayoutParams settingsButtonLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 42), Tv.dp(context, 42));
        settingsButtonLp.leftMargin = Tv.dp(context, 14);
        row.addView(settingsButton, settingsButtonLp);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x26FFFFFF));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 3));
        progressLp.topMargin = Tv.dp(context, 13);
        sheet.addView(progress, progressLp);

        hint = Tv.text(context, 11.5f, Tv.TEXT_3, false);
        hint.setSingleLine(true);
        hint.setGravity(Gravity.END);
        hint.setText(R.string.player_hint_overlay);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Tv.dp(context, 8);
        sheet.addView(hint, hintLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetLp.leftMargin = Tv.dp(context, 32);
        sheetLp.rightMargin = Tv.dp(context, 32);
        sheetLp.bottomMargin = Tv.dp(context, 24);
        overlay.addView(sheet, sheetLp);

        // ---------------------------------------------------------- channel switcher
        switcher = new LinearLayout(context);
        switcher.setOrientation(LinearLayout.VERTICAL);
        switcher.setBackground(Tv.box(context, Tv.BG_RAISED, 12f, Tv.STROKE, 1f));
        switcher.setPadding(Tv.dp(context, 12), Tv.dp(context, 12),
                Tv.dp(context, 12), Tv.dp(context, 10));
        switcher.setVisibility(View.GONE);

        TextView switcherTitle = Tv.label(context, getString(R.string.player_channels),
                Tv.TEXT_2);
        switcher.addView(switcherTitle);

        switcherRows = new LinearLayout(context);
        switcherRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowsLp.topMargin = Tv.dp(context, 4);
        switcher.addView(switcherRows, rowsLp);

        switcherHint = Tv.text(context, 10f, Tv.TEXT_3, false);
        switcherHint.setText(R.string.player_switcher_hint);
        switcherHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams switcherHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switcherHintLp.topMargin = Tv.dp(context, 7);
        switcher.addView(switcherHint, switcherHintLp);

        FrameLayout.LayoutParams switcherLp = new FrameLayout.LayoutParams(
                Tv.dp(context, SWITCHER_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        switcherLp.leftMargin = Tv.dp(context, 32);
        switcherLp.bottomMargin = Tv.dp(context, PANEL_BOTTOM_MARGIN_DP);
        overlay.addView(switcher, switcherLp);

        // ---------------------------------------------------------- settings sheet
        settings = new LinearLayout(context);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setBackground(Tv.box(context, Tv.BG_RAISED, 12f, Tv.STROKE, 1f));
        settings.setPadding(Tv.dp(context, 16), Tv.dp(context, 14),
                Tv.dp(context, 16), Tv.dp(context, 12));
        settings.setVisibility(View.GONE);

        TextView settingsTitle = Tv.label(context,
                getString(R.string.player_settings_title), Tv.TEXT_2);
        settings.addView(settingsTitle);

        View titleRule = Tv.hairline(context, true);
        LinearLayout.LayoutParams titleRuleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Tv.dp(context, 1)));
        titleRuleLp.topMargin = Tv.dp(context, 9);
        titleRuleLp.bottomMargin = Tv.dp(context, 4);
        settings.addView(titleRule, titleRuleLp);

        // 1. Quality preference + actual resolution.
        LinearLayout qualityRow = new LinearLayout(context);
        qualityRow.setOrientation(LinearLayout.VERTICAL);
        qualityRow.setPadding(Tv.dp(context, 10), Tv.dp(context, 8),
                Tv.dp(context, 10), Tv.dp(context, 8));
        settings.addView(qualityRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView qualitySection = Tv.label(context,
                getString(R.string.player_quality_section), Tv.TEXT_3);
        qualityRow.addView(qualitySection);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.topMargin = Tv.dp(context, 6);
        qualityRow.addView(chips, chipsLp);

        int[] qualityTitles = {
                R.string.quality_auto_title,
                R.string.quality_max_title,
                R.string.quality_saver_title
        };
        for (int i = 0; i < QUALITIES.length; i++) {
            final int index = i;
            TextView chip = Tv.displayText(context, 13.5f, Tv.TEXT_2);
            chip.setText(qualityTitles[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Tv.dp(context, 14), Tv.dp(context, 6),
                    Tv.dp(context, 14), Tv.dp(context, 6));
            chip.setBackground(Tv.box(context, Tv.SURFACE_2, 6f, 0, 0f));
            chip.setClickable(true);
            chip.setOnClickListener(view -> selectQuality(QUALITIES[index]));
            qualityChips[i] = chip;
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                chipLp.leftMargin = Tv.dp(context, 6);
            }
            chips.addView(chip, chipLp);
        }

        qualityActual = Tv.text(context, 11.5f, Tv.TEXT_3, false);
        LinearLayout.LayoutParams qualityActualLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qualityActualLp.topMargin = Tv.dp(context, 7);
        qualityRow.addView(qualityActual, qualityActualLp);
        settingsRows[SETTINGS_QUALITY] = qualityRow;

        settings.addView(Tv.hairline(context, true));

        // 2. Subtitles.
        LinearLayout subtitleRow = new LinearLayout(context);
        subtitleRow.setOrientation(LinearLayout.HORIZONTAL);
        subtitleRow.setGravity(Gravity.CENTER_VERTICAL);
        subtitleRow.setPadding(Tv.dp(context, 10), Tv.dp(context, 12),
                Tv.dp(context, 10), Tv.dp(context, 12));
        settings.addView(subtitleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitleSection = Tv.label(context,
                getString(R.string.player_subtitles_section), Tv.TEXT_3);
        subtitleRow.addView(subtitleSection, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        subtitleValue = Tv.displayText(context, 15f, Tv.TEXT);
        subtitleValue.setGravity(Gravity.END);
        subtitleValue.setSingleLine(true);
        subtitleValue.setEllipsize(TextUtils.TruncateAt.END);
        subtitleRow.addView(subtitleValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        subtitleValue.setMaxWidth(Tv.dp(context, 250));
        settingsRows[SETTINGS_SUBTITLES] = subtitleRow;

        settings.addView(Tv.hairline(context, true));

        // 3. Audio language.
        LinearLayout audioRow = new LinearLayout(context);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        audioRow.setGravity(Gravity.CENTER_VERTICAL);
        audioRow.setPadding(Tv.dp(context, 10), Tv.dp(context, 12),
                Tv.dp(context, 10), Tv.dp(context, 12));
        settings.addView(audioRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView audioSection = Tv.label(context,
                getString(R.string.player_audio_section), Tv.TEXT_3);
        audioRow.addView(audioSection, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        audioValue = Tv.displayText(context, 15f, Tv.TEXT);
        audioValue.setGravity(Gravity.END);
        audioValue.setSingleLine(true);
        audioValue.setEllipsize(TextUtils.TruncateAt.END);
        audioRow.addView(audioValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        audioValue.setMaxWidth(Tv.dp(context, 250));
        settingsRows[SETTINGS_AUDIO] = audioRow;

        TextView settingsHint = Tv.text(context, 10f, Tv.TEXT_3, false);
        settingsHint.setText(R.string.player_settings_hint);
        settingsHint.setSingleLine(true);
        LinearLayout.LayoutParams settingsHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsHintLp.topMargin = Tv.dp(context, 8);
        settings.addView(settingsHint, settingsHintLp);

        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(
                Tv.dp(context, SETTINGS_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        settingsLp.rightMargin = Tv.dp(context, 32);
        settingsLp.bottomMargin = Tv.dp(context, PANEL_BOTTOM_MARGIN_DP);
        overlay.addView(settings, settingsLp);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        actualQuality = getString(R.string.player_quality_unknown);
        channelQuality.setText(actualQuality);
        streamValue.setText(actualQuality);
        updateState();
        updatePlayGlyph();
        updateTransport();
        refreshSettings();
    }

    // ------------------------------------------------------------------ public API

    /**
     * Kept for the activity's tune path. {@code resolutionLabel} is intentionally ignored:
     * the overlay only shows the resolution the player actually produces.
     */
    public void setChannel(Channel channel, String resolutionLabel) {
        if (released) {
            return;
        }
        setChannelInfo(channel == null ? -1 : channel.getNumber(),
                channel == null ? "" : channel.getTitle(context), null);
        int found = channelNumberValue >= 0 ? indexOfNumber(channelNumberValue) : -1;
        if (found >= 0) {
            listIndex = found;
        }
        refreshNext();
        if (switcherOpen) {
            switcherFocus = resolveCurrentIndex();
            ensureSwitcherFocusVisible();
            clampSwitcherWindow();
            rebuildSwitcher();
        }
    }

    /** Identity without a Channel object, used by catch-up and VOD launches. */
    public void setChannelInfo(int number, String title, String resolutionLabel) {
        if (released) {
            return;
        }
        channelNumberValue = number;
        channelNumber.setText(number >= 0 ? String.valueOf(number) : "—");
        applyChannelName(title);
        setActualQuality(getString(R.string.player_quality_unknown));
        refreshNext();
    }

    public void setProgramme(String title, String timeRange, long startMs, long endMs) {
        if (released) {
            return;
        }
        programmeTitleText = title == null ? "" : title.trim();
        programmeTimeText = timeRange == null ? "" : timeRange.trim();
        programmeStartMs = Math.max(0L, startMs);
        programmeEndMs = Math.max(0L, endMs);
        updateProgramme();
        updateProgress();
        refreshNext();
    }

    public void setLive(boolean isLive) {
        if (released) {
            return;
        }
        live = isLive;
        updateState();
        updateProgress();
    }

    public void setChannelList(List<Channel> list, int currentIndex) {
        if (released) {
            return;
        }
        channels.clear();
        if (list != null) {
            for (Channel channel : list) {
                if (channel != null) {
                    channels.add(channel);
                }
            }
        }
        listIndex = currentIndex >= 0 && currentIndex < channels.size() ? currentIndex : -1;
        if (switcherOpen) {
            switcherVisibleCount = Math.min(SWITCHER_MAX_ROWS, channels.size());
            switcherFocus = resolveCurrentIndex();
            ensureSwitcherFocusVisible();
            clampSwitcherWindow();
            rebuildSwitcher();
        }
    }

    /** The live, actually produced picture resolution ("1080p", "720p", "—"). */
    public void setActualQuality(String label) {
        if (released) {
            return;
        }
        String value = TextUtils.isEmpty(label)
                ? getString(R.string.player_quality_unknown)
                : label.trim();
        if (value.equals(actualQuality)) {
            return;
        }
        actualQuality = value;
        channelQuality.setText(value);
        streamValue.setText(value);
        refreshSettings();
    }

    /** Pushes quality preference and the currently available audio/subtitle tracks. */
    public void setPlaybackOptions(QualityPreference.Quality selectedQuality,
                                   List<TrackOption> audio,
                                   List<TrackOption> subtitles,
                                   String currentAudioLabel,
                                   String currentSubtitleLabel) {
        if (released) {
            return;
        }
        if (selectedQuality != null) {
            this.selectedQuality = selectedQuality;
        }
        audioOptions.clear();
        if (audio != null) {
            for (TrackOption option : audio) {
                if (option != null) {
                    audioOptions.add(option);
                }
            }
        }
        subtitleOptions.clear();
        if (subtitles != null) {
            for (TrackOption option : subtitles) {
                if (option != null) {
                    subtitleOptions.add(option);
                }
            }
        }
        this.audioLabel = currentAudioLabel == null ? "" : currentAudioLabel.trim();
        this.subtitleLabel = currentSubtitleLabel == null ? "" : currentSubtitleLabel.trim();
        this.audioSelectedId = selectedId(audioOptions);
        this.subtitleSelectedId = selectedId(subtitleOptions);
        if (this.audioSelectedId.isEmpty() && !this.audioLabel.isEmpty()) {
            this.audioSelectedId = idForLabel(audioOptions, this.audioLabel);
        }
        if (this.subtitleSelectedId.isEmpty() && !this.subtitleLabel.isEmpty()) {
            this.subtitleSelectedId = idForLabel(subtitleOptions, this.subtitleLabel);
        }
        playbackOptionsKnown = true;
        refreshSettings();
    }

    public void onPlaybackState(boolean isPlaying, long positionMs, long durationMs) {
        if (released) {
            return;
        }
        this.playing = isPlaying;
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        updatePlayGlyph();
        updateProgress();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (released || event == null) {
            return false;
        }
        int action = event.getAction();
        int code = event.getKeyCode();
        if (action == KeyEvent.ACTION_UP) {
            if (lastHandledDown == code) {
                lastHandledDown = KeyEvent.KEYCODE_UNKNOWN;
                return true;
            }
            return false;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (event.getRepeatCount() > 0 && isConfirmKey(code)) {
            return visible;
        }
        boolean handled = visible ? handleVisible(code) : handleHidden(code);
        lastHandledDown = handled ? code : KeyEvent.KEYCODE_UNKNOWN;
        return handled;
    }

    public void show() {
        if (released) {
            return;
        }
        boolean wasVisible = visible;
        visible = true;
        refreshSettings();
        refreshNext();
        overlay.animate().cancel();
        if (!wasVisible) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.setTranslationY(Tv.dp(context, 6));
            overlay.animate().alpha(1f).translationY(0f)
                    .setDuration(FADE_MS).start();
        }
        updateTransportHint();
        scheduleHide();
    }

    public void hide() {
        if (released || !visible) {
            return;
        }
        visible = false;
        handler.removeCallbacks(hideRunnable);
        closeSwitcher(false);
        closeSettings(false);
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> {
                    if (!visible) {
                        overlay.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    public boolean isVisible() {
        return visible;
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        visible = false;
        switcherOpen = false;
        settingsOpen = false;
        callback = null;
        handler.removeCallbacksAndMessages(null);
        overlay.animate().cancel();
        switcher.animate().cancel();
        settings.animate().cancel();
        switcherRows.removeAllViews();
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    // ------------------------------------------------------------------ keys

    private boolean handleHidden(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
                fireZap(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                fireZap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                fireZap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                fireZap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                show();
                if (!channels.isEmpty()) {
                    openSwitcher();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return false;
            default:
                show();
                return false;
        }
    }

    private boolean handleVisible(int code) {
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            closeSwitcher(false);
            closeSettings(false);
            fireZap(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        if (settingsOpen) {
            return handleSettings(code);
        }
        if (switcherOpen) {
            return handleSwitcher(code);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                scheduleHide();
                fireZap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                openSettings();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                scheduleHide();
                firePlayPause();
                return true;
            case KeyEvent.KEYCODE_BACK:
                hide();
                return true;
            default:
                scheduleHide();
                return false;
        }
    }

    private boolean handleSwitcher(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSwitcher(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSwitcher(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                pickSwitcherChannel();
                return true;
            case KeyEvent.KEYCODE_BACK:
                closeSwitcher(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Leave the list but keep the transport overlay, so the next OK can
                // play/pause without a second BACK.
                closeSwitcher(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // No quality row any more; consume so the seek path stays off this panel.
                return true;
            default:
                return false;
        }
    }

    private boolean handleSettings(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                settingsFocus = Math.max(SETTINGS_QUALITY, settingsFocus - 1);
                refreshSettings();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                settingsFocus = Math.min(SETTINGS_AUDIO, settingsFocus + 1);
                refreshSettings();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                settingsStep(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                settingsStep(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (settingsFocus == SETTINGS_QUALITY) {
                    fireQuality(selectedQuality);
                } else {
                    settingsStep(1);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                closeSettings(true);
                return true;
            default:
                return false;
        }
    }

    private void settingsStep(int delta) {
        switch (settingsFocus) {
            case SETTINGS_SUBTITLES:
                cycleSubtitle(delta);
                break;
            case SETTINGS_AUDIO:
                cycleAudio(delta);
                break;
            case SETTINGS_QUALITY:
            default:
                int current = 0;
                for (int i = 0; i < QUALITIES.length; i++) {
                    if (QUALITIES[i] == selectedQuality) {
                        current = i;
                        break;
                    }
                }
                int next = (current + delta + QUALITIES.length) % QUALITIES.length;
                selectQuality(QUALITIES[next]);
                break;
        }
    }

    private static boolean isConfirmKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    private void fireZap(int delta) {
        Callback target = callback;
        if (target != null) {
            target.onZap(delta);
        }
    }

    private void firePlayPause() {
        Callback target = callback;
        if (target != null) {
            target.onPlayPause();
        }
    }

    private void fireQuality(QualityPreference.Quality quality) {
        Callback target = callback;
        if (target != null && quality != null) {
            target.onQualityPicked(quality);
        }
    }

    private void fireAudioTrack(String optionId) {
        Callback target = callback;
        if (target != null) {
            target.onAudioTrackPicked(optionId == null ? "" : optionId);
        }
    }

    private void fireSubtitleTrack(String optionId) {
        Callback target = callback;
        if (target != null) {
            target.onSubtitleTrackPicked(optionId == null ? "" : optionId);
        }
    }

    // ------------------------------------------------------------------ switcher

    private void openSwitcher() {
        if (released || switcherOpen || channels.isEmpty()) {
            return;
        }
        if (!visible) {
            show();
        }
        closeSettings(false);
        switcherVisibleCount = Math.min(SWITCHER_MAX_ROWS, channels.size());
        switcherFocus = resolveCurrentIndex();
        ensureSwitcherFocusVisible();
        clampSwitcherWindow();
        switcherOpen = true;
        handler.removeCallbacks(hideRunnable);
        switcher.animate().cancel();
        switcher.setVisibility(View.VISIBLE);
        switcher.setAlpha(0f);
        switcher.setTranslationX(-Tv.dp(context, 12));
        switcher.animate().alpha(1f).translationX(0f).setDuration(FADE_MS).start();
        rebuildSwitcher();
        updateTransportHint();
    }

    private void closeSwitcher(boolean animate) {
        if (!switcherOpen) {
            return;
        }
        switcherOpen = false;
        switcherFocus = -1;
        switcher.animate().cancel();
        if (animate) {
            switcher.animate().alpha(0f).setDuration(110L)
                    .withEndAction(() -> {
                        if (!switcherOpen) {
                            switcher.setVisibility(View.GONE);
                        }
                    }).start();
        } else {
            switcher.setVisibility(View.GONE);
        }
        if (!switcherOpen) {
            scheduleHide();
        }
        updateTransportHint();
    }

    private void moveSwitcher(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        int next = switcherFocus + delta;
        if (next < 0 || next >= channels.size()) {
            return;
        }
        switcherFocus = next;
        int visibleCount = Math.min(switcherVisibleCount, channels.size());
        if (switcherFocus < switcherWindowStart) {
            switcherWindowStart = switcherFocus;
        } else if (switcherFocus >= switcherWindowStart + visibleCount) {
            switcherWindowStart = switcherFocus - visibleCount + 1;
        }
        rebuildSwitcher();
    }

    private void pickSwitcherChannel() {
        if (switcherFocus < 0 || switcherFocus >= channels.size()) {
            return;
        }
        Channel chosen = channels.get(switcherFocus);
        closeSwitcher(false);
        scheduleHide();
        Callback target = callback;
        if (target != null) {
            target.onChannelPicked(chosen);
        }
    }

    /** Keeps the focused channel inside the five-row window. */
    private void ensureSwitcherFocusVisible() {
        int size = channels.size();
        if (size == 0 || switcherFocus < 0) {
            return;
        }
        int visibleCount = Math.min(switcherVisibleCount, size);
        if (switcherFocus < switcherWindowStart) {
            switcherWindowStart = switcherFocus;
        } else if (switcherFocus >= switcherWindowStart + visibleCount) {
            switcherWindowStart = switcherFocus - visibleCount + 1;
        }
    }

    private void clampSwitcherWindow() {
        int size = channels.size();
        if (size == 0) {
            switcherWindowStart = 0;
            return;
        }
        int visibleCount = Math.min(switcherVisibleCount, size);
        switcherWindowStart = Math.max(0, Math.min(switcherWindowStart,
                Math.max(0, size - visibleCount)));
    }

    private void rebuildSwitcher() {
        switcherRows.removeAllViews();
        int size = channels.size();
        if (size == 0) {
            TextView empty = Tv.text(context, 13f, Tv.TEXT_3, false);
            empty.setText(R.string.player_channels_empty);
            empty.setPadding(Tv.dp(context, 10), Tv.dp(context, 12),
                    Tv.dp(context, 10), Tv.dp(context, 12));
            switcherRows.addView(empty);
            return;
        }
        int visibleCount = Math.min(switcherVisibleCount, size);
        int start = Math.max(0, Math.min(switcherWindowStart, Math.max(0, size - visibleCount)));
        switcherWindowStart = start;
        for (int i = start; i < start + visibleCount; i++) {
            switcherRows.addView(buildSwitcherRow(i, channels.get(i)));
        }
    }

    private View buildSwitcherRow(int index, Channel channel) {
        boolean focused = index == switcherFocus;
        boolean current = channel.getNumber() == channelNumberValue;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tv.dp(context, 10), 0, Tv.dp(context, 10), 0);
        row.setBackground(focused
                ? Tv.box(context, Tv.SURFACE, 7f, 0xFFFFFFFF, 2f)
                : current ? Tv.box(context, 0x1FE6007E, 7f, 0, 0f)
                : Tv.box(context, 0x00000000, 7f, 0, 0f));

        if (current) {
            View tally = new View(context);
            tally.setBackgroundColor(Tv.BRAND);
            LinearLayout.LayoutParams tallyLp = new LinearLayout.LayoutParams(
                    Math.max(2, Tv.dp(context, 3)), Tv.dp(context, 22));
            tallyLp.rightMargin = Tv.dp(context, 9);
            row.addView(tally, tallyLp);
        }

        TextView number = Tv.numeral(context, 17f,
                current ? Tv.BRAND_BRIGHT : Tv.TEXT_2);
        number.setText(channel.getNumber() >= 0
                ? String.valueOf(channel.getNumber()) : "—");
        number.setGravity(Gravity.END);
        row.addView(number, new LinearLayout.LayoutParams(
                Tv.dp(context, 42), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Tv.dp(context, 14);
        row.addView(texts, textLp);

        TextView name = Tv.displayText(context, 15f,
                focused ? 0xFFFFFFFF : Tv.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(channel.getTitle(context));
        texts.addView(name);

        EpgProgram programme = EpgCache.programmeAt(channel.getNumber(),
                System.currentTimeMillis());
        TextView now = Tv.text(context, 10.5f, Tv.TEXT_3, false);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        now.setText(programme == null ? getString(R.string.channels_no_epg) : programme.title);
        texts.addView(now);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, SWITCHER_ROW_HEIGHT_DP));
        rowLp.bottomMargin = Tv.dp(context, 2);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setOnClickListener(view -> {
            switcherFocus = index;
            pickSwitcherChannel();
        });
        return row;
    }

    private int resolveCurrentIndex() {
        int size = channels.size();
        if (size == 0) {
            return 0;
        }
        int byNumber = channelNumberValue >= 0 ? indexOfNumber(channelNumberValue) : -1;
        if (byNumber >= 0) {
            return byNumber;
        }
        if (listIndex >= 0 && listIndex < size) {
            return listIndex;
        }
        return 0;
    }

    private int indexOfNumber(int number) {
        if (number < 0) {
            return -1;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getNumber() == number) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ settings

    private void openSettings() {
        if (released || settingsOpen) {
            return;
        }
        if (!visible) {
            show();
        }
        closeSwitcher(false);
        settingsOpen = true;
        settingsFocus = SETTINGS_QUALITY;
        handler.removeCallbacks(hideRunnable);
        settings.animate().cancel();
        settings.setVisibility(View.VISIBLE);
        settings.setAlpha(0f);
        settings.setTranslationY(Tv.dp(context, 8));
        settings.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start();
        refreshSettings();
        updateTransportHint();
    }

    private void closeSettings(boolean animate) {
        if (!settingsOpen) {
            return;
        }
        settingsOpen = false;
        settings.animate().cancel();
        if (animate) {
            settings.animate().alpha(0f).setDuration(110L)
                    .withEndAction(() -> {
                        if (!settingsOpen) {
                            settings.setVisibility(View.GONE);
                        }
                    }).start();
        } else {
            settings.setVisibility(View.GONE);
        }
        scheduleHide();
        updateTransportHint();
    }

    private void updateTransportHint() {
        if (hint == null) {
            return;
        }
        boolean panelOpen = switcherOpen || settingsOpen;
        hint.setVisibility(panelOpen ? View.GONE : View.VISIBLE);
        if (!panelOpen) {
            hint.setText(R.string.player_hint_overlay);
        }
    }

    private void selectQuality(QualityPreference.Quality quality) {
        if (quality == null) {
            return;
        }
        selectedQuality = quality;
        refreshSettings();
        fireQuality(quality);
    }

    private void cycleSubtitle(int delta) {
        if (subtitleOptions.isEmpty()) {
            return;
        }
        int size = subtitleOptions.size() + 1; // Off is always the first entry.
        int current = 0;
        if (!subtitleSelectedId.isEmpty()) {
            int found = indexOfId(subtitleOptions, subtitleSelectedId);
            if (found >= 0) {
                current = found + 1;
            }
        }
        int next = (current + delta + size) % size;
        if (next == 0) {
            subtitleSelectedId = "";
            subtitleLabel = "";
            fireSubtitleTrack("");
        } else {
            TrackOption option = subtitleOptions.get(next - 1);
            subtitleSelectedId = option.id;
            subtitleLabel = option.label;
            fireSubtitleTrack(option.id);
        }
        refreshSettings();
    }

    private void cycleAudio(int delta) {
        if (audioOptions.isEmpty()) {
            return;
        }
        int size = audioOptions.size();
        int current = 0;
        int found = indexOfId(audioOptions, audioSelectedId);
        if (found >= 0) {
            current = found;
        }
        int next = (current + delta + size) % size;
        TrackOption option = audioOptions.get(next);
        audioSelectedId = option.id;
        audioLabel = option.label;
        refreshSettings();
        fireAudioTrack(option.id);
    }

    private void refreshSettings() {
        if (qualityActual == null) {
            return;
        }
        qualityActual.setText(getString(R.string.player_quality_actual, actualQuality));
        for (int i = 0; i < QUALITIES.length; i++) {
            boolean selected = QUALITIES[i] == selectedQuality;
            TextView chip = qualityChips[i];
            if (chip == null) {
                continue;
            }
            chip.setBackground(selected
                    ? Tv.box(context, 0x33E6007E, 6f, 0, 0f)
                    : Tv.box(context, Tv.SURFACE_2, 6f, 0, 0f));
            chip.setTextColor(selected ? Tv.BRAND_BRIGHT : Tv.TEXT_2);
        }
        updateTrackLabels();
        for (int i = 0; i < settingsRows.length; i++) {
            LinearLayout row = settingsRows[i];
            if (row == null) {
                continue;
            }
            row.setBackground(settingsOpen && settingsFocus == i
                    ? Tv.box(context, Tv.SURFACE, 9f, 0xFFFFFFFF, 2f)
                    : Tv.box(context, 0x00000000, 9f, 0, 0f));
        }
    }

    private void updateTrackLabels() {
        if (subtitleOptions.isEmpty() || !playbackOptionsKnown) {
            subtitleValue.setText(playbackOptionsKnown
                    ? R.string.player_track_none : R.string.player_subtitles_off);
            subtitleValue.setTextColor(playbackOptionsKnown ? Tv.TEXT_3 : Tv.TEXT);
        } else if (subtitleSelectedId.isEmpty()) {
            subtitleValue.setText(R.string.player_subtitles_off);
            subtitleValue.setTextColor(Tv.TEXT);
        } else {
            subtitleValue.setText(TextUtils.isEmpty(subtitleLabel)
                    ? getString(R.string.player_subtitles_off) : subtitleLabel);
            subtitleValue.setTextColor(Tv.TEXT);
        }

        if (audioOptions.isEmpty() || !playbackOptionsKnown) {
            audioValue.setText(playbackOptionsKnown
                    ? R.string.player_track_none : R.string.player_track_default);
            audioValue.setTextColor(playbackOptionsKnown ? Tv.TEXT_3 : Tv.TEXT);
        } else {
            audioValue.setText(TextUtils.isEmpty(audioLabel)
                    ? getString(R.string.player_track_default) : audioLabel);
            audioValue.setTextColor(Tv.TEXT);
        }
    }

    private static String selectedId(List<TrackOption> options) {
        for (TrackOption option : options) {
            if (option.selected) {
                return option.id;
            }
        }
        return "";
    }

    private static String idForLabel(List<TrackOption> options, String label) {
        for (TrackOption option : options) {
            if (option.label.equalsIgnoreCase(label)) {
                return option.id;
            }
        }
        return "";
    }

    private static int indexOfId(List<TrackOption> options, String id) {
        if (TextUtils.isEmpty(id)) {
            return -1;
        }
        for (int i = 0; i < options.size(); i++) {
            if (id.equals(options.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ state

    private void applyChannelName(String title) {
        String value = TextUtils.isEmpty(title)
                ? getString(R.string.player_quality_unknown)
                : title.trim();
        float maxWidthPx = Tv.dpf(context, 760f);
        channelName.setText(TextUtils.ellipsize(
                value, channelName.getPaint(), maxWidthPx, TextUtils.TruncateAt.END));
    }

    private void updateState() {
        channelState.setText(live ? R.string.player_live : R.string.player_time_shift);
        channelState.setTextColor(live ? Tv.BRAND_BRIGHT : Tv.WARNING);
    }

    private void updateTransport() {
        streamValue.setText(actualQuality);
        channelQuality.setText(actualQuality);
    }

    private void updatePlayGlyph() {
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(getString(
                playing ? R.string.player_pause : R.string.player_play));
    }

    private void updateProgramme() {
        programmeTitle.setText(TextUtils.isEmpty(programmeTitleText)
                ? getString(R.string.player_title_fallback) : programmeTitleText);
        String time = programmeTimeText;
        if (TextUtils.isEmpty(time)
                && programmeStartMs > 0 && programmeEndMs > programmeStartMs) {
            time = clock.format(new Date(programmeStartMs))
                    + " – " + clock.format(new Date(programmeEndMs));
        }
        programmeTime.setText(time);
        programmeTime.setVisibility(TextUtils.isEmpty(time) ? View.GONE : View.VISIBLE);
    }

    private void refreshNext() {
        if (channelNumberValue < 0) {
            next.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        EpgProgram upcoming = EpgCache.next(channelNumberValue, now);
        if (upcoming == null) {
            next.setVisibility(View.GONE);
            return;
        }
        next.setText(getString(R.string.player_next_label) + "  "
                + upcoming.title + "  " + clock.format(new Date(upcoming.startMs)));
        next.setVisibility(View.VISIBLE);
    }

    private void updateProgress() {
        long now = System.currentTimeMillis();
        float fraction = -1f;
        if (!live && durationMs > 0) {
            fraction = positionMs / (float) durationMs;
        } else if (live && programmeStartMs > 0 && programmeEndMs > programmeStartMs) {
            fraction = (now - programmeStartMs)
                    / (float) (programmeEndMs - programmeStartMs);
        } else if (durationMs > 0) {
            fraction = positionMs / (float) durationMs;
        }
        if (fraction < 0f) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(Math.round(Math.max(0f, Math.min(1f, fraction)) * 1000f));
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (visible && !released && !switcherOpen && !settingsOpen) {
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        }
    }

    private String getString(int res) {
        return context.getString(res);
    }

    private String getString(int res, Object... args) {
        return context.getString(res, args);
    }
}
