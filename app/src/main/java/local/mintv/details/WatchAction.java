package local.mintv.details;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** One playable option or schedule entry inside {@code actions}. */
public final class WatchAction {

    public final String type;
    public final String mediaId;
    public final int channelNumber;
    public final String stationId;
    public final String listingGuid;
    public final long startUtc;
    public final long endUtc;
    public final boolean restricted;
    public final List<String> restrictionTypes;
    public final List<String> allowedPlatforms;
    public final String contentExpiryMessage;
    public final boolean recordingAvailableContent;
    public final int seasonNumber;
    public final int episodeNumber;
    public final String recordingState;
    public final boolean protectedContent;
    public final JSONObject raw;

    private WatchAction(Builder b) {
        this.type = b.type;
        this.mediaId = b.mediaId;
        this.channelNumber = b.channelNumber;
        this.stationId = b.stationId;
        this.listingGuid = b.listingGuid;
        this.startUtc = b.startUtc;
        this.endUtc = b.endUtc;
        this.restricted = b.restricted;
        this.restrictionTypes = b.restrictionTypes;
        this.allowedPlatforms = b.allowedPlatforms;
        this.contentExpiryMessage = b.contentExpiryMessage;
        this.recordingAvailableContent = b.recordingAvailableContent;
        this.seasonNumber = b.seasonNumber;
        this.episodeNumber = b.episodeNumber;
        this.recordingState = b.recordingState;
        this.protectedContent = b.protectedContent;
        this.raw = b.raw;
    }

    public static WatchAction fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new WatchAction(b);
        }
        b.raw = json;
        b.type = BffJson.str(json, "type");
        b.mediaId = BffJson.str(json, "media_id", "mediaId");
        b.channelNumber = BffJson.integer(json, -1, "channel_number", "channelNumber");
        b.stationId = BffJson.str(json, "station_id", "stationId");
        b.listingGuid = BffJson.str(json, "listing_guid", "listingGuid");
        b.startUtc = BffJson.time(BffJson.str(json, "start_utc", "startUtc", "start_time"));
        b.endUtc = BffJson.time(BffJson.str(json, "end_utc", "endUtc", "end_time"));
        b.restricted = BffJson.bool(json, "is_restricted", "restricted");
        b.restrictionTypes = BffJson.strings(json.optJSONArray("restriction_type"));
        b.allowedPlatforms = BffJson.strings(json.optJSONArray("allowed_platforms"));
        b.contentExpiryMessage = BffJson.str(json, "content_expiry_message",
                "contentExpiryMessage");
        b.recordingAvailableContent = BffJson.bool(json,
                "is_recording_available_content", "recordingAvailableContent");
        b.seasonNumber = BffJson.integer(json, 0, "season_number", "seasonNumber");
        b.episodeNumber = BffJson.integer(json, 0, "episode_number", "episodeNumber");
        b.recordingState = BffJson.str(json, "recording_state", "recordingState");
        b.protectedContent = BffJson.bool(json, "protected");
        return new WatchAction(b);
    }

    public static List<WatchAction> list(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<WatchAction> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(WatchAction.fromJson(item));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isVod() {
        return "VOD".equalsIgnoreCase(type);
    }

    public boolean isCatchup() {
        return "CATCHUP".equalsIgnoreCase(type);
    }

    public boolean isLive() {
        return "LIVE".equalsIgnoreCase(type);
    }

    public boolean isRecording() {
        return "RECORDING".equalsIgnoreCase(type);
    }

    private static final class Builder {
        String type = "", mediaId = "", stationId = "", listingGuid = "";
        String contentExpiryMessage = "", recordingState = "";
        int channelNumber = -1, seasonNumber, episodeNumber;
        long startUtc, endUtc;
        boolean restricted, recordingAvailableContent, protectedContent;
        List<String> restrictionTypes = Collections.emptyList();
        List<String> allowedPlatforms = Collections.emptyList();
        JSONObject raw;
    }
}
