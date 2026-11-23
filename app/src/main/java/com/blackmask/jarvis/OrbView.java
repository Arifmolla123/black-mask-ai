package com.blackmask.jarvis;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Arc-reactor / BLACK MASK HUD core - the face of Black Mask AI.
 * Rotating tick ring + dashed arc ring + reactor coils around a pulsing core.
 * Spins and pulses faster while listening, slow and steady when idle.
 */
public class OrbView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pulse = 0f;
    private float rot = 0f;
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
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
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
            rot += listening ? 2.5f : 0.5f;
            if (rot >= 360f) rot -= 360f;
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

        // Breathing outer glow (smooth sine wave, not a linear pulse).
        float breath = (float) (0.5f + 0.5f * Math.sin(pulse * Math.PI * 2f));
        for (int i = 3; i >= 1; i--) {
            float r = base * (0.55f + 0.15f * i) * (1f + 0.06f * breath);
            int alpha = (int) (24 + 26 * (1f - breath) + (listening ? 24 : 0));
            fill.setColor(Color.argb(Math.min(alpha, 95), 255, 42, 60));
            canvas.drawCircle(cx, cy, r, fill);
        }

        // Radar sweep - a bright arc that circles the dial like a scanner.
        stroke.setStrokeWidth(base * 0.045f);
        stroke.setColor(Color.argb(listening ? 220 : 150, 255, 42, 60));
        float sweepStart = rot * 1.2f;
        canvas.drawArc(cx - base * 0.80f, cy - base * 0.80f,
                cx + base * 0.80f, cy + base * 0.80f, sweepStart, 55, false, stroke);
        stroke.setColor(Color.argb(60, 255, 42, 60));
        canvas.drawArc(cx - base * 0.80f, cy - base * 0.80f,
                cx + base * 0.80f, cy + base * 0.80f, sweepStart + 55, 40, false, stroke);

        // Outer rotating tick ring - the BLACK MASK HUD dial.
        stroke.setStrokeWidth(base * 0.02f);
        for (int i = 0; i < 60; i++) {
            float a = (float) Math.toRadians(i * 6 + rot);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float r1 = base * 0.97f;
            float r2 = base * (i % 5 == 0 ? 0.88f : 0.93f);
            stroke.setColor(Color.argb(i % 5 == 0 ? 220 : 110, 255, 42, 60));
            canvas.drawLine(cx + r1 * cos, cy + r1 * sin, cx + r2 * cos, cy + r2 * sin, stroke);
        }

        // Dashed arc ring, rotating the other way.
        stroke.setStrokeWidth(base * 0.035f);
        stroke.setColor(Color.argb(150, 255, 42, 60));
        for (int i = 0; i < 12; i++) {
            float start = i * 30 - rot * 1.5f;
            canvas.drawArc(cx - base * 0.80f, cy - base * 0.80f,
                    cx + base * 0.80f, cy + base * 0.80f, start, 18, false, stroke);
        }

        // Thin static ring.
        stroke.setStrokeWidth(base * 0.015f);
        stroke.setColor(Color.argb(120, 255, 90, 100));
        canvas.drawCircle(cx, cy, base * 0.66f, stroke);

        // Middle ring.
        fill.setColor(Color.argb(listening ? 200 : 140, 139, 0, 18));
        canvas.drawCircle(cx, cy, base * 0.52f, fill);

        // Reactor coils - 4 arc segments slowly rotating.
        stroke.setStrokeWidth(base * 0.05f);
        stroke.setColor(Color.argb(200, 255, 42, 60));
        for (int i = 0; i < 4; i++) {
            float start = i * 90 + rot * 0.8f + 10;
            canvas.drawArc(cx - base * 0.44f, cy - base * 0.44f,
                    cx + base * 0.44f, cy + base * 0.44f, start, 60, false, stroke);
        }

        // Core (breathes with the sine wave).
        fill.setColor(Color.argb(255, 255, 42, 60));
        canvas.drawCircle(cx, cy, base * (0.34f + 0.05f * breath), fill);
        // Hot center.
        fill.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawCircle(cx, cy, base * (0.13f + 0.03f * breath), fill);

        // Orbiting dot on the thin ring.
        float oa = (float) Math.toRadians(rot * 2);
        fill.setColor(Color.argb(255, 255, 120, 130));
        canvas.drawCircle(cx + base * 0.66f * (float) Math.cos(oa),
                cy + base * 0.66f * (float) Math.sin(oa), base * 0.035f, fill);
        // Second dot, counter-rotating on the middle ring.
        float ob = (float) Math.toRadians(-rot * 1.4f + 120);
        fill.setColor(Color.argb(220, 255, 200, 205));
        canvas.drawCircle(cx + base * 0.52f * (float) Math.cos(ob),
                cy + base * 0.52f * (float) Math.sin(ob), base * 0.028f, fill);
    }
}
