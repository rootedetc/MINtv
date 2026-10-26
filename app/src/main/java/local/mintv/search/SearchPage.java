package local.mintv.search;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.data.HomeAsset;
import local.mintv.net.BffJson;

/** The four result groups plus the server's paging flag. */
public final class SearchPage {

    public final List<HomeAsset> movies;
    public final List<HomeAsset> shows;
    public final List<HomeAsset> streaming;
    public final List<Person> persons;
    public final boolean hasMoreItems;

    private SearchPage(List<HomeAsset> movies, List<HomeAsset> shows,
                       List<HomeAsset> streaming, List<Person> persons,
                       boolean hasMoreItems) {
        this.movies = movies;
        this.shows = shows;
        this.streaming = streaming;
        this.persons = persons;
        this.hasMoreItems = hasMoreItems;
    }

    public static SearchPage empty() {
        return new SearchPage(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    public static SearchPage fromJson(JSONObject json) {
        JSONObject data = BffJson.data(json);
        return new SearchPage(
                assets(data, "movies"),
                assets(data, "tv_shows", "tvShows", "shows"),
                assets(data, "streaming"),
                persons(data),
                BffJson.bool(data, "has_more_items", "hasMoreItems"));
    }

    private static List<HomeAsset> assets(JSONObject json, String... keys) {
        JSONArray array = BffJson.array(json, keys);
        if (array.length() == 0) {
            return Collections.emptyList();
        }
        List<HomeAsset> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(HomeAsset.fromJson(item));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Person> persons(JSONObject json) {
        JSONArray array = BffJson.array(json, "persons", "people");
        if (array.length() == 0) {
            return Collections.emptyList();
        }
        List<Person> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Person person = Person.fromJson(array.optJSONObject(i));
            if (person != null) {
                out.add(person);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() {
        return movies.isEmpty() && shows.isEmpty() && streaming.isEmpty() && persons.isEmpty();
    }
}
