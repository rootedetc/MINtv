package local.mintv.data;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** Server-provided navigation/badge button: {@code {title, deeplink, icon}}. */
public final class Cta {

    public final String title;
    public final String deeplink;
    public final String icon;

    private Cta(String title, String deeplink, String icon) {
        this.title = title;
        this.deeplink = deeplink;
        this.icon = icon;
    }

    public static Cta fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        Cta cta = new Cta(BffJson.str(json, "title"), BffJson.str(json, "deeplink"),
                BffJson.str(json, "icon"));
        return cta.deeplink.isEmpty() && cta.title.isEmpty() && cta.icon.isEmpty() ? null : cta;
    }

    public boolean hasDeeplink() {
        return !deeplink.isEmpty();
    }
}
