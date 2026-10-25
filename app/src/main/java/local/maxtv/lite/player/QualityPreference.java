package local.maxtv.lite.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-visible playback quality policy.
 *
 * <ul>
 *   <li>{@link #AUTO} - start cheap for a fast first frame, then move to the highest
 *       rendition the decoder supports. Falls back to a lower cap if the link stalls, and
 *       retries the top rendition periodically.</li>
 *   <li>{@link #MAX} - lock to the highest rendition from the first segment. No ramp, no
 *       automatic downshift; one quality switch never happens because there is no switch.</li>
 *   <li>{@link #SAVER} - lock to a ~720p cap for slow links or metered connections.</li>
 * </ul>
 */
public final class QualityPreference {

    public enum Quality {
        AUTO("Auto"),
        MAX("Maximum"),
        SAVER("Data saver");

        public final String label;

        Quality(String label) {
            this.label = label;
        }

        public Quality next() {
            switch (this) {
                case AUTO:
                    return MAX;
                case MAX:
                    return SAVER;
                default:
                    return AUTO;
            }
        }
    }

    private static final String PREFS = "playback";
    private static final String KEY_QUALITY = "quality";

    private QualityPreference() {
    }

    public static Quality get(Context context) {
        String stored = prefs(context).getString(KEY_QUALITY, Quality.AUTO.name());
        try {
            return Quality.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Quality.AUTO;
        }
    }

    public static void set(Context context, Quality quality) {
        prefs(context).edit().putString(KEY_QUALITY, quality.name()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
