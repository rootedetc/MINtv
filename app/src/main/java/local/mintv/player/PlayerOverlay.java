package local.mintv.player;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import local.mintv.data.Channel;

/**
 * TiviMate-style player overlay: channel identity top-left, wall clock top-right,
 * now/next + transport card at the bottom and a quick-switch channel strip that
 * expands upwards from the bottom card.
 *
 * <h3>Threading</h3>
 * <p>The whole class is main-thread only. Construct it from {@code onCreate}, call its
 * setters from the player's main-thread callbacks, and call {@link #release()} from
 * {@code onDestroy}.</p>
 *
 * <h3>Key handling</h3>
 * <p>The activity must give this overlay first refusal in its own
 * {@code dispatchKeyEvent}. The event is consumed only when this class returns
 * {@code true}; {@code BACK} is deliberately not consumed unless it is being used to
 * close the channel strip (in which case the activity must not exit).</p>
 *
 * <p>No XML, resources or new dependencies: every view is built programmatically and
 * every colour/string is local to this file.</p>
 */
public final class PlayerOverlay {

    /** Callbacks into the player activity. All are invoked on the main thread. */
    public interface Callback {
        /** +1 next channel, -1 previous channel. */
        void onZap(int delta);

        /** A channel row was confirmed in the quick-switch strip. */
        void onChannelPicked(Channel channel);

        /** The play/pause box was pressed (or OK was pressed on the closed overlay). */
        void onPlayPause();
    }

    // ------------------------------------------------------------------ tuning

    private static final long AUTO_HIDE_MS = 4_000L;
    private static final long CLOCK_PERIOD_MS = 60_000L;
    private static final long FADE_MS = 130L;

    private static final int CARD_BG = 0xB30E1116;
    private static final int CARD_STROKE = 0x26FFFFFF;
    private static final int PANEL_BG = 0xF00E1116;
    private static final int PANEL_STROKE = 0x26FFFFFF;

    private static final int ACCENT = 0xFFE6007E;
    private static final int ACCENT_BRIGHT = 0xFFFF3D9A;
    private static final int TEXT_PRIMARY = 0xFFF5F7FA;
    private static final int TEXT_SECONDARY = 0xFF98A2B3;
    private static final int TEXT_TERTIARY = 0xFF6B7480;
    private static final int WHITE = 0xFFFFFFFF;

    /** Catch-up uses a distinct blue so it never reads as "this is live". */
    private static final int TIME_SHIFT_BG = 0xFF2D6CDF;

    private static final int CHANNEL_TITLE_SP = 28;
    private static final int PILL_SP = 12;
    private static final int CHIP_SP = 11;
    private static final int CLOCK_SP = 22;
    private static final int PROGRAMME_TITLE_SP = 19;
    private static final int PROGRAMME_TIME_SP = 13;
    private static final int HINT_SP = 12;
    private static final int PLAY_GLYPH_SP = 19;
    private static final int STRIP_NAME_SP = 16;
    private static final int STRIP_NUMBER_SP = 15;
    private static final int STRIP_HINT_SP = 11;

    private static final int STRIP_WIDTH_DP = 420;
    private static final int STRIP_ROW_HEIGHT_DP = 36;
    private static final int STRIP_ROW_PITCH_DP = 38;   // row + 2 dp gap
    private static final int STRIP_CHROME_DP = 26;      // panel padding + header
    private static final int STRIP_MIN_ROWS = 3;
    private static final int STRIP_MAX_ROWS = 7;

    private static final String HINT_NORMAL =
            "OK: play/pause \u00B7 \u2193: channels \u00B7 BACK: exit";
    private static final String HINT_STRIP =
            "OK: watch \u00B7 \u2191\u2193: browse \u00B7 BACK: close";

    // ------------------------------------------------------------------- state

    private final Context context;
    private final FrameLayout overlay;
    private final LinearLayout topLeftCard;
    private final TextView channelName;
    private final TextView livePill;
    private final TextView resolutionChip;
    private final TextView clock;
    private final LinearLayout bottomColumn;
    private final LinearLayout stripCard;
    private final LinearLayout stripRows;
    private final LinearLayout bottomCard;
    private final TextView playPause;
    private final TextView programmeTitle;
    private final TextView programmeTime;
    private final ProgressBar progress;
    private final TextView hint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final List<Channel> channels = new ArrayList<>();

    private Callback callback;

    private boolean visible;
    private boolean released;
    private boolean stripOpen;
    private boolean live = true;
    private boolean playing;

    private int channelNumber = -1;
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

    /** The key whose ACTION_DOWN we consumed, so its ACTION_UP can be swallowed too. */
    private int lastHandledDown = KeyEvent.KEYCODE_UNKNOWN;

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || !visible) {
                return;
            }
            updateClock();
            updateProgress();
            scheduleClock();
        }
    };

    // ------------------------------------------------------------- construction

    /**
     * Builds the whole overlay and attaches it as the last child of {@code root}.
     *
     * @param activity host activity (used only to validate the caller; the views take
     *                 their context from {@code root})
     * @param root     the content container, normally
     *                 {@code findViewById(android.R.id.content)} or the FrameLayout of
     *                 {@code activity_player.xml}; the overlay is added on top of it
     * @param callback player callbacks, may be {@code null} but then keys are inert
     */
    public PlayerOverlay(Activity activity, ViewGroup root, Callback callback) {
        if (activity == null) {
            throw new IllegalArgumentException("activity == null");
        }
        if (root == null) {
            throw new IllegalArgumentException("root == null");
        }
        this.context = root.getContext();
        this.callback = callback;

        overlay = new FrameLayout(context);
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        // ---- top-left: channel name + LIVE/TIME SHIFT + resolution
        topLeftCard = new LinearLayout(context);
        topLeftCard.setOrientation(LinearLayout.VERTICAL);
        topLeftCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        topLeftCard.setBackground(panelBackground(dp(14)));

        channelName = label(CHANNEL_TITLE_SP, TEXT_PRIMARY, true);
        channelName.setSingleLine(true);
        channelName.setEllipsize(TextUtils.TruncateAt.END);
        channelName.setMaxWidth(dp(600));
        channelName.setText("\u2014");
        // WRAP_CONTENT, not MATCH_PARENT: in a WRAP_CONTENT card a MATCH_PARENT child
        // is measured against the sibling badge row and ellipsises the channel name.
        topLeftCard.addView(channelName, wrapWrap());

        LinearLayout badges = new LinearLayout(context);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams badgeRowLp = wrapWrap();
        badgeRowLp.topMargin = dp(7);
        topLeftCard.addView(badges, badgeRowLp);

        livePill = label(PILL_SP, WHITE, true);
        livePill.setAllCaps(true);
        livePill.setLetterSpacing(0.08f);
        livePill.setGravity(Gravity.CENTER);
        livePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        badges.addView(livePill, wrapWrap());

        resolutionChip = label(CHIP_SP, 0xFFD6DEE8, true);
        resolutionChip.setAllCaps(true);
        resolutionChip.setLetterSpacing(0.10f);
        resolutionChip.setGravity(Gravity.CENTER);
        resolutionChip.setPadding(dp(7), dp(3), dp(7), dp(3));
        resolutionChip.setBackground(box(0x33FFFFFF, 5, 0, 0));
        resolutionChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chipLp = wrapWrap();
        chipLp.leftMargin = dp(8);
        badges.addView(resolutionChip, chipLp);

        FrameLayout.LayoutParams topLeftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        topLeftLp.leftMargin = dp(32);
        topLeftLp.topMargin = dp(20);
        overlay.addView(topLeftCard, topLeftLp);

        // ---- top-right: wall clock
        LinearLayout clockCard = new LinearLayout(context);
        clockCard.setOrientation(LinearLayout.HORIZONTAL);
        clockCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        clockCard.setBackground(panelBackground(dp(12)));

        clock = label(CLOCK_SP, TEXT_PRIMARY, true);
        clock.setText("--:--");
        clockCard.addView(clock, wrapWrap());

        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clockLp.rightMargin = dp(32);
        clockLp.topMargin = dp(20);
        overlay.addView(clockCard, clockLp);

        // ---- bottom column: strip (hidden) above the info card
        bottomColumn = new LinearLayout(context);
        bottomColumn.setOrientation(LinearLayout.VERTICAL);
        bottomColumn.setGravity(Gravity.BOTTOM);

        stripCard = new LinearLayout(context);
        stripCard.setOrientation(LinearLayout.VERTICAL);
        stripCard.setPadding(dp(4), dp(4), dp(4), dp(4));
        stripCard.setBackground(panelBackground(dp(12)));
        stripCard.setVisibility(View.GONE);

        TextView stripHeader = label(10, TEXT_TERTIARY, true);
        stripHeader.setText("CHANNELS");
        stripHeader.setAllCaps(true);
        stripHeader.setLetterSpacing(0.12f);
        stripHeader.setPadding(dp(10), dp(2), dp(10), dp(2));
        stripCard.addView(stripHeader, matchWrap());

        stripRows = new LinearLayout(context);
        stripRows.setOrientation(LinearLayout.VERTICAL);
        stripCard.addView(stripRows, matchWrap());

        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                dp(STRIP_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.gravity = Gravity.START;
        stripLp.bottomMargin = dp(10);
        bottomColumn.addView(stripCard, stripLp);

        bottomCard = new LinearLayout(context);
        bottomCard.setOrientation(LinearLayout.HORIZONTAL);
        bottomCard.setGravity(Gravity.CENTER_VERTICAL);
        bottomCard.setPadding(dp(14), dp(13), dp(16), dp(13));
        bottomCard.setMinimumHeight(dp(104));
        bottomCard.setBackground(cardBackground(dp(14)));
        bottomCard.setElevation(dp(8));
        bottomColumn.addView(bottomCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomLp.leftMargin = dp(32);
        bottomLp.rightMargin = dp(32);
        bottomLp.bottomMargin = dp(20);
        overlay.addView(bottomColumn, bottomLp);

        playPause = label(PLAY_GLYPH_SP, TEXT_PRIMARY, true);
        playPause.setGravity(Gravity.CENTER);
        playPause.setFocusable(true);
        playPause.setClickable(true);
        playPause.setContentDescription("Play");
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scheduleHide();
                firePlayPause();
            }
        });
        playPause.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                refreshPlayPauseBackground();
            }
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        playLp.rightMargin = dp(16);
        bottomCard.addView(playPause, playLp);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        bottomCard.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        programmeTitle = label(PROGRAMME_TITLE_SP, TEXT_PRIMARY, true);
        programmeTitle.setSingleLine(true);
        programmeTitle.setEllipsize(TextUtils.TruncateAt.END);
        programmeTitle.setVisibility(View.GONE);
        info.addView(programmeTitle, matchWrap());

        programmeTime = label(PROGRAMME_TIME_SP, TEXT_SECONDARY, false);
        programmeTime.setSingleLine(true);
        programmeTime.setVisibility(View.GONE);
        LinearLayout.LayoutParams timeLp = matchWrap();
        timeLp.topMargin = dp(2);
        info.addView(programmeTime, timeLp);

        progress = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setMinimumHeight(dp(4));
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(
                ColorStateList.valueOf(0x33FFFFFF));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressLp.topMargin = dp(8);
        info.addView(progress, progressLp);

        hint = label(HINT_SP, TEXT_TERTIARY, false);
        hint.setSingleLine(true);
        hint.setLetterSpacing(0.02f);
        hint.setText(HINT_NORMAL);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.topMargin = dp(8);
        info.addView(hint, hintLp);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        updateLivePill();
        updateResolutionChip();
        updatePlayGlyph();
        refreshPlayPauseBackground();
    }

    // -------------------------------------------------------------- public API

    /** Called whenever the playing channel changes. */
    public void setChannel(Channel channel, String resolutionLabel) {
        if (released) {
            return;
        }
        channelNumber = channel != null ? channel.getNumber() : -1;
        String title = channel != null ? channel.getTitle() : "";
        channelName.setText(TextUtils.isEmpty(title) ? "\u2014" : title);

        String resLabel = resolutionLabel;
        if (TextUtils.isEmpty(resLabel) && channel != null) {
            resLabel = channel.getQuality();
        }
        resolution = resLabel == null ? "" : resLabel.trim();
        updateResolutionChip();

        int found = channelNumber >= 0 ? indexOfNumber(channelNumber) : -1;
        if (found >= 0) {
            listIndex = found;
        }
        if (stripOpen) {
            stripFocus = resolveCurrentIndex();
            int size = channels.size();
            int visibleCount = Math.min(stripVisibleCount, size);
            stripWindowStart = clamp(stripFocus - visibleCount / 2, 0,
                    Math.max(0, size - visibleCount));
            rebuildStrip();
        }
    }

    /** Optional now/next info; either string may be null. Pass 0/0 when unknown. */
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
    }

    /** LIVE versus time-shift/catch-up pill. */
    public void setLive(boolean isLive) {
        if (released) {
            return;
        }
        live = isLive;
        updateLivePill();
        updateProgress();
    }

    /** Line-up for the quick-switch strip. {@code currentIndex} may be stale. */
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
        listIndex = currentIndex >= 0 && currentIndex < channels.size()
                ? currentIndex : -1;
        if (stripOpen) {
            stripFocus = resolveCurrentIndex();
            int size = channels.size();
            int visibleCount = Math.min(stripVisibleCount, size);
            stripWindowStart = clamp(stripFocus - visibleCount / 2, 0,
                    Math.max(0, size - visibleCount));
            rebuildStrip();
        }
    }

    /** Playback state, for the play/pause glyph and the progress bar. */
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

    /**
     * Returns true when the overlay consumed the key.
     *
     * <p>Only ACTION_DOWN performs an action; the matching ACTION_UP is swallowed so the
     * focused player view cannot react a second time. {@code BACK} is not consumed while
     * the strip is closed - the activity owns exit - but it does close the strip and is
     * consumed in that case.</p>
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (released || event == null) {
            return false;
        }
        final int action = event.getAction();
        final int code = event.getKeyCode();

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
            // Holding OK must not machine-gun play/pause or channel picks.
            return visible;
        }

        boolean handled;
        if (!visible) {
            handled = handleHidden(code);
        } else {
            scheduleHide();
            handled = handleVisible(code);
        }
        lastHandledDown = handled ? code : KeyEvent.KEYCODE_UNKNOWN;
        return handled;
    }

    public void show() {
        if (released) {
            return;
        }
        boolean wasVisible = visible;
        visible = true;
        overlay.animate().cancel();
        if (!wasVisible) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(FADE_MS).start();
        }
        updateClock();
        scheduleClock();
        refreshPlayPauseBackground();
        scheduleHide();
    }

    public void hide() {
        if (released || !visible) {
            return;
        }
        visible = false;
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(clockRunnable);
        collapseStrip();
        refreshPlayPauseBackground();
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!visible) {
                            overlay.setVisibility(View.GONE);
                        }
                    }
                })
                .start();
    }

    public boolean isVisible() {
        return visible;
    }

    /** Stops the clock ticker, drops callbacks and detaches the view tree. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        visible = false;
        stripOpen = false;
        lastHandledDown = KeyEvent.KEYCODE_UNKNOWN;
        callback = null;
        handler.removeCallbacksAndMessages(null);
        overlay.animate().cancel();
        stripCard.animate().cancel();
        stripRows.removeAllViews();
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    // -------------------------------------------------------------- key routing

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
                // The activity handles exit; never flash the overlay on the way out.
                return false;
            default:
                // "Any key wakes the overlay", but leave the key itself to the activity.
                show();
                return false;
        }
    }

    private boolean handleVisible(int code) {
        // Physical channel keys zap from either state; browsing is a DPAD gesture.
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (stripOpen) {
                collapseStrip();
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
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    pickStripChannel();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    collapseStrip();
                    return true;
                default:
                    return false;
            }
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // Zap but keep the overlay so the new name is visible immediately.
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
                firePlayPause();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return false;
            default:
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

    // ----------------------------------------------------------- channel strip

    private void openStrip() {
        scheduleHide();
        if (channels.isEmpty()) {
            return;
        }
        stripVisibleCount = computeStripRowCount();
        stripFocus = resolveCurrentIndex();
        int size = channels.size();
        int visibleCount = Math.min(stripVisibleCount, size);
        stripWindowStart = clamp(stripFocus - visibleCount / 2, 0,
                Math.max(0, size - visibleCount));
        stripOpen = true;
        stripCard.animate().cancel();
        stripCard.setAlpha(0f);
        stripCard.setVisibility(View.VISIBLE);
        stripCard.animate().alpha(1f).setDuration(FADE_MS).start();
        rebuildStrip();
        updateHint();
        refreshPlayPauseBackground();
    }

    private void collapseStrip() {
        if (!stripOpen) {
            return;
        }
        stripOpen = false;
        stripFocus = -1;
        stripCard.animate().cancel();
        stripCard.setVisibility(View.GONE);
        stripRows.removeAllViews();
        updateHint();
        refreshPlayPauseBackground();
    }

    private void moveStrip(int delta) {
        int size = channels.size();
        if (size == 0) {
            return;
        }
        int next = clamp(stripFocus + delta, 0, size - 1);
        if (next == stripFocus) {
            return;
        }
        stripFocus = next;
        int visibleCount = Math.min(stripVisibleCount, size);
        if (stripFocus < stripWindowStart) {
            stripWindowStart = stripFocus;
        } else if (stripFocus >= stripWindowStart + visibleCount) {
            stripWindowStart = stripFocus - visibleCount + 1;
        }
        stripWindowStart = clamp(stripWindowStart, 0,
                Math.max(0, size - visibleCount));
        rebuildStrip();
        scheduleHide();
    }

    private void pickStripChannel() {
        int size = channels.size();
        if (stripFocus < 0 || stripFocus >= size) {
            collapseStrip();
            return;
        }
        final Channel chosen = channels.get(stripFocus);
        collapseStrip();
        scheduleHide();
        Callback target = callback;
        if (target != null) {
            target.onChannelPicked(chosen);
        }
    }

    private void rebuildStrip() {
        stripRows.removeAllViews();
        int size = channels.size();
        if (size == 0) {
            TextView empty = label(13, TEXT_TERTIARY, false);
            empty.setText("No channels");
            empty.setPadding(dp(10), dp(8), dp(10), dp(8));
            stripRows.addView(empty, matchWrap());
            return;
        }
        int visibleCount = Math.min(stripVisibleCount, size);
        int start = clamp(stripWindowStart, 0, Math.max(0, size - visibleCount));
        stripWindowStart = start;
        for (int i = start; i < start + visibleCount; i++) {
            stripRows.addView(buildStripRow(i, channels.get(i)));
        }
    }

    private View buildStripRow(final int index, Channel channel) {
        final boolean focused = index == stripFocus;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setBackground(rowBackground(focused));
        row.setClickable(true);
        row.setFocusable(false);

        TextView number = label(STRIP_NUMBER_SP, ACCENT, true);
        number.setText(channel.getNumber() >= 0
                ? String.valueOf(channel.getNumber()) : "\u2014");
        row.addView(number, new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = label(STRIP_NAME_SP,
                focused ? TEXT_PRIMARY : 0xFFD7DEE8, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(channel.getTitle());
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean current = channelNumber >= 0 && channel.getNumber() == channelNumber;
        TextView badge = label(STRIP_HINT_SP,
                current ? ACCENT_BRIGHT : TEXT_TERTIARY, true);
        badge.setAllCaps(true);
        badge.setLetterSpacing(0.06f);
        badge.setText(current ? "LIVE" : qualityOf(channel));
        LinearLayout.LayoutParams badgeLp = wrapWrap();
        badgeLp.leftMargin = dp(8);
        row.addView(badge, badgeLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(STRIP_ROW_HEIGHT_DP));
        rowLp.bottomMargin = dp(2);
        row.setLayoutParams(rowLp);
        row.setContentDescription("Channel " + channel.getNumber() + ", "
                + channel.getTitle());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stripFocus = index;
                pickStripChannel();
            }
        });
        return row;
    }

    /**
     * Row count that actually fits between the top-left card and the bottom card. The
     * panel is chrome + n * pitch tall, so a 540 dp-tall TV still shows the full seven
     * rows while shorter layouts degrade to five or six instead of clipping.
     */
    private int computeStripRowCount() {
        int available = bottomColumn.getTop() - topLeftCard.getBottom() - dp(8);
        if (available <= 0) {
            return STRIP_MAX_ROWS;
        }
        int rows = (available - dp(STRIP_CHROME_DP)) / dp(STRIP_ROW_PITCH_DP);
        return Math.max(STRIP_MIN_ROWS, Math.min(STRIP_MAX_ROWS, rows));
    }

    /** Current channel by number first, stale list index only as a fallback. */
    private int resolveCurrentIndex() {
        int size = channels.size();
        if (size == 0) {
            return -1;
        }
        int byNumber = channelNumber >= 0 ? indexOfNumber(channelNumber) : -1;
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

    private static String qualityOf(Channel channel) {
        String quality = channel.getQuality();
        return TextUtils.isEmpty(quality) ? "HD" : quality;
    }

    // ------------------------------------------------------------------ state

    private void updateLivePill() {
        livePill.setText(live ? "LIVE" : "TIME SHIFT");
        livePill.setBackground(box(live ? ACCENT : TIME_SHIFT_BG, 5, 0, 0));
    }

    private void updateResolutionChip() {
        if (TextUtils.isEmpty(resolution)) {
            resolutionChip.setVisibility(View.GONE);
        } else {
            resolutionChip.setText(resolution);
            resolutionChip.setVisibility(View.VISIBLE);
        }
    }

    private void updatePlayGlyph() {
        playPause.setText(playing ? "\u25AE\u25AE" : "\u25B6");
        playPause.setContentDescription(playing ? "Pause" : "Play");
    }

    private void updateProgramme() {
        boolean hasTitle = !TextUtils.isEmpty(programmeTitleText);
        programmeTitle.setText(programmeTitleText);
        programmeTitle.setVisibility(hasTitle ? View.VISIBLE : View.GONE);

        String time = programmeTimeText;
        if (TextUtils.isEmpty(time)
                && programmeStartMs > 0 && programmeEndMs > programmeStartMs) {
            time = clockFormat.format(new Date(programmeStartMs))
                    + " \u2013 " + clockFormat.format(new Date(programmeEndMs));
        }
        boolean hasTime = !TextUtils.isEmpty(time);
        programmeTime.setText(time);
        programmeTime.setVisibility(hasTime ? View.VISIBLE : View.GONE);
    }

    private void updateProgress() {
        long now = System.currentTimeMillis();
        float fraction = -1f;

        if (!live && durationMs > 0) {
            // Time-shift/catch-up: where the playhead is in the recording.
            fraction = positionMs / (float) durationMs;
        } else if (live && !(programmeStartMs > 0 && programmeEndMs > programmeStartMs)) {
            // Nothing meaningful to show for a live stream without EPG context.
            fraction = -1f;
        } else if (programmeStartMs > 0 && programmeEndMs > programmeStartMs) {
            // Live: progress through the current programme's wall-clock span.
            fraction = (now - programmeStartMs)
                    / (float) (programmeEndMs - programmeStartMs);
        } else if (durationMs > 0) {
            fraction = positionMs / (float) durationMs;
        }

        if (fraction < 0f) {
            progress.setVisibility(View.GONE);
            return;
        }
        if (fraction > 1f) {
            fraction = 1f;
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(Math.round(fraction * 1000f));
    }

    private void updateHint() {
        hint.setText(stripOpen ? HINT_STRIP : HINT_NORMAL);
    }

    private void updateClock() {
        clock.setText(clockFormat.format(new Date()));
    }

    // --------------------------------------------------------------- lifecycle

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (visible && !released) {
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        }
    }

    private void scheduleClock() {
        handler.removeCallbacks(clockRunnable);
        if (!visible || released) {
            return;
        }
        long now = System.currentTimeMillis();
        long untilNextMinute = CLOCK_PERIOD_MS - (now % CLOCK_PERIOD_MS);
        handler.postDelayed(clockRunnable, Math.max(1_000L, untilNextMinute));
    }

    // ------------------------------------------------------------------ drawing

    private void refreshPlayPauseBackground() {
        boolean active = visible && !stripOpen;
        boolean highlighted = active || playPause.hasFocus();
        int fill = highlighted ? 0x2EE6007E : 0x1FFFFFFF;
        int stroke = highlighted ? ACCENT : 0x33FFFFFF;
        float strokeWidth = highlighted ? 2f : 1f;
        playPause.setBackground(box(fill, 12, stroke, strokeWidth));
        playPause.setTextColor(highlighted ? WHITE : TEXT_PRIMARY);
    }

    private Drawable cardBackground(float radiusDp) {
        return box(CARD_BG, radiusDp, CARD_STROKE, 1f);
    }

    private Drawable panelBackground(float radiusDp) {
        return box(PANEL_BG, radiusDp, PANEL_STROKE, 1f);
    }

    private Drawable rowBackground(boolean focused) {
        if (focused) {
            return box(0x26E6007E, 9, ACCENT, 2f);
        }
        return box(0x0DFFFFFF, 9, 0, 0);
    }

    private Drawable box(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0f) {
            drawable.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        }
        return drawable;
    }

    private TextView label(float sp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
