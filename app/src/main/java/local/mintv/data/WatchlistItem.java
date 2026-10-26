package local.mintv.data;

import android.content.Context;

import org.json.JSONObject;

import local.mintv.R;
import local.mintv.net.BffJson;

/** One item of {@code GET /watchlist/v2/items} or {@code /watchlist/v3/items}. */
public final class WatchlistItem {

    public final String id;
    public final String showType;
    public final String contentType;
    public final String name;
    public final String title;
    public final String imageUrl;
    public final String ctaDeeplink;
    public final String playbackType;
    public final long runtimeSeconds;
    public final int releaseYear;
    public final String ratings;
    public final int seasonNumber;
    public final int episodeNumber;
    public final String startUtc;
    public final String endUtc;
    public final boolean isAdult;
    public final String seriesId;
    public final String programId;
    public final int channelNumber;
    public final String channelLogo;
    public final String entityType;

    public transient boolean selected;

    private WatchlistItem(Builder b) {
        this.id = b.id;
        this.showType = b.showType;
        this.contentType = b.contentType;
        this.name = b.name;
        this.title = b.title;
        this.imageUrl = b.imageUrl;
        this.ctaDeeplink = b.ctaDeeplink;
        this.playbackType = b.playbackType;
        this.runtimeSeconds = b.runtimeSeconds;
        this.releaseYear = b.releaseYear;
        this.ratings = b.ratings;
        this.seasonNumber = b.seasonNumber;
        this.episodeNumber = b.episodeNumber;
        this.startUtc = b.startUtc;
        this.endUtc = b.endUtc;
        this.isAdult = b.isAdult;
        this.seriesId = b.seriesId;
        this.programId = b.programId;
        this.channelNumber = b.channelNumber;
        this.channelLogo = b.channelLogo;
        this.entityType = b.entityType;
    }

    public static WatchlistItem fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new WatchlistItem(b);
        }
        Cta cta = Cta.fromJson(json.optJSONObject("cta"));
        b.id = BffJson.str(json, "id");
        b.showType = BffJson.str(json, "show_type", "showType");
        b.contentType = BffJson.str(json, "content_type", "contentType");
        b.name = BffJson.str(json, "name");
        b.title = BffJson.str(json, "title", "custom_title", "customTitle");
        b.imageUrl = BffJson.image(json);
        b.ctaDeeplink = cta == null ? "" : cta.deeplink;
        b.playbackType = BffJson.str(json, "playback_type", "playbackType");
        b.runtimeSeconds = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds");
        b.releaseYear = BffJson.integer(json, 0, "release_year", "releaseYear");
        b.ratings = BffJson.str(json, "ratings");
        b.seasonNumber = BffJson.integer(json, 0, "season_number", "seasonNumber");
        b.episodeNumber = BffJson.integer(json, 0, "episode_number", "episodeNumber");
        b.startUtc = BffJson.str(json, "start_utc", "startUtc", "start_time");
        b.endUtc = BffJson.str(json, "end_utc", "endUtc", "end_time");
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult", "adult");
        b.seriesId = BffJson.str(json, "series_id", "seriesId");
        b.programId = BffJson.str(json, "program_id", "programId");
        b.channelNumber = BffJson.integer(json, -1, "channel_number", "channelNumber");
        b.channelLogo = BffJson.str(json, "channel_logo", "channelLogo");
        b.entityType = BffJson.str(json, "entity_type", "entityType");
        return new WatchlistItem(b);
    }

    public String displayTitle() {
        if (!name.isEmpty()) {
            return name;
        }
        if (!title.isEmpty()) {
            return title;
        }
        return "";
    }

    /** Bulk delete uses the item's own {@code show_type || content_type}. */
    public String deleteType() {
        if (!showType.isEmpty()) {
            return showType;
        }
        return contentType;
    }

    public boolean isSeries() {
        return "Series".equalsIgnoreCase(entityType)
                || "TVShow".equalsIgnoreCase(showType)
                || !seriesId.isEmpty() && !"Movie".equalsIgnoreCase(showType);
    }

    public String badge() {
        if ("VOD".equalsIgnoreCase(playbackType)) {
            return "ON DEMAND";
        }
        if ("TV".equalsIgnoreCase(playbackType)) {
            return "TV";
        }
        return "";
    }

    /** Localized variant: the on-demand badge follows the active app language. */
    public String badge(Context context) {
        if ("VOD".equalsIgnoreCase(playbackType)) {
            return context.getString(R.string.badge_on_demand);
        }
        return badge();
    }

    private static final class Builder {
        String id = "", showType = "", contentType = "", name = "", title = "";
        String imageUrl = "", ctaDeeplink = "", playbackType = "", ratings = "";
        String startUtc = "", endUtc = "", seriesId = "", programId = "", channelLogo = "";
        String entityType = "";
        long runtimeSeconds;
        int releaseYear, seasonNumber, episodeNumber, channelNumber = -1;
        boolean isAdult;
    }
}
