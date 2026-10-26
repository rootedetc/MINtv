package local.mintv.data;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** One entry of {@code GET /home/root/categories}. */
public final class HomeCategory {

    public final String id;
    public final String pageId;
    public final String title;
    public final String deeplink;
    public final boolean isAdult;

    private HomeCategory(String id, String pageId, String title, String deeplink,
                         boolean isAdult) {
        this.id = id;
        this.pageId = pageId;
        this.title = BffJson.clean(title);
        this.deeplink = deeplink;
        this.isAdult = isAdult;
    }

    public static HomeCategory fromJson(JSONObject json) {
        Cta cta = Cta.fromJson(json == null ? null : json.optJSONObject("cta"));
        String id = BffJson.str(json, "id");
        String pageId = BffJson.str(json, "page_id", "pageId");
        return new HomeCategory(
                id,
                pageId.isEmpty() ? id : pageId,
                BffJson.str(json, "title"),
                cta == null ? "" : cta.deeplink,
                BffJson.bool(json, "is_adult", "isAdult", "adult"));
    }
}
