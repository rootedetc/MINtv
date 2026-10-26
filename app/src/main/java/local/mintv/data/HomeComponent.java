package local.mintv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** One server-driven block of a home page: hero, rail, sub-rail or a special feed. */
public final class HomeComponent {

    public final String id;
    public final String templateId;
    public final String title;
    public final String subTitle;
    public final String categoryId;
    public final String storeId;
    public final String type;
    public final boolean isPortrait;
    public final boolean isAdult;
    public final Cta cta;
    public final ContentDetails contentDetails;
    public final List<HomeAsset> assets;
    public final List<Highlight> highlights;
    public final List<HomeComponent> children;

    private HomeComponent(Builder b) {
        this.id = b.id;
        this.templateId = b.templateId;
        this.title = b.title;
        this.subTitle = b.subTitle;
        this.categoryId = b.categoryId;
        this.storeId = b.storeId;
        this.type = b.type;
        this.isPortrait = b.isPortrait;
        this.isAdult = b.isAdult;
        this.cta = b.cta;
        this.contentDetails = b.contentDetails;
        this.assets = b.assets;
        this.highlights = b.highlights;
        this.children = b.children;
    }

    public static HomeComponent fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new HomeComponent(b);
        }
        b.id = BffJson.str(json, "id");
        b.templateId = BffJson.str(json, "template_id", "templateId");
        b.title = BffJson.clean(BffJson.str(json, "title"));
        b.subTitle = BffJson.str(json, "sub_title", "subTitle");
        b.categoryId = BffJson.str(json, "category_id", "categoryId");
        b.storeId = BffJson.str(json, "store_id", "storeId");
        b.type = BffJson.str(json, "type");
        b.isPortrait = BffJson.bool(json, "is_portrait", "isPortrait");
        b.isAdult = BffJson.bool(json, "is_adult", "isAdult");
        b.cta = Cta.fromJson(json.optJSONObject("cta"));
        b.contentDetails = ContentDetails.fromJson(
                BffJson.obj(json, "content_details", "contentDetails"));

        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            List<HomeAsset> parsed = new ArrayList<>(assets.length());
            for (int i = 0; i < assets.length(); i++) {
                parsed.add(HomeAsset.fromJson(assets.optJSONObject(i)));
            }
            b.assets = Collections.unmodifiableList(parsed);
        }
        JSONArray highlights = json.optJSONArray("highlights");
        if (highlights != null) {
            List<Highlight> parsed = new ArrayList<>(highlights.length());
            for (int i = 0; i < highlights.length(); i++) {
                Highlight highlight = Highlight.fromJson(highlights.optJSONObject(i));
                if (highlight != null) {
                    parsed.add(highlight);
                }
            }
            b.highlights = Collections.unmodifiableList(parsed);
        }
        JSONArray children = json.optJSONArray("children");
        if (children != null) {
            List<HomeComponent> parsed = new ArrayList<>(children.length());
            for (int i = 0; i < children.length(); i++) {
                parsed.add(HomeComponent.fromJson(children.optJSONObject(i)));
            }
            b.children = Collections.unmodifiableList(parsed);
        }
        return new HomeComponent(b);
    }

    public String tag() {
        return contentDetails == null ? "" : contentDetails.tag;
    }

    public String endPoint() {
        return contentDetails == null ? "" : contentDetails.endPoint;
    }

    public String feedType() {
        return contentDetails == null ? "" : contentDetails.type;
    }

    public boolean requiresAuth() {
        return contentDetails != null && contentDetails.auth;
    }

    public boolean isHighlight() {
        return "HIGHLIGHT".equalsIgnoreCase(templateId);
    }

    public boolean isContinueWatching() {
        return "RAIL_CONTINUE_WATCHING".equalsIgnoreCase(templateId)
                || (contentDetails != null && contentDetails.isContinueWatching());
    }

    public boolean isWatchlist() {
        return "watchlist".equalsIgnoreCase(tag());
    }

    public boolean isFavouriteChannels() {
        return "favorite_channels".equalsIgnoreCase(tag());
    }

    public boolean isOnNow() {
        return "on_now".equalsIgnoreCase(tag());
    }

    public boolean isRentals() {
        return "rentals".equalsIgnoreCase(tag());
    }

    public boolean isRecording() {
        return "recording".equalsIgnoreCase(tag());
    }

    public boolean hasShowMore() {
        return cta != null && cta.hasDeeplink();
    }

    private static final class Builder {
        String id = "", templateId = "", title = "", subTitle = "", categoryId = "";
        String storeId = "", type = "";
        boolean isPortrait, isAdult;
        Cta cta;
        ContentDetails contentDetails;
        List<HomeAsset> assets = Collections.emptyList();
        List<Highlight> highlights = Collections.emptyList();
        List<HomeComponent> children = Collections.emptyList();
    }
}
