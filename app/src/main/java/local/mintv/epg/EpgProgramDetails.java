package local.mintv.epg;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Programme metadata that is not present in the schedule page: artwork, the long synopsis
 * and the real runtime. The native MAXtv client resolves this from
 * {@code /hr-bifrost/details/program/{program_id}}; the same public endpoint answers the
 * guest requests used by this app.
 */
public final class EpgProgramDetails {

    public final String id;
    public final String name;
    public final String posterImageUrl;
    public final String description;
    public final String genre;
    public final long runtimeSeconds;
    public final int releaseYear;

    private EpgProgramDetails(String id, String name, String posterImageUrl, String description,
                              String genre, long runtimeSeconds, int releaseYear) {
        this.id = id;
        this.name = name;
        this.posterImageUrl = posterImageUrl;
        this.description = description;
        this.genre = genre;
        this.runtimeSeconds = runtimeSeconds;
        this.releaseYear = releaseYear;
    }

    static EpgProgramDetails fromJson(JSONObject json) {
        String description = "";
        String genre = "";
        JSONObject details = json.optJSONObject("details");
        if (details != null) {
            description = clean(details.optString("description", ""));
            JSONArray metadata = details.optJSONArray("metadata");
            if (metadata != null) {
                for (int i = 0; i < metadata.length(); i++) {
                    JSONObject entry = metadata.optJSONObject(i);
                    if (entry != null && "GENRES".equals(entry.optString("type", ""))) {
                        genre = clean(entry.optString("value", ""));
                        break;
                    }
                }
            }
        }
        return new EpgProgramDetails(
                clean(json.optString("id", "")),
                clean(json.optString("name", "")),
                clean(json.optString("poster_image_url", "")),
                description,
                genre,
                Math.max(0L, json.optLong("runtime_seconds", 0L)),
                json.optInt("release_year", 0));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }
}
