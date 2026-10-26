package local.mintv.data;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** Hero slide from {@code /home/component/{id}/highlights}. */
public final class Highlight {

    public final String id;
    public final String title;
    public final String subTitle;
    public final String description;
    public final String image;
    public final boolean isAdult;
    public final Cta cta;

    private Highlight(String id, String title, String subTitle, String description,
                      String image, boolean isAdult, Cta cta) {
        this.id = id;
        this.title = title;
        this.subTitle = subTitle;
        this.description = description;
        this.image = image;
        this.isAdult = isAdult;
        this.cta = cta;
    }

    public static Highlight fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new Highlight(
                BffJson.str(json, "id"),
                BffJson.str(json, "title"),
                BffJson.str(json, "sub_title", "subTitle"),
                BffJson.str(json, "description"),
                BffJson.str(json, "image", "image_url"),
                BffJson.bool(json, "is_adult", "isAdult"),
                Cta.fromJson(json.optJSONObject("cta")));
    }
}
