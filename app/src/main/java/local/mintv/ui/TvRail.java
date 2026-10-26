package local.mintv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.R;

/**
 * The fixed source rail: MINtv lockup, Home / Guide / Channels / Search / Settings, account and clock.
 * A narrow 96dp column so the guide keeps the width, the way a monitor wall keeps its
 * sources on the left. The five destinations and the account take focus.
 *
 * <p>At rest the destinations are icon-only tiles on a rounded raised panel inset 8dp inside
 * the rail; there is no label under the icon. When the rail holds focus the panel widens over
 * the content to 208dp and each destination becomes one row: icon tile, then a readable
 * Barlow Condensed label. The rail root itself never changes width, so the page content is
 * never reflowed; the expanded panel is drawn above the content with a small Z lift.</p>
 */
public final class TvRail extends FrameLayout {

    public enum Tab {
        GUIDE, HOME, CHANNELS, SEARCH, SETTINGS
    }

    public interface Listener {
        void onTabSelected(Tab tab);

        void onAccount();
    }

    /** Panel widths; the rail root stays at 96dp and the expanded panel ends at 208dp. */
    private static final float PANEL_COLLAPSED_DP = 80f;
    /** 160dp rail at full expansion: enough for a 15sp label, while Settings still fits. */
    private static final float PANEL_EXPANDED_DP = 128f;
    private static final float ROW_HEIGHT_DP = 58f;
    private static final float TILE_DP = 34f;
    private static final float ICON_DP = 20f;
    /**
     * The collapsed tile is centred in the 72dp panel content: (72 - 34) / 2 = 19dp, while
     * the expanded tile sits at 12dp. The 7dp translation keeps the icon on the rail centre.
     */
    private static final float TILE_SHIFT_DP = 7f;
    /** Same idea for the avatar: centred in the collapsed foot, left-aligned when expanded. */
    private static final float AVATAR_SHIFT_DP = 9f;
    private static final float LABEL_SHIFT_DP = 3f;
    private static final long EXPAND_MS = 140L;
    private static final long COLLAPSE_MS = 110L;
    private static final float ROW_RADIUS_DP = 10f;
    private static final int WASH_SELECTED = 0x1FE6007E;
    private static final int WASH_SELECTED_PRESSED = 0x33E6007E;
    private static final int WASH_TILE = 0x26E6007E;
    private static final int FOCUS_RING = 0xFFFFFFFF;
    private static final int FOCUS_PRESSED = 0xFF323B48;

    private final DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
    private final List<NavItem> items = new ArrayList<>();
    private final LinearLayout panel;
    private final LinearLayout.LayoutParams panelLp;
    private final LinearLayout.LayoutParams accountLp;
    private final View accountRow;
    private final FrameLayout accountAvatarFrame;
    private final TextView accountLabel;
    private final View accountAvatar;
    private final View edge;
    private final StateListDrawable accountBgCollapsed;
    private final StateListDrawable accountBgExpanded;
    private Listener listener;
    private Tab active = Tab.GUIDE;
    private Tab lastFocused;
    private boolean railFocused;
    private boolean accountExpanded;
    private float expansion;
    private ValueAnimator expandAnimator;

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
                Tv.dp(context, PANEL_COLLAPSED_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
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
        // account section of Settings when signed in. The name only appears while the rail
        // is expanded; at rest the avatar keeps the bare ground under the panel.
        FrameLayout account = new FrameLayout(context);
        account.setFocusable(true);
        account.setClickable(true);
        accountBgCollapsed = rowStates(context, false, Color.TRANSPARENT, 0, 0f);
        accountBgExpanded = rowStates(context, false, Tv.BG_RAISED, Tv.STROKE, 1f);
        account.setBackground(accountBgCollapsed);
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
                Gravity.START | Gravity.CENTER_VERTICAL);
        avatarLp.leftMargin = Tv.dp(context, 12);
        account.addView(accountAvatarFrame, avatarLp);

        accountLabel = Tv.displayText(context, 15f, Tv.TEXT_2);
        accountLabel.setSingleLine(true);
        accountLabel.setEllipsize(TextUtils.TruncateAt.END);
        accountLabel.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        accountLabel.setLetterSpacing(0.02f);
        accountLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Fixed width: the row clips it while collapsing instead of reflowing the name.
        FrameLayout.LayoutParams accountLabelLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 60), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        accountLabelLp.leftMargin = Tv.dp(context, 56);
        account.addView(accountLabel, accountLabelLp);

        accountLp = new LinearLayout.LayoutParams(
                Tv.dp(context, PANEL_COLLAPSED_DP), Tv.dp(context, ROW_HEIGHT_DP));
        accountLp.leftMargin = pad;
        accountLp.gravity = Gravity.START;
        column.addView(account, accountLp);

        account.setOnFocusChangeListener((v, hasFocus) -> {
            applyAccountColors(hasFocus);
            if (hasFocus) {
                setRailFocused(true);
            } else {
                post(TvRail.this::syncRailFocus);
            }
        });

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
        applyRailFocus(false, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        super.onDetachedFromWindow();
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
        accountAvatar.getBackground().setTint(signedIn ? Tv.BRAND : Tv.SURFACE_3);
        accountAvatar.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        accountRow.setContentDescription(label == null || label.isEmpty()
                ? getContext().getString(R.string.action_sign_in) : label);
        applyAccountColors(accountRow.isFocused());
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

    /**
     * Focus may move between two rail items, in which case the first item's blur fires
     * before the next item's focus. Re-read the rail subtree after layout instead of
     * collapsing on every individual blur.
     */
    private void syncRailFocus() {
        setRailFocused(findFocus() != null);
    }

    private void setRailFocused(boolean focused) {
        if (railFocused == focused) {
            return;
        }
        applyRailFocus(focused, true);
    }

    private void applyRailFocus(boolean focused, boolean animate) {
        railFocused = focused;
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        if (!animate) {
            setExpansion(focused ? 1f : 0f);
            return;
        }
        expandAnimator = ValueAnimator.ofFloat(expansion, focused ? 1f : 0f);
        expandAnimator.setDuration(focused ? EXPAND_MS : COLLAPSE_MS);
        expandAnimator.setInterpolator(ease);
        expandAnimator.addUpdateListener(
                animator -> setExpansion(((Float) animator.getAnimatedValue()).floatValue()));
        expandAnimator.start();
    }

    /**
     * One fraction drives the whole rail: the panel/account width, the label reveal, the
     * tile centring and the hairline. The rail root keeps its 96dp layout width, so nothing
     * inside the content column is re-measured by the expansion.
     */
    private void setExpansion(float fraction) {
        expansion = Math.max(0f, Math.min(1f, fraction));
        // The rail is the first child of the screen's horizontal shell. Widening the rail
        // itself (instead of overdrawing the content) guarantees the expanded panel is
        // never hidden behind the content column, and keeps the D-pad geometry exact.
        ViewGroup.LayoutParams rootLp = getLayoutParams();
        if (rootLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rootLp;
            int rootWidth = Math.round(Tv.dpf(getContext(),
                    PANEL_COLLAPSED_DP + 16f
                            + (PANEL_EXPANDED_DP - PANEL_COLLAPSED_DP) * expansion));
            if (lp.width != rootWidth) {
                lp.width = rootWidth;
                setLayoutParams(lp);
            }
        }
        int width = Math.round(Tv.dpf(getContext(),
                PANEL_COLLAPSED_DP + (PANEL_EXPANDED_DP - PANEL_COLLAPSED_DP) * expansion));
        if (panelLp.width != width) {
            panelLp.width = width;
            panel.setLayoutParams(panelLp);
        }
        if (accountLp.width != width) {
            accountLp.width = width;
            accountRow.setLayoutParams(accountLp);
        }
        // The foot keeps the bare ground at rest. Only while expanded does it grow a quiet
        // plate behind the account name, so the name never floats over programme artwork.
        boolean expanded = expansion > 0.001f;
        if (expanded != accountExpanded) {
            accountExpanded = expanded;
            accountRow.setBackground(expanded ? accountBgExpanded : accountBgCollapsed);
        }
        float rest = 1f - expansion;
        for (NavItem item : items) {
            item.applyExpansion(expansion, rest);
        }
        accountLabel.setAlpha(expansion);
        accountLabel.setVisibility(expansion > 0.01f ? View.VISIBLE : View.INVISIBLE);
        accountAvatarFrame.setTranslationX(Tv.dpf(getContext(), AVATAR_SHIFT_DP) * rest);
        edge.setAlpha(rest);
    }

    private void applyAccountColors(boolean focused) {
        accountLabel.setTextColor(focused ? Tv.TEXT : Tv.TEXT_2);
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
        private final TextView label;
        private final View tally;

        NavItem(Context context, Tab tab, int iconRes, String text) {
            super(context);
            this.tab = tab;
            setId(itemId(tab));
            setFocusable(true);
            setClickable(true);
            // Keep clipping on: the fixed-width label is revealed by the growing row edge
            // instead of being re-flowed/ellipsized on every animation frame.
            setClipChildren(true);
            setBackground(rowStates(context, true, Color.TRANSPARENT, 0, 0f));
            // The row is one accessibility target: the visual label is not read twice.
            setContentDescription(text);

            // A rounded tile holds the glyph. It stays 34dp at both widths and slides to the
            // rail centre when the panel collapses.
            tile = new FrameLayout(context);
            tile.setBackground(Tv.box(context, Tv.SURFACE_2, 10f, 0, 0f));
            tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams tileLp = new FrameLayout.LayoutParams(
                    Tv.dp(context, TILE_DP), Tv.dp(context, TILE_DP),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            tileLp.leftMargin = Tv.dp(context, 12);
            addView(tile, tileLp);

            icon = Tv.icon(context, iconRes, Tv.TEXT_3, ICON_DP);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icon, new FrameLayout.LayoutParams(
                    Tv.dp(context, ICON_DP), Tv.dp(context, ICON_DP), Gravity.CENTER));

            label = Tv.displayText(context, 15f, Tv.TEXT_2);
            label.setText(text);
            label.setAllCaps(true);
            label.setLetterSpacing(0.06f);
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setVisibility(View.INVISIBLE);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Fixed width for the expanded row (136dp of content - 54dp - 2dp). The row
            // clips the tail while it is narrower, so collapsing never squashes the text.
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    Tv.dp(context, 64), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL);
            labelLp.leftMargin = Tv.dp(context, 54);
            addView(label, labelLp);

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
                if (hasFocus) {
                    setRailFocused(true);
                } else {
                    post(TvRail.this::syncRailFocus);
                }
            });
        }

        void setActive(boolean selected) {
            setSelected(selected);
            tally.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            applyColors(isFocused());
        }

        /** Label reveal, tile centring and the label slide are driven by the panel fraction. */
        void applyExpansion(float fraction, float rest) {
            label.setAlpha(fraction);
            label.setVisibility(fraction > 0.01f ? View.VISIBLE : View.INVISIBLE);
            label.setTranslationX(Tv.dpf(getContext(), LABEL_SHIFT_DP) * rest);
            tile.setTranslationX(Tv.dpf(getContext(), TILE_SHIFT_DP) * rest);
        }

        private void applyColors(boolean focused) {
            boolean selected = isSelected();
            int glyph = selected ? Tv.BRAND_BRIGHT : focused ? Tv.TEXT : Tv.TEXT_3;
            icon.setColorFilter(glyph);
            label.setTextColor(selected || focused ? Tv.TEXT : Tv.TEXT_2);
            tile.setBackground(Tv.box(getContext(),
                    selected ? WASH_TILE : focused ? Tv.SURFACE_3 : Tv.SURFACE_2,
                    10f, 0, 0f));
        }
    }
}
