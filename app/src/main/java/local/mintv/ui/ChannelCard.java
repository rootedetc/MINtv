package local.mintv.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import local.mintv.R;

/**
 * Channel card for the horizontal home rails.
 *
 * <p>It carries the same identity as the channel grid card — contained logo, number, name,
 * quality, now line and live progress — at the rail's 232x196 footprint. The rail used to
 * render channels through the poster card, which centre-cropped a channel logo like artwork
 * and had no channel number. The two cards share the same 1.18 aspect ratio, so this is a
 * scaled version of the grid card rather than a second visual language.</p>
 */
public final class ChannelCard extends FrameLayout {

    public static final int WIDTH_DP = 232;
    public static final int HEIGHT_DP = 196;

    private final ImageView logo;
    private final TextView number;
    private final TextView title;
    private final TextView quality;
    private final TextView now;
    private final ProgressBar progress;

    public ChannelCard(Context context) {
        super(context);
        setId(View.generateViewId());
        setFocusable(true);
        setClickable(true);
        setBackgroundResource(R.drawable.bg_card);
        setForeground(context.getDrawable(R.drawable.bg_card_focus));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout media = new FrameLayout(context);
        media.setBackgroundResource(R.drawable.bg_card_media);
        column.addView(media, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 112)));

        logo = new ImageView(context);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageResource(R.drawable.ic_channel_placeholder);
        logo.setContentDescription(null);
        int padH = Tv.dp(context, 14);
        int padV = Tv.dp(context, 8);
        logo.setPadding(padH, padV, padH, padV);
        media.addView(logo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Tv.dp(context, 12), Tv.dp(context, 7),
                Tv.dp(context, 12), 0);
        column.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        number = Tv.numeral(context, 20f, Tv.BRAND_BRIGHT);
        number.setTypeface(Tv.condensed());
        number.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numberLp.rightMargin = Tv.dp(context, 8);
        line.addView(number, numberLp);

        title = Tv.text(context, 15f, Tv.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        quality = new TextView(context);
        quality.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
        quality.setTextColor(Tv.TEXT_2);
        quality.setTypeface(Tv.displayMedium(context));
        quality.setAllCaps(true);
        quality.setLetterSpacing(0.09f);
        quality.setIncludeFontPadding(false);
        quality.setSingleLine(true);
        LinearLayout.LayoutParams qualityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qualityLp.leftMargin = Tv.dp(context, 6);
        line.addView(quality, qualityLp);

        now = Tv.text(context, 11.5f, Tv.TEXT_2, false);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.topMargin = Tv.dp(context, 3);
        body.addView(now, nowLp);

        body.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Tv.STROKE));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 2));
        progressLp.bottomMargin = Tv.dp(context, 6);
        body.addView(progress, progressLp);
    }

    public void bind(CardItem item) {
        number.setText(item.channelNumber >= 0 ? String.valueOf(item.channelNumber) : "—");
        title.setText(item.title);
        String qualityText = qualityLabel(item.quality);
        quality.setText(qualityText);
        quality.setVisibility(qualityText.isEmpty() ? View.GONE : View.VISIBLE);
        now.setText(item.subtitle);
        now.setVisibility(item.subtitle == null || item.subtitle.isEmpty()
                ? View.GONE : View.VISIBLE);
        int percent = Math.max(0, Math.min(100, item.progressPercent));
        progress.setProgress(percent * 10);
        progress.setVisibility(percent > 0 ? View.VISIBLE : View.GONE);
        ImageLoader.load(logo, item.imageUrl);

        String description = item.channelNumber >= 0
                ? getContext().getString(R.string.channel_description,
                        item.title, item.channelNumber)
                : item.title;
        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            description += ", " + item.subtitle;
        }
        setContentDescription(description);
    }

    /** Same normalization as the channel grid card, so both surfaces spell quality alike. */
    private static String qualityLabel(String quality) {
        if (quality == null) {
            return "";
        }
        String value = quality.trim().toUpperCase(Locale.US);
        if (value.isEmpty()) {
            return "";
        }
        if (value.contains("UHD") || value.contains("4K")) {
            return "4K";
        }
        if (value.contains("FHD") || value.contains("1080") || value.contains("FULL HD")) {
            return "FULL HD";
        }
        if (value.contains("HD")) {
            return "HD";
        }
        return value;
    }
}
