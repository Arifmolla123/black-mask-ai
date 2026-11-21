package com.blackmask.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private SpeechRecognizer recognizer;
    private SharedPreferences prefs;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean listening = false;
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
        Button micBtn = findViewById(R.id.micBtn);
        Button saveKeyBtn = findViewById(R.id.saveKeyBtn);
        Button keepAliveBtn = findViewById(R.id.keepAliveBtn);
        CheckBox wakeCheck = findViewById(R.id.wakeCheck);
        CheckBox floatCheck = findViewById(R.id.floatCheck);

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
        wakeCheck.setChecked(prefs.getBoolean("wake_word", false));
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
        micBtn.setOnClickListener(v -> startListening());
        saveKeyBtn.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            prefs.edit().putString(KEY_API, k).apply();
            Toast.makeText(this, k.isEmpty() ? "API key removed" : "API key saved", Toast.LENGTH_SHORT).show();
            append("JARVIS", "API key saved. You can talk to me now.");
        });
        keepAliveBtn.setOnClickListener(v -> requestBatteryOptOut());
        wakeCheck.setOnCheckedChangeListener((v, on) -> {
            Intent i = new Intent(this, JarvisService.class)
                    .setAction(on ? JarvisService.ACTION_WAKE_ON : JarvisService.ACTION_WAKE_OFF);
            try { startService(i); } catch (Exception ignored) {}
            Toast.makeText(this, on ? "Wake word on — call me in any language" : "Wake word off", Toast.LENGTH_SHORT).show();
        });
        floatCheck.setOnCheckedChangeListener((v, on) -> {
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'Draw over other apps' first", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                floatCheck.setChecked(false);
                return;
            }
            Intent i = new Intent(this, JarvisService.class)
                    .setAction(on ? JarvisService.ACTION_FLOAT_ON : JarvisService.ACTION_FLOAT_OFF);
            try { startService(i); } catch (Exception ignored) {}
        });

        View.OnClickListener chipClick = v -> {
            String text = ((Button) v).getText().toString();
            handleUserText(text);
        };
        chip1.setOnClickListener(chipClick);
        chip2.setOnClickListener(chipClick);
        chip3.setOnClickListener(chipClick);

        requestPermissionsIfNeeded();
        startBackgroundService();

        // Restore chat history, or show the welcome message on first run.
        String history = prefs.getString(KEY_HISTORY, "");
        if (!history.isEmpty()) {
            chatLog.setText(history);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } else {
            append("JARVIS", "Hello, I am Black Mask AI. Tap the mic and speak, or type below.\nTry: \"open youtube\", \"whatsapp Rahul I am coming\", \"call Maa\", \"set alarm 7 am\".");
        }
        showDefaultChips();

        // Boot sequence.
        setStatus("INITIALIZING SYSTEMS...");
        hudHandler.postDelayed(() -> setStatus("BLACK MASK ONLINE"), 900);
        hudHandler.postDelayed(() -> setStatus("Ready — running in background"), 1800);
        updateHud();
        hudHandler.postDelayed(hudRunnable, 30000);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            Toast.makeText(this, "Background service could not start", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateHud() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String hud = "BAT " + (bat >= 0 ? bat + "%" : "--") + "  •  " + time + "  •  SYSTEM ONLINE";
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
                Toast.makeText(this, "Already set to run without restrictions", Toast.LENGTH_SHORT).show();
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
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        }
    }

    private void handleUserText(String text) {
        append("You", text);
        boolean handled = CommandEngine.handle(this, text, reply -> {
            if ("__CLEAR__".equals(reply)) { append("JARVIS", "__CLEAR__"); return; }
            append("JARVIS", reply);
            speak(reply);
        });
        if (handled) return;
        if (!CommandEngine.isOnline(this)) {
            String msg = "Internet is off. I can still do local things — time, alarms, open apps, notes, calculator. Turn on the net for AI chat, weather, news and translate.";
            append("JARVIS", msg);
            speak(msg);
            showSuggestions(CommandEngine.getSuggestions(text));
            return;
        }
        String key = prefs.getString(KEY_API, "");
        if (key.isEmpty()) {
            String msg = "Add your free Gemini API key above first (get one at aistudio.google.com/apikey), then ask me anything. Meanwhile I can do local things — try one below.";
            append("JARVIS", msg);
            speak(msg);
            showSuggestions(CommandEngine.getSuggestions(text));
            return;
        }
        setStatus("Thinking...");
        append("JARVIS", "Thinking...");
        net.execute(() -> {
            String reply = askGemini(text, key);
            runOnUiThread(() -> {
                String log = chatLog.getText().toString();
                log = log.replace("\nJARVIS: Thinking...\n", "");
                chatLog.setText(log);
                saveHistory();
            });
            append("JARVIS", reply);
            speak(reply);
            setStatus("Ready — running in background");
            if (reply.startsWith("Sorry, I could not reach Gemini")) {
                showSuggestions(CommandEngine.getSuggestions(text));
            }
        });
    }

    // ---------- Gemini ----------

    private String askGemini(String prompt, String apiKey) {
        String fullPrompt = "You are Black Mask AI, a helpful personal AI assistant built by Arif. "
                + "Reply in the same language the user speaks (Bengali, Hinglish or English). "
                + "Keep replies short and useful.\n\nUser: " + prompt;
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

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    setListeningUi(false);
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) handleUserText(list.get(0));
                    else showSuggestions(CommandEngine.getSuggestions(""));
                }
                @Override public void onError(int error) {
                    setListeningUi(false);
                    append("JARVIS", "I didn't catch that. Try one of these:");
                    showSuggestions(CommandEngine.getSuggestions(""));
                }
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle p) {}
                @Override public void onEvent(int e, Bundle p) {}
            });
            recognizer.startListening(intent);
            setListeningUi(true);
            Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            setListeningUi(false);
            Toast.makeText(this, "Could not start listening", Toast.LENGTH_SHORT).show();
        }
    }

    private void setListeningUi(boolean on) {
        listening = on;
        runOnUiThread(() -> {
            orb.setListening(on);
            setStatus(on ? "Listening..." : "Ready — running in background");
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onDestroy() {
        hudHandler.removeCallbacks(hudRunnable);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }
}
