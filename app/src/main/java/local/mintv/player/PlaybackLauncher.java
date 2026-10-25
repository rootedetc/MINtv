package local.mintv.player;

import android.app.Activity;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import local.mintv.PlayerActivity;
import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.data.Session;
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
