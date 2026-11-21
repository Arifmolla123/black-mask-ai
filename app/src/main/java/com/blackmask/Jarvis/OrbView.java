package com.blackmask.jarvis;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Glowing pulsing orb - the face of Black Mask AI.
 * Pulses faster while listening, glows steady when idle.
 */
public class OrbView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pulse = 0f;
    private boolean listening = false;
    private ValueAnimator animator;

    public OrbView(Context context) {
        super(context);
        init();
    }

    public OrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void setListening(boolean listening) {
        this.listening = listening;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(listening ? 700 : 2200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) setListening(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) / 2f;

        // Outer glow rings (red, like the logo's laser eyes).
        for (int i = 3; i >= 1; i--) {
            float r = base * (0.55f + 0.15f * i) * (1f + 0.08f * pulse);
            int alpha = (int) (28 + 22 * (1f - pulse) + (listening ? 20 : 0));
            paint.setColor(Color.argb(Math.min(alpha, 90), 255, 42, 60));
            canvas.drawCircle(cx, cy, r, paint);
        }
        // Middle ring.
        paint.setColor(Color.argb(listening ? 200 : 140, 139, 0, 18));
        canvas.drawCircle(cx, cy, base * 0.52f, paint);
        // Core.
        paint.setColor(Color.argb(255, 255, 42, 60));
        canvas.drawCircle(cx, cy, base * (0.34f + 0.05f * pulse), paint);
        // Hot center.
        paint.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawCircle(cx, cy, base * (0.13f + 0.03f * pulse), paint);
    }
}
