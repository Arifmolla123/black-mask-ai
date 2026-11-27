package com.blackmask.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.Manifest;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.LinearLayout;

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
    public static final String ACTION_LIVE_START = "com.blackmask.jarvis.LIVE_START";
    public static final String ACTION_LIVE_STOP = "com.blackmask.jarvis.LIVE_STOP";
    public static final String ACTION_FLOAT_ON = "com.blackmask.jarvis.FLOAT_ON";
    public static final String ACTION_FLOAT_OFF = "com.blackmask.jarvis.FLOAT_OFF";
    public static final String ACTION_STOP_RING = "com.blackmask.jarvis.STOP_RING";
    public static final String ACTION_SPEAK = "com.blackmask.jarvis.SPEAK";
    /** Speak a line aloud from anywhere (used by the notification reader). */
    public static void speakText(Context ctx, String text) {
        try {
            Intent i = new Intent(ctx, JarvisService.class).setAction(ACTION_SPEAK);
            i.putExtra("text", text);
            ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static final String ACTION_SOS_SHAKE = "com.blackmask.jarvis.SOS_SHAKE";
    public static final String ACTION_SEND_LOCATION = "com.blackmask.jarvis.SEND_LOCATION";

    // Emergency SOS: shake the phone 3 times within 3 seconds and an SOS SMS
    // with the location goes to the emergency contacts (picked in Settings).
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener shakeListener;
    private boolean sosShakeOn = false;
    private int shakeCount = 0;
    private long firstShakeTime = 0;
    private long lastShakeTime = 0;
    private long lastSosTime = 0;

    /** (Re)register the accelerometer listener from the "sos_shake" toggle. */
    private void updateSosShake() {
        boolean on = getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("sos_shake", false);
        if (on == sosShakeOn) return;
        sosShakeOn = on;
        if (sensorManager == null) sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;
        if (accelerometer == null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) return;
        if (on) {
            if (shakeListener == null) {
                shakeListener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        float x = event.values[0], y = event.values[1], z = event.values[2];
                        float mag = (float) Math.sqrt(x * x + y * y + z * z);
                        long now = SystemClock.elapsedRealtime();
                        if (mag > 25 && now - lastShakeTime > 300) {
                            lastShakeTime = now;
                            if (now - firstShakeTime > 3000) {
                                shakeCount = 0;
                                firstShakeTime = now;
                            }
                            shakeCount++;
                            if (shakeCount >= 3 && now - lastSosTime > 60000) {
                                lastSosTime = now;
                                shakeCount = 0;
                                boolean later = CommandEngine.sendSosAuto(JarvisService.this, sent -> {
                                    int lang = CommandEngine.getPreferredLang(JarvisService.this);
                                    String text = sent < 0
                                            ? (lang == 1 ? "SMS পারমিশন দেওয়া নেই।" : lang == 2 ? "SMS परमिशन नहीं दी गई है।" : "SMS permission is missing.")
                                            : sent > 0
                                            ? (lang == 1 ? "SOS পাঠানো হয়েছে।" : lang == 2 ? "SOS भेजा गया है।" : "SOS sent.")
                                            : null;
                                    if (text != null) {
                                        new Handler(Looper.getMainLooper()).post(() -> speak(text));
                                    }
                                });
                                if (later) {
                                    int lang = CommandEngine.getPreferredLang(JarvisService.this);
                                    speak(lang == 1 ? "লোকেশন চালু করছি, SOS পাঠানো হচ্ছে।" : lang == 2 ? "लोकेशन चालू कर रहा हूँ, SOS भेजा जा रहा है।" : "Turning location on, sending SOS.");
                                }
                            }
                        }
                    }
                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
                };
            }
            sensorManager.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(shakeListener);
            shakeCount = 0;
        }
    }
    public static final String BROADCAST_LOG = "com.blackmask.jarvis.LOG";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "jarvis_channel";
    private static final String[] MODELS = {"gemini-3.1-flash-lite", "gemini-3.5-flash-lite"};

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;
    // Gemini Live voice (v4.9.31): one open audio line, like MYRAA.
    private GeminiLiveVoice liveVoice;
    public static volatile boolean liveRunning = false;
    // (v4.9.35) True only once the server accepted the session
    // (setupComplete). A mic tap while connecting must NOT kill the
    // session - on a slow network the user taps again thinking
    // nothing happened, and the tap itself stops the call.
    public static volatile boolean liveReady = false;
    // Continuous mic (v4.9.29, MYRAA-style): the mic stays on after
    // one tap, restarting each session until the user stops it.
    // (Retry: the first build failed only because Maven Central
    // answered 429 Too Many Requests - no code error.)
    private boolean continuousStop = false;
    // True while the app itself is speaking (plus a beat after):
    // the mic ignores its own voice in continuous mode.
    private volatile boolean speaking = false;
    private boolean voiceWarned = false;

    private boolean isContinuous() {
        return getSharedPreferences("jarvis", MODE_PRIVATE)
                .getBoolean("continuous_mic", false);
    }

    /** The recognizer plays a "ding" every time a session starts
     * (v4.9.30): in continuous mode that meant a beep every few
     * seconds, which is the on/off noise. Mute the music/system
     * streams briefly around the start - the beep plays on one of
     * them on most phones. Always unmuted right after. */
    private void muteBeep() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, true);
            am.setStreamMute(android.media.AudioManager.STREAM_SYSTEM, true);
            handler.postDelayed(() -> unmuteBeep(), 2000);
        } catch (Exception ignored) {}
    }
    private void unmuteBeep() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, false);
            am.setStreamMute(android.media.AudioManager.STREAM_SYSTEM, false);
        } catch (Exception ignored) {}
    }

    /** After a session ends, start the next one so the mic stays on
     * until the user stops it. No-op in tap-to-talk mode. */
    private void restartIfContinuous() {
        if (!isContinuous() || continuousStop) return;
        handler.postDelayed(() -> {
            if (continuousStop) return;
            startListeningInternal();
        }, 500);
    }

    /** Strip a leading wake word ("Hey Black Mask", ...) so the
     * command in the same breath still runs - the old one-breath
     * bug, fixed. Returns the text unchanged when there is none. */
    private static String stripWakeWord(String text) {
        if (text == null) return null;
        String t = text.trim();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        String[] wakes = {"hey black mask", "black mask", "ok black mask",
                "hei black mask", "হেই ব্ল্যাক মাস্ক", "হে ব্ল্যাক মাস্ক", "ব্ল্যাক মাস্ক"};
        for (String w : wakes) {
            if (lower.startsWith(w)) {
                return t.substring(w.length()).replaceFirst("^[ ,।]+", "").trim();
            }
        }
        return t;
    }
    private View floatView;
    private WindowManager windowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        LocaleHelper.apply(this);
        createChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                // Normal speed (v4.9.28): 1.25x was fast but less
                // clear - clarity first, speed comes from local
                // commands answering instantly instead.
                tts.setSpeechRate(1.0f);
                // Deaf window (v4.9.29): while the app itself is
                // speaking (plus a beat after), the continuous mic
                // ignores what it hears, so it never answers its
                // own voice.
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String id) { speaking = true; }
                    @Override public void onDone(String id) { handler.postDelayed(() -> speaking = false, 1200); }
                    @Override public void onError(String id) { speaking = false; }
                });
            }
        });
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_ready)));
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        if (p.getBoolean("float_mic", false)) showFloatMic();
        updateSosShake();
        handler.post(locationRecorder);
    }

    /** Anti-theft (v4.9.9): remember where the phone was, every 15 min,
     * so the location SMS still works when location is off - it sends
     * the last saved place (and the cell tower) instead. A cache read
     * only, no GPS wake-up, so it costs no battery. */
    private final Runnable locationRecorder = new Runnable() {
        @Override public void run() {
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                    android.location.Location found = null;
                    if (lm != null) {
                        found = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (found == null) found = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (found != null) {
                        getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                                .putString("saved_loc_lat", String.valueOf(found.getLatitude()))
                                .putString("saved_loc_lng", String.valueOf(found.getLongitude()))
                                .putLong("saved_loc_time", System.currentTimeMillis()).apply();
                    }
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 15 * 60 * 1000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_LISTEN.equals(a)) {
                // Mic = Live (v4.9.33): every mic tap - orb, Home
                // Listen button, notification, widget - starts a Live
                // call when a Gemini key is saved (tap again stops
                // it); without a key it falls back to old Listen.
                // (v4.9.35) While still connecting, a tap does
                // nothing - it must not kill the session.
                if (liveRunning) { if (liveReady) stopLive(); }
                else if (hasLiveKey()) startLive();
                else startListening();
            }
            else if (ACTION_STOP_LISTEN.equals(a)) stopListening();
            else if (ACTION_LIVE_START.equals(a)) startLive();
            else if (ACTION_LIVE_STOP.equals(a)) stopLive();
            else if (ACTION_FLOAT_ON.equals(a)) showFloatMic();
            else if (ACTION_FLOAT_OFF.equals(a)) hideFloatMic();
            else if (ACTION_STOP_RING.equals(a)) {
                CommandEngine.stopRing();
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(2);
            } else if (ACTION_SPEAK.equals(a)) {
                String text = intent.getStringExtra("text");
                if (text != null && !text.isEmpty()) speak(text);
            } else if (ACTION_SOS_SHAKE.equals(a)) {
                updateSosShake();
            } else if (ACTION_SEND_LOCATION.equals(a)) {
                // "BLACKMASK LOC" reply (v4.9.10): turn Location on first
                // if it is off, wait for a fresh fix, then reply by SMS.
                String to = intent.getStringExtra("loc_to");
                if (to != null && !to.isEmpty()) {
                    CommandEngine.withFreshLocation(this, 15000, () -> {
                        String loc = CommandEngine.locationLink(this);
                        String msg = loc.isEmpty()
                                ? "Black Mask AI: ফোনের লোকেশন পাওয়া যায়নি — GPS চালু করো।"
                                : "Black Mask AI: আমি এখানে আছি: " + loc;
                        CommandEngine.sendSmsReported(this, to, msg, "LOC");
                    });
                }
            }
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
        Notification.Builder nb = b.setContentTitle("Black Mask AI")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_btn_speak_now, "Listen", listenPi).build());
        // Continuous mic (v4.9.29): a Stop button, so one tap ends
        // the listening loop.
        if (getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("continuous_mic", false)) {
            Intent stop = new Intent(this, JarvisService.class).setAction(ACTION_STOP_LISTEN);
            PendingIntent stopPi = PendingIntent.getService(
                    this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, getString(R.string.notif_stop), stopPi).build());
        }
        // Live voice (v4.9.31): a Stop button while a Live call runs.
        if (liveRunning) {
            Intent stopLive = new Intent(this, JarvisService.class).setAction(ACTION_LIVE_STOP);
            PendingIntent stopLivePi = PendingIntent.getService(
                    this, 3, stopLive, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, getString(R.string.live_stop), stopLivePi).build());
        }
        return nb.setOngoing(true).build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---------- listening (tap Listen / floating mic / widget - one shot) ----------

    private void startListening() {
        continuousStop = false;
        startListeningInternal();
    }

    private void startListeningInternal() {
        if (listening) return;
        // Live owns the mic while a call runs (v4.9.31).
        if (liveRunning) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this device.");
            return;
        }
        // (v4.9.42) Pop-up so he can see the mic is on.
        toastMain(getString(R.string.mic_toast_on));
        try {
            // Fresh recognizer every session: reusing one gets stuck in an
            // error loop on many phones (mic flickers, nothing is ever heard).
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            // Ask for several guesses (v4.9.28): the top-N fix tries
            // them all, so the recognizer must actually return them.
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            // Live hearing (v4.9.28): show the words AS he speaks
            // (running answer, like MYRAA) instead of silence until
            // the end - partial results come through onPartialResults.
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // No silence-length extras (v4.9.27): the 1000/700ms values
            // from v4.9.26 cut the session short on this phone and the
            // recognizer failed with nothing heard - use the default
            // timing. Speed still comes from local commands + fast TTS.
            // Voice language from Settings: English / Bengali / Hindi.
            String langTag = getSharedPreferences("jarvis", MODE_PRIVATE).getString("voice_lang", "en-IN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(langTag));
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    listening = false;
                    unmuteBeep();
                    // In continuous mode the notification stays on
                    // "Listening..." - flipping to Ready every few
                    // seconds is what made it LOOK like on/off.
                    if (!isContinuous()) updateNotification(getString(R.string.notif_ready));
                    if (continuousStop) return;
                    // Own voice (v4.9.29): the reply is speaking while
                    // the next session listens - ignore what it hears.
                    if (speaking) { restartIfContinuous(); return; }
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) handleHeard(list);
                    else if (!isContinuous()) notHeard();
                    restartIfContinuous();
                }
                @Override public void onError(int error) {
                    listening = false;
                    unmuteBeep();
                    if (!isContinuous()) updateNotification(getString(R.string.notif_ready));
                    if (continuousStop) return;
                    // In continuous mode a failed session just restarts
                    // silently (v4.9.29) - speaking "didn't catch that"
                    // after every quiet 10 seconds would be unbearable.
                    if (isContinuous()) { restartIfContinuous(); return; }
                    // Was silent before (v4.9.27): any recognition
                    // failure left the mic dead with no word at all.
                    notHeard();
                }
                @Override public void onReadyForSpeech(Bundle p) { unmuteBeep(); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partial) {
                    // Running answer (v4.9.28): show what is heard
                    // live in the notification while he is speaking.
                    ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty() && list.get(0) != null && !list.get(0).isEmpty()) {
                        updateNotification(getString(R.string.listening_partial, list.get(0)));
                    }
                }
                @Override public void onEvent(int e, Bundle p) {}
            });
            muteBeep();
            recognizer.startListening(intent);
            listening = true;
            updateNotification("Listening...");
        } catch (Exception e) {
            listening = false;
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = null;
            }
            // (v4.9.30) A failed start used to kill the continuous
            // loop silently - nothing ever answered again. Retry.
            if (isContinuous() && !continuousStop) {
                handler.postDelayed(() -> {
                    if (!continuousStop) startListeningInternal();
                }, 2000);
            }
        }
    }

    private void stopListening() {
        // Set first: the session ending fires onError/onResults,
        // and both must see this and NOT restart or speak.
        continuousStop = true;
        listening = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
        updateNotification(getString(R.string.notif_ready));
    }

    // ---------- Gemini Live voice (v4.9.31, MYRAA-style) ----------

    private void startLive() {
        if (liveRunning) return;
        String key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("gemini_key", "");
        if (key == null || key.trim().isEmpty()) {
            String msg = getString(R.string.live_no_key);
            sendLog("BLACK MASK", msg);
            speak(msg);
            return;
        }
        if (listening) stopListening();
        liveRunning = true;
        liveReady = false;
        // (v4.9.42) Pop-up so he can see the mic is on.
        toastMain(getString(R.string.mic_toast_on));
        updateNotification(getString(R.string.live_connecting));
        liveVoice = new GeminiLiveVoice(this, new GeminiLiveVoice.Listener() {
            @Override public void onStatus(String text) {
                if (text.equals(getString(R.string.live_on))) liveReady = true;
                updateNotification(text);
            }
            @Override public void onLog(String who, String text) { sendLog(who, text); }
            @Override public void onEnded(String reason) {
                liveRunning = false;
                liveReady = false;
                toastMain(getString(R.string.mic_toast_off));
                updateNotification(getString(R.string.notif_ready));
                if (reason != null && !reason.isEmpty()) sendLog("BLACK MASK", reason);
            }
            @Override public String onToolCall(String name, org.json.JSONObject args) {
                // The Live model asked the phone to do something:
                // run it as a local command (silent - the model speaks).
                try {
                    final java.util.ArrayList<String> out = new java.util.ArrayList<>();
                    CommandEngine.Reply quiet = reply -> {
                        if (!"__CLEAR__".equals(reply)) out.add(reply);
                    };
                    String cmd = null;
                    if ("open_app".equals(name)) {
                        String app = args.optString("app", "").trim();
                        if (!app.isEmpty()) cmd = "open " + app;
                    } else if ("search".equals(name)) {
                        String q = args.optString("query", "").trim();
                        String target = args.optString("target", "google");
                        if (!q.isEmpty()) cmd = "youtube".equals(target)
                                ? "search " + q + " on youtube" : "search " + q;
                    } else if ("run_command".equals(name)) {
                        // (v4.9.40) The full brain: any phone action
                        // the user asked for - call, message,
                        // flashlight, reminder, weather - runs as a
                        // local command, exactly like a spoken one.
                        String c = args.optString("command", "").trim();
                        if (!c.isEmpty()) cmd = c;
                    }
                    if (cmd == null) return null;
                    if (CommandEngine.handle(JarvisService.this, cmd, quiet)) {
                        return out.isEmpty() ? "done" : out.get(0);
                    }
                    return "could not do that";
                } catch (Exception e) {
                    return "failed";
                }
            }
        });
        liveVoice.start(key.trim());
    }

    private void stopLive() {
        liveRunning = false;
        liveReady = false;
        if (liveVoice != null) {
            try { liveVoice.stop(); } catch (Exception ignored) {}
            liveVoice = null;
        }
        updateNotification(getString(R.string.notif_ready));
    }

    // ---------- mic on/off pop-up (v4.9.42) ----------
    // A Toast is the visible pop-up that the mic is on. It must run
    // on the main thread - Live's end arrives on its own thread.
    private void toastMain(String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show());
    }

    private boolean hasLiveKey() {
        String key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("gemini_key", "");
        return key != null && !key.trim().isEmpty();
    }

    // ---------- floating mic bubble ----------

    private void showFloatMic() {
        if (floatView != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            speak("Please allow the overlay permission for the floating mic.");
            // (v4.9.37) Say it in the Chat page too - spoken words
            // are easy to miss, a written line is not.
            sendLog("BLACK MASK", getString(R.string.bubble_no_overlay));
            return;
        }
        try {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("float_mic", true).apply();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            ImageView bubble = new ImageView(this);
            try {
                bubble.setImageBitmap(circularLogoBitmap());
            } catch (Exception ignored) {
                bubble.setImageResource(R.drawable.logo);
            }
            int size = (int) (48 * getResources().getDisplayMetrics().density);
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
            final Runnable longPress = () -> { if (!moved[0]) showBubbleMenu(bubble); };
            bubble.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX[0] = (int) event.getRawX();
                        lastY[0] = (int) event.getRawY();
                        moved[0] = false;
                        handler.postDelayed(longPress, 600);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - lastX[0];
                        int dy = (int) event.getRawY() - lastY[0];
                        if (Math.abs(dx) + Math.abs(dy) > 10) {
                            moved[0] = true;
                            handler.removeCallbacks(longPress);
                        }
                        params.x += dx;
                        params.y += dy;
                        lastX[0] = (int) event.getRawX();
                        lastY[0] = (int) event.getRawY();
                        windowManager.updateViewLayout(floatView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPress);
                        // Live first (v4.9.32): one tap on the bubble
                        // starts a Live call (it stays on, no on/off
                        // cycling); tap again stops it. Without a
                        // saved Gemini key it falls back to Listen.
                        if (!moved[0]) {
                            // (v4.9.40) The bubble is the only on/off
                            // switch: one tap starts Live, one tap
                            // stops it - even while it is still
                            // connecting. The old "do nothing while
                            // connecting" guard left him stuck: the
                            // notification cannot be swiped away
                            // until Live stops, so the bubble is now
                            // the escape hatch. The chip appears the
                            // moment a tap registers.
                            if (liveRunning) stopLive();
                            else if (hasLiveKey()) startLive();
                            else if (listening && isContinuous()) stopListening();
                            else startListening();
                        }
                        return true;
                }
                return false;
            });
            floatView = bubble;
            windowManager.addView(floatView, params);
        } catch (Exception e) {
            floatView = null;
            // (v4.9.37) A silent failure here used to leave the user
            // staring at a missing bubble with no clue why.
            String why = e.getMessage();
            if (why == null || why.isEmpty()) why = e.toString();
            if (why.length() > 150) why = why.substring(0, 150);
            sendLog("BLACK MASK", getString(R.string.bubble_failed, why));
        }
    }

    private void hideFloatMic() {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("float_mic", false).apply();
        if (floatView != null && windowManager != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
    }

    /** Round floating-mic icon: the logo on a red-glow gradient, with a red ring. */
    /** Round floating-mic icon: the masked face on a pure black circle,
     * with a red ring. The logo is a tall photo whose bottom band is the
     * white "anonymous" text, so only the square around the face is used. */
    private Bitmap circularLogoBitmap() {
        int size = 256;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Pure black background - no white, no glow.
        p.setColor(0xFF000000);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        Bitmap logo = BitmapFactory.decodeResource(getResources(), R.drawable.logo);
        if (logo != null) {
            // Square crop around the face (upper part of the photo).
            int side = Math.min(logo.getWidth(), logo.getHeight());
            int top = (int) (logo.getHeight() * 0.12);
            Rect src = new Rect(0, top, side, top + side);
            Path circle = new Path();
            circle.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            c.save();
            c.clipPath(circle);
            float d = size * 0.8f;
            Rect dst = new Rect((int) (size / 2f - d / 2), (int) (size / 2f - d / 2),
                    (int) (size / 2f + d / 2), (int) (size / 2f + d / 2));
            c.drawBitmap(logo, src, dst, p);
            c.restore();
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFFFF2A3C);
        p.setStrokeWidth(size * 0.03f);
        c.drawCircle(size / 2f, size / 2f, size / 2f - p.getStrokeWidth() / 2, p);
        return out;
    }

    /** Long-press menu on the floating mic: essential shortcuts only, in a
     * custom black card (the stock PopupMenu was white and crowded). */
    private void showBubbleMenu(View anchor) {
        try {
            View view = LayoutInflater.from(this).inflate(R.layout.bubble_menu, null);
            AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            view.findViewById(R.id.menuListen).setOnClickListener(v -> {
                dialog.dismiss();
                startListening();
            });
            view.findViewById(R.id.menuYoutube).setOnClickListener(v -> {
                dialog.dismiss();
                runCommand("open youtube");
            });
            view.findViewById(R.id.menuWhatsapp).setOnClickListener(v -> {
                dialog.dismiss();
                runCommand("open whatsapp");
            });
            view.findViewById(R.id.menuFlashlight).setOnClickListener(v -> {
                dialog.dismiss();
                runCommand(CommandEngine.flashlightOn ? "flashlight off" : "flashlight on");
            });
            view.findViewById(R.id.menuSos).setOnClickListener(v -> {
                dialog.dismiss();
                runCommand("sos");
            });
            view.findViewById(R.id.menuLock).setOnClickListener(v -> {
                dialog.dismiss();
                runCommand("lock phone");
            });
            view.findViewById(R.id.menuRemove).setOnClickListener(v -> {
                dialog.dismiss();
                hideFloatMic();
            });
            dialog.show();
        } catch (Exception e) {
            speak("Could not open the menu.");
        }
    }

    /** Run a voice command from the bubble menu, with a spoken reply. */
    private void runCommand(String text) {
        CommandEngine.handle(this, text, reply -> {
            if ("__CLEAR__".equals(reply)) return;
            sendLog("BLACK MASK", reply);
            speakReply(reply);
        });
    }

    // ---------- handling what was heard ----------

    private void handleHeard(ArrayList<String> candidates) {
        // The first guess is what he most likely said; the rest are
        // Google's other guesses for the same audio.
        String text = candidates.get(0);
        sendLog("You", text);
        updateNotification("Heard: " + text);
        // Hearing fix (v4.9.25): try EVERY guess Google gave, in
        // order - the first guess is often wrong and a later one is
        // the command he meant. Trial replies are recorded, not
        // spoken, so only the winning guess's reply is ever heard.
        // The first guess that matches a local command wins and runs.
        // (v4.9.29) The wake word is stripped first, so "Hey Black
        // Mask, open YouTube" runs in the same breath.
        boolean woke = false;
        boolean allEmpty = true;
        for (String guess : candidates) {
            if (guess == null || guess.trim().isEmpty()) continue;
            String stripped = stripWakeWord(guess);
            if (!stripped.equals(guess.trim())) woke = true;
            if (stripped.isEmpty()) continue;
            allEmpty = false;
            ArrayList<String> recorded = new ArrayList<>();
            CommandEngine.Reply trial = reply -> {
                if (!"__CLEAR__".equals(reply)) recorded.add(reply);
            };
            if (CommandEngine.handle(this, stripped, trial)) {
                for (String r : recorded) {
                    sendLog("BLACK MASK", r);
                    speakReply(r);
                }
                updateNotification(getString(R.string.notif_ready));
                return;
            }
        }
        if (woke && allEmpty) {
            // Only the wake word was said ("Hey Black Mask") - say
            // we are listening. The mic stays on in continuous mode.
            sendLog("BLACK MASK", getString(R.string.wake_ack));
            speakReply(getString(R.string.wake_ack));
            updateNotification(getString(R.string.notif_ready));
            return;
        }
        // The brain hears the command without the wake word.
        // (One assignment only: a lambda below uses this, so it must
        // stay effectively final - the earlier two-step version did
        // not compile.)
        String strippedFirst = stripWakeWord(text);
        final String clean = strippedFirst.isEmpty() ? text : strippedFirst;
        if (isContinuous() && !woke) {
            // Continuous mode, no wake word, no local command: try
            // the brain's command mapping (v4.9.30) - a slightly
            // misheard command still runs instead of vanishing
            // silently. If it maps to nothing, stay quiet instead
            // of chatting with the room.
            if (!CommandEngine.isOnline(this)) {
                updateNotification(getString(R.string.notif_ready));
                return;
            }
            net.execute(() -> {
                String cmd = CommandEngine.parseIntentCommand(this, clean);
                if (cmd != null) {
                    CommandEngine.Reply cb = reply -> {
                        if ("__CLEAR__".equals(reply)) return;
                        sendLog("BLACK MASK", reply);
                        speakReply(reply);
                    };
                    if (CommandEngine.handle(this, cmd, cb)) {
                        updateNotification(getString(R.string.notif_ready));
                        return;
                    }
                }
                updateNotification(getString(R.string.notif_ready));
            });
            return;
        }
        CommandEngine.Reply replyCb = reply -> {
            if ("__CLEAR__".equals(reply)) return;
            sendLog("BLACK MASK", reply);
            speakReply(reply);
        };
        // No guess was a local command: the brain / chat path hears
        // the first guess, exactly as before.
        if (!CommandEngine.isOnline(this)) {
            String msg = "Internet is off. I can still do local things — time, alarms, open apps, notes. Turn on the net for AI chat.";
            sendLog("BLACK MASK", msg);
            speakReply(msg);
            updateNotification(getString(R.string.notif_ready));
            return;
        }
        SharedPreferences prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        String key = prefs.getString("gemini_key", "");
        updateNotification("Thinking...");
        net.execute(() -> {
            // Brain (v4.9.16): no command words needed - the AI turns
            // what was heard into one command the app can run.
            String cmd = CommandEngine.parseIntentCommand(this, clean);
            if (cmd != null && CommandEngine.handle(this, cmd, replyCb)) {
                updateNotification(getString(R.string.notif_ready));
                return;
            }
            String reply;
            if (key.isEmpty()) {
                // No key? Chat with the free keyless AI instead - zero setup.
                reply = CommandEngine.freeChat(this, clean);
                if (reply == null) {
                    reply = CommandEngine.aiFailMessage(this, clean);
                }
            } else {
                reply = askGemini(clean, key);
            }
            sendLog("BLACK MASK", reply);
            speakReply(reply);
            updateNotification(getString(R.string.notif_ready));
        });
    }

    // Chat history key (v4.9.33): the service itself saves every
    // log line here, so the Chat page shows Live lines even when
    // MainActivity was in the background when they were written.
    public static final Object LOG_LOCK = new Object();

    private void sendLog(String who, String text) {
        // Persist first (v4.9.33): a broadcast alone is lost when
        // MainActivity is paused - e.g. Live started from the bubble
        // - and the Chat page then shows nothing at all.
        try {
            synchronized (LOG_LOCK) {
                android.content.SharedPreferences p =
                        getSharedPreferences("jarvis", MODE_PRIVATE);
                String h = p.getString("chat_history", "");
                String n = h + "\n" + who + ": " + text + "\n";
                if (n.length() > 40000) n = n.substring(n.length() - 40000);
                p.edit().putString("chat_history", n).apply();
            }
        } catch (Exception ignored) {}
        Intent i = new Intent(BROADCAST_LOG);
        i.putExtra("who", who);
        i.putExtra("text", text);
        sendBroadcast(i);
    }

    private void speak(String text) {
        if (tts != null) {
            Locale loc = ttsLocaleFor(text);
            tts.setLanguage(loc);
            applyBestVoice(loc);
            // (v4.9.29) If this phone has no voice for the reply's
            // language, the words come out garbled - note it once in
            // the chat log so the cause is visible.
            if (!voiceWarned && !loc.getLanguage().equals("en")) {
                int avail = tts.isLanguageAvailable(loc);
                if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                    voiceWarned = true;
                    sendLog("BLACK MASK", getString(R.string.voice_missing));
                }
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        }
    }

    /** Clear voice (v4.9.28): pick the best installed voice for the
     * reply's language - on-device voices first, then the highest
     * quality one (MYRAA picks local voices the same way). The voice
     * the engine picks by default is not always the clearest. */
    private void applyBestVoice(Locale locale) {
        if (tts == null) return;
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) return;
            android.speech.tts.Voice best = null;
            for (android.speech.tts.Voice v : voices) {
                if (!v.getLocale().getLanguage().equals(locale.getLanguage())) continue;
                // On-device voices first: isNetworkConnectionRequired()
                // false means the voice works offline (local). There is
                // no isLocal() on Voice - that was the v4.9.28 build fix.
                if (v.isNetworkConnectionRequired()) continue;
                if (best == null || v.getQuality() > best.getQuality()) best = v;
            }
            if (best == null) {
                for (android.speech.tts.Voice v : voices) {
                    if (!v.getLocale().getLanguage().equals(locale.getLanguage())) continue;
                    if (best == null || v.getQuality() > best.getQuality()) best = v;
                }
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) {}
    }

    /** Spoken reply to a voice command (v4.9.15): only when the
     * "Speak replies aloud" switch in Settings is ON. Typed Chat
     * replies never reach this - they are text-only. Status lines
     * (SOS, errors, notification reading) still use speak() above,
     * so they are not silenced by this switch. */
    private void speakReply(String text) {
        if (!getSharedPreferences("jarvis", MODE_PRIVATE)
                .getBoolean("speak_replies", true)) return;
        speak(text);
    }

    /** The mic heard nothing, or the recognizer failed (v4.9.27): say
     * so out loud instead of going silently dead - silence was the
     * whole complaint. Uses speak(), not speakReply(), so the switch
     * in Settings cannot silence this error feedback. */
    private void notHeard() {
        String msg = getString(R.string.not_heard);
        sendLog("BLACK MASK", msg);
        speak(msg);
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
        handler.removeCallbacks(locationRecorder);
        hideFloatMic();
        stopLive();
        if (sensorManager != null && shakeListener != null) {
            sensorManager.unregisterListener(shakeListener);
        }
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }
}
