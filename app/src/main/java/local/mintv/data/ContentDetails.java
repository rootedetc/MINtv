package local.mintv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** {@code content_details}: the rail's data source and renderer hints. */
public final class ContentDetails {

    public final String modelType;
    public final String endPoint;
    public final String type;
    public final String displayType;
    public final String tag;
    public final String railType;
    public final boolean auth;
    public final List<String> genres;

    private ContentDetails(String modelType, String endPoint, String type, String displayType,
                           String tag, String railType, boolean auth, List<String> genres) {
        this.modelType = modelType;
        this.endPoint = endPoint;
        this.type = type;
        this.displayType = displayType;
        this.tag = tag;
        this.railType = railType;
        this.auth = auth;
        this.genres = genres;
    }

    public static ContentDetails fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        List<String> genres = new ArrayList<>();
        JSONArray array = json.optJSONArray("genres");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject genre = array.optJSONObject(i);
                String name = genre == null ? BffJson.clean(array.optString(i, ""))
                        : BffJson.str(genre, "name", "title");
                if (!name.isEmpty()) {
                    genres.add(name);
                }
            }
        }
        return new ContentDetails(
                BffJson.str(json, "model_type", "modelType"),
                BffJson.str(json, "end_point", "endPoint"),
                BffJson.str(json, "type"),
                BffJson.str(json, "display_type", "displayType"),
                BffJson.str(json, "tag"),
                BffJson.str(json, "rail_type", "railType"),
                BffJson.bool(json, "auth"),
                Collections.unmodifiableList(genres));
    }

    public boolean isContinueWatching() {
        return "RAIL_CONTINUE_WATCHING".equalsIgnoreCase(tag);
    }
}
