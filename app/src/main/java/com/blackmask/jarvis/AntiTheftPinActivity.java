package com.blackmask.jarvis;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Anti-theft PIN gate (v4.9.7): when someone opens the power menu, the
 * accessibility service closes it and shows this screen instead. Only
 * the owner's PIN gets past it - and a correct PIN gives 60 seconds of
 * grace to press power and tap "Power off" normally.
 *
 * Wrong PIN 3 times: the phone rings loudly, a location SMS goes to
 * the trusted number, and the phone locks. The two-button force
 * restart (power + volume down) is hardware and cannot be blocked by
 * any app - but the phone comes back locked, and on boot the location
 * SMS goes out again (BootReceiver).
 */
public class AntiTheftPinActivity extends Activity {

    private static final int GRACE_MS = 60_000;
    private static final int MAX_WRONG = 3;

    private SharedPreferences prefs;
    private String pin = "";
    private String entered = "";
    private int wrong = 0;
    private TextView dotsView;
    private TextView msgView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        pin = prefs.getString("antitheft_pin", "");
        // Defensive: no PIN set, or anti-theft off - nothing to guard.
        if (pin.isEmpty() || !prefs.getBoolean("antitheft_on", false)) {
            finish();
            return;
        }
        // Show over the lock screen too - the power menu opens from there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("BLACK MASK AI");
        title.setTextColor(Color.parseColor("#FF2A3C"));
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.antitheft_gate_title));
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(18);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 24, 0, 8);
        root.addView(sub);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.antitheft_gate_desc));
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

    private void onKey(String k) {
        if ("C".equals(k)) {
            entered = "";
        } else if ("⌫".equals(k)) {
            if (!entered.isEmpty()) entered = entered.substring(0, entered.length() - 1);
        } else {
            if (entered.length() >= 4) return;
            entered += k;
        }
        updateDots();
        if (entered.length() == 4) checkPin();
    }

    private void updateDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i < entered.length() ? "● " : "○ ");
        dotsView.setText(sb.toString().trim());
    }

    private void checkPin() {
        if (entered.equals(pin)) {
            // Correct: 60 seconds of grace to power off normally.
            prefs.edit().putLong("antitheft_grace_until",
                    System.currentTimeMillis() + GRACE_MS).apply();
            Toast.makeText(this, getString(R.string.antitheft_correct_pin),
                    Toast.LENGTH_LONG).show();
            finish();
        } else {
            wrong++;
            entered = "";
            updateDots();
            if (wrong >= MAX_WRONG) {
                msgView.setText(getString(R.string.antitheft_wrong_pin));
                // Thief: ring loudly, send the location to the trusted
                // number, and lock the phone.
                CommandEngine.sendAntiTheftAlert(this, "THEFT");
                finish();
            } else {
                msgView.setText(getString(R.string.antitheft_wrong_pin)
                        + " (" + wrong + "/" + MAX_WRONG + ")");
            }
        }
    }

    /** The gate cannot be dismissed with Back. */
    @Override
    public void onBackPressed() {
        // Blocked on purpose - only the PIN closes this screen.
    }
}
