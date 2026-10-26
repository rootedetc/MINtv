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

/** {@code GET /details/series/{id}} — guest-readable metadata and season list. */
public final class SeriesDetail {

    public final String id;
    public final String name;
    public final int episodesCount;
    public final int seasonsCount;
    public final String posterImageUrl;
    public final boolean isLive;
    public final String showType;
    public final int releaseYear;
    public final int endYear;
    public final String ratings;
    public final boolean isAdult;
    public final String description;
    public final List<MetaRow> metadata;
    public final List<Person> roles;
    public final List<Season> seasons;
    public final String dataSource;
    public final List<String> countryOfOrigin;

    private SeriesDetail(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.episodesCount = b.episodesCount;
        this.seasonsCount = b.seasonsCount;
        this.posterImageUrl = b.posterImageUrl;
        this.isLive = b.isLive;
        this.showType = b.showType;
        this.releaseYear = b.releaseYear;
        this.endYear = b.endYear;
        this.ratings = b.ratings;
        this.isAdult = b.isAdult;
        this.description = b.description;
        this.metadata = b.metadata;
        this.roles = b.roles;
        this.seasons = b.seasons;
        this.dataSource = b.dataSource;
        this.countryOfOrigin = b.countryOfOrigin;
    }

    public static SeriesDetail fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new SeriesDetail(b);
        }
        b.id = BffJson.str(json, "id");
        b.name = BffJson.str(json, "name", "title");
        b.episodesCount = BffJson.integer(json, 0, "episodes_count", "episodesCount");
        b.seasonsCount = BffJson.integer(json, 0, "seasons_count", "seasonsCount");
        b.posterImageUrl = BffJson.poster(json);
        b.isLive = BffJson.bool(json, "is_live", "isLive");
        b.showType = BffJson.str(json, "show_type", "showType");
        b.releaseYear = BffJson.integer(json, 0, "release_year", "releaseYear");
        b.endYear = BffJson.integer(json, 0, "end_year", "endYear");
        b.ratings = BffJson.str(json, "ratings");
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult");
        b.dataSource = BffJson.str(json, "data_source", "dataSource");
        b.countryOfOrigin = BffJson.strings(json.optJSONArray("country_of_origin"));
        JSONObject details = BffJson.obj(json, "details");
        b.description = BffJson.str(details, "description");
        b.metadata = ProgramDetail.metaRows(details);
        b.roles = ProgramDetail.people(json.optJSONArray("roles"));

        JSONArray seasons = json.optJSONArray("seasons");
        if (seasons != null) {
            List<Season> parsed = new ArrayList<>(seasons.length());
            for (int i = 0; i < seasons.length(); i++) {
                Season season = Season.fromJson(seasons.optJSONObject(i));
                if (season != null && !season.id.isEmpty()) {
                    parsed.add(season);
                }
            }
            b.seasons = Collections.unmodifiableList(parsed);
        }
        return new SeriesDetail(b);
    }

    public String meta() {
        return meta(seasonsCount > 0 ? seasonsCount + " sez." : null);
    }

    /** Localized variant: the season count follows the active app language. */
    public String meta(Context context) {
        String seasons = seasonsCount > 0
                ? context.getResources().getQuantityString(
                R.plurals.meta_seasons_short, seasonsCount, seasonsCount)
                : null;
        return meta(seasons);
    }

    private String meta(String seasonsLabel) {
        List<String> parts = new ArrayList<>();
        for (MetaRow row : metadata) {
            if ("GENRES".equalsIgnoreCase(row.type)) {
                parts.add(row.value);
                break;
            }
        }
        if (seasonsLabel != null) {
            parts.add(seasonsLabel);
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
        String description = "", dataSource = "";
        int episodesCount, seasonsCount, releaseYear, endYear;
        boolean isLive, isAdult;
        List<MetaRow> metadata = Collections.emptyList();
        List<Person> roles = Collections.emptyList();
        List<Season> seasons = Collections.emptyList();
        List<String> countryOfOrigin = Collections.emptyList();
    }
}
