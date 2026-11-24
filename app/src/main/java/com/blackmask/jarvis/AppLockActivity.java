package com.blackmask.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App Lock PIN gate (v4.9.12): when a locked app comes to the front,
 * the accessibility service shows this screen instead. Only the owner's
 * PIN gets past it - a correct PIN gives 60 seconds of grace for that
 * app, so opening chats inside it does not ask again and again.
 *
 * Unlike the anti-theft gate, a wrong PIN does NOT ring or send an SMS
 * - this is just an app lock. After 3 wrong PINs the phone goes Home.
 * Back also goes Home, so there is no way past the gate into the app.
 * The PIN is separate from the anti-theft PIN, and the owner can turn
 * App Lock off from More Settings if the PIN is forgotten.
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(48, 48, 48, 48);

        // Black Mask logo at the top.
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        int logoPx = (int) (120 * getResources().getDisplayMetrics().density);
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

        appView = new TextView(this);
        appView.setTextColor(Color.parseColor("#9AA5B1"));
        appView.setTextSize(13);
        appView.setGravity(Gravity.CENTER);
        appView.setPadding(0, 0, 0, 8);
        root.addView(appView);
        refreshAppName();

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
        root.addView(dotsView);
        updateDots();

        msgView = new TextView(this);
        msgView.setTextColor(Color.parseColor("#FF2A3C"));
        msgView.setTextSize(14);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(0, 0, 0, 24);
        root.addView(msgView);

        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        pad.setRowCount(4);
        pad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫"};
        for (String k : keys) {
            Button b = new Button(this);
            b.setText(k);
            b.setTextSize(22);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.parseColor("#1A1A1A"));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 210;
            lp.height = 150;
            lp.setMargins(8, 8, 8, 8);
            b.setLayoutParams(lp);
            final String key = k;
            b.setOnClickListener(v -> onKey(key));
            pad.addView(b);
        }
        root.addView(pad);

        setContentView(root);
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
        refreshAppName();
    }

    /** The locked app's display name, so the user sees what is locked. */
    private void refreshAppName() {
        if (appView == null) return;
        String name = targetPkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) name = label.toString();
        } catch (Exception ignored) {}
        appView.setText(name);
    }

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
        if (entered.length() == pin.length()) checkPin();
    }

    private void updateDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) sb.append(i < entered.length() ? "● " : "○ ");
        dotsView.setText(sb.toString().trim());
    }

    private void checkPin() {
        if (entered.equals(pin)) {
            // Correct: 60 seconds of grace for this app.
            pinOk = true;
            prefs.edit().putString("applock_grace_pkg", targetPkg)
                    .putLong("applock_grace_until", System.currentTimeMillis() + GRACE_MS)
                    .apply();
            Toast.makeText(this, getString(R.string.applock_correct_pin),
                    Toast.LENGTH_LONG).show();
            finish();
        } else {
            wrong++;
            entered = "";
            updateDots();
            if (wrong >= MAX_WRONG) {
                // 3 wrong PINs: give up and go Home - the app stays locked.
                goHome();
            } else {
                msgView.setText(getString(R.string.applock_wrong_pin)
                        + " (" + wrong + "/" + MAX_WRONG + ")");
            }
        }
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
