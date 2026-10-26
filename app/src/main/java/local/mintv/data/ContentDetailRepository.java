package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import local.mintv.details.ActionSet;
import local.mintv.details.Episode;
import local.mintv.details.PersonFeed;
import local.mintv.details.ProgramDetail;
import local.mintv.details.RecordingActions;
import local.mintv.details.SeriesDetail;
import local.mintv.net.ApiException;
import local.mintv.net.BffApi;
import local.mintv.net.BffJson;
import local.mintv.net.GuestAuth;

/** The details hub: guest metadata plus authenticated actions, episodes and related feeds. */
public final class ContentDetailRepository {

    private static final String DENSITY = "xxhdpi";

    private final Context appContext;

    public ContentDetailRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public ProgramDetail program(String programId) throws ApiException {
        return ProgramDetail.fromJson(BffApi.get(appContext,
                "/details/program/" + programId, metadataQuery(), false, DENSITY,
                false, "SINGLE_PROGRAM_DETAIL", "PROGRAM_METADATA", ""));
    }

    public SeriesDetail series(String seriesId) throws ApiException {
        return SeriesDetail.fromJson(BffApi.get(appContext,
                "/details/series/" + seriesId, metadataQuery(), false, DENSITY,
                false, "SERIES_DETAIL", "SERIES_METADATA", ""));
    }

    public ActionSet actionsForProgram(String id, boolean isAdult, boolean interactedNpvr)
            throws ApiException {
        return ActionSet.fromJson(BffApi.get(appContext,
                "/details/program/" + id + "/actions/v2", actionsQuery(interactedNpvr),
                true, DENSITY, isAdult, "SINGLE_PROGRAM_DETAIL", "PROGRAM_ACTIONS", ""));
    }

    public ActionSet actionsForSeries(String id, boolean isAdult, boolean interactedNpvr)
            throws ApiException {
        return ActionSet.fromJson(BffApi.get(appContext,
                "/details/series/" + id + "/actions/v2", actionsQuery(interactedNpvr),
                true, DENSITY, isAdult, "SERIES_DETAIL", "SERIES_ACTIONS", ""));
    }

    public RecordingActions recordingActions(String id, boolean isSeries, boolean isAdult)
            throws ApiException {
        String path = isSeries
                ? "/details/series/" + id + "/recordings/actions/v2"
                : "/details/program/" + id + "/recordings/actions/v2";
        return RecordingActions.fromJson(BffApi.get(appContext, path,
                BffApi.query("natco_key", GuestAuth.NATCO_KEY), true, DENSITY,
                isAdult, isSeries ? "SERIES_DETAIL" : "SINGLE_PROGRAM_DETAIL",
                "RECORDING_ACTIONS", ""));
    }

    public List<Episode> seasonEpisodes(String seriesId, String seasonId, Integer seasonNumber,
                                        boolean isAdult) throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("interacted_with_nPVR", "false");
        if (seasonNumber != null) {
            query.put("season_number", String.valueOf(seasonNumber));
        }
        JSONObject json = BffApi.get(appContext,
                "/details/series/" + seriesId + "/season/" + seasonId + "/v2", query,
                true, DENSITY, isAdult, "SERIES_DETAIL", "SEASON_EPISODE_LIST", "");
        JSONArray array = BffJson.arrayDeep(json, "episodes");
        List<Episode> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Episode episode = Episode.fromJson(array.optJSONObject(i));
            if (episode != null && !episode.programId.isEmpty()) {
                out.add(episode);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Episode nextEpisode(String seriesId, String seasonId, String episodeId)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("device_type", "WEB");
        query.put("episode_id", episodeId);
        query.put("series_id", seriesId);
        query.put("season_id", seasonId);
        return Episode.fromJson(BffApi.get(appContext,
                "/details/series/episode/next", query, true));
    }

    public HomeRailFeed related(String programId, boolean live, boolean isSeries,
                                int size, int offset) throws ApiException {
        return new HomeRepository(appContext).related(programId, live, isSeries, offset, size);
    }

    public PersonFeed personItems(String personId, int size, int offset, boolean extended)
            throws ApiException {
        Map<String, String> query = BffApi.query();
        query.put("person_id", personId);
        query.put("size", String.valueOf(size));
        query.put("offset", String.valueOf(offset));
        query.put("isExtendedMode", String.valueOf(extended));
        return PersonFeed.fromJson(BffApi.get(appContext,
                "/search/person/" + personId + "/items", query, true));
    }

    /** Convenience bridge so the details screen can read the same Continue Watching cache. */
    public List<ContinueWatchingItem> bookmarks(String type) throws ApiException {
        return new BookmarkRepository(appContext).load(type);
    }

    public void putBookmark(ProgressUpdate update) throws ApiException {
        new BookmarkRepository(appContext).save(update);
    }

    public void deleteBookmarks(List<ContinueWatchingItem> items) throws ApiException {
        new BookmarkRepository(appContext).remove(items);
    }

    private static Map<String, String> metadataQuery() {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("interacted_with_nPVR", "false");
        return query;
    }

    private static Map<String, String> actionsQuery(boolean interactedNpvr) {
        Map<String, String> query = BffApi.query();
        query.put("natco_key", GuestAuth.NATCO_KEY);
        query.put("interacted_with_nPVR", String.valueOf(interactedNpvr));
        return query;
    }
}
