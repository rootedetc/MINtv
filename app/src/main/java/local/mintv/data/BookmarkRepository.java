package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.BffJson;
import local.mintv.net.GuestAuth;

/** Continue Watching: list, idempotent progress write, bulk delete. */
public final class BookmarkRepository {

    private final Context appContext;
    private volatile List<ContinueWatchingItem> cached = Collections.emptyList();
    private volatile long cachedAt;

    public BookmarkRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public List<ContinueWatchingItem> load() throws ApiException {
        return load("");
    }

    public List<ContinueWatchingItem> load(String type) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("type", type == null ? "" : type);
        JSONObject json = BffApi.get(appContext, "/details/bookmarks/v3", query, true,
                "xhdpi", false, "START_UP", "BOOKMARK_ALL", "");
        JSONArray array = BffJson.arrayDeep(json, "bookmarks");
        List<ContinueWatchingItem> parsed = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            ContinueWatchingItem item = ContinueWatchingItem.fromJson(array.optJSONObject(i));
            if (item != null) {
                parsed.add(item);
            }
        }
        if (type == null || type.isEmpty()) {
            cached = Collections.unmodifiableList(parsed);
            cachedAt = System.currentTimeMillis();
        }
        return Collections.unmodifiableList(parsed);
    }

    public List<ContinueWatchingItem> loadAllTypes() throws ApiException {
        List<ContinueWatchingItem> out = new ArrayList<>();
        ApiException firstFailure = null;
        boolean anySuccess = false;
        for (String type : new String[]{"", "CATCHUP", "RECORDING"}) {
            try {
                List<ContinueWatchingItem> page = load(type);
                anySuccess = true;
                for (ContinueWatchingItem item : page) {
                    boolean duplicate = false;
                    for (ContinueWatchingItem existing : out) {
                        if (existing.id.equals(item.id)
                                && existing.bookmarkType.equalsIgnoreCase(item.bookmarkType)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        out.add(item);
                    }
                }
            } catch (ApiException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (!anySuccess && firstFailure != null) {
            throw firstFailure;
        }
        return Collections.unmodifiableList(out);
    }

    public void save(ProgressUpdate update) throws ApiException {
        if (update == null || update.type.isEmpty() || update.timeSeconds <= 0
                || update.runtimeSeconds <= 0) {
            return;
        }
        String path = "/details/bookmarks/" + update.type.toUpperCase() + "/v3";
        String step = "SET_BOOKMARK";
        if ("CATCHUP".equalsIgnoreCase(update.type)) {
            step = "BOOKMARK_CATCHUP";
        } else if ("RECORDING".equalsIgnoreCase(update.type)) {
            step = "BOOKMARK_RECORDING";
        }
        BffApi.put(appContext, path, BffApi.query(), update.toJson(), true,
                "xhdpi", false, "PLAYER", step, "");
    }

    public void remove(List<ContinueWatchingItem> items) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            JSONArray bookmarks = new JSONArray();
            for (ContinueWatchingItem item : items) {
                JSONObject entry = new JSONObject();
                entry.put("id", item.id);
                entry.put("runtime_seconds", item.runtimeSeconds);
                entry.put("program_id", item.programId);
                entry.put("bookmark_type", item.bookmarkType);
                if (!item.subjectId.isEmpty()) {
                    entry.put("subject_id", item.subjectId);
                }
                bookmarks.put(entry);
            }
            body.put("bookmarks", bookmarks);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "Neispravan zahtjev.", 0, e);
        }
        BffApi.post(appContext, "/delete/bookmarks/v2", BffApi.query(), body, true,
                "xhdpi", false, "", "", "");
        cached = Collections.emptyList();
    }

    public ContinueWatchingItem resumeFor(String programId, String titleId) {
        for (ContinueWatchingItem item : cached) {
            if (programId != null && !programId.isEmpty()) {
                if (programId.equals(item.programId) || programId.equals(item.id)) {
                    return item;
                }
            } else if (titleId != null && !titleId.isEmpty() && titleId.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    public List<ContinueWatchingItem> cached() {
        return cached;
    }

    public long cachedAt() {
        return cachedAt;
    }

    public void cache(List<ContinueWatchingItem> items) {
        cached = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        cachedAt = System.currentTimeMillis();
    }

    public void clearCache() {
        cached = Collections.emptyList();
        cachedAt = 0;
    }
}
