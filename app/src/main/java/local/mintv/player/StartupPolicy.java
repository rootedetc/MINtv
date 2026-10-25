package local.mintv.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * Single place to tune live-playback startup for the MAXtv DASH channels.
 *
 * <p>Why a ramp is needed: the Broadpeak packager publishes each channel at six video bitrates
 * (627_600, 1_036_400, 1_547_200, 2_160_000, 3_182_000 and 5_225_200 bit/s). Media3 has to buffer
 * {@code bufferForPlaybackMs} of media before it renders the first frame, so a player that starts
 * on the 5.2 Mbit/s rendition has to download several megabytes (2-3 x ~1.3 MB segments) before
 * any picture appears. On a TV link that delivers a few Mbit/s that is where the 10-20 s start
 * comes from. Starting on the lower renditions cuts the bytes needed for the first frame by
 * roughly 3-8x, then the policy switches to the highest decodable rendition once playback is
 * healthy and keeps it there (no ABR flapping).</p>
 *
 * <p>Phases:</p>
 * <ol>
 *   <li>{@link Phase#STARTUP} - adaptive selection limited to the low renditions (627k/1M/1.5M).
 *       Media3's {@code AdaptiveTrackSelection} cannot climb above the cap, so both the first
 *       segment and any adaptation during startup stay small.</li>
 *   <li>{@link Phase#STEADY} - {@code forceHighestSupportedBitrate}, i.e. a fixed selection of
 *       the highest rendition the renderer reports as supported (5.2 Mbit/s on capable TVs).
 *       Fixed means no up/down switching, which is the user-visible quality preference.</li>
 *   <li>{@link Phase#FALLBACK} - only entered if playback stalls after the ramp (network cannot
 *       sustain 5.2 Mbit/s). Adaptive again, capped at 2.16 Mbit/s, and it does not ramp again,
 *       so it settles instead of oscillating.</li>
 * </ol>
 */
public final class StartupPolicy {

    /** Logcat tag for start-up measurements. Keep this string stable for `adb logcat -s`. */
    public static final String TAG = "MinTvStartup";

    /** Playback phases. See the class comment. */
    public enum Phase {
        STARTUP,
        STEADY,
        FALLBACK
    }

    /**
     * Video bitrate ceiling (bit/s) while starting. The MPD's renditions are 627_600, 1_036_400,
     * 1_547_200, 2_160_000, 3_182_000 and 5_225_200 bit/s, so this keeps the three cheapest
     * renditions adaptively selectable and excludes everything from 2.16 Mbit/s up.
     *
     * <p>Note this is a {@code max}, not {@code forceLowestBitrate}: on a slow link ABR can still
     * drop to 627k, while a fast link may use 1.5M for the very first segment. Either way the
     * first picture needs at most ~0.8 MB instead of ~2.6 MB.</p>
     */
    public static final int STARTUP_MAX_VIDEO_BITRATE = 1_600_000;

    /**
     * Ceiling used if the stream stalls after the ramp. Adaptive within 627k..2.16M so playback
     * recovers and stays stable instead of bouncing between the top renditions.
     */
    public static final int FALLBACK_MAX_VIDEO_BITRATE = 2_160_000;

    /** Wait after the first rendered frame before attempting the jump to the top rendition. */
    public static final long RAMP_DELAY_AFTER_FIRST_FRAME_MS = 3_000L;

    /**
     * Minimum buffered media (ms) required before ramping. With 2 s segments this is two
     * segments. It is deliberately below the ~6 s live-window cushion Media3 keeps for a dynamic
     * MPD (3 x segment duration), otherwise the condition would never be met on live playback.
     * The one-shot fallback covers links that cannot hold the top rendition after the switch.
     */
    public static final long RAMP_MIN_BUFFER_MS = 4_000L;

    /** Re-check cadence while waiting for the buffer/playback state to be healthy enough. */
    public static final long RAMP_POLL_INTERVAL_MS = 500L;

    /**
     * Give up ramping if the buffer never becomes healthy within this window after the first
     * frame. Staying on the startup cap is better than forcing a bitrate the link cannot carry.
     */
    public static final long RAMP_DEADLINE_MS = 25_000L;

    /** Log the selected track/bitrate once this long after the first frame. */
    public static final long STEADY_LOG_DELAY_MS = 15_000L;

    /** Ignore stalls during the first seconds of steady state (codec re-init on the switch). */
    public static final long STEADY_REBUFFER_GRACE_MS = 8_000L;

    /** A stall must last this long before the fallback phase is entered. */
    public static final long STEADY_REBUFFER_CONFIRM_MS = 2_000L;

    /**
     * Media3's default is 2_500 ms, which on this packager (2 s segments) means two full segments
     * must be downloaded before the first frame. 1_500 ms is crossed by the first segment alone,
     * with some slack for a last sample that lands a frame or two before the segment end. The
     * ramp below waits for a much larger buffer before raising the bitrate.
     */
    public static final int STARTUP_BUFFER_FOR_PLAYBACK_MS = 1_500;

    /** Resuming after a rebuffer: 3 s (Media3 default 5 s) is two segments on this stream. */
    public static final int STARTUP_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000;

    private StartupPolicy() {
    }

    /**
     * Returns {@code base} with the video constraints for {@code phase} applied.
     *
     * <p>{@code exceedVideoConstraintsIfNecessary} is deliberately left at Media3's default
     * ({@code true}): if a stream had no rendition under the cap, the selector would still fall
     * back to its lowest rendition instead of selecting no video at all. The renderer-capability
     * policy is kept from the previous behaviour ({@code false}).</p>
     */
    public static DefaultTrackSelector.Parameters parameters(
            DefaultTrackSelector.Parameters base, Phase phase) {
        return parameters(base, phase, QualityPreference.Quality.AUTO);
    }

    public static DefaultTrackSelector.Parameters parameters(
            DefaultTrackSelector.Parameters base, Phase phase,
            QualityPreference.Quality quality) {
        DefaultTrackSelector.Parameters.Builder builder = base.buildUpon()
                .setForceLowestBitrate(false)
                .setExceedRendererCapabilitiesIfNecessary(false);

        // Explicit user choice overrides the automatic phase machine.
        if (quality == QualityPreference.Quality.MAX) {
            return builder
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoBitrate(Integer.MAX_VALUE)
                    .build();
        }
        if (quality == QualityPreference.Quality.SAVER) {
            return builder
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoBitrate(FALLBACK_MAX_VIDEO_BITRATE)
                    .build();
        }

        switch (phase) {
            case STEADY:
                // Fixed selection of the highest decodable rendition: no ABR, no oscillation.
                return builder
                        .setForceHighestSupportedBitrate(true)
                        .setMaxVideoBitrate(Integer.MAX_VALUE)
                        .build();
            case FALLBACK:
                // Deterministic single rendition under the cap: one visible switch instead
                // of ABR stepping through several resolutions.
                return builder
                        .setForceHighestSupportedBitrate(true)
                        .setMaxVideoBitrate(FALLBACK_MAX_VIDEO_BITRATE)
                        .build();
            case STARTUP:
            default:
                // Deterministic highest rendition under the cap. ABR here would step
                // 360p -> 576p -> 720p, and every step is a decoder re-init (a visible
                // blip). One fixed rendition means exactly one switch later, at the ramp.
                return builder
                        .setForceHighestSupportedBitrate(true)
                        .setMaxVideoBitrate(STARTUP_MAX_VIDEO_BITRATE)
                        .build();
        }
    }

    /**
     * Load control with a shorter start threshold. Only {@code bufferForPlayback*} is changed;
     * the 50 s min/max buffer sizing is left at the Media3 defaults.
     */
    public static DefaultLoadControl loadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        STARTUP_BUFFER_FOR_PLAYBACK_MS,
                        STARTUP_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();
    }

    /** Compact one-line description of a track for logcat, e.g. {@code 6 5225kbps 1920x1080 avc1}. */
    public static String describe(@Nullable Format format) {
        if (format == null) {
            return "none";
        }
        StringBuilder builder = new StringBuilder(48);
        builder.append(format.id != null ? format.id : "?");
        if (format.bitrate != Format.NO_VALUE) {
            builder.append(' ').append(format.bitrate / 1000).append("kbps");
        }
        if (format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
            builder.append(' ').append(format.width).append('x').append(format.height);
        }
        if (format.codecs != null) {
            builder.append(' ').append(format.codecs);
        }
        return builder.toString();
    }
}
