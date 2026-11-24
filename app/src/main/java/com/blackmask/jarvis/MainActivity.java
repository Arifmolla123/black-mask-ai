package com.blackmask.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import java.util.Calendar;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BLACK MASK AI - voice + text personal assistant.
 * Runs in the background (JarvisService) so it keeps working when the
 * app is closed - only the internet being off stops the AI chat.
 * Wake word ("Hey Black Mask"), floating mic, home screen widget,
 * reminders, calculator, news, translate, cricket scores and
 * "did you mean" suggestions when something is not understood.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1002;
    private static final String PREFS = "jarvis";
    private static final int REQ_SOS_1 = 101, REQ_SOS_2 = 102, REQ_SOS_3 = 103;
    private static final int REQ_THEFT = 105;
    private static final int REQ_SMS_SCHEDULE = 104;
    private static final String KEY_API = "gemini_key";
    private static final String KEY_HISTORY = "chat_history";
    private static final String[] MODELS = {"gemini-3.1-flash-lite", "gemini-3.5-flash-lite"};

    private TextView chatLog;
    private ScrollView scroll;
    private EditText input;
    private EditText keyInput;
    private CheckBox ttsCheck;
    private TextView statusText;
    private TextView hudText;
    private OrbView orb;
    private LinearLayout chipRow;
    private Button chip1, chip2, chip3;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler hudHandler = new Handler(Looper.getMainLooper());
    private final Runnable hudRunnable = new Runnable() {
        @Override
        public void run() {
            updateHud();
            hudHandler.postDelayed(this, 30000);
        }
    };

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String who = intent.getStringExtra("who");
            String text = intent.getStringExtra("text");
            if (who != null && text != null) append(who, text);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        chatLog = findViewById(R.id.chatLog);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        keyInput = findViewById(R.id.keyInput);
        ttsCheck = findViewById(R.id.ttsCheck);
        statusText = findViewById(R.id.statusText);
        hudText = findViewById(R.id.hudText);
        orb = findViewById(R.id.orb);
        chipRow = findViewById(R.id.chipRow);
        chip1 = findViewById(R.id.chip1);
        chip2 = findViewById(R.id.chip2);
        chip3 = findViewById(R.id.chip3);
        Button sendBtn = findViewById(R.id.sendBtn);
        Button saveKeyBtn = findViewById(R.id.saveKeyBtn);
        Button keepAliveBtn = findViewById(R.id.keepAliveBtn);
        CheckBox floatCheck = findViewById(R.id.floatCheck);
        CheckBox briefCheck = findViewById(R.id.briefCheck);

        // Logo: your logo.png in app/src/main/res/drawable/
        ImageView logoView = findViewById(R.id.logoView);
        int logoId = getResources().getIdentifier("logo", "drawable", getPackageName());
        if (logoId != 0) {
            logoView.setImageResource(logoId);
            logoView.setVisibility(View.VISIBLE);
        } else {
            logoView.setVisibility(View.GONE);
        }

        keyInput.setText(prefs.getString(KEY_API, ""));
        ttsCheck.setChecked(true);
        floatCheck.setChecked(prefs.getBoolean("float_mic", false));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.getDefault());
        });

        sendBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (!text.isEmpty()) {
                input.setText("");
                handleUserText(text);
            }
        });
        saveKeyBtn.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            prefs.edit().putString(KEY_API, k).apply();
            Toast.makeText(this, k.isEmpty() ? getString(R.string.toast_key_removed) : getString(R.string.toast_key_saved), Toast.LENGTH_SHORT).show();
            append("BLACK MASK", "API key saved. You can talk to me now.");
        });
        // GET FREE KEY (v4.9.12): open Google AI Studio's key page in the
        // browser - the user copies the key, pastes it above, taps SAVE KEY.
        Button getKeyBtn = findViewById(R.id.getKeyBtn);
        getKeyBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://aistudio.google.com/apikey")));
            } catch (Exception ignored) {}
        });
        // MORE SETTINGS (v4.9.12): a second settings page - App Lock and
        // more - because this page was getting too long.
        Button moreSettingsBtn = findViewById(R.id.moreSettingsBtn);
        moreSettingsBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, MoreSettingsActivity.class));
            } catch (Exception ignored) {}
        });
        keepAliveBtn.setOnClickListener(v -> requestBatteryOptOut());

        // Home quick actions: tap the orb (or the Listen button) to talk,
        // SOS to send the emergency SMS, Flashlight to toggle the torch.
        View orb = findViewById(R.id.orb);
        View.OnClickListener listenClick = v -> {
            try {
                startService(new Intent(this, JarvisService.class).setAction(JarvisService.ACTION_LISTEN));
            } catch (Exception ignored) {}
        };
        orb.setOnClickListener(listenClick);
        Button homeListenBtn = findViewById(R.id.homeListenBtn);
        homeListenBtn.setOnClickListener(listenClick);
        Button homeSosBtn = findViewById(R.id.homeSosBtn);
        homeSosBtn.setOnClickListener(v -> {
            boolean later = CommandEngine.sendSosAuto(this, sent -> runOnUiThread(() -> {
                if (sent < 0) Toast.makeText(this, getString(R.string.home_sos_no_perm), Toast.LENGTH_LONG).show();
                else if (sent == 0) Toast.makeText(this, getString(R.string.home_sos_no_contact), Toast.LENGTH_LONG).show();
                else Toast.makeText(this, getString(R.string.home_sos_sent), Toast.LENGTH_SHORT).show();
            }));
            if (later) Toast.makeText(this, getString(R.string.home_sos_loc_on), Toast.LENGTH_LONG).show();
        });
        Button homeFlashBtn = findViewById(R.id.homeFlashBtn);
        homeFlashBtn.setOnClickListener(v -> {
            boolean on = CommandEngine.toggleFlashlight(this);
            Toast.makeText(this, getString(on ? R.string.home_flash_on : R.string.home_flash_off), Toast.LENGTH_SHORT).show();
        });
        floatCheck.setOnCheckedChangeListener((v, on) -> {
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.toast_overlay), Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                floatCheck.setChecked(false);
                return;
            }
            Intent i = new Intent(this, JarvisService.class)
                    .setAction(on ? JarvisService.ACTION_FLOAT_ON : JarvisService.ACTION_FLOAT_OFF);
            try { startService(i); } catch (Exception ignored) {}
        });

        // Morning briefing at 7 AM: time + weather + headlines in a notification.
        briefCheck.setChecked(prefs.getBoolean("briefing", false));
        briefCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("briefing", on).apply();
            CommandEngine.setMorningBriefing(this, on);
            Toast.makeText(this, on ? getString(R.string.toast_brief_on) : getString(R.string.toast_brief_off), Toast.LENGTH_SHORT).show();
        });

        // Auto-tap (Accessibility): lets the app press buttons for you -
        // Wi-Fi on/off, take a photo, screenshot, lock the phone.
        // WhatsApp is never automated - you always tap Send yourself.
        Button accessBtn = findViewById(R.id.accessBtn);
        Button appInfoBtn = findViewById(R.id.appInfoBtn);
        accessBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
        appInfoBtn.setOnClickListener(v -> {
            // Android 13+: a sideloaded APK needs "Allow restricted settings"
            // in App info first, or the accessibility switch stays greyed out.
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, getString(R.string.toast_restricted), Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });

        // AUTOMATION toggles (Settings list): each one switches an automation on/off.
        wireCheck(R.id.autoRingCheck, "auto_ring", true);
        wireCheck(R.id.autoLocCheck, "auto_loc", true);
        wireCheck(R.id.autoOtpCheck, "auto_otp", true);
        wireCheck(R.id.drivingCheck, "driving", false);
        wireCheck(R.id.missedSmsCheck, "missed_sms", true);
        wireCheck(R.id.readNotifCheck, "read_notif", false);
        wireCheck(R.id.screeningCheck, "screening", true);
        Button notifAccessBtn = findViewById(R.id.notifAccessBtn);
        notifAccessBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {}
        });

        // Emergency SOS: shake 3 times -> SOS SMS to the saved contacts.
        CheckBox sosCheck = findViewById(R.id.sosCheck);
        sosCheck.setChecked(prefs.getBoolean("sos_shake", false));
        sosCheck.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("sos_shake", on).apply();
            if (on) ensureBatteryExempt();
            try {
                startService(new Intent(this, JarvisService.class).setAction(JarvisService.ACTION_SOS_SHAKE));
            } catch (Exception ignored) {}
        });
        // SOS emergency contacts (3 slots): tap Select, pick a contact.
        setupSosPicker(R.id.sosPick1, REQ_SOS_1);
        setupSosPicker(R.id.sosPick2, REQ_SOS_2);
        setupSosPicker(R.id.sosPick3, REQ_SOS_3);
        refreshSosLabels();

        // Anti-theft (v4.9.7): power menu opens -> PIN gate. Needs auto-tap
        // (accessibility) on, a 4-digit PIN, and a trusted number that gets
        // the location SMS. Toggle needs a PIN first.
        CheckBox theftCheck = findViewById(R.id.antitheftCheck);
        theftCheck.setChecked(prefs.getBoolean("antitheft_on", false));
        theftCheck.setOnCheckedChangeListener((v, on) -> {
            if (on && prefs.getString("antitheft_pin", "").isEmpty()) {
                theftCheck.setChecked(false);
                Toast.makeText(this, getString(R.string.antitheft_need_pin), Toast.LENGTH_LONG).show();
                showPinDialog();
                return;
            }
            prefs.edit().putBoolean("antitheft_on", on).apply();
            if (on) ensureBatteryExempt();
            refreshAntiTheftLabels();
        });
        Button pinBtn = findViewById(R.id.antitheftPinBtn);
        pinBtn.setOnClickListener(v -> showPinDialog());
        setupSosPicker(R.id.antitheftPick, REQ_THEFT);
        refreshAntiTheftLabels();
        // No secure screen lock? The lock screen is only a swipe screen
        // then, and a thief can dismiss it - send the user to set one.
        Button lockBtn = findViewById(R.id.antitheftLockBtn);
        lockBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {}
        });

        // SMS permission status: RING / LOC / SOS all need it. If it is missing,
        // the toggles look on but nothing happens - this line shows the truth.
        Button smsPermBtn = findViewById(R.id.smsPermBtn);
        smsPermBtn.setOnClickListener(v -> {
            ArrayList<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.SEND_SMS);
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        });

        // Scheduled SMS (Tools page): pick a number, type the message, pick a time.
        Button smsPickBtn = findViewById(R.id.smsPickBtn);
        smsPickBtn.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_SMS_SCHEDULE);
            } catch (Exception ignored) {}
        });
        Button smsTimeBtn = findViewById(R.id.smsTimeBtn);
        smsTimeBtn.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            new TimePickerDialog(this, (view, hour, min) -> {
                prefs.edit().putInt("sms_hour", hour).putInt("sms_min", min).apply();
                refreshSmsScheduleLabels();
            }, prefs.getInt("sms_hour", now.get(Calendar.HOUR_OF_DAY)),
                    prefs.getInt("sms_min", now.get(Calendar.MINUTE)), false).show();
        });
        Button smsScheduleBtn = findViewById(R.id.smsScheduleBtn);
        smsScheduleBtn.setOnClickListener(v -> scheduleSms());
        Button smsCancelBtn = findViewById(R.id.smsCancelBtn);
        smsCancelBtn.setOnClickListener(v -> {
            cancelScheduledSms();
            Toast.makeText(this, getString(R.string.sms_cancelled), Toast.LENGTH_SHORT).show();
            refreshSmsScheduleLabels();
        });
        refreshSmsScheduleLabels();

        // Suggestion chips: tap one and its text runs as a command.
        View.OnClickListener chipClick = v -> {
            String text = ((Button) v).getText().toString();
            if (!text.isEmpty()) handleUserText(text);
        };
        chip1.setOnClickListener(chipClick);
        chip2.setOnClickListener(chipClick);
        chip3.setOnClickListener(chipClick);

        // Bottom navigation - Home / Chat / Tools / Settings pages.
        ViewFlipper pager = findViewById(R.id.pager);
        View navHome = findViewById(R.id.navHome);
        View navChat = findViewById(R.id.navChat);
        View navTools = findViewById(R.id.navTools);
        View navSettings = findViewById(R.id.navSettings);
        View[] navBtns = {navHome, navChat, navTools, navSettings};
        View.OnClickListener navClick = v -> {
            int page = v == navChat ? 1 : v == navTools ? 2 : v == navSettings ? 3 : 0;
            pager.setDisplayedChild(page);
            for (int i = 0; i < navBtns.length; i++) {
                navBtns[i].setSelected(i == page);
            }
        };
        navHome.setOnClickListener(navClick);
        navChat.setOnClickListener(navClick);
        navTools.setOnClickListener(navClick);
        navSettings.setOnClickListener(navClick);
        navHome.setSelected(true);
        // A voice command can open a page directly ("schedule sms" -> Tools).
        int openPage = getIntent().getIntExtra("page", 0);
        if (openPage > 0 && openPage < navBtns.length) {
            pager.setDisplayedChild(openPage);
            for (int i = 0; i < navBtns.length; i++) navBtns[i].setSelected(i == openPage);
        }

        // Voice language: English / Bengali / Hindi - the recognizer listens in this language.
        Spinner langSpinner = findViewById(R.id.langSpinner);
        String[] langs = {"English", "বাংলা", "हिन्दी"};
        String[] langTags = {"en-IN", "bn-IN", "hi-IN"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, langs);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langAdapter);
        String savedLang = prefs.getString("voice_lang", "en-IN");
        for (int i = 0; i < langTags.length; i++) {
            if (langTags[i].equals(savedLang)) { langSpinner.setSelection(i); break; }
        }
        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString("voice_lang", langTags[pos]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // App language: English (default) / Bengali / Hindi - the app's own
        // menus and buttons. No API, no internet - the translations live in
        // the app itself (res/values/strings.xml, values-bn, values-hi).
        Spinner appLangSpinner = findViewById(R.id.appLangSpinner);
        String[] appLangs = {"English", "বাংলা", "हिन्दी"};
        String[] appLangCodes = {"en", "bn", "hi"};
        ArrayAdapter<String> appLangAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, appLangs);
        appLangAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appLangSpinner.setAdapter(appLangAdapter);
        String savedAppLang = prefs.getString("app_lang", "en");
        for (int i = 0; i < appLangCodes.length; i++) {
            if (appLangCodes[i].equals(savedAppLang)) { appLangSpinner.setSelection(i); break; }
        }
        appLangSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String code = appLangCodes[pos];
                if (code.equals(prefs.getString("app_lang", "en"))) return;
                prefs.edit().putString("app_lang", code).apply();
                // Keep the voice in the same language, so "Bengali" means
                // everything Bengali (menus + voice). The voice spinner below
                // can still be changed on its own afterwards.
                String voiceTag = code.equals("bn") ? "bn-IN" : code.equals("hi") ? "hi-IN" : "en-IN";
                prefs.edit().putString("voice_lang", voiceTag).apply();
                for (int i = 0; i < langTags.length; i++) {
                    if (langTags[i].equals(voiceTag)) { langSpinner.setSelection(i); break; }
                }
                LocaleHelper.apply(MainActivity.this);
                // Restart the service so its notification and voice switch at once.
                try {
                    stopService(new Intent(MainActivity.this, JarvisService.class));
                    startService(new Intent(MainActivity.this, JarvisService.class));
                } catch (Exception ignored) {}
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        requestPermissionsIfNeeded();
        startBackgroundService();

        // Restore chat history, or show the welcome message on first run.
        String history = prefs.getString(KEY_HISTORY, "");
        if (!history.isEmpty()) {
            chatLog.setText(history);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } else {
            int wLang = CommandEngine.getPreferredLang(this);
            String welcome = wLang == 1
                    ? "হ্যালো, আমি Black Mask AI। নোটিফিকেশনে Listen চাপো, বা ফ্লোটিং মাইক চাপো, তারপর বলো — English, বাংলা বা हिन्दी-তে। অথবা নিচে লিখো।\nচেষ্টা করো: \"open youtube\", \"whatsapp Rahul I am coming\", \"call Maa\", \"set alarm 7 am\"।"
                    : wLang == 2
                    ? "नमस्ते, मैं Black Mask AI हूँ। नोटिफिकेशन में Listen दबाओ, या फ्लोटिंग माइक दबाओ, फिर बोलो — English, বাংলা या हिन्दी में। या नीचे लिखो।\nकोशिश करो: \"open youtube\", \"whatsapp Rahul I am coming\", \"call Maa\", \"set alarm 7 am\"।"
                    : "Hello, I am Black Mask AI. Tap Listen in the notification, or the floating mic, then speak — in English, বাংলা or हिन्दी. Or type below.\nTry: \"open youtube\", \"whatsapp Rahul I am coming\", \"call Maa\", \"set alarm 7 am\".";
            append("BLACK MASK", welcome);
        }
        showDefaultChips();

        // Boot sequence.
        setStatus("INITIALIZING SYSTEMS...");
        hudHandler.postDelayed(() -> setStatus("BLACK MASK ONLINE"), 900);
        hudHandler.postDelayed(() -> setStatus("Ready — running in background"), 1800);
        updateHud();
        hudHandler.postDelayed(hudRunnable, 30000);
    }

    /** Wire one AUTOMATION checkbox to a SharedPreferences key. */
    private void wireCheck(int id, String key, boolean def) {
        CheckBox cb = findViewById(id);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            // Automation only works if the phone lets the app run in the
            // background - ask for the battery exemption the moment a
            // toggle is switched on, or RING / LOC / SOS stay silent.
            if (on) ensureBatteryExempt();
        });
    }

    /** Ask for the "run without restrictions" battery permission, if not granted. */
    private void ensureBatteryExempt() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                requestBatteryOptOut();
            }
        } catch (Exception ignored) {}
    }

    /** One SOS contact slot: the Select button opens the system contact picker. */
    private void setupSosPicker(int btnId, int req) {
        Button b = findViewById(btnId);
        b.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI), req);
            } catch (Exception ignored) {}
        });
    }

    /** Next occurrence of the picked hour/minute (tomorrow if it already passed today). */
    private long nextSmsTime() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, prefs.getInt("sms_hour", -1));
        c.set(Calendar.MINUTE, prefs.getInt("sms_min", 0));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    /** Schedule the SMS: number + message + time, fired by ScheduledSmsReceiver. */
    private void scheduleSms() {
        String num = prefs.getString("sms_num", "");
        String msg = ((EditText) findViewById(R.id.smsMessage)).getText().toString().trim();
        if (num.isEmpty()) {
            Toast.makeText(this, getString(R.string.sms_no_contact), Toast.LENGTH_SHORT).show();
            return;
        }
        if (msg.isEmpty()) {
            Toast.makeText(this, getString(R.string.sms_no_message), Toast.LENGTH_SHORT).show();
            return;
        }
        if (prefs.getInt("sms_hour", -1) < 0) {
            Toast.makeText(this, getString(R.string.sms_no_time_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        long when = nextSmsTime();
        Intent i = new Intent(this, ScheduledSmsReceiver.class);
        i.putExtra("num", num);
        i.putExtra("msg", msg);
        i.putExtra("name", prefs.getString("sms_name", ""));
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        prefs.edit().putBoolean("sms_scheduled", true).putString("sms_msg", msg)
                .putLong("sms_when", when).apply();
        Toast.makeText(this, getString(R.string.sms_scheduled), Toast.LENGTH_SHORT).show();
        refreshSmsScheduleLabels();
    }

    /** Cancel the scheduled SMS (if any). */
    private void cancelScheduledSms() {
        Intent i = new Intent(this, ScheduledSmsReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        prefs.edit().putBoolean("sms_scheduled", false).apply();
    }

    /** Show the scheduled-SMS card state (number, time, and the current schedule). */
    private void refreshSmsScheduleLabels() {
        TextView label = findViewById(R.id.smsLabel);
        String name = prefs.getString("sms_name", "");
        String num = prefs.getString("sms_num", "");
        label.setText(name.isEmpty() ? getString(R.string.sms_not_set) : name + " • " + num);
        TextView timeLabel = findViewById(R.id.smsTimeLabel);
        int hour = prefs.getInt("sms_hour", -1);
        int min = prefs.getInt("sms_min", 0);
        if (hour < 0) {
            timeLabel.setText(getString(R.string.sms_no_time));
        } else {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, min);
            timeLabel.setText(f.format(c.getTime()));
        }
        TextView status = findViewById(R.id.smsStatus);
        if (prefs.getBoolean("sms_scheduled", false)) {
            String when = new java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
                    .format(new java.util.Date(prefs.getLong("sms_when", 0)));
            String who = name.isEmpty() ? num : name;
            status.setText(getString(R.string.sms_scheduled) + ": " + who + " • " + when);
        } else {
            status.setText("");
        }
    }

    /** Show the saved SOS contact names/numbers (or "Not set"). */
    private void refreshSosLabels() {
        setSosLabel(R.id.sosLabel1, "sos_name_1", "sos_num_1");
        setSosLabel(R.id.sosLabel2, "sos_name_2", "sos_num_2");
        setSosLabel(R.id.sosLabel3, "sos_name_3", "sos_num_3");
    }

    /** Anti-theft PIN dialog: 4 digits, saved in SharedPreferences. */
    private void showPinDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        input.setHint(getString(R.string.antitheft_pin_hint));
        String saved = prefs.getString("antitheft_pin", "");
        if (!saved.isEmpty()) input.setText(saved);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.antitheft_pin_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 4 || pin.length() > 8) {
                        Toast.makeText(this, getString(R.string.antitheft_pin_wrong),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("antitheft_pin", pin).apply();
                    Toast.makeText(this, getString(R.string.antitheft_pin_set),
                            Toast.LENGTH_SHORT).show();
                    refreshAntiTheftLabels();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Anti-theft status: trusted number label + ON/OFF line + PIN button text. */
    private void refreshAntiTheftLabels() {
        TextView label = findViewById(R.id.antitheftLabel);
        String name = prefs.getString("antitheft_name", "");
        String num = prefs.getString("antitheft_num", "");
        label.setText(name.isEmpty() ? getString(R.string.antitheft_not_set) : name + " • " + num);
        TextView st = findViewById(R.id.antitheftStatus);
        boolean on = prefs.getBoolean("antitheft_on", false);
        boolean ready = !prefs.getString("antitheft_pin", "").isEmpty() && !num.isEmpty();
        st.setText(getString(on ? R.string.antitheft_status_on : R.string.antitheft_status_off));
        st.setTextColor(on && ready ? 0xFF4CAF50 : 0xFFFF2A3C);
        Button pinBtn = findViewById(R.id.antitheftPinBtn);
        pinBtn.setText(getString(prefs.getString("antitheft_pin", "").isEmpty()
                ? R.string.antitheft_set_pin : R.string.antitheft_pin_change));
        // Warn if the phone has no secure screen lock (PIN/pattern):
        // without it the lock screen is only a swipe screen, and a thief
        // can dismiss it with Home. The app cannot set a system lock -
        // the user must set one in Settings.
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean secure = km != null && km.isDeviceSecure();
        TextView warn = findViewById(R.id.antitheftLockWarn);
        Button lockBtn = findViewById(R.id.antitheftLockBtn);
        warn.setVisibility(secure ? View.GONE : View.VISIBLE);
        lockBtn.setVisibility(secure ? View.GONE : View.VISIBLE);
    }

    private void setSosLabel(int labelId, String nameKey, String numKey) {
        TextView t = findViewById(labelId);
        String name = prefs.getString(nameKey, "");
        String num = prefs.getString(numKey, "");
        t.setText(name.isEmpty() ? getString(R.string.sos_not_set) : name + " • " + num);
    }

    /** The contact the user picked for an SOS slot. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_THEFT) {
            try (Cursor c = getContentResolver().query(data.getData(),
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    String num = c.getString(1);
                    prefs.edit().putString("antitheft_name", name != null ? name : "")
                            .putString("antitheft_num", num != null ? num : "").apply();
                    refreshAntiTheftLabels();
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
            return;
        }
        if (requestCode == REQ_SMS_SCHEDULE) {
            try (Cursor c = getContentResolver().query(data.getData(),
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    String num = c.getString(1);
                    prefs.edit().putString("sms_name", name != null ? name : "")
                            .putString("sms_num", num != null ? num : "").apply();
                    refreshSmsScheduleLabels();
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
            return;
        }
        int slot = requestCode == REQ_SOS_1 ? 1 : requestCode == REQ_SOS_2 ? 2 : requestCode == REQ_SOS_3 ? 3 : 0;
        if (slot == 0) return;
        try (Cursor c = getContentResolver().query(data.getData(),
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                String num = c.getString(1);
                prefs.edit().putString("sos_name_" + slot, name != null ? name : "")
                        .putString("sos_num_" + slot, num != null ? num : "").apply();
                refreshSosLabels();
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }

    /** Is notification access granted for this app? */
    private boolean isNotificationAccessEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh the auto-tap (accessibility) status line.
        TextView accessStatus = findViewById(R.id.accessStatus);
        if (accessStatus != null) {
            boolean on = BlackMaskAccessibilityService.isEnabled(this);
            accessStatus.setText(on ? getString(R.string.autotap_on) : getString(R.string.autotap_off));
            accessStatus.setTextColor(on ? 0xFF4CAF50 : 0xFF9AA5B1);
        }
        // Refresh the SMS permission status line.
        TextView smsPermStatus = findViewById(R.id.smsPermStatus);
        if (smsPermStatus != null) {
            boolean on = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
            smsPermStatus.setText(on ? getString(R.string.sms_perm_on) : getString(R.string.sms_perm_off));
            smsPermStatus.setTextColor(on ? 0xFF4CAF50 : 0xFFFF2A3C);
        }
        // Refresh the "last SMS received" status line.
        TextView smsLastStatus = findViewById(R.id.smsLastStatus);
        if (smsLastStatus != null) {
            long when = prefs.getLong("last_sms_time", 0);
            if (when == 0) {
                smsLastStatus.setText(getString(R.string.sms_last_none));
                smsLastStatus.setTextColor(0xFF9AA5B1);
            } else {
                String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(when));
                String from = prefs.getString("last_sms_from", "");
                boolean found = prefs.getBoolean("last_sms_blackmask", false);
                smsLastStatus.setText(getString(found ? R.string.sms_last_yes : R.string.sms_last_no, time, from));
                smsLastStatus.setTextColor(found ? 0xFF4CAF50 : 0xFFFF2A3C);
            }
        }
        // Refresh the notification-access status line.
        TextView notifStatus = findViewById(R.id.notifStatus);
        if (notifStatus != null) {
            boolean on = isNotificationAccessEnabled();
            notifStatus.setText(on ? getString(R.string.notif_on) : getString(R.string.notif_off));
            notifStatus.setTextColor(on ? 0xFF4CAF50 : 0xFF9AA5B1);
        }
        // Refresh the anti-theft status line.
        refreshAntiTheftLabels();
        IntentFilter f = new IntentFilter(JarvisService.BROADCAST_LOG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, f);
        }
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(logReceiver); } catch (Exception ignored) {}
        super.onPause();
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_CONTACTS);
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CALL_PHONE);
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECEIVE_SMS);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_PHONE_STATE);
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void startBackgroundService() {
        try {
            Intent i = new Intent(this, JarvisService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_service_fail), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateHud() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String hud = getString(R.string.home_hud_bat) + " " + (bat >= 0 ? bat + "%" : "--") + "  •  " + time + "  •  " + getString(R.string.home_hud_online);
        runOnUiThread(() -> hudText.setText(hud));
    }

    private void requestBatteryOptOut() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                Toast.makeText(this, getString(R.string.toast_battery), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    // ---------- chat + suggestions ----------

    private void setStatus(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    private void append(String who, String text) {
        runOnUiThread(() -> {
            if ("__CLEAR__".equals(text)) {
                chatLog.setText("");
                saveHistory();
                return;
            }
            chatLog.append("\n" + who + ": " + text + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            saveHistory();
        });
    }

    private void saveHistory() {
        String log = chatLog.getText().toString();
        if (log.length() > 40000) log = log.substring(log.length() - 40000);
        prefs.edit().putString(KEY_HISTORY, log).apply();
    }

    private void showSuggestions(List<String> list) {
        runOnUiThread(() -> {
            if (list == null || list.isEmpty()) { chipRow.setVisibility(View.GONE); return; }
            chip1.setText(list.size() > 0 ? list.get(0) : "");
            chip2.setText(list.size() > 1 ? list.get(1) : "");
            chip3.setText(list.size() > 2 ? list.get(2) : "");
            chip1.setVisibility(list.size() > 0 ? View.VISIBLE : View.GONE);
            chip2.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);
            chip3.setVisibility(list.size() > 2 ? View.VISIBLE : View.GONE);
            chipRow.setVisibility(View.VISIBLE);
        });
    }

    private void showDefaultChips() {
        List<String> def = new ArrayList<>();
        def.add("open youtube");
        def.add("weather");
        def.add("help");
        showSuggestions(def);
    }

    private void speak(String text) {
        if (ttsCheck.isChecked() && tts != null) {
            tts.setLanguage(ttsLocaleFor(text));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        }
    }

    /** TTS voice follows the script of the reply: Bengali, Hindi or English. */
    private static Locale ttsLocaleFor(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') return new Locale("bn", "IN");
            if (c >= '\u0900' && c <= '\u097F') return new Locale("hi", "IN");
        }
        return Locale.ENGLISH;
    }

    private void handleUserText(String text) {
        append("You", text);
        boolean handled = CommandEngine.handle(this, text, reply -> {
            if ("__CLEAR__".equals(reply)) { append("BLACK MASK", "__CLEAR__"); return; }
            append("BLACK MASK", reply);
            speak(reply);
        });
        if (handled) return;
        if (!CommandEngine.isOnline(this)) {
            String msg = "Internet is off. I can still do local things — time, alarms, open apps, notes, calculator. Turn on the net for AI chat, weather, news and translate.";
            append("BLACK MASK", msg);
            speak(msg);
            showSuggestions(CommandEngine.getSuggestions(text));
            return;
        }
        String key = prefs.getString(KEY_API, "");
        if (key.isEmpty()) {
            // No key? Chat with the free keyless AI instead - zero setup.
            setStatus("Thinking...");
            append("BLACK MASK", "Thinking...");
            net.execute(() -> {
                String reply = CommandEngine.freeChat(this, text);
                runOnUiThread(() -> {
                    String log = chatLog.getText().toString();
                    log = log.replace("\nBLACK MASK: Thinking...\n", "");
                    chatLog.setText(log);
                    saveHistory();
                });
                if (reply == null) {
                    reply = CommandEngine.aiFailMessage(this, text);
                }
                append("BLACK MASK", reply);
                speak(reply);
                setStatus("Ready — running in background");
            });
            return;
        }
        setStatus("Thinking...");
        append("BLACK MASK", "Thinking...");
        net.execute(() -> {
            String reply = askGemini(text, key);
            runOnUiThread(() -> {
                String log = chatLog.getText().toString();
                log = log.replace("\nBLACK MASK: Thinking...\n", "");
                chatLog.setText(log);
                saveHistory();
            });
            append("BLACK MASK", reply);
            speak(reply);
            setStatus("Ready — running in background");
            if (reply.startsWith("Sorry, I could not reach Gemini")) {
                showSuggestions(CommandEngine.getSuggestions(text));
            }
        });
    }

    // ---------- Gemini ----------

    private String askGemini(String prompt, String apiKey) {
        String fullPrompt = CommandEngine.aiSystemPrompt(this, prompt) + "\n\nUser: " + prompt;
        for (String model : MODELS) {
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", fullPrompt));
                content.put("parts", parts);
                contents.put(content);
                body.put("contents", contents);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                if (code != 200) continue; // try the next model
                JSONObject res = new JSONObject(sb.toString());
                String out = res.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text");
                return out.trim();
            } catch (Exception e) {
                // Try the next model.
            }
        }
        return "Sorry, I could not reach Gemini. Check your API key and internet, then try again.";
    }

    // ---------- voice input ----------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onDestroy() {
        hudHandler.removeCallbacks(hudRunnable);
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }
}
