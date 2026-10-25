package local.mintv.player;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
 * Picture-first player chrome. Two pieces only: a quiet channel identity at the top-left
 * and one transport plate at the bottom. Everything else is in the quick-channel drawer,
 * including the stream quality. Nothing stays on screen when the viewer stops moving.
 */
public final class PlayerOverlay {

    public interface Callback {
        void onZap(int delta);

        void onChannelPicked(Channel channel);

        void onPlayPause();

        void onQualityChanged();
    }

    private static final long AUTO_HIDE_MS = 4_000L;
    private static final long FADE_MS = 150L;
    private static final int STRIP_ROW_HEIGHT_DP = 44;
    private static final int STRIP_MIN_ROWS = 3;
    private static final int STRIP_MAX_ROWS = 6;

    private final Context context;
    private final FrameLayout overlay;
    private final TextView channelNumber;
    private final TextView channelName;
    private final TextView channelState;
    private final TextView channelResolution;
    private final LinearLayout sheet;
    private final ImageView playPause;
    private final TextView programmeTitle;
    private final TextView programmeTime;
    private final TextView next;
    private final TextView qualityValue;
    private final TextView hint;
    private final ProgressBar progress;

    private final LinearLayout strip;
    private final LinearLayout stripQuality;
    private final TextView stripQualityValue;
    private final TextView stripHint;
    private final LinearLayout stripRows;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final List<Channel> channels = new ArrayList<>();

    private Callback callback;
    private boolean visible;
    private boolean released;
    private boolean stripOpen;
    private boolean live = true;
    private boolean playing;
    private int channelNumberValue = -1;
    private String resolution = "";
    private int listIndex = -1;
    private int stripFocus = -1;
    private int stripWindowStart;
    private int stripVisibleCount = STRIP_MAX_ROWS;
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
        channelName.setMinWidth(Tv.dp(context, 220));
        channelName.setMaxWidth(Tv.dp(context, 760));
        channelName.setShadowLayer(Tv.dpf(context, 7f), 0f, Tv.dpf(context, 2f), 0xCC000000);
        channelName.setText("—");
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
        channelResolution = Tv.label(context, "", Tv.TEXT_2);
        channelResolution.setShadowLayer(Tv.dpf(context, 5f), 0f, Tv.dpf(context, 1f), 0xCC000000);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resLp.leftMargin = Tv.dp(context, 8);
        stateRow.addView(channelResolution, resLp);
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

        LinearLayout side = new LinearLayout(context);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sideLp.leftMargin = Tv.dp(context, 18);
        row.addView(side, sideLp);

        TextView qualityLabel = Tv.label(context, getString(R.string.player_quality_short),
                Tv.TEXT_3);
        qualityLabel.setGravity(Gravity.END);
        qualityLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
        qualityLabel.setLetterSpacing(0.06f);
        side.addView(qualityLabel);
        qualityValue = Tv.displayText(context, 15f, Tv.TEXT);
        qualityValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams qualityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qualityLp.topMargin = Tv.dp(context, 2);
        side.addView(qualityValue, qualityLp);

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
        hint.setText(R.string.player_hint_simple);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Tv.dp(context, 8);
        hint.setGravity(Gravity.END);
        sheet.addView(hint, hintLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetLp.leftMargin = Tv.dp(context, 32);
        sheetLp.rightMargin = Tv.dp(context, 32);
        sheetLp.bottomMargin = Tv.dp(context, 24);
        overlay.addView(sheet, sheetLp);

        // ---------------------------------------------------------- quick drawer
        strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.VERTICAL);
        // Opaque: the channel identity behind the drawer must never show through the
        // quality row when the drawer opens.
        strip.setBackground(Tv.box(context, 0xFF0B0D11, 14f, 0x26FFFFFF, 1f));
        strip.setPadding(Tv.dp(context, 12), Tv.dp(context, 12),
                Tv.dp(context, 12), Tv.dp(context, 10));
        strip.setVisibility(View.GONE);

        LinearLayout stripHeader = new LinearLayout(context);
        stripHeader.setOrientation(LinearLayout.HORIZONTAL);
        stripHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView stripTitle = Tv.label(context, getString(R.string.player_channels), Tv.TEXT_2);
        stripHeader.addView(stripTitle);
        strip.addView(stripHeader);

        stripQuality = new LinearLayout(context);
        stripQuality.setOrientation(LinearLayout.HORIZONTAL);
        stripQuality.setGravity(Gravity.CENTER_VERTICAL);
        stripQuality.setPadding(Tv.dp(context, 10), 0, Tv.dp(context, 10), 0);
        TextView stripQualityLabel = Tv.label(context,
                getString(R.string.player_quality_label), Tv.TEXT_3);
        stripQuality.addView(stripQualityLabel);
        stripQualityValue = Tv.displayText(context, 15f, Tv.TEXT);
        LinearLayout.LayoutParams stripQualityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripQualityLp.leftMargin = Tv.dp(context, 10);
        stripQuality.addView(stripQualityValue, stripQualityLp);
        LinearLayout.LayoutParams stripQualityRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 38));
        stripQualityRowLp.topMargin = Tv.dp(context, 4);
        strip.addView(stripQuality, stripQualityRowLp);
        stripQuality.setOnClickListener(view -> {
            stripFocus = -1;
            rebuildStrip();
            cycleQuality();
        });

        stripRows = new LinearLayout(context);
        stripRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowsLp.topMargin = Tv.dp(context, 3);
        strip.addView(stripRows, rowsLp);

        stripHint = Tv.text(context, 9.5f, Tv.TEXT_3, false);
        stripHint.setText(R.string.player_hint_strip_simple);
        stripHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stripHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripHintLp.topMargin = Tv.dp(context, 7);
        strip.addView(stripHint, stripHintLp);

        FrameLayout.LayoutParams stripLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 430), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        stripLp.leftMargin = Tv.dp(context, 32);
        stripLp.bottomMargin = Tv.dp(context, 156);
        overlay.addView(strip, stripLp);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateState();
        updatePlayGlyph();
        updateQuality();
    }

    // ------------------------------------------------------------------ public API

    public void setChannel(Channel channel, String resolutionLabel) {
        if (released) {
            return;
        }
        String label = resolutionLabel;
        if (TextUtils.isEmpty(label) && channel != null) {
            label = channel.getQuality();
        }
        setChannelInfo(channel == null ? -1 : channel.getNumber(),
                channel == null ? "" : channel.getTitle(), label);
        int found = channelNumberValue >= 0 ? indexOfNumber(channelNumberValue) : -1;
        if (found >= 0) {
            listIndex = found;
        }
        refreshNext();
        if (stripOpen) {
            stripFocus = resolveCurrentIndex();
            clampStripWindow();
            rebuildStrip();
        }
    }

    /** Identity without a Channel object, used by catch-up launches. */
    public void setChannelInfo(int number, String title, String resolutionLabel) {
        if (released) {
            return;
        }
        channelNumberValue = number;
        channelNumber.setText(number >= 0 ? String.valueOf(number) : "—");
        channelName.setText(TextUtils.isEmpty(title) ? "—" : title);
        resolution = resolutionLabel == null ? "" : resolutionLabel.trim();
        if (TextUtils.isEmpty(resolution)) {
            resolution = "";
        }
        channelResolution.setText(resolution.toUpperCase(Locale.US));
        channelResolution.setVisibility(TextUtils.isEmpty(resolution) ? View.GONE : View.VISIBLE);
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
        if (stripOpen) {
            stripFocus = resolveCurrentIndex();
            clampStripWindow();
            rebuildStrip();
        }
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
        refreshNext();
        updateQuality();
        overlay.animate().cancel();
        if (!wasVisible) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.setTranslationY(Tv.dp(context, 6));
            overlay.animate().alpha(1f).translationY(0f)
                    .setDuration(FADE_MS).start();
        }
        scheduleHide();
    }

    public void hide() {
        if (released || !visible) {
            return;
        }
        visible = false;
        handler.removeCallbacks(hideRunnable);
        collapseStrip(false);
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
        stripOpen = false;
        callback = null;
        handler.removeCallbacksAndMessages(null);
        overlay.animate().cancel();
        strip.animate().cancel();
        stripRows.removeAllViews();
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    // ------------------------------------------------------------------ keys

    private boolean handleHidden(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                fireZap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                fireZap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                show();
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
            if (stripOpen) {
                collapseStrip(true);
            }
            fireZap(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        if (stripOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveStrip(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveStrip(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (stripFocus >= 0) {
                        stripFocus = -1;
                        rebuildStrip();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (stripFocus < 0) {
                        stripFocus = resolveCurrentIndex();
                        clampStripWindow();
                        rebuildStrip();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (stripFocus < 0) {
                        cycleQuality();
                    } else {
                        pickStripChannel();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    collapseStrip(true);
                    return true;
                default:
                    return false;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                scheduleHide();
                fireZap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                openStrip();
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

    // ------------------------------------------------------------------ strip

    private void openStrip() {
        if (channels.isEmpty()) {
            return;
        }
        stripVisibleCount = computeStripRowCount();
        stripFocus = resolveCurrentIndex();
        clampStripWindow();
        stripOpen = true;
        handler.removeCallbacks(hideRunnable);
        strip.animate().cancel();
        strip.setVisibility(View.VISIBLE);
        strip.setAlpha(0f);
        strip.setTranslationX(-Tv.dp(context, 12));
        strip.animate().alpha(1f).translationX(0f).setDuration(FADE_MS).start();
        rebuildStrip();
    }

    private void collapseStrip(boolean animate) {
        if (!stripOpen) {
            return;
        }
        stripOpen = false;
        stripFocus = -1;
        strip.animate().cancel();
        if (animate) {
            strip.animate().alpha(0f).setDuration(110L)
                    .withEndAction(() -> {
                        if (!stripOpen) {
                            strip.setVisibility(View.GONE);
                        }
                    }).start();
        } else {
            strip.setVisibility(View.GONE);
        }
        stripRows.removeAllViews();
        scheduleHide();
    }

    private void moveStrip(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        if (stripFocus < 0) {
            if (delta > 0) {
                stripFocus = 0;
            }
            rebuildStrip();
            return;
        }
        int next = stripFocus + delta;
        if (next < 0) {
            stripFocus = -1;
            rebuildStrip();
            return;
        }
        if (next >= channels.size()) {
            return;
        }
        stripFocus = next;
        int visibleCount = Math.min(stripVisibleCount, channels.size());
        if (stripFocus < stripWindowStart) {
            stripWindowStart = stripFocus;
        } else if (stripFocus >= stripWindowStart + visibleCount) {
            stripWindowStart = stripFocus - visibleCount + 1;
        }
        rebuildStrip();
    }

    private void pickStripChannel() {
        if (stripFocus < 0 || stripFocus >= channels.size()) {
            return;
        }
        Channel chosen = channels.get(stripFocus);
        collapseStrip(false);
        scheduleHide();
        Callback target = callback;
        if (target != null) {
            target.onChannelPicked(chosen);
        }
    }

    private void clampStripWindow() {
        int size = channels.size();
        if (size == 0) {
            stripWindowStart = 0;
            return;
        }
        int visibleCount = Math.min(stripVisibleCount, size);
        stripWindowStart = Math.max(0, Math.min(stripWindowStart,
                Math.max(0, size - visibleCount)));
    }

    private void rebuildStrip() {
        stripRows.removeAllViews();
        int size = channels.size();
        stripQuality.setBackground(stripFocus < 0
                ? Tv.box(context, Tv.SURFACE_3, 7f, 0xFFFFFFFF, 2f)
                : Tv.box(context, 0x00000000, 7f, 0, 0f));
        if (size == 0) {
            TextView empty = Tv.text(context, 13f, Tv.TEXT_3, false);
            empty.setText(R.string.player_channels_empty);
            empty.setPadding(Tv.dp(context, 10), Tv.dp(context, 12),
                    Tv.dp(context, 10), Tv.dp(context, 12));
            stripRows.addView(empty);
            return;
        }
        int visibleCount = Math.min(stripVisibleCount, size);
        int start = Math.max(0, Math.min(stripWindowStart, Math.max(0, size - visibleCount)));
        stripWindowStart = start;
        for (int i = start; i < start + visibleCount; i++) {
            stripRows.addView(buildStripRow(i, channels.get(i)));
        }
    }

    private View buildStripRow(int index, Channel channel) {
        boolean focused = index == stripFocus;
        boolean current = channel.getNumber() == channelNumberValue;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tv.dp(context, 10), 0, Tv.dp(context, 10), 0);
        row.setBackground(focused
                ? Tv.box(context, Tv.SURFACE_3, 7f, 0xFFFFFFFF, 2f)
                : current ? Tv.box(context, 0x1FE6007E, 7f, Tv.STROKE, 1f)
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
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Tv.dp(context, 14);
        row.addView(texts, textLp);

        TextView name = Tv.displayText(context, 15f,
                focused ? 0xFFFFFFFF : Tv.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(channel.getTitle());
        texts.addView(name);

        EpgProgram programme = EpgCache.programmeAt(channel.getNumber(),
                System.currentTimeMillis());
        TextView now = Tv.text(context, 10.5f, Tv.TEXT_3, false);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        now.setText(programme == null ? getString(R.string.channels_no_epg) : programme.title);
        texts.addView(now);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, STRIP_ROW_HEIGHT_DP));
        rowLp.bottomMargin = Tv.dp(context, 2);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setOnClickListener(view -> {
            stripFocus = index;
            pickStripChannel();
        });
        return row;
    }

    private int computeStripRowCount() {
        int available = overlay.getHeight()
                - Tv.dp(context, 156 + 24 + 38 + 18 + 30);
        if (available <= 0) {
            return STRIP_MIN_ROWS;
        }
        int rows = available / Tv.dp(context, STRIP_ROW_HEIGHT_DP);
        return Math.max(STRIP_MIN_ROWS, Math.min(STRIP_MAX_ROWS, rows));
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

    // ------------------------------------------------------------------ state

    private void updateState() {
        channelState.setText(live ? R.string.player_live : R.string.player_time_shift);
        channelState.setTextColor(live ? Tv.BRAND_BRIGHT : Tv.WARNING);
    }

    private void updateQuality() {
        QualityPreference.Quality quality = QualityPreference.get(context);
        String label;
        switch (quality) {
            case MAX:
                label = getString(R.string.quality_short_max);
                break;
            case SAVER:
                label = getString(R.string.quality_short_saver);
                break;
            default:
                label = getString(R.string.quality_short_auto);
                break;
        }
        qualityValue.setText(label);
        stripQualityValue.setText(label);
    }

    private void cycleQuality() {
        QualityPreference.Quality nextQuality = QualityPreference.get(context).next();
        QualityPreference.set(context, nextQuality);
        updateQuality();
        Callback target = callback;
        if (target != null) {
            target.onQualityChanged();
        }
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
        if (visible && !released && !stripOpen) {
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        }
    }

    private String getString(int res) {
        return context.getString(res);
    }
}
