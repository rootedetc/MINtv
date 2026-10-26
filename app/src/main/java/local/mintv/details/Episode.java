package local.mintv.details;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** One episode row from {@code /details/series/{id}/season/{seasonId}/v2}. */
public final class Episode {

    public final String programId;
    public final String seasonId;
    public final String episodeName;
    public final String description;
    public final String imageUrl;
    public final int seasonNumber;
    public final int episodeNumber;
    public final long startTime;
    public final long endTime;
    public final long runtimeSeconds;
    public final boolean restricted;
    public final List<String> allowedPlatforms;
    public final ActionSet actions;

    private Episode(Builder b) {
        this.programId = b.programId;
        this.seasonId = b.seasonId;
        this.episodeName = b.episodeName;
        this.description = b.description;
        this.imageUrl = b.imageUrl;
        this.seasonNumber = b.seasonNumber;
        this.episodeNumber = b.episodeNumber;
        this.startTime = b.startTime;
        this.endTime = b.endTime;
        this.runtimeSeconds = b.runtimeSeconds;
        this.restricted = b.restricted;
        this.allowedPlatforms = b.allowedPlatforms;
        this.actions = b.actions;
    }

    public static Episode fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new Episode(b);
        }
        b.programId = BffJson.str(json, "program_id", "programId", "id");
        b.seasonId = BffJson.str(json, "season_id", "seasonId");
        b.episodeName = BffJson.str(json, "episode_name", "episodeName", "name");
        b.description = BffJson.str(json, "description");
        b.imageUrl = BffJson.image(json);
        b.seasonNumber = BffJson.integer(json, 0, "season_number", "seasonNumber");
        b.episodeNumber = BffJson.integer(json, 0, "episode_number", "episodeNumber",
                "number");
        b.startTime = BffJson.time(BffJson.str(json, "start_time", "startTime"));
        b.endTime = BffJson.time(BffJson.str(json, "end_time", "endTime"));
        b.runtimeSeconds = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds",
                "duration");
        b.restricted = BffJson.bool(json, "is_restricted", "restricted");
        b.allowedPlatforms = BffJson.strings(json.optJSONArray("allowed_platforms"));
        org.json.JSONObject actionObject = BffJson.obj(json, "actions");
        if (actionObject == null && (json.has("watch") || json.has("pinned"))) {
            actionObject = json;
        }
        b.actions = ActionSet.fromJson(actionObject);
        return new Episode(b);
    }

    public String label() {
        if (episodeNumber > 0) {
            return "E" + episodeNumber + (episodeName.isEmpty() ? "" : " · " + episodeName);
        }
        return episodeName;
    }

    private static final class Builder {
        String programId = "", seasonId = "", episodeName = "", description = "", imageUrl = "";
        int seasonNumber, episodeNumber;
        long startTime, endTime, runtimeSeconds;
        boolean restricted;
        List<String> allowedPlatforms = Collections.emptyList();
        ActionSet actions;
    }
}
