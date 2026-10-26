package local.mintv.details;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** One localized metadata row, e.g. {@code Žanrovi: Drama}. */
public final class MetaRow {

    public final String name;
    public final String value;
    public final String type;

    private MetaRow(String name, String value, String type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    public static MetaRow fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        MetaRow row = new MetaRow(BffJson.str(json, "name"), BffJson.str(json, "value"),
                BffJson.str(json, "type"));
        return row.value.isEmpty() ? null : row;
    }
}
