package local.maxtv.lite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.UnstableApi;

import java.util.HashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;

import local.maxtv.lite.data.Channel;
import local.maxtv.lite.data.Session;
import local.maxtv.lite.epg.EpgCache;
import local.maxtv.lite.epg.EpgProgram;
import local.maxtv.lite.net.ApiException;
import local.maxtv.lite.net.Http;
import local.maxtv.lite.net.PlaybackApi;
import local.maxtv.lite.player.ChannelQueue;
import local.maxtv.lite.player.PlayerOverlay;
import local.maxtv.lite.player.QualityPreference;
import local.maxtv.lite.player.StartupPolicy;

/**
 * DASH playback screen.
 *
 * <p>The same activity handles three cases:</p>
 * <ul>
 *   <li>a public clear DASH stream (the in-app "Test player" button);</li>
 *   <li>a channel's MAXtv {@code video_src_dash} URL (public, unauthenticated in M1);</li>
 *   <li>later, a DRM-protected stream: pass {@link #EXTRA_DRM_LICENSE_URL} and optionally
 *       {@link #EXTRA_DRM_LICENSE_TOKEN}. The MediaItem gets a
 *       {@link MediaItem.DrmConfiguration} for Widevine and the token becomes an
 *       {@code Authorization} header on licence requests. No licence flow is implemented
 *       in milestone 1; the seam is only wired.</li>
 * </ul>
 *
 * <p>Lifecycle: the player is created in {@code onStart} and fully released in
 * {@code onStop}; position and play state survive stop/start and returning from Login.</p>
 *
 * <p>Startup: media is handed to ExoPlayer immediately, with no manifest preflight. Media3's
 * {@code OkHttpDataSource} reports the post-redirect URI through {@link
 * androidx.media3.datasource.DataSource#getUri()}, and {@code ParsingLoadable} passes that URI to
 * {@code DashManifestParser} as the document base, so the relative segment {@code BaseURL}
 * resolves against the CDN's token URL on the first (and only) manifest request. A preflight
 * ranged GET is kept solely as a one-shot fallback for the first HTTP failure, for the
 * hypothetical case of a CDN/data-source combination that does not expose the redirect target.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "local.maxtv.lite.extra.URL";
    public static final String EXTRA_TITLE = "local.maxtv.lite.extra.TITLE";
    public static final String EXTRA_SUBTITLE = "local.maxtv.lite.extra.SUBTITLE";
    public static final String EXTRA_DRM_LICENSE_URL = "local.maxtv.lite.extra.DRM_LICENSE_URL";
    public static final String EXTRA_DRM_LICENSE_TOKEN = "local.maxtv.lite.extra.DRM_LICENSE_TOKEN";
    /** Complete Authorization header value for the licence request (e.g. Basic ...). */
    public static final String EXTRA_DRM_AUTH = "local.maxtv.lite.extra.DRM_AUTH";

    /** Public, clear DASH VOD used to prove playback independently of MAXtv/CDN changes. */
    public static final String DEMO_DASH_URL =
            "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.mpd";

    private static final long SEEK_STEP_MS = 10_000L;
    private static final long FAST_SEEK_STEP_MS = 30_000L;

    private PlayerView playerView;
    private ExoPlayer player;
    /** Media title, kept for the player's media id and channel switches. */
    private String mediaTitle;
    private TextView stateView;
    private TextView hintView;
    private TextView seekHud;
    private TextView errorMessage;
    private View errorPanel;
    private Button retryButton;
    private final List<View> overlayViews = new ArrayList<>();
    private PlaybackApi playbackApi;
    private Channel currentChannel;
    private boolean overlayVisible = true;
    private PlayerOverlay overlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideSeekHud = () -> seekHud.setVisibility(View.GONE);
    private static final long BUFFERING_BADGE_DELAY_MS = 1_500L;
    private final Runnable showBufferingBadge = () -> {
        stateView.setText(R.string.player_loading);
        stateView.setVisibility(View.VISIBLE);
    };
    private static final long OVERLAY_TIMEOUT_MS = 4000L;
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "manifest-resolver");
                thread.setDaemon(true);
                return thread;
            });

    private String mediaUrl;
    /** URL of the media item currently being played (may differ from {@link #mediaUrl} when zapping). */
    private String playbackUrl;
    private String drmLicenseUrl;
    private String drmLicenseToken;
    private String drmAuthHeader;
    private long resumePositionMs = C.TIME_UNSET;
    private boolean playWhenReady = true;
    private boolean started;
    private boolean preparingMedia;

    // --------------------------------------------------------------- fast start
    private DefaultTrackSelector trackSelector;
    private StartupPolicy.Phase phase = StartupPolicy.Phase.STARTUP;
    /** Bumped for every new media item so stale handler callbacks and retries are ignored. */
    private int playbackGeneration;
    private long prepareStartedAtMs = C.TIME_UNSET;
    private long firstFrameAtMs = C.TIME_UNSET;
    private long steadyStartedAtMs = C.TIME_UNSET;
    private boolean preflightFallbackUsed;
    private int startupRetries;
    private QualityPreference.Quality quality = QualityPreference.Quality.AUTO;
    private static final long STARTUP_WATCHDOG_MS = 6_000L;
    private static final int STARTUP_MAX_RETRIES = 2;
    private int manifestLoads;
    private boolean manifestLogged;
    private int droppedVideoFrames;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
                errorPanel.setVisibility(View.GONE);
            } else if (playbackState == Player.STATE_BUFFERING) {
                watchForSteadyStateRebuffer();
            }
            updateState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateState();
            pushOverlayPlaybackState();
            // If the ramp was postponed (paused/buffering during the startup window), retry as
            // soon as playback is actually running again so the steady state still converges.
            if (isPlaying && player != null && phase == StartupPolicy.Phase.STARTUP
                    && firstFrameAtMs != C.TIME_UNSET
                    && SystemClock.elapsedRealtime() - firstFrameAtMs
                            >= StartupPolicy.RAMP_DELAY_AFTER_FIRST_FRAME_MS) {
                checkRamp(playbackGeneration);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (retryWithPreflightIfUseful(error)) {
                return;
            }
            showError(error);
        }
    };

    /**
     * Startup instrumentation. Only three or four lines per media item, all under
     * {@link StartupPolicy#TAG}: prepare -> first frame, the ramp, and the +15 s steady state.
     */
    private final AnalyticsListener analyticsListener = new AnalyticsListener() {
        @Override
        public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
            handler.removeCallbacks(startupWatchdog);
            logFirstFrame();
        }

        @Override
        public void onLoadCompleted(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
            if (data.dataType != C.DATA_TYPE_MANIFEST) {
                return;
            }
            manifestLoads++;
            if (manifestLogged) {
                return;
            }
            manifestLogged = true;
            // info.uri is the URI the manifest was actually read from, i.e. the post-redirect
            // token URL for this CDN. The host alone is enough to see that the redirect was
            // followed; the token path itself is short-lived and is not logged.
            Log.i(StartupPolicy.TAG, "manifest loaded in " + info.loadDurationMs + " ms"
                    + " host=" + (info.uri != null ? info.uri.getHost() : "?")
                    + " bytes=" + info.bytesLoaded);
        }

        @Override
        public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
            droppedVideoFrames = droppedFrames;
        }
    };

    public static Intent intentForChannel(Context context, Channel channel) {
        String quality = TextUtils.isEmpty(channel.getQuality())
                ? "HD"
                : channel.getQuality();
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_URL, channel.getDashUrl());
        intent.putExtra(EXTRA_TITLE, channel.getTitle());
        intent.putExtra(EXTRA_SUBTITLE, context.getString(
                R.string.player_channel_subtitle, channel.getNumber(), quality));
        return intent;
    }

    /** Launches a channel with the selector's manifest and (when present) DRM licence. */
    public static Intent intentForPlayback(Context context, Channel channel,
                                           local.maxtv.lite.net.PlaybackApi.Playback playback) {
        Intent intent = intentForChannel(context, channel);
        intent.putExtra(EXTRA_URL, playback.manifestUrl);
        if (playback.isDrm()) {
            intent.putExtra(EXTRA_DRM_LICENSE_URL, playback.licenseUrl);
            intent.putExtra(EXTRA_DRM_AUTH, playback.licenseAuthorization);
        }
        return intent;
    }

    public static Intent intentForDemo(Context context) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_URL, DEMO_DASH_URL);
        intent.putExtra(EXTRA_TITLE, context.getString(R.string.player_demo_title));
        intent.putExtra(EXTRA_SUBTITLE, context.getString(R.string.player_demo_subtitle));
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        mediaUrl = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(mediaUrl)) {
            finish();
            return;
        }
        drmLicenseUrl = getIntent().getStringExtra(EXTRA_DRM_LICENSE_URL);
        drmLicenseToken = getIntent().getStringExtra(EXTRA_DRM_LICENSE_TOKEN);
        drmAuthHeader = getIntent().getStringExtra(EXTRA_DRM_AUTH);

        playerView = findViewById(R.id.player_view);
        stateView = findViewById(R.id.player_state);
        hintView = findViewById(R.id.player_hint);
        seekHud = findViewById(R.id.seek_hud);
        errorPanel = findViewById(R.id.player_error);
        errorMessage = findViewById(R.id.player_error_message);
        retryButton = findViewById(R.id.button_player_retry);
        playerView.setUseController(false);
        playbackApi = new PlaybackApi(this);
        currentChannel = ChannelQueue.current();

        // Rich TiviMate-style overlay owns the top-left channel card, top-right clock,
        // bottom programme card and the DOWN quick-switch strip.
        overlay = new PlayerOverlay(this, findViewById(android.R.id.content),
                new PlayerOverlay.Callback() {
                    @Override
                    public void onZap(int delta) {
                        zap(delta);
                    }

                    @Override
                    public void onChannelPicked(Channel channel) {
                        playChannel(channel);
                    }

                    @Override
                    public void onPlayPause() {
                        togglePlayPause();
                    }
                });
        overlay.setChannelList(ChannelQueue.channels(), ChannelQueue.index());
        if (currentChannel != null) {
            overlay.setChannel(currentChannel, resolutionLabel());
        }
        overlay.setLive(true);
        overlay.show();

        // The legacy title/subtitle header (and its top gradient scrim) is gone; the rich
        // overlay owns the channel identity. Only the loading badge remains useful.
        mediaTitle = getIntent().getStringExtra(EXTRA_TITLE) != null
                ? getIntent().getStringExtra(EXTRA_TITLE)
                : getString(R.string.player_title_fallback);
        hintView.setVisibility(View.GONE);
        overlayViews.clear();
        handler.postDelayed(overlayTick, 1000);

        retryButton.setOnClickListener(view -> restartPlayback());
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        hintView.setVisibility(visibility == View.VISIBLE
                                ? View.GONE
                                : View.VISIBLE));

        if (savedInstanceState != null) {
            long saved = savedInstanceState.getLong("position", 0);
            resumePositionMs = saved > 0 ? saved : C.TIME_UNSET;
            playWhenReady = savedInstanceState.getBoolean("playing", true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playerView == null) {
            return; // onCreate finished early (missing media URL)
        }
        started = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player == null && !preparingMedia) {
            prepareMedia();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        playbackGeneration++; // invalidate pending ramp/steady-state callbacks
        if (player != null) {
            resumePositionMs = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            playerView.setPlayer(null);
            player.removeListener(playerListener);
            player.removeAnalyticsListener(analyticsListener);
            player.release();
            player = null;
        }
        trackSelector = null;
        handler.removeCallbacks(overlayTick);
        handler.removeCallbacks(startupWatchdog);
        if (overlay != null) {
            overlay.hide();
        }
        seekHud.setVisibility(View.GONE);
        handler.removeCallbacks(hideSeekHud);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(overlayTick);
        handler.removeCallbacks(startupWatchdog);
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            playerView.setPlayer(null);
            player.removeListener(playerListener);
            player.removeAnalyticsListener(analyticsListener);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong("position", player.getCurrentPosition());
            outState.putBoolean("playing", player.getPlayWhenReady());
        }
    }

    /**
     * Starts playback immediately; no manifest round trip is spent before the player exists.
     *
     * <p>The MAXtv CDN answers the well-known {@code video_src_dash} with a 307 to a short-lived
     * token URL and the MPD uses a relative segment {@code BaseURL} ({@code dash/}). Media3
     * resolves that against the URI returned by the data source it read the manifest through:
     * {@code ParsingLoadable.load()} calls {@code dataSource.getUri()} after opening, and
     * {@code OkHttpDataSource.getUri()} returns {@code response.request().url()}, i.e. the
     * post-redirect token URL. So a single manifest request is both sufficient and correct.
     * {@link #resolvePlaybackUrl(String)} is only used by the one-shot fallback below.</p>
     */
    private void prepareMedia() {
        if (player != null) {
            return;
        }
        preparingMedia = true;
        stateView.setText(R.string.player_loading);
        stateView.setVisibility(View.VISIBLE);
        preflightFallbackUsed = false;
        createPlayer();
        preparingMedia = false;
    }

    /**
     * One-byte ranged GET that follows the CDN redirect and returns the final manifest URL.
     *
     * <p>Only called after a pre-first-frame HTTP failure, as a safety net for the case where a
     * data source or CDN does not expose the redirect target through {@code getUri()}. Never on
     * the happy path, so it cannot slow normal startup down.</p>
     */
    private static String resolvePlaybackUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "http", 0, 4)) {
            return url;
        }
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build();
        okhttp3.Call call = Http.client().newCall(request);
        call.timeout().timeout(8, java.util.concurrent.TimeUnit.SECONDS);
        try (okhttp3.Response response = call.execute()) {
            if (response.isSuccessful() || response.code() == 206) {
                String finalUrl = response.request().url().toString();
                return finalUrl.isEmpty() ? url : finalUrl;
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Fall through to the original URL; the player will report any real failure.
        }
        return url;
    }

    private void createPlayer() {
        errorPanel.setVisibility(View.GONE);
        OkHttpDataSource.Factory dataSource =
                new OkHttpDataSource.Factory(Http.client());
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSource);

        // Fast start: ramp up from the low renditions, then lock to the highest decodable one.
        // All thresholds live in StartupPolicy so they can be tuned in one place.
        quality = QualityPreference.get(this);
        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(StartupPolicy.parameters(
                trackSelector.getParameters(), StartupPolicy.Phase.STARTUP));

        ExoPlayer created = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(StartupPolicy.loadControl())
                .setHandleAudioBecomingNoisy(true)
                .build();
        created.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        created.addListener(playerListener);
        created.addAnalyticsListener(analyticsListener);
        playerView.setPlayer(created);
        playerView.requestFocus();
        player = created;
        playMedia(mediaUrl, resumePositionMs, playWhenReady);
    }

    /**
     * (Re)starts playback of {@code url} from scratch: resets the start-up policy and the
     * measurement for this media item, then calls {@code prepare()}. Used for the first tune and
     * for every zap, so both always go through the same fast-start path (DRM included).
     */
    private void playMedia(String url, long startPositionMs, boolean play) {
        if (player == null) {
            return;
        }
        playbackGeneration++;
        playbackUrl = url;
        quality = QualityPreference.get(this);
        // MAX/SAVER lock a single rendition for the whole session: no ramp, so no switch.
        phase = quality == QualityPreference.Quality.AUTO
                ? StartupPolicy.Phase.STARTUP
                : StartupPolicy.Phase.STEADY;
        applyTrackParameters();
        firstFrameAtMs = C.TIME_UNSET;
        steadyStartedAtMs = C.TIME_UNSET;
        manifestLoads = 0;
        manifestLogged = false;
        droppedVideoFrames = 0;
        prepareStartedAtMs = SystemClock.elapsedRealtime();
        Log.i(StartupPolicy.TAG, "prepare() quality=" + quality
                + " phase=" + phase
                + " startupCap=" + StartupPolicy.STARTUP_MAX_VIDEO_BITRATE / 1000 + "kbps"
                + " rampAfter=" + StartupPolicy.RAMP_DELAY_AFTER_FIRST_FRAME_MS + "ms");

        player.setMediaItem(buildMediaItem(url));
        if (startPositionMs != C.TIME_UNSET) {
            player.seekTo(startPositionMs);
        }
        player.setPlayWhenReady(play);
        handler.removeCallbacks(startupWatchdog);
        handler.postDelayed(startupWatchdog, STARTUP_WATCHDOG_MS);
        player.prepare();
        updateState();
    }

    private MediaItem buildMediaItem(String url) {
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(url)
                .setMediaId(TextUtils.isEmpty(mediaTitle) ? url : mediaTitle);

        if (!TextUtils.isEmpty(drmLicenseUrl)) {
            Map<String, String> requestHeaders = new HashMap<>();
            if (!TextUtils.isEmpty(drmAuthHeader)) {
                requestHeaders.put("Authorization", drmAuthHeader);
            } else if (!TextUtils.isEmpty(drmLicenseToken)) {
                requestHeaders.put("Authorization", "Bearer " + drmLicenseToken);
            }
            // Widevine seam. Media3 creates its HttpMediaDrmCallback from the data source
            // factory above; the owner's own session token can be supplied later through
            // EXTRA_DRM_LICENSE_TOKEN (or replaced with a custom DrmSessionManagerProvider
            // when the licence exchange needs signed request bodies).
            MediaItem.DrmConfiguration drm = new MediaItem.DrmConfiguration.Builder(
                    C.WIDEVINE_UUID)
                    .setLicenseUri(Uri.parse(drmLicenseUrl))
                    .setLicenseRequestHeaders(requestHeaders)
                    .setMultiSession(false)
                    .build();
            builder.setDrmConfiguration(drm);
        }
        return builder.build();
    }

    // ------------------------------------------------------------- fast start

    /** Applies the parameters for the current {@link #phase} to the retained track selector. */
    private void applyTrackParameters() {
        if (trackSelector == null) {
            return;
        }
        trackSelector.setParameters(StartupPolicy.parameters(
                trackSelector.getParameters(), phase, quality));
    }

    private void logFirstFrame() {
        if (player == null || firstFrameAtMs != C.TIME_UNSET) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        firstFrameAtMs = now;
        long sincePrepareMs = prepareStartedAtMs == C.TIME_UNSET ? -1 : now - prepareStartedAtMs;
        Log.i(StartupPolicy.TAG, "firstFrame " + sincePrepareMs + " ms after prepare()"
                + " video=[" + StartupPolicy.describe(player.getVideoFormat()) + "]"
                + " audio=[" + StartupPolicy.describe(player.getAudioFormat()) + "]"
                + " bufferedMs=" + player.getTotalBufferedDuration()
                + " phase=" + phase);
        final int generation = playbackGeneration;
        if (phase == StartupPolicy.Phase.STARTUP) {
            handler.postDelayed(() -> checkRamp(generation),
                    StartupPolicy.RAMP_DELAY_AFTER_FIRST_FRAME_MS);
        }
        handler.postDelayed(() -> logSteadyState(generation), StartupPolicy.STEADY_LOG_DELAY_MS);
    }

    /**
     * Switches from the capped start-up selection to a fixed "highest supported" selection once
     * there is enough buffer to absorb the bigger segment. Retries while the player is buffering
     * or the buffer is below {@link StartupPolicy#RAMP_MIN_BUFFER_MS}, up to a deadline; if it
     * never becomes healthy the capped selection is kept (better than a stall at 5.2 Mbit/s).
     */
    /**
     * The fallback used to be permanent for the whole tune, which is why playback could sit
     * at 720p forever after one hiccup. Retry the top rendition once the link has been
     * healthy for a while; a further stall simply falls back again.
     */
    private void scheduleFallbackRetry(int generation) {
        if (quality != QualityPreference.Quality.AUTO) {
            return;
        }
        handler.postDelayed(() -> {
            if (generation != playbackGeneration || player == null || !started) {
                return;
            }
            if (phase != StartupPolicy.Phase.FALLBACK) {
                return;
            }
            long buffered = player.getTotalBufferedDuration();
            if (!player.isPlaying() || buffered < 6_000L) {
                scheduleFallbackRetry(generation);
                return;
            }
            Log.i(StartupPolicy.TAG, "retrying top rendition after fallback (buffer="
                    + buffered + "ms)");
            phase = StartupPolicy.Phase.STEADY;
            steadyStartedAtMs = SystemClock.elapsedRealtime();
            applyTrackParameters();
        }, 60_000L);
    }

    private void checkRamp(int generation) {
        if (quality != QualityPreference.Quality.AUTO) {
            return;
        }
        if (generation != playbackGeneration || player == null
                || phase != StartupPolicy.Phase.STARTUP) {
            return;
        }
        boolean healthy = player.getPlaybackState() == Player.STATE_READY && player.isPlaying();
        long bufferedMs = player.getTotalBufferedDuration();
        if (healthy && bufferedMs >= StartupPolicy.RAMP_MIN_BUFFER_MS) {
            String before = StartupPolicy.describe(player.getVideoFormat());
            phase = StartupPolicy.Phase.STEADY;
            steadyStartedAtMs = SystemClock.elapsedRealtime();
            applyTrackParameters();
            Log.i(StartupPolicy.TAG, "ramp -> steady at " + (steadyStartedAtMs - firstFrameAtMs)
                    + " ms after first frame; forcedHighestSupported (was [" + before
                    + "], bufferedMs=" + bufferedMs + ")");
            return;
        }
        if (SystemClock.elapsedRealtime() - firstFrameAtMs < StartupPolicy.RAMP_DEADLINE_MS) {
            handler.postDelayed(() -> checkRamp(generation), StartupPolicy.RAMP_POLL_INTERVAL_MS);
            return;
        }
        Log.w(StartupPolicy.TAG, "ramp skipped: not healthy after "
                + StartupPolicy.RAMP_DEADLINE_MS + " ms"
                + " (state=" + player.getPlaybackState() + " bufferedMs=" + bufferedMs
                + "); keeping startup cap");
    }

    private void logSteadyState(int generation) {
        if (generation != playbackGeneration || player == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Log.i(StartupPolicy.TAG, "steady check t+" + (now - prepareStartedAtMs) + " ms"
                + " (firstFrame+" + (now - firstFrameAtMs) + " ms)"
                + " phase=" + phase
                + " video=[" + StartupPolicy.describe(player.getVideoFormat()) + "]"
                + " bufferedMs=" + player.getTotalBufferedDuration()
                + " droppedFrames=" + droppedVideoFrames
                + " playing=" + player.isPlaying()
                + " manifests=" + manifestLoads);
    }

    /**
     * One-shot protection for the steady state: if 5.2 Mbit/s cannot be sustained, fall back to
     * an ABR selection capped at 2.16 Mbit/s rather than stalling repeatedly. Ignored during the
     * first seconds after the ramp (the codec re-initialises on the switch and may briefly buffer).
     */
    private void watchForSteadyStateRebuffer() {
        if (player == null || phase != StartupPolicy.Phase.STEADY
                || steadyStartedAtMs == C.TIME_UNSET
                || SystemClock.elapsedRealtime() - steadyStartedAtMs
                        < StartupPolicy.STEADY_REBUFFER_GRACE_MS) {
            return;
        }
        final int generation = playbackGeneration;
        handler.postDelayed(() -> {
            if (generation != playbackGeneration || player == null
                    || phase != StartupPolicy.Phase.STEADY
                    || player.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }
            Log.w(StartupPolicy.TAG, "steady state stalled; falling back to cap="
                    + StartupPolicy.FALLBACK_MAX_VIDEO_BITRATE / 1000 + "kbps");
            phase = StartupPolicy.Phase.FALLBACK;
            applyTrackParameters();
            scheduleFallbackRetry(generation);
        }, StartupPolicy.STEADY_REBUFFER_CONFIRM_MS);
    }

    /**
     * If the very first manifest/segment load fails with an HTTP error, retry once through the
     * old ranged-GET preflight. This is purely a safety net; the fast path never waits for it.
     */
    private boolean retryWithPreflightIfUseful(PlaybackException error) {
        String failedUrl = playbackUrl;
        if (preflightFallbackUsed || firstFrameAtMs != C.TIME_UNSET || player == null
                || failedUrl == null || !failedUrl.regionMatches(true, 0, "http", 0, 4)) {
            return false;
        }
        int code = error.errorCode;
        if (code != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                && code != PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                && code != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
            return false;
        }
        preflightFallbackUsed = true;
        final int generation = playbackGeneration;
        Log.w(StartupPolicy.TAG, "pre-first-frame " + error.getErrorCodeName()
                + "; retrying once with preflight-resolved manifest URL");
        io.execute(() -> {
            String resolved = resolvePlaybackUrl(failedUrl);
            runOnUiThread(() -> {
                if (generation != playbackGeneration || player == null || !started
                        || isFinishing() || isDestroyed()) {
                    return;
                }
                if (TextUtils.equals(resolved, failedUrl)) {
                    showError(error);
                    return;
                }
                errorPanel.setVisibility(View.GONE);
                playMedia(resolved, C.TIME_UNSET, true);
            });
        });
        return true;
    }

    private void restartPlayback() {
        if (player != null) {
            playerView.setPlayer(null);
            player.removeListener(playerListener);
            player.removeAnalyticsListener(analyticsListener);
            player.release();
            player = null;
        }
        trackSelector = null;
        playbackGeneration++;
        resumePositionMs = C.TIME_UNSET;
        playWhenReady = true;
        preflightFallbackUsed = false;
        errorPanel.setVisibility(View.GONE);
        hintView.setVisibility(View.VISIBLE);
        prepareMedia();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The rich overlay gets first refusal: it owns OK/UP/DOWN and the quick-switch
        // strip, and it never consumes BACK unless the strip is open.
        if (overlay != null && overlay.dispatchKeyEvent(event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                finish();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(event);
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-FAST_SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(FAST_SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_STEP_MS);
                return true;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ overlay

    private final Runnable overlayTick = new Runnable() {
        @Override
        public void run() {
            pushOverlayPlaybackState();
            // Refresh now/next when a programme boundary passes.
            if (overlay != null && overlay.isVisible() && currentChannel != null) {
                applyProgrammeToOverlay(currentChannel, System.currentTimeMillis());
            }
            handler.postDelayed(this, 1000L);
        }
    };

    /** Shows the programme currently on air for {@code channel} in the bottom card. */
    private void applyProgrammeToOverlay(Channel channel, long nowMs) {
        if (overlay == null || channel == null) {
            return;
        }
        EpgProgram programme = EpgCache.programmeAt(channel.getNumber(), nowMs);
        if (programme == null) {
            overlay.setProgramme(null, null, 0L, 0L);
        } else {
            overlay.setProgramme(programme.title, null, programme.startMs, programme.endMs);
        }
    }

    /**
     * The CDN redirects to one of several token hosts and their throughput varies wildly
     * (observed 0.9 s to 25 s for the first frame). If no picture arrives quickly, ask the
     * origin for a fresh token URL and restart -- a different edge is usually fast.
     */
    private final Runnable startupWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null || firstFrameAtMs != C.TIME_UNSET
                    || isFinishing() || isDestroyed()) {
                return;
            }
            if (startupRetries >= STARTUP_MAX_RETRIES) {
                Log.w(StartupPolicy.TAG, "startup watchdog: giving up after "
                        + startupRetries + " retries");
                return;
            }
            startupRetries++;
            final int generation = playbackGeneration;
            final String failedUrl = playbackUrl;
            Log.w(StartupPolicy.TAG, "startup watchdog: no first frame after "
                    + STARTUP_WATCHDOG_MS + "ms, re-resolving token host (attempt "
                    + startupRetries + ")");
            io.execute(() -> {
                String resolved = resolvePlaybackUrl(failedUrl);
                runOnUiThread(() -> {
                    if (generation != playbackGeneration || player == null || !started
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (TextUtils.equals(resolved, failedUrl)) {
                        return;
                    }
                    errorPanel.setVisibility(View.GONE);
                    playMedia(resolved, C.TIME_UNSET, true);
                    handler.postDelayed(startupWatchdog, STARTUP_WATCHDOG_MS);
                });
            });
        }
    };

    private void pushOverlayPlaybackState() {
        if (overlay == null || player == null) {
            return;
        }
        long duration = player.getDuration();
        overlay.onPlaybackState(player.isPlaying(), player.getCurrentPosition(),
                duration == C.TIME_UNSET ? 0L : duration);
    }

    /** Resolution chip text, from the decoder's current video size when available. */
    private String resolutionLabel() {
        if (player != null) {
            int height = player.getVideoSize().height;
            if (height >= 2160) {
                return "4K";
            }
            if (height >= 1080) {
                return "FULL HD";
            }
            if (height >= 720) {
                return "HD";
            }
            if (height > 0) {
                return height + "p";
            }
        }
        if (currentChannel != null && !TextUtils.isEmpty(currentChannel.getQuality())) {
            return currentChannel.getQuality();
        }
        return "HD";
    }

    // ------------------------------------------------------------------ overlay

    // ------------------------------------------------------------------ zapping

    private void zap(int delta) {
        if (!ChannelQueue.hasNext()) {
            return;
        }
        Channel next = ChannelQueue.step(delta);
        if (next != null) {
            playChannel(next);
        }
    }

    /** Switches the player to {@code channel}, resolving entitlement first if needed. */
    private void playChannel(Channel channel) {
        currentChannel = channel;
        startupRetries = 0;
        String quality = TextUtils.isEmpty(channel.getQuality()) ? "HD" : channel.getQuality();
        mediaTitle = channel.getTitle();
        if (overlay != null) {
            overlay.setChannel(channel, quality);
            overlay.setLive(true);
            applyProgrammeToOverlay(channel, System.currentTimeMillis());
        }

        Session session = Session.get(this);
        if (channel.isFreeToAir()) {
            switchMedia(channel.getDashUrl(), "", "");
            return;
        }
        if (!session.isLoggedIn()) {
            if (overlay != null) {
                overlay.hide();
            }
            errorMessage.setText(R.string.error_sign_in_required);
            errorPanel.setVisibility(View.VISIBLE);
            return;
        }
        stateView.setText(R.string.player_loading);
        stateView.setVisibility(View.VISIBLE);
        playbackApi.resolve(channel, new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                runOnUiThread(() -> switchMedia(playback.manifestUrl,
                        playback.licenseUrl, playback.licenseAuthorization));
            }

            @Override
            public void onFailure(ApiException error) {
                runOnUiThread(() -> {
                    if (overlay != null) {
                        overlay.hide();
                    }
                    errorMessage.setText(String.valueOf(error.getMessage()));
                    errorPanel.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void switchMedia(String url, String licenseUrl, String auth) {
        if (player == null) {
            return;
        }
        drmLicenseUrl = licenseUrl;
        drmLicenseToken = null;
        drmAuthHeader = auth;
        errorPanel.setVisibility(View.GONE);
        playWhenReady = true;
        // Re-runs the start-up policy for the new channel: capped start, then ramp to highest.
        playMedia(url, C.TIME_UNSET, true);
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekToDefaultPosition();
            }
            player.play();
        }
        updateState();
    }

    private void seekBy(long deltaMs) {
        if (player == null) {
            return;
        }
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET && duration > 0) {
            target = Math.min(target, duration);
        }
        player.seekTo(target);
        showSeekHud(deltaMs < 0
                ? getString(R.string.player_seek_back)
                : getString(R.string.player_seek_forward));
    }

    private void showSeekHud(String label) {
        seekHud.setText(label);
        seekHud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekHud);
        handler.postDelayed(hideSeekHud, 800L);
    }

    private void updateState() {
        if (player == null) {
            return;
        }
        boolean live = player.isCurrentMediaItemLive();
        int playbackState = player.getPlaybackState();

        // The rich overlay owns the LIVE/TIME SHIFT pill and play state. This badge is
        // only the initial loading indicator. After the first frame, buffering is almost
        // always a quality switch (one or two frames), so debounce it instead of flashing
        // "loading" on every rendition change.
        boolean buffering = playbackState == Player.STATE_BUFFERING
                || playbackState == Player.STATE_IDLE;
        if (buffering && firstFrameAtMs == C.TIME_UNSET) {
            handler.removeCallbacks(showBufferingBadge);
            stateView.setText(R.string.player_loading);
            stateView.setVisibility(View.VISIBLE);
        } else if (buffering) {
            handler.removeCallbacks(showBufferingBadge);
            handler.postDelayed(showBufferingBadge, BUFFERING_BADGE_DELAY_MS);
        } else {
            handler.removeCallbacks(showBufferingBadge);
            stateView.setVisibility(View.GONE);
        }
    }

    private void showError(PlaybackException error) {
        stateView.setVisibility(View.GONE);
        hintView.setVisibility(View.GONE);
        String detail = error.getErrorCodeName();
        errorMessage.setText(TextUtils.isEmpty(detail)
                ? getString(R.string.player_error_generic)
                : getString(R.string.player_error_generic) + "\n" + detail);
        errorPanel.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
    }
}
