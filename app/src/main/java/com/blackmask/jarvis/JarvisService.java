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
            if (ACTION_LISTEN.equals(a)) startListening();
            else if (ACTION_STOP_LISTEN.equals(a)) stopListening();
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

    // ---------- listening (tap Listen / floating mic / widget - one shot) ----------

    private void startListening() {
        startListeningInternal();
    }

    private void startListeningInternal() {
        if (listening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this device.");
            return;
        }
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
                    updateNotification(getString(R.string.notif_ready));
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) handleHeard(list);
                    else notHeard();
                }
                @Override public void onError(int error) {
                    listening = false;
                    updateNotification(getString(R.string.notif_ready));
                    // Was silent before (v4.9.27): any recognition
                    // failure left the mic dead with no word at all.
                    notHeard();
                }
                @Override public void onReadyForSpeech(Bundle p) {}
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
            recognizer.startListening(intent);
            listening = true;
            updateNotification("Listening...");
        } catch (Exception e) {
            listening = false;
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = null;
            }
        }
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
        updateNotification(getString(R.string.notif_ready));
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
        for (String guess : candidates) {
            if (guess == null || guess.trim().isEmpty()) continue;
            ArrayList<String> recorded = new ArrayList<>();
            CommandEngine.Reply trial = reply -> {
                if (!"__CLEAR__".equals(reply)) recorded.add(reply);
            };
            if (CommandEngine.handle(this, guess, trial)) {
                for (String r : recorded) {
                    sendLog("BLACK MASK", r);
                    speakReply(r);
                }
                updateNotification(getString(R.string.notif_ready));
                return;
            }
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
            String cmd = CommandEngine.parseIntentCommand(this, text);
            if (cmd != null && CommandEngine.handle(this, cmd, replyCb)) {
                updateNotification(getString(R.string.notif_ready));
                return;
            }
            String reply;
            if (key.isEmpty()) {
                // No key? Chat with the free keyless AI instead - zero setup.
                reply = CommandEngine.freeChat(this, text);
                if (reply == null) {
                    reply = CommandEngine.aiFailMessage(this, text);
                }
            } else {
                reply = askGemini(text, key);
            }
            sendLog("BLACK MASK", reply);
            speakReply(reply);
            updateNotification(getString(R.string.notif_ready));
        });
    }

    private void sendLog(String who, String text) {
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
        if (sensorManager != null && shakeListener != null) {
            sensorManager.unregisterListener(shakeListener);
        }
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }
}
