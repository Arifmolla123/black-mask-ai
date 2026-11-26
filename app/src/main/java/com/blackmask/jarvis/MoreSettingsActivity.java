package com.blackmask.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        // Auto-reply (v4.9.16): below App Lock. WhatsApp / Telegram /
        // Messenger messages get one automatic reply, so the owner
        // never has to send anything himself.
        CheckBox replyCheck = findViewById(R.id.autoreplyCheck);
        replyCheck.setChecked(prefs.getBoolean("autoreply_on", false));
        replyCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("autoreply_on", on).apply();
            if (on) ensureBatteryExempt();
            refreshAutoreplyLabels();
        });

        Button notifBtn = findViewById(R.id.autoreplyNotifBtn);
        notifBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {}
        });

        Button backBtn = findViewById(R.id.moreBackBtn);
        backBtn.setOnClickListener(v -> finish());

        refreshAppLockLabels();
        refreshAutoreplyLabels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-tap may have been switched on/off while we were away.
        refreshAppLockLabels();
        refreshAutoreplyLabels();
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
     * App picker (v4.9.13): a dialog with a search box on top and the
     * apps below, each with its icon, name and checkbox. Our own app is
     * never listed (locking it would lock this very page), and neither
     * is the home launcher (locking it would lock the home screen).
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
        List<AppItem> all = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ownPkg) || pkg.equals(homePkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            all.add(new AppItem(pkg, label != null ? label.toString() : pkg, ri));
        }
        // Sort by name so WhatsApp, Gallery etc. are easy to find.
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Set<String> saved = prefs.getStringSet("applock_apps", null);
        Set<String> locked = saved != null ? new HashSet<>(saved) : new HashSet<>();
        for (AppItem item : all) item.checked = locked.contains(item.pkg);

        View view = getLayoutInflater().inflate(R.layout.dialog_app_picker, null);
        EditText search = view.findViewById(R.id.appSearch);
        ListView list = view.findViewById(R.id.appList);
        AppPickAdapter adapter = new AppPickAdapter(this, all);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            AppItem item = adapter.getItem(pos);
            item.checked = !item.checked;
            adapter.notifyDataSetChanged();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.applock_choose_apps))
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Set<String> next = new HashSet<>();
                    for (AppItem item : all) {
                        if (item.checked) next.add(item.pkg);
                    }
                    prefs.edit().putStringSet("applock_apps", next).apply();
                    refreshAppLockLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One row in the app picker: package, display name, icon source. */
    private static class AppItem {
        final String pkg;
        final String label;
        final ResolveInfo info;
        boolean checked;

        AppItem(String pkg, String label, ResolveInfo info) {
            this.pkg = pkg;
            this.label = label;
            this.info = info;
        }
    }

    /** App picker list: icon + name + checkbox, with a name search. */
    private static class AppPickAdapter extends BaseAdapter {
        private final Context ctx;
        private final PackageManager pm;
        private final List<AppItem> all;
        private final List<AppItem> shown = new ArrayList<>();
        private final Map<String, Drawable> icons = new HashMap<>();

        AppPickAdapter(Context ctx, List<AppItem> all) {
            this.ctx = ctx;
            this.pm = ctx.getPackageManager();
            this.all = all;
            shown.addAll(all);
        }

        void filter(String q) {
            shown.clear();
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ENGLISH);
            for (AppItem item : all) {
                if (needle.isEmpty()
                        || item.label.toLowerCase(Locale.ENGLISH).contains(needle)) {
                    shown.add(item);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public AppItem getItem(int pos) { return shown.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx)
                        .inflate(R.layout.row_app_pick, parent, false);
            }
            AppItem item = getItem(pos);
            ImageView icon = convertView.findViewById(R.id.appIcon);
            Drawable d = icons.get(item.pkg);
            if (d == null) {
                d = item.info.loadIcon(pm);
                icons.put(item.pkg, d);
            }
            icon.setImageDrawable(d);
            ((TextView) convertView.findViewById(R.id.appName)).setText(item.label);
            ((CheckBox) convertView.findViewById(R.id.appCheck)).setChecked(item.checked);
            return convertView;
        }
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

    /** Auto-reply status + notification-access warning. */
    private void refreshAutoreplyLabels() {
        boolean on = prefs.getBoolean("autoreply_on", false);
        TextView st = findViewById(R.id.autoreplyStatus);
        st.setText(getString(on ? R.string.autoreply_status_on : R.string.autoreply_status_off));
        st.setTextColor(on ? 0xFF4CAF50 : 0xFFFF2A3C);
        // Auto-reply can only see messages while notification access is on.
        TextView warn = findViewById(R.id.autoreplyNotifWarn);
        warn.setVisibility(isNotifListenerOn() ? View.GONE : View.VISIBLE);
    }

    /** Is the notification listener switched on for this app? */
    private boolean isNotifListenerOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
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
