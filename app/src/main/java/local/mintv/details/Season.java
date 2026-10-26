package local.mintv.details;

import android.content.Context;

import org.json.JSONObject;

import local.mintv.R;
import local.mintv.net.BffJson;

/** Season descriptor from {@code GET /details/series/{id}}; {@code id} is numeric and is the
 * season path segment, not the season number. */
public final class Season {

    public final String id;
    public final String name;
    public final int number;
    public final int episodesCount;
    public final String displayName;
    public final boolean addSeasonPrefix;

    private Season(String id, String name, int number, int episodesCount,
                   String displayName, boolean addSeasonPrefix) {
        this.id = id;
        this.name = name;
        this.number = number;
        this.episodesCount = episodesCount;
        this.displayName = displayName;
        this.addSeasonPrefix = addSeasonPrefix;
    }

    public static Season fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new Season(
                BffJson.str(json, "id"),
                BffJson.str(json, "name"),
                BffJson.integer(json, 0, "number"),
                BffJson.integer(json, 0, "episodes_count", "episodesCount"),
                BffJson.str(json, "display_name", "displayName"),
                BffJson.bool(json, "add_season_prefix", "addSeasonPrefix"));
    }

    public String label() {
        if (!displayName.isEmpty()) {
            return displayName;
        }
        if (!name.isEmpty()) {
            return name;
        }
        return "Sezona " + number;
    }

    /** Localized fallback for seasons without a server-provided name. */
    public String label(Context context) {
        if (!displayName.isEmpty()) {
            return displayName;
        }
        if (!name.isEmpty()) {
            return name;
        }
        return context.getString(R.string.season_label, number);
    }
}
