package local.mintv.details;

import org.json.JSONObject;

import local.mintv.net.BffJson;

/** Extra recording metadata from {@code /details/{program|series}/{id}/recordings/actions/v2}. */
public final class RecordingActions {

    public final ActionSet actions;
    public final boolean deviceNotCompatible;
    public final String contentExpiryDisclaimer;
    public final long totalQuotaTimeSeconds;
    public final long consumedQuotaTimeSeconds;
    public final String contentExpiryMessage;

    private RecordingActions(ActionSet actions, boolean deviceNotCompatible,
                             String contentExpiryDisclaimer, long totalQuotaTimeSeconds,
                             long consumedQuotaTimeSeconds, String contentExpiryMessage) {
        this.actions = actions;
        this.deviceNotCompatible = deviceNotCompatible;
        this.contentExpiryDisclaimer = contentExpiryDisclaimer;
        this.totalQuotaTimeSeconds = totalQuotaTimeSeconds;
        this.consumedQuotaTimeSeconds = consumedQuotaTimeSeconds;
        this.contentExpiryMessage = contentExpiryMessage;
    }

    public static RecordingActions fromJson(JSONObject json) {
        return new RecordingActions(
                ActionSet.fromJson(json),
                BffJson.bool(json, "is_device_not_compatible", "deviceNotCompatible"),
                BffJson.str(json, "content_expiry_disclaimer_message",
                        "contentExpiryDisclaimerMessage"),
                BffJson.longValue(json, 0L, "total_quota_time_secs", "totalQuotaTimeSecs"),
                BffJson.longValue(json, 0L, "consumed_quota_time_secs",
                        "consumedQuotaTimeSecs"),
                BffJson.str(json, "content_expiry_message", "contentExpiryMessage"));
    }
}
