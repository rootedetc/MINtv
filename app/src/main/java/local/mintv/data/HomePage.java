package local.mintv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** A home page, subpage or component list; {@code nextOffset == -1} means end of feed. */
public final class HomePage {

    public final int nextOffset;
    public final List<HomeComponent> components;
    public final List<HomeAsset> assets;
    public final HomeComponent parent;
    public final String templateId;
    public final String title;
    public final String categoryId;
    public final String titleOverride;
    public final String referenceId;

    private HomePage(Builder b) {
        this.nextOffset = b.nextOffset;
        this.components = b.components;
        this.assets = b.assets;
        this.parent = b.parent;
        this.templateId = b.templateId;
        this.title = b.title;
        this.categoryId = b.categoryId;
        this.titleOverride = b.titleOverride;
        this.referenceId = b.referenceId;
    }

    public static HomePage fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new HomePage(b);
        }
        JSONObject data = BffJson.data(json);
        b.nextOffset = BffJson.integer(data, -1, "next_offset", "nextOffset");
        b.templateId = BffJson.str(data, "template_id", "templateId");
        b.title = BffJson.clean(BffJson.str(data, "title"));
        b.categoryId = BffJson.str(data, "category_id", "categoryId");
        b.titleOverride = BffJson.str(data, "title_override", "titleOverride");
        b.referenceId = BffJson.str(data, "reference_id", "referenceId");
        JSONObject parent = data.optJSONObject("parent");
        b.parent = parent == null ? null : HomeComponent.fromJson(parent);
        JSONArray components = data.optJSONArray("components");
        if (components != null) {
            List<HomeComponent> parsed = new ArrayList<>(components.length());
            for (int i = 0; i < components.length(); i++) {
                parsed.add(HomeComponent.fromJson(components.optJSONObject(i)));
            }
            b.components = Collections.unmodifiableList(parsed);
        }
        JSONArray assets = data.optJSONArray("assets");
        if (assets != null) {
            List<HomeAsset> parsed = new ArrayList<>(assets.length());
            for (int i = 0; i < assets.length(); i++) {
                parsed.add(HomeAsset.fromJson(assets.optJSONObject(i)));
            }
            b.assets = Collections.unmodifiableList(parsed);
        }
        return new HomePage(b);
    }

    public boolean hasMore() {
        return nextOffset >= 0;
    }

    private static final class Builder {
        int nextOffset = -1;
        List<HomeComponent> components = Collections.emptyList();
        List<HomeAsset> assets = Collections.emptyList();
        HomeComponent parent;
        String templateId = "", title = "", categoryId = "", titleOverride = "", referenceId = "";
    }
}
