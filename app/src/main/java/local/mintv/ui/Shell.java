package local.mintv.ui;

import android.app.Activity;
import android.content.Intent;

import local.mintv.ChannelListActivity;
import local.mintv.EpgActivity;
import local.mintv.LoginActivity;
import local.mintv.R;
import local.mintv.SettingsActivity;
import local.mintv.data.Session;

/** Top-level navigation between the three destinations. One activity on screen at a time. */
public final class Shell {

    public static final String EXTRA_SECTION = "section";
    public static final String SECTION_ACCOUNT = "account";

    private Shell() {
    }

    public static void open(Activity from, TvRail.Tab tab) {
        if (from instanceof EpgActivity && tab == TvRail.Tab.GUIDE) {
            return;
        }
        if (from instanceof ChannelListActivity && tab == TvRail.Tab.CHANNELS) {
            return;
        }
        if (from instanceof SettingsActivity && tab == TvRail.Tab.SETTINGS) {
            return;
        }

        Class<?> target;
        switch (tab) {
            case CHANNELS:
                target = ChannelListActivity.class;
                break;
            case SETTINGS:
                target = SettingsActivity.class;
                break;
            default:
                target = EpgActivity.class;
                break;
        }

        Intent intent = new Intent(from, target);
        if (tab == TvRail.Tab.GUIDE) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        from.startActivity(intent);
        if (!(from instanceof EpgActivity)) {
            // The guide is the root: keep it underneath so Back returns there.
            from.finish();
        }
        from.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    public static void account(Activity from) {
        Session session = Session.get(from);
        if (session.isLoggedIn()) {
            Intent intent = new Intent(from, SettingsActivity.class);
            intent.putExtra(EXTRA_SECTION, SECTION_ACCOUNT);
            from.startActivity(intent);
            if (!(from instanceof EpgActivity)) {
                from.finish();
            }
            from.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else {
            from.startActivity(new Intent(from, LoginActivity.class));
            from.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }

    public static void finish(Activity activity) {
        activity.finish();
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
