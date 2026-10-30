package local.mintv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import local.mintv.PlayerActivity;
import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.data.BookmarkRepository;
import local.mintv.data.ChannelSubscriptions;
import local.mintv.data.ContinueWatchingItem;
import local.mintv.data.Session;
import local.mintv.details.WatchAction;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgProgram;
import local.mintv.net.ApiException;
import local.mintv.net.PlaybackApi;

/**
 * Single entry point for "start watching this channel", shared by the channel grid and the
 * EPG so the two screens cannot drift apart.
 *
 * <p>Free-to-air channels play straight from the public manifest. Subscription channels go
 * through the authenticated selector, which costs one round trip, so it runs asynchronously
 * and the player opens when the entitlement manifest and releasePid are known.</p>
 *
 * <p>The same contract covers the two time-shifted modes: {@link #openCatchup} for a listing
 * that has already aired and {@link #openTimeshift} for "start from the beginning" of the
 * programme currently on air. Both always require a signed-in session (catch-up is a
 * subscription feature) and are resolved off the UI thread before the player is started.</p>
 */
public final class PlaybackLauncher {

    private PlaybackLauncher() {
    }

    public static void open(Activity activity, Channel channel, List<Channel> lineUp) {
        if (channel == null) {
            return;
        }
        if (ChannelSubscriptions.isLocked(activity, channel.getNumber(), channel.isFreeToAir())) {
            showLocked(activity);
            return;
        }
        if (lineUp != null) {
            ChannelQueue.set(lineUp, lineUp.indexOf(channel));
        }

        Session session = Session.get(activity);
        if (channel.isFreeToAir()) {
            activity.startActivity(PlayerActivity.intentForChannel(activity, channel));
            return;
        }
        if (!session.isLoggedIn()) {
            Toast.makeText(activity, R.string.error_sign_in_required, Toast.LENGTH_LONG).show();
            return;
        }

        new PlaybackApi(activity).resolve(channel, new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                activity.runOnUiThread(() -> activity.startActivity(
                        PlayerActivity.intentForPlayback(activity, channel, playback)));
            }

            @Override
            public void onFailure(ApiException error) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Explains that the selected channel is outside the account's subscription. Shared by
     * every entry point so a locked channel never starts an entitlement call first.
     */
    public static void showLocked(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.channels_locked_title)
                .setMessage(R.string.channels_locked_body)
                .setPositiveButton(R.string.common_ok, null)
                .show();
    }

    /**
     * Plays a finished EPG listing from the catch-up catalogue ("watch what aired earlier").
     *
     * @param channel the EPG row the programme belongs to; its {@code stationId} and channel
     *                number are sent to the BFF
     * @param program the selected listing; {@code catchup} must be true
     */
    public static void openCatchup(Activity activity, EpgChannel channel, EpgProgram program) {
        openTimeShifted(activity, channel, program, false);
    }

    /**
     * "Start from the beginning" for the programme currently on air. Only valid while
     * {@link EpgProgram#isLive(long)} and {@link EpgProgram#catchup} are true.
     */
    public static void openTimeshift(Activity activity, EpgChannel channel, EpgProgram program) {
        openTimeShifted(activity, channel, program, true);
    }

    private static void openTimeShifted(Activity activity, EpgChannel channel, EpgProgram program,
                                        boolean startOver) {
        if (activity == null || channel == null || program == null) {
            return;
        }
        if (ChannelSubscriptions.isLocked(activity, channel.number, channel.freeToAir)) {
            showLocked(activity);
            return;
        }
        if (!Session.get(activity).isLoggedIn()) {
            Toast.makeText(activity, R.string.error_sign_in_required, Toast.LENGTH_LONG).show();
            return;
        }

        PlaybackApi api = new PlaybackApi(activity);
        PlaybackApi.Callback callback = new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                Channel playerChannel = playerChannel(channel);
                android.content.Intent intent = PlayerActivity.intentForPlayback(
                        activity, playerChannel, playback);
                intent.putExtra(PlayerActivity.EXTRA_LIVE, false);
                intent.putExtra(PlayerActivity.EXTRA_PROGRAMME_TITLE, program.title);
                intent.putExtra(PlayerActivity.EXTRA_PROGRAMME_START, program.startMs);
                intent.putExtra(PlayerActivity.EXTRA_PROGRAMME_END, program.endMs);
                // Catch-up assets arrive as a timeshift window whose live edge is the end of
                // the recording. Without an explicit start the player would join near the end.
                intent.putExtra(PlayerActivity.EXTRA_START_AT_BEGINNING, true);
                if (!startOver) {
                    ContinueWatchingItem resume = new BookmarkRepository(activity)
                            .resumeFor(program.id, program.catchupId);
                    intent.putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, "CATCHUP");
                    intent.putExtra(PlayerActivity.EXTRA_CONTENT_ID,
                            program.catchupId.isEmpty() ? program.id : program.catchupId);
                    intent.putExtra(PlayerActivity.EXTRA_PROGRAM_ID, program.id);
                    intent.putExtra(PlayerActivity.EXTRA_MEDIA_ID, program.mediaId);
                    intent.putExtra(PlayerActivity.EXTRA_STATION_ID, channel.stationId);
                    intent.putExtra(PlayerActivity.EXTRA_LISTING_GUID, program.listingGuid);
                    intent.putExtra(PlayerActivity.EXTRA_RUNTIME_SECONDS,
                            Math.max(0L, program.durationMs() / 1000L));
                    intent.putExtra(PlayerActivity.EXTRA_CATCHUP_START, program.startMs);
                    intent.putExtra(PlayerActivity.EXTRA_CATCHUP_END, program.endMs);
                    if (resume != null) {
                        intent.putExtra(PlayerActivity.EXTRA_RESUME_SECONDS,
                                resume.timeSeconds);
                    }
                }
                activity.runOnUiThread(() -> activity.startActivity(intent));
            }

            @Override
            public void onFailure(ApiException error) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show());
            }
        };
        if (startOver) {
            api.resolveTimeshift(channel, program, callback);
        } else {
            api.resolveCatchup(channel, program, callback);
        }
    }

    /**
     * VOD playback for a details/search/home watch action.
     */
    public static void openProgram(Activity activity, String programId, String mediaId,
                                   String title, String subtitle, String seriesId,
                                   String seasonId, long runtimeSeconds, long resumeSeconds) {
        if (activity == null || programId == null || programId.isEmpty()) {
            return;
        }
        if (!Session.get(activity).isLoggedIn()) {
            Toast.makeText(activity, R.string.error_sign_in_required, Toast.LENGTH_LONG).show();
            return;
        }
        new PlaybackApi(activity).resolveProgram(programId, mediaId, new PlaybackApi.Callback() {
            @Override
            public void onReady(PlaybackApi.Playback playback) {
                Intent intent = PlayerActivity.intentForMedia(activity, playback.manifestUrl,
                        playback.licenseUrl, playback.licenseAuthorization, title, subtitle);
                intent.putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, "VOD");
                intent.putExtra(PlayerActivity.EXTRA_CONTENT_ID, programId);
                intent.putExtra(PlayerActivity.EXTRA_PROGRAM_ID, programId);
                intent.putExtra(PlayerActivity.EXTRA_MEDIA_ID, mediaId);
                intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId);
                intent.putExtra(PlayerActivity.EXTRA_SEASON_ID, seasonId);
                intent.putExtra(PlayerActivity.EXTRA_RUNTIME_SECONDS, runtimeSeconds);
                intent.putExtra(PlayerActivity.EXTRA_RESUME_SECONDS, resumeSeconds);
                activity.runOnUiThread(() -> activity.startActivity(intent));
            }

            @Override
            public void onFailure(ApiException error) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Catch-up from a details action. */
    public static void openCatchup(Activity activity, WatchAction action, String programId,
                                   String title, String seriesId, long runtimeSeconds,
                                   long resumeSeconds) {
        if (activity == null || action == null) {
            return;
        }
        new PlaybackApi(activity).resolveCatchup(programId, action.stationId,
                action.startUtc, action.endUtc, action.channelNumber, action.listingGuid,
                new PlaybackApi.Callback() {
                    @Override
                    public void onReady(PlaybackApi.Playback playback) {
                        Intent intent = PlayerActivity.intentForMedia(activity,
                                playback.manifestUrl, playback.licenseUrl,
                                playback.licenseAuthorization, title,
                                activity.getString(R.string.player_time_shift));
                        intent.putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, "CATCHUP");
                        intent.putExtra(PlayerActivity.EXTRA_CONTENT_ID,
                                action.listingGuid.isEmpty() ? programId : action.listingGuid);
                        intent.putExtra(PlayerActivity.EXTRA_PROGRAM_ID, programId);
                        intent.putExtra(PlayerActivity.EXTRA_MEDIA_ID, action.mediaId);
                        intent.putExtra(PlayerActivity.EXTRA_STATION_ID, action.stationId);
                        intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId);
                        intent.putExtra(PlayerActivity.EXTRA_LISTING_GUID, action.listingGuid);
                        intent.putExtra(PlayerActivity.EXTRA_RUNTIME_SECONDS, runtimeSeconds);
                        intent.putExtra(PlayerActivity.EXTRA_RESUME_SECONDS, resumeSeconds);
                        intent.putExtra(PlayerActivity.EXTRA_CATCHUP_START, action.startUtc);
                        intent.putExtra(PlayerActivity.EXTRA_CATCHUP_END, action.endUtc);
                        intent.putExtra(PlayerActivity.EXTRA_START_AT_BEGINNING, true);
                        activity.runOnUiThread(() -> activity.startActivity(intent));
                    }

                    @Override
                    public void onFailure(ApiException error) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show());
                    }
                });
    }

    /** Continue Watching catch-up tile; the bookmark already carries every identity field. */
    public static void openCatchup(Activity activity, ContinueWatchingItem item) {
        if (activity == null || item == null) {
            return;
        }
        new PlaybackApi(activity).resolveCatchup(item.programId, item.stationId,
                item.startTime, item.endTime, item.channelNumber, item.listingGuid,
                new PlaybackApi.Callback() {
                    @Override
                    public void onReady(PlaybackApi.Playback playback) {
                        Intent intent = PlayerActivity.intentForMedia(activity,
                                playback.manifestUrl, playback.licenseUrl,
                                playback.licenseAuthorization, item.displayTitle(),
                                activity.getString(R.string.player_time_shift));
                        intent.putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, "CATCHUP");
                        intent.putExtra(PlayerActivity.EXTRA_CONTENT_ID, item.id);
                        intent.putExtra(PlayerActivity.EXTRA_PROGRAM_ID, item.programId);
                        intent.putExtra(PlayerActivity.EXTRA_MEDIA_ID, item.mediaId);
                        intent.putExtra(PlayerActivity.EXTRA_STATION_ID, item.stationId);
                        intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, item.seriesId);
                        intent.putExtra(PlayerActivity.EXTRA_SEASON_ID, item.seasonId);
                        intent.putExtra(PlayerActivity.EXTRA_LISTING_GUID, item.listingGuid);
                        intent.putExtra(PlayerActivity.EXTRA_RUNTIME_SECONDS,
                                item.runtimeSeconds);
                        intent.putExtra(PlayerActivity.EXTRA_RESUME_SECONDS, item.timeSeconds);
                        intent.putExtra(PlayerActivity.EXTRA_CATCHUP_START, item.startTime);
                        intent.putExtra(PlayerActivity.EXTRA_CATCHUP_END, item.endTime);
                        intent.putExtra(PlayerActivity.EXTRA_START_AT_BEGINNING, true);
                        activity.runOnUiThread(() -> activity.startActivity(intent));
                    }

                    @Override
                    public void onFailure(ApiException error) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show());
                    }
                });
    }

    /**
     * {@link PlayerActivity#intentForPlayback} is typed on {@link Channel}, which carries the
     * channel identity the player overlay shows. The EPG only has an {@link EpgChannel}, so
     * build the minimal equivalent; the programme title travels in its own extra so the
     * channel is never renamed to the programme. {@code Channel}'s only public factory is the
     * JSON one, hence the round trip through {@link JSONObject}.
     */
    private static Channel playerChannel(EpgChannel channel) {
        try {
            JSONObject json = new JSONObject();
            json.put("channel_number", channel.number);
            json.put("station_id", channel.stationId);
            json.put("title", channel.title);
            json.put("channel_logo", channel.logoUrl);
            json.put("quality", channel.quality);
            json.put("is_free_to_air", channel.freeToAir);
            return Channel.fromJson(json);
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot build a player channel", e);
        }
    }
}
