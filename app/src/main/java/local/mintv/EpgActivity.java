package local.mintv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgGridView;
import local.mintv.epg.EpgProgram;
import local.mintv.epg.EpgRepository;
import local.mintv.player.PlaybackLauncher;
import local.mintv.player.QualityPreference;
import local.mintv.ui.EpgLogoLoader;

/**
 * EPG screen: channel rows against a time axis, TiviMate style.
 *
 * <p>Data comes from {@link EpgRepository} (merged line-up + a full day of programmes), the
 * same cached day the player uses for now/next. Selecting a programme delegates to
 * {@link PlaybackLauncher}, so free-to-air and subscription channels follow exactly the same
 * path as from the channel grid.</p>
 */
public final class EpgActivity extends Activity implements EpgGridView.Listener {

    private static final String TAG = "EpgActivity";

    private EpgGridView grid;
    private EpgRepository epgRepository;
    private EpgLogoLoader epgLogos;
    private TextView loadingView;
    private TextView qualityChip;
    private TextView infoTitle;
    private TextView infoTime;
    private TextView infoDescription;
    private final Map<Integer, Channel> lineUp = new HashMap<>();
    private boolean firstDelivery = true;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayLabel =
            new SimpleDateFormat("EEEE, d. MMMM", new Locale("hr", "HR"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        epgLogos = new EpgLogoLoader(grid, grid::invalidate);
        grid.setLogoProvider(epgLogos::get);

        epgRepository = new EpgRepository(this);
        long midnight = localMidnight(System.currentTimeMillis());
        grid.setDayStartMs(midnight);
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
                // The EPG still renders; playback just needs the line-up later.
            }
        });

        String isoDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(midnight));
        epgRepository.loadDay(isoDate, this::onEpgLoaded, message -> {
            loadingView.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void onEpgLoaded(List<EpgChannel> channels) {
        loadingView.setVisibility(View.GONE);
        EpgCache.set(channels);
        grid.setChannels(channels);
        long now = System.currentTimeMillis();
        grid.setNowMs(now);
        if (firstDelivery) {
            // Cache may deliver immediately and the network a moment later; only the first
            // delivery should move the viewport, otherwise a refresh would yank the user
            // back to "now" while they are browsing.
            firstDelivery = false;
            grid.scrollToNow();
            if (!channels.isEmpty()) {
                grid.focusChannel(channels.get(0).number);
            }
        }
        if (channels.isEmpty()) {
            updateInfo(null, null);
        } else {
            EpgChannel first = channels.get(0);
            updateInfo(first, first.programAt(System.currentTimeMillis()));
        }
    }

    // ------------------------------------------------------------------ ui

    private View buildUi() {
        int bg = getColor(R.color.bg);
        int accent = getColor(R.color.accent);
        int surface = getColor(R.color.surface);
        int textPrimary = getColor(R.color.text_primary);
        int textDim = getColor(R.color.text_secondary);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);

        // Top bar / grid / info strip stacked vertically, so the grid never has to guess
        // how much padding the overlays need.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(28), dp(10), dp(28), dp(10));
        topBar.setBackgroundColor(getColor(R.color.bg_raised));
        TextView day = new TextView(this);
        day.setText(dayLabel.format(new Date()));
        day.setTextColor(textPrimary);
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        topBar.addView(day, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        qualityChip = new TextView(this);
        qualityChip.setText(qualityLabel());
        qualityChip.setTextColor(textPrimary);
        qualityChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        qualityChip.setFocusable(true);
        qualityChip.setClickable(true);
        qualityChip.setBackgroundResource(R.drawable.bg_badge_chip);
        qualityChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.rightMargin = dp(18);
        qualityChip.setOnClickListener(view -> {
            QualityPreference.set(this, QualityPreference.get(this).next());
            qualityChip.setText(qualityLabel());
            Toast.makeText(this, R.string.quality_applies_next, Toast.LENGTH_SHORT).show();
        });
        topBar.addView(qualityChip, chipParams);

        TextView hint = new TextView(this);
        hint.setText(R.string.epg_hint);
        hint.setTextColor(textDim);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        topBar.addView(hint);
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new EpgGridView(this);
        grid.setPalette(accent, bg, surface, textPrimary, textDim);
        grid.setListener(this);
        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(28), dp(10), dp(28), dp(14));
        info.setBackgroundColor(getColor(R.color.bg_raised));
        infoTitle = new TextView(this);
        infoTitle.setTextColor(textPrimary);
        infoTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        infoTime = new TextView(this);
        infoTime.setTextColor(accent);
        infoTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        infoDescription = new TextView(this);
        infoDescription.setTextColor(textDim);
        infoDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        infoDescription.setMaxLines(2);
        info.addView(infoTitle);
        info.addView(infoTime);
        info.addView(infoDescription);
        column.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loadingView = new TextView(this);
        loadingView.setText(R.string.epg_loading);
        loadingView.setTextColor(textDim);
        loadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadingParams.gravity = Gravity.CENTER;
        root.addView(loadingView, loadingParams);
        return root;
    }

    @Override
    public void onProgramFocused(EpgChannel channel, EpgProgram program) {
        updateInfo(channel, program);
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
            // Already aired: this is catch-up, not the live channel.
            if (program.canCatchup(now)) {
                PlaybackLauncher.openCatchup(this, channel, program);
            } else {
                Toast.makeText(this, R.string.error_catchup_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (program != null && program.startMs > now) {
            Toast.makeText(this, R.string.error_programme_not_started,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<Channel> order = new ArrayList<>(lineUp.values());
        order.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
        PlaybackLauncher.open(this, playable, order);
    }

    private String qualityLabel() {
        switch (QualityPreference.get(this)) {
            case MAX:
                return getString(R.string.quality_max);
            case SAVER:
                return getString(R.string.quality_saver);
            default:
                return getString(R.string.quality_auto);
        }
    }

    private void updateInfo(EpgChannel channel, EpgProgram program) {
        if (channel == null) {
            infoTitle.setText("");
            infoTime.setText("");
            infoDescription.setText("");
            return;
        }
        if (program == null) {
            infoTitle.setText(channel.title);
            infoTime.setText(String.valueOf(channel.number));
            infoDescription.setText(R.string.epg_no_programmes);
            return;
        }
        infoTitle.setText(channel.number + " · " + channel.title + " — " + program.title);
        infoTime.setText(clock.format(new Date(program.startMs))
                + " – " + clock.format(new Date(program.endMs)));
        infoDescription.setText(program.description);
    }

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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
