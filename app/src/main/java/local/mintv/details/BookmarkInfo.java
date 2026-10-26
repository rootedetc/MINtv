package local.mintv.details;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** Resume point returned in {@code actions.bookmark}. */
public final class BookmarkInfo {

    public final long timeSeconds;
    public final long runtimeSeconds;

    private BookmarkInfo(long timeSeconds, long runtimeSeconds) {
        this.timeSeconds = timeSeconds;
        this.runtimeSeconds = runtimeSeconds;
    }

    public static BookmarkInfo fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        long time = BffJson.longValue(json, 0L, "time_seconds", "timeSeconds");
        long runtime = BffJson.longValue(json, 0L, "runtime_seconds", "runtimeSeconds");
        return time <= 0 && runtime <= 0 ? null : new BookmarkInfo(time, runtime);
    }

    public boolean hasResume() {
        return timeSeconds > 0 && runtimeSeconds > 0 && timeSeconds < runtimeSeconds;
    }

    public int percent() {
        if (runtimeSeconds <= 0 || timeSeconds <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(100.0 * timeSeconds / runtimeSeconds));
    }
}
