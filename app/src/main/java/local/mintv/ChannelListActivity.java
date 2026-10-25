package local.mintv;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.net.ApiException;
import local.mintv.net.PlaybackApi;
import local.mintv.player.ChannelQueue;
import local.mintv.ui.ChannelAdapter;
import local.mintv.ui.Shell;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** Every channel in the line-up, with what is on now and a truthful live progress line. */
public final class ChannelListActivity extends Activity implements ChannelRepository.Listener {

    private static final String TAG = "ChannelList";

    private TvRail rail;
    private RecyclerView grid;
    private ChannelAdapter adapter;
    private TextView status;
    private TextView footerInfo;
    private TextView filterAll;
    private TextView filterFree;
    private TextView filterHd;
    private View emptyState;
    private TextView emptyText;
    private ProgressBar progress;
    private ChannelRepository repository;
    private PlaybackApi playbackApi;

    private final List<Channel> allChannels = new ArrayList<>();
    private int filter; // 0 all, 1 free, 2 HD
    private boolean firstDataDelivered;
    private boolean fromCache;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable nowTick = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.refreshNow();
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        repository = ChannelRepository.get(this);
        playbackApi = new PlaybackApi(this);
        load(false);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        warnIfVpn();
        updateAccount();
        handler.removeCallbacks(nowTick);
        handler.post(nowTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(nowTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Tv.BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new TvRail(this);
        rail.setActive(TvRail.Tab.CHANNELS);
        rail.setListener(new TvRail.Listener() {
            @Override
            public void onTabSelected(TvRail.Tab tab) {
                Shell.open(ChannelListActivity.this, tab);
            }

            @Override
            public void onAccount() {
                Shell.account(ChannelListActivity.this);
            }
        });
        shell.addView(rail, new LinearLayout.LayoutParams(
                Tv.dp(this, 96), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        shell.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        content.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 88)));

        FrameLayout body = new FrameLayout(this);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(this);
        grid.setId(View.generateViewId());
        int spanCount = Math.max(3, Math.min(8,
                getResources().getConfiguration().screenWidthDp / 210));
        GridLayoutManager manager = new GridLayoutManager(this, spanCount);
        grid.setLayoutManager(manager);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(Tv.dp(this, 32), Tv.dp(this, 16),
                Tv.dp(this, 32), Tv.dp(this, 20));
        grid.setVerticalScrollBarEnabled(false);
        grid.setNextFocusUpId(filterAll.getId());
        grid.setNextFocusLeftId(rail.item(TvRail.Tab.CHANNELS).getId());
        adapter = new ChannelAdapter(this::openChannel);
        adapter.setSpanCount(spanCount);
        adapter.setRailFocusId(rail.item(TvRail.Tab.CHANNELS).getId());
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(
                Tv.dp(this, 42), Tv.dp(this, 42), Gravity.CENTER);
        body.addView(progress, spinnerLp);

        body.addView(buildEmpty(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        content.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 38)));

        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(header, 32, 0, 32, 0);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Tv.displayText(this, 32f, Tv.TEXT);
        title.setText(R.string.channels_title);
        title.setLetterSpacing(0.01f);
        titles.addView(title);
        status = Tv.text(this, 12f, Tv.TEXT_3, false);
        status.setText(R.string.channels_loading);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Tv.dp(this, 3);
        titles.addView(status, statusLp);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        filterAll = chip(getString(R.string.channels_filter_all), 0);
        filterFree = chip(getString(R.string.channels_filter_free), 1);
        filterHd = chip(getString(R.string.channels_filter_hd), 2);
        updateChips();
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.addView(filterAll, chipLp(0));
        chips.addView(filterFree, chipLp(8));
        chips.addView(filterHd, chipLp(8));
        header.addView(chips);

        TextView refresh = Tv.ghost(this, getString(R.string.channels_refresh));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44));
        refreshLp.leftMargin = Tv.dp(this, 12);
        refresh.setOnClickListener(view -> load(true));
        header.addView(refresh, refreshLp);
        return header;
    }

    private LinearLayout.LayoutParams chipLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 42));
        lp.leftMargin = Tv.dp(this, leftMargin);
        return lp;
    }

    private TextView chip(String label, int index) {
        TextView view = Tv.text(this, 13f, Tv.TEXT_2, true);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Tv.dp(this, 18), 0, Tv.dp(this, 18), 0);
        view.setBackgroundResource(R.drawable.bg_chip);
        view.setFocusable(true);
        view.setClickable(true);
        Tv.focusScale(view, 1.03f);
        view.setOnClickListener(v -> setFilter(index));
        view.setOnFocusChangeListener((v, hasFocus) -> view.setTextColor(
                hasFocus ? Tv.TEXT : index == filter ? Tv.TEXT : Tv.TEXT_2));
        return view;
    }

    private View buildEmpty() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundResource(R.drawable.bg_error_panel);
        panel.setPadding(Tv.dp(this, 40), Tv.dp(this, 28),
                Tv.dp(this, 40), Tv.dp(this, 28));
        panel.setVisibility(View.GONE);
        emptyState = panel;

        emptyText = Tv.text(this, 20f, Tv.TEXT, true);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText(R.string.channels_error_title);
        panel.addView(emptyText);
        TextView body = Tv.text(this, 14f, Tv.TEXT_2, false);
        body.setGravity(Gravity.CENTER);
        body.setText(R.string.channels_error_body);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Tv.dp(this, 8);
        panel.addView(body, bodyLp);
        TextView retry = Tv.button(this, getString(R.string.channels_retry), true);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 48));
        retryLp.topMargin = Tv.dp(this, 22);
        retry.setOnClickListener(view -> load(true));
        panel.addView(retry, retryLp);
        return panel;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(footer, 32, 0, 32, 0);
        TextView hint = Tv.text(this, 12f, Tv.TEXT_3, false);
        hint.setText(R.string.channels_hint);
        footer.addView(hint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return footer;
    }

    private void setFilter(int index) {
        if (filter == index) {
            return;
        }
        filter = index;
        updateChips();
        applyFilter(true);
    }

    private void updateChips() {
        filterAll.setTextColor(filter == 0 ? Tv.TEXT : Tv.TEXT_2);
        filterFree.setTextColor(filter == 1 ? Tv.TEXT : Tv.TEXT_2);
        filterHd.setTextColor(filter == 2 ? Tv.TEXT : Tv.TEXT_2);
        filterAll.setSelected(filter == 0);
        filterFree.setSelected(filter == 1);
        filterHd.setSelected(filter == 2);
    }

    private void applyFilter(boolean moveFocus) {
        List<Channel> filtered = new ArrayList<>();
        for (Channel channel : allChannels) {
            if (filter == 1 && !channel.isFreeToAir()) {
                continue;
            }
            if (filter == 2 && !isHd(channel)) {
                continue;
            }
            filtered.add(channel);
        }
        adapter.submit(filtered);
        status.setText(getString(fromCache
                ? R.string.channels_source_cached : R.string.channels_source_live));
        if (filtered.isEmpty()) {
            emptyText.setText(R.string.channels_empty_filter);
            emptyState.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            emptyState.post(() -> {
                View retry = ((ViewGroup) emptyState).getChildAt(2);
                if (retry != null) {
                    retry.requestFocus();
                }
            });
        } else {
            emptyState.setVisibility(View.GONE);
            if (moveFocus) {
                focusPosition(0);
            }
        }
    }

    private static boolean isHd(Channel channel) {
        String quality = channel.getQuality();
        if (TextUtils.isEmpty(quality)) {
            return false;
        }
        String upper = quality.toUpperCase(java.util.Locale.US);
        return upper.contains("HD") || upper.contains("4K") || upper.contains("UHD");
    }

    private void load(boolean forceRefresh) {
        emptyState.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        if (adapter.getItemCount() == 0) {
            progress.setVisibility(View.VISIBLE);
            status.setText(R.string.channels_loading);
        } else if (forceRefresh) {
            status.setText(R.string.shell_refreshing);
        }
        repository.load(forceRefresh, this);
    }

    @Override
    public void onChannels(List<Channel> channels, boolean fromCache) {
        this.fromCache = fromCache;
        progress.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);

        allChannels.clear();
        allChannels.addAll(channels);
        boolean same = adapter.hasSameContent(channels);
        if (!same || filter != 0) {
            applyFilter(false);
        } else {
            status.setText(getString(fromCache
                    ? R.string.channels_source_cached : R.string.channels_source_live));
        }
        status.setVisibility(View.GONE);

        if (!firstDataDelivered && !channels.isEmpty()) {
            firstDataDelivered = true;
            focusPosition(0);
        }
    }

    private void focusPosition(int position) {
        grid.post(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
                return;
            }
            grid.scrollToPosition(position);
            grid.post(() -> {
                RecyclerView.ViewHolder retry = grid.findViewHolderForAdapterPosition(position);
                if (retry != null) {
                    retry.itemView.requestFocus();
                } else {
                    grid.requestFocus();
                }
            });
        });
    }

    @Override
    public void onError(String message, Throwable cause) {
        Log.w(TAG, "Channel load failed: " + message, cause);
        progress.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        if (adapter.getItemCount() == 0) {
            emptyText.setText(R.string.channels_error_title);
            emptyState.setVisibility(View.VISIBLE);
            ViewGroup group = (ViewGroup) emptyState;
            if (group.getChildCount() > 2) {
                group.getChildAt(2).requestFocus();
            }
        } else {
            status.setText(R.string.status_offline_short);
        }
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

    private void warnIfVpn() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                Toast.makeText(this, R.string.vpn_warning, Toast.LENGTH_LONG).show();
                Log.i(TAG, "VPN active: MAXtv CDN rejects playback over VPN");
                return;
            }
        }
    }

    private void openChannel(Channel channel) {
        if (!channel.isPlayable()) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Channel> order = new ArrayList<>(adapter.current());
        ChannelQueue.set(order, order.indexOf(channel));

        Session session = Session.get(this);
        if (!channel.isFreeToAir() && !session.isLoggedIn()) {
            Toast.makeText(this, R.string.error_sign_in_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (channel.isFreeToAir()) {
            startActivity(PlayerActivity.intentForChannel(this, channel));
            return;
        }
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.status_starting);
        playbackApi.resolve(channel, new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                runOnUiThread(() -> startActivity(
                        PlayerActivity.intentForPlayback(ChannelListActivity.this,
                                channel, playback)));
            }

            @Override
            public void onFailure(ApiException error) {
                runOnUiThread(() -> {
                    status.setText(getString(R.string.status_playback_failed,
                            String.valueOf(error.getMessage())));
                    Toast.makeText(ChannelListActivity.this,
                            String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
