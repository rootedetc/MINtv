package local.mintv.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import local.mintv.R;

/**
 * Shared chrome for the feature surfaces: the permanent source rail plus a content column with
 * the Broadcast Rail header. Existing screens keep their bespoke headers; the new home, search,
 * watchlist, continue-watching, details and subpage screens use this to stay identical.
 */
public final class ShellHost {

    public final FrameLayout root;
    public final TvRail rail;
    public final LinearLayout content;
    private final Activity activity;

    public ShellHost(Activity activity, TvRail.Tab active) {
        this.activity = activity;
        root = new FrameLayout(activity);
        root.setBackgroundColor(Tv.BG);

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new TvRail(activity);
        rail.setActive(active);
        local.mintv.data.Session session = local.mintv.data.Session.get(activity);
        if (session.isLoggedIn()) {
            String name = session.displayName();
            if (name == null || name.isEmpty()) {
                name = session.username();
            }
            rail.setAccount(name == null || name.isEmpty()
                    ? activity.getString(R.string.nav_settings) : name, true);
        } else {
            rail.setAccount(activity.getString(R.string.action_sign_in), false);
        }
        rail.setListener(new TvRail.Listener() {
            @Override
            public void onTabSelected(TvRail.Tab tab) {
                if (tab == active) {
                    rail.item(tab).requestFocus();
                    return;
                }
                Shell.open(activity, tab);
            }

            @Override
            public void onAccount() {
                Shell.account(activity);
            }
        });
        shell.addView(rail, new LinearLayout.LayoutParams(
                Tv.dp(activity, 96), ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        shell.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    public LinearLayout header(String title, String subtitle) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(header, 32, 0, 32, 0);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = Tv.displayText(activity, 32f, Tv.TEXT);
        titleView.setText(title);
        titleView.setLetterSpacing(0.01f);
        titles.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = Tv.text(activity, 12.5f, Tv.TEXT_2, false);
            subtitleView.setText(subtitle);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Tv.dp(activity, 4);
            titles.addView(subtitleView, lp);
        }
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    public void addHeader(String title, String subtitle, int heightDp) {
        content.addView(header(title, subtitle), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(activity, heightDp)));
    }

    public void addBody(View body) {
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public TextView action(String label) {
        TextView view = Tv.ghost(activity, label);
        return view;
    }

    public static LinearLayout actionRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public Activity activity() {
        return activity;
    }

    /** Refresh the rail's account foot after returning from Login or Settings. */
    public void updateAccount() {
        local.mintv.data.Session session = local.mintv.data.Session.get(activity);
        if (session.isLoggedIn()) {
            String name = session.displayName();
            if (name == null || name.isEmpty()) {
                name = session.username();
            }
            rail.setAccount(name == null || name.isEmpty()
                    ? activity.getString(R.string.nav_settings) : name, true);
        } else {
            rail.setAccount(activity.getString(R.string.action_sign_in), false);
        }
    }
}
