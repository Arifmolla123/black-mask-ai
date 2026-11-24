package com.blackmask.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Animated home background (v4.9.11): a deep-space "other world" behind
 * the Home page - twinkling starfield, two slow-drifting nebula glows
 * (red + violet) and embers rising from the bottom. Plain Canvas only,
 * no libraries. Redraws at ~30fps while the Home page is showing and
 * pauses the moment it is hidden (ViewFlipper) or detached, so it
 * costs no battery on the other pages.
 */
public class HomeBackgroundView extends View {

    private static final int STAR_COUNT = 90;
    private static final int EMBER_COUNT = 26;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(1337);
    private final RectF nebulaDst = new RectF();

    // Positions are fractions of width/height, so a rotation keeps them valid.
    private float[] starX, starY, starR, starPhase, starSpeed;
    private int[] starColor;
    private float[] emberX, emberY, emberR, emberSpeed, emberSway, emberPhase;
    private int[] emberColor;

    private Bitmap nebulaRed, nebulaViolet;
    private LinearGradient bgGradient;
    private int viewW, viewH;

    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean animating = false;
    private double timeSec = 0;
    private long lastFrameMs = 0;
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;
            long now = SystemClock.uptimeMillis();
            if (lastFrameMs != 0) timeSec += (now - lastFrameMs) / 1000.0;
            lastFrameMs = now;
            invalidate();
            animHandler.postDelayed(this, 33);
        }
    };

    public HomeBackgroundView(Context context) {
        super(context);
        init();
    }

    public HomeBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        nebulaRed = makeGlowBitmap(0xFFFF2A3C);
        nebulaViolet = makeGlowBitmap(0xFF7B2FFF);
    }

    /** A soft radial glow, pre-rendered once into a bitmap. */
    private Bitmap makeGlowBitmap(int color) {
        int s = 256;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                color, transparent, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        return b;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        if (w <= 0 || h <= 0) return;
        bgGradient = new LinearGradient(0, 0, 0, h,
                new int[]{0xFF05050F, 0xFF0A0616, 0xFF140409},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        if (starX != null) return; // positions are fractions - generate once
        starX = new float[STAR_COUNT];
        starY = new float[STAR_COUNT];
        starR = new float[STAR_COUNT];
        starPhase = new float[STAR_COUNT];
        starSpeed = new float[STAR_COUNT];
        starColor = new int[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            starR[i] = 0.8f + random.nextFloat() * 1.8f;
            starPhase[i] = random.nextFloat() * (float) (Math.PI * 2);
            starSpeed[i] = 0.4f + random.nextFloat() * 1.2f;
            starColor[i] = random.nextFloat() < 0.75f ? 0xFFFFFFFF : 0xFFFFD9DE;
        }
        emberX = new float[EMBER_COUNT];
        emberY = new float[EMBER_COUNT];
        emberR = new float[EMBER_COUNT];
        emberSpeed = new float[EMBER_COUNT];
        emberSway = new float[EMBER_COUNT];
        emberPhase = new float[EMBER_COUNT];
        emberColor = new int[EMBER_COUNT];
        for (int i = 0; i < EMBER_COUNT; i++) {
            emberX[i] = random.nextFloat();
            emberY[i] = random.nextFloat();
            emberR[i] = 1.2f + random.nextFloat() * 2.4f;
            emberSpeed[i] = 6f + random.nextFloat() * 14f; // px per second, rising
            emberSway[i] = 8f + random.nextFloat() * 18f;
            emberPhase[i] = random.nextFloat() * (float) (Math.PI * 2);
            emberColor[i] = random.nextFloat() < 0.6f ? 0xFFFF2A3C : 0xFFFF7A3D;
        }
    }

    private void startAnim() {
        if (animating) return;
        animating = true;
        lastFrameMs = 0;
        animHandler.post(frame);
    }

    private void stopAnim() {
        animating = false;
        animHandler.removeCallbacks(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) startAnim();
        else stopAnim();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (viewW <= 0 || viewH <= 0 || starX == null) return;
        float t = (float) timeSec;

        // Deep-space gradient.
        paint.setShader(bgGradient);
        canvas.drawRect(0, 0, viewW, viewH, paint);
        paint.setShader(null);

        // Nebula glows: two big soft blobs drifting in slow circles.
        drawNebula(canvas, nebulaRed,
                viewW * (0.5f + 0.32f * (float) Math.sin(t * 0.05f)),
                viewH * (0.30f + 0.16f * (float) Math.cos(t * 0.037f)),
                viewW * 0.95f, 90);
        drawNebula(canvas, nebulaViolet,
                viewW * (0.5f + 0.34f * (float) Math.cos(t * 0.031f + 2f)),
                viewH * (0.72f + 0.14f * (float) Math.sin(t * 0.043f + 1f)),
                viewW * 0.85f, 60);

        // Stars, twinkling.
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * starSpeed[i] + starPhase[i]);
            paint.setColor(starColor[i]);
            paint.setAlpha((int) (50 + 180 * tw));
            canvas.drawCircle(starX[i] * viewW, starY[i] * viewH, starR[i], paint);
        }

        // Embers rising from the bottom, swaying side to side.
        float span = viewH + 40;
        for (int i = 0; i < EMBER_COUNT; i++) {
            float y = emberY[i] * viewH - t * emberSpeed[i];
            y = ((y % span) + span) % span - 20;
            float x = emberX[i] * viewW + emberSway[i] * (float) Math.sin(t * 0.5f + emberPhase[i]);
            float edge = Math.min(y, viewH - y);
            float fade = Math.min(1f, edge / 100f);
            paint.setColor(emberColor[i]);
            paint.setAlpha((int) (190 * fade));
            canvas.drawCircle(x, y, emberR[i], paint);
        }
        paint.setAlpha(255);
    }

    private void drawNebula(Canvas canvas, Bitmap glow, float cx, float cy, float size, int alpha) {
        nebulaDst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        paint.setAlpha(alpha);
        canvas.drawBitmap(glow, null, nebulaDst, paint);
    }
}
