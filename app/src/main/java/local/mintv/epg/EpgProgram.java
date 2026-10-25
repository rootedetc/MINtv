package local.mintv.epg;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One EPG entry. Times are stored as epoch millis (UTC on the wire, displayed local).
 *
 * <p>Wire shape (verified against the live endpoint):
 * {@code program_id, start_time, end_time, description, show_type, genres[], cta.deeplink,
 * is_catchup_enabled, is_adult, series_id, listing_guid, catchup_id, catchup_key, media_id,
 * entitlements{...}}.</p>
 *
 * <p>The catch-up fields are the ones the player needs to ask the BFF for a past / currently
 * airing listing:</p>
 * <ul>
 *   <li>{@link #listingGuid} — the EPG listing id for this airing ({@code listing_guid}). Sent as
 *       {@code listing_guid} on the catch-up playinfo call when present.</li>
 *   <li>{@link #catchupId} — {@code <station_id>_<start>_<end>} server-side identity of the
 *       listing.</li>
 *   <li>{@link #catchupKey} — {@code <station_id>_<start>}, the coarser cache/grouping key.</li>
 *   <li>{@link #mediaId} — the timeshift asset id ({@code …-timeshift}), the fallback selector
 *       id when playinfo returns no {@code service_items[].media_id}.</li>
 * </ul>
 *
 * <p>{@code catchupId} and {@code catchupKey} are not query parameters on their own: the BFF
 * derives the same values from {@code station_id} + {@code start_time} (+ {@code end_time}), which
 * the catch-up request already carries. They are kept because they identify the listing without
 * having to re-format the times and are useful for logging/bookmarks.</p>
 */
public final class EpgProgram {

    public final String id;
    public final String title;
    public final String description;
    public final String genre;
    public final String showType;
    public final long startMs;
    public final long endMs;
    public final boolean catchup;
    public final boolean adult;
    /** {@code listing_guid}: EPG listing id, may be empty. */
    public final String listingGuid;
    /** {@code catchup_id}: {@code <station>_<start>_<end>}, may be empty. */
    public final String catchupId;
    /** {@code catchup_key}: {@code <station>_<start>}, may be empty. */
    public final String catchupKey;
    /** {@code media_id}: timeshift asset id, may be empty. */
    public final String mediaId;
    /** {@code entitlements.hasStartOver}; false when the object is absent. */
    public final boolean hasStartOver;
    /** {@code entitlements.hasTimeshift}; false when the object is absent. */
    public final boolean hasTimeshift;

    private EpgProgram(String id, String title, String description, String genre,
                       String showType, long startMs, long endMs, boolean catchup,
                       boolean adult, String listingGuid, String catchupId, String catchupKey,
                       String mediaId, boolean hasStartOver, boolean hasTimeshift) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.genre = genre;
        this.showType = showType;
        this.startMs = startMs;
        this.endMs = endMs;
        this.catchup = catchup;
        this.adult = adult;
        this.listingGuid = listingGuid;
        this.catchupId = catchupId;
        this.catchupKey = catchupKey;
        this.mediaId = mediaId;
        this.hasStartOver = hasStartOver;
        this.hasTimeshift = hasTimeshift;
    }

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }

    /** 0..1 progress, clamped. Returns 0 outside the programme. */
    public float progress(long nowMs) {
        if (nowMs <= startMs) {
            return 0f;
        }
        if (nowMs >= endMs) {
            return 1f;
        }
        return (nowMs - startMs) / (float) durationMs();
    }

    public boolean isLive(long nowMs) {
        return nowMs >= startMs && nowMs < endMs;
    }

    /** True once the listing has finished and catch-up is enabled for it. */
    public boolean canCatchup(long nowMs) {
        return catchup && endMs > 0 && endMs <= nowMs;
    }

    /** True while the listing is on air and the backend allows starting it over. */
    public boolean canStartOver(long nowMs) {
        return catchup && isLive(nowMs);
    }

    static EpgProgram fromJson(String stationId, JSONObject json) {
        // Observed wire shape: this endpoint does NOT send a "title" field. The programme
        // name arrives in "episode_name" when the EPG has one, otherwise in "description"
        // (e.g. "Dnevnik 1", "TV kalendar"); "full_description" holds the synopsis.
        String name = clean(json.optString("episode_name", ""));
        if (name.isEmpty() || name.equalsIgnoreCase("Informacije nisu dostupne")
                || name.equalsIgnoreCase("Nema informacija")) {
            name = "";
        }
        String description = clean(json.optString("description", ""));
        String fullDescription = clean(json.optString("full_description", ""));

        String title = !name.isEmpty() ? name : description;
        if (title.isEmpty()) {
            title = fullDescription;
        }
        if (title.isEmpty()) {
            title = "Bez naziva";
        }

        // Avoid repeating the title as its own synopsis in the info strip.
        String synopsis = fullDescription;
        if (synopsis.isEmpty() && !description.equalsIgnoreCase(title)) {
            synopsis = description;
        }
        if (synopsis.equalsIgnoreCase(title)) {
            synopsis = "";
        }

        String genre = "";
        JSONArray genres = json.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            JSONObject g = genres.optJSONObject(0);
            genre = g == null ? clean(genres.optString(0, "")) : clean(g.optString("name", ""));
        }

        JSONObject entitlements = json.optJSONObject("entitlements");
        boolean hasStartOver = entitlements != null
                && entitlements.optBoolean("hasStartOver", false);
        boolean hasTimeshift = entitlements != null
                && entitlements.optBoolean("hasTimeshift", false);

        return new EpgProgram(
                clean(json.optString("program_id", "")),
                title,
                synopsis,
                genre,
                clean(json.optString("show_type", "")),
                parseTime(json.optString("start_time", "")),
                parseTime(json.optString("end_time", "")),
                json.optBoolean("is_catchup_enabled", false),
                json.optBoolean("is_adult", false),
                clean(json.optString("listing_guid", "")),
                clean(json.optString("catchup_id", "")),
                clean(json.optString("catchup_key", "")),
                clean(json.optString("media_id", "")),
                hasStartOver,
                hasTimeshift);
    }

    /** Parses {@code 2026-10-25T23:55:00.00Z} (also tolerates a plain ISO string). */
    static long parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String normalised = value.trim().replace("Z", "+0000");
        // Trim sub-second precision that SimpleDateFormat cannot handle consistently.
        int dot = normalised.indexOf('.');
        if (dot > 0) {
            int end = dot + 1;
            while (end < normalised.length() && Character.isDigit(normalised.charAt(end))) {
                end++;
            }
            normalised = normalised.substring(0, dot) + normalised.substring(end);
        }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ"};
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                Date date = format.parse(normalised);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
                // try the next pattern
            }
        }
        return 0L;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }
}
