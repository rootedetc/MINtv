package local.mintv.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** One idempotent bookmark write; the path segment is the uppercase {@link #type}. */
public final class ProgressUpdate {

    public final String type;      // VOD | CATCHUP | RECORDING
    public final String id;        // title_id / catchup_id / recording_id
    public final String programId;
    public final String mediaId;
    public final String stationId;
    public final String listingGuid;
    public final String seriesId;
    public final String seasonId;
    public final long timeSeconds;
    public final long runtimeSeconds;
    public final long startTime;
    public final long endTime;

    private ProgressUpdate(Builder b) {
        this.type = b.type;
        this.id = b.id;
        this.programId = b.programId;
        this.mediaId = b.mediaId;
        this.stationId = b.stationId;
        this.listingGuid = b.listingGuid;
        this.seriesId = b.seriesId;
        this.seasonId = b.seasonId;
        this.timeSeconds = b.timeSeconds;
        this.runtimeSeconds = b.runtimeSeconds;
        this.startTime = b.startTime;
        this.endTime = b.endTime;
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public JSONObject toJson() {
        JSONObject body = new JSONObject();
        try {
            body.put("time_seconds", timeSeconds);
            body.put("runtime_seconds", runtimeSeconds);
            if (!id.isEmpty()) {
                body.put("id", id);
            }
            if (!programId.isEmpty()) {
                body.put("program_id", programId);
            }
            if (!mediaId.isEmpty()) {
                body.put("media_id", mediaId);
            }
            if (!seasonId.isEmpty()) {
                body.put("season_id", seasonId);
            }
            if (!seriesId.isEmpty()) {
                body.put("series_id", seriesId);
            }
            if (!stationId.isEmpty()) {
                body.put("station_id", stationId);
            }
            if (!listingGuid.isEmpty()) {
                body.put("listing_guid", listingGuid);
            }
            if (startTime > 0) {
                body.put("start_time", wireTime(startTime));
            }
            if (endTime > 0) {
                body.put("end_time", wireTime(endTime));
            }
        } catch (JSONException ignored) {
            // All keys are plain strings/longs.
        }
        return body;
    }

    private static String wireTime(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SS'Z'", java.util.Locale.US));
    }

    public boolean isComplete() {
        return runtimeSeconds > 0 && timeSeconds >= runtimeSeconds - 2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProgressUpdate)) {
            return false;
        }
        ProgressUpdate other = (ProgressUpdate) o;
        return timeSeconds == other.timeSeconds
                && runtimeSeconds == other.runtimeSeconds
                && Objects.equals(type, other.type)
                && Objects.equals(id, other.id)
                && Objects.equals(programId, other.programId)
                && Objects.equals(mediaId, other.mediaId)
                && Objects.equals(stationId, other.stationId)
                && Objects.equals(listingGuid, other.listingGuid)
                && Objects.equals(seriesId, other.seriesId)
                && Objects.equals(seasonId, other.seasonId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id, programId, mediaId, stationId, listingGuid, seriesId,
                seasonId, timeSeconds, runtimeSeconds);
    }

    public static final class Builder {
        private final String type;
        private String id = "", programId = "", mediaId = "", stationId = "", listingGuid = "";
        private String seriesId = "", seasonId = "";
        private long timeSeconds, runtimeSeconds, startTime, endTime;

        private Builder(String type) {
            this.type = type == null ? "" : type.toUpperCase();
        }

        public Builder id(String value) {
            this.id = value == null ? "" : value;
            return this;
        }

        public Builder programId(String value) {
            this.programId = value == null ? "" : value;
            return this;
        }

        public Builder mediaId(String value) {
            this.mediaId = value == null ? "" : value;
            return this;
        }

        public Builder stationId(String value) {
            this.stationId = value == null ? "" : value;
            return this;
        }

        public Builder listingGuid(String value) {
            this.listingGuid = value == null ? "" : value;
            return this;
        }

        public Builder seriesId(String value) {
            this.seriesId = value == null ? "" : value;
            return this;
        }

        public Builder seasonId(String value) {
            this.seasonId = value == null ? "" : value;
            return this;
        }

        public Builder time(long seconds) {
            this.timeSeconds = seconds;
            return this;
        }

        public Builder runtime(long seconds) {
            this.runtimeSeconds = seconds;
            return this;
        }

        public Builder startTime(long epochMs) {
            this.startTime = epochMs;
            return this;
        }

        public Builder endTime(long epochMs) {
            this.endTime = epochMs;
            return this;
        }

        public ProgressUpdate build() {
            return new ProgressUpdate(this);
        }
    }
}
