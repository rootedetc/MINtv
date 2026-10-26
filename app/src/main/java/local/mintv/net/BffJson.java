package local.mintv.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defensive JSON access shared by the BFF models.
 *
 * <p>The six MAXtv feature contracts mark several fields as inferred, so every reader tolerates
 * missing keys, JSON {@code null}, numeric strings and image objects. No model ever assumes a
 * property exists.</p>
 */
public final class BffJson {

    private BffJson() {
    }

    public static String str(JSONObject object, String... keys) {
        if (object == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || !object.has(key)) {
                continue;
            }
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) {
                continue;
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            } else if (value instanceof JSONObject) {
                String text = str((JSONObject) value, "url", "src", "value", "name");
                if (!text.isEmpty()) {
                    return text;
                }
            } else {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    public static int integer(JSONObject object, int fallback, String... keys) {
        String text = str(object, keys);
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static long longValue(JSONObject object, long fallback, String... keys) {
        String text = str(object, keys);
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean bool(JSONObject object, String... keys) {
        if (object == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null || !object.has(key)) {
                continue;
            }
            Object value = object.opt(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue() != 0;
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                return "true".equalsIgnoreCase(text) || "1".equals(text);
            }
        }
        return false;
    }

    public static JSONObject obj(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static JSONArray array(JSONObject object, String... keys) {
        if (object == null) {
            return new JSONArray();
        }
        for (String key : keys) {
            JSONArray value = object.optJSONArray(key);
            if (value != null) {
                return value;
            }
        }
        return new JSONArray();
    }

    public static List<JSONObject> objects(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<JSONObject> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    /** The first array found under {@code keys}, robust to a {@code data} wrapper. */
    public static JSONArray arrayDeep(JSONObject object, String... keys) {
        JSONArray direct = array(object, keys);
        if (direct.length() > 0) {
            return direct;
        }
        JSONObject data = object == null ? null : object.optJSONObject("data");
        return data == null ? direct : array(data, keys);
    }

    public static JSONObject data(JSONObject object) {
        JSONObject data = object == null ? null : object.optJSONObject("data");
        return data == null ? object : data;
    }

    /** Image fallback chain used by the web client: image_url, thumbnail, poster_image_url. */
    public static String image(JSONObject object) {
        if (object == null) {
            return "";
        }
        String url = str(object, "image_url", "thumbnail", "poster_image_url",
                "portraitThumbnail", "channel_logo");
        if (!url.isEmpty()) {
            return url;
        }
        JSONObject thumbnail = object.optJSONObject("thumbnail");
        return thumbnail == null ? "" : str(thumbnail, "url");
    }

    public static String poster(JSONObject object) {
        if (object == null) {
            return "";
        }
        String url = str(object, "poster_image_url", "image_url", "thumbnail",
                "portraitThumbnail");
        if (!url.isEmpty()) {
            return url;
        }
        JSONObject poster = object.optJSONObject("poster_image_url");
        return poster == null ? "" : str(poster, "url");
    }

    public static long time(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        String text = value.trim();
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    /**
     * Breadth-first search for the first object carrying any of the supplied keys. Login payloads
     * differ between single- and multi-subscription accounts, so the account object cannot be
     * assumed to sit at a fixed depth.
     */
    public static JSONObject find(JSONObject root, String... keys) {
        if (root == null) {
            return null;
        }
        List<JSONObject> queue = new ArrayList<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            JSONObject current = queue.remove(0);
            for (String key : keys) {
                if (current.has(key) && current.opt(key) != JSONObject.NULL) {
                    return current;
                }
            }
            JSONArray names = current.names();
            if (names == null) {
                continue;
            }
            for (int i = 0; i < names.length(); i++) {
                Object value = current.opt(names.optString(i));
                if (value instanceof JSONObject) {
                    queue.add((JSONObject) value);
                } else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    for (int j = 0; j < array.length(); j++) {
                        JSONObject child = array.optJSONObject(j);
                        if (child != null) {
                            queue.add(child);
                        }
                    }
                }
            }
        }
        return null;
    }

    public static String deepString(JSONObject root, String... keys) {
        JSONObject object = find(root, keys);
        return object == null ? "" : str(object, keys);
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    public static List<String> strings(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String value = clean(array.optString(i, ""));
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }
}
