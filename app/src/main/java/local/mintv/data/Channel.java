package local.mintv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import local.mintv.R;

/**
 * One linear channel as returned by the public MAXtv EPG endpoint.
 *
 * <p>Deliberately a plain immutable value object: no framework dependencies, no parsing
 * side effects. Only the fields needed by milestone 1 are kept; everything else in the
 * upstream payload is ignored so a schema change cannot break the app.</p>
 */
public final class Channel {

    private final int number;
    private final int channelId;
    private final String stationId;
    private final String title;
    private final boolean syntheticTitle;
    private final String logoUrl;
    private final String quality;
    private final boolean freeToAir;
    private final String type;
    private final boolean hasDvb;
    private final String dashUrl;
    private final String hlsUrl;
    private final String pidDash;
    private final String pidHls;
    private final String mediaPid;

    private Channel(int number,
                    int channelId,
                    String stationId,
                    String title,
                    boolean syntheticTitle,
                    String logoUrl,
                    String quality,
                    boolean freeToAir,
                    String type,
                    boolean hasDvb,
                    String dashUrl,
                    String hlsUrl,
                    String pidDash,
                    String pidHls,
                    String mediaPid) {
        this.number = number;
        this.channelId = channelId;
        this.stationId = stationId;
        this.title = title;
        this.syntheticTitle = syntheticTitle;
        this.logoUrl = logoUrl;
        this.quality = quality;
        this.freeToAir = freeToAir;
        this.type = type;
        this.hasDvb = hasDvb;
        this.dashUrl = dashUrl;
        this.hlsUrl = hlsUrl;
        this.pidDash = pidDash;
        this.pidHls = pidHls;
        this.mediaPid = mediaPid;
    }

    /**
     * Parses the exact subset of fields documented for the guest endpoint:
     * channel_number, station_id, title, channel_logo, quality, is_free_to_air,
     * video_src_dash, video_src_m3u, pid_dash, pid_m3u.
     */
    public static Channel fromJson(JSONObject json) {
        int number = json.optInt("channel_number", -1);
        if (number < 0) {
            // Older payloads occasionally encode the number as a string.
            try {
                number = Integer.parseInt(json.optString("channel_number", "-1").trim());
            } catch (NumberFormatException ignored) {
                number = -1;
            }
        }
        String title = clean(json.optString("title", ""));
        boolean syntheticTitle = title.isEmpty();
        if (syntheticTitle) {
            title = number >= 0 ? "Kanal " + number : "Kanal";
        }
        int channelId = json.optInt("channel_id", number);
        if (channelId < 0) {
            channelId = number;
        }
        String type = clean(json.optString("type", "linear"));
        if (type.isEmpty()) {
            type = "linear";
        }
        boolean hasDvb = !clean(json.optString("dvb_url", "")).isEmpty()
                || hasDistribution(json.optJSONArray("distribution_types"), "dvb-t");
        return new Channel(
                number,
                channelId,
                clean(json.optString("station_id", "")),
                title,
                syntheticTitle,
                clean(json.optString("channel_logo", "")),
                clean(json.optString("quality", "")),
                json.optBoolean("is_free_to_air", false),
                type,
                hasDvb,
                clean(json.optString("video_src_dash", "")),
                clean(json.optString("video_src_m3u", "")),
                clean(json.optString("pid_dash", "")),
                clean(json.optString("pid_m3u", "")),
                clean(json.optString("media_pid", "")));
    }

    private static boolean hasDistribution(JSONArray array, String value) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length(); i++) {
            if (value.equalsIgnoreCase(clean(array.optString(i, "")))) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        // org.json represents JSON null as the string "null" in some implementations.
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    public int getNumber() {
        return number;
    }

    /** Order key for {@code /epg/channel/order}. */
    public int getChannelId() {
        return channelId;
    }

    public String getStationId() {
        return stationId;
    }

    public String getTitle() {
        return title;
    }

    /** Localized name for channels whose payload carried no title. */
    public String getTitle(Context context) {
        if (!syntheticTitle) {
            return title;
        }
        return number >= 0
                ? context.getString(R.string.channel_number_fallback, number)
                : context.getString(R.string.channel_fallback);
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getQuality() {
        return quality;
    }

    public boolean isFreeToAir() {
        return freeToAir;
    }

    /** {@code linear}, {@code virtual} or {@code synthetic}. */
    public String getType() {
        return type;
    }

    public boolean isVirtual() {
        return "virtual".equalsIgnoreCase(type);
    }

    public boolean isSynthetic() {
        return "synthetic".equalsIgnoreCase(type);
    }

    /** DVB channels cannot be reordered in the custom channel list. */
    public boolean hasDvb() {
        return hasDvb;
    }

    public boolean isReorderable() {
        return !hasDvb() && !isSynthetic();
    }

    /** Clear or DRM-protected DASH manifest. Passed to {@code PlayerActivity} as-is. */
    public String getDashUrl() {
        return dashUrl;
    }

    /** Kept for a future HLS fallback; milestone 1 plays DASH only. */
    public String getHlsUrl() {
        return hlsUrl;
    }

    /** Identifiers used by the authenticated MAXtv playback/token APIs (unused in M1). */
    /** Selector media id used by the authenticated /media call (may be empty). */
    public String getMediaPid() {
        return mediaPid;
    }

    public String getPidDash() {
        return pidDash;
    }

    public String getPidHls() {
        return pidHls;
    }

    public boolean isPlayable() {
        return !dashUrl.isEmpty();
    }
}
