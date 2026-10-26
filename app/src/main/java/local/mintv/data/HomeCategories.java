package local.mintv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** Response of {@code GET /home/root/categories}. */
public final class HomeCategories {

    public final String defaultCategoryId;
    public final List<HomeCategory> categories;

    private HomeCategories(String defaultCategoryId, List<HomeCategory> categories) {
        this.defaultCategoryId = defaultCategoryId;
        this.categories = categories;
    }

    public static HomeCategories fromJson(JSONObject json) {
        JSONObject data = BffJson.data(json);
        String fallback = BffJson.str(data, "default_category_id", "defaultCategoryId");
        JSONArray array = data.optJSONArray("categories");
        List<HomeCategory> parsed = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                HomeCategory category = HomeCategory.fromJson(array.optJSONObject(i));
                if (category != null && !category.id.isEmpty()) {
                    parsed.add(category);
                }
            }
        }
        return new HomeCategories(fallback, Collections.unmodifiableList(parsed));
    }

    public HomeCategory find(String id) {
        for (HomeCategory category : categories) {
            if (category.id.equals(id) || category.pageId.equals(id)) {
                return category;
            }
        }
        return null;
    }
}
