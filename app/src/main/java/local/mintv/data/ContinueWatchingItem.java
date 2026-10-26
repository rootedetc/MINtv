package local.mintv.data;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** One bookmark row from {@code GET /details/bookmarks/v3}. */
public final class ContinueWatchingItem {

    public final String id;
    public final String programId;
    public final String seriesId;
    public final String seasonId;
    public final String bookmarkType;
    public final String subjectId;
    public final String mediaId;
    public final String stationId;
    public final String listingGuid;
    public final String title;
    public final String imageUrl;
    public final String deeplink;
    public final String playbackType;
    public final String ratings;
    public final int channelNumber;
    public final String channelName;
    public final int releaseYear;
    public final long timeSeconds;
    public final long runtimeSeconds;
    public final long startTime;
    public final long endTime;
    public final boolean isAdult;

    private ContinueWatchingItem(Builder b) {
        this.id = b.id;
        this.programId = b.programId;
        this.seriesId = b.seriesId;
        this.seasonId = b.seasonId;
        this.bookmarkType = b.bookmarkType;
        this.subjectId = b.subjectId;
        this.mediaId = b.mediaId;
        this.stationId = b.stationId;
        this.listingGuid = b.listingGuid;
        this.title = b.title;
        this.imageUrl = b.imageUrl;
        this.deeplink = b.deeplink;
        this.playbackType = b.playbackType;
        this.ratings = b.ratings;
        this.channelNumber = b.channelNumber;
        this.channelName = b.channelName;
        this.releaseYear = b.releaseYear;
        this.timeSeconds = b.timeSeconds;
        this.runtimeSeconds = b.runtimeSeconds;
        this.startTime = b.startTime;
        this.endTime = b.endTime;
        this.isAdult = b.isAdult;
    }

    public static ContinueWatchingItem fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new ContinueWatchingItem(b);
        }
        Cta cta = Cta.fromJson(json.optJSONObject("cta"));
        b.id = BffJson.str(json, "id");
        b.programId = BffJson.str(json, "program_id", "programId");
        b.seriesId = BffJson.str(json, "series_id", "seriesId");
        b.seasonId = BffJson.str(json, "season_id", "seasonId");
        b.bookmarkType = BffJson.str(json, "bookmark_type", "bookmarkType");
        b.subjectId = BffJson.str(json, "subject_id", "subjectId");
        b.mediaId = BffJson.str(json, "media_id", "mediaId");
        b.stationId = BffJson.str(json, "station_id", "stationId");
        b.listingGuid = BffJson.str(json, "listing_guid", "listingGuid");
        b.title = BffJson.str(json, "name", "title");
        b.imageUrl = BffJson.image(json);
        b.deeplink = cta == null ? "" : cta.deeplink;
        b.playbackType = BffJson.str(json, "playback_type", "playbackType");
        b.ratings = BffJson.str(json, "ratings");
        b.channelNumber = BffJson.integer(json, -1, "channel_number", "channelNumber");
        b.channelName = BffJson.str(json, "channel_name", "channelName");
        b.releaseYear = BffJson.integer(json, 0, "release_year", "releaseYear");
        b.timeSeconds = BffJson.longValue(json, 0L, "time_seconds", "timeSeconds");
        b.runtimeSeconds = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds");
        b.startTime = BffJson.time(BffJson.str(json, "start_time", "startTime"));
        b.endTime = BffJson.time(BffJson.str(json, "end_time", "endTime"));
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult", "adult");
        return new ContinueWatchingItem(b);
    }

    public int percent() {
        if (runtimeSeconds <= 0 || timeSeconds <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(100.0 * timeSeconds / runtimeSeconds));
    }

    public long remainingSeconds() {
        return Math.max(0, runtimeSeconds - timeSeconds);
    }

    public int remainingMinutes() {
        long seconds = remainingSeconds();
        return seconds <= 0 ? 0 : Math.max(1, (int) Math.round(seconds / 60.0));
    }

    public String displayTitle() {
        if (!title.isEmpty()) {
            return title;
        }
        return channelName;
    }

    public boolean isCatchup() {
        return "CATCHUP".equalsIgnoreCase(bookmarkType);
    }

    public boolean isVod() {
        return "VOD".equalsIgnoreCase(bookmarkType);
    }

    private static final class Builder {
        String id = "", programId = "", seriesId = "", seasonId = "", bookmarkType = "";
        String subjectId = "", mediaId = "", stationId = "", listingGuid = "";
        String title = "", imageUrl = "", deeplink = "", playbackType = "", ratings = "";
        String channelName = "";
        int channelNumber = -1, releaseYear;
        long timeSeconds, runtimeSeconds, startTime, endTime;
        boolean isAdult;
    }
}
