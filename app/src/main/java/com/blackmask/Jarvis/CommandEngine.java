package com.blackmask.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All local (no-AI-key) commands for Black Mask AI.
 * Used by MainActivity (foreground) and JarvisService (background),
 * so a command works the same from the app and from the notification.
 *
 * NOTE: WhatsApp messages open the chat with the text ready - WhatsApp
 * does not let any app press Send by itself, so the final tap is yours.
 */
public class CommandEngine {

    public interface Reply {
        void say(String text);
    }

    private static final String[] JOKES = {
            "Why do programmers prefer dark mode? Because light attracts bugs!",
            "I told my computer I needed a break, and now it won't stop sending me KitKat ads.",
            "Why did the developer go broke? Because he used up all his cache.",
            "There are only 10 kinds of people: those who understand binary and those who don't.",
            "Why do Java developers wear glasses? Because they don't C#."
    };

    private static final Random random = new Random();

    // Known commands for the "did you mean" suggestions.
    private static final String[][] SUGGESTIONS = {
            {"open youtube", "open", "youtube", "video"},
            {"whatsapp Rahul I am coming", "whatsapp", "message", "msg"},
            {"call Maa", "call", "phone"},
            {"set alarm 7 am", "alarm", "wake"},
            {"set timer 5 minutes", "timer"},
            {"weather", "weather", "temperature", "rain"},
            {"news", "news", "headlines"},
            {"tell me a joke", "joke", "funny"},
            {"calculate 25 * 4", "calculate", "math", "plus", "minus"},
            {"translate hello in Bengali", "translate"},
            {"cricket score", "cricket", "score", "match"},
            {"remind me in 10 minutes to drink water", "remind", "reminder"},
            {"flashlight on", "flashlight", "torch", "light"},
            {"open camera", "open", "camera"},
            {"what is the time", "time"},
            {"help", "help"},
    };

    /** Offline check - local commands work without net, Gemini does not. */
    public static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return true; // if we cannot tell, try anyway
        }
    }

    /** Top 3 "did you mean" suggestions for a misunderstood input. */
    public static List<String> getSuggestions(String input) {
        String t = input.toLowerCase(Locale.ENGLISH);
        int[] scores = new int[SUGGESTIONS.length];
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            for (int j = 1; j < SUGGESTIONS[i].length; j++) {
                String kw = SUGGESTIONS[i][j];
                if (t.contains(kw)) scores[i] += 3;
                else if (kw.length() > 3 && levenshtein(t, kw) <= 2) scores[i] += 1;
            }
            // Bonus when the input shares words with the example itself.
            for (String w : SUGGESTIONS[i][0].split(" ")) {
                if (w.length() > 2 && t.contains(w)) scores[i] += 1;
            }
        }
        List<String> out = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            int best = -1, bestScore = 0;
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] > bestScore) { bestScore = scores[i]; best = i; }
            }
            if (best < 0) break;
            out.add(SUGGESTIONS[best][0]);
            scores[best] = -1;
        }
        if (out.isEmpty()) {
            out.add("open youtube");
            out.add("weather");
            out.add("help");
        }
        return out;
    }

    private static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.length()][b.length()];
    }

    /** Returns true if the text was a local command (handled here). */
    public static boolean handle(Context ctx, String text, Reply reply) {
        String t = text.toLowerCase(Locale.ENGLISH).trim();

        if (t.contains("time")) {
            String now = new SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault()).format(new Date());
            reply.say("It is " + now + ".");
            return true;
        }
        if (t.contains("date") || t.contains("day is")) {
            String d = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(new Date());
            reply.say("Today is " + d + ".");
            return true;
        }
        if (t.contains("weather")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for the weather."); return true; }
            reply.say("Checking the weather...");
            new Thread(() -> reply.say(fetchWeather())).start();
            return true;
        }
        if (t.contains("news") || t.contains("headline")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for the news."); return true; }
            reply.say("Fetching today's headlines...");
            new Thread(() -> reply.say(fetchNews())).start();
            return true;
        }
        if (t.contains("cricket") || t.contains("live score")) {
            openUrl(ctx, "https://www.espncricinfo.com/live-cricket-score");
            reply.say("Opening live cricket scores.");
            return true;
        }
        if (t.contains("joke")) {
            reply.say(JOKES[random.nextInt(JOKES.length)]);
            return true;
        }
        if (t.startsWith("note ") || t.startsWith("remember ")) {
            String note = text.substring(text.indexOf(' ') + 1).trim();
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("note", note).apply();
            reply.say("Noted: " + note);
            return true;
        }
        if (t.contains("my note") || t.contains("show note")) {
            String note = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("note", "");
            reply.say(note.isEmpty() ? "You have no note saved yet." : "Your note: " + note);
            return true;
        }
        if (t.equals("clear") || t.equals("clear chat")) {
            reply.say("__CLEAR__");
            return true;
        }
        if (t.contains("who are you") || t.contains("your name")) {
            reply.say("I am Black Mask AI, your personal AI assistant, built by Arif.");
            return true;
        }
        if (t.contains("help") || t.contains("what can you do")) {
            reply.say("I can chat with Gemini AI, tell time, date, weather and news, calculate, translate, open any app, send WhatsApp messages and SMS by name, call people, set alarms, timers and reminders, search the web and YouTube, turn the flashlight on or off, save notes and tell jokes. Just speak. If I don't understand, I'll show you options.");
            return true;
        }
        if (t.contains("flashlight") || t.contains("torch")) {
            boolean on = !t.contains("off");
            if (setTorch(ctx, on)) reply.say(on ? "Flashlight on." : "Flashlight off.");
            else reply.say("Could not control the flashlight. Please allow the camera permission.");
            return true;
        }

        // "calculate 25 * 4" / "what is 25 plus 30" / "calculate (10+5)*2"
        if (t.startsWith("calculate") || t.startsWith("calc ") || t.startsWith("what is ")
                || t.matches(".*\\d+\\s*(plus|minus|times|multiplied|divided|\\+|-|\\*|/|x)\\s*\\d+.*")) {
            String expr = t.replaceFirst("^(calculate|calc|what is)\\s*", "")
                    .replace("plus", "+").replace("minus", "-")
                    .replace("times", "*").replace("multiplied by", "*").replace("multiplied", "*")
                    .replace("divided by", "/").replace("into", "*").replace("x", "*")
                    .replaceAll("[^0-9+\\-*/().% ]", "").trim();
            // Not actually math (e.g. "what is the meaning of life") - let Gemini answer.
            if (!expr.matches(".*\\d.*")) return false;
            try {
                double result = evalExpr(expr);
                String out = (result == Math.floor(result))
                        ? String.valueOf((long) result)
                        : String.valueOf(Math.round(result * 100.0) / 100.0);
                reply.say(expr + " = " + out);
            } catch (Exception e) {
                reply.say("I could not calculate that. Try like: calculate 25 * 4.");
            }
            return true;
        }

        // "translate hello in Bengali" / "translate thank you to Hindi"
        if (t.startsWith("translate ")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net to translate."); return true; }
            Matcher m = Pattern.compile("translate\\s+(.+?)\\s+(?:in|to)\\s+(\\w+)\\s*$").matcher(t);
            if (m.find()) {
                String phrase = text.substring(text.toLowerCase(Locale.ENGLISH).indexOf("translate") + 9, text.toLowerCase(Locale.ENGLISH).lastIndexOf(m.group(2))).trim();
                String lang = langCode(m.group(2));
                if (lang == null) { reply.say("I don't know that language yet. Try Bengali, Hindi or English."); return true; }
                final String p = phrase, l = lang;
                new Thread(() -> reply.say(translate(p, l))).start();
            } else {
                reply.say("Say it like: translate hello in Bengali.");
            }
            return true;
        }

        // "remind me in 10 minutes to drink water" / "remind me at 7 pm to call Maa"
        if (t.startsWith("remind me ")) {
            Matcher inM = Pattern.compile("remind me in (\\d+)\\s*(minute|minutes|min|hour|hours|second|seconds)\\s+to\\s+(.+)").matcher(t);
            Matcher atM = Pattern.compile("remind me at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+to\\s+(.+)").matcher(t);
            long when = -1;
            String what = null;
            if (inM.find()) {
                int v = Integer.parseInt(inM.group(1));
                String unit = inM.group(2);
                long add = unit.startsWith("hour") ? v * 3600_000L : unit.startsWith("min") ? v * 60_000L : v * 1000L;
                when = System.currentTimeMillis() + add;
                what = inM.group(3);
            } else if (atM.find()) {
                int hour = Integer.parseInt(atM.group(1));
                int min = atM.group(2) != null ? Integer.parseInt(atM.group(2)) : 0;
                String ap = atM.group(3);
                if ("pm".equals(ap) && hour < 12) hour += 12;
                if ("am".equals(ap) && hour == 12) hour = 0;
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
                when = cal.getTimeInMillis();
                what = atM.group(4);
            }
            if (when > 0 && what != null) {
                scheduleReminder(ctx, when, what);
                reply.say("Reminder set: " + what);
            } else {
                reply.say("Say it like: remind me in 10 minutes to drink water. Or: remind me at 7 pm to call Maa.");
            }
            return true;
        }

        // "set alarm 7 am" / "set alarm 7:30 pm"
        if (t.contains("alarm")) {
            Matcher m = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(t);
            if (m.find()) {
                int hour = Integer.parseInt(m.group(1));
                int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                String ap = m.group(3);
                if ("pm".equals(ap) && hour < 12) hour += 12;
                if ("am".equals(ap) && hour == 12) hour = 0;
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Black Mask AI alarm")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.say("Alarm set for " + hour + ":" + String.format(Locale.ENGLISH, "%02d", min) + ".");
            } else {
                reply.say("Tell me the time, like: set alarm 7 am.");
            }
            return true;
        }

        // "set timer 5 minutes"
        if (t.contains("timer")) {
            Matcher m = Pattern.compile("(\\d+)\\s*(hour|minute|min|second|sec)").matcher(t);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                String unit = m.group(2);
                int seconds = unit.startsWith("hour") ? v * 3600 : unit.startsWith("min") ? v * 60 : v;
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Black Mask AI timer")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.say("Timer set for " + v + " " + unit + ".");
            } else {
                reply.say("Tell me the length, like: set timer 5 minutes.");
            }
            return true;
        }

        // "call <name>" - calls directly, no tap needed (needs CALL_PHONE permission).
        if (t.startsWith("call ")) {
            String name = text.substring(5).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say("I could not find " + name + " in your contacts."); return true; }
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null) {
                        tm.placeCall(Uri.parse("tel:" + number), null);
                        reply.say("Calling " + name + ".");
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            // No call permission - open the dialer with the number ready.
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            reply.say("Opening the dialer for " + name + ". Tap call to connect.");
            return true;
        }

        // "whatsapp <name> <message>" / "message <name> <message>"
        if (t.startsWith("whatsapp ") || t.startsWith("message ")) {
            String rest = text.substring(text.indexOf(' ') + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp < 0) { reply.say("Tell me the name and the message, like: whatsapp Rahul I am coming."); return true; }
            String name = rest.substring(0, sp).trim();
            String message = rest.substring(sp + 1).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say("I could not find " + name + " in your contacts."); return true; }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/" + number + "?text=" + URLEncoder.encode(message, "UTF-8")));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.say("Opening WhatsApp to " + name + " with your message ready. Tap send.");
            } catch (Exception e) {
                reply.say("Could not open WhatsApp.");
            }
            return true;
        }

        // "sms <name> <message>" / "text <name> <message>"
        if (t.startsWith("sms ") || t.startsWith("text ")) {
            String rest = text.substring(text.indexOf(' ') + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp < 0) { reply.say("Tell me the name and the message, like: sms Rahul I am coming."); return true; }
            String name = rest.substring(0, sp).trim();
            String message = rest.substring(sp + 1).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say("I could not find " + name + " in your contacts."); return true; }
            try {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                i.putExtra("sms_body", message);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.say("Opening SMS to " + name + ".");
            } catch (Exception e) {
                reply.say("Could not open SMS.");
            }
            return true;
        }

        // "open <app name>" / "play <song> on youtube" / "search <query>"
        if (t.startsWith("open ")) {
            String name = text.substring(5).trim();
            if (openApp(ctx, name)) reply.say("Opening " + name + ".");
            else reply.say("I could not find an app called " + name + ".");
            return true;
        }
        if (t.startsWith("play ")) {
            String q = text.substring(5).trim().replaceAll("(?i)\\s+on youtube$", "").trim();
            openUrl(ctx, "https://www.youtube.com/results?search_query=" + urlEncode(q));
            reply.say("Playing " + q + " on YouTube.");
            return true;
        }
        if (t.startsWith("search ")) {
            String q = text.substring(7).trim();
            openUrl(ctx, "https://www.google.com/search?q=" + urlEncode(q));
            reply.say("Searching for " + q + ".");
            return true;
        }

        return false; // not a local command - caller should ask Gemini
    }

    // ---------- helpers ----------

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static void openUrl(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static boolean openApp(Context ctx, String name) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            String want = name.toLowerCase(Locale.ENGLISH).trim();
            ApplicationInfo best = null;
            for (ApplicationInfo app : apps) {
                String label = String.valueOf(pm.getApplicationLabel(app)).toLowerCase(Locale.ENGLISH);
                if (label.equals(want)) { best = app; break; }
                if (best == null && label.contains(want)) best = app;
            }
            if (best == null) return false;
            Intent launch = pm.getLaunchIntentForPackage(best.packageName);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Find a contact's phone number by name. Returns digits in international form, or null. */
    private static String findContactNumber(Context ctx, String name) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name + "%"}, null);
            if (c == null) return null;
            List<String> matches = new ArrayList<>();
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n != null && !n.isEmpty()) matches.add(n);
            }
            c.close();
            if (matches.isEmpty()) return null;
            String raw = matches.get(0).replaceAll("[^0-9+]", "");
            if (raw.startsWith("+")) return raw.substring(1);
            if (raw.startsWith("0")) raw = raw.substring(1);
            if (raw.length() == 10) return "91" + raw; // India default
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean setTorch(Context ctx, boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return false;
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return false;
            cm.setTorchMode(ids[0], on);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fetchWeather() {
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=22.57&longitude=88.36&current=temperature_2m,weather_code,wind_speed_10m");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            double temp = cur.getDouble("temperature_2m");
            double wind = cur.getDouble("wind_speed_10m");
            int code = cur.getInt("weather_code");
            String cond = code == 0 ? "clear sky" : code <= 3 ? "cloudy" : code <= 67 ? "rainy" : code <= 77 ? "snowy" : "stormy";
            return "Kolkata: " + temp + "°C, " + cond + ", wind " + wind + " km/h.";
        } catch (Exception e) {
            return "Could not fetch the weather. Check your internet.";
        }
    }

    /** Top headlines from Google News RSS (free, no key). */
    private static String fetchNews() {
        try {
            URL url = new URL("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            XmlPullParser p = Xml.newPullParser();
            p.setInput(conn.getInputStream(), "UTF-8");
            List<String> titles = new ArrayList<>();
            int event = p.getEventType();
            boolean inItem = false;
            while (event != XmlPullParser.END_DOCUMENT && titles.size() < 3) {
                if (event == XmlPullParser.START_TAG) {
                    if ("item".equals(p.getName())) inItem = true;
                    else if (inItem && "title".equals(p.getName())) titles.add(p.nextText());
                } else if (event == XmlPullParser.END_TAG && "item".equals(p.getName())) {
                    inItem = false;
                }
                event = p.next();
            }
            if (titles.isEmpty()) return "No headlines right now.";
            StringBuilder sb = new StringBuilder("Today's top headlines:\n");
            for (int i = 0; i < titles.size(); i++) sb.append((i + 1)).append(". ").append(titles.get(i)).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            return "Could not fetch the news. Check your internet.";
        }
    }

    private static String langCode(String name) {
        String n = name.toLowerCase(Locale.ENGLISH).trim();
        switch (n) {
            case "bengali": case "bangla": return "bn";
            case "hindi": return "hi";
            case "english": return "en";
            case "urdu": return "ur";
            case "arabic": return "ar";
            case "spanish": return "es";
            case "french": return "fr";
            case "german": return "de";
            case "tamil": return "ta";
            case "telugu": return "te";
            case "marathi": return "mr";
            case "punjabi": return "pa";
            case "gujarati": return "gu";
            case "japanese": return "ja";
            case "korean": return "ko";
            case "russian": return "ru";
            case "portuguese": return "pt";
            default: return null;
        }
    }

    /** Translate with Google's free endpoint (no key). */
    private static String translate(String text, String lang) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + lang + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            JSONArray parts = arr.getJSONArray(0);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) out.append(parts.getJSONArray(i).getString(0));
            return out.toString().trim();
        } catch (Exception e) {
            return "Could not translate. Check your internet.";
        }
    }

    private static void scheduleReminder(Context ctx, long when, String what) {
        try {
            Intent i = new Intent(ctx, ReminderReceiver.class);
            i.putExtra("text", what);
            int id = (int) (System.currentTimeMillis() % 100000);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception ignored) {}
    }

    // ---------- simple expression evaluator (+ - * / % and parentheses) ----------

    private static double evalExpr(String expr) throws Exception {
        return new Object() {
            int pos = -1, ch;
            void next() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) {
                while (ch == ' ') next();
                if (ch == c) { next(); return true; }
                return false;
            }
            double parse() {
                next();
                double x = parseExpr();
                if (pos < expr.length()) throw new Exception("bad expr");
                return x;
            }
            double parseExpr() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else if (eat('%')) x %= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int start = pos;
                if (eat('(')) { x = parseExpr(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') next();
                    x = Double.parseDouble(expr.substring(start, pos));
                } else throw new Exception("bad factor");
                return x;
            }
        }.parse();
    }
}
