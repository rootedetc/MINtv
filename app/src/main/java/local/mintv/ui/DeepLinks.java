package local.mintv.ui;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;

import java.util.List;

import local.mintv.ChannelListActivity;
import local.mintv.ContentDetailActivity;
import local.mintv.R;
import local.mintv.SubpageActivity;
import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ContinueWatchingItem;
import local.mintv.data.HomeAsset;
import local.mintv.player.PlaybackLauncher;

/** Central router for the server's {@code magiotv://} contracts. */
public final class DeepLinks {

    private DeepLinks() {
    }

    public static void openAsset(Activity activity, HomeAsset asset) {
        if (asset == null) {
            return;
        }
        if (!asset.deeplink.isEmpty() && open(activity, asset.deeplink)) {
            return;
        }
        if (asset.isChannel() && asset.channelNumber >= 0) {
            openChannel(activity, asset.channelNumber);
            return;
        }
        if (!asset.id.isEmpty()) {
            activity.startActivity(ContentDetailActivity.intent(activity, asset.id,
                    asset.isSeries(), asset.displayTitle(), TvRail.Tab.HOME));
        }
    }


    public static void openContinueWatching(Activity activity, ContinueWatchingItem item) {
        if (item == null) {
            return;
        }
        if (item.isCatchup()) {
            PlaybackLauncher.openCatchup(activity, item);
            return;
        }
        if (!item.deeplink.isEmpty() && open(activity, item.deeplink)) {
            return;
        }
        String id = item.programId.isEmpty() ? item.id : item.programId;
        if (!id.isEmpty()) {
            activity.startActivity(ContentDetailActivity.intent(activity, id, false,
                    item.displayTitle(), TvRail.Tab.HOME));
        }
    }

    public static boolean open(Activity activity, String deeplink) {
        if (deeplink == null || deeplink.trim().isEmpty()) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(deeplink.trim());
        } catch (RuntimeException e) {
            return false;
        }
        String host = uri.getHost();
        List<String> segments = uri.getPathSegments();
        if ("details".equalsIgnoreCase(host)) {
            if (segments.isEmpty()) {
                return false;
            }
            boolean series = "series".equalsIgnoreCase(segments.get(0));
            String id = series && segments.size() > 1 ? segments.get(1) : segments.get(0);
            if (id.isEmpty()) {
                return false;
            }
            String title = uri.getQueryParameter("name");
            activity.startActivity(ContentDetailActivity.intent(activity, id, series,
                    title == null ? "" : title, TvRail.Tab.HOME));
            return true;
        }
        if ("subpage".equalsIgnoreCase(host)) {
            if (segments.isEmpty()) {
                return false;
            }
            String title = uri.getQueryParameter("title");
            activity.startActivity(SubpageActivity.intent(activity, segments.get(0),
                    title == null ? "" : title,
                    uri.getQueryParameter("end_point"),
                    uri.getQueryParameter("type"),
                    uri.getQueryParameter("store_id")));
            return true;
        }
        if ("home".equalsIgnoreCase(host)) {
            String page = uri.getQueryParameter("page");
            activity.startActivity(local.mintv.HomeActivity.intent(activity, page));
            return true;
        }
        if ("player".equalsIgnoreCase(host)) {
            String number = uri.getQueryParameter("channelNumber");
            if (number != null) {
                try {
                    openChannel(activity, Integer.parseInt(number));
                    return true;
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return false;
    }

    private static void openChannel(Activity activity, int channelNumber) {
        List<Channel> channels = ChannelRepository.get(activity).cachedChannels();
        if (channels.isEmpty()) {
            ChannelRepository.get(activity).load(false, new ChannelRepository.Listener() {
                @Override
                public void onChannels(List<Channel> loaded, boolean fromCache) {
                    Channel found = find(loaded, channelNumber);
                    if (found != null) {
                        PlaybackLauncher.open(activity, found, loaded);
                    }
                }

                @Override
                public void onError(String message, Throwable cause) {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        Channel found = find(channels, channelNumber);
        if (found == null) {
            Toast.makeText(activity, R.string.channels_empty_filter, Toast.LENGTH_SHORT).show();
            return;
        }
        PlaybackLauncher.open(activity, found, channels);
    }

    private static Channel find(List<Channel> channels, int number) {
        for (Channel channel : channels) {
            if (channel.getNumber() == number) {
                return channel;
            }
        }
        return null;
    }
}
