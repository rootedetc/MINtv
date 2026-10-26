package local.mintv.details;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.data.HomeAsset;
import local.mintv.net.BffJson;
import local.mintv.search.Person;

/** Result of {@code /search/person/{id}/items}: credits plus the person header. */
public final class PersonFeed {

    public final List<HomeAsset> items;
    public final boolean hasMoreItems;
    public final Person person;

    private PersonFeed(List<HomeAsset> items, boolean hasMoreItems, Person person) {
        this.items = items;
        this.hasMoreItems = hasMoreItems;
        this.person = person;
    }

    public static PersonFeed fromJson(JSONObject json) {
        JSONObject data = BffJson.data(json);
        JSONArray assets = BffJson.arrayDeep(data, "items", "assets", "movies", "tv_shows");
        List<HomeAsset> items = new ArrayList<>(assets.length());
        for (int i = 0; i < assets.length(); i++) {
            items.add(HomeAsset.fromJson(assets.optJSONObject(i)));
        }
        Person person = Person.fromJson(BffJson.obj(data, "person"));
        return new PersonFeed(Collections.unmodifiableList(items),
                BffJson.bool(data, "has_more_items", "hasMoreItems"), person);
    }
}
