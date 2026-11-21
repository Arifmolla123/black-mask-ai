package com.blackmask.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps Black Mask AI alive in the background.
 * A persistent notification lets the user tap "Listen" any time -
 * even when the app is closed - and the assistant speaks the answer.
 * Wake-word mode ("Hey Black Mask") keeps listening for the wake
 * phrase, and the floating mic bubble works on top of other apps.
 * Same commands as the app: open apps, WhatsApp by name, calls,
 * alarms, timers, reminders, weather, news, calculator, translate,
 * notes, jokes and Gemini chat.
 */
public class JarvisService extends Service {

    public static final String ACTION_LISTEN = "com.blackmask.jarvis.LISTEN";
    public static final String ACTION_STOP_LISTEN = "com.blackmask.jarvis.STOP_LISTEN";
    public static final String ACTION_WAKE_ON = "com.blackmask.jarvis.WAKE_ON";
    public static final String ACTION_WAKE_OFF = "com.blackmask.jarvis.WAKE_OFF";
    public static final String ACTION_FLOAT_ON = "com.blackmask.jarvis.FLOAT_ON";
    public static final String ACTION_FLOAT_OFF = "com.blackmask.jarvis.FLOAT_OFF";
    public static final String BROADCAST_LOG = "com.blackmask.jarvis.LOG";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "jarvis_channel";
    private static final String[] MODELS = {"gemini-3.1-flash-lite", "gemini-3.5-flash-lite"};

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;
    private boolean wakeMode = false;
    private boolean wakeListening = false; // true while waiting for the wake phrase
    private View floatView;
    private WindowManager windowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.getDefault());
        });
        startForeground(NOTIF_ID, buildNotification("Ready. Tap Listen and speak."));
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        if (p.getBoolean("wake_word", false)) startWakeLoop();
        if (p.getBoolean("float_mic", false)) showFloatMic();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_LISTEN.equals(a)) startListening();
            else if (ACTION_STOP_LISTEN.equals(a)) stopListening();
            else if (ACTION_WAKE_ON.equals(a)) startWakeLoop();
            else if (ACTION_WAKE_OFF.equals(a)) stopWakeLoop();
            else if (ACTION_FLOAT_ON.equals(a)) showFloatMic();
            else if (ACTION_FLOAT_OFF.equals(a)) hideFloatMic();
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away - restart the service so it keeps running.
        try {
            Intent restart = new Intent(this, JarvisService.class);
            PendingIntent pi = PendingIntent.getService(
                    this, 0, restart, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Black Mask AI", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Black Mask AI background assistant");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent listen = new Intent(this, JarvisService.class).setAction(ACTION_LISTEN);
        PendingIntent listenPi = PendingIntent.getService(
                this, 1, listen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Black Mask AI")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_btn_speak_now, "Listen", listenPi).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---------- listening (one-shot and wake-word loop) ----------

    private void startListening() {
        startListeningInternal(false);
    }

    /** Wake word in any language - English, Bengali, Hindi, Hinglish. */
    private static boolean isWakeWord(String heard) {
        String low = heard.toLowerCase(Locale.ENGLISH);
        String[] words = {
                "black mask", "jarvis", "mask",
                "ব্ল্যাক মাস্ক", "ব্লাক মাস্ক", "মাস্ক", "জার্ভিস", "ব্ল্যাক",
                "ब्लैक मास्क", "मास्क", "जार्विस", "ब्लैक",
                "hey black", "hello black", "হেই ব্ল্যাক", "হ্যালো ব্ল্যাক", "এই ব্ল্যাক",
                "sun black", "suno black", "oye black", "are black"
        };
        for (String w : words) {
            if (low.contains(w)) return true;
        }
        return false;
    }

    private void startWakeLoop() {
        wakeMode = true;
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("wake_word", true).apply();
        updateNotification("Wake word on — call me in any language.");
        startListeningInternal(true);
    }

    private void stopWakeLoop() {
        wakeMode = false;
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("wake_word", false).apply();
        stopListening();
        updateNotification("Ready. Tap Listen and speak.");
    }

    private void startListeningInternal(final boolean wake) {
        if (listening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this device.");
            return;
        }
        try {
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            wakeListening = wake;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String heard = (list != null && !list.isEmpty()) ? list.get(0) : "";
                    if (wakeListening) {
                        if (isWakeWord(heard)) {
                            wakeListening = false;
                            speak("Yes?");
                            updateNotification("Listening for your command...");
                            handler.postDelayed(() -> startListeningInternal(false), 1200);
                        } else {
                            restartWakeListen();
                        }
                    } else {
                        updateNotification("Ready. Tap Listen and speak.");
                        if (!heard.isEmpty()) handleHeard(heard);
                        if (wakeMode) restartWakeListen();
                    }
                }
                @Override public void onError(int error) {
                    listening = false;
                    if (wakeMode) restartWakeListen();
                    else updateNotification("Ready. Tap Listen and speak.");
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
            listening = true;
            if (!wake) updateNotification("Listening...");
        } catch (Exception e) {
            listening = false;
            if (wakeMode) restartWakeListen();
        }
    }

    private void restartWakeListen() {
        handler.postDelayed(() -> {
            if (wakeMode && !listening) startListeningInternal(true);
        }, 800);
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
        updateNotification("Ready. Tap Listen and speak.");
    }

    // ---------- floating mic bubble ----------

    private void showFloatMic() {
        if (floatView != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            speak("Please allow the overlay permission for the floating mic.");
            return;
        }
        try {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("float_mic", true).apply();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            ImageView bubble = new ImageView(this);
            bubble.setImageResource(R.drawable.logo);
            int size = (int) (64 * getResources().getDisplayMetrics().density);
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 200;
            final int[] lastX = {0};
            final int[] lastY = {0};
            final boolean[] moved = {false};
            bubble.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX[0] = (int) event.getRawX();
                        lastY[0] = (int) event.getRawY();
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - lastX[0];
                        int dy = (int) event.getRawY() - lastY[0];
                        if (Math.abs(dx) + Math.abs(dy) > 10) moved[0] = true;
                        params.x += dx;
                        params.y += dy;
                        lastX[0] = (int) event.getRawX();
                        lastY[0] = (int) event.getRawY();
                        windowManager.updateViewLayout(floatView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved[0]) startListening();
                        return true;
                }
                return false;
            });
            floatView = bubble;
            windowManager.addView(floatView, params);
        } catch (Exception e) {
            floatView = null;
        }
    }

    private void hideFloatMic() {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("float_mic", false).apply();
        if (floatView != null && windowManager != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
    }

    // ---------- handling what was heard ----------

    private void handleHeard(String text) {
        sendLog("You", text);
        updateNotification("Heard: " + text);
        boolean handled = CommandEngine.handle(this, text, reply -> {
            if ("__CLEAR__".equals(reply)) return;
            sendLog("JARVIS", reply);
            speak(reply);
        });
        if (handled) {
            updateNotification("Ready. Tap Listen and speak.");
            return;
        }
        if (!CommandEngine.isOnline(this)) {
            String msg = "Internet is off. I can still do local things — time, alarms, open apps, notes. Turn on the net for AI chat.";
            sendLog("JARVIS", msg);
            speak(msg);
            updateNotification("Ready. Tap Listen and speak.");
            return;
        }
        SharedPreferences prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        String key = prefs.getString("gemini_key", "");
        if (key.isEmpty()) {
            String msg = "Add your Gemini API key in the app first.";
            sendLog("JARVIS", msg);
            speak(msg);
            updateNotification("Ready. Tap Listen and speak.");
            return;
        }
        net.execute(() -> {
            String reply = askGemini(text, key);
            sendLog("JARVIS", reply);
            speak(reply);
            updateNotification("Ready. Tap Listen and speak.");
        });
    }

    private void sendLog(String who, String text) {
        Intent i = new Intent(BROADCAST_LOG);
        i.putExtra("who", who);
        i.putExtra("text", text);
        sendBroadcast(i);
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
    }

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
                if (code != 200) continue;
                JSONObject res = new JSONObject(sb.toString());
                return res.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text").trim();
            } catch (Exception e) {
                // Try the next model.
            }
        }
        return "Sorry, I could not reach Gemini. Check your API key and internet.";
    }

    @Override
    public void onDestroy() {
        hideFloatMic();
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }
}
