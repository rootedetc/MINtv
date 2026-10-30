package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.R;
import local.mintv.net.BffJson;

/**
 * One card from a home rail, search result<br> or related feed.
 *
 * <p>All six specs read the same asset shape, so one tolerant model serves home, search,
 * details rails. Fields absent from the response stay empty or zero; no caller may
 * assume one exists.</p>
 */
public final class HomeAsset {

    public final String id;
    public final String programId;
    public final String title;
    public final String name;
    public final String customTitle;
    public final String showType;
    public final String type;
    public final String contentType;
    public final String entityType;
    public final String thumbnail;
    public final String imageUrl;
    public final String posterImageUrl;
    public final String portraitThumbnail;
    public final String channelLogo;
    public final String channelName;
    public final String stationId;
    public final String seriesId;
    public final String ratings;
    public final String playbackType;
    public final String description;
    public final String deeplink;
    public final int channelNumber;
    public final int position;
    public final int releaseYear;
    public final long runtimeSeconds;
    public final int seasonNumber;
    public final int episodeNumber;
    public final boolean isAdult;
    public final boolean adult;
    public final boolean isCustomTile;
    public final long startTime;
    public final long endTime;
    public final List<WarningLabel> warningLabels;
    public final Cta cta;

    private HomeAsset(Builder b) {
        this.id = b.id;
        this.programId = b.programId;
        this.title = b.title;
        this.name = b.name;
        this.customTitle = b.customTitle;
        this.showType = b.showType;
        this.type = b.type;
        this.contentType = b.contentType;
        this.entityType = b.entityType;
        this.thumbnail = b.thumbnail;
        this.imageUrl = b.imageUrl;
        this.posterImageUrl = b.posterImageUrl;
        this.portraitThumbnail = b.portraitThumbnail;
        this.channelLogo = b.channelLogo;
        this.channelName = b.channelName;
        this.stationId = b.stationId;
        this.seriesId = b.seriesId;
        this.ratings = b.ratings;
        this.playbackType = b.playbackType;
        this.description = b.description;
        this.deeplink = b.deeplink;
        this.channelNumber = b.channelNumber;
        this.position = b.position;
        this.releaseYear = b.releaseYear;
        this.runtimeSeconds = b.runtimeSeconds;
        this.seasonNumber = b.seasonNumber;
        this.episodeNumber = b.episodeNumber;
        this.isAdult = b.isAdult;
        this.adult = b.adult;
        this.isCustomTile = b.isCustomTile;
        this.startTime = b.startTime;
        this.endTime = b.endTime;
        this.warningLabels = b.warningLabels;
        this.cta = b.cta;
    }

    public static HomeAsset fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new HomeAsset(b);
        }
        Cta cta = Cta.fromJson(json.optJSONObject("cta"));
        b.id = BffJson.str(json, "id");
        b.programId = BffJson.str(json, "program_id", "programId");
        b.title = BffJson.str(json, "title");
        b.name = BffJson.str(json, "name", "person_name");
        b.customTitle = BffJson.str(json, "custom_title", "customTitle");
        b.showType = BffJson.str(json, "show_type", "showType", "type");
        b.type = BffJson.str(json, "type");
        b.contentType = BffJson.str(json, "content_type", "contentType");
        b.entityType = BffJson.str(json, "entity_type", "entityType");
        b.thumbnail = BffJson.str(json, "thumbnail");
        b.imageUrl = BffJson.str(json, "image_url", "imageUrl");
        b.posterImageUrl = BffJson.str(json, "poster_image_url", "posterImageUrl");
        b.portraitThumbnail = BffJson.str(json, "portraitThumbnail", "portrait_thumbnail");
        b.channelLogo = BffJson.str(json, "channel_logo", "channelLogo");
        b.channelName = BffJson.str(json, "channel_name", "channelName");
        b.stationId = BffJson.str(json, "station_id", "stationId");
        b.seriesId = BffJson.str(json, "series_id", "seriesId");
        b.ratings = BffJson.str(json, "ratings");
        b.playbackType = BffJson.str(json, "playback_type", "playbackType");
        b.description = BffJson.str(json, "description");
        b.deeplink = cta == null ? "" : cta.deeplink;
        b.channelNumber = BffJson.integer(json, -1, "channel_number", "channelNumber");
        b.position = BffJson.integer(json, -1, "position");
        b.releaseYear = BffJson.integer(json, 0, "release_year", "releaseYear");
        b.runtimeSeconds = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds");
        b.seasonNumber = BffJson.integer(json, 0, "season_number", "seasonNumber");
        b.episodeNumber = BffJson.integer(json, 0, "episode_number", "episodeNumber");
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult");
        b.adult = BffJson.bool(json, "adult");
        b.isCustomTile = BffJson.bool(json, "isCustomTile", "is_custom_tile");
        b.startTime = BffJson.time(BffJson.str(json, "start_time", "startTime", "start_utc"));
        b.endTime = BffJson.time(BffJson.str(json, "end_time", "endTime", "end_utc"));
        b.cta = cta;
        b.warningLabels = warnings(json);
        return new HomeAsset(b);
    }

    private static List<WarningLabel> warnings(JSONObject json) {
        JSONArray array = json.optJSONArray("warning_labels");
        if (array == null) {
            return Collections.emptyList();
        }
        List<WarningLabel> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            WarningLabel label = WarningLabel.fromJson(array.optJSONObject(i));
            if (label != null) {
                out.add(label);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public String displayTitle() {
        if (!customTitle.isEmpty()) {
            return customTitle;
        }
        if (!title.isEmpty()) {
            return title;
        }
        if (!name.isEmpty()) {
            return name;
        }
        return channelName;
    }

    public String image() {
        if (!imageUrl.isEmpty()) {
            return imageUrl;
        }
        if (!thumbnail.isEmpty()) {
            return thumbnail;
        }
        if (!posterImageUrl.isEmpty()) {
            return posterImageUrl;
        }
        if (!portraitThumbnail.isEmpty()) {
            return portraitThumbnail;
        }
        return channelLogo;
    }

    public boolean isSeries() {
        String kind = !entityType.isEmpty() ? entityType : contentType;
        return "Series".equalsIgnoreCase(kind) || "TVShow".equalsIgnoreCase(showType)
                || !seriesId.isEmpty() && !"Movie".equalsIgnoreCase(showType);
    }

    public boolean isChannel() {
        return "Channel".equalsIgnoreCase(entityType)
                || "Channel".equalsIgnoreCase(contentType)
                || (!channelLogo.isEmpty() && playbackType.isEmpty()
                && deeplink.contains("/player/live"));
    }

    public String meta() {
        return meta((Context) null);
    }

    /** Localized variant: the runtime label follows the active app language. */
    public String meta(Context context) {
        StringBuilder out = new StringBuilder();
        if (releaseYear > 0) {
            out.append(releaseYear);
        }
        if (runtimeSeconds > 0) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            int minutes = Math.max(1, (int) (runtimeSeconds / 60));
            if (context == null) {
                out.append(minutes).append(" min");
            } else {
                out.append(context.getString(R.string.meta_minutes, minutes));
            }
        }
        if (seasonNumber > 0) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append('S').append(seasonNumber);
            if (episodeNumber > 0) {
                out.append(' ').append('E').append(episodeNumber);
            }
        }
        if (out.length() == 0 && !channelName.isEmpty()) {
            out.append(channelName);
        }
        return out.toString();
    }

    private static final class Builder {
        String id = "", programId = "", title = "", name = "", customTitle = "";
        String showType = "", type = "", contentType = "", entityType = "";
        String thumbnail = "", imageUrl = "", posterImageUrl = "", portraitThumbnail = "";
        String channelLogo = "", channelName = "", stationId = "", seriesId = "";
        String ratings = "", playbackType = "", description = "", deeplink = "";
        int channelNumber = -1, position = -1, releaseYear, seasonNumber, episodeNumber;
        long runtimeSeconds, startTime, endTime;
        boolean isAdult, adult, isCustomTile;
        List<WarningLabel> warningLabels = Collections.emptyList();
        Cta cta;
    }
}
