package local.maxtv.lite.player;

import java.util.Collections;
import java.util.List;

import local.maxtv.lite.data.Channel;

/**
 * The ordered channel list the player zaps through with CH+/CH-.
 *
 * <p>Kept as process-wide state on purpose: the player is a separate activity and the list
 * is large (247 entries), so passing it through an Intent would mean a TransactionTooLarge
 * risk. The list is replaced every time a channel is opened from the grid.</p>
 */
public final class ChannelQueue {

    private static List<Channel> channels = Collections.emptyList();
    private static int index = -1;

    private ChannelQueue() {
    }

    public static void set(List<Channel> list, int position) {
        channels = list == null ? Collections.emptyList() : list;
        index = position >= 0 && position < channels.size() ? position : -1;
    }

    public static Channel current() {
        return index >= 0 && index < channels.size() ? channels.get(index) : null;
    }

    /** Moves by {@code delta} with wraparound and returns the new channel, or null. */
    public static Channel step(int delta) {
        if (channels.isEmpty() || index < 0) {
            return null;
        }
        int size = channels.size();
        index = ((index + delta) % size + size) % size;
        return channels.get(index);
    }

    /** Alias used by the overlay. */
    public static List<Channel> channels() {
        return channels;
    }

    /** Immutable view of the current line-up (never null). */
    public static List<Channel> list() {
        return channels;
    }

    /** Index of the current channel, or -1. */
    public static int index() {
        return index;
    }

    public static boolean hasNext() {
        return channels.size() > 1 && index >= 0;
    }
}
