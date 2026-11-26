package com.blackmask.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
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
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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

    private static final int REQ_CONTACTS = 201;
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

        // Per-app choice (v4.9.19): reply only where ticked.
        wireAppCheck(R.id.autoreplyAppWhatsapp, "autoreply_app_whatsapp");
        wireAppCheck(R.id.autoreplyAppTelegram, "autoreply_app_telegram");
        wireAppCheck(R.id.autoreplyAppMessenger, "autoreply_app_messenger");
        wireAppCheck(R.id.autoreplyAppInstagram, "autoreply_app_instagram");
        wireAppCheck(R.id.autoreplyAppFacebook, "autoreply_app_facebook");

        // Schedule (v4.9.19): only at set hours.
        CheckBox schedCheck = findViewById(R.id.autoreplySchedCheck);
        schedCheck.setChecked(prefs.getBoolean("autoreply_sched_on", false));
        schedCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("autoreply_sched_on", on).apply();
            refreshAutoreplyLabels();
        });
        Button fromBtn = findViewById(R.id.autoreplyFromBtn);
        Button toBtn = findViewById(R.id.autoreplyToBtn);
        fromBtn.setOnClickListener(v -> showTimePicker(true));
        toBtn.setOnClickListener(v -> showTimePicker(false));

        // My own reply (v4.9.19).
        Button customBtn = findViewById(R.id.autoreplyCustomBtn);
        customBtn.setOnClickListener(v -> showCustomReplyDialog());
        CheckBox customUseCheck = findViewById(R.id.autoreplyCustomUseCheck);
        customUseCheck.setChecked(prefs.getBoolean("autoreply_custom_use", false));
        customUseCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("autoreply_custom_use", on).apply();
            refreshAutoreplyLabels();
        });

        // Groups (v4.9.19): reply only in picked groups.
        CheckBox groupsCheck = findViewById(R.id.autoreplyGroupsCheck);
        groupsCheck.setChecked(prefs.getBoolean("autoreply_groups_on", false));
        groupsCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("autoreply_groups_on", on).apply();
            refreshAutoreplyLabels();
        });
        Button groupsBtn = findViewById(R.id.autoreplyGroupsBtn);
        groupsBtn.setOnClickListener(v -> showGroupPicker());

        // Contacts (v4.9.19): only picked contacts (+ unknowns if ticked).
        Button contactsBtn = findViewById(R.id.autoreplyContactsBtn);
        contactsBtn.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            } else {
                showContactPicker();
            }
        });
        CheckBox unknownCheck = findViewById(R.id.autoreplyUnknownCheck);
        unknownCheck.setChecked(prefs.getBoolean("autoreply_unknown_on", true));
        unknownCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("autoreply_unknown_on", on).apply();
            refreshAutoreplyLabels();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACTS
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showContactPicker();
        } else if (requestCode == REQ_CONTACTS) {
            Toast.makeText(this, getString(R.string.autoreply_need_contact_perm),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** One per-app auto-reply checkbox (v4.9.19). */
    private void wireAppCheck(int id, String key) {
        CheckBox box = findViewById(id);
        box.setChecked(prefs.getBoolean(key, true));
        box.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            refreshAutoreplyLabels();
        });
    }

    /** "10:49 PM" for a timestamp. */
    private String fmtClock(long millis) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(new java.util.Date(millis));
    }

    /** The diagnostic code the service saved, in the owner's language. */
    private String checkText(String code) {
        if (code.startsWith("replied:")) {
            return getString(R.string.autoreply_check_replied, code.substring("replied:".length()));
        }
        switch (code) {
            case "schedule": return getString(R.string.autoreply_check_schedule);
            case "group": return getString(R.string.autoreply_check_group);
            case "online": return getString(R.string.autoreply_check_online);
            case "not-selected": return getString(R.string.autoreply_check_not_selected);
            case "cap": return getString(R.string.autoreply_check_cap);
            case "dedupe": return getString(R.string.autoreply_check_dedupe);
            case "no-button": return getString(R.string.autoreply_check_no_button);
            default: return code;
        }
    }

    /** From/To time picker for the auto-reply schedule (v4.9.19). */
    private void showTimePicker(boolean isStart) {
        String key = isStart ? "autoreply_sched_start" : "autoreply_sched_end";
        int def = isStart ? 22 * 60 : 7 * 60;
        int mins = prefs.getInt(key, def);
        new TimePickerDialog(this, (view, hour, minute) -> {
            prefs.edit().putInt(key, hour * 60 + minute).apply();
            refreshAutoreplyLabels();
        }, mins / 60, mins % 60, DateFormat.is24HourFormat(this)).show();
    }

    /** "10:00 PM" for minutes since midnight. */
    private String fmtTime(int mins) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, mins / 60);
        c.set(Calendar.MINUTE, mins % 60);
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(c.getTime());
    }

    /** Write your own reply text, one per language (v4.9.21): the
     * sender gets the one in the language they wrote in. The old
     * single text, if any, shows up as the English one. */
    private void showCustomReplyDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        String legacy = prefs.getString("autoreply_custom_text", "");
        EditText enInput = addReplyField(layout, "English",
                prefs.getString("autoreply_custom_en", legacy));
        EditText bnInput = addReplyField(layout, "বাংলা",
                prefs.getString("autoreply_custom_bn", ""));
        EditText hiInput = addReplyField(layout, "हिन्दी",
                prefs.getString("autoreply_custom_hi", ""));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.autoreply_custom_btn))
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.edit().putString("autoreply_custom_en", enInput.getText().toString().trim())
                            .putString("autoreply_custom_bn", bnInput.getText().toString().trim())
                            .putString("autoreply_custom_hi", hiInput.getText().toString().trim()).apply();
                    refreshAutoreplyLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One labelled reply box inside the custom-reply dialog. */
    private EditText addReplyField(LinearLayout parent, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF555555);
        tv.setTextSize(13);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHint(getString(R.string.autoreply_custom_hint));
        et.setText(value);
        parent.addView(et);
        return et;
    }

    /** Pick which groups get auto-replies (v4.9.19). Only groups seen
     * in notifications can be listed - the app cannot read WhatsApp's
     * own group list. Group names are normalized (v4.9.21): WhatsApp
     * sometimes adds " (2 messages)" to the title, so the same group
     * showed up three times - now it shows once. */
    private void showGroupPicker() {
        Set<String> seen = prefs.getStringSet("autoreply_groups_seen", null);
        Set<String> normSeen = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (seen != null) {
            for (String g : seen) {
                String n = BlackMaskNotificationService.normalizeGroupName(g);
                if (!n.isEmpty()) normSeen.add(n);
            }
        }
        List<String> groups = new ArrayList<>(normSeen);
        if (groups.isEmpty()) {
            Toast.makeText(this, getString(R.string.autoreply_groups_empty),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> saved = prefs.getStringSet("autoreply_groups", null);
        Set<String> picked = new HashSet<>();
        if (saved != null) {
            for (String g : saved) picked.add(BlackMaskNotificationService.normalizeGroupName(g));
        }
        boolean[] checked = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) checked[i] = picked.contains(groups.get(i));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.autoreply_groups_btn))
                .setMultiChoiceItems(groups.toArray(new String[0]), checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Set<String> next = new HashSet<>();
                    for (int i = 0; i < groups.size(); i++) {
                        if (checked[i]) next.add(groups.get(i));
                    }
                    prefs.edit().putStringSet("autoreply_groups", next).apply();
                    refreshAutoreplyLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Pick which contacts get auto-replies (v4.9.19): search box on
     * top, name + number + checkbox below. Names are stored; numbers
     * are only shown to tell same-name contacts apart. */
    private void showContactPicker() {
        List<String[]> all = CommandEngine.allContacts(this);
        // One row per name (a contact with two numbers shows once).
        List<ContactItem> items = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (String[] c : all) {
            if (seenNames.add(c[0])) items.add(new ContactItem(c[0], c[1]));
        }
        Set<String> saved = prefs.getStringSet("autoreply_contacts", null);
        Set<String> picked = saved != null ? new HashSet<>(saved) : new HashSet<>();
        for (ContactItem item : items) item.checked = picked.contains(item.name);

        View view = getLayoutInflater().inflate(R.layout.dialog_app_picker, null);
        EditText search = view.findViewById(R.id.appSearch);
        search.setHint(getString(R.string.autoreply_contacts_search_hint));
        ListView list = view.findViewById(R.id.appList);
        ContactPickAdapter adapter = new ContactPickAdapter(this, items);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            ContactItem item = adapter.getItem(pos);
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
                .setTitle(getString(R.string.autoreply_contacts_btn))
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Set<String> next = new HashSet<>();
                    for (ContactItem item : items) {
                        if (item.checked) next.add(item.name);
                    }
                    prefs.edit().putStringSet("autoreply_contacts", next).apply();
                    refreshAutoreplyLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One row in the contact picker: display name + number. */
    private static class ContactItem {
        final String name;
        final String number;
        boolean checked;

        ContactItem(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    /** Contact picker list: name + number + checkbox, with a search. */
    private static class ContactPickAdapter extends BaseAdapter {
        private final List<ContactItem> all;
        private final List<ContactItem> shown = new ArrayList<>();

        ContactPickAdapter(Context ctx, List<ContactItem> all) {
            this.all = all;
            shown.addAll(all);
        }

        void filter(String q) {
            shown.clear();
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ENGLISH);
            for (ContactItem item : all) {
                if (needle.isEmpty()
                        || item.name.toLowerCase(Locale.ENGLISH).contains(needle)
                        || item.number.contains(needle)) {
                    shown.add(item);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public ContactItem getItem(int pos) { return shown.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.row_contact_pick, parent, false);
            }
            ContactItem item = getItem(pos);
            ((TextView) convertView.findViewById(R.id.contactName)).setText(item.name);
            ((TextView) convertView.findViewById(R.id.contactNumber)).setText(item.number);
            ((CheckBox) convertView.findViewById(R.id.contactCheck)).setChecked(item.checked);
            return convertView;
        }
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
        // Diagnosis (v4.9.20): last message seen + why it was answered/skipped.
        TextView lastSeen = findViewById(R.id.autoreplyLastSeen);
        if (lastSeen != null) {
            String seen = prefs.getString("autoreply_last_seen", "");
            long at = prefs.getLong("autoreply_last_seen_at", 0);
            lastSeen.setText(seen.isEmpty()
                    ? getString(R.string.autoreply_last_seen_none)
                    : getString(R.string.autoreply_last_seen, seen) + " • " + fmtClock(at));
        }
        TextView lastCheck = findViewById(R.id.autoreplyLastCheck);
        if (lastCheck != null) {
            String code = prefs.getString("autoreply_last_check", "");
            long at = prefs.getLong("autoreply_last_check_at", 0);
            lastCheck.setText(code.isEmpty() ? "" : checkText(code) + " • " + fmtClock(at));
        }
        // From / To buttons show the set hours.
        Button fromBtn = findViewById(R.id.autoreplyFromBtn);
        Button toBtn = findViewById(R.id.autoreplyToBtn);
        if (fromBtn != null) {
            fromBtn.setText(getString(R.string.autoreply_from_fmt,
                    fmtTime(prefs.getInt("autoreply_sched_start", 22 * 60))));
            toBtn.setText(getString(R.string.autoreply_to_fmt,
                    fmtTime(prefs.getInt("autoreply_sched_end", 7 * 60))));
        }
        // My own reply texts: which languages are set (v4.9.21).
        TextView customText = findViewById(R.id.autoreplyCustomText);
        if (customText != null) {
            List<String> setLangs = new ArrayList<>();
            if (!prefs.getString("autoreply_custom_en", "").trim().isEmpty()) setLangs.add("English");
            if (!prefs.getString("autoreply_custom_bn", "").trim().isEmpty()) setLangs.add("বাংলা");
            if (!prefs.getString("autoreply_custom_hi", "").trim().isEmpty()) setLangs.add("हिन्दी");
            if (setLangs.isEmpty()
                    && !prefs.getString("autoreply_custom_text", "").trim().isEmpty()) {
                setLangs.add("English");
            }
            customText.setText(setLangs.isEmpty()
                    ? getString(R.string.autoreply_custom_none)
                    : getString(R.string.autoreply_custom_set_multi,
                            android.text.TextUtils.join(", ", setLangs)));
        }
        // How many groups are picked.
        TextView groupsText = findViewById(R.id.autoreplyGroupsText);
        if (groupsText != null) {
            Set<String> groups = prefs.getStringSet("autoreply_groups", null);
            int n = groups == null ? 0 : groups.size();
            groupsText.setText(n == 0
                    ? getString(R.string.autoreply_groups_none)
                    : getString(R.string.autoreply_groups_count, n));
        }
        // How many contacts are picked. None picked = everybody.
        TextView contactsText = findViewById(R.id.autoreplyContactsText);
        if (contactsText != null) {
            Set<String> contacts = prefs.getStringSet("autoreply_contacts", null);
            int n = contacts == null ? 0 : contacts.size();
            contactsText.setText(n == 0
                    ? getString(R.string.autoreply_contacts_none)
                    : getString(R.string.autoreply_contacts_count, n));
        }
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
