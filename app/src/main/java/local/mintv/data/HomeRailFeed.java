package local.mintv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** One page of rail assets/channels. Editorial rails use {@code assets}, channel rails {@code channels}. */
public final class HomeRailFeed {

    public final int nextOffset;
    public final List<HomeAsset> assets;
    public final List<Channel> channels;
    public final String referenceId;
    public final String titleOverride;

    private HomeRailFeed(int nextOffset, List<HomeAsset> assets, List<Channel> channels,
                         String referenceId, String titleOverride) {
        this.nextOffset = nextOffset;
        this.assets = assets;
        this.channels = channels;
        this.referenceId = referenceId;
        this.titleOverride = titleOverride;
    }

    public static HomeRailFeed fromJson(JSONObject json) {
        JSONObject data = BffJson.data(json);
        int nextOffset = BffJson.integer(data, -1, "next_offset", "nextOffset");
        String referenceId = BffJson.str(data, "reference_id", "referenceId");
        String titleOverride = BffJson.str(data, "title_override", "titleOverride");

        JSONArray assetArray = BffJson.arrayDeep(data, "assets");
        List<HomeAsset> assets = new ArrayList<>(assetArray.length());
        for (int i = 0; i < assetArray.length(); i++) {
            assets.add(HomeAsset.fromJson(assetArray.optJSONObject(i)));
        }

        JSONArray channelArray = BffJson.arrayDeep(data, "channels");
        List<Channel> channels = new ArrayList<>(channelArray.length());
        for (int i = 0; i < channelArray.length(); i++) {
            JSONObject channel = channelArray.optJSONObject(i);
            if (channel != null) {
                channels.add(Channel.fromJson(channel));
            }
        }

        return new HomeRailFeed(nextOffset,
                Collections.unmodifiableList(assets),
                Collections.unmodifiableList(channels),
                referenceId,
                titleOverride);
    }

    public boolean isEmpty() {
        return assets.isEmpty() && channels.isEmpty();
    }
}
