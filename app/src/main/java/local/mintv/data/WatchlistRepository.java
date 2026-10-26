package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.BffJson;
import local.mintv.net.GuestAuth;

/** Watchlist endpoints: v2 full list, v3 paged rail, one POST for add/remove. */
public final class WatchlistRepository {

    private final Context appContext;
    private volatile List<WatchlistItem> cached = Collections.emptyList();

    public WatchlistRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public List<WatchlistItem> loadAll() throws ApiException {
        JSONObject json = BffApi.get(appContext, "/watchlist/v2/items",
                BffApi.query(), true, "", false, "MY_WATCHLIST",
                "GET_WATCHLIST_ITEMS", "");
        JSONArray items = BffJson.arrayDeep(json, "items", "assets");
        List<WatchlistItem> parsed = parse(items);
        cached = parsed;
        return parsed;
    }

    public HomeRailFeed loadPage(int offset, int pageSize, String type) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        if (type != null && !type.isEmpty()) {
            query.put("type", type);
        }
        HomeRailFeed feed = HomeRailFeed.fromJson(BffApi.get(appContext,
                "/watchlist/v3/items", query, true, "", false, "MY_WATCHLIST",
                "GET_WATCHLIST_ITEMS_V3", ""));
        if (offset == 0) {
            List<WatchlistItem> parsed = new ArrayList<>();
            for (HomeAsset asset : feed.assets) {
                parsed.add(WatchlistItem.fromJson(assetJson(asset)));
            }
            cached = Collections.unmodifiableList(parsed);
        }
        return feed;
    }

    public void add(String id, String type) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            JSONArray actions = new JSONArray();
            JSONObject action = new JSONObject();
            action.put("id", id);
            action.put("type", type);
            actions.put(action);
            body.put("add_action", actions);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "Neispravan zahtjev.", 0, e);
        }
        JSONObject response = BffApi.post(appContext, "/watchlist/items", BffApi.query(),
                body, true, "", false, "MY_WATCHLIST", "UPDATE_WATCHLIST", "");
        assertActionSucceeded(response, "add_action");
    }

    public void remove(String id, String type) throws ApiException {
        removeIds(Collections.singletonList(new String[]{id, type}));
    }

    public void removeAll(List<WatchlistItem> items) throws ApiException {
        List<String[]> refs = new ArrayList<>();
        for (WatchlistItem item : items) {
            refs.add(new String[]{item.id, item.deleteType()});
        }
        removeIds(refs);
    }

    /** One batch POST for the whole selection; every item carries its own type. */
    public void removeIds(List<String[]> refs) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            JSONArray actions = new JSONArray();
            for (String[] ref : refs) {
                if (ref[0] == null || ref[0].isEmpty()) {
                    continue;
                }
                JSONObject action = new JSONObject();
                action.put("id", ref[0]);
                action.put("type", ref[1] == null ? "" : ref[1]);
                actions.put(action);
            }
            body.put("delete_action", actions);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "Neispravan zahtjev.", 0, e);
        }
        JSONObject response = BffApi.post(appContext, "/watchlist/items", BffApi.query(),
                body, true, "", false, "MY_WATCHLIST", "UPDATE_WATCHLIST", "");
        assertActionSucceeded(response, "delete_action");
        cached = Collections.emptyList();
    }

    public boolean isPinned(String contentId) {
        if (contentId == null || contentId.isEmpty()) {
            return false;
        }
        for (WatchlistItem item : cached) {
            if (contentId.equals(item.id) || contentId.equals(item.programId)) {
                return true;
            }
        }
        return false;
    }

    public List<WatchlistItem> cached() {
        return cached;
    }

    public void cache(List<WatchlistItem> items) {
        cached = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static List<WatchlistItem> parse(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<WatchlistItem> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json != null) {
                out.add(WatchlistItem.fromJson(json));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static JSONObject assetJson(HomeAsset asset) {
        // The v3 feed uses the shared asset shape. Reuse the watchlist parser by round-tripping
        // the fields the item model needs; this keeps one source of truth for both feeds.
        JSONObject json = new JSONObject();
        try {
            json.put("id", asset.id);
            json.put("program_id", asset.programId);
            json.put("name", asset.displayTitle());
            json.put("title", asset.title);
            json.put("show_type", asset.showType);
            json.put("content_type", asset.contentType);
            json.put("entity_type", asset.entityType);
            json.put("image_url", asset.image());
            json.put("runtime_seconds", asset.runtimeSeconds);
            json.put("release_year", asset.releaseYear);
            json.put("ratings", asset.ratings);
            json.put("season_number", asset.seasonNumber);
            json.put("episode_number", asset.episodeNumber);
            json.put("series_id", asset.seriesId);
            json.put("channel_number", asset.channelNumber);
            json.put("channel_logo", asset.channelLogo);
            json.put("is_adult", asset.isAdult);
            json.put("playback_type", asset.playbackType);
            JSONObject cta = new JSONObject();
            cta.put("deeplink", asset.deeplink);
            json.put("cta", cta);
        } catch (JSONException ignored) {
            // plain strings/numbers
        }
        return json;
    }

    private static void assertActionSucceeded(JSONObject response, String key)
            throws ApiException {
        if (response == null) {
            return;
        }
        JSONObject action = response.optJSONObject(key);
        if (action == null) {
            JSONObject data = response.optJSONObject("data");
            action = data == null ? null : data.optJSONObject(key);
        }
        if (action != null && BffJson.bool(action, "failed")) {
            String message = BffJson.str(action, "message", "displayMessage", "error_message");
            throw new ApiException(ApiException.SERVER,
                    message.isEmpty() ? "Promjena popisa nije uspjela." : message, 0, null);
        }
    }
}
