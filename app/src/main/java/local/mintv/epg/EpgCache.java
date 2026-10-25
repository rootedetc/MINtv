package local.mintv.epg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-wide, read-only view of the EPG day that is currently loaded.
 *
 * <p>{@link EpgRepository} owns fetching and disk caching; this holder exists so the player
 * can show "what is on now" for the channel it is playing (and update it while zapping)
 * without re-parsing the day or touching the network. It is populated by the EPG screen
 * after each delivery and is safe to read from any thread once published.</p>
 */
public final class EpgCache {

    private static volatile List<EpgChannel> channels = Collections.emptyList();

    private EpgCache() {
    }

    public static void set(List<EpgChannel> loaded) {
        channels = loaded == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(loaded));
    }

    public static boolean isEmpty() {
        return channels.isEmpty();
    }

    public static EpgChannel channel(int number) {
        for (EpgChannel channel : channels) {
            if (channel.number == number) {
                return channel;
            }
        }
        return null;
    }

    /** Programme on air for {@code channelNumber} at {@code nowMs}, or null. */
    public static EpgProgram programmeAt(int channelNumber, long nowMs) {
        EpgChannel channel = channel(channelNumber);
        return channel == null ? null : channel.programAt(nowMs);
    }

    /** Next programme after {@code nowMs}, or null. */
    public static EpgProgram next(int channelNumber, long nowMs) {
        EpgChannel channel = channel(channelNumber);
        return channel == null ? null : channel.nextAfter(nowMs);
    }
}
