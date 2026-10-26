package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.BffJson;
import local.mintv.net.GuestAuth;

/**
 * Favourite channels, custom order, subscriptions and the channel line-up variants.
 *
 * <p>Favourites are keyed by {@code channel_number}; custom order by {@code channel_id}.
 * Both writes always send an array, even for one item.</p>
 */
public final class AccountChannelRepository {

    private static final String CACHE_FAVOURITES = "EPG_SUBSCRIBED_CHANNELS_MAP";
    private static final String CACHE_SUBSCRIPTIONS =
            "EPG_SUBSCRIBED_CHANNELS,EPG_SUBSCRIBED_CHANNELS_MAP_MK";

    private final Context appContext;

    public AccountChannelRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public List<Integer> favourites(boolean cacheIgnore) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("includeVirtualChannels", "false");
        query.put("includeSyntheticChannels", "false");
        JSONObject json = BffApi.get(appContext, "/epg/channel/favourite", query, true,
                "", false, "", "", cacheIgnore ? CACHE_FAVOURITES : "");
        return numbers(json, "channel_number", "channelNumber");
    }

    public void addFavourite(int channelNumber) throws ApiException {
        writeFavourite("add_keys", Collections.singletonList(channelNumber));
    }

    public void removeFavourite(int channelNumber) throws ApiException {
        writeFavourite("delete_keys", Collections.singletonList(channelNumber));
    }

    public void resetFavourites(List<Integer> allFavouriteNumbers) throws ApiException {
        writeFavourite("delete_keys", allFavouriteNumbers);
    }

    private void writeFavourite(String key, List<Integer> values) throws ApiException {
        JSONObject body = new JSONObject();
        JSONArray keys = new JSONArray();
        for (Integer value : values) {
            if (value != null) {
                keys.put(value);
            }
        }
        try {
            body.put(key, keys);
        } catch (org.json.JSONException e) {
            throw new ApiException(ApiException.PARSE, "Neispravan zahtjev.", 0, e);
        }
        BffApi.post(appContext, "/epg/channel/favourite", BffApi.query(), body, true,
                "", false, "", "SET_FAVOURITE", "");
    }

    public List<Integer> order() throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("includeVirtualChannels", "false");
        query.put("includeSyntheticChannels", "false");
        JSONObject json = BffApi.get(appContext, "/epg/channel/order", query, true);
        JSONArray array = BffJson.arrayDeep(json, "channel_order", "channelOrder");
        List<Integer> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof Number) {
                out.add(((Number) value).intValue());
            } else if (value instanceof JSONObject) {
                int id = BffJson.integer((JSONObject) value, -1,
                        "channel_id", "channelId", "id");
                if (id >= 0) {
                    out.add(id);
                }
            } else {
                try {
                    out.add(Integer.parseInt(String.valueOf(value)));
                } catch (NumberFormatException ignored) {
                    // skip malformed entry
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Full replacement; an empty list resets the order. */
    public void saveOrder(List<Integer> channelIdsInOrder) throws ApiException {
        JSONObject body = new JSONObject();
        JSONArray array = new JSONArray();
        for (Integer id : channelIdsInOrder) {
            if (id != null) {
                array.put(id);
            }
        }
        try {
            body.put("channel_order", array);
        } catch (org.json.JSONException e) {
            throw new ApiException(ApiException.PARSE, "Neispravan zahtjev.", 0, e);
        }
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("includeVirtualChannels", "false");
        query.put("includeSyntheticChannels", "false");
        BffApi.post(appContext, "/epg/channel/order", query, body, true,
                "", false, "", "SET_CHANNEL_ORDER", "");
    }

    public void resetOrder() throws ApiException {
        saveOrder(Collections.emptyList());
    }

    public List<Channel> subscriptions() throws ApiException {
        JSONObject json = BffApi.get(appContext, "/epg/channel/subscriptions",
                BffApi.query(), true, "", false, "", "", CACHE_SUBSCRIPTIONS);
        return channels(json);
    }

    public Set<Integer> subscribedNumbers() throws ApiException {
        Set<Integer> out = new LinkedHashSet<>();
        for (Channel channel : subscriptions()) {
            if (channel.getNumber() >= 0) {
                out.add(channel.getNumber());
            }
        }
        return out;
    }

    public List<Channel> channels(boolean v2) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("channelMap_id", "");
        query.put("includeVirtualChannels", "false");
        query.put("includeSyntheticChannels", "false");
        JSONObject json = BffApi.get(appContext,
                v2 ? "/epg/channel/v2" : "/epg/channel", query, false);
        return channels(json);
    }

    public List<Channel> favouriteChannels(boolean cacheIgnore) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("includeVirtualChannels", "false");
        query.put("includeSyntheticChannels", "false");
        JSONObject json = BffApi.get(appContext, "/epg/channel/favourite", query, true,
                "", false, "", "", cacheIgnore ? CACHE_FAVOURITES : "");
        return channels(json);
    }

    /**
     * The favourite endpoint sometimes returns only numbers/identities. Resolve them against the
     * guest line-up so the home channel rail still has logos, names and now/next.
     */
    public List<Channel> resolvedFavouriteChannels(boolean cacheIgnore) throws ApiException {
        List<Channel> direct = favouriteChannels(cacheIgnore);
        if (!direct.isEmpty()) {
            return direct;
        }
        List<Integer> numbers = favourites(cacheIgnore);
        if (numbers.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> wanted = new LinkedHashSet<>(numbers);
        List<Channel> out = new ArrayList<>();
        for (Channel channel : channels(false)) {
            if (wanted.contains(channel.getNumber())) {
                out.add(channel);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Channel> channels(JSONObject json) {
        JSONArray array = BffJson.arrayDeep(json, "channels");
        if (array.length() == 0) {
            // Some favourite responses are just the array, or a flat list of numbers.
            if (json != null && json.optJSONArray("channels") == null) {
                array = json.optJSONArray("items");
            }
        }
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<Channel> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject channel = array.optJSONObject(i);
            if (channel != null) {
                out.add(Channel.fromJson(channel));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Integer> numbers(JSONObject json, String... keys) {
        JSONArray array = BffJson.arrayDeep(json, "channels", "items");
        if (array.length() == 0 && json != null && json.optJSONArray("channels") != null) {
            array = json.optJSONArray("channels");
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject channel = array.optJSONObject(i);
            if (channel != null) {
                int number = BffJson.integer(channel, -1, keys);
                if (number >= 0) {
                    out.add(number);
                }
            } else {
                try {
                    out.add(Integer.parseInt(BffJson.clean(array.optString(i, ""))));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }
}
