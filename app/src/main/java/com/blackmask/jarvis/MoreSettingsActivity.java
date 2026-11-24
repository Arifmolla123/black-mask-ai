package com.blackmask.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * More Settings (v4.9.12): a second settings page, because the main
 * Settings page was getting too long. Extra features live here.
 *
 * App Lock: pick any installed app, and it asks for your PIN before it
 * opens. The accessibility service (auto-tap) watches which app comes
 * to the front and shows the PIN gate (AppLockActivity). The PIN is
 * separate from the anti-theft PIN. If you forget it, just turn App
 * Lock off here - this page itself is never locked.
 */
public class MoreSettingsActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more_settings);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        CheckBox lockCheck = findViewById(R.id.applockCheck);
        lockCheck.setChecked(prefs.getBoolean("applock_on", false));
        lockCheck.setOnCheckedChangeListener((v, on) -> {
            if (on && prefs.getString("applock_pin", "").isEmpty()) {
                lockCheck.setChecked(false);
                Toast.makeText(this, getString(R.string.applock_need_pin), Toast.LENGTH_LONG).show();
                showAppLockPinDialog();
                return;
            }
            prefs.edit().putBoolean("applock_on", on).apply();
            if (on) ensureBatteryExempt();
            refreshAppLockLabels();
        });

        Button pinBtn = findViewById(R.id.applockPinBtn);
        pinBtn.setOnClickListener(v -> showAppLockPinDialog());

        Button pickBtn = findViewById(R.id.applockPickBtn);
        pickBtn.setOnClickListener(v -> showAppPicker());

        Button backBtn = findViewById(R.id.moreBackBtn);
        backBtn.setOnClickListener(v -> finish());

        refreshAppLockLabels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-tap may have been switched on/off while we were away.
        refreshAppLockLabels();
    }

    /** App Lock PIN dialog: 4-8 digits, saved in SharedPreferences. */
    private void showAppLockPinDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        input.setHint(getString(R.string.applock_pin_hint));
        String saved = prefs.getString("applock_pin", "");
        if (!saved.isEmpty()) input.setText(saved);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.applock_pin_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 4 || pin.length() > 8) {
                        Toast.makeText(this, getString(R.string.applock_pin_wrong),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("applock_pin", pin).apply();
                    Toast.makeText(this, getString(R.string.applock_pin_set),
                            Toast.LENGTH_SHORT).show();
                    refreshAppLockLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * App picker: every installed app with a launcher icon, with
     * checkboxes. Our own app is never listed (locking it would lock
     * this very page), and neither is the home launcher (locking it
     * would lock the home screen itself).
     */
    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        // The default home app: locking it would lock the home screen.
        String homePkg = "";
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo homeInfo = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (homeInfo != null && homeInfo.activityInfo != null) homePkg = homeInfo.activityInfo.packageName;
        } catch (Exception ignored) {}
        String ownPkg = getPackageName();
        List<String> pkgs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ownPkg) || pkg.equals(homePkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            pkgs.add(pkg);
            labels.add(label != null ? label.toString() : pkg);
        }
        // Sort by name so WhatsApp, Gallery etc. are easy to find.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        List<String> sortedPkgs = new ArrayList<>();
        List<String> sortedLabels = new ArrayList<>();
        for (int i : order) {
            sortedPkgs.add(pkgs.get(i));
            sortedLabels.add(labels.get(i));
        }
        Set<String> saved = prefs.getStringSet("applock_apps", null);
        Set<String> locked = saved != null ? new HashSet<>(saved) : new HashSet<>();
        boolean[] checked = new boolean[sortedPkgs.size()];
        for (int i = 0; i < sortedPkgs.size(); i++) checked[i] = locked.contains(sortedPkgs.get(i));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.applock_choose_apps))
                .setMultiChoiceItems(sortedLabels.toArray(new String[0]), checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Set<String> next = new HashSet<>();
                    for (int i = 0; i < sortedPkgs.size(); i++) {
                        if (checked[i]) next.add(sortedPkgs.get(i));
                    }
                    prefs.edit().putStringSet("applock_apps", next).apply();
                    refreshAppLockLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** App Lock status: ON/OFF line + PIN button text + locked-apps count. */
    private void refreshAppLockLabels() {
        boolean on = prefs.getBoolean("applock_on", false);
        boolean hasPin = !prefs.getString("applock_pin", "").isEmpty();
        Set<String> locked = prefs.getStringSet("applock_apps", null);
        int count = locked != null ? locked.size() : 0;
        TextView st = findViewById(R.id.applockStatus);
        st.setText(getString(on ? R.string.applock_status_on : R.string.applock_status_off));
        st.setTextColor(on && hasPin ? 0xFF4CAF50 : 0xFFFF2A3C);
        Button pinBtn = findViewById(R.id.applockPinBtn);
        pinBtn.setText(getString(hasPin ? R.string.applock_pin_change : R.string.applock_set_pin));
        TextView appsLabel = findViewById(R.id.applockAppsLabel);
        appsLabel.setText(count == 0
                ? getString(R.string.applock_apps_none)
                : getString(R.string.applock_apps_count, count));
        // App Lock only works while auto-tap (accessibility) is on.
        TextView warn = findViewById(R.id.applockTapWarn);
        boolean tapOn = BlackMaskAccessibilityService.isEnabled(this);
        warn.setVisibility(tapOn ? View.GONE : View.VISIBLE);
    }

    /** Ask Android not to kill the app in the background (same as the
     * anti-theft toggle in MainActivity). */
    private void ensureBatteryExempt() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }
}
