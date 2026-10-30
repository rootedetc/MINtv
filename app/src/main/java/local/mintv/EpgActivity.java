package local.mintv;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
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
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import local.mintv.data.AccountChannelRepository;
import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ChannelSubscriptions;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgGridView;
import local.mintv.epg.EpgProgram;
import local.mintv.epg.EpgProgramDetails;
import local.mintv.epg.EpgRepository;
import local.mintv.epg.ProgramDetailsRepository;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.player.PlaybackLauncher;
import local.mintv.ui.EpgLogoLoader;
import local.mintv.ui.ImageLoader;
import local.mintv.ui.Shell;
import local.mintv.ui.Skeleton;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/**
 * The guide is the front door: a fixed source rail, the day's schedule on a ruled time
 * armature, and an on-air bar that shows the focused programme's artwork, synopsis,
 * running time, progress and channel.
 */
public final class EpgActivity extends BaseActivity implements EpgGridView.Listener {

    private EpgGridView grid;
    private TvRail rail;
    private EpgRepository epgRepository;
    private AccountChannelRepository accountRepository;
    private EpgLogoLoader epgLogos;
    private EpgLogoLoader inspectorLogos;

    private FrameLayout guideHost;
    private View loading;
    private View errorBar;
    private TextView errorText;
    private TextView errorRetry;
    private TextView dateLabel;

    private TextView stateLabel;
    private TextView timeLabel;
    private TextView titleLabel;
    private TextView descriptionLabel;
    private ImageView channelLogo;
    private ImageView channelLock;
    private ImageView programmePoster;
    private View posterFallback;
    private TextView nextTitle;
    private TextView nextTime;

    private static final int ON_AIR_HEIGHT_DP = 152;
    private static final int ON_AIR_POSTER_HEIGHT_DP = 112;

    private final Map<Integer, Channel> lineUp = new HashMap<>();
    private final Set<Integer> subscriptions = new HashSet<>();
    private boolean subscriptionsKnown;
    private boolean subscriptionsLoading;
    private final SimpleDateFormat clock =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat iso =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EpgChannel focusedChannel;
    private EpgProgram focusedProgram;
    private EpgProgramDetails focusedDetails;
    private int detailsRequest;
    private long dayStartMs;
    private boolean firstDelivery = true;
    private String stateTag = "";

    private final Runnable timeTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long today = localMidnight(now);
            if (today != dayStartMs) {
                // The guide stayed open across midnight: move to the new day instead of
                // leaving last night's schedule on screen until the next resume.
                dayStartMs = today;
                grid.setDayStartMs(dayStartMs);
                grid.setNowMs(now);
                updateHeaderDate();
                firstDelivery = true;
                loadGuide(false);
            }
            handler.postDelayed(this, 15_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        epgLogos = new EpgLogoLoader(grid, grid::invalidate);
        grid.setLogoProvider(epgLogos::get);
        inspectorLogos = new EpgLogoLoader(this, this::refreshChannelLogo, dp(72), dp(44));

        epgRepository = new EpgRepository(this);
        accountRepository = new AccountChannelRepository(this);
        dayStartMs = localMidnight(System.currentTimeMillis());
        grid.setDayStartMs(dayStartMs);
        grid.setNowMs(System.currentTimeMillis());
        grid.setLiveUpdates(true);

        ChannelRepository.get(this).load(false, new ChannelRepository.Listener() {
            @Override
            public void onChannels(List<Channel> channels, boolean fromCache) {
                lineUp.clear();
                for (Channel channel : channels) {
                    lineUp.put(channel.getNumber(), channel);
                }
            }

            @Override
            public void onError(String message, Throwable cause) {
                // The guide renders from its own line-up; playback resolves channels later.
            }
        });

        loadGuide(false);
        loadSubscriptions();
        rail.setContentFocusTarget(grid);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccount();
        updateHeaderDate();
        if (!subscriptionsKnown && !subscriptionsLoading
                && Session.get(this).isLoggedIn()) {
            loadSubscriptions();
        } else if (subscriptionsKnown && !Session.get(this).isLoggedIn()) {
            ChannelSubscriptions.clear();
            applySubscriptions(null);
        }
        handler.removeCallbacks(timeTick);
        handler.post(timeTick);
        if (inspectorLogos != null) {
            inspectorLogos.resume();
        }
        if (epgLogos != null) {
            epgLogos.resume();
        }
        long today = localMidnight(System.currentTimeMillis());
        if (today != dayStartMs) {
            dayStartMs = today;
            grid.setDayStartMs(dayStartMs);
            updateHeaderDate();
            firstDelivery = true;
            loadGuide(false);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(timeTick);
        if (inspectorLogos != null) {
            inspectorLogos.pause();
        }
        if (epgLogos != null) {
            epgLogos.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (inspectorLogos != null) {
            inspectorLogos.clear();
        }
        super.onDestroy();
    }

    private void loadGuide(boolean force) {
        errorBar.setVisibility(View.GONE);
        showGuideSkeleton();
        epgRepository.loadDay(iso.format(new Date(dayStartMs)), force,
                this::onEpgLoaded, message -> {
            hideGuideSkeleton();
            errorText.setText(TextUtils.isEmpty(message)
                    ? getString(R.string.guide_error) : message);
            errorBar.setVisibility(View.VISIBLE);
            if (errorRetry != null) {
                errorRetry.requestFocus();
            }
        });
    }

    private void onEpgLoaded(List<EpgChannel> channels) {
        hideGuideSkeleton();
        errorBar.setVisibility(View.GONE);
        EpgCache.set(channels);
        grid.setChannels(channels);
        long now = System.currentTimeMillis();
        grid.setNowMs(now);
        updateHeaderDate();

        if (firstDelivery) {
            firstDelivery = false;
            grid.scrollToNow();
            EpgChannel target = firstUsefulChannel(channels, now);
            if (target != null) {
                grid.focusChannel(target.number);
            }
        } else if (focusedChannel != null) {
            updateInspector(focusedChannel, focusedProgram);
        } else if (!channels.isEmpty()) {
            updateInspector(channels.get(0), channels.get(0).programAt(now));
        }
    }

    /**
     * Loads the account's subscribed channel numbers once. Until this succeeds the guide
     * shows no lock markers rather than guessing that every premium channel is locked.
     */
    private void loadSubscriptions() {
        if (subscriptionsLoading || !Session.get(this).isLoggedIn()) {
            return;
        }
        // Paint the last known snapshot immediately, then refresh it. A failed refresh
        // keeps the snapshot rather than turning every premium channel unlocked.
        Set<Integer> cached = ChannelSubscriptions.current(this);
        if (cached != null) {
            applySubscriptions(cached);
        }
        subscriptionsLoading = true;
        Background.run(() -> accountRepository.subscribedNumbers(),
                new Background.Result<Set<Integer>>() {
                    @Override
                    public void onSuccess(Set<Integer> numbers) {
                        subscriptionsLoading = false;
                        ChannelSubscriptions.publish(EpgActivity.this, numbers);
                        // publish() refuses when the session vanished mid-fetch; mirror the
                        // cached state so the marker and the click guard never disagree.
                        applySubscriptions(ChannelSubscriptions.current(EpgActivity.this));
                    }

                    @Override
                    public void onError(ApiException error) {
                        subscriptionsLoading = false;
                        if (!subscriptionsKnown) {
                            applySubscriptions(null);
                        }
                    }
                });
    }

    private void applySubscriptions(Set<Integer> numbers) {
        subscriptionsKnown = numbers != null;
        subscriptions.clear();
        if (numbers != null) {
            subscriptions.addAll(numbers);
        }
        grid.setSubscriptionState(subscriptions, subscriptionsKnown);
        if (focusedChannel != null) {
            updateInspector(focusedChannel, focusedProgram);
        }
    }

    private boolean isLocked(EpgChannel channel) {
        return subscriptionsKnown && Session.get(this).isLoggedIn()
                && channel != null && !channel.freeToAir
                && !subscriptions.contains(channel.number);
    }

    private EpgChannel firstUsefulChannel(List<EpgChannel> channels, long now) {
        for (EpgChannel channel : channels) {
            if (channel.number > 0 && channel.programAt(now) != null) {
                return channel;
            }
        }
        return channels.isEmpty() ? null : channels.get(0);
    }

    // ------------------------------------------------------------------ ui

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Tv.BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new TvRail(this);
        rail.setActive(TvRail.Tab.GUIDE);
        rail.setListener(new TvRail.Listener() {
            @Override
            public void onTabSelected(TvRail.Tab tab) {
                if (tab == TvRail.Tab.GUIDE) {
                    grid.requestFocus();
                    return;
                }
                Shell.open(EpgActivity.this, tab);
            }

            @Override
            public void onAccount() {
                Shell.account(EpgActivity.this);
            }
        });
        shell.addView(rail, new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        shell.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        content.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        content.addView(buildErrorBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new EpgGridView(this);
        grid.setId(View.generateViewId());
        grid.setPalette(Tv.BRAND, Tv.BRAND_BRIGHT, Tv.BG, Tv.BG_RAISED,
                Tv.SURFACE, Tv.SURFACE_2, Tv.TEXT, Tv.TEXT_2);
        grid.setListener(this);

        LinearLayout guideArea = new LinearLayout(this);
        guideArea.setOrientation(LinearLayout.VERTICAL);
        guideArea.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        guideArea.addView(buildOnAirBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(152)));

        // The loading skeleton covers the guide surface only: the rail, header and error bar
        // never move or disappear underneath a spinner.
        guideHost = new FrameLayout(this);
        guideHost.addView(guideArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(guideHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Tv.BG);
        Tv.pad(header, 32, 0, 32, 0);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Tv.displayText(this, 32f, Tv.TEXT);
        title.setText(R.string.guide_title);
        title.setLetterSpacing(0.01f);
        titleBlock.addView(title);

        dateLabel = Tv.label(this, "", Tv.TEXT_2);
        dateLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        dateLabel.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(4);
        titleBlock.addView(dateLabel, dateLp);

        header.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private View buildErrorBar() {
        LinearLayout bar = new LinearLayout(this);
        errorBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Tv.box(this, 0x1AFFB454, 0, Tv.WARNING, 0));
        Tv.pad(bar, 32, 8, 32, 8);
        bar.setVisibility(View.GONE);

        errorText = Tv.text(this, 13f, Tv.WARNING, false);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(errorText, textLp);

        TextView retry = Tv.ghost(this, getString(R.string.guide_retry));
        errorRetry = retry;
        retry.setOnClickListener(view -> loadGuide(true));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        retryLp.leftMargin = dp(16);
        bar.addView(retry, retryLp);
        return bar;
    }

    private View buildOnAirBar() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Tv.BG_RAISED);
        root.setClickable(false);

        // The body is a frame so the state tag can sit at the top-right of the bar, on the
        // same right edge as the NEXT column, without joining the programme copy.
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bodyRow = new LinearLayout(this);
        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
        bodyRow.setGravity(Gravity.CENTER_VERTICAL);
        // The 112 dp poster sits inside the 152 dp bar with generous air; there is no
        // progress track and no channel identity row in this lower third.
        int bodyPadV = Math.min(12,
                Math.max(0, (ON_AIR_HEIGHT_DP - ON_AIR_POSTER_HEIGHT_DP) / 2));
        Tv.pad(bodyRow, 24, bodyPadV, 24, bodyPadV);
        body.addView(bodyRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout posterFrame = new FrameLayout(this);
        // Broadcast frame, not a card: square artwork edge with a hairline.
        posterFrame.setBackground(Tv.box(this, Tv.SURFACE_2, 0f, Tv.STROKE_STRONG, 1f));
        posterFrame.setClipToOutline(true);

        programmePoster = new ImageView(this);
        programmePoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        programmePoster.setVisibility(View.GONE);
        posterFrame.addView(programmePoster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setBackgroundColor(Tv.SURFACE_2);
        ImageView fallbackIcon = Tv.icon(this, R.drawable.ic_tv, Tv.TEXT_3, 34f);
        fallbackIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        fallback.addView(fallbackIcon, new LinearLayout.LayoutParams(
                dp(34), dp(34)));
        posterFrame.addView(fallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        posterFallback = fallback;

        bodyRow.addView(posterFrame, new LinearLayout.LayoutParams(
                dp(200), dp(ON_AIR_POSTER_HEIGHT_DP)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(18);
        bodyRow.addView(info, infoLp);

        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);

        channelLogo = new ImageView(this);
        channelLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        channelLogo.setBackground(Tv.box(this, Tv.SURFACE, 4f, Tv.STROKE, 1f));
        channelLogo.setPadding(dp(5), dp(4), dp(5), dp(4));
        channelRow.addView(channelLogo, new LinearLayout.LayoutParams(dp(48), dp(30)));

        channelLock = new ImageView(this);
        channelLock.setImageResource(R.drawable.ic_lock);
        channelLock.setColorFilter(Tv.TEXT_2);
        channelLock.setContentDescription(getString(R.string.channels_locked));
        channelLock.setVisibility(View.GONE);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        lockLp.leftMargin = dp(8);
        channelRow.addView(channelLock, lockLp);

        info.addView(channelRow);

        titleLabel = Tv.displayText(this, 27f, Tv.TEXT);
        titleLabel.setSingleLine(true);
        titleLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(4);
        info.addView(titleLabel, titleLp);

        timeLabel = Tv.text(this, 12.5f, Tv.TEXT_2, false);
        timeLabel.setFontFeatureSettings("tnum");
        timeLabel.setSingleLine(true);
        timeLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(2);
        info.addView(timeLabel, timeLp);

        descriptionLabel = Tv.text(this, 13f, Tv.TEXT_2, false);
        descriptionLabel.setMaxLines(2);
        descriptionLabel.setEllipsize(TextUtils.TruncateAt.END);
        descriptionLabel.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(4);
        info.addView(descriptionLabel, descLp);

        // A 1 px hairline separates the programme copy from the NEXT column, the way the
        // broadcast lower third does. It is shorter than the bar and clears the state tag.
        View nextDivider = new View(this);
        nextDivider.setBackgroundColor(Tv.STROKE);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                Math.max(1, dp(1)), dp(76));
        dividerLp.leftMargin = dp(18);
        dividerLp.gravity = Gravity.CENTER_VERTICAL;
        bodyRow.addView(nextDivider, dividerLp);

        LinearLayout next = new LinearLayout(this);
        next.setOrientation(LinearLayout.VERTICAL);
        next.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(218),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nextLp.leftMargin = dp(18);
        bodyRow.addView(next, nextLp);

        TextView nextLabel = Tv.label(this, getString(R.string.guide_next), Tv.TEXT_3);
        nextLabel.setGravity(Gravity.END);
        next.addView(nextLabel);
        nextTitle = Tv.displayText(this, 17f, Tv.TEXT);
        nextTitle.setGravity(Gravity.END);
        nextTitle.setSingleLine(true);
        nextTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nextTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nextTitleLp.topMargin = dp(3);
        next.addView(nextTitle, nextTitleLp);
        nextTime = Tv.text(this, 11.5f, Tv.TEXT_3, false);
        nextTime.setGravity(Gravity.END);
        nextTime.setFontFeatureSettings("tnum");
        nextTime.setSingleLine(true);
        nextTime.setEllipsize(TextUtils.TruncateAt.END);
        next.addView(nextTime);

        // State tag: top-right of the bar, right edge flush with the NEXT column. NEXT is
        // vertically centred in the body, so the tag sits above its label with clear air
        // instead of covering the next title on a tight bar.
        stateLabel = Tv.label(this, "", 0xFFFFFFFF);
        stateLabel.setGravity(Gravity.CENTER);
        stateLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        stateLabel.setBackgroundResource(R.drawable.bg_chip_live);
        FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        stateLp.topMargin = dp(12);
        stateLp.rightMargin = dp(24);
        body.addView(stateLabel, stateLp);

        return root;
    }

    // ------------------------------------------------------------------ loading

    private static final int GUIDE_SKELETON_ROWS = 5;

    /** Programme-block widths per skeleton row, as weights of the visible 4-hour window. */
    private static final float[][] SKELETON_BLOCKS = {
            {0.95f, 0.55f, 1.30f, 0.90f},
            {1.15f, 0.90f, 0.70f, 0.95f},
            {0.70f, 1.25f, 1.05f, 0.80f},
            {1.05f, 0.75f, 0.60f, 1.30f},
            {0.85f, 1.10f, 1.20f, 0.70f}
    };

    private void showGuideSkeleton() {
        if (guideHost == null || loading != null) {
            return;
        }
        loading = buildGuideSkeleton();
        guideHost.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideGuideSkeleton() {
        if (guideHost == null || loading == null) {
            return;
        }
        // Removing the view (rather than hiding it) also cancels the pulse animator.
        guideHost.removeView(loading);
        loading = null;
    }

    /**
     * A quiet Broadcast Rail skeleton for the guide region alone: the real ruler, channel
     * column and on-air bar geometry, filled with flat SURFACE_2/SURFACE_3 plates. The rail,
     * header and error bar stay visible; nothing outside this overlay moves or pulses.
     */
    private View buildGuideSkeleton() {
        // Opaque ground stays outside the pulsing group, so the empty grid underneath (and
        // its "no schedule" line) can never ghost through the 55-90% opacity pulse.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Tv.BG);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setContentDescription(getString(R.string.common_loading));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(guide, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        guide.addView(buildSkeletonChannelColumn(), new LinearLayout.LayoutParams(
                dp(76), ViewGroup.LayoutParams.MATCH_PARENT));
        guide.addView(buildSkeletonTimeline(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        content.addView(buildSkeletonOnAirBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(152)));

        Skeleton.pulse(content);
        return root;
    }

    private View buildSkeletonChannelColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Tv.BG_RAISED);

        LinearLayout corner = new LinearLayout(this);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(corner, 12, 0, 12, 0);
        corner.addView(Skeleton.line(this, 40f, 12f));
        column.addView(corner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        for (int row = 0; row < GUIDE_SKELETON_ROWS; row++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER);
            cell.addView(Skeleton.box(this, 48f, 32f, 5f),
                    new LinearLayout.LayoutParams(dp(48), dp(32)));

            column.addView(cell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        return column;
    }

    private View buildSkeletonTimeline() {
        LinearLayout timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);

        LinearLayout ruler = new LinearLayout(this);
        ruler.setOrientation(LinearLayout.HORIZONTAL);
        ruler.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(ruler, 8, 0, 8, 0);
        for (int slot = 0; slot < 4; slot++) {
            LinearLayout lane = new LinearLayout(this);
            lane.setOrientation(LinearLayout.HORIZONTAL);
            lane.setGravity(Gravity.CENTER_VERTICAL);
            lane.addView(Skeleton.line(this, 34f, 10f));
            ruler.addView(lane, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        timeline.addView(ruler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        for (int row = 0; row < GUIDE_SKELETON_ROWS; row++) {
            LinearLayout lane = new LinearLayout(this);
            lane.setOrientation(LinearLayout.HORIZONTAL);
            lane.setGravity(Gravity.CENTER_VERTICAL);
            Tv.pad(lane, 4, 3, 4, 3);
            float[] weights = SKELETON_BLOCKS[row % SKELETON_BLOCKS.length];
            for (float weight : weights) {
                LinearLayout.LayoutParams blockLp =
                        new LinearLayout.LayoutParams(0, dp(46), weight);
                blockLp.leftMargin = dp(1);
                blockLp.rightMargin = dp(1);
                lane.addView(Skeleton.box(this, 0f, 46f, 6f), blockLp);
            }
            timeline.addView(lane, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        return timeline;
    }

    private View buildSkeletonOnAirBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Tv.BG_RAISED);

        FrameLayout body = new FrameLayout(this);
        bar.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bodyRow = new LinearLayout(this);
        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
        bodyRow.setGravity(Gravity.CENTER_VERTICAL);
        int bodyPadV = Math.min(12,
                Math.max(0, (ON_AIR_HEIGHT_DP - ON_AIR_POSTER_HEIGHT_DP) / 2));
        Tv.pad(bodyRow, 24, bodyPadV, 24, bodyPadV);
        body.addView(bodyRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bodyRow.addView(Skeleton.box(this, 200f, 112f, 0f),
                new LinearLayout.LayoutParams(dp(200), dp(ON_AIR_POSTER_HEIGHT_DP)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(18);
        bodyRow.addView(info, infoLp);

        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.HORIZONTAL);
        ident.setGravity(Gravity.CENTER_VERTICAL);
        ident.addView(Skeleton.box(this, 48f, 30f, 4f),
                new LinearLayout.LayoutParams(dp(48), dp(30)));
        info.addView(ident);

        info.addView(skeletonLane(0.62f, 22f), skeletonLaneParams(10));
        info.addView(skeletonLane(0.38f, 12f), skeletonLaneParams(8));
        info.addView(skeletonLane(0.92f, 10f), skeletonLaneParams(7));
        info.addView(skeletonLane(0.72f, 10f), skeletonLaneParams(4));

        View nextDivider = new View(this);
        nextDivider.setBackgroundColor(Tv.STROKE);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                Math.max(1, dp(1)), dp(76));
        dividerLp.leftMargin = dp(18);
        dividerLp.gravity = Gravity.CENTER_VERTICAL;
        bodyRow.addView(nextDivider, dividerLp);

        LinearLayout next = new LinearLayout(this);
        next.setOrientation(LinearLayout.VERTICAL);
        next.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(218),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nextLp.leftMargin = dp(18);
        bodyRow.addView(next, nextLp);
        next.addView(Skeleton.line(this, 66f, 12f), skeletonEndParams(66, 12, 0));
        next.addView(Skeleton.line(this, 158f, 16f), skeletonEndParams(158, 16, 6));
        next.addView(Skeleton.line(this, 92f, 12f), skeletonEndParams(92, 12, 4));

        body.addView(skeletonStateTag());

        return bar;
    }

    /** The on-air state tag's resting place: top-right, flush with the NEXT column. */
    private View skeletonStateTag() {
        View tag = Skeleton.line(this, 42f, 18f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(42), dp(18),
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(12);
        lp.rightMargin = dp(24);
        tag.setLayoutParams(lp);
        return tag;
    }

    /** A line that fills {@code fraction} of its lane and leaves the rest empty. */
    private View skeletonLane(float fraction, float heightDp) {
        LinearLayout lane = new LinearLayout(this);
        lane.setOrientation(LinearLayout.HORIZONTAL);
        lane.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(Math.round(heightDp))));
        lane.addView(Skeleton.line(this, 0f, heightDp), new LinearLayout.LayoutParams(
                0, dp(Math.round(heightDp)), fraction));
        lane.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f - fraction));
        return lane;
    }

    private LinearLayout.LayoutParams skeletonLaneParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private LinearLayout.LayoutParams skeletonEndParams(int widthDp, int heightDp,
                                                        int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.gravity = Gravity.END;
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    // ------------------------------------------------------------------ state

    private void updateHeaderDate() {
        setTextIfChanged(dateLabel, Tv.day(dayStartMs));
    }

    private void updateAccount() {
        Session session = Session.get(this);
        if (session.isLoggedIn()) {
            String name = session.displayName();
            if (TextUtils.isEmpty(name)) {
                name = session.username();
            }
            rail.setAccount(TextUtils.isEmpty(name)
                    ? getString(R.string.nav_settings) : name, true);
        } else {
            rail.setAccount(getString(R.string.action_sign_in), false);
        }
    }

    private void updateInspector(EpgChannel channel, EpgProgram program) {
        focusedChannel = channel;
        focusedProgram = program;
        focusedDetails = null;
        detailsRequest++;

        if (channel == null) {
            channelLogo.setImageDrawable(null);
            channelLock.setVisibility(View.GONE);
            stateLabel.setVisibility(View.GONE);
            stateTag = "";
            setTextIfChanged(titleLabel, getString(R.string.guide_inspector_empty));
            setTextIfChanged(timeLabel, "");
            setTextIfChanged(descriptionLabel, "");
            setTextIfChanged(nextTitle, "");
            setTextIfChanged(nextTime, "");
            programmePoster.setVisibility(View.GONE);
            posterFallback.setVisibility(View.VISIBLE);
            return;
        }

        long now = System.currentTimeMillis();
        boolean locked = isLocked(channel);
        channelLock.setVisibility(locked ? View.VISIBLE : View.GONE);
        refreshChannelLogo();

        stateLabel.setVisibility(View.VISIBLE);
        if (program == null) {
            setStateChip("none", getString(R.string.guide_no_programmes),
                    Tv.TEXT_2, R.drawable.bg_chip_quiet);
            setTextIfChanged(titleLabel, getString(R.string.guide_no_programmes));
            setTextIfChanged(timeLabel, locked
                    ? getString(R.string.channels_locked) : "");
            setTextIfChanged(descriptionLabel, "");
            setTextIfChanged(nextTitle, "—");
            setTextIfChanged(nextTime, "");
            showPoster("");
            return;
        }

        boolean live = program.isLive(now);
        boolean past = program.endMs <= now;
        boolean catchup = past && program.canCatchup(now);
        if (live) {
            setStateChip("live", getString(R.string.guide_live),
                    0xFFFFFFFF, R.drawable.bg_chip_live);
        } else if (catchup) {
            setStateChip("catchup", getString(R.string.badge_catchup),
                    Tv.SUCCESS, R.drawable.bg_chip_catchup_available);
        } else if (past) {
            setStateChip("past", getString(R.string.guide_ended),
                    Tv.WARNING, R.drawable.bg_chip_catchup);
        } else {
            setStateChip("soon", getString(R.string.guide_soon),
                    Tv.TEXT_2, R.drawable.bg_chip_quiet);
        }

        setTextIfChanged(titleLabel, program.displayTitle(this));
        setTextIfChanged(timeLabel, timeLine(program, live, past, now,
                program.durationMs(), "", locked));
        setTextIfChanged(descriptionLabel, TextUtils.isEmpty(program.description)
                ? getString(R.string.guide_no_description) : program.description);

        EpgProgram following = channel.nextAfter(program.endMs);
        if (following != null) {
            setTextIfChanged(nextTitle, following.displayTitle(this));
            setTextIfChanged(nextTime, clock.format(new Date(following.startMs))
                    + " – " + clock.format(new Date(following.endMs)));
        } else {
            setTextIfChanged(nextTitle, "—");
            setTextIfChanged(nextTime, "");
        }

        showPoster("");
        loadDetails(program);
    }

    /**
     * Sets the on-air state tag only when the state actually changes. Re-applying the chip
     * background on every focus tick re-inflated a drawable and invalidated the whole bar.
     */
    private void setStateChip(String tag, String text, int color, int backgroundRes) {
        if (!tag.equals(stateTag)) {
            stateTag = tag;
            stateLabel.setTextColor(color);
            stateLabel.setBackgroundResource(backgroundRes);
        }
        setTextIfChanged(stateLabel, text);
    }

    /** Avoids a re-layout and a redraw of the on-air bar when the value is unchanged. */
    private void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null) {
            return;
        }
        if (!TextUtils.equals(view.getText(), value)) {
            view.setText(value);
        }
    }

    private void loadDetails(EpgProgram program) {
        if (program == null || TextUtils.isEmpty(program.id)) {
            return;
        }
        int request = ++detailsRequest;
        ProgramDetailsRepository.load(this, program.id, details -> {
            if (request != detailsRequest || focusedProgram != program) {
                return;
            }
            focusedDetails = details;
            applyDetails(program);
        });
    }

    private void applyDetails(EpgProgram program) {
        if (focusedDetails == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean live = program.isLive(now);
        boolean past = program.endMs <= now;
        long runtimeMs = focusedDetails.runtimeSeconds > 0
                ? focusedDetails.runtimeSeconds * 1000L : program.durationMs();

        if (!TextUtils.isEmpty(focusedDetails.description)) {
            setTextIfChanged(descriptionLabel, focusedDetails.description);
        }
        setTextIfChanged(timeLabel, timeLine(program, live, past, now, runtimeMs,
                focusedDetails.genre, isLocked(focusedChannel)));
        if (!TextUtils.isEmpty(focusedDetails.name)
                && (program.titleMissing || TextUtils.isEmpty(program.title))) {
            setTextIfChanged(titleLabel, focusedDetails.name);
        }
        showPoster(focusedDetails.posterImageUrl);
    }

    private String timeLine(EpgProgram program, boolean live, boolean past, long now,
                            long runtimeMs, String genre, boolean locked) {
        StringBuilder value = new StringBuilder();
        value.append(clock.format(new Date(program.startMs)))
                .append(" – ")
                .append(clock.format(new Date(program.endMs)));
        // One quiet metadata line: range · access · genre · duration · remaining. Empty
        // segments (no genre yet, no runtime) disappear without leaving a stray separator.
        if (locked) {
            appendMeta(value, getString(R.string.channels_locked));
        }
        appendMeta(value, genre);
        appendMeta(value, formatDuration(runtimeMs));
        if (live) {
            long remaining = Math.max(0L, program.endMs - now);
            if (remaining > 0) {
                appendMeta(value, getString(R.string.guide_remaining_inline,
                        formatDuration(remaining)));
            }
        } else if (!past) {
            appendMeta(value, getString(R.string.guide_starts_at_inline,
                    clock.format(new Date(program.startMs))));
        }
        return value.toString();
    }

    private void appendMeta(StringBuilder value, String segment) {
        if (TextUtils.isEmpty(segment)) {
            return;
        }
        value.append(" · ").append(segment);
    }

    private String formatDuration(long ms) {
        if (ms <= 0L) {
            return "";
        }
        long minutes = Math.max(1L, Math.round(ms / 60_000.0));
        if (minutes < 60L) {
            return getString(R.string.duration_minutes, minutes);
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L
                ? getString(R.string.duration_hours, hours)
                : getString(R.string.duration_hours_minutes, hours, rest);
    }

    private void showPoster(String url) {
        if (!TextUtils.isEmpty(url)) {
            programmePoster.setVisibility(View.VISIBLE);
            posterFallback.setVisibility(View.GONE);
            ImageLoader.load(programmePoster, url);
        } else {
            programmePoster.setVisibility(View.GONE);
            programmePoster.setImageDrawable(null);
            posterFallback.setVisibility(View.VISIBLE);
        }
    }

    private void refreshChannelLogo() {
        if (channelLogo == null || focusedChannel == null) {
            return;
        }
        Bitmap bitmap = inspectorLogos != null ? inspectorLogos.get(focusedChannel) : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            if (channelLogo.getDrawable() instanceof BitmapDrawable
                    && ((BitmapDrawable) channelLogo.getDrawable()).getBitmap() == bitmap) {
                return;
            }
            channelLogo.setImageBitmap(bitmap);
        } else {
            channelLogo.setImageDrawable(null);
        }
    }

    // ------------------------------------------------------------------ grid callbacks

    @Override
    public void onProgramFocused(EpgChannel channel, EpgProgram program) {
        updateInspector(channel, program);
    }

    @Override
    public void onProgramSelected(EpgChannel channel, EpgProgram program) {
        if (channel == null) {
            return;
        }
        if (isLocked(channel)) {
            PlaybackLauncher.showLocked(this);
            return;
        }
        Channel playable = lineUp.get(channel.number);
        if (playable == null) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        if (program != null && program.endMs > 0 && program.endMs <= now) {
            if (program.canCatchup(now)) {
                PlaybackLauncher.openCatchup(this, channel, program);
                return;
            }
            Toast.makeText(this, R.string.guide_tuning_after_end, Toast.LENGTH_SHORT).show();
        } else if (program != null && program.startMs > now) {
            Toast.makeText(this, R.string.guide_tuning_before_start, Toast.LENGTH_SHORT).show();
        }
        List<Channel> order = new ArrayList<>(lineUp.values());
        order.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
        PlaybackLauncher.open(this, playable, order);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    && grid.hasFocus() && grid.isFirstProgramFocused()) {
                rail.item(TvRail.Tab.GUIDE).requestFocus();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    && grid.hasFocus() && grid.isTopRowFocused()) {
                rail.item(TvRail.Tab.GUIDE).requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ utils

    private static long localMidnight(long nowMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nowMs);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private int dp(int value) {
        return Tv.dp(this, value);
    }
}
