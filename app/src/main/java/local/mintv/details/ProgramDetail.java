package local.mintv.details;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.R;
import local.mintv.net.BffJson;
import local.mintv.search.Person;

/** {@code GET /details/program/{id}} — always guest-readable. */
public final class ProgramDetail {

    public final String id;
    public final String name;
    public final String posterImageUrl;
    public final boolean isLive;
    public final String showType;
    public final int releaseYear;
    public final String ratings;
    public final long runtimeSeconds;
    public final boolean isAdult;
    public final String description;
    public final List<MetaRow> metadata;
    public final List<Person> roles;
    public final String seriesId;
    public final int seasonNumber;
    public final int episodeNumber;
    public final String seasonDisplayNumber;
    public final String dataSource;
    public final List<String> countryOfOrigin;

    private ProgramDetail(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.posterImageUrl = b.posterImageUrl;
        this.isLive = b.isLive;
        this.showType = b.showType;
        this.releaseYear = b.releaseYear;
        this.ratings = b.ratings;
        this.runtimeSeconds = b.runtimeSeconds;
        this.isAdult = b.isAdult;
        this.description = b.description;
        this.metadata = b.metadata;
        this.roles = b.roles;
        this.seriesId = b.seriesId;
        this.seasonNumber = b.seasonNumber;
        this.episodeNumber = b.episodeNumber;
        this.seasonDisplayNumber = b.seasonDisplayNumber;
        this.dataSource = b.dataSource;
        this.countryOfOrigin = b.countryOfOrigin;
    }

    public static ProgramDetail fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new ProgramDetail(b);
        }
        b.id = BffJson.str(json, "id");
        b.name = BffJson.str(json, "name", "title");
        b.posterImageUrl = BffJson.poster(json);
        b.isLive = BffJson.bool(json, "is_live", "isLive");
        b.showType = BffJson.str(json, "show_type", "showType");
        b.releaseYear = BffJson.integer(json, 0, "release_year", "releaseYear");
        b.ratings = BffJson.str(json, "ratings");
        b.runtimeSeconds = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds");
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult");
        b.seriesId = BffJson.str(json, "series_id", "seriesId");
        b.seasonNumber = BffJson.integer(json, 0, "season_number", "seasonNumber");
        b.episodeNumber = BffJson.integer(json, 0, "episode_number", "episodeNumber");
        b.seasonDisplayNumber = BffJson.str(json, "season_display_number",
                "seasonDisplayNumber");
        b.dataSource = BffJson.str(json, "data_source", "dataSource");
        b.countryOfOrigin = BffJson.strings(json.optJSONArray("country_of_origin"));
        b.description = BffJson.str(BffJson.obj(json, "details"), "description");
        b.metadata = metaRows(BffJson.obj(json, "details"));
        b.roles = people(json.optJSONArray("roles"));
        return new ProgramDetail(b);
    }

    static List<MetaRow> metaRows(JSONObject details) {
        JSONArray array = details == null ? null : details.optJSONArray("metadata");
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<MetaRow> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            MetaRow row = MetaRow.fromJson(array.optJSONObject(i));
            if (row != null) {
                out.add(row);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static List<Person> people(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Person person = Person.fromJson(array.optJSONObject(i));
            if (person != null && !person.name.isEmpty()) {
                out.add(person);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public String meta() {
        return meta(runtimeSeconds > 0 ? Math.max(1, runtimeSeconds / 60) + " min" : null);
    }

    /** Localized variant: the runtime label follows the active app language. */
    public String meta(Context context) {
        String runtime = runtimeSeconds > 0
                ? context.getString(R.string.meta_minutes, Math.max(1, runtimeSeconds / 60))
                : null;
        return meta(runtime);
    }

    private String meta(String runtimeLabel) {
        List<String> parts = new ArrayList<>();
        if (runtimeLabel != null) {
            parts.add(runtimeLabel);
        }
        for (MetaRow row : metadata) {
            if ("GENRES".equalsIgnoreCase(row.type)) {
                parts.add(row.value);
                break;
            }
        }
        if (releaseYear > 0) {
            parts.add(String.valueOf(releaseYear));
        }
        if (!ratings.isEmpty() && !"0".equals(ratings)) {
            parts.add("★ " + ratings);
        }
        return String.join(" · ", parts);
    }

    private static final class Builder {
        String id = "", name = "", posterImageUrl = "", showType = "", ratings = "";
        String description = "", seriesId = "", seasonDisplayNumber = "", dataSource = "";
        int releaseYear, seasonNumber, episodeNumber;
        long runtimeSeconds;
        boolean isLive, isAdult;
        List<MetaRow> metadata = Collections.emptyList();
        List<Person> roles = Collections.emptyList();
        List<String> countryOfOrigin = Collections.emptyList();
    }
}
