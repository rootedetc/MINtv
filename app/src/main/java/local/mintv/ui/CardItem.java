package local.mintv.ui;

import android.content.Context;

import local.mintv.R;
import local.mintv.data.ContinueWatchingItem;
import local.mintv.data.HomeAsset;
import local.mintv.data.WatchlistItem;

/** UI projection of the shared MAXtv asset shape used by home, search, watchlist and rails. */
public final class CardItem {

    public final String id;
    public final String programId;
    public final String seriesId;
    public final String title;
    public final String subtitle;
    public final String badge;
    public final String imageUrl;
    public final String deeplink;
    public final String showType;
    public final String contentType;
    public final String channelName;
    public final int channelNumber;
    public final int progressPercent;
    public final int remainingMinutes;
    public final long runtimeSeconds;
    public final boolean adult;
    public final boolean channel;
    public final boolean series;
    public final Object source;

    private CardItem(Builder b) {
        this.id = b.id;
        this.programId = b.programId;
        this.seriesId = b.seriesId;
        this.title = b.title;
        this.subtitle = b.subtitle;
        this.badge = b.badge;
        this.imageUrl = b.imageUrl;
        this.deeplink = b.deeplink;
        this.showType = b.showType;
        this.contentType = b.contentType;
        this.channelName = b.channelName;
        this.channelNumber = b.channelNumber;
        this.progressPercent = b.progressPercent;
        this.remainingMinutes = b.remainingMinutes;
        this.runtimeSeconds = b.runtimeSeconds;
        this.adult = b.adult;
        this.channel = b.channel;
        this.series = b.series;
        this.source = b.source;
    }

    public static CardItem of(HomeAsset asset) {
        return of(asset, null);
    }

    /** Localized variant: the runtime label follows the active app language. */
    public static CardItem of(HomeAsset asset, Context context) {
        Builder b = new Builder();
        if (asset == null) {
            return new CardItem(b);
        }
        b.id = asset.id;
        b.programId = asset.programId;
        b.seriesId = asset.seriesId;
        b.title = asset.displayTitle();
        b.subtitle = context == null ? asset.meta() : asset.meta(context);
        b.badge = asset.playbackType;
        b.imageUrl = asset.image();
        b.deeplink = asset.deeplink;
        b.showType = asset.showType;
        b.contentType = asset.contentType;
        b.channelName = asset.channelName;
        b.channelNumber = asset.channelNumber;
        b.runtimeSeconds = asset.runtimeSeconds;
        b.adult = asset.isAdult || asset.adult;
        b.channel = asset.isChannel();
        b.series = asset.isSeries();
        b.source = asset;
        return new CardItem(b);
    }

    public static CardItem of(WatchlistItem item) {
        return of(item, null);
    }

    /** Localized variant: the on-demand badge follows the active app language. */
    public static CardItem of(WatchlistItem item, Context context) {
        Builder b = new Builder();
        if (item == null) {
            return new CardItem(b);
        }
        String badge = context == null ? item.badge() : item.badge(context);
        b.id = item.id;
        b.programId = item.programId;
        b.seriesId = item.seriesId;
        b.title = item.displayTitle();
        b.subtitle = meta(item, context);
        b.badge = badge;
        b.imageUrl = item.imageUrl;
        b.deeplink = item.ctaDeeplink;
        b.showType = item.showType;
        b.contentType = item.contentType;
        b.channelNumber = item.channelNumber;
        b.runtimeSeconds = item.runtimeSeconds;
        b.adult = item.isAdult;
        b.channel = false;
        b.series = item.isSeries();
        b.source = item;
        return new CardItem(b);
    }

    private static String meta(WatchlistItem item, Context context) {
        String out = "";
        if (item.releaseYear > 0) {
            out = String.valueOf(item.releaseYear);
        }
        if (item.runtimeSeconds > 0) {
            int minutes = Math.max(1, (int) (item.runtimeSeconds / 60));
            String duration = context == null
                    ? minutes + " min"
                    : context.getString(R.string.meta_minutes, minutes);
            out = out.isEmpty() ? duration : out + " · " + duration;
        }
        if (item.seasonNumber > 0) {
            String season = "S" + item.seasonNumber
                    + (item.episodeNumber > 0 ? " E" + item.episodeNumber : "");
            out = out.isEmpty() ? season : out + " · " + season;
        }
        return out;
    }

    public static CardItem of(ContinueWatchingItem item) {
        return of(item, null);
    }
    /** Localized variant: catch-up, on-demand and recording badges follow the app language. */
    public static CardItem of(ContinueWatchingItem item, Context context) {
        Builder b = new Builder();
        if (item == null) {
            return new CardItem(b);
        }
        b.id = item.id;
        b.programId = item.programId;
        b.seriesId = item.seriesId;
        b.title = item.displayTitle();
        b.subtitle = item.bookmarkType;
        b.badge = badge(context, item);
        b.imageUrl = item.imageUrl;
        b.deeplink = item.deeplink;
        b.channelName = item.channelName;
        b.channelNumber = item.channelNumber;
        b.progressPercent = item.percent();
        b.remainingMinutes = item.remainingMinutes();
        b.runtimeSeconds = item.runtimeSeconds;
        b.adult = item.isAdult;
        b.channel = false;
        b.series = false;
        b.source = item;
        return new CardItem(b);
    }

    public static CardItem channel(String title, String image, int number, String subtitle,
                                   Object source) {
        Builder b = new Builder();
        b.title = title == null ? "" : title;
        b.imageUrl = image == null ? "" : image;
        b.channelNumber = number;
        b.subtitle = subtitle == null ? "" : subtitle;
        b.channel = true;
        b.source = source;
        b.id = "channel:" + number;
        return new CardItem(b);
    }

    public static CardItem placeholder(String title) {
        Builder b = new Builder();
        b.title = title == null ? "" : title;
        return new CardItem(b);
    }

    private static String badge(Context context, ContinueWatchingItem item) {
        if (item.isCatchup()) {
            return label(context, R.string.badge_catchup, "CATCH-UP");
        }
        if (item.isVod()) {
            return label(context, R.string.badge_on_demand, "ON DEMAND");
        }
        return label(context, R.string.badge_recording, "RECORDING");
    }

    private static String label(Context context, int resource, String fallback) {
        return context == null ? fallback : context.getString(resource);
    }

    private static final class Builder {
        String id = "", programId = "", seriesId = "", title = "", subtitle = "", badge = "";
        String imageUrl = "", deeplink = "", showType = "", contentType = "", channelName = "";
        int channelNumber = -1, progressPercent, remainingMinutes;
        long runtimeSeconds;
        boolean adult, channel, series;
        Object source;
    }
}
