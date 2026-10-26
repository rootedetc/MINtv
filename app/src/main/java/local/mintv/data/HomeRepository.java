package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.BffJson;
import local.mintv.net.GuestAuth;

/** Every home/rails endpoint from the home spec. Methods are synchronous; call off the main thread. */
public final class HomeRepository {

    /** Known HR root used only when {@code /home/root/categories} fails. */
    public static final String FALLBACK_ROOT_ID = "61260e9988ed9d0001c921d6";

    private final Context appContext;
    private volatile String cachedRootId = "";

    public HomeRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public HomeCategories categories() throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        HomeCategories categories = HomeCategories.fromJson(
                BffApi.get(appContext, "/home/root/categories", query, false));
        if (!categories.defaultCategoryId.isEmpty()) {
            cachedRootId = categories.defaultCategoryId;
        }
        return categories;
    }

    public String rootCategoryId() {
        if (!cachedRootId.isEmpty()) {
            return cachedRootId;
        }
        try {
            categories();
        } catch (ApiException ignored) {
            // fall through to the known id; the caller sees the category call's own error
        }
        return cachedRootId.isEmpty() ? FALLBACK_ROOT_ID : cachedRootId;
    }

    public HomePage page(String categoryId, int offset, int pageSize, boolean loggedIn)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("component_type", loggedIn ? "all" : "discovery");
        return HomePage.fromJson(BffApi.get(appContext,
                "/home/page/" + categoryId, query, false));
    }

    public HomePage page(String categoryId, int offset, int pageSize, boolean loggedIn,
                         boolean adult) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("component_type", loggedIn ? "all" : "discovery");
        return HomePage.fromJson(BffApi.get(appContext,
                "/home/page/" + categoryId, query, false, "", adult, "", "", ""));
    }

    public HomeComponent component(String componentId) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        return HomeComponent.fromJson(BffApi.get(appContext,
                "/home/component/" + componentId, query, false));
    }

    /** Editorial rail page ({@code page_size} is mandatory). */
    public HomeRailFeed assets(String componentId, String type, String storeId,
                               int offset, int pageSize) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        if (type != null && !type.isEmpty()) {
            query.put("type", type);
        }
        if (storeId != null && !storeId.isEmpty()) {
            query.put("store_id", storeId);
        }
        return HomeRailFeed.fromJson(BffApi.get(appContext,
                "/home/component/" + componentId + "/assets", query, false));
    }

    /**
     * Follows {@code content_details.end_point}; editorial rails land on
     * {@code /home/component/{id}/assets}, subscriber rails on their own feed.
     */
    public HomeRailFeed rail(HomeComponent component, int offset, int pageSize)
            throws ApiException {
        if (component == null) {
            return HomeRailFeed.fromJson(null);
        }
        String endPoint = component.endPoint();
        if (endPoint.isEmpty() || endPoint.startsWith("home/component/")) {
            return assets(component.id, component.feedType(), component.storeId,
                    offset, pageSize);
        }
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        if (!component.feedType().isEmpty()) {
            query.put("type", component.feedType());
        }
        if (!component.storeId.isEmpty()) {
            query.put("store_id", component.storeId);
        }
        String path = endPoint.startsWith("/") ? endPoint : "/" + endPoint;
        boolean auth = component.requiresAuth()
                || component.tag().toLowerCase(java.util.Locale.US).contains("recommend");
        return HomeRailFeed.fromJson(BffApi.get(appContext, path, query, auth));
    }

    public List<Highlight> highlights(String componentId) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("page_size", "30");
        query.put("offset", "0");
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        JSONObject json = BffApi.get(appContext,
                "/home/component/" + componentId + "/highlights", query, false,
                "XXXHDPI", false, "", "", "");
        JSONArray array = BffJson.arrayDeep(json, "highlights");
        if (array.length() == 0) {
            return Collections.emptyList();
        }
        List<Highlight> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Highlight highlight = Highlight.fromJson(array.optJSONObject(i));
            if (highlight != null) {
                out.add(highlight);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public HomePage subComponents(String componentId, int offset, int pageSize)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        return HomePage.fromJson(BffApi.get(appContext,
                "/home/subcomponents/" + componentId, query, false));
    }

    public HomePage subPage(String componentId, int offset, int pageSize, String storeId)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("page_size", String.valueOf(pageSize));
        query.put("offset", String.valueOf(offset));
        if (storeId != null && !storeId.isEmpty()) {
            query.put("store_id", storeId);
        }
        return HomePage.fromJson(BffApi.get(appContext,
                "/home/subpage/" + componentId, query, false));
    }

    /** Authenticated related rail; handles the observed {@code {data:{assets,next_offset}}} wrapper. */
    public HomeRailFeed related(String programId, boolean live, boolean series,
                                int offset, int size) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("program_id", programId);
        query.put("live", String.valueOf(live));
        query.put("size", String.valueOf(size));
        query.put("offset", String.valueOf(offset));
        query.put("is_series", String.valueOf(series));
        return HomeRailFeed.fromJson(BffApi.get(appContext,
                "/relatedcontent/feed", query, true));
    }
}
