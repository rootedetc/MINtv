package local.mintv.details;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** The server's action vocabulary for one program or series. */
public final class ActionSet {

    public final boolean deviceNotCompatible;
    public final boolean geoBlocked;
    public final String providerId;
    public final boolean pinned;
    public final List<WatchAction> watch;
    public final List<WatchAction> liveSchedule;
    public final List<WatchAction> upcomingSchedule;
    public final List<WatchAction> catchupSchedules;
    public final List<WatchAction> subscribe;
    public final List<WatchAction> channelSubscribe;
    public final List<WatchAction> trailer;
    public final BookmarkInfo bookmark;
    public final String svodSubscriptionMessage;
    public final JSONObject raw;

    private ActionSet(Builder b) {
        this.deviceNotCompatible = b.deviceNotCompatible;
        this.geoBlocked = b.geoBlocked;
        this.providerId = b.providerId;
        this.pinned = b.pinned;
        this.watch = b.watch;
        this.liveSchedule = b.liveSchedule;
        this.upcomingSchedule = b.upcomingSchedule;
        this.catchupSchedules = b.catchupSchedules;
        this.subscribe = b.subscribe;
        this.channelSubscribe = b.channelSubscribe;
        this.trailer = b.trailer;
        this.bookmark = b.bookmark;
        this.svodSubscriptionMessage = b.svodSubscriptionMessage;
        this.raw = b.raw;
    }

    public static ActionSet empty() {
        return new ActionSet(new Builder());
    }

    public static ActionSet fromJson(JSONObject json) {
        Builder b = new Builder();
        if (json == null) {
            return new ActionSet(b);
        }
        JSONObject data = json.optJSONObject("data");
        if (data != null && json.optJSONObject("actions") == null
                && data.optJSONObject("actions") != null) {
            json = data;
        }
        b.raw = json;
        b.deviceNotCompatible = BffJson.bool(json,
                "is_device_not_compatible", "deviceNotCompatible");
        b.geoBlocked = BffJson.bool(json, "is_geo_blocked", "geoBlocked");
        b.providerId = BffJson.str(json, "provider_id", "providerId");

        JSONObject actions = BffJson.obj(json, "actions");
        if (actions == null) {
            actions = json;
        }
        b.pinned = BffJson.bool(actions, "pinned");
        b.watch = WatchAction.list(BffJson.array(actions, "watch"));
        b.liveSchedule = WatchAction.list(BffJson.array(actions,
                "live_schedule", "liveSchedule", "schedules"));
        b.upcomingSchedule = WatchAction.list(BffJson.array(actions,
                "upcoming_schedule", "upcomingSchedule"));
        b.catchupSchedules = WatchAction.list(BffJson.array(actions,
                "catchup_schedules", "catchupSchedules"));
        b.subscribe = WatchAction.list(BffJson.array(actions, "subscribe"));
        b.channelSubscribe = WatchAction.list(BffJson.array(actions,
                "channel_subscribe", "channelSubscribe"));
        b.trailer = WatchAction.list(BffJson.array(actions, "trailer"));
        b.bookmark = BookmarkInfo.fromJson(BffJson.obj(actions, "bookmark"));
        if (b.bookmark == null) {
            b.bookmark = BookmarkInfo.fromJson(BffJson.obj(json, "bookmark"));
        }
        b.svodSubscriptionMessage = BffJson.str(actions,
                "svod_subscription_message", "svodSubscriptionMessage");
        return new ActionSet(b);
    }

    public WatchAction primaryWatch() {
        if (!watch.isEmpty()) {
            return watch.get(0);
        }
        if (!catchupSchedules.isEmpty()) {
            return catchupSchedules.get(0);
        }
        if (!liveSchedule.isEmpty()) {
            return liveSchedule.get(0);
        }
        return null;
    }

    public boolean hasWatch() {
        return primaryWatch() != null;
    }

    public boolean hasTrailer() {
        return !trailer.isEmpty();
    }

    private static final class Builder {
        boolean deviceNotCompatible, geoBlocked, pinned;
        String providerId = "", svodSubscriptionMessage = "";
        List<WatchAction> watch = Collections.emptyList();
        List<WatchAction> liveSchedule = Collections.emptyList();
        List<WatchAction> upcomingSchedule = Collections.emptyList();
        List<WatchAction> catchupSchedules = Collections.emptyList();
        List<WatchAction> subscribe = Collections.emptyList();
        List<WatchAction> channelSubscribe = Collections.emptyList();
        List<WatchAction> trailer = Collections.emptyList();
        BookmarkInfo bookmark;
        JSONObject raw;
    }
}
