package local.mintv.search;

import android.content.Context;

import local.mintv.R;
import local.mintv.data.HomeAsset;

import java.util.List;

/** One row on the search surface. */
public final class SearchRow {

    public static final String ACTION_DELETE = "delete";
    public static final String ACTION_CLEAR = "clear";

    public final int type;
    public final String title;
    public final String subtitle;
    public final String imageUrl;
    public final String badge;
    public final String id;
    public final Object source;
    public final String action;

    private SearchRow(int type, String title, String subtitle, String imageUrl, String badge,
                      String id, Object source, String action) {
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.imageUrl = imageUrl;
        this.badge = badge;
        this.id = id;
        this.source = source;
        this.action = action;
    }

    public static SearchRow header(String title) {
        return new SearchRow(SearchRowsAdapter.TYPE_HEADER, title, "", "", "", "", null, "");
    }

    public static SearchRow message(String title) {
        return new SearchRow(SearchRowsAdapter.TYPE_MESSAGE, title, "", "", "", "", null, "");
    }

    public static SearchRow action(String title, String action) {
        return new SearchRow(SearchRowsAdapter.TYPE_ACTION, title, "", "", "", "", null, action);
    }

    public static SearchRow history(String query) {
        return new SearchRow(SearchRowsAdapter.TYPE_HISTORY, query, "", "",
                "", query, null, "");
    }

    public static SearchRow program(HomeAsset asset) {
        return program(asset, asset.isSeries() ? "SERIJA" : "FILM", asset.meta());
    }

    /** Badge supplied by the caller, e.g. {@code R.string.details_kind_series}. */
    public static SearchRow program(HomeAsset asset, String badge) {
        return program(asset, badge, asset.meta());
    }

    /** Localized variant: badge and runtime label follow the active app language. */
    public static SearchRow program(Context context, HomeAsset asset) {
        String badge = context.getString(asset.isSeries()
                ? R.string.details_kind_series : R.string.details_kind_program);
        return program(asset, badge, asset.meta(context));
    }

    private static SearchRow program(HomeAsset asset, String badge, String meta) {
        return new SearchRow(SearchRowsAdapter.TYPE_PROGRAM, asset.displayTitle(), meta,
                asset.image(), badge, asset.id, asset, "");
    }

    public static List<SearchRow> programList(List<HomeAsset> assets) {
        List<SearchRow> rows = new java.util.ArrayList<>();
        for (HomeAsset asset : assets) {
            rows.add(program(asset));
        }
        return rows;
    }

    /** Localized variant of {@link #programList(List)}. */
    public static List<SearchRow> programList(Context context, List<HomeAsset> assets) {
        List<SearchRow> rows = new java.util.ArrayList<>();
        for (HomeAsset asset : assets) {
            rows.add(program(context, asset));
        }
        return rows;
    }

    public static SearchRow person(Person person) {
        return new SearchRow(SearchRowsAdapter.TYPE_PERSON, person.name, person.roleText(),
                person.imageUrl, "", person.id, person, "");
    }

    public static SearchRow channel(String title, String subtitle, String image, int number,
                                    Object source) {
        return new SearchRow(SearchRowsAdapter.TYPE_CHANNEL, title, subtitle, image,
                String.valueOf(number), "channel:" + number, source, "");
    }

    /** Long-pressing a history row carries this signal so the screen can remove it. */
    public SearchRow deleteVariant() {
        return new SearchRow(type, title, subtitle, imageUrl, badge, id, source, ACTION_DELETE);
    }
}
