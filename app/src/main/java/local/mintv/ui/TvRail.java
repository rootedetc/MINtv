package local.mintv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
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
 * The fixed source rail: MINtv lockup, Home / Guide / Channels / Search / Settings, account and clock.
 * A narrow 96dp icon column, the way a native TV sidebar keeps its destinations out of the
 * content. Destinations and the account take focus; no labels appear on focus, so the rail
 * never changes width or reflows the page. Selection is a magenta wash and tally, focus is a
 * full-row surface plus the 2dp white rule, and choosing a destination sends focus into the
 * content panel.
 */
public final class TvRail extends FrameLayout {

    public enum Tab {
        GUIDE, HOME, CHANNELS, SEARCH, SETTINGS
    }

    public interface Listener {
        void onTabSelected(Tab tab);

        void onAccount();
    }

    /** The panel is inset inside the 96dp rail root; both widths are fixed. */
    private static final float PANEL_WIDTH_DP = 80f;
    private static final float ROW_HEIGHT_DP = 58f;
    private static final float TILE_DP = 34f;
    private static final float ICON_DP = 20f;
    private static final float ROW_RADIUS_DP = 10f;
    private static final int WASH_SELECTED = 0x1FE6007E;
    private static final int WASH_SELECTED_PRESSED = 0x33E6007E;
    private static final int WASH_TILE = 0x26E6007E;
    private static final int FOCUS_RING = 0xFFFFFFFF;
    private static final int FOCUS_PRESSED = 0xFF323B48;

    private final List<NavItem> items = new ArrayList<>();
    private final LinearLayout panel;
    private final LinearLayout.LayoutParams panelLp;
    private final LinearLayout.LayoutParams accountLp;
    private final View accountRow;
    private final FrameLayout accountAvatarFrame;
    private final View accountAvatar;
    private final View edge;
    private Listener listener;
    private Tab active = Tab.GUIDE;
    private Tab lastFocused;

    public TvRail(Context context) {
        super(context);
        setId(R.id.rail);
        setBackgroundColor(Tv.BG);
        setClipChildren(false);
        setClipToPadding(false);
        // Draw the expanded panel above the content sibling without reordering the shell's
        // horizontal children (bringToFront would move the rail after the content column).
        setTranslationZ(Tv.dpf(context, 6f));
        setOutlineProvider(null);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        // START, not CENTER_HORIZONTAL: the panel is laid out wider than the 96dp column
        // while it expands and must grow to the right, not around the rail centre.
        column.setGravity(Gravity.START);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        int pad = Tv.dp(context, 8);
        column.setPadding(0, pad, 0, Tv.dp(context, 12));
        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The destinations and the lockup share one raised, inset panel. It is a block,
        // not a full-height strip, so the account and clock below keep the bare ground.
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(Tv.box(context, Tv.BG_RAISED, 15f, Tv.STROKE, 1f));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        Tv.pad(panel, 4, 10, 4, 10);
        panelLp = new LinearLayout.LayoutParams(
                Tv.dp(context, PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.leftMargin = pad;
        panelLp.gravity = Gravity.START;
        column.addView(panel, panelLp);

        // The MINtv lockup, type only: MIN near-white, tv magenta, short magenta rule.
        // It gets the full panel width and keeps the 42dp block's breathing room, but no
        // icon plate behind it.
        LinearLayout lockup = Tv.brand(context, 28f);
        // Tv.brand adds the word with the vertical LinearLayout default MATCH_PARENT
        // width. In a wrap-content column that makes LinearLayout discount the text
        // width, size the column to the magenta rule and re-measure the word clipped to
        // it. Let the word measure itself so the whole wordmark is drawn.
        View wordmark = lockup.getChildAt(0);
        ViewGroup.LayoutParams wordLp = wordmark.getLayoutParams();
        wordLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        wordLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        wordmark.setLayoutParams(wordLp);
        lockup.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        lockup.setMinimumHeight(Tv.dp(context, 42));
        for (int i = 0; i < lockup.getChildCount(); i++) {
            lockup.getChildAt(i).setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        LinearLayout.LayoutParams lockupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lockupLp.bottomMargin = Tv.dp(context, 10);
        panel.addView(lockup, lockupLp);

        NavItem home = new NavItem(context, Tab.HOME, R.drawable.ic_home,
                context.getString(R.string.nav_home));
        NavItem guide = new NavItem(context, Tab.GUIDE, R.drawable.ic_guide,
                context.getString(R.string.nav_guide));
        NavItem channels = new NavItem(context, Tab.CHANNELS, R.drawable.ic_channels,
                context.getString(R.string.nav_channels));
        NavItem search = new NavItem(context, Tab.SEARCH, R.drawable.ic_search,
                context.getString(R.string.nav_search));
        NavItem settings = new NavItem(context, Tab.SETTINGS, R.drawable.ic_settings,
                context.getString(R.string.nav_settings));
        items.add(home);
        items.add(guide);
        items.add(channels);
        items.add(search);
        items.add(settings);
        for (NavItem item : items) {
            panel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, ROW_HEIGHT_DP)));
        }

        View spacer = new View(context);
        column.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Account: a quiet avatar with a status dot. It opens login when signed out and the
        // account section of Settings when signed in. The avatar is the whole visual; the
        // name stays in Settings and in the accessibility description.
        FrameLayout account = new FrameLayout(context);
        account.setFocusable(true);
        account.setClickable(true);
        account.setBackground(rowStates(context, false, Color.TRANSPARENT, 0, 0f));
        account.setContentDescription(context.getString(R.string.action_sign_in));
        account.setId(View.generateViewId());
        account.setOnClickListener(view -> {
            if (listener != null) {
                listener.onAccount();
            }
        });
        accountRow = account;

        accountAvatarFrame = new FrameLayout(context);
        accountAvatarFrame.setBackground(Tv.box(context, Tv.SURFACE_2, 99f, Tv.STROKE_STRONG, 1f));
        accountAvatarFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView accountIcon = Tv.icon(context, R.drawable.ic_account, Tv.TEXT_2, 20f);
        accountIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        accountAvatarFrame.addView(accountIcon,
                new FrameLayout.LayoutParams(
                        Tv.dp(context, 20), Tv.dp(context, 20), Gravity.CENTER));
        accountAvatar = new View(context);
        accountAvatar.setBackground(Tv.box(context, Tv.BRAND, 99f, 0, 0f));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 11), Tv.dp(context, 11), Gravity.BOTTOM | Gravity.END);
        accountAvatarFrame.addView(accountAvatar, dotLp);
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 38), Tv.dp(context, 38),
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        account.addView(accountAvatarFrame, avatarLp);

        accountLp = new LinearLayout.LayoutParams(
                Tv.dp(context, PANEL_WIDTH_DP), Tv.dp(context, ROW_HEIGHT_DP));
        accountLp.leftMargin = pad;
        accountLp.gravity = Gravity.START;
        column.addView(account, accountLp);

        TextView clock = Tv.numeral(context, 21f, Tv.TEXT);
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.topMargin = Tv.dp(context, 12);
        column.addView(clock, clockLp);

        TextView date = Tv.label(context, context.getString(R.string.guide_corner_day),
                Tv.TEXT_3);
        date.setGravity(Gravity.CENTER);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = Tv.dp(context, 3);
        column.addView(date, dateLp);

        // One vertical focus chain for the D-pad: the five destinations, then the account
        // foot, and back up. LEFT/RIGHT stay owned by the screen (nextFocusLeft on content
        // cards, nextFocusRight set by setContentFocusTarget).
        for (int i = 0; i < items.size(); i++) {
            NavItem item = items.get(i);
            item.setNextFocusDownId(i + 1 < items.size()
                    ? items.get(i + 1).getId() : account.getId());
            if (i > 0) {
                item.setNextFocusUpId(items.get(i - 1).getId());
            }
        }
        account.setNextFocusUpId(items.get(items.size() - 1).getId());

        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                clock.setText(Tv.clock(now));
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        "d. MMM", java.util.Locale.getDefault());
                date.setText(f.format(new java.util.Date(now)));
                postDelayed(this, 20_000L);
            }
        });

        edge = new View(context);
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
        accountAvatar.getBackground().setTint(signedIn ? Tv.BRAND : Tv.SURFACE_3);
        accountAvatar.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        accountRow.setContentDescription(label == null || label.isEmpty()
                ? getContext().getString(R.string.action_sign_in) : label);
    }

    /**
     * RIGHT from the rail enters the screen's primary control. UP/DOWN are deliberately
     * chained inside the rail so the five destinations keep their natural vertical order
     * and the account foot stays reachable.
     */
    public void setContentFocusTarget(View target) {
        if (target == null) {
            return;
        }
        for (NavItem item : items) {
            item.setNextFocusRightId(target.getId());
        }
        accountRow.setNextFocusRightId(target.getId());
    }

    /** Land on the destination this screen represents. */
    public boolean focusActive() {
        return item(active).requestFocus();
    }

    /**
     * The focus home: return to the destination last visited in this screen, or to the
     * active destination the first time. Returns false when no item could take focus, so a
     * caller can fall through to normal Back handling.
     */
    public boolean focusRail() {
        Tab target = lastFocused != null ? lastFocused : active;
        return item(target).requestFocus();
    }

    /** True when focus is on the rail itself or on one of its destinations/account foot. */
    public boolean holdsFocus() {
        return findFocus() != null;
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

    private static int itemId(Tab tab) {
        switch (tab) {
            case HOME:
                return R.id.rail_home;
            case CHANNELS:
                return R.id.rail_channels;
            case SEARCH:
                return R.id.rail_search;
            case SETTINGS:
                return R.id.rail_settings;
            case GUIDE:
            default:
                return R.id.rail_guide;
        }
    }

    private static StateListDrawable rowStates(Context context, boolean selectable,
                                               int idleFill, int idleStroke, float idleStrokeDp) {
        StateListDrawable drawable = new StateListDrawable();
        // Pressing a focused row brightens it while the white rule stays put. Focus is never
        // magenta and never a scale.
        drawable.addState(
                new int[]{android.R.attr.state_focused, android.R.attr.state_pressed},
                Tv.box(context, FOCUS_PRESSED, ROW_RADIUS_DP, FOCUS_RING, 2f));
        // A focused selected row keeps its magenta identity and adds the white rule;
        // selection must not disappear the moment the D-pad lands on it.
        drawable.addState(
                new int[]{android.R.attr.state_focused, android.R.attr.state_selected},
                Tv.box(context, WASH_SELECTED_PRESSED, ROW_RADIUS_DP, FOCUS_RING, 2f));
        drawable.addState(new int[]{android.R.attr.state_focused},
                Tv.box(context, Tv.SURFACE_FOCUS, ROW_RADIUS_DP, FOCUS_RING, 2f));
        if (selectable) {
            drawable.addState(
                    new int[]{android.R.attr.state_pressed, android.R.attr.state_selected},
                    Tv.box(context, WASH_SELECTED_PRESSED, ROW_RADIUS_DP, 0, 0f));
            drawable.addState(new int[]{android.R.attr.state_selected},
                    Tv.box(context, WASH_SELECTED, ROW_RADIUS_DP, 0, 0f));
        }
        drawable.addState(new int[]{android.R.attr.state_pressed},
                Tv.box(context, Tv.SURFACE_2, ROW_RADIUS_DP, 0, 0f));
        drawable.addState(new int[]{},
                Tv.box(context, idleFill, ROW_RADIUS_DP, idleStroke, idleStrokeDp));
        return drawable;
    }

    private final class NavItem extends FrameLayout {

        final Tab tab;
        private final FrameLayout tile;
        private final ImageView icon;
        private final View tally;

        NavItem(Context context, Tab tab, int iconRes, String text) {
            super(context);
            this.tab = tab;
            setId(itemId(tab));
            setFocusable(true);
            setClickable(true);
            setClipChildren(true);
            setBackground(rowStates(context, true, Color.TRANSPARENT, 0, 0f));
            // The icon row is one accessibility target; the destination name is spoken from
            // here because the native rail does not paint labels.
            setContentDescription(text);

            // A rounded 34dp tile holds the glyph, centred in the fixed 80dp panel.
            tile = new FrameLayout(context);
            tile.setBackground(Tv.box(context, Tv.SURFACE_2, 10f, 0, 0f));
            tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams tileLp = new FrameLayout.LayoutParams(
                    Tv.dp(context, TILE_DP), Tv.dp(context, TILE_DP),
                    Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            addView(tile, tileLp);

            icon = Tv.icon(context, iconRes, Tv.TEXT_3, ICON_DP);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icon, new FrameLayout.LayoutParams(
                    Tv.dp(context, ICON_DP), Tv.dp(context, ICON_DP), Gravity.CENTER));

            tally = new View(context);
            tally.setBackground(Tv.box(context, Tv.BRAND, 99f, 0, 0f));
            tally.setVisibility(View.INVISIBLE);
            tally.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams tallyLp = new FrameLayout.LayoutParams(
                    Math.max(2, Tv.dp(context, 3)), Tv.dp(context, 16),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            tallyLp.leftMargin = Tv.dp(context, 4);
            addView(tally, tallyLp);

            setOnClickListener(view -> {
                if (listener != null) {
                    listener.onTabSelected(tab);
                }
            });
            setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    lastFocused = tab;
                }
                applyColors(hasFocus);
            });
        }

        void setActive(boolean selected) {
            setSelected(selected);
            tally.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            applyColors(isFocused());
        }

        private void applyColors(boolean focused) {
            boolean selected = isSelected();
            int glyph = selected ? Tv.BRAND_BRIGHT : focused ? Tv.TEXT : Tv.TEXT_3;
            icon.setColorFilter(glyph);
            tile.setBackground(Tv.box(getContext(),
                    selected ? WASH_TILE : focused ? Tv.SURFACE_3 : Tv.SURFACE_2,
                    10f, 0, 0f));
        }
    }
}
