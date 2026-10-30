package local.mintv;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import local.mintv.data.AccountChannelRepository;
import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ChannelSubscriptions;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.net.PlaybackApi;
import local.mintv.player.ChannelQueue;
import local.mintv.player.PlaybackLauncher;
import local.mintv.ui.ChannelAdapter;
import local.mintv.ui.Shell;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** Every channel in the line-up, with what is on now and a truthful live progress line. */
public final class ChannelListActivity extends BaseActivity implements ChannelRepository.Listener {

    public static android.content.Intent intentFavourites(android.content.Context context) {
        android.content.Intent intent = new android.content.Intent(context,
                ChannelListActivity.class);
        intent.putExtra(EXTRA_FAVOURITES, true);
        return intent;
    }

    private static final String TAG = "ChannelList";
    private static final String EXTRA_FAVOURITES = "local.mintv.extra.FAVOURITES";

    private TvRail rail;
    private RecyclerView grid;
    private ChannelAdapter adapter;
    private TextView status;
    private View footer;
    private TextView footerInfo;
    private TextView filterFavourites;
    private FrameLayout optionsAction;
    private View emptyState;
    private TextView emptyText;
    private ProgressBar progress;
    private ChannelRepository repository;
    private AccountChannelRepository accountRepository;
    private PlaybackApi playbackApi;

    private final List<Channel> allChannels = new ArrayList<>();
    private final Set<Integer> favourites = new HashSet<>();
    private final Set<Integer> subscriptions = new HashSet<>();
    private final List<Integer> serverOrder = new ArrayList<>();
    private boolean favouritesKnown;
    private boolean subscriptionsKnown;
    private boolean orderApplied;
    private boolean accountLoaded;
    private boolean accountLoading;
    private boolean reorderMode;
    private List<Channel> reorderSnapshot;
    private int reorderPosition = -1;
    private boolean favouritesOnly;
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
        favouritesOnly = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FAVOURITES, false);
        setContentView(buildUi());

        repository = ChannelRepository.get(this);
        accountRepository = new AccountChannelRepository(this);
        playbackApi = new PlaybackApi(this);
        if (Session.get(this).isLoggedIn()) {
            Set<Integer> cached = ChannelSubscriptions.current(this);
            if (cached != null) {
                subscriptionsKnown = true;
                subscriptions.addAll(cached);
            }
            loadAccountChannels();
        }
        load(false);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        warnIfVpn();
        updateAccount();
        if (Session.get(this).isLoggedIn()) {
            if (!accountLoaded) {
                loadAccountChannels();
            }
        } else if (accountLoaded || favouritesKnown || subscriptionsKnown) {
            accountLoaded = false;
            favouritesKnown = false;
            subscriptionsKnown = false;
            favourites.clear();
            subscriptions.clear();
            serverOrder.clear();
            orderApplied = false;
            ChannelSubscriptions.clear();
            if (favouritesOnly) {
                applyFilter(false);
            }
        }
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
                Tv.dp(this, 32), Tv.dp(this, 64));
        grid.setVerticalScrollBarEnabled(false);
        grid.setNextFocusUpId(filterFavourites.getId());
        grid.setNextFocusLeftId(rail.item(TvRail.Tab.CHANNELS).getId());
        adapter = new ChannelAdapter(this::openChannel, this::toggleFavourite);
        adapter.setSpanCount(spanCount);
        adapter.setRailFocusId(rail.item(TvRail.Tab.CHANNELS).getId());
        adapter.setHeaderFocusId(filterFavourites.getId());
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Scrim over the foot of the grid: the third row peeks and fades instead of
        // being hard-clipped. Purely decorative and never focusable or clickable.
        View fade = new View(this);
        fade.setBackgroundResource(R.drawable.bg_grid_fade);
        fade.setFocusable(false);
        fade.setClickable(false);
        fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(fade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.channel_fade_height),
                Gravity.BOTTOM));

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

        filterFavourites = chip(getString(R.string.channels_filter_favourites));
        updateFilterChip();
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.addView(filterFavourites, chipLp(0));
        header.addView(chips);

        optionsAction = iconButton(R.drawable.ic_settings, getString(R.string.channels_options));
        optionsAction.setOnClickListener(view -> {
            if (reorderMode) {
                saveReorder();
            } else {
                showOptions();
            }
        });
        LinearLayout.LayoutParams optionsLp = new LinearLayout.LayoutParams(
                Tv.dp(this, 44), Tv.dp(this, 44));
        optionsLp.leftMargin = Tv.dp(this, 10);
        header.addView(optionsAction, optionsLp);

        FrameLayout refresh = iconButton(R.drawable.ic_refresh,
                getString(R.string.channels_refresh));
        refresh.setOnClickListener(view -> load(true));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                Tv.dp(this, 44), Tv.dp(this, 44));
        refreshLp.leftMargin = Tv.dp(this, 8);
        header.addView(refresh, refreshLp);
        return header;
    }

    /** Square ghost icon button; the header's refresh and options actions share it. */
    private FrameLayout iconButton(int iconRes, String description) {
        FrameLayout button = new FrameLayout(this);
        button.setBackgroundResource(R.drawable.bg_button_ghost);
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(description);
        Tv.focusScale(button);
        button.addView(Tv.icon(this, iconRes, Tv.TEXT_2, 21f),
                new FrameLayout.LayoutParams(
                        Tv.dp(this, 21), Tv.dp(this, 21), Gravity.CENTER));
        return button;
    }

    private LinearLayout.LayoutParams chipLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 42));
        lp.leftMargin = Tv.dp(this, leftMargin);
        return lp;
    }

    private TextView chip(String label) {
        TextView view = Tv.text(this, 13f, Tv.TEXT_2, true);
        view.setId(View.generateViewId());
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Tv.dp(this, 18), 0, Tv.dp(this, 18), 0);
        view.setBackgroundResource(R.drawable.bg_chip);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(v -> toggleFavourites());
        // One listener owns both the focus scale and the label colour; setting a second
        // focus listener would replace the scale that Tv.focusScale installs.
        android.view.animation.DecelerateInterpolator ease =
                new android.view.animation.DecelerateInterpolator(2.2f);
        view.setOnFocusChangeListener((v, hasFocus) -> {
            view.setTextColor(hasFocus || favouritesOnly ? Tv.TEXT : Tv.TEXT_2);
            view.animate().cancel();
            view.animate()
                    .scaleX(hasFocus ? 1.03f : 1f)
                    .scaleY(hasFocus ? 1.03f : 1f)
                    .setDuration(hasFocus ? 140L : 110L)
                    .setInterpolator(ease)
                    .start();
        });
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
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(bar, 32, 0, 32, 0);
        footerInfo = Tv.text(this, 12f, Tv.TEXT_3, false);
        bar.addView(footerInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Normal mode has no permanent key hint; the line only exists for reorder.
        bar.setVisibility(View.GONE);
        footer = bar;
        return bar;
    }

    private void showFooter(int textRes) {
        footerInfo.setText(textRes);
        footer.setVisibility(View.VISIBLE);
    }

    private void hideFooter() {
        footerInfo.setText("");
        footer.setVisibility(View.GONE);
    }

    /** The favourites chip is a toggle: off shows the full line-up, on shows only favourites. */
    private void toggleFavourites() {
        favouritesOnly = !favouritesOnly;
        updateFilterChip();
        applyFilter(true);
    }

    private void updateFilterChip() {
        filterFavourites.setTextColor(favouritesOnly ? Tv.TEXT : Tv.TEXT_2);
        filterFavourites.setSelected(favouritesOnly);
    }

    private void applyFilter(boolean moveFocus) {
        List<Channel> filtered = new ArrayList<>();
        for (Channel channel : allChannels) {
            if (favouritesOnly && !favourites.contains(channel.getNumber())) {
                continue;
            }
            filtered.add(channel);
        }
        adapter.submit(filtered);
        adapter.setFavouriteState(favourites, favouritesKnown);
        adapter.setSubscriptionState(subscriptions, subscriptionsKnown);
        status.setText(getString(fromCache
                ? R.string.channels_source_cached : R.string.channels_source_live));
        if (filtered.isEmpty()) {
            emptyText.setText(favouritesOnly
                    ? (favouritesKnown ? R.string.channels_empty_favourites
                            : R.string.channels_favourites_sign_in)
                    : R.string.channels_empty_filter);
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
        if (!reorderMode) {
            applyServerOrder();
        }
        boolean same = adapter.hasSameContent(allChannels);
        if (!same || favouritesOnly) {
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
        Session session = Session.get(this);        if (session.isLoggedIn()) {
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

    private void loadAccountChannels() {
        if (accountLoading) {
            return;
        }
        accountLoading = true;
        Background.run(() -> {
            List<Integer> favs = accountRepository.favourites(true);
            List<Integer> order = accountRepository.order();
            AccountLoad load = new AccountLoad();
            load.favourites = favs;
            load.order = order;
            try {
                load.subscriptions = accountRepository.subscribedNumbers();
                load.subscriptionsKnown = true;
            } catch (ApiException ignored) {
                load.subscriptions = new HashSet<>();
            }
            return load;
        }, new Background.Result<AccountLoad>() {
            @Override
            public void onSuccess(AccountLoad load) {
                accountLoading = false;
                accountLoaded = true;
                favourites.clear();
                favourites.addAll(load.favourites);
                favouritesKnown = true;
                if (load.subscriptionsKnown) {
                    ChannelSubscriptions.publish(ChannelListActivity.this, load.subscriptions);
                }
                subscriptions.clear();
                subscriptionsKnown = false;
                Set<Integer> current = ChannelSubscriptions.current(ChannelListActivity.this);
                if (current != null) {
                    subscriptions.addAll(current);
                    subscriptionsKnown = true;
                }
                serverOrder.clear();
                serverOrder.addAll(load.order);
                orderApplied = false;
                if (!reorderMode) {
                    applyServerOrder();
                }
                applyFilter(false);
            }

            @Override
            public void onError(ApiException error) {
                accountLoading = false;
                favouritesKnown = false;
                if (favouritesOnly) {
                    applyFilter(false);
                }
            }
        });
    }

    private void applyServerOrder() {
        if (orderApplied || serverOrder.isEmpty() || allChannels.isEmpty()) {
            return;
        }
        orderApplied = true;
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < serverOrder.size(); i++) {
            rank.put(serverOrder.get(i), i);
        }
        allChannels.sort((left, right) -> {
            Integer leftRank = rank.get(left.getChannelId());
            Integer rightRank = rank.get(right.getChannelId());
            if (leftRank != null && rightRank != null) {
                return Integer.compare(leftRank, rightRank);
            }
            if (leftRank != null) {
                return -1;
            }
            if (rightRank != null) {
                return 1;
            }
            return Integer.compare(left.getNumber(), right.getNumber());
        });
    }

    private void toggleFavourite(Channel channel) {
        if (!Session.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.channels_favourites_sign_in,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!favouritesKnown) {
            Toast.makeText(this, R.string.channels_favourites_loading,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (channel.isVirtual() || channel.isSynthetic()) {
            Toast.makeText(this, R.string.channels_favourite_not_allowed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean add = !favourites.contains(channel.getNumber());
        Background.run(() -> {
            if (add) {
                accountRepository.addFavourite(channel.getNumber());
            } else {
                accountRepository.removeFavourite(channel.getNumber());
            }
            return add;
        }, new Background.Result<Boolean>() {
            @Override
            public void onSuccess(Boolean added) {
                if (added) {
                    favourites.add(channel.getNumber());
                } else {
                    favourites.remove(channel.getNumber());
                }
                applyFilter(false);
                Toast.makeText(ChannelListActivity.this,
                        getString(added ? R.string.channels_favourite_added
                                : R.string.channels_favourite_removed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ApiException error) {
                Toast.makeText(ChannelListActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showOptions() {
        if (!Session.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.channels_favourites_sign_in,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {
                getString(R.string.channels_options_reorder),
                getString(R.string.channels_options_reset_favourites),
                getString(R.string.channels_options_reset_order)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.channels_options)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        enterReorder();
                    } else if (which == 1) {
                        confirmResetFavourites();
                    } else {
                        confirmResetOrder();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void enterReorder() {
        if (allChannels.isEmpty()) {
            return;
        }
        reorderMode = true;
        reorderSnapshot = new ArrayList<>(allChannels);
        if (favouritesOnly) {
            favouritesOnly = false;
            updateFilterChip();
        }
        applyFilter(false);
        reorderPosition = grid.hasFocus() && grid.getFocusedChild() != null
                ? grid.getChildAdapterPosition(grid.getFocusedChild()) : 0;
        if (reorderPosition < 0) {
            reorderPosition = 0;
        }
        showFooter(R.string.channels_reorder_hint);
        updateOptionsLabel();
        focusPosition(reorderPosition);
    }

    private void exitReorder(boolean restore) {
        reorderMode = false;
        if (restore && reorderSnapshot != null) {
            allChannels.clear();
            allChannels.addAll(reorderSnapshot);
        }
        reorderSnapshot = null;
        hideFooter();
        applyFilter(false);
        updateOptionsLabel();
    }

    private void saveReorder() {
        List<Integer> ids = new ArrayList<>();
        for (Channel channel : allChannels) {
            ids.add(channel.getChannelId());
        }
        footerInfo.setText(R.string.channels_reorder_saving);
        Background.run(() -> {
            accountRepository.saveOrder(ids);
            return ids;
        }, new Background.Result<List<Integer>>() {
            @Override
            public void onSuccess(List<Integer> saved) {
                serverOrder.clear();
                serverOrder.addAll(saved);
                orderApplied = true;
                reorderSnapshot = null;
                reorderMode = false;
                hideFooter();
                applyFilter(false);
                updateOptionsLabel();
                Toast.makeText(ChannelListActivity.this, R.string.channels_reorder_saved,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ApiException error) {
                footerInfo.setText(R.string.channels_reorder_failed);
                Toast.makeText(ChannelListActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmResetFavourites() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.channels_reset_favourites_title)
                .setMessage(R.string.channels_reset_favourites_body)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_ok, (dialog, which) -> resetFavourites())
                .show();
    }

    private void resetFavourites() {
        List<Integer> current = new ArrayList<>(favourites);
        Background.run(() -> {
            accountRepository.resetFavourites(current);
            return current;
        }, new Background.Result<List<Integer>>() {
            @Override
            public void onSuccess(List<Integer> cleared) {
                favourites.removeAll(cleared);
                applyFilter(false);
                Toast.makeText(ChannelListActivity.this,
                        R.string.channels_favourites_cleared, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ApiException error) {
                Toast.makeText(ChannelListActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmResetOrder() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.channels_reset_order_title)
                .setMessage(R.string.channels_reset_order_body)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_ok, (dialog, which) -> resetOrder())
                .show();
    }

    private void resetOrder() {
        Background.run(() -> {
            accountRepository.resetOrder();
            return Boolean.TRUE;
        }, new Background.Result<Boolean>() {
            @Override
            public void onSuccess(Boolean ignored) {
                serverOrder.clear();
                orderApplied = false;
                allChannels.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
                applyFilter(false);
                Toast.makeText(ChannelListActivity.this, R.string.channels_order_reset,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ApiException error) {
                Toast.makeText(ChannelListActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** The same header button means "options" normally and "save" while reordering. */
    private void updateOptionsLabel() {
        optionsAction.setContentDescription(getString(reorderMode
                ? R.string.channels_options_done : R.string.channels_options));
        if (optionsAction.getChildCount() > 0
                && optionsAction.getChildAt(0) instanceof android.widget.ImageView) {
            ((android.widget.ImageView) optionsAction.getChildAt(0)).setImageResource(
                    reorderMode ? R.drawable.ic_check : R.drawable.ic_settings);
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (reorderMode && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == android.view.KeyEvent.KEYCODE_BACK) {
                exitReorder(true);
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || key == android.view.KeyEvent.KEYCODE_ENTER) {
                saveReorder();
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                int from = grid.getFocusedChild() == null ? reorderPosition
                        : grid.getChildAdapterPosition(grid.getFocusedChild());
                if (from < 0) {
                    from = reorderPosition;
                }
                int to = key == android.view.KeyEvent.KEYCODE_DPAD_UP ? from - 1 : from + 1;
                if (from >= 0 && to >= 0 && to < adapter.getItemCount()) {
                    Channel channel = allChannels.remove(from);
                    allChannels.add(to, channel);
                    adapter.move(from, to);
                    reorderPosition = to;
                    focusPosition(to);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openChannel(Channel channel) {
        Session session = Session.get(this);
        if (!channel.isFreeToAir() && !session.isLoggedIn()) {
            Toast.makeText(this, R.string.error_sign_in_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (subscriptionsKnown && !channel.isFreeToAir()
                && !subscriptions.contains(channel.getNumber())) {
            PlaybackLauncher.showLocked(this);
            return;
        }
        if (!channel.isPlayable()) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Channel> order = new ArrayList<>(adapter.current());
        ChannelQueue.set(order, order.indexOf(channel));

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

    private static final class AccountLoad {
        List<Integer> favourites = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        Set<Integer> subscriptions = new HashSet<>();
        boolean subscriptionsKnown;
    }
}
