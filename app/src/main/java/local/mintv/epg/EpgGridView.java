package local.mintv.epg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import local.mintv.R;

/**
 * A TiviMate-style EPG grid rendered on a single {@link Canvas}.
 *
 * <h3>Layout math</h3>
 * <p>The view has three fixed pieces and one scrolling piece:</p>
 * <pre>
 *   x = 0                    x = columnW                          x = width
 *   +------------------------+---------------------------------------------+
 *   +      corner (date)     |  time ruler (44 dp, fixed vertically)       |  y = 0
 *   +------------------------+---------------------------------------------+
 *   |  channel column        |  programme grid                              |
 *   |  (210 dp, fixed        |  scrolls both ways; clip = the rect to the   |
 *   |   horizontally)        |  right/below the two fixed strips            |
 *   +------------------------+---------------------------------------------+
 * </pre>
 *
 * <p>The timeline always shows {@link #VISIBLE_MINUTES} (4 h) across the programme
 * area, so {@code pxPerMinute = gridWidth / 240}. The content is one full local day
 * ({@code 24 h}) wide and {@code rowHeight * channelCount} tall. Content coordinates
 * are day-relative: {@code x = (timeMs - dayStartMs) * pxPerMs}, {@code y = row * rowHeight}.
 * The canvas is translated by {@code (columnW - scrollX, rulerH - scrollY)} while drawing
 * the grid, which keeps all geometry code in content coordinates.</p>
 *
 * <p>Only rows in {@code [scrollY / rowH, (scrollY + gridH) / rowH]} are drawn. For each
 * row a binary search on programme start times finds the first block that can intersect
 * the visible window (plus a short walk back for programmes that started earlier but are
 * still running). Nothing ever iterates all ~9,500 programmes.</p>
 *
 * <h3>Focus / scroll model</h3>
 * <p>Focus is a {@code (row, programmeIndex)} pair, independent of Android's view focus.
 * LEFT/RIGHT step the programme index inside the current channel, clamp at the day edges
 * and scroll the timeline as needed ({@link #ensureProgramVisible}).</p>
 *
 * <p>UP/DOWN change only the focused row and the vertical scroll. The horizontal scroll
 * offset is never touched by a vertical move. The programme focused in the new row is the
 * one covering the <em>focused time</em> - the previously focused programme's start time,
 * clamped to the visible window {@code [timeAtScrollX, timeAtScrollX + 4 h]} - with the
 * nearest programme used when the channel has no programme at that instant (gap or end of
 * its EPG). The focused time is remembered for the whole vertical run, so repeated DOWN
 * presses stay on the same time slice even across gaps. Because the focused time lies
 * inside the visible window, the focused block is always at least partly visible and no
 * horizontal correction is ever required; see {@link #moveVertical}.</p>
 *
 * <p>Explicit jumps and LEFT/RIGHT smooth-scroll only enough to reveal the focused block and
 * row, and a fling/drag that ends with the focused block off-screen snaps it back.</p>
 *
 * <p>Border cases: programmes starting before {@code dayStartMs} are clipped to x = 0;
 * programmes running past the day end are clipped to the right edge; channels without
 * programmes draw a dim "—" cell; an empty channel list draws a centred placeholder.</p>
 *
 * <p>All colours come from {@link #setPalette}; text measurement and ellipsising are
 * cached per programme/channel, so {@code onDraw} does no meaningful allocation.</p>
 *
 * <h3>Call order</h3>
 * <p>{@code setListener}, {@code setLogoProvider}, {@code setChannels} and
 * {@code setDayStartMs} may be called in any order; geometry is deferred until the view has
 * a size. Typical usage is {@code setChannels(list); setDayStartMs(midnight);
 * scrollToNow();}. {@code setChannels} auto-positions only on the first non-empty delivery
 * (or after a day change), so later refreshes keep the current scroll position.</p>
 */
public final class EpgGridView extends View {

    public interface Listener {
        /** Called whenever the focused programme changes (also on channel-only focus). */
        void onProgramFocused(EpgChannel channel, EpgProgram program);

        /** OK/ENTER on a programme. */
        void onProgramSelected(EpgChannel channel, EpgProgram program);
    }

    public interface LogoProvider {
        Bitmap logoFor(EpgChannel channel);
    }

    // ------------------------------------------------------------------------------------
    // Layout constants (dp unless stated otherwise)
    // ------------------------------------------------------------------------------------

    private static final int VISIBLE_MINUTES = 240;           // 4 hours across the grid
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long SLOT_MS = 30L * 60L * 1000L;    // ruler tick / label spacing
    private static final int SLOT_COUNT = 49;                 // 00:00 .. 24:00 inclusive

    private static final int CHANNEL_COLUMN_DP = 244;
    private static final int RULER_HEIGHT_DP = 40;
    private static final int ROW_HEIGHT_DP = 52;

    private static final int BLOCK_RADIUS_DP = 6;
    private static final int BLOCK_GAP_DP = 2;                // per side -> 2 dp between blocks
    private static final int BLOCK_INSET_DP = 3;              // top/bottom inside the row
    private static final int BLOCK_TEXT_PAD_DP = 10;
    private static final int FOCUS_STROKE_DP = 2;

    private static final int CELL_PAD_DP = 12;
    private static final int NUMBER_COLUMN_DP = 42;
    private static final int LOGO_WIDTH_DP = 48;
    private static final int LOGO_HEIGHT_DP = 32;
    private static final int LOGO_GAP_DP = 8;
    private static final int TITLE_GAP_DP = 9;
    private static final int LOCK_CHIP_DP = 20;
    private static final int LOCK_GLYPH_DP = 12;

    private static final int NOW_LINE_DP = 2;
    private static final int PROGRESS_HEIGHT_DP = 3;
    private static final int PROGRESS_BOTTOM_DP = 5;

    // Baselines measured from the top of the programme block (52 dp row - 2 x 3 dp inset
    // = 46 dp). Barlow Condensed reports a full 1.0 em ascent, so hanging the time off the
    // title's descent used to put its baseline at ~37 dp - on top of the 38 dp progress bar.
    private static final float TITLE_BASELINE_DP = 15f;
    private static final float TIME_BASELINE_DP = 30f;

    // D-pad focus moves are one short, decisive step: long enough to read as motion,
    // short enough that repeated presses never feel like they are chasing the ring.
    private static final int SCROLL_DURATION_MS = 140;
    private static final long LIVE_TICK_MS = 60_000L;

    private static final float TITLE_SP = 14f;
    private static final float TIME_SP = 10.5f;
    private static final float NUMBER_SP = 21f;
    private static final float CHANNEL_TITLE_SP = 13.5f;
    private static final float LOGO_NUMBER_SP = 12f;
    private static final float RULER_SP = 12f;
    private static final float DAY_SP = 12.5f;
    private static final float EMPTY_SP = 16f;

    private final String emptyMessage;
    private final String nowLabel;
    private final String dayLabel;
    private static final String DASH = "—";
    private static final String ELLIPSIS = "…";

    // Default dark palette (mirrors the app's colors.xml so the grid fits the rest of the UI).
    private int accentColor = 0xFFE6007E;
    private int accentBrightColor = 0xFFFF3D9A;
    private int backgroundColor = 0xFF07080B;
    private int raisedColor = 0xFF0B0D11;
    private int surfaceColor = 0xFF12151B;
    private int textPrimaryColor = 0xFFF4F6F9;
    private int textDimColor = 0xFFAEB6C2;

    // Derived colours, recomputed by applyPalette().
    private int rowEvenColor;
    private int rowAltColor;
    private int channelBackgroundColor;
    private int rulerBackgroundColor;
    private int dividerColor;
    private int gridLineColor;
    private int blockFillColor;
    private int blockLiveFillColor;
    private int blockPastFillColor;
    private int blockFocusFillColor;
    private int channelFocusFillColor;
    private int placeholderColor;
    private int progressTrackColor;
    private int borderColor;
    private int textPastColor;

    private final float density;

    private final List<EpgChannel> channels = new ArrayList<>();
    private Set<Integer> subscriptions = Collections.emptySet();
    private boolean subscriptionKnown;

    private Listener listener;
    private LogoProvider logoProvider;

    private long dayStartMs;
    private long nowOverrideMs = Long.MIN_VALUE;
    private long nowOverrideRealtimeMs;
    private boolean liveUpdates;
    private boolean pendingInitialPosition = true;
    private String zoneId;

    private final OverScroller scroller;
    private final int touchSlop;
    private final int minimumFlingVelocity;
    private final int maximumFlingVelocity;

    private final Paint fillPaint;
    private final Paint strokePaint;
    private final Paint hairlinePaint;
    private final Paint logoPaint;
    private final Paint titlePaint;
    private final Paint timePaint;
    private final Paint channelNumberPaint;
    private final Paint channelTitlePaint;
    private final Paint logoNumberPaint;
    private final Paint rulerPaint;
    private final Paint rulerMinorPaint;
    private final Paint dayPaint;
    private final Paint dayLabelPaint;
    private final Paint nowPaint;
    private final Paint emptyPaint;
    private final Paint dashPaint;
    private final Drawable lockGlyph;
    private Bitmap lockBitmap;

    private final SimpleDateFormat timeFormatter;
    private final SimpleDateFormat dayFormatter;
    private final String[] rulerLabels = new String[SLOT_COUNT];
    private String cornerLabel = "";

    // Geometry, all px, recomputed in updateMetrics().
    private int viewContentW;
    private int viewContentH;
    private int channelColumnW;
    private int rulerHeightPx;
    private int rowHeightPx;
    private int gridW;
    private int gridH;
    private float pxPerMinute;
    private float pxPerMs;
    private float contentWidthPx;
    private float contentHeightPx;
    private int maxScrollX;
    private int maxScrollY;

    // Signature used to skip redundant geometry passes (size/padding unchanged).
    private int metricsWidth = -1;
    private int metricsHeight = -1;
    private int metricsPadLeft;
    private int metricsPadTop;
    private int metricsPadRight;
    private int metricsPadBottom;

    // Visible-window times, refreshed once per frame.
    private long visibleStartMs;
    private long visibleEndMs;

    private int scrollXPx;
    private int scrollYPx;

    // Focused cell; row -1 means "nothing focused".
    private int focusRow = -1;
    private int focusProgramIndex = -1;

    // Focused time (epoch ms) for the current UP/DOWN run. While valid, every vertical step
    // selects the programme covering this one time slice, so holding DOWN stays on the same
    // part of the timeline. Cleared by horizontal navigation, touch, or explicit re-positioning.
    private long verticalAnchorMs;
    private boolean verticalAnchorValid;

    // Drawing helpers, reused to keep onDraw allocation-free.
    private final RectF rectF = new RectF();
    private final RectF blockRect = new RectF();
    private final RectF logoRect = new RectF();

    private float blockRadiusPx;
    private float blockGapPx;
    private float blockInsetPx;
    private float blockTextPadPx;
    private float strokeWidthPx;
    private float strokeInsetPx;
    private float titleBaselineOffset;
    private float timeBaselineOffset;
    private float progressHeightPx;
    private float progressBottomPx;
    private float nowLineHalfPx;
    private float tickHeightPx;
    private float hairlineWidthPx;
    private float cellPadPx;
    private float numberColumnPx;
    private float logoWidthPx;
    private float logoHeightPx;
    private float logoGapPx;
    private float titleGapPx;
    private float dashHalfWidthPx;
    private float dashBaselineOffset;
    private float lockChipPx;
    private float lockGlyphPx;

    private final LruCache<EpgProgram, ProgramText> programTexts = new LruCache<>(4096);
    private final LruCache<EpgChannel, ChannelText> channelTexts = new LruCache<>(256);

    private VelocityTracker velocityTracker;
    private float lastTouchX;
    private float lastTouchY;
    private float downTouchX;
    private float downTouchY;
    private boolean dragging;
    private boolean userTouching;
    private boolean scrollTickerPosted;

    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (!liveUpdates || !isAttachedToWindow()) {
                return;
            }
            postInvalidateOnAnimation();
            postDelayed(this, LIVE_TICK_MS);
        }
    };

    private final Runnable scrollTicker = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) {
                scrollTickerPosted = false;
                return;
            }
            if (!scroller.computeScrollOffset()) {
                scrollTickerPosted = false;
                onScrollSettled();
                return;
            }
            scrollXPx = scroller.getCurrX();
            scrollYPx = scroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
            postOnAnimation(this);
        }
    };

    public EpgGridView(Context context) {
        super(context);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density > 0f ? metrics.density : 1f;

        scroller = new OverScroller(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);

        hairlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hairlinePaint.setStyle(Paint.Style.FILL);

        logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        logoPaint.setStyle(Paint.Style.FILL);

        titlePaint = createTextPaint(TITLE_SP, local.mintv.ui.Tv.display(getContext()));
        timePaint = createTextPaint(TIME_SP, Typeface.DEFAULT);
        channelNumberPaint = createTextPaint(NUMBER_SP,
                local.mintv.ui.Tv.displayBold(getContext()));
        channelTitlePaint = createTextPaint(CHANNEL_TITLE_SP,
                local.mintv.ui.Tv.displayMedium(getContext()));
        logoNumberPaint = createTextPaint(LOGO_NUMBER_SP,
                local.mintv.ui.Tv.displayBold(getContext()));
        rulerPaint = createTextPaint(RULER_SP, Typeface.DEFAULT);
        rulerMinorPaint = createTextPaint(RULER_SP - 1f, Typeface.DEFAULT);
        dayPaint = createTextPaint(10.5f, local.mintv.ui.Tv.displayMedium(getContext()));
        dayLabelPaint = createTextPaint(9f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        nowPaint = createTextPaint(11f, local.mintv.ui.Tv.displayBold(getContext()));
        emptyPaint = createTextPaint(EMPTY_SP, Typeface.DEFAULT);
        dashPaint = createTextPaint(CHANNEL_TITLE_SP,
                local.mintv.ui.Tv.displayMedium(getContext()));
        Drawable glyph = getContext().getDrawable(R.drawable.ic_lock);
        lockGlyph = glyph == null ? null : glyph.mutate();

        blockRadiusPx = dp(BLOCK_RADIUS_DP);
        blockGapPx = dp(BLOCK_GAP_DP);
        blockInsetPx = dp(BLOCK_INSET_DP);
        blockTextPadPx = dp(BLOCK_TEXT_PAD_DP);
        strokeWidthPx = dp(FOCUS_STROKE_DP);
        strokePaint.setStrokeWidth(strokeWidthPx);
        strokeInsetPx = strokeWidthPx / 2f;
        progressHeightPx = dp(PROGRESS_HEIGHT_DP);
        progressBottomPx = dp(PROGRESS_BOTTOM_DP);
        nowLineHalfPx = dp(NOW_LINE_DP) / 2f;
        tickHeightPx = dp(8f);
        hairlineWidthPx = Math.max(1f, dp(0.5f));
        cellPadPx = dp(CELL_PAD_DP);
        numberColumnPx = dp(NUMBER_COLUMN_DP);
        logoWidthPx = dp(LOGO_WIDTH_DP);
        logoHeightPx = dp(LOGO_HEIGHT_DP);
        logoGapPx = dp(LOGO_GAP_DP);
        titleGapPx = dp(TITLE_GAP_DP);

        titleBaselineOffset = dp(TITLE_BASELINE_DP);
        // Title ink 5.2-17.8 dp, time ink 22.7-32.1 dp, progress bar 38-41 dp: ~5 dp of
        // air between title and time and ~6 dp between the time and the bar in every cell.
        timeBaselineOffset = dp(TIME_BASELINE_DP);
        dashHalfWidthPx = dashPaint.measureText(DASH) / 2f;
        dashBaselineOffset = -(dashPaint.ascent() + dashPaint.descent()) / 2f;
        lockChipPx = dp(LOCK_CHIP_DP);
        lockGlyphPx = dp(LOCK_GLYPH_DP);

        emptyMessage = getContext().getString(R.string.guide_empty);
        nowLabel = getContext().getString(R.string.guide_now).toUpperCase(Locale.getDefault());
        dayLabel = getContext().getString(R.string.guide_corner_label)
                .toUpperCase(Locale.getDefault());
        timeFormatter = new SimpleDateFormat("HH:mm", Locale.US);
        dayFormatter = new SimpleDateFormat("d. MMM", Locale.getDefault());
        timeFormatter.setTimeZone(TimeZone.getDefault());
        dayFormatter.setTimeZone(TimeZone.getDefault());
        zoneId = TimeZone.getDefault().getID();

        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        dayStartMs = midnight.getTimeInMillis();

        applyPalette();
        rebuildRulerLabels();

        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    public void setChannels(List<EpgChannel> newChannels) {
        EpgChannel oldFocusedChannel = focusedChannel();
        EpgProgram oldFocusedProgram = focusedProgram();
        int oldNumber = oldFocusedChannel != null ? oldFocusedChannel.number : Integer.MIN_VALUE;
        long anchor = oldFocusedProgram != null ? oldFocusedProgram.startMs : currentNowMs();
        boolean wasEmpty = channels.isEmpty();
        // A new line-up invalidates the remembered vertical time slice.
        verticalAnchorValid = false;

        channels.clear();
        if (newChannels != null) {
            for (EpgChannel channel : newChannels) {
                if (channel != null) {
                    channels.add(channel);
                }
            }
        }

        programTexts.evictAll();
        channelTexts.evictAll();
        focusRow = -1;
        focusProgramIndex = -1;

        if (!channels.isEmpty()) {
            int row = 0;
            if (oldNumber != Integer.MIN_VALUE) {
                for (int i = 0; i < channels.size(); i++) {
                    if (channels.get(i).number == oldNumber) {
                        row = i;
                        break;
                    }
                }
            }
            setFocus(row, indexAt(channels.get(row), anchor), false);
        }

        updateGeometry();
        clampScroll();

        boolean firstData = wasEmpty && !channels.isEmpty();
        if (firstData || pendingInitialPosition) {
            pendingInitialPosition = true;
            if (gridW > 0 && gridH > 0) {
                // Silent here: the single notification below covers both the initial
                // selection and the "scroll to now" re-selection.
                positionAtNow(false, false);
            }
        }
        if (gridW > 0 && gridH > 0 && focusRow >= 0) {
            // Keeps the focused block visible if a refresh changed the line-up.
            ensureProgramVisible(false, true);
        }
        if (!channels.isEmpty()) {
            notifyFocusChanged();
        }
        postInvalidateOnAnimation();
    }

    /**
     * Marks the channels the signed-in account cannot watch. A {@code null} set or
     * {@code known == false} means the subscription has not loaded yet: nothing is locked.
     */
    public void setSubscriptionState(Set<Integer> numbers, boolean known) {
        subscriptions = numbers == null ? Collections.emptySet() : numbers;
        subscriptionKnown = known;
        channelTexts.evictAll();
        postInvalidateOnAnimation();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null && focusedChannel() != null) {
            notifyFocusChanged();
        }
    }

    public void setLogoProvider(LogoProvider provider) {
        this.logoProvider = provider;
        postInvalidateOnAnimation();
    }

    /** Local midnight of the displayed day, epoch millis. */
    public void setDayStartMs(long dayStartMs) {
        if (this.dayStartMs == dayStartMs) {
            postInvalidateOnAnimation();
            return;
        }
        this.dayStartMs = dayStartMs;
        scrollXPx = 0;
        verticalAnchorValid = false;
        rebuildRulerLabels();
        programTexts.evictAll();
        updateGeometry();
        clampScroll();
        if (channels.isEmpty()) {
            pendingInitialPosition = true;
            postInvalidateOnAnimation();
            return;
        }
        pendingInitialPosition = true;
        if (gridW > 0 && gridH > 0) {
            positionAtNow(false, true);
        } else {
            postInvalidateOnAnimation();
        }
    }

    /** Vertical accent "now" line position; defaults to System.currentTimeMillis(). */
    public void setNowMs(long nowMs) {
        // The value seeds the clock; afterwards it keeps advancing in real time, so the
        // minute live tick moves the now line and the live progress bars. Passing a
        // non-positive value falls back to the plain system clock.
        if (nowMs > 0L) {
            nowOverrideMs = nowMs;
            nowOverrideRealtimeMs = SystemClock.elapsedRealtime();
        } else {
            nowOverrideMs = Long.MIN_VALUE;
        }
        postInvalidateOnAnimation();
    }

    /** Scroll so that the current time is visible, and focus the channel currently playing if known. */
    public void scrollToNow() {
        if (channels.isEmpty()) {
            return;
        }
        positionAtNow(true, true);
    }

    public void focusChannel(int channelNumber) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number == channelNumber) {
                verticalAnchorValid = false;
                // A channel jump lands on what is on now, not on whatever time the
                // previous row happened to be focused on.
                setFocus(i, indexAt(channels.get(i), currentNowMs()), false);
                ensureProgramVisible(true, false);
                // Explicit focus request: always tell the listener, even when the
                // selection happens to be the same programme as before.
                notifyFocusChanged();
                return;
            }
        }
    }

    /** True when LEFT cannot move to an earlier programme, so the shell may take focus. */
    public boolean isTopRowFocused() {
        return hasFocus() && focusRow == 0;
    }

    public boolean isFirstProgramFocused() {
        return focusProgramIndex <= 0;
    }

    /** Starts/stops the once-per-minute internal invalidation used for the now line. */
    public void setLiveUpdates(boolean enabled) {
        if (liveUpdates == enabled) {
            return;
        }
        liveUpdates = enabled;
        removeCallbacks(liveTick);
        if (enabled && isAttachedToWindow()) {
            postDelayed(liveTick, LIVE_TICK_MS);
        }
    }

    /** Optional theming hook; all arguments are ARGB colours. */
    public void setPalette(int accent, int background, int surface, int textPrimary, int textDim) {
        setPalette(accent, accent, background, background, surface, textPrimary, textDim);
    }

    /** Full palette: brand, bright brand, ground, raised ruler, blocks, text. */
    public void setPalette(int accent, int accentBright, int background, int raised,
                           int surface, int textPrimary, int textDim) {
        accentColor = accent;
        accentBrightColor = accentBright;
        backgroundColor = background;
        raisedColor = raised;
        surfaceColor = surface;
        textPrimaryColor = textPrimary;
        textDimColor = textDim;
        applyPalette();
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------------------------
    // Measurement / geometry
    // ------------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMetrics(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Padding can change without a size change (e.g. bars overlaid by the activity),
        // so geometry is refreshed here too; updateMetrics() early-returns when unchanged.
        updateMetrics(getWidth(), getHeight());
    }

    private void updateMetrics(int w, int h) {
        int padLeft = getPaddingLeft();
        int padTop = getPaddingTop();
        int padRight = getPaddingRight();
        int padBottom = getPaddingBottom();
        if (w == metricsWidth && h == metricsHeight
                && padLeft == metricsPadLeft && padTop == metricsPadTop
                && padRight == metricsPadRight && padBottom == metricsPadBottom) {
            return;
        }
        metricsWidth = w;
        metricsHeight = h;
        metricsPadLeft = padLeft;
        metricsPadTop = padTop;
        metricsPadRight = padRight;
        metricsPadBottom = padBottom;

        viewContentW = Math.max(0, w - padLeft - padRight);
        viewContentH = Math.max(0, h - padTop - padBottom);
        channelColumnW = Math.min(dpInt(CHANNEL_COLUMN_DP), (int) (viewContentW * 0.45f));
        rulerHeightPx = dpInt(RULER_HEIGHT_DP);
        rowHeightPx = Math.max(1, dpInt(ROW_HEIGHT_DP));
        gridW = Math.max(0, viewContentW - channelColumnW);
        gridH = Math.max(0, viewContentH - rulerHeightPx);

        if (gridW <= 0 || gridH <= 0) {
            pxPerMinute = 0f;
            pxPerMs = 0f;
            contentWidthPx = 0f;
            contentHeightPx = 0f;
            maxScrollX = 0;
            maxScrollY = 0;
            return;
        }

        float oldPxPerMinute = pxPerMinute;
        int oldScrollX = scrollXPx;

        pxPerMinute = gridW / (float) VISIBLE_MINUTES;
        pxPerMs = pxPerMinute / 60000f;

        if (oldPxPerMinute > 0f) {
            // Keep the same time at the left edge when the view is resized.
            float oldPxPerMs = oldPxPerMinute / 60000f;
            long leftTime = dayStartMs + (long) (oldScrollX / oldPxPerMs);
            scrollXPx = Math.round((leftTime - dayStartMs) * pxPerMs);
        } else {
            scrollXPx = 0;
        }

        updateGeometry();
        programTexts.evictAll();
        channelTexts.evictAll();

        if (pendingInitialPosition && !channels.isEmpty()) {
            pendingInitialPosition = false;
            positionAtNow(false, true);
        } else {
            if (focusRow >= 0) {
                ensureProgramVisible(false, true);
            }
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    private void updateGeometry() {
        if (gridW <= 0 || gridH <= 0) {
            contentWidthPx = 0f;
            contentHeightPx = 0f;
            maxScrollX = 0;
            maxScrollY = 0;
            return;
        }
        pxPerMinute = gridW / (float) VISIBLE_MINUTES;
        pxPerMs = pxPerMinute / 60000f;
        contentWidthPx = DAY_MS * pxPerMs;
        contentHeightPx = channels.size() * (float) rowHeightPx;
        maxScrollX = Math.max(0, Math.round(contentWidthPx) - gridW);
        maxScrollY = Math.max(0, Math.round(contentHeightPx) - gridH);
    }

    private void clampScroll() {
        scrollXPx = clampX(scrollXPx);
        scrollYPx = clampY(scrollYPx);
    }

    private int clampX(int x) {
        if (maxScrollX <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(x, maxScrollX));
    }

    private int clampY(int y) {
        if (maxScrollY <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(y, maxScrollY));
    }

    private float timeToX(long timeMs) {
        return (timeMs - dayStartMs) * pxPerMs;
    }

    private float dp(float value) {
        return value * density;
    }

    private int dpInt(float value) {
        return Math.round(value * density);
    }

    private Paint createTextPaint(float sp, Typeface typeface) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        paint.setTypeface(typeface);
        return paint;
    }

    private void applyPalette() {
        rowEvenColor = backgroundColor;
        rowAltColor = blend(backgroundColor, surfaceColor, 0.22f);
        channelBackgroundColor = blend(backgroundColor, surfaceColor, 0.48f);
        rulerBackgroundColor = raisedColor;
        dividerColor = blend(backgroundColor, 0xFFFFFFFF, 0.075f);
        gridLineColor = blend(backgroundColor, 0xFFFFFFFF, 0.045f);
        blockFillColor = blend(backgroundColor, surfaceColor, 0.72f);
        blockLiveFillColor = blend(blockFillColor, accentColor, 0.12f);
        blockPastFillColor = blend(backgroundColor, surfaceColor, 0.30f);
        blockFocusFillColor = blend(surfaceColor, 0xFFFFFFFF, 0.16f);
        channelFocusFillColor = blend(surfaceColor, 0xFFFFFFFF, 0.08f);
        placeholderColor = blend(backgroundColor, 0xFFFFFFFF, 0.06f);
        progressTrackColor = blend(backgroundColor, 0xFFFFFFFF, 0.18f);
        borderColor = blend(backgroundColor, 0xFFFFFFFF, 0.11f);
        textPastColor = blend(textDimColor, backgroundColor, 0.28f);

        titlePaint.setColor(textPrimaryColor);
        timePaint.setColor(textDimColor);
        channelNumberPaint.setColor(accentBrightColor);
        channelTitlePaint.setColor(textPrimaryColor);
        logoNumberPaint.setColor(textDimColor);
        rulerPaint.setColor(textDimColor);
        rulerMinorPaint.setColor(blend(textDimColor, backgroundColor, 0.42f));
        dayPaint.setColor(textPrimaryColor);
        dayLabelPaint.setColor(accentBrightColor);
        nowPaint.setColor(0xFFFFFFFF);
        emptyPaint.setColor(textDimColor);
        dashPaint.setColor(blend(surfaceColor, textDimColor, 0.55f));
        if (lockGlyph != null) {
            lockGlyph.setTint(textDimColor);
        }
        rebuildLockBitmap();
    }

    /** Bakes the tiny vector lock once so the grid's onDraw never inflates a drawable. */
    private void rebuildLockBitmap() {
        if (lockGlyph == null || lockGlyphPx <= 0f) {
            return;
        }
        int size = Math.max(1, Math.round(lockGlyphPx));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        lockGlyph.setBounds(0, 0, size, size);
        lockGlyph.draw(canvas);
        Bitmap previous = lockBitmap;
        lockBitmap = bitmap;
        if (previous != null && previous != bitmap) {
            previous.recycle();
        }
    }

    private void rebuildRulerLabels() {
        timeFormatter.setTimeZone(TimeZone.getDefault());
        dayFormatter.setTimeZone(TimeZone.getDefault());
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            rulerLabels[slot] = timeFormatter.format(new Date(dayStartMs + slot * SLOT_MS));
        }
        cornerLabel = dayFormatter.format(new Date(dayStartMs));
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        canvas.drawColor(backgroundColor);

        if (channels.isEmpty() || viewContentW <= 0 || viewContentH <= 0
                || gridW <= 0 || gridH <= 0 || pxPerMs <= 0f) {
            drawEmptyState(canvas, w, h);
            return;
        }

        final long now = currentNowMs();
        clampScroll();

        visibleStartMs = dayStartMs + (long) (scrollXPx / pxPerMs);
        visibleEndMs = visibleStartMs + (long) (gridW / pxPerMs);
        if (visibleStartMs < dayStartMs) {
            visibleStartMs = dayStartMs;
        }
        long dayEndMs = dayStartMs + DAY_MS;
        if (visibleEndMs > dayEndMs) {
            visibleEndMs = dayEndMs;
        }

        final int firstRow = Math.max(0, scrollYPx / rowHeightPx);
        final int lastRow = Math.min(channels.size() - 1,
                (scrollYPx + gridH - 1) / rowHeightPx);

        canvas.save();
        // Honour padding: an activity can reserve room for its own bars.
        canvas.translate(getPaddingLeft(), getPaddingTop());

        canvas.save();
        canvas.clipRect(channelColumnW, rulerHeightPx, viewContentW, viewContentH);
        canvas.translate(channelColumnW - scrollXPx, rulerHeightPx - scrollYPx);
        drawGridRows(canvas, firstRow, lastRow);
        drawTimeGridLines(canvas);
        drawPrograms(canvas, firstRow, lastRow, now);
        canvas.restore();

        drawRuler(canvas, now);
        drawChannelColumn(canvas, firstRow, lastRow);
        drawCorner(canvas);

        hairlinePaint.setColor(borderColor);
        canvas.drawRect(0f, rulerHeightPx - hairlineWidthPx,
                viewContentW, rulerHeightPx, hairlinePaint);
        canvas.drawRect(channelColumnW - hairlineWidthPx, 0f,
                channelColumnW, viewContentH, hairlinePaint);

        drawNowLine(canvas, now);

        canvas.restore();
    }

    private void drawGridRows(Canvas canvas, int firstRow, int lastRow) {
        for (int row = firstRow; row <= lastRow; row++) {
            float top = row * (float) rowHeightPx;
            fillPaint.setColor((row & 1) == 0 ? rowEvenColor : rowAltColor);
            rectF.set(0f, top, contentWidthPx, top + rowHeightPx);
            canvas.drawRect(rectF, fillPaint);
        }
        hairlinePaint.setColor(dividerColor);
        for (int row = firstRow; row <= lastRow; row++) {
            float bottom = (row + 1) * (float) rowHeightPx;
            canvas.drawRect(0f, bottom - hairlineWidthPx, contentWidthPx, bottom, hairlinePaint);
        }
    }

    private void drawTimeGridLines(Canvas canvas) {
        fillPaint.setColor(gridLineColor);
        int firstSlot = Math.max(0,
                (int) Math.floor((visibleStartMs - dayStartMs) / (double) SLOT_MS));
        int lastSlot = Math.min(SLOT_COUNT,
                (int) Math.ceil((visibleEndMs - dayStartMs) / (double) SLOT_MS));
        float columnHeight = contentHeightPx;
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            float x = timeToX(dayStartMs + slot * SLOT_MS);
            canvas.drawRect(x, 0f, x + hairlineWidthPx, columnHeight, fillPaint);
        }
    }

    private void drawPrograms(Canvas canvas, int firstRow, int lastRow, long now) {
        for (int row = firstRow; row <= lastRow; row++) {
            EpgChannel channel = channels.get(row);
            List<EpgProgram> programs = channel.programs();
            float rowTop = row * (float) rowHeightPx;

            if (programs.isEmpty()) {
                drawDashCell(canvas, rowTop);
                continue;
            }

            boolean rowFocused = row == focusRow;
            int index = firstIndexEndingAfter(programs, visibleStartMs);
            for (; index < programs.size(); index++) {
                EpgProgram program = programs.get(index);
                if (program.startMs >= visibleEndMs) {
                    break;
                }

                float x1 = timeToX(program.startMs);
                float x2 = timeToX(program.endMs);
                if (x2 <= 0f || x1 >= contentWidthPx) {
                    continue;
                }
                x1 = Math.max(0f, x1);
                x2 = Math.min(contentWidthPx, x2);
                if (x2 - x1 < 1f) {
                    continue;
                }

                boolean focused = rowFocused && index == focusProgramIndex;
                drawProgramBlock(canvas, program, x1, x2, rowTop, focused, now);
            }
        }
    }

    private void drawProgramBlock(Canvas canvas, EpgProgram program, float x1, float x2,
                                  float rowTop, boolean focused, long now) {
        float left = x1 + blockGapPx;
        float right = x2 - blockGapPx;
        if (right <= left) {
            left = x1;
            right = x2;
        }
        float top = rowTop + blockInsetPx;
        float bottom = rowTop + rowHeightPx - blockInsetPx;

        boolean live = program.isLive(now);
        boolean past = program.endMs <= now;

        blockRect.set(left, top, right, bottom);
        fillPaint.setColor(focused
                ? blockFocusFillColor
                : live ? blockLiveFillColor : past ? blockPastFillColor : blockFillColor);
        canvas.drawRoundRect(blockRect, blockRadiusPx, blockRadiusPx, fillPaint);

        float textLeft = left + blockTextPadPx;
        float textRight = right - blockTextPadPx;
        // A block that runs past the right edge of the visible window is clipped by the
        // grid canvas. Ellipsize against the visible part as well, so that the last column
        // ends with an ellipsis instead of a glyph cut in half by the grid border.
        float visibleRight = scrollXPx + gridW;
        if (textRight > visibleRight - dp(4f)) {
            textRight = visibleRight - dp(4f);
        }
        float available = textRight - textLeft;
        if (available > dp(18f)) {
            ProgramText text = programText(program, available);
            titlePaint.setColor(focused ? 0xFFFFFFFF
                    : past ? textPastColor : textPrimaryColor);
            timePaint.setColor(focused ? 0xFFE9D4DF
                    : past ? blend(textPastColor, backgroundColor, 0.2f) : textDimColor);
            // Canvas text is never allowed outside the block that owns it: the ellipsis
            // math keeps it inside, the clip guarantees it even if a glyph overhangs its
            // advance or the font falls back to a wider face.
            canvas.save();
            canvas.clipRect(left, top, right, bottom);
            canvas.drawText(text.title, textLeft, top + titleBaselineOffset, titlePaint);
            if (text.timeWidthPx <= available) {
                canvas.drawText(text.time, textLeft, top + timeBaselineOffset, timePaint);
            }
            canvas.restore();
        }

        if (live && right - left > dp(14f)) {
            float barLeft = textLeft;
            float barRight = right - blockTextPadPx;
            float barTop = bottom - progressBottomPx - progressHeightPx;
            fillPaint.setColor(progressTrackColor);
            rectF.set(barLeft, barTop, barRight, barTop + progressHeightPx);
            canvas.drawRoundRect(rectF, progressHeightPx / 2f, progressHeightPx / 2f, fillPaint);
            float filled = (barRight - barLeft) * program.progress(now);
            if (filled > 0.f) {
                fillPaint.setColor(accentColor);
                rectF.set(barLeft, barTop, barLeft + filled, barTop + progressHeightPx);
                canvas.drawRoundRect(rectF, progressHeightPx / 2f, progressHeightPx / 2f, fillPaint);
            }
        }

        if (focused) {
            // White ring: the native TV focus language. Magenta stays reserved for on-air.
            strokePaint.setColor(0xFFFFFFFF);
            rectF.set(blockRect);
            rectF.inset(strokeInsetPx, strokeInsetPx);
            float radius = Math.max(0f, blockRadiusPx - strokeInsetPx);
            canvas.drawRoundRect(rectF, radius, radius, strokePaint);
        }
    }

    private void drawDashCell(Canvas canvas, float rowTop) {
        float centerX = scrollXPx + gridW / 2f;
        float baseline = rowTop + rowHeightPx / 2f + dashBaselineOffset;
        canvas.drawText(DASH, centerX - dashHalfWidthPx, baseline, dashPaint);
    }

    private void drawRuler(Canvas canvas, long now) {
        fillPaint.setColor(rulerBackgroundColor);
        canvas.drawRect(0f, 0f, viewContentW, rulerHeightPx, fillPaint);

        canvas.save();
        canvas.clipRect(channelColumnW, 0, viewContentW, rulerHeightPx);
        canvas.translate(channelColumnW - scrollXPx, 0f);

        int firstSlot = Math.max(0,
                (int) Math.floor((visibleStartMs - dayStartMs) / (double) SLOT_MS));
        int lastSlot = Math.min(SLOT_COUNT - 1,
                (int) Math.ceil((visibleEndMs - dayStartMs) / (double) SLOT_MS));

        hairlinePaint.setColor(dividerColor);
        float labelBaseline = rulerHeightPx - dp(10f);
        boolean hasNow = now >= dayStartMs && now < dayStartMs + DAY_MS;
        float nowVisibleX = hasNow ? timeToX(now) - scrollXPx : -1f;
        String nowText = hasNow ? nowLabel : "";
        float nowWidth = hasNow ? nowPaint.measureText(nowText) + dp(16f) : 0f;
        float nowVisibleLeft = hasNow ? Math.max(dp(2f),
                Math.min(nowVisibleX - nowWidth / 2f, gridW - nowWidth - dp(2f))) : -1f;
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            long slotMs = dayStartMs + slot * SLOT_MS;
            float x = timeToX(slotMs);
            boolean hour = (slot & 1) == 0;
            hairlinePaint.setColor(hour ? dividerColor : blend(dividerColor, backgroundColor, 0.45f));
            float tickTop = rulerHeightPx - (hour ? dp(8f) : dp(5f));
            canvas.drawRect(x, tickTop, x + hairlineWidthPx, rulerHeightPx, hairlinePaint);
            String label = rulerLabels[slot];
            if (label != null) {
                Paint paint = hour ? rulerPaint : rulerMinorPaint;
                float labelWidth = paint.measureText(label);
                // Labels are drawn 6 dp to the right of their tick. A label whose glyphs
                // would cross either edge of the grid used to show up as a half letter
                // ("0" at the left, "19:3" at the right); skip it instead. The tick itself
                // remains, so the ruler still reads correctly.
                float labelVisibleX = x - scrollXPx + dp(6f);
                boolean clippedByGrid = labelVisibleX < 0f
                        || labelVisibleX + labelWidth > gridW;
                boolean hiddenByNow = hasNow
                        && labelVisibleX < nowVisibleLeft + nowWidth + dp(4f)
                        && labelVisibleX + labelWidth > nowVisibleLeft - dp(4f);
                if (!clippedByGrid && !hiddenByNow) {
                    canvas.drawText(label, x + dp(6f), labelBaseline, paint);
                }
            }
        }

        // The on-air tally: a solid magenta tab carries the current minute into the ruler.
        if (hasNow) {
            float tabLeft = scrollXPx + nowVisibleLeft;
            float tabTop = dp(2f);
            rectF.set(tabLeft, tabTop, tabLeft + nowWidth, tabTop + dp(21f));
            fillPaint.setColor(accentColor);
            canvas.drawRoundRect(rectF, dp(4f), dp(4f), fillPaint);
            float baseline = tabTop + (dp(21f) - (nowPaint.ascent() + nowPaint.descent())) / 2f;
            canvas.drawText(nowText, tabLeft + dp(8f), baseline, nowPaint);
        }

        canvas.restore();
    }

    private void drawChannelColumn(Canvas canvas, int firstRow, int lastRow) {
        fillPaint.setColor(channelBackgroundColor);
        canvas.drawRect(0f, rulerHeightPx, channelColumnW, viewContentH, fillPaint);

        canvas.save();
        canvas.clipRect(0, rulerHeightPx, channelColumnW, viewContentH);
        canvas.translate(0f, rulerHeightPx - scrollYPx);

        float titleLeft = cellPadPx + numberColumnPx + logoGapPx + logoWidthPx + titleGapPx;
        float titleWidth = Math.max(0f, channelColumnW - titleLeft - cellPadPx);

        for (int row = firstRow; row <= lastRow; row++) {
            float top = row * (float) rowHeightPx;
            boolean focused = row == focusRow;
            fillPaint.setColor(focused
                    ? channelFocusFillColor
                    : ((row & 1) == 0 ? rowEvenColor : rowAltColor));
            canvas.drawRect(0f, top, channelColumnW, top + rowHeightPx, fillPaint);

            if (focused) {
                fillPaint.setColor(accentColor);
                canvas.drawRect(0f, top + dp(4f),
                        Math.max(2f, dp(3f)), top + rowHeightPx - dp(4f), fillPaint);
            }

            drawChannelCell(canvas, channels.get(row), top, titleLeft, titleWidth);

            hairlinePaint.setColor(dividerColor);
            canvas.drawRect(0f, top + rowHeightPx - hairlineWidthPx,
                    channelColumnW, top + rowHeightPx, hairlinePaint);
        }

        canvas.restore();
    }

    private void drawChannelCell(Canvas canvas, EpgChannel channel, float rowTop,
                                 float titleLeft, float titleWidth) {
        float centerY = rowTop + rowHeightPx / 2f;
        boolean locked = isLocked(channel);
        ChannelText text = channelText(channel, titleWidth);
        boolean focused = ((int) (rowTop / rowHeightPx)) == focusRow;
        channelNumberPaint.setColor(focused ? accentBrightColor : textDimColor);

        float numberX = Math.max(cellPadPx,
                cellPadPx + numberColumnPx - text.numberWidthPx);
        float numberBaseline = centerY - (channelNumberPaint.ascent()
                + channelNumberPaint.descent()) / 2f;
        canvas.save();
        canvas.clipRect(cellPadPx, rowTop,
                cellPadPx + numberColumnPx + dp(1f), rowTop + rowHeightPx);
        canvas.drawText(text.number, numberX, numberBaseline, channelNumberPaint);
        canvas.restore();

        float logoLeft = cellPadPx + numberColumnPx + logoGapPx;
        float logoTop = centerY - logoHeightPx / 2f;
        rectF.set(logoLeft, logoTop, logoLeft + logoWidthPx, logoTop + logoHeightPx);
        fillPaint.setColor(placeholderColor);
        canvas.drawRoundRect(rectF, dp(5f), dp(5f), fillPaint);

        Bitmap logo = logoProvider != null ? logoProvider.logoFor(channel) : null;
        if (logo != null && !logo.isRecycled()
                && logo.getWidth() > 0 && logo.getHeight() > 0) {
            float inset = dp(3f);
            float boxLeft = rectF.left + inset;
            float boxTop = rectF.top + inset;
            float boxWidth = rectF.width() - inset * 2f;
            float boxHeight = rectF.height() - inset * 2f;
            float scale = Math.min(boxWidth / logo.getWidth(), boxHeight / logo.getHeight());
            float drawWidth = logo.getWidth() * scale;
            float drawHeight = logo.getHeight() * scale;
            float drawLeft = boxLeft + (boxWidth - drawWidth) / 2f;
            float drawTop = boxTop + (boxHeight - drawHeight) / 2f;
            logoRect.set(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight);
            canvas.drawBitmap(logo, null, logoRect, logoPaint);
        } else {
            float logoBaseline = centerY - (logoNumberPaint.ascent()
                    + logoNumberPaint.descent()) / 2f;
            canvas.save();
            canvas.clipRect(rectF);
            canvas.drawText(text.number, rectF.centerX() - text.logoNumberWidthPx,
                    logoBaseline, logoNumberPaint);
            canvas.restore();
        }

        if (locked) {
            drawLockChip(canvas, logoLeft + logoWidthPx + dp(2f), logoTop - dp(2f));
        }

        if (text.title.length() > 0) {
            channelTitlePaint.setColor(((int) (rowTop / rowHeightPx)) == focusRow
                    ? 0xFFFFFFFF : textPrimaryColor);
            float titleBaseline = centerY - (channelTitlePaint.ascent()
                    + channelTitlePaint.descent()) / 2f;
            // Keep the name out of the column hairline even when a glyph overhangs the
            // width returned by measureText().
            canvas.save();
            canvas.clipRect(titleLeft, rowTop, titleLeft + titleWidth, rowTop + rowHeightPx);
            canvas.drawText(text.title, titleLeft, titleBaseline, channelTitlePaint);
            canvas.restore();
        }
    }

    private boolean isLocked(EpgChannel channel) {
        return subscriptionKnown && channel != null && !channel.freeToAir
                && !subscriptions.contains(channel.number);
    }

    /** A quiet lock plate on the channel logo, mirroring the channel cards. */
    private void drawLockChip(Canvas canvas, float right, float top) {
        float left = right - lockChipPx;
        rectF.set(left, top, right, top + lockChipPx);
        fillPaint.setColor(placeholderColor);
        canvas.drawRoundRect(rectF, dp(4f), dp(4f), fillPaint);
        if (lockBitmap != null && !lockBitmap.isRecycled()) {
            float glyphLeft = left + (lockChipPx - lockGlyphPx) / 2f;
            float glyphTop = top + (lockChipPx - lockGlyphPx) / 2f;
            canvas.drawBitmap(lockBitmap, glyphLeft, glyphTop, logoPaint);
        }
    }

    private void drawCorner(Canvas canvas) {
        canvas.save();
        canvas.clipRect(0, 0, channelColumnW, rulerHeightPx);
        fillPaint.setColor(rulerBackgroundColor);
        canvas.drawRect(0f, 0f, channelColumnW, rulerHeightPx, fillPaint);

        float labelBaseline = rulerHeightPx / 2f
                - (dayPaint.ascent() + dayPaint.descent()) / 2f;
        canvas.drawText(dayLabel, cellPadPx, labelBaseline, dayPaint);
        canvas.restore();
    }

    private void drawNowLine(Canvas canvas, long now) {
        if (now < dayStartMs || now >= dayStartMs + DAY_MS) {
            return;
        }
        float x = channelColumnW + timeToX(now) - scrollXPx;
        if (x < channelColumnW || x > viewContentW) {
            return;
        }
        canvas.save();
        canvas.clipRect(channelColumnW, rulerHeightPx, viewContentW, viewContentH);
        fillPaint.setColor(accentColor);
        rectF.set(x - nowLineHalfPx, rulerHeightPx, x + nowLineHalfPx, viewContentH);
        canvas.drawRect(rectF, fillPaint);
        canvas.restore();
    }

    private void drawEmptyState(Canvas canvas, int w, int h) {
        float textWidth = emptyPaint.measureText(emptyMessage);
        float baseline = h / 2f - (emptyPaint.ascent() + emptyPaint.descent()) / 2f;
        canvas.drawText(emptyMessage, (w - textWidth) / 2f, baseline, emptyPaint);
        hairlinePaint.setColor(dividerColor);
        canvas.drawRect(w / 2f - dp(22f), baseline + dp(14f),
                w / 2f + dp(22f), baseline + dp(15f), hairlinePaint);
    }

    // ------------------------------------------------------------------------------------
    // Text caches
    // ------------------------------------------------------------------------------------

    private ProgramText programText(EpgProgram program, float maxWidth) {
        ProgramText cached = programTexts.get(program);
        if (cached != null && Math.abs(cached.maxWidth - maxWidth) < 1f) {
            return cached;
        }
        String raw = program.displayTitle(getContext())
                .replace('\n', ' ').replace('\r', ' ').trim();
        String title = ellipsize(titlePaint, raw, maxWidth);
        String time = timeFormatter.format(new Date(program.startMs))
                + "–" + timeFormatter.format(new Date(program.endMs));
        ProgramText created = new ProgramText(title, time, maxWidth,
                timePaint.measureText(time));
        programTexts.put(program, created);
        return created;
    }

    private ChannelText channelText(EpgChannel channel, float maxWidth) {
        ChannelText cached = channelTexts.get(channel);
        if (cached != null && Math.abs(cached.maxWidth - maxWidth) < 1f) {
            return cached;
        }
        String number = channel.number >= 0 ? String.valueOf(channel.number) : "–";
        String raw = channel.title == null ? "" : channel.title
                .replace('\n', ' ').replace('\r', ' ').trim();
        ChannelText created = new ChannelText(
                number,
                channelNumberPaint.measureText(number),
                logoNumberPaint.measureText(number),
                ellipsize(channelTitlePaint, raw, maxWidth),
                maxWidth);
        channelTexts.put(channel, created);
        return created;
    }

    private static String ellipsize(Paint paint, String text, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) {
            return "";
        }
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        float ellipsisWidth = paint.measureText(ELLIPSIS);
        if (ellipsisWidth > maxWidth) {
            return "";
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (paint.measureText(text, 0, mid) + ellipsisWidth <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        if (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) {
            low--;
        }
        if (low <= 0) {
            return ELLIPSIS;
        }
        String prefix = text.substring(0, low);
        int space = prefix.lastIndexOf(' ');
        if (space >= 4 && space > low * 0.55f) {
            prefix = prefix.substring(0, space);
        }
        return prefix + ELLIPSIS;
    }

    // ------------------------------------------------------------------------------------
    // Programme lookup
    // ------------------------------------------------------------------------------------

    /** First programme whose end is after {@code timeMs}; safe for overlapping data. */
    private static int firstIndexEndingAfter(List<EpgProgram> programs, long timeMs) {
        int low = 0;
        int high = programs.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (programs.get(mid).startMs < timeMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        int steps = 0;
        while (low > 0 && programs.get(low - 1).endMs > timeMs && steps < 8) {
            low--;
            steps++;
        }
        return low;
    }

    /**
     * Index of the programme covering {@code timeMs}; otherwise the next programme,
     * otherwise the last one; -1 for a channel with no programmes.
     */
    private static int indexAt(EpgChannel channel, long timeMs) {
        List<EpgProgram> programs = channel.programs();
        int size = programs.size();
        if (size == 0) {
            return -1;
        }
        int low = 0;
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (programs.get(mid).startMs <= timeMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        if (low > 0 && programs.get(low - 1).endMs > timeMs) {
            return low - 1;
        }
        if (low < size) {
            return low;
        }
        return size - 1;
    }

    /**
     * Index of the programme covering {@code timeMs}; in a gap or past the end of the
     * channel's EPG, the programme nearest in time (distance measured to its interval).
     * -1 for a channel with no programmes.
     */
    private static int nearestIndexAt(EpgChannel channel, long timeMs) {
        List<EpgProgram> programs = channel.programs();
        int size = programs.size();
        if (size == 0) {
            return -1;
        }
        int index = indexAt(channel, timeMs);
        if (index < 0) {
            return -1;
        }
        EpgProgram candidate = programs.get(index);
        if (timeMs >= candidate.startMs && timeMs < candidate.endMs) {
            return index;
        }
        int best = index;
        long bestDistance = intervalDistance(candidate, timeMs);
        if (index > 0) {
            EpgProgram previous = programs.get(index - 1);
            long distance = intervalDistance(previous, timeMs);
            if (distance < bestDistance) {
                best = index - 1;
            }
        }
        return best;
    }

    /** Distance from {@code timeMs} to a programme's interval; 0 when it covers the time. */
    private static long intervalDistance(EpgProgram program, long timeMs) {
        if (timeMs < program.startMs) {
            return program.startMs - timeMs;
        }
        if (timeMs >= program.endMs) {
            return timeMs - program.endMs;
        }
        return 0L;
    }

    // ------------------------------------------------------------------------------------
    // Focus and scrolling
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveHorizontal(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveHorizontal(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveVertical(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveVertical(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    selectFocused();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void moveHorizontal(int delta) {
        // A horizontal step defines a new focused time: the next UP/DOWN continues from the
        // newly focused programme instead of the old column's anchor.
        verticalAnchorValid = false;
        if (focusRow < 0 || focusRow >= channels.size()) {
            return;
        }
        EpgChannel channel = channels.get(focusRow);
        int count = channel.programs().size();
        if (count == 0) {
            return;
        }
        int index = focusProgramIndex;
        if (index < 0 || index >= count) {
            index = indexAt(channel, currentNowMs());
        }
        int next = Math.max(0, Math.min(count - 1, index + delta));
        if (next != focusProgramIndex) {
            setFocus(focusRow, next, true);
        }
        ensureProgramVisible(true, false);
    }

    /**
     * UP/DOWN. This is the only path that moves focus vertically, and it must never change
     * {@link #scrollXPx} - no horizontal startScroll, fling or snap of any kind.
     *
     * <p>Rule: the focused time is the previously focused programme's start time, clamped to
     * the visible window {@code [timeAtScrollX, timeAtScrollX + visibleMinutes]}. It is
     * captured once per vertical run in {@link #verticalAnchorMs}, so repeated DOWN presses
     * stay on the same time slice even when an intermediate channel has a gap or different
     * programme boundaries. The new row focuses the programme covering that time, or the
     * nearest one when the channel has none at that instant. The anchor always lies inside
     * the visible window, so the focused block is already at least partly visible and only
     * the vertical offset needs correcting ({@link #ensureRowVisible}).</p>
     */
    private void moveVertical(int delta) {
        if (channels.isEmpty()) {
            return;
        }

        if (!verticalAnchorValid) {
            EpgProgram focused = focusedProgram();
            verticalAnchorMs = focused != null ? focused.startMs : currentNowMs();
            verticalAnchorValid = true;
        }
        // Re-clamp on every step: the window may have been resized since the run started.
        long anchor = clampToVisibleWindow(verticalAnchorMs);
        verticalAnchorMs = anchor;

        int row = Math.max(0, Math.min(channels.size() - 1, focusRow + delta));
        if (row != focusRow) {
            setFocus(row, nearestIndexAt(channels.get(row), anchor), true);
        }
        ensureRowVisible(true);
    }

    /**
     * Visible-window start derived from the live scroll offset (not from the last onDraw).
     */
    private long visibleWindowStartMs() {
        long start = dayStartMs;
        if (pxPerMs > 0f) {
            start += (long) (scrollXPx / pxPerMs);
        }
        return Math.max(dayStartMs, Math.min(start, dayStartMs + DAY_MS));
    }

    /** End of the one-page (4 h) visible window, clamped to the displayed day. */
    private long visibleWindowEndMs() {
        long end = visibleWindowStartMs();
        if (pxPerMs > 0f) {
            end += (long) (gridW / pxPerMs);
        }
        return Math.min(end, dayStartMs + DAY_MS);
    }

    /** Clamps {@code timeMs} into the currently visible time window. */
    private long clampToVisibleWindow(long timeMs) {
        long start = visibleWindowStartMs();
        long end = visibleWindowEndMs();
        if (end <= start) {
            return start;
        }
        // end - 1 keeps the anchor strictly inside the window, so indexAt() cannot select a
        // programme that only starts exactly at the right edge.
        return Math.max(start, Math.min(timeMs, end - 1L));
    }

    private void selectFocused() {
        EpgChannel channel = focusedChannel();
        EpgProgram program = focusedProgram();
        if (listener != null && channel != null && program != null) {
            listener.onProgramSelected(channel, program);
        }
    }

    /**
     * Vertical-only correction used by UP/DOWN: scrolls just enough to make the focused row
     * fully visible while passing {@link #scrollXPx} through untouched. A vertical move must
     * never move the timeline.
     */
    private void ensureRowVisible(boolean smooth) {
        if (focusRow < 0 || gridH <= 0 || rowHeightPx <= 0) {
            return;
        }
        int targetY = rowVisibleScrollY();
        if (targetY == scrollYPx) {
            return;
        }
        if (smooth) {
            smoothScrollTo(scrollXPx, targetY);
        } else {
            setScrollImmediate(scrollXPx, targetY);
        }
    }

    /** Y offset that makes the focused row fully visible; {@link #scrollYPx} if it already is. */
    private int rowVisibleScrollY() {
        int targetY = scrollYPx;
        float top = focusRow * (float) rowHeightPx;
        float bottom = top + rowHeightPx;
        if (top < targetY) {
            targetY = Math.round(top - dp(2f));
        } else if (bottom > targetY + gridH) {
            targetY = Math.round(bottom - gridH + dp(2f));
        }
        return clampY(targetY);
    }

    /**
     * Scrolls just enough to make the focused row/block visible. Used by LEFT/RIGHT and
     * explicit jumps; never by UP/DOWN. When {@code horizontalOnlyIfOffscreen} is set, the
     * timeline only moves if the focused block is completely outside the viewport, which
     * keeps zapping smooth when nearby channels share the same schedule.
     */
    private void ensureProgramVisible(boolean smooth, boolean horizontalOnlyIfOffscreen) {
        if (focusRow < 0 || gridW <= 0 || gridH <= 0) {
            return;
        }

        int targetX = scrollXPx;
        EpgProgram program = focusedProgram();
        if (program != null) {
            float x1 = timeToX(program.startMs);
            float x2 = timeToX(program.endMs);
            float margin = dp(28f);
            boolean intersects = x2 > scrollXPx && x1 < scrollXPx + gridW;
            if (!horizontalOnlyIfOffscreen || !intersects) {
                if (x2 - x1 > gridW - 2f * margin) {
                    // Wider than the viewport: show its left border rather than neither edge.
                    if (x1 < scrollXPx + margin || x1 > scrollXPx + gridW - margin) {
                        targetX = Math.round(x1 - margin);
                    }
                } else if (x1 < scrollXPx + margin) {
                    targetX = Math.round(x1 - margin);
                } else if (x2 > scrollXPx + gridW - margin) {
                    targetX = Math.round(x2 - gridW + margin);
                }
            }
        }

        int targetY = rowVisibleScrollY();
        targetX = clampX(targetX);
        if (targetX == scrollXPx && targetY == scrollYPx) {
            return;
        }
        if (smooth) {
            smoothScrollTo(targetX, targetY);
        } else {
            setScrollImmediate(targetX, targetY);
        }
    }

    private void positionAtNow(boolean preferLiveChannel, boolean notify) {
        if (channels.isEmpty()) {
            return;
        }
        verticalAnchorValid = false;
        long now = currentNowMs();
        int row = (focusRow >= 0 && focusRow < channels.size()) ? focusRow : 0;

        if (preferLiveChannel && channels.get(row).programAt(now) == null) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).programAt(now) != null) {
                    row = i;
                    break;
                }
            }
        }

        EpgChannel channel = channels.get(row);
        EpgProgram live = channel.programAt(now);
        int index = live != null ? channel.programs().indexOf(live) : indexAt(channel, now);
        setFocus(row, index, notify);

        if (gridW <= 0 || gridH <= 0) {
            pendingInitialPosition = true;
            postInvalidateOnAnimation();
            return;
        }

        pendingInitialPosition = false;
        long clampedNow = Math.max(dayStartMs, Math.min(now, dayStartMs + DAY_MS));
        int targetX = Math.round((clampedNow - dayStartMs) * pxPerMs) - gridW / 3;
        int targetY = row * rowHeightPx - (gridH - rowHeightPx) / 2;
        setScrollImmediate(targetX, targetY);
    }

    private void setFocus(int row, int programIndex, boolean notify) {
        int oldRow = focusRow;
        boolean changed = row != focusRow || programIndex != focusProgramIndex;
        focusRow = row;
        focusProgramIndex = programIndex;
        if (changed) {
            if (notify) {
                notifyFocusChanged();
            }
            // Horizontal moves repaint only the row bands; a vertical move can leave a
            // stale ring in the row that lost focus because the two rows are separated
            // by scroll bands, so repaint the canvas once in that case.
            if (oldRow != row) {
                postInvalidateOnAnimation();
            } else {
                invalidateFocusRows(oldRow, row);
            }
        }
    }

    /**
     * Repaints just the two row bands affected by a focus move. The channel cell and every
     * programme block live inside their own 52 dp row, so no other pixels change. Falls back
     * to a full animation-frame invalidation when the view has no geometry yet.
     */
    private void invalidateFocusRows(int oldRow, int newRow) {
        if (rowHeightPx <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            postInvalidateOnAnimation();
            return;
        }
        int gridTop = getPaddingTop() + rulerHeightPx;
        int gridBottom = getHeight() - getPaddingBottom();
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            int row = pass == 0 ? oldRow : newRow;
            if (row < 0) {
                continue;
            }
            int rowTop = gridTop + row * rowHeightPx - scrollYPx;
            int rowBottom = rowTop + rowHeightPx;
            if (rowBottom <= gridTop || rowTop >= gridBottom) {
                continue;
            }
            top = Math.min(top, Math.max(rowTop, gridTop));
            bottom = Math.max(bottom, Math.min(rowBottom, gridBottom));
        }
        if (top >= bottom) {
            postInvalidateOnAnimation();
            return;
        }
        postInvalidateOnAnimation(0, top, getWidth(), bottom);
    }

    private void notifyFocusChanged() {
        EpgChannel channel = focusedChannel();
        EpgProgram program = focusedProgram();
        if (listener != null && channel != null) {
            listener.onProgramFocused(channel, program);
        }
        if (channel == null) {
            setContentDescription(null);
            return;
        }
        StringBuilder description = new StringBuilder(64);
        description.append(channel.number).append(' ').append(channel.title);
        if (program != null) {
            description.append(", ").append(program.displayTitle(getContext()));
        }
        setContentDescription(description.toString());
    }

    private EpgChannel focusedChannel() {
        if (focusRow < 0 || focusRow >= channels.size()) {
            return null;
        }
        return channels.get(focusRow);
    }

    private EpgProgram focusedProgram() {
        EpgChannel channel = focusedChannel();
        if (channel == null) {
            return null;
        }
        List<EpgProgram> programs = channel.programs();
        if (focusProgramIndex < 0 || focusProgramIndex >= programs.size()) {
            return null;
        }
        return programs.get(focusProgramIndex);
    }

    private long currentNowMs() {
        if (nowOverrideMs == Long.MIN_VALUE) {
            return System.currentTimeMillis();
        }
        return nowOverrideMs + (SystemClock.elapsedRealtime() - nowOverrideRealtimeMs);
    }

    // ------------------------------------------------------------------------------------
    // Scroller plumbing
    // ------------------------------------------------------------------------------------

    private void smoothScrollTo(int targetX, int targetY) {
        if (getWidth() <= 0) {
            return;
        }
        int x = clampX(targetX);
        int y = clampY(targetY);
        if (x == scrollXPx && y == scrollYPx) {
            return;
        }
        scroller.startScroll(scrollXPx, scrollYPx,
                x - scrollXPx, y - scrollYPx, SCROLL_DURATION_MS);
        postScrollTicker();
    }

    private void setScrollImmediate(int x, int y) {
        // A jump must win over an in-flight fling or focus animation.
        if (!scroller.isFinished()) {
            scroller.forceFinished(true);
        }
        int clampedX = clampX(x);
        int clampedY = clampY(y);
        if (clampedX == scrollXPx && clampedY == scrollYPx) {
            return;
        }
        scrollXPx = clampedX;
        scrollYPx = clampedY;
        postInvalidateOnAnimation();
    }

    private void postScrollTicker() {
        if (scrollTickerPosted) {
            return;
        }
        scrollTickerPosted = true;
        postOnAnimation(scrollTicker);
    }

    private void onScrollSettled() {
        clampScroll();
        // A UP/DOWN run keeps the focused block inside the visible window by construction,
        // so never let the settle pull the timeline back to it (that was the "jump back in
        // time" bug). Only drags/flings, which clear the vertical anchor, auto-correct X.
        if (!userTouching && !verticalAnchorValid) {
            ensureProgramVisible(true, true);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (gainFocus && focusRow < 0 && !channels.isEmpty()) {
            verticalAnchorValid = false;
            setFocus(0, indexAt(channels.get(0), currentNowMs()), true);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (liveUpdates) {
            removeCallbacks(liveTick);
            postDelayed(liveTick, LIVE_TICK_MS);
        }
        String zone = TimeZone.getDefault().getID();
        if (!zone.equals(zoneId)) {
            zoneId = zone;
            rebuildRulerLabels();
            programTexts.evictAll();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(liveTick);
        removeCallbacks(scrollTicker);
        scrollTickerPosted = false;
        scroller.forceFinished(true);
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------------------------
    // Touch support (secondary on TV; D-pad is the primary input)
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gridW <= 0 || gridH <= 0) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                // Touch starts a fresh gesture: drop the remembered vertical time slice so a
                // drag/fling settle may still snap the focused block back into view.
                verticalAnchorValid = false;
                userTouching = true;
                dragging = false;
                downTouchX = event.getX();
                downTouchY = event.getY();
                lastTouchX = downTouchX;
                lastTouchY = downTouchY;
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                velocityTracker.addMovement(event);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                float x = event.getX();
                float y = event.getY();
                if (!dragging) {
                    if (Math.abs(x - downTouchX) > touchSlop
                            || Math.abs(y - downTouchY) > touchSlop) {
                        dragging = true;
                        // Consume the slop so the content does not jump on the first drag.
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                }
                if (dragging) {
                    int dx = Math.round(lastTouchX - x);
                    int dy = Math.round(lastTouchY - y);
                    if (dx != 0 || dy != 0) {
                        scrollXPx = clampX(scrollXPx + dx);
                        scrollYPx = clampY(scrollYPx + dy);
                        postInvalidateOnAnimation();
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                }
                return true;

            case MotionEvent.ACTION_UP:
                float velocityX = 0f;
                float velocityY = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000, maximumFlingVelocity);
                    velocityX = velocityTracker.getXVelocity();
                    velocityY = velocityTracker.getYVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                userTouching = false;
                if (dragging && (Math.abs(velocityX) > minimumFlingVelocity
                        || Math.abs(velocityY) > minimumFlingVelocity)) {
                    scroller.fling(scrollXPx, scrollYPx,
                            (int) -velocityX, (int) -velocityY,
                            0, maxScrollX, 0, maxScrollY, 0, 0);
                    postScrollTicker();
                } else {
                    if (!dragging) {
                        performClick();
                    }
                    ensureProgramVisible(true, true);
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                userTouching = false;
                dragging = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                ensureProgramVisible(true, true);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    // ------------------------------------------------------------------------------------
    // Small value holders
    // ------------------------------------------------------------------------------------

    private static final class ProgramText {
        final String title;
        final String time;
        final float maxWidth;
        final float timeWidthPx;

        ProgramText(String title, String time, float maxWidth, float timeWidthPx) {
            this.title = title;
            this.time = time;
            this.maxWidth = maxWidth;
            this.timeWidthPx = timeWidthPx;
        }
    }

    private static final class ChannelText {
        final String number;
        final float numberWidthPx;
        final float logoNumberWidthPx;
        final String title;
        final float maxWidth;

        ChannelText(String number, float numberWidthPx, float logoNumberWidthPx,
                    String title, float maxWidth) {
            this.number = number;
            this.numberWidthPx = numberWidthPx;
            this.logoNumberWidthPx = logoNumberWidthPx;
            this.title = title;
            this.maxWidth = maxWidth;
        }
    }

    private static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = (int) (((from >>> 24) & 0xFF) * (1f - t) + ((to >>> 24) & 0xFF) * t);
        int r = (int) (((from >> 16) & 0xFF) * (1f - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1f - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1f - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
