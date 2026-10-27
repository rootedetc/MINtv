package local.mintv.data;

import android.content.Context;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Process-wide snapshot of the channel numbers included in the signed-in account's
 * subscription.
 *
 * <p>The BFF already exposes this at {@code /epg/channel/subscriptions}; the channel grid and
 * the guide both need it to mark the channels the account cannot watch. The snapshot is tied
 * to the account it was fetched for, so a sign-out or a different login cannot inherit another
 * user's lock state. Until a successful fetch, {@link #current(Context)} returns {@code null}
 * and the UI must not guess.</p>
 */
public final class ChannelSubscriptions {

    private static volatile Set<Integer> numbers = Collections.emptySet();
    private static volatile String accountKey = "";
    private static volatile boolean known;

    private ChannelSubscriptions() {
    }

    /** Stores a successful fetch for the currently signed-in account. */
    public static synchronized void publish(Context context, Collection<Integer> subscribed) {
        String key = accountKey(Session.get(context));
        if (key.isEmpty()) {
            clear();
            return;
        }
        Set<Integer> copy = new LinkedHashSet<>();
        if (subscribed != null) {
            for (Integer number : subscribed) {
                if (number != null && number >= 0) {
                    copy.add(number);
                }
            }
        }
        numbers = Collections.unmodifiableSet(copy);
        accountKey = key;
        known = true;
    }

    public static synchronized void clear() {
        numbers = Collections.emptySet();
        accountKey = "";
        known = false;
    }

    /**
     * The subscribed channel numbers for the current account, or {@code null} when they have
     * not been fetched yet (or belong to a previous account).
     */
    public static synchronized Set<Integer> current(Context context) {
        if (!known) {
            return null;
        }
        String key = accountKey(Session.get(context));
        return !key.isEmpty() && key.equals(accountKey) ? numbers : null;
    }

    /**
     * True only when the snapshot is trustworthy and the channel is neither free-to-air nor
     * in the subscription. Unknown state is never treated as locked.
     */
    public static boolean isLocked(Context context, int channelNumber, boolean freeToAir) {
        if (freeToAir || channelNumber < 0) {
            return false;
        }
        Set<Integer> current = current(context);
        return current != null && !current.contains(channelNumber);
    }

    private static String accountKey(Session session) {
        if (session == null || !session.isLoggedIn()) {
            return "";
        }
        String userId = session.userId();
        return userId.isEmpty() ? session.username() : userId;
    }
}
