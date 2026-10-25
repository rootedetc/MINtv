package local.mintv;

import android.app.Activity;
import android.graphics.Bitmap;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgGridView;
import local.mintv.epg.EpgProgram;
import local.mintv.epg.EpgProgramDetails;
import local.mintv.epg.EpgRepository;
import local.mintv.epg.ProgramDetailsRepository;
import local.mintv.player.PlaybackLauncher;
import local.mintv.ui.EpgLogoLoader;
import local.mintv.ui.ImageLoader;
import local.mintv.ui.Shell;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/**
 * The guide is the front door: a fixed source rail, the day's schedule on a ruled time
 * armature, and an on-air bar that shows the focused programme's artwork, synopsis,
 * running time, progress and channel.
 */
public final class EpgActivity extends Activity implements EpgGridView.Listener {

    private EpgGridView grid;
    private TvRail rail;
    private EpgRepository epgRepository;
    private EpgLogoLoader epgLogos;
    private EpgLogoLoader inspectorLogos;

    private View loading;
    private View errorBar;
    private TextView errorText;
    private TextView errorRetry;
    private TextView dateLabel;

    private TextView stateLabel;
    private TextView timeLabel;
    private TextView titleLabel;
    private TextView descriptionLabel;
    private TextView channelNumber;
    private TextView channelName;
    private TextView channelMeta;
    private ImageView channelLogo;
    private ImageView programmePoster;
    private View posterFallback;
    private TextView posterNumber;
    private TextView nextTitle;
    private TextView nextTime;
    private TextView actionHint;
    private View progressFill;
    private View progressRest;

    private final Map<Integer, Channel> lineUp = new HashMap<>();
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

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress(System.currentTimeMillis());
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
                if (focusedChannel != null) {
                    updateAction(focusedChannel, focusedProgram);
                }
            }

            @Override
            public void onError(String message, Throwable cause) {
                // The guide renders from its own line-up; playback resolves channels later.
            }
        });

        loadGuide(false);
        rail.setContentFocusTarget(grid);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccount();
        updateHeaderDate();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
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
        handler.removeCallbacks(progressTick);
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
        loading.setVisibility(View.VISIBLE);
        epgRepository.loadDay(iso.format(new Date(dayStartMs)), this::onEpgLoaded, message -> {
            loading.setVisibility(View.GONE);
            errorText.setText(TextUtils.isEmpty(message)
                    ? getString(R.string.guide_error) : message);
            errorBar.setVisibility(View.VISIBLE);
            if (errorRetry != null) {
                errorRetry.requestFocus();
            }
        });
    }

    private void onEpgLoaded(List<EpgChannel> channels) {
        loading.setVisibility(View.GONE);
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
                Tv.SURFACE, Tv.TEXT, Tv.TEXT_2);
        grid.setListener(this);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildOnAirBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(152)));

        loading = buildLoading();
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        retry.setOnClickListener(view -> {
            loading.setVisibility(View.VISIBLE);
            loadGuide(true);
        });
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

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(Tv.STROKE);
        progressFill = new View(this);
        progressFill.setBackgroundColor(Tv.BRAND);
        progressRest = new View(this);
        progressRest.setBackgroundColor(0x00000000);
        track.addView(progressFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        track.addView(progressRest, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(track, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(body, 24, 12, 24, 12);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setBackground(Tv.box(this, Tv.SURFACE_2, 7f, Tv.STROKE_STRONG, 1f));
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
        posterNumber = Tv.numeral(this, 42f, Tv.BRAND);
        posterNumber.setGravity(Gravity.CENTER);
        fallback.addView(posterNumber);
        TextView fallbackLabel = Tv.label(this, "KANAL", Tv.TEXT_3);
        fallbackLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fallbackLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fallbackLabelLp.topMargin = dp(2);
        fallback.addView(fallbackLabel, fallbackLabelLp);
        posterFrame.addView(fallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        posterFallback = fallback;

        body.addView(posterFrame, new LinearLayout.LayoutParams(dp(200), dp(112)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(18);
        body.addView(info, infoLp);

        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);

        channelLogo = new ImageView(this);
        channelLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        channelLogo.setBackground(Tv.box(this, Tv.SURFACE, 4f, Tv.STROKE, 1f));
        channelLogo.setPadding(dp(5), dp(4), dp(5), dp(4));
        channelRow.addView(channelLogo, new LinearLayout.LayoutParams(dp(48), dp(30)));

        channelNumber = Tv.numeral(this, 18f, Tv.BRAND_BRIGHT);
        channelNumber.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(
                dp(34), ViewGroup.LayoutParams.WRAP_CONTENT);
        numberLp.leftMargin = dp(10);
        channelRow.addView(channelNumber, numberLp);

        LinearLayout channelNames = new LinearLayout(this);
        channelNames.setOrientation(LinearLayout.VERTICAL);
        channelName = Tv.displayText(this, 15f, Tv.TEXT);
        channelName.setSingleLine(true);
        channelName.setEllipsize(TextUtils.TruncateAt.END);
        channelNames.addView(channelName);
        channelMeta = Tv.label(this, "", Tv.TEXT_3);
        channelMeta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(3);
        channelNames.addView(channelMeta, metaLp);
        channelRow.addView(channelNames, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stateLabel = Tv.label(this, "", 0xFFFFFFFF);
        stateLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        stateLabel.setBackgroundResource(R.drawable.bg_chip_live);
        channelRow.addView(stateLabel);
        info.addView(channelRow);

        titleLabel = Tv.displayText(this, 27f, Tv.TEXT);
        titleLabel.setSingleLine(true);
        titleLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(7);
        info.addView(titleLabel, titleLp);

        timeLabel = Tv.text(this, 12.5f, Tv.TEXT_2, false);
        timeLabel.setFontFeatureSettings("tnum");
        timeLabel.setSingleLine(true);
        timeLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(3);
        info.addView(timeLabel, timeLp);

        descriptionLabel = Tv.text(this, 13f, Tv.TEXT_2, false);
        descriptionLabel.setMaxLines(2);
        descriptionLabel.setEllipsize(TextUtils.TruncateAt.END);
        descriptionLabel.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(5);
        info.addView(descriptionLabel, descLp);

        LinearLayout next = new LinearLayout(this);
        next.setOrientation(LinearLayout.VERTICAL);
        next.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(218),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nextLp.leftMargin = dp(20);
        body.addView(next, nextLp);

        TextView nextLabel = Tv.label(this, getString(R.string.guide_next), Tv.TEXT_3);
        nextLabel.setGravity(Gravity.END);
        next.addView(nextLabel);
        nextTitle = Tv.displayText(this, 15f, Tv.TEXT);
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
        next.addView(nextTime);

        actionHint = Tv.label(this, "", Tv.BRAND_BRIGHT);
        actionHint.setGravity(Gravity.END);
        actionHint.setSingleLine(true);
        actionHint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        next.addView(actionHint, hintLp);

        return root;
    }

    private View buildLoading() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Tv.BG);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        spinnerLp.bottomMargin = dp(14);
        panel.addView(spinner, spinnerLp);

        TextView label = Tv.text(this, 15f, Tv.TEXT_2, false);
        label.setText(R.string.guide_loading);
        panel.addView(label);
        return panel;
    }

    // ------------------------------------------------------------------ state

    private void updateHeaderDate() {
        dateLabel.setText(Tv.day(dayStartMs));
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
            channelNumber.setText("—");
            channelName.setText("");
            channelMeta.setText("");
            channelLogo.setImageDrawable(null);
            stateLabel.setVisibility(View.GONE);
            titleLabel.setText(R.string.guide_inspector_empty);
            timeLabel.setText("");
            descriptionLabel.setText("");
            nextTitle.setText("");
            nextTime.setText("");
            actionHint.setText("");
            posterNumber.setText("—");
            programmePoster.setVisibility(View.GONE);
            posterFallback.setVisibility(View.VISIBLE);
            setProgressFraction(0f);
            return;
        }

        long now = System.currentTimeMillis();
        channelNumber.setText(String.valueOf(channel.number));
        channelName.setText(channel.title);
        channelMeta.setText(channelMeta(channel));
        refreshChannelLogo();
        posterNumber.setText(String.valueOf(channel.number));

        stateLabel.setVisibility(View.VISIBLE);
        if (program == null) {
            stateLabel.setText(R.string.guide_no_programmes);
            stateLabel.setTextColor(Tv.TEXT_2);
            stateLabel.setBackgroundResource(R.drawable.bg_chip_quiet);
            titleLabel.setText(R.string.guide_no_programmes);
            timeLabel.setText("");
            descriptionLabel.setText("");
            nextTitle.setText("—");
            nextTime.setText("");
            actionHint.setText(R.string.guide_no_programmes);
            actionHint.setTextColor(Tv.TEXT_3);
            showPoster("");
            setProgressFraction(0f);
            return;
        }

        boolean live = program.isLive(now);
        boolean past = program.endMs <= now;
        if (live) {
            stateLabel.setText(R.string.guide_live);
            stateLabel.setTextColor(0xFFFFFFFF);
            stateLabel.setBackgroundResource(R.drawable.bg_chip_live);
        } else if (past) {
            stateLabel.setText(R.string.guide_ended);
            stateLabel.setTextColor(Tv.WARNING);
            stateLabel.setBackgroundResource(R.drawable.bg_chip_catchup);
        } else {
            stateLabel.setText(R.string.guide_soon);
            stateLabel.setTextColor(Tv.TEXT_2);
            stateLabel.setBackgroundResource(R.drawable.bg_chip_quiet);
        }

        titleLabel.setText(program.title);
        timeLabel.setText(timeLine(program, live, past, now,
                program.durationMs()));
        descriptionLabel.setText(TextUtils.isEmpty(program.description)
                ? getString(R.string.guide_no_description) : program.description);

        EpgProgram following = channel.nextAfter(program.endMs);
        if (following != null) {
            nextTitle.setText(following.title);
            nextTime.setText(clock.format(new Date(following.startMs))
                    + " – " + clock.format(new Date(following.endMs)));
        } else {
            nextTitle.setText("—");
            nextTime.setText("");
        }

        updateAction(channel, program);
        updateProgress(now);
        showPoster("");
        loadDetails(program);
    }

    private String channelMeta(EpgChannel channel) {
        StringBuilder value = new StringBuilder("KANAL ").append(channel.number);
        if (!TextUtils.isEmpty(channel.quality)) {
            value.append(" · ").append(channel.quality);
        }
        if (channel.freeToAir) {
            value.append(" · BESPLATNO");
        }
        return value.toString();
    }

    private void updateAction(EpgChannel channel, EpgProgram program) {
        if (actionHint == null) {
            return;
        }
        if (channel == null || program == null) {
            actionHint.setText("");
            return;
        }
        boolean playable = lineUp.get(channel.number) != null;
        if (!playable) {
            actionHint.setText(R.string.error_no_stream);
            actionHint.setTextColor(Tv.TEXT_3);
            return;
        }
        long now = System.currentTimeMillis();
        boolean live = program.isLive(now);
        boolean past = program.endMs <= now;
        actionHint.setTextColor(Tv.BRAND_BRIGHT);
        if (live) {
            actionHint.setText(R.string.guide_live_action);
        } else if (past && program.canCatchup(now)) {
            actionHint.setText(R.string.guide_catchup_action);
        } else {
            actionHint.setText(R.string.guide_watch_channel_action);
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
            descriptionLabel.setText(focusedDetails.description);
        }
        timeLabel.setText(timeLine(program, live, past, now, runtimeMs));
        if (!TextUtils.isEmpty(focusedDetails.name)
                && ("Bez naziva".equals(program.title) || TextUtils.isEmpty(program.title))) {
            titleLabel.setText(focusedDetails.name);
        }
        showPoster(focusedDetails.posterImageUrl);
    }

    private String timeLine(EpgProgram program, boolean live, boolean past, long now,
                            long runtimeMs) {
        StringBuilder value = new StringBuilder();
        value.append(clock.format(new Date(program.startMs)))
                .append(" – ")
                .append(clock.format(new Date(program.endMs)));
        String duration = formatDuration(runtimeMs);
        if (!duration.isEmpty()) {
            value.append("   ·   ").append(duration);
        }
        if (live) {
            long remaining = Math.max(0L, program.endMs - now);
            if (remaining > 0) {
                value.append("   ·   još ").append(formatDuration(remaining));
            }
        } else if (!past) {
            value.append("   ·   počinje u ")
                    .append(clock.format(new Date(program.startMs)));
        }
        return value.toString();
    }

    private String formatDuration(long ms) {
        if (ms <= 0L) {
            return "";
        }
        long minutes = Math.max(1L, Math.round(ms / 60_000.0));
        if (minutes < 60L) {
            return minutes + " min";
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L ? hours + " h" : hours + " h " + rest + " min";
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

    private void updateProgress(long now) {
        if (focusedProgram == null) {
            setProgressFraction(0f);
            return;
        }
        float fraction;
        if (focusedProgram.endMs <= now) {
            fraction = 1f;
        } else if (focusedProgram.startMs >= now) {
            fraction = 0f;
        } else {
            fraction = focusedProgram.progress(now);
        }
        setProgressFraction(fraction);
    }

    private void setProgressFraction(float value) {
        float fraction = Math.max(0f, Math.min(1f, value));
        LinearLayout.LayoutParams fill = (LinearLayout.LayoutParams)
                progressFill.getLayoutParams();
        fill.weight = fraction;
        progressFill.setLayoutParams(fill);
        LinearLayout.LayoutParams rest = (LinearLayout.LayoutParams)
                progressRest.getLayoutParams();
        rest.weight = 1f - fraction;
        progressRest.setLayoutParams(rest);
    }

    private void refreshChannelLogo() {
        if (channelLogo == null || focusedChannel == null) {
            return;
        }
        Bitmap bitmap = inspectorLogos != null ? inspectorLogos.get(focusedChannel) : null;
        if (bitmap != null && !bitmap.isRecycled()) {
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
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && rail.hasFocus()) {
                grid.requestFocus();
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
