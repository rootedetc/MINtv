package local.mintv.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import local.mintv.R;

/**
 * One visual language for the whole TV app: colour, type, spacing, focus and the
 * handful of surface recipes used by the shell, guide, channels, settings and player.
 * Main thread only.
 */
public final class Tv {

    public static final int BG = 0xFF07080B;
    public static final int BG_RAISED = 0xFF0B0D11;
    public static final int SURFACE = 0xFF12151B;
    public static final int SURFACE_2 = 0xFF191E26;
    public static final int SURFACE_3 = 0xFF222934;
    public static final int SURFACE_FOCUS = 0xFF2A323E;
    public static final int STROKE = 0xFF242B35;
    public static final int STROKE_SOFT = 0xFF1A2028;
    public static final int STROKE_STRONG = 0xFF39424F;
    public static final int TEXT = 0xFFF4F6F9;
    public static final int TEXT_2 = 0xFFAEB6C2;
    public static final int TEXT_3 = 0xFF7B8492;
    public static final int BRAND = 0xFFE6007E;
    public static final int BRAND_BRIGHT = 0xFFFF3D9A;
    public static final int WARNING = 0xFFFFB454;
    public static final int SUCCESS = 0xFF5BD6A0;
    public static final int DANGER = 0xFFFF6B6B;

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2.2f);
    private static final SimpleDateFormat CLOCK =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault());

    private Tv() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    public static String clock(long atMs) {
        return CLOCK.format(new Date(atMs));
    }

    public static String day(long atMs) {
        String value = DAY.format(new Date(atMs));
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** Broadcast display face: Barlow Condensed, loaded from the app's own font files. */
    public static Typeface display(Context context) {
        return font(context, R.font.barlow_condensed_semibold);
    }

    public static Typeface displayMedium(Context context) {
        return font(context, R.font.barlow_condensed_medium);
    }

    public static Typeface displayBold(Context context) {
        return font(context, R.font.barlow_condensed_bold);
    }

    public static Typeface condensed() {
        return Typeface.create("sans-serif-condensed", Typeface.BOLD);
    }

    private static Typeface font(Context context, int res) {
        try {
            return context.getResources().getFont(res);
        } catch (android.content.res.Resources.NotFoundException e) {
            return Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
    }

    public static TextView text(Context context, float sp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(medium ? medium() : Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView numeral(Context context, float sp, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(displayBold(context));
        view.setIncludeFontPadding(false);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    public static TextView displayText(Context context, float sp, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(display(context));
        view.setIncludeFontPadding(false);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    public static TextView label(Context context, String value, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        view.setTextColor(color);
        view.setTypeface(displayMedium(context));
        view.setText(value);
        view.setAllCaps(true);
        view.setLetterSpacing(0.11f);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView button(Context context, String value, boolean primary) {
        TextView view = displayText(context, 17f, primary ? 0xFFFFFFFF : TEXT);
        view.setTypeface(display(context));
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 22), 0, dp(context, 22), 0);
        view.setMinHeight(dp(context, primary ? 50 : 46));
        view.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button);
        view.setTextColor(context.getColorStateList(primary
                ? R.color.button_text_primary : R.color.button_text));
        view.setFocusable(true);
        view.setClickable(true);
        focusScale(view, 1.03f);
        return view;
    }

    public static TextView ghost(Context context, String value) {
        TextView view = displayText(context, 15f, TEXT_2);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        view.setMinHeight(dp(context, 44));
        view.setBackgroundResource(R.drawable.bg_button_ghost);
        view.setFocusable(true);
        view.setClickable(true);
        focusScale(view, 1.03f);
        return view;
    }

    public static ImageView icon(Context context, int res, int tint, float sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(res);
        view.setColorFilter(tint);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }

    /**
     * TV focus: a short decisive scale, never a bouncy spring. The ring itself comes
     * from each control's state-list background so it can be white on dark surfaces.
     */
    public static void focusScale(View view, float scale) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().cancel();
            v.animate()
                    .scaleX(hasFocus ? scale : 1f)
                    .scaleY(hasFocus ? scale : 1f)
                    .setDuration(hasFocus ? 140L : 110L)
                    .setInterpolator(EASE)
                    .start();
        });
    }

    public static void focusScale(View view) {
        focusScale(view, 1.03f);
    }

    public static GradientDrawable box(Context context, int fill, float radiusDp,
                                       int stroke, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dpf(context, radiusDp));
        if (strokeDp > 0f && (stroke >>> 24) != 0) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), stroke);
        }
        return drawable;
    }

    public static StateListDrawable states(Context context, int fill, int focusedFill,
                                           int pressedFill, float radiusDp,
                                           int stroke, float strokeDp,
                                           int focusStroke, float focusStrokeDp) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused},
                box(context, focusedFill, radiusDp, focusStroke, focusStrokeDp));
        drawable.addState(new int[]{android.R.attr.state_pressed},
                box(context, pressedFill, radiusDp, stroke, strokeDp));
        drawable.addState(new int[]{},
                box(context, fill, radiusDp, stroke, strokeDp));
        return drawable;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static View hairline(Context context, boolean horizontal) {
        View view = new View(context);
        view.setBackgroundColor(STROKE_SOFT);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : Math.max(1, dp(context, 1)),
                horizontal ? Math.max(1, dp(context, 1)) : ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    public static void pad(View view, float left, float top, float right, float bottom) {
        Context c = view.getContext();
        view.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
    }

    /** The MINtv lockup from the icon: condensed MIN, magenta tv, a short magenta rule. */
    public static LinearLayout brand(Context context, float sp) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.START);

        TextView word = new TextView(context);
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        word.setTypeface(displayBold(context));
        word.setIncludeFontPadding(false);
        word.setLetterSpacing(-0.02f);
        android.text.SpannableString label = new android.text.SpannableString(
                context.getString(R.string.brand_name) + context.getString(R.string.brand_accent));
        int split = context.getString(R.string.brand_name).length();
        label.setSpan(new android.text.style.ForegroundColorSpan(TEXT), 0, split,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.ForegroundColorSpan(BRAND), split, label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.RelativeSizeSpan(0.66f), split, label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        word.setText(label);
        column.addView(word);

        View rule = new View(context);
        rule.setBackgroundColor(BRAND);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                Math.max(2, dp(context, sp * 0.74f)), Math.max(2, dp(context, 2)));
        ruleLp.topMargin = dp(context, 2);
        column.addView(rule, ruleLp);
        column.setContentDescription(context.getString(R.string.app_name));
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return column;
    }

    public static View dot(Context context, int color, float sizeDp) {
        View view = new View(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        view.setBackground(bg);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }
}
