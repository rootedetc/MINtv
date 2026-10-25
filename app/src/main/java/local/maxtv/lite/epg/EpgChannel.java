package local.maxtv.lite.epg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A channel together with its programmes for one day, ordered by start time. */
public final class EpgChannel {

    public final int number;
    public final String stationId;
    public final String title;
    public final String logoUrl;
    public final String quality;
    public final boolean freeToAir;
    private final List<EpgProgram> programs;

    public EpgChannel(int number, String stationId, String title, String logoUrl,
                      String quality, boolean freeToAir, List<EpgProgram> programs) {
        this.number = number;
        this.stationId = stationId;
        this.title = title;
        this.logoUrl = logoUrl;
        this.quality = quality;
        this.freeToAir = freeToAir;
        List<EpgProgram> sorted = new ArrayList<>(programs);
        Collections.sort(sorted, Comparator.comparingLong(p -> p.startMs));
        this.programs = Collections.unmodifiableList(sorted);
    }

    public List<EpgProgram> programs() {
        return programs;
    }

    /** Programme covering {@code nowMs}, or null. */
    public EpgProgram programAt(long nowMs) {
        for (EpgProgram program : programs) {
            if (nowMs >= program.startMs && nowMs < program.endMs) {
                return program;
            }
        }
        return null;
    }

    /** First programme starting at or after {@code nowMs}, or null. */
    public EpgProgram nextAfter(long nowMs) {
        for (EpgProgram program : programs) {
            if (program.startMs >= nowMs) {
                return program;
            }
        }
        return null;
    }

    /** Longest programme end time, useful for laying out the day. */
    public long lastEndMs() {
        long end = 0L;
        for (EpgProgram program : programs) {
            end = Math.max(end, program.endMs);
        }
        return end;
    }

    public boolean hasProgrammes() {
        return !programs.isEmpty();
    }
}
