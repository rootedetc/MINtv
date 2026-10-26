package local.mintv.data;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** Content-warning icon from the shared asset model. */
public final class WarningLabel {

    public final String translationKey;
    public final String icon;
    public final String label;

    private WarningLabel(String translationKey, String icon, String label) {
        this.translationKey = translationKey;
        this.icon = icon;
        this.label = label;
    }

    public static WarningLabel fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        WarningLabel label = new WarningLabel(
                BffJson.str(json, "translation_key", "translationKey"),
                BffJson.str(json, "icon"),
                BffJson.str(json, "warning_label", "warningLabel", "label"));
        return label.label.isEmpty() && label.icon.isEmpty() ? null : label;
    }
}
