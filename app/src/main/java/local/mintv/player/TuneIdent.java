package local.mintv.player;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.ui.Tv;

/**
 * The station ident shown while a stream acquires its first frame. It is the loading
 * state, never an added delay: the player publishes STATE_READY and the ident leaves at
 * once. Switching channels inside the player does not replay it.
 */
public final class TuneIdent extends FrameLayout {

    private final TextView ghostNumber;
    private final TextView channelName;
    private final TextView programme;
    private final TextView status;
    private final TextView kicker;
    private final ProgressBar acquisition;

    public TuneIdent(Context context) {
        super(context);
        setClickable(true);
        setFocusable(false);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF140810, 0xFF07080B, 0xFF07080B});
        setBackground(bg);
        setAlpha(0f);
        setVisibility(View.GONE);

        ghostNumber = new TextView(context);
        ghostNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, 230f);
        ghostNumber.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        ghostNumber.setTextColor(0x12FFFFFF);
        ghostNumber.setIncludeFontPadding(false);
        ghostNumber.setGravity(Gravity.END);
        LayoutParams ghostLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        ghostLp.rightMargin = Tv.dp(context, 6);
        ghostLp.bottomMargin = Tv.dp(context, -36);
        addView(ghostNumber, ghostLp);

        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams blockLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        blockLp.leftMargin = Tv.dp(context, 88);
        addView(block, blockLp);

        View tally = new View(context);
        tally.setBackgroundColor(Tv.BRAND);
        LinearLayout.LayoutParams tallyLp = new LinearLayout.LayoutParams(
                Math.max(3, Tv.dp(context, 4)), Tv.dp(context, 176));
        tallyLp.rightMargin = Tv.dp(context, 30);
        block.addView(tally, tallyLp);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        block.addView(content);

        content.addView(Tv.brand(context, 30f));

        status = Tv.label(context, getContext().getString(R.string.player_live), 0xFFFFFFFF);
        status.setPadding(Tv.dp(context, 9), Tv.dp(context, 4),
                Tv.dp(context, 9), Tv.dp(context, 4));
        status.setBackground(Tv.box(context, Tv.BRAND, 4f, 0, 0f));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Tv.dp(context, 30);
        content.addView(status, statusLp);

        channelName = Tv.text(context, 48f, Tv.TEXT, true);
        channelName.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        channelName.setSingleLine(true);
        channelName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Tv.dp(context, 12);
        content.addView(channelName, nameLp);

        programme = Tv.text(context, 19f, Tv.TEXT_2, false);
        programme.setSingleLine(true);
        programme.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progLp.topMargin = Tv.dp(context, 8);
        content.addView(programme, progLp);

        kicker = Tv.label(context, getContext().getString(R.string.player_tune_connecting),
                Tv.TEXT_3);
        LinearLayout.LayoutParams kickerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kickerLp.topMargin = Tv.dp(context, 26);
        content.addView(kicker, kickerLp);

        acquisition = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        acquisition.setIndeterminate(true);
        acquisition.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(Tv.BRAND));
        acquisition.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Tv.STROKE));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 360), Tv.dp(context, 2));
        barLp.topMargin = Tv.dp(context, 10);
        content.addView(acquisition, barLp);

        TextView footer = Tv.text(context, 11f, Tv.TEXT_3, false);
        footer.setText(getContext().getString(R.string.player_tune_ready));
        LayoutParams footerLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        footerLp.leftMargin = Tv.dp(context, 122);
        footerLp.bottomMargin = Tv.dp(context, 40);
        addView(footer, footerLp);
    }

    public void showChannel(Channel channel, String programmeTitle, boolean live) {
        if (channel != null) {
            ghostNumber.setText(String.format(Locale.US, "%02d", channel.getNumber()));
            channelName.setText(channel.getTitle(getContext()));
        } else {
            ghostNumber.setText("");
            channelName.setText(R.string.player_demo_title);
        }
        programme.setText(programmeTitle == null ? "" : programmeTitle);
        status.setText(live ? R.string.player_live : R.string.player_time_shift);
        status.setBackground(Tv.box(getContext(), live ? Tv.BRAND : 0xFF8A5A18,
                4f, 0, 0f));
        kicker.setText(R.string.player_tune_connecting);
        show();
    }

    public void showDemo(String title) {
        ghostNumber.setText("");
        channelName.setText(title);
        programme.setText(R.string.player_demo_subtitle);
        status.setText(R.string.player_tune_ready);
        status.setBackground(Tv.box(getContext(), Tv.SURFACE_2, 4f,
                Tv.STROKE_STRONG, 1f));
        kicker.setText(R.string.player_tune_connecting);
        show();
    }

    private void show() {
        animate().cancel();
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(130L).start();
    }

    public void hide() {
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        animate().cancel();
        animate().alpha(0f).setDuration(180L).withEndAction(() -> {
            if (getAlpha() == 0f) {
                setVisibility(View.GONE);
            }
        }).start();
    }
}
