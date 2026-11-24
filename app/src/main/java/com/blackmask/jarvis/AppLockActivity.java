package com.blackmask.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App Lock PIN gate (v4.9.12, redesigned v4.9.13): when a locked app
 * comes to the front, the accessibility service shows this screen
 * instead. Only the owner's PIN - or the phone's own enrolled
 * fingerprint - gets past it. A correct unlock lasts only while the
 * user stays inside that app (v4.9.14): leave it for another app and
 * the next open asks again.
 *
 * Unlike the anti-theft gate, a wrong PIN does NOT ring or send an SMS
 * - this is just an app lock. After 3 wrong PINs the phone goes Home.
 * Back also goes Home, so there is no way past the gate into the app.
 * The PIN is separate from the anti-theft PIN, and the owner can turn
 * App Lock off from More Settings if the PIN is forgotten.
 *
 * v4.9.13 design: the same animated starfield/nebula background as
 * the Home page, the locked app's own icon and name up front, round
 * keypad buttons, and a shake when the PIN is wrong.
 * v4.9.14: the gate is centered and fits any screen (ScrollView),
 * and typing digits never unlocks on its own - only the ENTER button
 * submits the PIN, so guessing digit by digit cannot slip through.
 */
public class AppLockActivity extends Activity {

    private static final int GRACE_MS = 60_000;
    private static final int MAX_WRONG = 3;

    private SharedPreferences prefs;
    private String pin = "";
    private String targetPkg = "";
    private String entered = "";
    private int wrong = 0;
    private boolean pinOk = false;
    private TextView dotsView;
    private TextView msgView;
    private TextView appView;
    private ImageView appIconView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        pin = prefs.getString("applock_pin", "");
        // Defensive: no PIN set, or App Lock off - nothing to guard.
        if (pin.isEmpty() || !prefs.getBoolean("applock_on", false)) {
            finish();
            return;
        }
        targetPkg = getIntent().getStringExtra("lock_pkg");
        if (targetPkg == null) targetPkg = "";
        float density = getResources().getDisplayMetrics().density;

        // Animated background first (same starfield as the Home page),
        // content on top of it. The content sits in a ScrollView (v4.9.14)
        // so it stays centered and nothing is ever cut off, even on a
        // small screen - the fingerprint button is always reachable.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        HomeBackgroundView bg = new HomeBackgroundView(this);
        frame.addView(bg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(32, 24, 32, 24);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Black Mask logo at the top.
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        int logoPx = (int) (72 * density);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoPx, logoPx);
        logoLp.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoLp);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("BLACK MASK AI");
        title.setTextColor(Color.parseColor("#FF2A3C"));
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 24, 0, 0);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.applock_gate_title));
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(18);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 24, 0, 8);
        root.addView(sub);

        // The locked app itself: its own icon + name, so you see at a
        // glance which app is locked.
        appIconView = new ImageView(this);
        int iconPx = (int) (64 * density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.gravity = Gravity.CENTER;
        appIconView.setLayoutParams(iconLp);
        root.addView(appIconView);

        appView = new TextView(this);
        appView.setTextColor(Color.WHITE);
        appView.setTextSize(16);
        appView.setTypeface(null, android.graphics.Typeface.BOLD);
        appView.setGravity(Gravity.CENTER);
        appView.setPadding(0, 12, 0, 8);
        root.addView(appView);
        refreshAppInfo();

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.applock_gate_desc));
        desc.setTextColor(Color.parseColor("#9AA5B1"));
        desc.setTextSize(13);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 32);
        root.addView(desc);

        dotsView = new TextView(this);
        dotsView.setTextColor(Color.WHITE);
        dotsView.setTextSize(28);
        dotsView.setGravity(Gravity.CENTER);
        dotsView.setPadding(0, 0, 0, 8);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dotsView.setLayoutParams(dotsLp);
        root.addView(dotsView);
        updateDots();

        msgView = new TextView(this);
        msgView.setTextColor(Color.parseColor("#FF2A3C"));
        msgView.setTextSize(14);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(0, 0, 0, 24);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        msgView.setLayoutParams(msgLp);
        root.addView(msgView);

        // Round keypad buttons, centered in the middle of the screen.
        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        pad.setRowCount(4);
        pad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫"};
        int btnPx = (int) (68 * density);
        int btnMargin = (int) (8 * density);
        for (String k : keys) {
            Button b = new Button(this);
            b.setText(k);
            b.setTextSize(22);
            b.setTextColor(Color.WHITE);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.parseColor("#1A1A1A"));
            b.setBackground(circle);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = btnPx;
            lp.height = btnPx;
            lp.setMargins(btnMargin, btnMargin, btnMargin, btnMargin);
            b.setLayoutParams(lp);
            final String key = k;
            b.setOnClickListener(v -> onKey(key));
            pad.addView(b);
        }
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        padLp.gravity = Gravity.CENTER_HORIZONTAL;
        pad.setLayoutParams(padLp);
        root.addView(pad);

        // ENTER (v4.9.14): typing digits never unlocks on its own any
        // more - only this button submits the PIN, so nobody can slip
        // through by typing digit after digit.
        Button enterBtn = new Button(this);
        enterBtn.setText(getString(R.string.applock_enter));
        enterBtn.setTextColor(Color.WHITE);
        enterBtn.setTextSize(16);
        enterBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable enterBg = new GradientDrawable();
        enterBg.setShape(GradientDrawable.RECTANGLE);
        enterBg.setCornerRadius(24 * density);
        enterBg.setColor(Color.parseColor("#FF2A3C"));
        enterBtn.setBackground(enterBg);
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        enterLp.setMargins(0, 24, 0, 0);
        enterBtn.setLayoutParams(enterLp);
        enterBtn.setOnClickListener(v -> submitPin());
        root.addView(enterBtn);

        // Fingerprint (v4.9.13): the phone's own enrolled fingerprint
        // opens the app too - no separate setup, it just works if the
        // phone has a fingerprint sensor with a finger enrolled. The
        // button only shows when fingerprint is actually available.
        Button bioBtn = new Button(this);
        bioBtn.setText(getString(R.string.applock_bio_button));
        bioBtn.setTextColor(Color.parseColor("#FF2A3C"));
        bioBtn.setTextSize(16);
        GradientDrawable bioBg = new GradientDrawable();
        bioBg.setShape(GradientDrawable.RECTANGLE);
        bioBg.setCornerRadius(24 * density);
        bioBg.setColor(Color.parseColor("#1A1A1A"));
        bioBtn.setBackground(bioBg);
        LinearLayout.LayoutParams bioLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bioLp.setMargins(0, 24, 0, 0);
        bioBtn.setLayoutParams(bioLp);
        bioBtn.setOnClickListener(v -> tryFingerprint());
        root.addView(bioBtn);
        if (!isFingerprintAvailable()) bioBtn.setVisibility(View.GONE);

        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        // Ask for the fingerprint straight away - if the user cancels,
        // the PIN keypad is right there.
        tryFingerprint();
    }

    /**
     * Single-task relaunch: the gate was in the background and another
     * locked app opened - guard the new app, and start the PIN fresh.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String pkg = intent.getStringExtra("lock_pkg");
        if (pkg != null) targetPkg = pkg;
        entered = "";
        wrong = 0;
        pinOk = false;
        updateDots();
        if (msgView != null) msgView.setText("");
        refreshAppInfo();
    }

    /** The locked app's icon + display name, so you see what is locked. */
    private void refreshAppInfo() {
        if (appView == null) return;
        String name = targetPkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) name = label.toString();
            if (appIconView != null) {
                Drawable icon = pm.getApplicationIcon(targetPkg);
                appIconView.setImageDrawable(icon);
            }
        } catch (Exception ignored) {}
        appView.setText(name);
    }

    // ---------- fingerprint ----------

    /** Fingerprint sensor + at least one finger enrolled (API 28+).
     * Uses the no-arg canAuthenticate(): the BIOMETRIC_STRONG constant
     * is missing from this SDK's android.jar, and the plain check is
     * all an app-lock gate needs. */
    private boolean isFingerprintAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            BiometricManager bm = getSystemService(BiometricManager.class);
            if (bm == null) return false;
            return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    /** Show the system fingerprint prompt. Success unlocks like a PIN. */
    private void tryFingerprint() {
        if (!isFingerprintAvailable()) return;
        try {
            BiometricPrompt bp = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.applock_bio_title))
                    .setDescription(getString(R.string.applock_bio_desc))
                    .setNegativeButton(getString(R.string.applock_bio_cancel),
                            getMainExecutor(), (d, which) -> {
                                // Back to the PIN keypad.
                            })
                    .build();
            bp.authenticate(new CancellationSignal(), getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            onUnlocked(getString(R.string.applock_bio_ok));
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // Wrong finger: the system shows its own message.
                        }

                        @Override
                        public void onAuthenticationError(int errorCode,
                                CharSequence errString) {
                            // Cancelled or locked out: the PIN keypad is
                            // still there.
                        }
                    });
        } catch (Exception ignored) {}
    }

    // ---------- PIN keypad ----------

    /** Digits only fill the dots now - nothing is checked until ENTER
     * is pressed (v4.9.14), so typing digit after digit can never
     * slip through on its own. */
    private void onKey(String k) {
        if ("C".equals(k)) {
            entered = "";
        } else if ("⌫".equals(k)) {
            if (!entered.isEmpty()) entered = entered.substring(0, entered.length() - 1);
        } else {
            if (entered.length() >= pin.length()) return;
            entered += k;
        }
        updateDots();
        if (msgView != null) msgView.setText("");
    }

    /** ENTER pressed: only now is the PIN checked. */
    private void submitPin() {
        if (entered.length() < pin.length()) {
            shake(dotsView);
            msgView.setText(getString(R.string.applock_enter_full));
            return;
        }
        checkPin();
    }

    private void updateDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) sb.append(i < entered.length() ? "● " : "○ ");
        dotsView.setText(sb.toString().trim());
    }

    private void checkPin() {
        if (entered.equals(pin)) {
            onUnlocked(getString(R.string.applock_correct_pin));
        } else {
            wrong++;
            entered = "";
            updateDots();
            shake(dotsView);
            if (wrong >= MAX_WRONG) {
                // 3 wrong PINs: give up and go Home - the app stays locked.
                goHome();
            } else {
                msgView.setText(getString(R.string.applock_wrong_pin)
                        + " (" + wrong + "/" + MAX_WRONG + ")");
            }
        }
    }

    /** Correct PIN or fingerprint: the app is unlocked while the user
     * stays inside it. The service clears this grace the moment the
     * user leaves the app, so the next open asks again. */
    private void onUnlocked(String msg) {
        pinOk = true;
        prefs.edit().putString("applock_grace_pkg", targetPkg)
                .putLong("applock_grace_until", System.currentTimeMillis() + GRACE_MS)
                .apply();
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Quick left-right shake (wrong PIN). */
    private void shake(View v) {
        v.animate().translationX(20f).setDuration(60)
                .withEndAction(() -> v.animate().translationX(-20f).setDuration(80)
                .withEndAction(() -> v.animate().translationX(12f).setDuration(60)
                .withEndAction(() -> v.animate().translationX(0f).setDuration(60)
                        .start()).start()).start()).start();
    }

    /** Home screen - the locked app stays locked behind the gate. */
    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception ignored) {}
        finish();
    }

    /** Back goes Home too - there is no way past the gate into the app. */
    @Override
    public void onBackPressed() {
        goHome();
    }
}
