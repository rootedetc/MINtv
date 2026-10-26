package local.mintv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * Broadcast Rail skeleton primitives.
 *
 * <p>A skeleton is a flat surface panel, not a shimmer: the whole group breathes once every
 * 1.8 seconds between 55% and 90% opacity. It disappears the moment real content arrives and is
 * cancelled when its view leaves the window.</p>
 */
public final class Skeleton {

    private Skeleton() {
    }

    public static View box(Context context, float widthDp, float heightDp) {
        View view = new View(context);
        view.setBackground(Tv.box(context, Tv.SURFACE_2, 6f, Tv.STROKE_SOFT, 1f));
        view.setLayoutParams(new ViewGroup.LayoutParams(
                widthDp > 0 ? Tv.dp(context, widthDp) : ViewGroup.LayoutParams.MATCH_PARENT,
                Tv.dp(context, heightDp)));
        return view;
    }

    public static View box(Context context, float widthDp, float heightDp, float radiusDp) {
        View view = box(context, widthDp, heightDp);
        view.setBackground(Tv.box(context, Tv.SURFACE_2, radiusDp, Tv.STROKE_SOFT, 1f));
        return view;
    }

    public static View line(Context context, float widthDp, float heightDp) {
        View view = new View(context);
        view.setBackground(Tv.box(context, Tv.SURFACE_3, 3f, 0, 0f));
        view.setLayoutParams(new ViewGroup.LayoutParams(
                widthDp > 0 ? Tv.dp(context, widthDp) : ViewGroup.LayoutParams.MATCH_PARENT,
                Tv.dp(context, heightDp)));
        return view;
    }

    /** Starts the quiet opacity pulse on a skeleton group. */
    public static void pulse(View view) {
        if (view == null) {
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0.55f, 0.9f);
        animator.setDuration(900L);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation ->
                view.setAlpha((float) animation.getAnimatedValue()));
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                animator.cancel();
            }
        });
        animator.start();
    }
}
