package local.mintv;

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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
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
import java.util.Locale;

import local.mintv.data.Channel;
import local.mintv.data.BookmarkRepository;
import local.mintv.data.ProgressUpdate;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgProgram;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.net.ConcurrencyLock;
import local.mintv.net.Http;
import local.mintv.net.PlaybackApi;
import local.mintv.player.ChannelQueue;
import local.mintv.player.QualityPreference;
import local.mintv.player.PlayerOverlay;
import local.mintv.player.TuneIdent;
import local.mintv.player.StartupPolicy;

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
public final class PlayerActivity extends BaseActivity {

    public static final String EXTRA_URL = "local.mintv.extra.URL";
    public static final String EXTRA_TITLE = "local.mintv.extra.TITLE";
    public static final String EXTRA_SUBTITLE = "local.mintv.extra.SUBTITLE";
    public static final String EXTRA_DRM_LICENSE_URL = "local.mintv.extra.DRM_LICENSE_URL";
    public static final String EXTRA_DRM_LICENSE_TOKEN = "local.mintv.extra.DRM_LICENSE_TOKEN";
    /** Complete Authorization header value for the licence request (e.g. Basic ...). */
    public static final String EXTRA_DRM_AUTH = "local.mintv.extra.DRM_AUTH";
    public static final String EXTRA_CHANNEL_NUMBER = "local.mintv.extra.CHANNEL_NUMBER";
    public static final String EXTRA_CHANNEL_TITLE = "local.mintv.extra.CHANNEL_TITLE";
    public static final String EXTRA_CHANNEL_QUALITY = "local.mintv.extra.CHANNEL_QUALITY";
    public static final String EXTRA_LIVE = "local.mintv.extra.LIVE";
    public static final String EXTRA_PROGRAMME_TITLE = "local.mintv.extra.PROGRAMME_TITLE";
    public static final String EXTRA_PROGRAMME_START = "local.mintv.extra.PROGRAMME_START";
    public static final String EXTRA_PROGRAMME_END = "local.mintv.extra.PROGRAMME_END";

    /** Continue Watching identity. Empty {@link #EXTRA_CONTENT_TYPE} means "never report". */
    public static final String EXTRA_CONTENT_TYPE = "local.mintv.extra.CONTENT_TYPE";
    public static final String EXTRA_CONTENT_ID = "local.mintv.extra.CONTENT_ID";
    public static final String EXTRA_PROGRAM_ID = "local.mintv.extra.PROGRAM_ID";
    public static final String EXTRA_MEDIA_ID = "local.mintv.extra.MEDIA_ID";
    public static final String EXTRA_STATION_ID = "local.mintv.extra.STATION_ID";
    public static final String EXTRA_SERIES_ID = "local.mintv.extra.SERIES_ID";
    public static final String EXTRA_SEASON_ID = "local.mintv.extra.SEASON_ID";
    public static final String EXTRA_LISTING_GUID = "local.mintv.extra.LISTING_GUID";
    public static final String EXTRA_RUNTIME_SECONDS = "local.mintv.extra.RUNTIME_SECONDS";
    public static final String EXTRA_RESUME_SECONDS = "local.mintv.extra.RESUME_SECONDS";
    public static final String EXTRA_CATCHUP_START = "local.mintv.extra.CATCHUP_START";
    public static final String EXTRA_CATCHUP_END = "local.mintv.extra.CATCHUP_END";
    /** The timeshift manifest is dynamic; play it from its first frame, not the live edge. */
    public static final String EXTRA_START_AT_BEGINNING = "local.mintv.extra.START_AT_BEGINNING";

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
    private boolean liveSession = true;
    private boolean overlayVisible = true;
    private PlayerOverlay overlay;
    private TuneIdent tuneIdent;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Re-reads track labels after a selection; ExoPlayer publishes new Tracks asynchronously. */
    private final Runnable refreshTracksRunnable = this::refreshPlaybackOptions;
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
    /** URL of the media item currently being played (may differ from {@link #mediaUrl} after a switch). */
    private String playbackUrl;
    private String drmLicenseUrl;
    private String drmLicenseToken;
    private String drmAuthHeader;
    private long resumePositionMs = C.TIME_UNSET;
    private boolean playWhenReady = true;
    private boolean started;
    private boolean preparingMedia;

    // --------------------------------------------------------------- bookmarks
    private BookmarkRepository bookmarkRepository;
    private String bookmarkType = "";
    private String bookmarkId = "";
    private String bookmarkProgramId = "";
    private String bookmarkMediaId = "";
    private String bookmarkStationId = "";
    private String bookmarkSeriesId = "";
    private String bookmarkSeasonId = "";
    private String bookmarkListingGuid = "";
    private long bookmarkRuntimeSeconds;
    private long bookmarkStart;
    private long bookmarkEnd;
    private volatile ProgressUpdate lastSent;
    private volatile boolean bookmarkSending;
    private static final long BOOKMARK_INTERVAL_MS = 5_000L;

    private final Runnable bookmarkTick = new Runnable() {
        @Override
        public void run() {
            if (player == null || bookmarkType.isEmpty() || !player.isPlaying()) {
                return;
            }
            long pos = player.getCurrentPosition() / 1000L;
            long dur = player.getDuration() / 1000L;
            if (pos <= 0 || dur <= 0) {
                scheduleBookmarkTick();
                return;
            }
            ProgressUpdate update = snapshot(pos, dur);
            if (update.equals(lastSent) || bookmarkSending) {
                scheduleBookmarkTick();
                return;
            }
            sendBookmark(update, true);
            scheduleBookmarkTick();
        }
    };

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
    /** Last label rendered in the overlay; compared before touching the views. */
    private String actualQuality = "";
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
                if (tuneIdent != null) {
                    tuneIdent.hide();
                }
                updateActualQuality();
                refreshPlaybackOptions();
            } else if (playbackState == Player.STATE_BUFFERING) {
                watchForSteadyStateRebuffer();
            } else if (playbackState == Player.STATE_ENDED) {
                completeBookmark();
            }
            updateState();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            updateActualQuality();
            refreshPlaybackOptions();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            updateActualQuality();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateState();
            pushOverlayPlaybackState();
            if (isPlaying) {
                bookmarksStart();
            } else {
                flushBookmark();
                handler.removeCallbacks(bookmarkTick);
            }
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
        intent.putExtra(EXTRA_TITLE, channel.getTitle(context));
        intent.putExtra(EXTRA_SUBTITLE, context.getString(
                R.string.player_channel_subtitle, channel.getNumber(), quality));
        intent.putExtra(EXTRA_CHANNEL_NUMBER, channel.getNumber());
        intent.putExtra(EXTRA_CHANNEL_TITLE, channel.getTitle(context));
        intent.putExtra(EXTRA_CHANNEL_QUALITY, quality);
        return intent;
    }

    /** Launches a channel with the selector's manifest and (when present) DRM licence. */
    public static Intent intentForPlayback(Context context, Channel channel,
                                           local.mintv.net.PlaybackApi.Playback playback) {
        Intent intent = intentForChannel(context, channel);
        intent.putExtra(EXTRA_URL, playback.manifestUrl);
        if (playback.isDrm()) {
            intent.putExtra(EXTRA_DRM_LICENSE_URL, playback.licenseUrl);
            intent.putExtra(EXTRA_DRM_AUTH, playback.licenseAuthorization);
        }
        return intent;
    }

    /** VOD / catch-up media without a channel identity (details and search watch actions). */
    public static Intent intentForMedia(Context context, String manifestUrl, String licenseUrl,
                                        String licenseAuth, String title, String subtitle) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_URL, manifestUrl);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SUBTITLE, subtitle);
        intent.putExtra(EXTRA_LIVE, false);
        if (licenseUrl != null && !licenseUrl.isEmpty()) {
            intent.putExtra(EXTRA_DRM_LICENSE_URL, licenseUrl);
            intent.putExtra(EXTRA_DRM_AUTH, licenseAuth);
        }
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
        liveSession = getIntent().getBooleanExtra(EXTRA_LIVE, true);
        currentChannel = liveSession ? ChannelQueue.current() : null;
        bookmarkRepository = new BookmarkRepository(this);
        readBookmarkExtras();

        // Rich TiviMate-style overlay owns the top-left channel card, top-right clock,
        // bottom programme card, the channel switcher and the playback settings sheet.
        overlay = new PlayerOverlay(this, findViewById(android.R.id.content),
                new PlayerOverlay.Callback() {
                    @Override
                    public void onChannelPicked(Channel channel) {
                        playChannel(channel);
                    }

                    @Override
                    public void onPlayPause() {
                        togglePlayPause();
                    }

                    @Override
                    public void onQualityPicked(QualityPreference.Quality picked) {
                        applyQualityPreference(picked);
                    }

                    @Override
                    public void onAudioTrackPicked(String optionId) {
                        applyAudioTrack(optionId);
                    }

                    @Override
                    public void onSubtitleTrackPicked(String optionId) {
                        applySubtitleTrack(optionId);
                    }
                });
        overlay.setChannelList(ChannelQueue.channels(), ChannelQueue.index());
        if (liveSession && currentChannel != null) {
            overlay.setChannel(currentChannel, null);
        } else {
            overlay.setChannelInfo(
                    getIntent().getIntExtra(EXTRA_CHANNEL_NUMBER, -1),
                    getIntent().getStringExtra(EXTRA_CHANNEL_TITLE),
                    null);
            overlay.setChannelList(java.util.Collections.emptyList(), -1);
        }
        overlay.setActualQuality(getString(R.string.player_quality_unknown));
        actualQuality = getString(R.string.player_quality_unknown);
        overlay.setLive(liveSession);
        if (!liveSession) {
            overlay.setProgramme(
                    getIntent().getStringExtra(EXTRA_PROGRAMME_TITLE),
                    null,
                    getIntent().getLongExtra(EXTRA_PROGRAMME_START, 0L),
                    getIntent().getLongExtra(EXTRA_PROGRAMME_END, 0L));
        }
        overlay.show();

        // Station ident while the first frame is acquired. It is the loading state, not a
        // minimum hold: STATE_READY removes it immediately.
        tuneIdent = new TuneIdent(this);
        ((ViewGroup) findViewById(android.R.id.content)).addView(tuneIdent,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        if (currentChannel != null) {
            local.mintv.epg.EpgProgram now = EpgCache.programmeAt(
                    currentChannel.getNumber(), System.currentTimeMillis());
            String identProgramme = now == null ? null : now.title;
            if (!liveSession) {
                String requested = getIntent().getStringExtra(EXTRA_PROGRAMME_TITLE);
                if (requested != null && !requested.isEmpty()) {
                    identProgramme = requested;
                }
            }
            tuneIdent.showChannel(currentChannel, identProgramme, liveSession);
        } else {
            String identTitle = getIntent().getStringExtra(EXTRA_TITLE);
            boolean replay = "CATCHUP".equalsIgnoreCase(
                    getIntent().getStringExtra(EXTRA_CONTENT_TYPE));
            tuneIdent.showMedia(
                    identTitle == null || identTitle.isEmpty()
                            ? getString(R.string.player_title_fallback) : identTitle,
                    getIntent().getStringExtra(EXTRA_SUBTITLE),
                    replay);
        }

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
        long requestedResume = getIntent().getLongExtra(EXTRA_RESUME_SECONDS, 0L);
        if (resumePositionMs == C.TIME_UNSET && requestedResume > 0 && !liveSession) {
            resumePositionMs = requestedResume * 1000L;
        }
        // A catch-up/start-over manifest is a rolling timeshift window. Media3 would join it
        // near the live edge, which is the wrong end of the recording; seek to the first frame
        // unless a bookmark already supplied a resume position.
        if (resumePositionMs == C.TIME_UNSET
                && getIntent().getBooleanExtra(EXTRA_START_AT_BEGINNING, false)) {
            resumePositionMs = 0L;
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
        handler.removeCallbacks(overlayTick);
        handler.post(overlayTick);
        if (overlay != null && liveSession && currentChannel != null) {
            overlay.setChannelList(ChannelQueue.channels(), ChannelQueue.index());
        }
        if (player == null && !preparingMedia) {
            prepareMedia();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        playbackGeneration++; // invalidate pending ramp/steady-state callbacks
        flushBookmark();
        handler.removeCallbacks(bookmarkTick);
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
        // The player is the owner of the account's stream reservation. Give it back when
        // the activity really goes away; a plain onStop keeps it so a resume from
        // background/Login can restart the same stream.
        ConcurrencyLock.release();
        handler.removeCallbacks(overlayTick);
        handler.removeCallbacks(startupWatchdog);
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
        if (tuneIdent != null) {
            tuneIdent.animate().cancel();
            ((ViewGroup) tuneIdent.getParent()).removeView(tuneIdent);
            tuneIdent = null;
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
        resetActualQuality();
        refreshPlaybackOptions();
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
        resetActualQuality();
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
        // Retry the channel we are actually on, not the channel this activity was born with.
        if (!TextUtils.isEmpty(playbackUrl)) {
            mediaUrl = playbackUrl;
        }
        errorPanel.setVisibility(View.GONE);
        hintView.setVisibility(View.GONE);
        prepareMedia();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // With the error sheet up, the retry button owns the D-pad. The overlay is hidden.
        if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    finish();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        // The overlay gets first refusal: it owns OK/UP/DOWN, the channel switcher and
        // the playback settings sheet.
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
            updateActualQuality();
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

    /** Resets the overlay's quality readout until the new stream produces its first frame. */
    private void resetActualQuality() {
        actualQuality = getString(R.string.player_quality_unknown);
        if (overlay != null) {
            overlay.setActualQuality(actualQuality);
        }
    }

    /**
     * Reads the resolution the decoder is actually producing. This is deliberately based on
     * {@link Player#getVideoFormat()} rather than the preference, so a fallback to 720p is
     * visible as 720p.
     */
    private void updateActualQuality() {
        if (overlay == null) {
            return;
        }
        String label = getString(R.string.player_quality_unknown);
        Format format = player == null ? null : player.getVideoFormat();
        if (format != null && format.height > 0) {
            int width = format.width;
            int height = format.height;
            if (height >= 2000) {
                label = width >= 3800 ? "4K" : "2160p";
            } else if (height >= 1000) {
                label = "1080p";
            } else if (height >= 700) {
                label = "720p";
            } else {
                label = height + "p";
            }
        }
        if (!label.equals(actualQuality)) {
            actualQuality = label;
            overlay.setActualQuality(label);
        }
    }

    /** Pushes the current quality preference and the player's audio/text track options. */
    private void refreshPlaybackOptions() {
        if (overlay == null) {
            return;
        }
        List<PlayerOverlay.TrackOption> audio = new ArrayList<>();
        List<PlayerOverlay.TrackOption> subtitles = new ArrayList<>();
        String currentAudioLabel = "";
        String currentSubtitleLabel = "";

        if (player != null) {
            List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                Tracks.Group group = groups.get(groupIndex);
                if (group == null) {
                    continue;
                }
                int type = group.getType();
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) {
                    continue;
                }
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getTrackFormat(trackIndex);
                    boolean selected = group.isTrackSelected(trackIndex);
                    String label = trackLabel(format, trackIndex);
                    String id = groupIndex + ":" + trackIndex;
                    if (type == C.TRACK_TYPE_AUDIO) {
                        audio.add(new PlayerOverlay.TrackOption(id, label, selected));
                        if (selected) {
                            currentAudioLabel = label;
                        }
                    } else {
                        subtitles.add(new PlayerOverlay.TrackOption(id, label, selected));
                        if (selected) {
                            currentSubtitleLabel = label;
                        }
                    }
                }
            }

            Format audioFormat = player.getAudioFormat();
            if (TextUtils.isEmpty(currentAudioLabel) && audioFormat != null) {
                currentAudioLabel = trackLabel(audioFormat, 0);
            }
            Format textFormat = selectedTextFormat();
            if (textFormat != null) {
                currentSubtitleLabel = trackLabel(textFormat, 0);
            } else {
                currentSubtitleLabel = "";
            }
        }
        if (TextUtils.isEmpty(currentAudioLabel)) {
            currentAudioLabel = getString(R.string.player_track_default);
        }
        overlay.setPlaybackOptions(quality, audio, subtitles,
                currentAudioLabel, currentSubtitleLabel);
    }

    /** The selected text track, or null when subtitles are off (Media3 1.4 has no getTextFormat). */
    private Format selectedTextFormat() {
        if (player == null) {
            return null;
        }
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (Tracks.Group group : groups) {
            if (group == null || group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    return group.getTrackFormat(i);
                }
            }
        }
        return null;
    }

    /** Human label for a track: its own label, else its language, else a neutral fallback. */
    private String trackLabel(Format format, int trackIndex) {
        if (format == null) {
            return getString(R.string.player_track_default);
        }
        if (!TextUtils.isEmpty(format.label)) {
            return format.label;
        }
        if (!TextUtils.isEmpty(format.language) && !"und".equalsIgnoreCase(format.language)) {
            Locale locale = Locale.getDefault();
            String display = Locale.forLanguageTag(format.language).getDisplayLanguage(locale);
            if (!TextUtils.isEmpty(display) && !"und".equalsIgnoreCase(display)) {
                return capitalize(display);
            }
            return format.language.toUpperCase(Locale.US);
        }
        String fallback = getString(R.string.player_track_default);
        return trackIndex == 0 ? fallback : fallback + " " + (trackIndex + 1);
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static int[] parseOptionId(String optionId) {
        if (TextUtils.isEmpty(optionId)) {
            return null;
        }
        int colon = optionId.indexOf(':');
        if (colon <= 0 || colon >= optionId.length() - 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(optionId.substring(0, colon)),
                    Integer.parseInt(optionId.substring(colon + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Applies a new quality preference without restarting the stream. */
    private void applyQualityPreference(QualityPreference.Quality picked) {
        if (picked == null) {
            return;
        }
        quality = picked;
        QualityPreference.set(this, picked);
        phase = StartupPolicy.Phase.STEADY;
        // AUTO may fall back again after a stall; explicit MAX/SAVER choices never do.
        steadyStartedAtMs = picked == QualityPreference.Quality.AUTO
                ? SystemClock.elapsedRealtime() : C.TIME_UNSET;
        applyTrackParameters();
        updateActualQuality();
        refreshPlaybackOptions();
    }

    private void applyAudioTrack(String optionId) {
        if (player == null) {
            return;
        }
        int[] indices = parseOptionId(optionId);
        if (indices == null) {
            return;
        }
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        if (indices[0] < 0 || indices[0] >= groups.size()) {
            return;
        }
        Tracks.Group group = groups.get(indices[0]);
        if (group == null || group.getType() != C.TRACK_TYPE_AUDIO
                || indices[1] < 0 || indices[1] >= group.length) {
            return;
        }
        TrackSelectionParameters.Builder builder =
                player.getTrackSelectionParameters().buildUpon();
        builder.setOverrideForType(new TrackSelectionOverride(
                group.getMediaTrackGroup(), indices[1]));
        player.setTrackSelectionParameters(builder.build());
        handler.removeCallbacks(refreshTracksRunnable);
        handler.postDelayed(refreshTracksRunnable, 150L);
    }

    private void applySubtitleTrack(String optionId) {
        if (player == null) {
            return;
        }
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (TextUtils.isEmpty(optionId)) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            int[] indices = parseOptionId(optionId);
            if (indices == null) {
                return;
            }
            List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
            if (indices[0] < 0 || indices[0] >= groups.size()) {
                return;
            }
            Tracks.Group group = groups.get(indices[0]);
            if (group == null || group.getType() != C.TRACK_TYPE_TEXT
                    || indices[1] < 0 || indices[1] >= group.length) {
                return;
            }
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(
                            group.getMediaTrackGroup(), indices[1]));
        }
        player.setTrackSelectionParameters(builder.build());
        handler.removeCallbacks(refreshTracksRunnable);
        handler.postDelayed(refreshTracksRunnable, 150L);
    }

    // ------------------------------------------------------------------ overlay

    /** Switches the player to {@code channel}, resolving entitlement first if needed. */
    private void playChannel(Channel channel) {
        currentChannel = channel;
        startupRetries = 0;
        mediaTitle = channel.getTitle(this);
        if (overlay != null) {
            overlay.setChannel(channel, null);
            overlay.setLive(true);
            overlay.setActualQuality(getString(R.string.player_quality_unknown));
            applyProgrammeToOverlay(channel, System.currentTimeMillis());
        }
        resetActualQuality();

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

    // --------------------------------------------------------------- bookmarks

    private void readBookmarkExtras() {
        bookmarkType = getIntent().getStringExtra(EXTRA_CONTENT_TYPE);
        if (bookmarkType == null) {
            bookmarkType = "";
        }
        bookmarkType = bookmarkType.trim().toUpperCase(java.util.Locale.US);
        if (liveSession || bookmarkType.isEmpty()
                || !("VOD".equals(bookmarkType) || "CATCHUP".equals(bookmarkType)
                || "RECORDING".equals(bookmarkType))) {
            bookmarkType = "";
            return;
        }
        bookmarkId = stringExtra(EXTRA_CONTENT_ID);
        bookmarkProgramId = stringExtra(EXTRA_PROGRAM_ID);
        bookmarkMediaId = stringExtra(EXTRA_MEDIA_ID);
        bookmarkStationId = stringExtra(EXTRA_STATION_ID);
        bookmarkSeriesId = stringExtra(EXTRA_SERIES_ID);
        bookmarkSeasonId = stringExtra(EXTRA_SEASON_ID);
        bookmarkListingGuid = stringExtra(EXTRA_LISTING_GUID);
        bookmarkRuntimeSeconds = getIntent().getLongExtra(EXTRA_RUNTIME_SECONDS, 0L);
        bookmarkStart = getIntent().getLongExtra(EXTRA_CATCHUP_START, 0L);
        bookmarkEnd = getIntent().getLongExtra(EXTRA_CATCHUP_END, 0L);
    }

    private String stringExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private void bookmarksStart() {
        if (bookmarkType.isEmpty()) {
            return;
        }
        handler.removeCallbacks(bookmarkTick);
        handler.postDelayed(bookmarkTick, BOOKMARK_INTERVAL_MS);
    }

    private void scheduleBookmarkTick() {
        handler.removeCallbacks(bookmarkTick);
        if (!bookmarkType.isEmpty()) {
            handler.postDelayed(bookmarkTick, BOOKMARK_INTERVAL_MS);
        }
    }

    private ProgressUpdate snapshot(long positionSeconds, long durationSeconds) {
        long runtime = durationSeconds > 0 ? durationSeconds : bookmarkRuntimeSeconds;
        return ProgressUpdate.builder(bookmarkType)
                .id(bookmarkId)
                .programId(bookmarkProgramId)
                .mediaId(bookmarkMediaId)
                .stationId(bookmarkStationId)
                .listingGuid(bookmarkListingGuid)
                .seriesId(bookmarkSeriesId)
                .seasonId(bookmarkSeasonId)
                .time(positionSeconds)
                .runtime(runtime)
                .startTime(bookmarkStart)
                .endTime(bookmarkEnd)
                .build();
    }

    private void sendBookmark(ProgressUpdate update, boolean tracked) {
        if (bookmarkType.isEmpty() || update == null || update.timeSeconds <= 0
                || update.runtimeSeconds <= 0) {
            return;
        }
        if (bookmarkSending) {
            pendingBookmark = update;
            return;
        }
        bookmarkSending = true;
        Background.run(() -> {
            bookmarkRepository.save(update);
            return update;
        }, new Background.Result<ProgressUpdate>() {
            @Override
            public void onSuccess(ProgressUpdate sent) {
                bookmarkSending = false;
                lastSent = sent;
                ProgressUpdate pending = pendingBookmark;
                pendingBookmark = null;
                if (pending != null && !pending.equals(sent)) {
                    sendBookmark(pending, tracked);
                }
            }

            @Override
            public void onError(ApiException error) {
                bookmarkSending = false;
                ProgressUpdate pending = pendingBookmark;
                pendingBookmark = null;
                // lastSent stays unchanged, so the next tick retries the newest snapshot.
                if (pending != null) {
                    sendBookmark(pending, tracked);
                }
            }
        });
    }

    private void flushBookmark() {
        if (player == null || bookmarkType.isEmpty()) {
            return;
        }
        long pos = player.getCurrentPosition() / 1000L;
        long dur = player.getDuration() / 1000L;
        if (pos <= 0 || dur <= 0) {
            return;
        }
        ProgressUpdate update = snapshot(pos, dur);
        if (!update.equals(lastSent)) {
            sendBookmark(update, false);
        }
    }

    private void completeBookmark() {
        if (bookmarkType.isEmpty() || player == null) {
            return;
        }
        long dur = player.getDuration() / 1000L;
        if (dur <= 0) {
            dur = bookmarkRuntimeSeconds;
        }
        if (dur <= 0) {
            return;
        }
        ProgressUpdate update = snapshot(dur, dur);
        sendBookmark(update, false);
    }

    private volatile ProgressUpdate pendingBookmark;

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
        if (overlay != null) {
            overlay.hide();
        }
        if (tuneIdent != null) {
            tuneIdent.hide();
        }
        String detail = error.getErrorCodeName();
        errorMessage.setText(TextUtils.isEmpty(detail)
                ? getString(R.string.player_error_generic)
                : getString(R.string.player_error_generic) + "\n" + detail);
        errorPanel.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
    }
}
