package local.maxtv.lite;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import local.maxtv.lite.data.Channel;
import local.maxtv.lite.net.ApiException;
import local.maxtv.lite.net.PlaybackApi;
import local.maxtv.lite.player.ChannelQueue;
import local.maxtv.lite.data.ChannelRepository;
import local.maxtv.lite.data.Session;
import local.maxtv.lite.ui.ChannelAdapter;

/**
 * Launcher screen: cached-first channel grid with a background refresh.
 *
 * <p>Deliberately does no blocking work in {@link #onCreate}. The repository parses the
 * cached payload and hits the network on its own thread, then calls back on the main
 * thread. That keeps the TV cold start to one layout pass.</p>
 */
public final class ChannelListActivity extends Activity implements ChannelRepository.Listener {

    private static final String TAG = "ChannelList";

    private RecyclerView grid;
    private ChannelAdapter adapter;
    private TextView status;
    private TextView footerInfo;
    private View emptyState;
    private ProgressBar progress;
    private ChannelRepository repository;
    private PlaybackApi playbackApi;
    private boolean firstDataDelivered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);

        status = findViewById(R.id.status);
        footerInfo = findViewById(R.id.footer_info);
        emptyState = findViewById(R.id.empty_state);
        progress = findViewById(R.id.progress);
        grid = findViewById(R.id.channel_grid);

        int spanCount = Math.max(4, Math.min(7,
                getResources().getConfiguration().screenWidthDp / 175));
        grid.setLayoutManager(new GridLayoutManager(this, spanCount));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        adapter = new ChannelAdapter(this::openChannel);
        playbackApi = new PlaybackApi(this);
        grid.setAdapter(adapter);

        findViewById(R.id.button_sign_in).setOnClickListener(
                view -> startActivity(new Intent(this, LoginActivity.class)));
        findViewById(R.id.button_demo).setOnClickListener(
                view -> startActivity(PlayerActivity.intentForDemo(this)));
        findViewById(R.id.button_epg).setOnClickListener(
                view -> startActivity(new Intent(this, EpgActivity.class)));
        findViewById(R.id.button_refresh).setOnClickListener(
                view -> load(true));
        findViewById(R.id.button_retry).setOnClickListener(
                view -> load(true));

        repository = ChannelRepository.get(this);
        load(false);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        warnIfVpn();
        Session session = Session.get(this);
        footerInfo.setText(session.isLoggedIn()
                ? getString(R.string.footer_signed_in,
                        session.displayName().isEmpty()
                                ? session.username()
                                : session.displayName())
                : getString(R.string.channel_footer_version));
    }

    /**
     * MAXtv's CDN refuses requests from VPN egress (verified on device: NordVPN's
     * tun0 makes every manifest 404). Surface that instead of a bare player error.
     */
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

    private void load(boolean forceRefresh) {
        emptyState.setVisibility(View.GONE);
        if (adapter.getItemCount() == 0) {
            progress.setVisibility(View.VISIBLE);
            status.setText(R.string.status_loading);
        } else if (forceRefresh) {
            status.setText(R.string.status_refreshing);
        }
        repository.load(forceRefresh, this);
    }

    @Override
    public void onChannels(List<Channel> channels, boolean fromCache) {
        progress.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);

        View focusedChild = grid.getFocusedChild();
        int focusedPosition = focusedChild == null
                ? RecyclerView.NO_POSITION
                : grid.getChildAdapterPosition(focusedChild);

        boolean unchanged = adapter.hasSameContent(channels);
        if (!unchanged) {
            adapter.submit(channels);
        }
        status.setText(fromCache
                ? getResources().getQuantityString(
                        R.plurals.status_cached, channels.size(), channels.size())
                : getResources().getQuantityString(
                        R.plurals.status_live, channels.size(), channels.size()));

        if (unchanged) {
            if (!firstDataDelivered) {
                firstDataDelivered = true;
                // The header buttons are focusable, so the framework gives them initial
                // focus before the grid is populated. Move focus to the first card once
                // data exists, otherwise the remote starts on "Refresh".
                focusPosition(0);
            }
            return;
        }
        if (focusedPosition != RecyclerView.NO_POSITION) {
            // Keep the DPAD on the same card when the background refresh rebinds the grid.
            focusPosition(focusedPosition);
        } else if (!firstDataDelivered && !channels.isEmpty()) {
            firstDataDelivered = true;
            // First paint: the framework hands initial focus to the header buttons
            // before the grid has children, so claim it back for the first card.
            focusPosition(0);
        }
    }

    /**
     * Gives focus to an actual card, not the RecyclerView itself. Requesting focus on the
     * already-focused RecyclerView is a no-op, which would leave no visible focus ring on
     * the first TV paint.
     */
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

    private void focusPositionIfGridFocused(int position) {
        View current = getCurrentFocus();
        if (current == null || current == grid) {
            focusPosition(position);
        }
    }

    @Override
    public void onError(String message, Throwable cause) {
        Log.w(TAG, "Channel load failed: " + message, cause);
        progress.setVisibility(View.GONE);
        if (adapter.getItemCount() == 0) {
            status.setText(R.string.status_offline_short);
            emptyState.setVisibility(View.VISIBLE);
            findViewById(R.id.button_retry).post(
                    () -> findViewById(R.id.button_retry).requestFocus());
        } else {
            status.setText(getResources().getQuantityString(
                    R.plurals.status_offline, adapter.getItemCount(),
                    adapter.getItemCount()));
        }
    }

    private void openChannel(Channel channel) {
        if (!channel.isPlayable()) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        // Free-to-air channels play straight from the public manifest. Subscription
        // channels must go through the selector (/media) so the CDN hands back an
        // entitlement-tagged manifest and the releasePid needed for the licence.
        List<Channel> order = adapter.current();
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
        status.setText(R.string.status_starting);
        playbackApi.resolve(channel, new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                runOnUiThread(() -> startActivity(
                        PlayerActivity.intentForPlayback(ChannelListActivity.this, channel, playback)));
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
