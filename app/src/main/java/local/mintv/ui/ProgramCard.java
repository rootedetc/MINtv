package local.mintv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import local.mintv.R;

/**
 * The shared poster card. Broadcast Rail language: flat surface, 1px hairline, white 2px focus
 * rule, condensed title, magenta only for live/progress and the selected state.
 */
public final class ProgramCard extends FrameLayout {

    public static final int WIDTH_DP = 232;
    public static final int HEIGHT_DP = 196;

    private final ImageView poster;
    private final TextView badge;
    private final TextView title;
    private final TextView meta;
    private final ProgressBar progress;
    private final ImageView check;
    private final View scrim;

    private CardItem item;
    private boolean selected;

    public ProgramCard(Context context) {
        super(context);
        setId(View.generateViewId());
        setFocusable(true);
        setClickable(true);
        setBackground(Tv.states(context, Tv.SURFACE, Tv.SURFACE_FOCUS, Tv.SURFACE_3,
                8f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f));
        setForeground(Tv.states(context, 0x00000000, 0x00000000, 0x00000000,
                8f, 0, 0f, 0xFFFFFFFF, 2f));
        setClipToOutline(true);
        setElevation(0f);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout media = new FrameLayout(context);
        media.setBackgroundColor(Tv.SURFACE_2);
        column.addView(media, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 112)));

        poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setImageResource(R.drawable.ic_channel_placeholder);
        media.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim = new View(context);
        scrim.setBackgroundResource(R.drawable.bg_scrim_bottom);
        media.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 54), Gravity.BOTTOM));

        badge = Tv.label(context, "", 0xFFFFFFFF);
        badge.setBackground(Tv.box(context, 0xCC07080B, 4f, 0x33FFFFFF, 1f));
        badge.setPadding(Tv.dp(context, 6), Tv.dp(context, 3),
                Tv.dp(context, 6), Tv.dp(context, 3));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        badgeLp.leftMargin = Tv.dp(context, 8);
        badgeLp.topMargin = Tv.dp(context, 8);
        media.addView(badge, badgeLp);

        check = Tv.icon(context, R.drawable.ic_check, 0xFFFFFFFF, 15f);
        check.setBackground(Tv.box(context, Tv.BRAND, 99f, 0, 0f));
        check.setPadding(Tv.dp(context, 4), Tv.dp(context, 4),
                Tv.dp(context, 4), Tv.dp(context, 4));
        check.setVisibility(View.GONE);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(
                Tv.dp(context, 24), Tv.dp(context, 24), Gravity.TOP | Gravity.END);
        checkLp.rightMargin = Tv.dp(context, 8);
        checkLp.topMargin = Tv.dp(context, 8);
        media.addView(check, checkLp);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        Tv.pad(body, 11, 8, 11, 8);
        column.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        title = Tv.displayText(context, 15f, Tv.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        meta = Tv.text(context, 11f, Tv.TEXT_3, false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Tv.dp(context, 3);
        body.addView(meta, metaLp);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Tv.SURFACE_3));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 3));
        progressLp.topMargin = Tv.dp(context, 7);
        body.addView(progress, progressLp);

        Tv.focusScale(this, 1.025f);
    }

    public void bind(CardItem newItem) {
        this.item = newItem;
        title.setText(newItem.title);
        meta.setText(newItem.remainingMinutes > 0 && newItem.progressPercent > 0
                ? getContext().getString(R.string.cw_remaining, newItem.remainingMinutes)
                : newItem.subtitle);
        meta.setVisibility(meta.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String badgeText = newItem.badge == null ? "" : newItem.badge.trim();
        badge.setText(badgeText);
        badge.setVisibility(badgeText.isEmpty() ? View.GONE : View.VISIBLE);
        boolean isLive = "TV".equalsIgnoreCase(badgeText)
                || "LIVE".equalsIgnoreCase(badgeText)
                || getContext().getString(R.string.badge_catchup).equalsIgnoreCase(badgeText);
        badge.setTextColor(isLive ? Tv.BRAND_BRIGHT : Tv.TEXT_2);
        scrim.setVisibility(newItem.channel ? View.GONE : View.VISIBLE);
        progress.setVisibility(newItem.progressPercent > 0 ? View.VISIBLE : View.GONE);
        progress.setProgress(newItem.progressPercent);
        ImageLoader.load(poster, newItem.imageUrl);
        String description = newItem.title;
        if (!newItem.subtitle.isEmpty()) {
            description += ", " + newItem.subtitle;
        }
        setContentDescription(description);
        poster.setContentDescription(newItem.title);
        setSelected(selected);
    }

    public CardItem item() {
        return item;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        selected = value;
        check.setVisibility(value ? View.VISIBLE : View.GONE);
        // Selected is not focus; use a magenta hairline rather than changing the fill.
        setBackground(Tv.states(getContext(),
                value ? 0xFF21111A : Tv.SURFACE,
                Tv.SURFACE_FOCUS,
                Tv.SURFACE_3,
                8f,
                value ? 0xFF8A1C55 : Tv.STROKE,
                value ? 2f : 1f,
                0xFFFFFFFF,
                2f));
    }

    public static LinearLayout.LayoutParams railParams(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Tv.dp(context, WIDTH_DP), Tv.dp(context, HEIGHT_DP));
        lp.rightMargin = Tv.dp(context, 12);
        return lp;
    }
}
