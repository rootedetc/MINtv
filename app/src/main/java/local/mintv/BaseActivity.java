package local.mintv;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import local.mintv.data.LocalePreference;
import local.mintv.ui.TvRail;

/**
 * Activity base that applies the language chosen in Settings.
 *
 * <p>The locale is resolved before the activity's own resources are created, so every
 * string, layout and formatted date on the screen uses it from the first frame. If the
 * preference changes while this activity sits in the back stack, the next resume
 * recreates it once; the applied tag is remembered so the new instance does not loop.</p>
 */
public abstract class BaseActivity extends Activity {

    private String appliedLocale;
    private boolean recreateRequested;

    @Override
    protected void attachBaseContext(Context newBase) {
        Context wrapped = LocalePreference.wrap(newBase);
        super.attachBaseContext(wrapped);
        appliedLocale = LocalePreference.get(wrapped);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedLocale = LocalePreference.get(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String stored = LocalePreference.get(this);
        if (recreateRequested || stored.equals(appliedLocale)) {
            return;
        }
        recreateRequested = true;
        appliedLocale = stored;
        recreate();
    }

    /**
     * Back first returns focus to the source rail, which is the primary control on every
     * screen that has one. Only a Back pressed while the rail already holds focus performs
     * the normal action (finish the screen or leave the app). Screens without a rail, such
     * as Login, and players that consume Back themselves are untouched.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        TvRail rail = findViewById(R.id.rail);
        if (rail != null && !rail.holdsFocus() && rail.focusRail()) {
            return;
        }
        super.onBackPressed();
    }
}
