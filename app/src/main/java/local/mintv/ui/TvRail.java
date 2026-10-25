package local.mintv.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.R;

/**
 * The fixed source rail: MINtv lockup, Guide / Channels / Settings, account and clock.
 * A narrow 96dp column so the guide keeps the width, the way a monitor wall keeps its
 * sources on the left. Only the three destinations and the account take focus.
 */
public final class TvRail extends FrameLayout {

    public enum Tab {
        GUIDE, CHANNELS, SETTINGS
    }

    public interface Listener {
        void onTabSelected(Tab tab);

        void onAccount();
    }

    private final List<NavItem> items = new ArrayList<>();
    private final TextView accountLabel;
    private final View accountAvatar;
    private Listener listener;
    private Tab active = Tab.GUIDE;

    public TvRail(Context context) {
        super(context);
        setBackgroundColor(Tv.BG);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0, Tv.dp(context, 22), 0, Tv.dp(context, 14));
        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The app icon, square and quiet, instead of a letter mark.
        FrameLayout iconFrame = new FrameLayout(context);
        iconFrame.setBackground(Tv.box(context, Tv.SURFACE, 7f, Tv.STROKE_STRONG, 1f));
        iconFrame.setClipToOutline(true);
        ImageView appIcon = new ImageView(context);
        appIcon.setImageResource(R.drawable.ic_launcher);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appIcon.setContentDescription(context.getString(R.string.app_name));
        iconFrame.addView(appIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 42), Tv.dp(context, 42));
        iconLp.bottomMargin = Tv.dp(context, 26);
        column.addView(iconFrame, iconLp);

        NavItem guide = new NavItem(context, Tab.GUIDE, R.drawable.ic_guide,
                context.getString(R.string.nav_guide));
        NavItem channels = new NavItem(context, Tab.CHANNELS, R.drawable.ic_channels,
                context.getString(R.string.nav_channels));
        NavItem settings = new NavItem(context, Tab.SETTINGS, R.drawable.ic_settings,
                context.getString(R.string.nav_settings));
        items.add(guide);
        items.add(channels);
        items.add(settings);
        for (NavItem item : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Tv.dp(context, 76), Tv.dp(context, 76));
            lp.bottomMargin = Tv.dp(context, 4);
            column.addView(item, lp);
        }

        View spacer = new View(context);
        column.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Account: a quiet avatar with a one-word status. Signed out it opens login;
        // signed in it opens the account section of settings.
        LinearLayout account = new LinearLayout(context);
        account.setOrientation(LinearLayout.VERTICAL);
        account.setGravity(Gravity.CENTER);
        account.setFocusable(true);
        account.setClickable(true);
        account.setBackgroundResource(R.drawable.bg_nav_item);
        Tv.pad(account, 4, 8, 4, 8);
        Tv.focusScale(account, 1.04f);
        account.setOnClickListener(view -> {
            if (listener != null) {
                listener.onAccount();
            }
        });

        FrameLayout avatarFrame = new FrameLayout(context);
        avatarFrame.setBackground(Tv.box(context, Tv.SURFACE_2, 99f, Tv.STROKE_STRONG, 1f));
        accountAvatar = new View(context);
        accountAvatar.setBackground(Tv.box(context, Tv.BRAND, 99f, 0, 0f));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 11), Tv.dp(context, 11), Gravity.BOTTOM | Gravity.END);
        avatarFrame.addView(Tv.icon(context, R.drawable.ic_account, Tv.TEXT_2, 20f),
                new FrameLayout.LayoutParams(
                        Tv.dp(context, 20), Tv.dp(context, 20), Gravity.CENTER));
        avatarFrame.addView(accountAvatar, dotLp);
        account.addView(avatarFrame, new LinearLayout.LayoutParams(
                Tv.dp(context, 38), Tv.dp(context, 38)));

        accountLabel = Tv.label(context, "", Tv.TEXT_3);
        accountLabel.setGravity(Gravity.CENTER);
        accountLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        accountLabel.setSingleLine(true);
        LinearLayout.LayoutParams accountLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        accountLp.topMargin = Tv.dp(context, 6);
        account.addView(accountLabel, accountLp);
        column.addView(account, new LinearLayout.LayoutParams(
                Tv.dp(context, 80), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clock = Tv.numeral(context, 21f, Tv.TEXT);
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.topMargin = Tv.dp(context, 14);
        column.addView(clock, clockLp);

        TextView date = Tv.label(context, "DANAS", Tv.TEXT_3);
        date.setGravity(Gravity.CENTER);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = Tv.dp(context, 3);
        column.addView(date, dateLp);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                clock.setText(Tv.clock(now));
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        "d. MMM", new java.util.Locale("hr", "HR"));
                date.setText(f.format(new java.util.Date(now)));
                postDelayed(this, 20_000L);
            }
        });

        View edge = new View(context);
        edge.setBackgroundColor(Tv.STROKE_SOFT);
        addView(edge, new FrameLayout.LayoutParams(
                Math.max(1, Tv.dp(context, 1)), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END));

        setActive(Tab.GUIDE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setActive(Tab tab) {
        if (tab == null) {
            return;
        }
        active = tab;
        for (NavItem item : items) {
            item.setActive(item.tab == tab);
        }
    }

    public Tab getActive() {
        return active;
    }

    /** Account foot updates on resume, without rebuilding the rail. */
    public void setAccount(String label, boolean signedIn) {
        accountLabel.setText(label);
        accountLabel.setTextColor(signedIn ? Tv.BRAND_BRIGHT : Tv.TEXT_3);
        accountAvatar.getBackground().setTint(signedIn ? Tv.BRAND : Tv.SURFACE_3);
        accountAvatar.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    /** DOWN/RIGHT from the rail always lands on the screen's primary control. */
    public void setContentFocusTarget(View target) {
        if (target == null) {
            return;
        }
        for (NavItem item : items) {
            item.setNextFocusRightId(target.getId());
            item.setNextFocusDownId(target.getId());
        }
    }

    /** The focusable item for a destination, used to land focus on the rail. */
    public View item(Tab tab) {
        for (NavItem item : items) {
            if (item.tab == tab) {
                return item;
            }
        }
        return items.get(0);
    }

    private final class NavItem extends LinearLayout {

        final Tab tab;
        private final ImageView icon;
        private final TextView label;
        private final View tally;

        NavItem(Context context, Tab tab, int iconRes, String text) {
            super(context);
            this.tab = tab;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setFocusable(true);
            setClickable(true);
            setBackgroundResource(R.drawable.bg_nav_item);
            Tv.focusScale(this, 1.04f);

            icon = Tv.icon(context, iconRes, Tv.TEXT_3, 24f);
            addView(icon);

            label = Tv.label(context, text, Tv.TEXT_3);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = Tv.dp(context, 6);
            addView(label, labelLp);

            tally = new View(context);
            tally.setBackgroundColor(Tv.BRAND);
            LinearLayout.LayoutParams tallyLp = new LinearLayout.LayoutParams(
                    Tv.dp(context, 22), Math.max(2, Tv.dp(context, 2)));
            tallyLp.topMargin = Tv.dp(context, 5);
            addView(tally, tallyLp);

            setOnClickListener(view -> {
                if (listener != null) {
                    listener.onTabSelected(tab);
                }
            });
            setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().cancel();
                v.animate()
                        .scaleX(hasFocus ? 1.04f : 1f)
                        .scaleY(hasFocus ? 1.04f : 1f)
                        .setDuration(hasFocus ? 140L : 110L)
                        .start();
                applyColors(hasFocus);
            });
        }

        void setActive(boolean selected) {
            setSelected(selected);
            tally.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            applyColors(isFocused());
        }

        private void applyColors(boolean focused) {
            int color = focused ? Tv.TEXT : isSelected() ? Tv.BRAND_BRIGHT : Tv.TEXT_3;
            icon.setColorFilter(color);
            label.setTextColor(color);
        }
    }
}
