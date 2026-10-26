package local.mintv.search;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.mintv.data.AccountChannelRepository;
import local.mintv.data.Channel;
import local.mintv.details.PersonFeed;
import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.GuestAuth;

/** Search endpoints plus local-only recent-search history. */
public final class SearchRepository {

    private static final String PREFS = "search";
    private static final String KEY_HISTORY = "searchText";
    private static final int HISTORY_CAP = 7;

    private final Context appContext;
    private volatile boolean searchV2;

    public SearchRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public SearchPage search(String text, SearchType type, int offset, int size)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        query.put("text_search", text == null ? "" : text.trim());
        if (type != null && type != SearchType.ALL && !type.itemType.isEmpty()) {
            query.put("item_type", type.itemType);
        }
        if (type != null && type != SearchType.ALL) {
            query.put("offset", String.valueOf(offset));
        }
        query.put("size", String.valueOf(size));
        String path = searchV2 ? "/search/items/v2" : "/search/items";
        String step = step(type);
        JSONObject json = BffApi.get(appContext, path, query, true, "", false,
                "SEARCH", step, "");
        SearchPage page = SearchPage.fromJson(json);
        if (text != null && !text.trim().isEmpty()) {
            rememberQuery(text.trim());
        }
        return page;
    }

    public PersonFeed personItems(String personId, int offset, int size, boolean extended)
            throws ApiException {
        return new local.mintv.data.ContentDetailRepository(appContext)
                .personItems(personId, size, offset, extended);
    }

    /** Channels tab is the guest EPG list filtered client-side. */
    public List<Channel> channels() throws ApiException {
        return new AccountChannelRepository(appContext).channels(false);
    }

    public boolean isSearchV2() {
        return searchV2;
    }

    public void setSearchV2(boolean enabled) {
        this.searchV2 = enabled;
    }

    public List<String> history() {
        String raw = prefs().getString(KEY_HISTORY, "");
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(raw);
            List<String> out = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
            return Collections.unmodifiableList(out);
        } catch (JSONException e) {
            return Collections.emptyList();
        }
    }

    public void rememberQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) {
            return;
        }
        List<String> history = new ArrayList<>(history());
        history.remove(value);
        history.add(0, value);
        while (history.size() > HISTORY_CAP) {
            history.remove(history.size() - 1);
        }
        write(history);
    }

    public void removeQuery(String query) {
        List<String> history = new ArrayList<>(history());
        history.remove(query);
        write(history);
    }

    public void clearHistory() {
        prefs().edit().remove(KEY_HISTORY).apply();
    }

    private void write(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        prefs().edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String step(SearchType type) {
        if (type == null) {
            return "SEARCH_ALL";
        }
        switch (type) {
            case MOVIES:
                return "SEARCH_MOVIE";
            case SHOWS:
                return "SEARCH_SERIES";
            case PEOPLE:
                return "SEARCH_PERSON";
            default:
                return "SEARCH_ALL";
        }
    }
}
