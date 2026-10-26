package local.mintv.data;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * The language of the app's own interface.
 *
 * <p>Only two values ship: {@link #HR} (the product default) and {@link #EN}. Server
 * catalogue text stays Croatian regardless of this choice; this preference only changes
 * the resources the app reads its own labels from.</p>
 *
 * <p>On API 33+ the choice is also handed to {@link LocaleManager} so the system
 * per-app language setting and the app agree. API 26–32 have no system per-app locale,
 * so every activity wraps its base context instead (see {@code local.mintv.BaseActivity}).</p>
 */
public final class LocalePreference {

    public static final String HR = "hr";
    public static final String EN = "en";

    private static final String PREFS = "locale";
    private static final String KEY_LANGUAGE = "language";

    private LocalePreference() {
    }

    /** The stored choice, or the system-derived default when nothing has been stored yet. */
    public static String get(Context context) {
        String stored = prefs(context).getString(KEY_LANGUAGE, null);
        return isSupported(stored) ? stored : defaultTag(context);
    }

    /** Persists {@code tag} and, on API 33+, applies it through the platform. */
    public static void set(Context context, String tag) {
        String value = isSupported(tag) ? tag : defaultTag(context);
        prefs(context).edit().putString(KEY_LANGUAGE, value).commit();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                manager.setApplicationLocales(new LocaleList(Locale.forLanguageTag(value)));
            }
        }
    }

    /**
     * Returns a context whose resources and configuration use the chosen language.
     * Called from {@code attachBaseContext} so every view, string and date in the
     * activity follows the choice.
     */
    public static Context wrap(Context context) {
        Locale locale = Locale.forLanguageTag(get(context));
        LocaleList locales = new LocaleList(locale);
        Locale.setDefault(locale);
        LocaleList.setDefault(locales);
        Configuration configuration =
                new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(locales);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }

    /** English when the device language is English; Croatian in every other case. */
    public static String defaultTag(Context context) {
        Locale system = systemLocale();
        return system != null && system.getLanguage().startsWith("en") ? EN : HR;
    }

    public static boolean isSupported(String tag) {
        return HR.equals(tag) || EN.equals(tag);
    }

    /**
     * The system language, not the app's own override. {@link Locale#getDefault()} is
     * changed by {@link LocaleManager} on API 33+, so read the system configuration.
     */
    private static Locale systemLocale() {
        try {
            LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
            if (!locales.isEmpty()) {
                return locales.get(0);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the process default.
        }
        return Locale.getDefault();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
