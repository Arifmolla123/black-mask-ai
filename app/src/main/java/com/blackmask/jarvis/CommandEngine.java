package com.blackmask.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.app.KeyguardManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.util.Xml;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
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
    /** Is the flashlight on right now? (Set by the flashlight branch; the bubble menu toggles it.) */
    public static boolean flashlightOn = false;

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

    private static final String[] SIGNS = {"aries", "taurus", "gemini", "cancer", "leo", "virgo",
            "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces"};

    /** World clock cities. */
    private static final Map<String, String> CITY_TIMEZONES = new HashMap<>();
    static {
        CITY_TIMEZONES.put("london", "Europe/London");
        CITY_TIMEZONES.put("new york", "America/New_York");
        CITY_TIMEZONES.put("los angeles", "America/Los_Angeles");
        CITY_TIMEZONES.put("dubai", "Asia/Dubai");
        CITY_TIMEZONES.put("tokyo", "Asia/Tokyo");
        CITY_TIMEZONES.put("dhaka", "Asia/Dhaka");
        CITY_TIMEZONES.put("delhi", "Asia/Kolkata");
        CITY_TIMEZONES.put("kolkata", "Asia/Kolkata");
        CITY_TIMEZONES.put("paris", "Europe/Paris");
        CITY_TIMEZONES.put("sydney", "Australia/Sydney");
        CITY_TIMEZONES.put("singapore", "Asia/Singapore");
        CITY_TIMEZONES.put("moscow", "Europe/Moscow");
    }

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
            {"bitcoin price", "bitcoin", "crypto", "btc", "price"},
            {"convert 100 usd to inr", "convert", "currency", "dollar", "rupee"},
            {"meaning of hello", "meaning", "define", "dictionary"},
            {"tell me a fact", "fact"},
            {"motivate me", "quote", "motivate", "inspire"},
            {"give me advice", "advice"},
            {"aries horoscope", "horoscope"},
            {"calculate 25 * 4", "calculate", "math", "plus", "minus"},
            {"translate hello in Bengali", "translate"},
            {"cricket score", "cricket", "score", "match"},
            {"remind me in 10 minutes to drink water", "remind", "reminder"},
            {"flashlight on", "flashlight", "torch", "light"},
            {"open camera", "open", "camera"},
            {"good morning", "good morning", "morning"},
            {"wifi on", "wifi"},
            {"battery", "battery"},
            {"remember my bike number is WB12AB3456", "remember", "memory"},
            {"where is my phone", "where is my phone", "find my phone"},
            {"take a photo", "photo", "camera", "selfie"},
            {"play music", "music", "song"},
            {"volume up", "volume"},
            {"take a screenshot", "screenshot"},
            {"lock my phone", "lock"},
            {"silent mode", "silent"},
            {"generate password", "password"},
            {"flip a coin", "coin"},
            {"what is my ip", "ip"},
            {"where am i", "location"},
            {"time in london", "time"},
            {"remind me every day at 7 am to drink water", "remind"},
            {"scroll down", "scroll"},
            {"driving mode on", "driving"},
            {"sos", "sos"},
            {"schedule sms", "schedule"},


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
        String t = normalizeCommand(input).toLowerCase(Locale.ENGLISH);
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

    // Phrases the recognizer often hears slightly wrong, and Bengali/Hindi
    // command phrases - all become the English form the dispatcher knows.
    private static final String[][] NORMALIZE_PHRASES = {
            {"wi fi", "wifi"},
            {"whats app", "whatsapp"},
            {"what's app", "whatsapp"},
            {"whats up", "whatsapp"},
            {"u tube", "youtube"},
            {"face book", "facebook"},
            {"insta gram", "instagram"},
            {"flash light", "flashlight"},
            {"কটা বাজে", "time"},
            {"ক\u09DFটা বাজে", "time"},
            {"कितने बजे", "time"},
            {"कितने बजे हैं", "time"},
            {"গান চালাও", "play music"},
            {"গান শোনাও", "play music"},
            {"गाना चलाओ", "play music"},
            {"गाना सुनाओ", "play music"},
            {"টর্চ চালাও", "torch on"},
            {"টর্চ বন্ধ করো", "torch off"},
            {"ফ্ল্যাশলাইট চালাও", "flashlight on"},
            {"ফ্ল্যাশলাইট বন্ধ করো", "flashlight off"},
            {"फ्लैशलाइट चालू करो", "flashlight on"},
            {"टॉर्च चालू करो", "torch on"},
            {"टॉर्च बंद करो", "torch off"},
            {"কল করো", "call"},
            {"कॉल करो", "call"},
            {"মেসেজ করো", "whatsapp"},
            {"মেসেজ পাঠাও", "whatsapp"},
            {"मैसेज करो", "whatsapp"},
            {"मैसेज भेजो", "whatsapp"},
            {"call koro", "call"},
            {"call karo", "call"},
            {"বন্ধ করো", "off"},
            {"बंद करो", "off"},
            {"শুভ সকাল", "good morning"},
            {"शुभ प्रभात", "good morning"},
            {"শুভ রাত্রি", "good night"},
            {"शुभ रात्रि", "good night"},
            {"মনে রাখো", "remember"},
            {"याद रखो", "remember"},
            {"মনে রেখেছো", "remember"},
            {"আমার ফোন কোথা\u09DF", "where is my phone"},
            {"मेरा फोन कहाँ है", "where is my phone"},
            {"ফোন কোথা\u09DF", "where is my phone"},
            {"ফোন লক করো", "lock phone"},
            {"फोन लॉक करो", "lock phone"},
            {"হোমে যাও", "go home"},
            {"होम जाओ", "go home"},
            {"পিছনে যাও", "go back"},
            {"पीछे जाओ", "go back"},
            {"স্ক্রিনশট নাও", "screenshot"},
            {"डू नॉट डिस्टर्ब", "do not disturb"},
            {"ডু নট ডিস্টার্ব", "do not disturb"},
            {"স্ক্রল করো", "scroll down"},
            {"নিচে স্ক্রল", "scroll down"},
            {"উপরে স্ক্রল", "scroll up"},
            {"দ\u09DFা করে", ""},
    };

    // Single words: mishearings, Bengali/Hindi command and app words,
    // people (so "মাকে কল করো" finds the contact saved as "Maa"),
    // and filler verbs that are dropped.
    private static final String[][] NORMALIZE_WORDS = {
            // Misheard English.
            {"yutube", "youtube"}, {"utube", "youtube"}, {"wether", "weather"},
            {"whether", "weather"}, {"alram", "alarm"}, {"allarm", "alarm"},
            {"opne", "open"}, {"oppen", "open"}, {"timmer", "timer"},
            {"please", ""},
            // App names - Bengali.
            {"ইউটিউব", "youtube"}, {"হো\u09DFাটসঅ্যাপ", "whatsapp"}, {"হো\u09DFাটসাপ", "whatsapp"},
            {"ফেসবুক", "facebook"}, {"ইনস্টাগ্রাম", "instagram"}, {"ইনস্টা", "instagram"},
            {"ক্রোম", "chrome"}, {"জিমেইল", "gmail"}, {"ম্যাপস", "maps"}, {"ম্যাপ", "maps"},
            {"টেলিগ্রাম", "telegram"}, {"স্পটিফাই", "spotify"}, {"নেটফ্লিক্স", "netflix"},
            {"ক্যামেরা", "camera"}, {"গ্যালারি", "gallery"}, {"সেটিংস", "settings"},
            {"ক্যালকুলেটর", "calculator"}, {"ঘ\u09DCি", "clock"},
            // App names - Hindi.
            {"यूट्यूब", "youtube"}, {"व्हाट्सएप", "whatsapp"}, {"व्हाट्सऐप", "whatsapp"},
            {"फेसबुक", "facebook"}, {"इंस्टाग्राम", "instagram"}, {"इंस्टा", "instagram"},
            {"क्रोम", "chrome"}, {"जीमेल", "gmail"}, {"मैप्स", "maps"}, {"मैप", "maps"},
            {"टेलीग्राम", "telegram"}, {"स्पॉटिफाई", "spotify"}, {"नेटफ्लिक्स", "netflix"},
            {"कैमरा", "camera"}, {"गैलरी", "gallery"}, {"सेटिंग्स", "settings"},
            {"कैलकुलेटर", "calculator"}, {"घ\u095Cी", "clock"},
            // Command words - Bengali.
            {"খোলো", "open"}, {"খুলো", "open"}, {"খোল", "open"},
            {"সম\u09DF", "time"}, {"তারিখ", "date"}, {"আবহাও\u09DFা", "weather"},
            {"খবর", "news"}, {"অ্যালার্ম", "alarm"}, {"টাইমার", "timer"},
            {"টর্চ", "torch"}, {"ফ্ল্যাশলাইট", "flashlight"},
            {"চালু", "on"}, {"বন্ধ", "off"}, {"কল", "call"},
            {"গান", "play music"}, {"হিসাব", "calculate"}, {"অনুবাদ", "translate"},
            {"রিমাইন্ডার", "remind"}, {"নোট", "note"}, {"জোকস", "joke"},
            {"ক্রিকেট", "cricket"}, {"সকাল", "am"}, {"সন্ধ্যা", "pm"},
            {"রাত", "pm"}, {"দুপুর", "pm"},
            {"মিনিট", "minute"}, {"মিনিটের", "minute"}, {"ঘণ্টা", "hour"},
            {"ঘণ্টার", "hour"}, {"সেকেন্ড", "second"}, {"সেকেন্ডের", "second"},
            {"মানে", "meaning"}, {"কি", ""},
            // Command words - Hindi.
            {"खोलो", "open"}, {"खोल", "open"},
            {"समय", "time"}, {"तारीख", "date"}, {"मौसम", "weather"},
            {"खबर", "news"}, {"अलार्म", "alarm"}, {"टाइमर", "timer"},
            {"टॉर्च", "torch"}, {"फ्लैशलाइट", "flashlight"},
            {"चालू", "on"}, {"बंद", "off"}, {"कॉल", "call"},
            {"गाना", "play music"}, {"गाने", "play music"}, {"हिसाब", "calculate"},
            {"अनुवाद", "translate"}, {"रिमाइंडर", "remind"}, {"नोट", "note"},
            {"जोक", "joke"}, {"क्रिकेट", "cricket"}, {"सुबह", "am"}, {"शाम", "pm"},
            {"रात", "pm"}, {"दोपहर", "pm"},
            {"मिनट", "minute"}, {"मिनटों", "minute"}, {"घंटा", "hour"},
            {"घंटे", "hour"}, {"सेकंड", "second"},
            {"मतलब", "meaning"}, {"क्या", ""}, {"कृपया", ""},
            // People - so "মাকে কল করো" finds the contact saved as "Maa".
            {"মা", "Maa"}, {"মাকে", "Maa"}, {"মাম্মি", "Mummy"}, {"বাবা", "Baba"},
            {"বাবাকে", "Baba"}, {"ভাই", "Bhai"}, {"ভাইকে", "Bhai"}, {"বোন", "Bon"},
            {"বোনকে", "Bon"}, {"দাদা", "Dada"}, {"দাদাকে", "Dada"}, {"দিদি", "Didi"},
            {"দিদিকে", "Didi"},
            {"माँ", "Maa"}, {"मम्मी", "Mummy"}, {"पापा", "Papa"}, {"पिताजी", "Papa"},
            {"भाई", "Bhai"}, {"बहन", "Behen"}, {"दादा", "Dada"}, {"दीदी", "Didi"},
            // Filler verbs - dropped.
            {"করো", ""}, {"দাও", ""}, {"পাঠাও", ""}, {"শোনাও", ""},
            {"करो", ""}, {"दो", ""}, {"भेजो", ""}, {"सुनाओ", ""},
            // More verb forms.
            {"খুলে", "open"}, {"খুলুন", "open"}, {"দেখাও", "open"}, {"জ্বালাও", "on"},
            {"নিভাও", "off"}, {"खोलिए", "open"}, {"दिखाओ", "open"}, {"जलाओ", "on"},
            {"बुझाओ", "off"}, {"बताओ", ""}, {"कीजिए", ""}, {"दीजिए", ""},
            // Romanized Bengali/Hindi - when the recognizer writes Bengali in Latin letters.
            {"kholo", "open"}, {"khule", "open"}, {"khulo", "open"}, {"khol", "open"},
            {"shomoy", "time"}, {"somoy", "time"}, {"abhawa", "weather"},
            {"khobor", "news"}, {"ma", "Maa"}, {"maa", "Maa"}, {"maake", "Maa"},
            {"baba", "Baba"}, {"bhai", "Bhai"}, {"bon", "Bon"},
            {"dada", "Dada"}, {"didi", "Didi"}, {"koro", ""}, {"dao", ""},
            {"daw", ""}, {"jalao", "on"}, {"nibhao", "off"}, {"dekhao", "open"},
            {"bolo", ""}, {"batao", ""},
            // Phone controls / device status.
            {"ভলিউম", "volume"}, {"আও\u09DFাজ", "volume"}, {"ब\u095Dाओ", "up"}, {"घटाओ", "down"},
            {"বা\u09DCাও", "up"}, {"কমাও", "down"},
            {"ব্যাটারি", "battery"}, {"बैटरी", "battery"},
            {"স্টোরেজ", "storage"}, {"स्टोरेज", "storage"},
            {"ব্রাইটনেস", "brightness"}, {"ब्राइटनेस", "brightness"},
            {"ও\u09DFাইফাই", "wifi"}, {"वाईफाई", "wifi"},
            {"ব্লুটুথ", "bluetooth"}, {"ब्लूटूथ", "bluetooth"},
            // Camera.
            {"ছবি", "photo"}, {"সেলফি", "selfie"}, {"फोटो", "photo"}, {"सेल्फी", "selfie"},
            // Music control.
            {"gaan", "music"}, {"gaana", "music"}, {"bajao", "play"}, {"thamo", "pause"},
            {"porer", "next"}, {"ager", "previous"}, {"chalu", "on"}, {"bondho", "off"},
            {"বাজাও", "play"}, {"থামাও", "pause"}, {"পরের", "next"}, {"আগের", "previous"},
            {"बजाओ", "play"}, {"रुको", "pause"}, {"अगला", "next"}, {"पिछला", "previous"},
            // Memory.
            {"হলো", "is"}, {"है", "is"}, {"নম্বর", "number"}, {"नंबर", "number"},
            {"কী", "what"},
            // More commands.
            {"লন্ডন", "london"}, {"লন্ডনে", "london"},
            {"ঢাকা", "dhaka"}, {"ঢাকা\u09DF", "dhaka"},
            {"দিল্লি", "delhi"}, {"দুবাই", "dubai"},
            {"টোকিও", "tokyo"}, {"প্যারিস", "paris"}, {"সিঙ্গাপুর", "singapore"},
            {"প্রতিদিন", "every day"}, {"रोज", "every day"},
            {"স্ক্রিনশট", "screenshot"}, {"स्क्रीनशॉट", "screenshot"},
            {"লক", "lock"}, {"लॉक", "lock"},
            {"সাইলেন্ট", "silent"}, {"साइलेंट", "silent"},
            {"ভাইব্রেট", "vibrate"}, {"वाइब्रेट", "vibrate"},
            {"পাসও\u09DFার্ড", "password"}, {"पासवर्ड", "password"},
            {"ক\u09DFেন", "coin"}, {"सिक्का", "coin"},
            {"ছক্কা", "dice"}, {"पासा", "dice"},
            {"আইপি", "ip"}, {"आईपी", "ip"},
            {"স্টপও\u09DFাচ", "stopwatch"}, {"स्टॉपवॉच", "stopwatch"},
            {"নোটিফিকেশন", "notifications"}, {"नोटिफिकेशन", "notifications"},
            {"স্ক্রল", "scroll"}, {"स्क्रॉल", "scroll"},
            {"এসওএস", "sos"},

    };

    // Apps known by package name, for fuzzy matching of misheard names.
    private static final String[] KNOWN_APP_NAMES = {"youtube", "whatsapp", "facebook", "instagram",
            "chrome", "gmail", "maps", "play store", "telegram", "spotify", "snapchat",
            "twitter", "x", "tiktok", "netflix", "camera", "gallery", "settings",
            "clock", "calculator"};

    // Bengali written in Latin letters - the recognizer on many phones
    // writes Bengali this way even when Bengali is selected ("ami banglai
    // kotha bolbo"). Two or more of these words = the user speaks Bengali.
    private static final String[] ROMAN_BN = {"ami", "tumi", "se", "amra", "tomra", "kotha",
            "katha", "bolbo", "bolbe", "bolo", "bol", "bolchi", "bolchhi", "korbo", "korbe",
            "korchi", "dao", "daw", "debo", "dibo", "chai", "chao", "jabo", "jabe", "asbo",
            "asbe", "khabo", "khabe", "ghum", "uthbo", "shomoy", "somoy", "ekhon", "akhon",
            "kal", "aj", "aaj", "bhalo", "valo", "kharap", "kemon", "keman", "koto", "keno",
            "kothay", "kothai", "amar", "tomar", "amader", "tomader", "tar", "tader", "eta",
            "ota", "ebong", "kintu", "jodi", "tahole", "pore", "age", "bashay", "barite",
            "kholo", "khule", "shono", "dekhao", "chalu", "bondho", "bajao", "thamo", "cholo",
            "jachhi", "aschhi", "parbo", "parbe", "pari", "paro", "parchi", "hochhe", "hocche",
            "hobe", "holo", "chilo", "ache", "achhe", "nei", "thik", "bhul", "moja", "hashi",
            "prem", "bondhu", "maa", "baba", "bhai", "bon", "dada", "didi", "bhat", "mach",
            "mangsho", "cha", "jol", "pani", "khawa", "khabar", "kaj", "pora", "lekha",
            "school", "college", "office", "bazaar", "dokan", "rasta", "gari", "train",
            "taka", "poisha", "daam", "bangla", "banglai", "hindi", "phool", "ful", "bal",
            "chuke", "fal", "na"};

    private static final String[] ROMAN_HI = {"main", "tum", "aap", "kya", "kaise", "kahan",
            "kab", "kyun", "mera", "tumhara", "hamara", "aaj", "kal", "abhi", "accha", "acha",
            "bura", "kitna", "kaisa", "bolo", "bol", "karo", "jao", "aao", "khao", "so", "utho",
            "ghar", "paise", "kaam", "baat", "batao", "sunao", "dekho", "khol", "chalu", "band",
            "gaana", "gana", "bajao", "ruko", "chalo", "ja", "aa", "hai", "tha", "hoga",
            "hain", "nahi", "haan", "thik", "galat", "dost", "maa", "papa", "bhai", "behen",
            "dada", "didi", "pani", "khana", "chai", "bhaiya", "arre", "yaar", "bilkul",
            "zaroor", "shayad", "matlab", "kyunki", "lekin", "phir", "pehle", "baad",
            "andar", "bahar"};

    /** 0 = English, 1 = Bengali, 2 = Hindi - from the script used. */

    private static int detectLang(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') return 1;
            if (c >= '\u0900' && c <= '\u097F') return 2;
        }
        return 0;
    }

    /** 0 = English, 1 = Bengali, 2 = Hindi - also catches Bengali/Hindi
     * written in Latin letters, which is what the recognizer often returns. */
    public static int detectSpokenLang(String text) {
        int script = detectLang(text);
        if (script != 0) return script;
        String[] ws = text.toLowerCase(Locale.ENGLISH).split("\\s+");
        int bn = 0, hi = 0;
        for (String w : ws) {
            for (String b : ROMAN_BN) if (w.equals(b)) { bn++; break; }
            for (String h : ROMAN_HI) if (w.equals(h)) { hi++; break; }
        }
        if (bn >= 2) return 1;
        if (hi >= 2) return 2;
        return 0;
    }

    /** The reply language the user chose in Settings (voice_lang):
     * 0 = English, 1 = Bengali, 2 = Hindi. Settings wins for ALL chat -
     * if Bengali is selected, every reply is in Bengali; if English is
     * selected, every reply is in English. */
    public static int getPreferredLang(Context ctx) {
        String v = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                .getString("voice_lang", "en-IN");
        if ("bn-IN".equals(v)) return 1;
        if ("hi-IN".equals(v)) return 2;
        return 0;
    }

    /** System prompt for Gemini / free AI: answer in the Settings language. */
    public static String aiSystemPrompt(Context ctx, String text) {
        String base = "You are Black Mask AI, a helpful personal AI assistant built by Arif. "
                + "Keep replies short and useful.";
        int lang = getPreferredLang(ctx);
        if (lang == 1) return base + " Reply in Bengali script (বাংলা), in a friendly tone.";
        if (lang == 2) return base + " Reply in Hindi (Devanagari script), in a friendly tone.";
        return base + " Reply in English.";
    }

    /** What to say when the free AI cannot be reached - in the Settings language. */
    public static String aiFailMessage(Context ctx, String text) {
        int lang = getPreferredLang(ctx);
        if (lang == 1) return "এখন ফ্রি AI-এর সাথে যোগাযোগ হচ্ছে না। অ্যাপে তোমার ফ্রি Gemini API key দিলে আরও ভালো উত্তর পাবে, অথবা আবার চেষ্টা করো।";
        if (lang == 2) return "अभी फ्री AI से संपर्क नहीं हो रहा। ऐप में अपनी फ्री Gemini API key डालने पर बेहतर जवाब मिलेगा, या फिर से कोशिश करो।";
        return "I could not reach the free AI right now. Add your free Gemini API key in the app for the best chat, or try again.";
    }

    /** Reply in the language the user spoke. */
    private static String loc(int lang, String en, String bn, String hi) {
        if (lang == 1) return bn;
        if (lang == 2) return hi;
        return en;
    }

    /** Fix what the recognizer (or the user) got slightly wrong, and turn
     * Bengali / Hindi commands into the same English form the dispatcher knows.
     * Bengali and Hindi put the verb last ("youtube kholo", "Maa ke call karo"),
     * so the verb is moved to the front. */
    private static String normalizeCommand(String raw) {
        // NFC first: the recognizer and the tables must use the same form
        // for letters like য় / ড় (composed vs base + nukta).
        String s = " " + Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ENGLISH).trim() + " ";
        // Bengali + Devanagari digits -> 0-9.
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u09E6' && c <= '\u09EF') digits.append((char) ('0' + (c - '\u09E6')));
            else if (c >= '\u0966' && c <= '\u096F') digits.append((char) ('0' + (c - '\u0966')));
            else digits.append(c);
        }
        s = digits.toString();
        // "Wi-Fi" -> "wifi": a hyphen broke every contains() check, so
        // "Wi-Fi on" fell through to the AI instead of the WiFi branch.
        s = s.replace("-", "");
        // Phrases first (multi-word), then single words. Space padding keeps
        // the matches whole-word only, in any script.
        for (String[] p : NORMALIZE_PHRASES) s = s.replace(" " + p[0] + " ", " " + p[1] + " ");
        for (String[] p : NORMALIZE_WORDS) s = s.replace(" " + p[0] + " ", " " + p[1] + " ");
        s = s.replaceAll(" +", " ").trim();
        // Fuzzy: fix a Bengali/Hindi word heard one or two letters wrong.
        String[] parts = s.split(" ");
        StringBuilder fixed = new StringBuilder();
        for (String w : parts) {
            String rep = fuzzyWord(w);
            fixed.append(rep != null ? rep : w).append(' ');
        }
        s = fixed.toString().replaceAll(" +", " ").trim();
        // Verb-final order: "youtube open" -> "open youtube", "Maa call" -> "call Maa".
        if (s.endsWith(" open")) s = "open " + s.substring(0, s.length() - 5).trim();
        else if (s.endsWith(" play")) s = "play " + s.substring(0, s.length() - 5).trim();
        else if (s.endsWith(" call")) {
            String before = dropFillers(s.substring(0, s.length() - 5).trim());
            if (!before.isEmpty()) s = "call " + before;
        } else if (s.endsWith(" meaning")) s = "meaning of " + s.substring(0, s.length() - 8).trim();
        return s;
    }

    /** Drop filler words ("আমার", "কে", "please") from a name. */
    private static String dropFillers(String s) {
        String f = " " + s + " ";
        for (String w : new String[]{"আমার", "কে", "আমাকে", "একটা", "একটি", "একটু", "একবার", "please", "দ\u09DFা করে", "मुझे", "को", "कृपया", "जरा", "थो\u095Cा", "थोड़ा",
                "ke", "ko", "mein", "se", "ek", "baar", "koro", "kar"}) {
            f = f.replace(" " + w + " ", " ");
        }
        return f.replaceAll(" +", " ").trim();
    }

    /** True when the word is written in Bengali or Devanagari script. */
    private static boolean hasIndicScript(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= '\u0980' && c <= '\u09FF') || (c >= '\u0900' && c <= '\u097F')) return true;
        }
        return false;
    }

    /** Bengali/Hindi word with dependent vowel signs and virama stripped -
     * the consonant skeleton, which the recognizer usually gets right even
     * when it drops a vowel sign ("ইউটুব" and "ইউটিউব" share one). */
    private static String skeleton(String w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= '\u0981' && c <= '\u0983') || (c >= '\u09BE' && c <= '\u09CC')
                    || c == '\u09CD'
                    || (c >= '\u0901' && c <= '\u0902') || (c >= '\u093E' && c <= '\u094C')
                    || c == '\u094D') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** A Bengali/Hindi word the recognizer heard slightly wrong
     * ("ইউটুব" for "ইউটিউব") - returns what it should have been, or null.
     * Skeletons must match closely, so "ফোনে" (phone) never becomes
     * "মানে" (meaning), and English words never change. */
    private static String fuzzyWord(String w) {
        if (!hasIndicScript(w) || w.length() < 4) return null;
        String sk = skeleton(w);
        if (sk.length() < 4) return null;
        for (String[] p : NORMALIZE_WORDS) {
            String key = p[0];
            if (key.length() >= 4 && hasIndicScript(key)) {
                String ksk = skeleton(key);
                if (ksk.length() >= 4 && levenshtein(sk, ksk) <= 1) return p[1];
            }
        }
        return null;
    }

    /** Returns true if the text was a local command (handled here). */
    public static boolean handle(Context ctx, String text, Reply reply) {
        // Reply language = what the user chose in Settings (voice_lang).
        // Input script = what they actually typed (for names and messages).
        int lang = getPreferredLang(ctx);
        int inputLang = detectLang(text);
        String nt = normalizeCommand(text);
        String src = inputLang == 0 ? text : nt;
        String t = nt.toLowerCase(Locale.ENGLISH).trim();

        // World clock: "time in london" / "লন্ডনে সম\u09DF কত".
        if (t.contains("time")) {
            for (Map.Entry<String, String> e : CITY_TIMEZONES.entrySet()) {
                if (t.contains(e.getKey())) {
                    SimpleDateFormat f = new SimpleDateFormat("hh:mm a", Locale.ENGLISH);
                    f.setTimeZone(TimeZone.getTimeZone(e.getValue()));
                    String when = f.format(new Date());
                    String cap = e.getKey().substring(0, 1).toUpperCase(Locale.ENGLISH) + e.getKey().substring(1);
                    reply.say(loc(lang, "It is " + when + " in " + cap + ".",
                            cap + "-এ এখন " + when + "।", cap + " में अभी " + when + " है।"));
                    return true;
                }
            }
        }

        if (t.contains("time")) {
            String now = new SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault()).format(new Date());
            reply.say(loc(lang, "It is " + now + ".", "এখন " + now + "।", "अभी " + now + " है।"));
            return true;
        }
        if (t.contains("date") || t.contains("day is")) {
            String d = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(new Date());
            reply.say(loc(lang, "Today is " + d + ".", "আজ " + d + "।", "आज " + d + " है।"));
            return true;
        }
        if (t.contains("weather")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for the weather."); return true; }
            reply.say(loc(lang, "Checking the weather...", "আবহাও\u09DFা দেখছি...", "मौसम देख रहा हूँ..."));
            new Thread(() -> reply.say(fetchWeather(lang))).start();
            return true;
        }
        if (t.contains("news") || t.contains("headline")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for the news."); return true; }
            reply.say(loc(lang, "Fetching today's headlines...", "আজকের খবর আনছি...", "आज की खबरें ला रहा हूँ..."));
            new Thread(() -> reply.say(fetchNews(lang))).start();
            return true;
        }
        if (t.contains("cricket") || t.contains("live score")) {
            openUrl(ctx, "https://www.espncricinfo.com/live-cricket-score");
            reply.say("Opening live cricket scores.");
            return true;
        }
        if (t.contains("joke")) {
            if (!isOnline(ctx)) { reply.say(JOKES[random.nextInt(JOKES.length)]); return true; }
            new Thread(() -> {
                String j = fetchJoke();
                reply.say(j != null ? j : JOKES[random.nextInt(JOKES.length)]);
            }).start();
            return true;
        }
        // Free no-key APIs, one after another - same as the WhatsApp bot.
        if (t.contains("bitcoin") || t.contains("btc") || t.contains("crypto")
                || t.contains("ethereum") || t.contains("dogecoin") || t.contains("solana")
                || t.contains("xrp") || t.contains("litecoin")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for crypto prices."); return true; }
            final String coin = coinId(t);
            reply.say("Checking crypto prices...");
            new Thread(() -> reply.say(fetchCrypto(coin))).start();
            return true;
        }
        if (t.startsWith("convert ") || t.contains(" convert ")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net to convert currency."); return true; }
            Matcher cm = Pattern.compile("\\b(usd|inr|eur|gbp|bdt|aed|jpy|aud|cad|sgd|chf|cny|krw|rub|try|zar|nzd|thb|myr|idr|php|pkr|npr|lkr)\\b").matcher(t);
            List<String> found = new ArrayList<>();
            while (cm.find()) found.add(cm.group(1).toUpperCase(Locale.ENGLISH));
            Matcher am = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(t);
            double amount = am.find() ? Double.parseDouble(am.group(1)) : 1;
            if (found.isEmpty()) {
                reply.say("Say it like: convert 100 usd to inr.");
            } else {
                final String from = found.get(0);
                final String to = found.size() > 1 ? found.get(1) : (from.equals("INR") ? "USD" : "INR");
                final double amt = amount;
                reply.say("Converting...");
                new Thread(() -> reply.say(fetchCurrency(amt, from, to))).start();
            }
            return true;
        }
        if (t.startsWith("meaning of ") || t.startsWith("meaning ") || t.startsWith("define ") || t.startsWith("definition of ")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for meanings."); return true; }
            String word = src.replaceFirst("(?i)^(meaning of|meaning|define|definition of)\\s+", "").trim().split("\\s+")[0].replaceAll("[^a-zA-Z-]", "");
            if (word.isEmpty()) { reply.say("Say it like: meaning of hello."); return true; }
            final String w = word.toLowerCase(Locale.ENGLISH);
            reply.say("Looking up " + w + "...");
            new Thread(() -> reply.say(fetchMeaning(w))).start();
            return true;
        }
        if (t.contains("tell me a fact") || t.startsWith("fact") || t.contains("random fact") || t.contains("interesting fact")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for facts."); return true; }
            new Thread(() -> reply.say(fetchFact())).start();
            return true;
        }
        if (t.contains("quote") || t.contains("motivat") || t.contains("inspire me")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for quotes."); return true; }
            new Thread(() -> reply.say(fetchQuote())).start();
            return true;
        }
        if (t.contains("advice")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for advice."); return true; }
            new Thread(() -> reply.say(fetchAdvice())).start();
            return true;
        }
        if (t.contains("horoscope")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net for horoscopes."); return true; }
            String sign = null;
            for (String s : SIGNS) if (t.contains(s)) { sign = s; break; }
            if (sign == null) { reply.say("Which sign? Say like: aries horoscope."); return true; }
            final String sg = sign;
            reply.say("Checking your horoscope...");
            new Thread(() -> reply.say(fetchHoroscope(sg))).start();
            return true;
        }
        if (t.startsWith("remember ")) {
            // "remember my bike number is WB12AB3456" - a memory, not a note.
            String low = text.toLowerCase(Locale.ENGLISH);
            String sep = null;
            int sepAt = -1;
            for (String sepTry : new String[]{" is ", " হলো ", " है "}) {
                int i = low.indexOf(sepTry);
                if (i > 0) { sep = sepTry; sepAt = i; break; }
            }
            if (sep != null) {
                String key = stripMemoryKey(normalizeCommand(t.substring(9, t.indexOf(" is ")).trim()));
                String value = text.substring(sepAt + sep.length()).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    saveMemory(ctx, key, value);
                    reply.say(loc(lang, "Remembered: " + key + " is " + value + ".", "মনে রাখলাম: " + key + " হলো " + value + "।", "याद रख लिया: " + key + " है " + value + "।"));
                    return true;
                }
            }
            // No "is" - fall back to the old note behaviour.
            String note = src.substring(src.indexOf(' ') + 1).trim();
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("note", note).apply();
            reply.say("Noted: " + note);
            return true;
        }
        if (t.startsWith("note ")) {
            String note = src.substring(src.indexOf(' ') + 1).trim();
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("note", note).apply();
            reply.say("Noted: " + note);
            return true;
        }
        if (t.contains("my note") || t.contains("show note")
                || t.contains("আমার note") || t.contains("मेरा note")) {
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

        // "schedule sms" - open the Tools page, where the number, message and time are picked.
        if (t.contains("schedule sms") || t.contains("schedule message")) {
            try {
                Intent i = new Intent(ctx, MainActivity.class);
                i.putExtra("page", 2);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                reply.say(loc(lang, "Opening the scheduled SMS page. Pick a number, type the message and pick a time.",
                        "নির্ধারিত SMS-এর পেজ খুলছি। নম্বর বেছে নাও, মেসেজ লেখো, সময় বেছে নাও।",
                        "निर्धारित SMS का पेज खोल रहा हूँ। नंबर चुनो, मैसेज लिखो, समय चुनो।"));
            } catch (Exception e) {
                reply.say("Could not open the scheduled SMS page.");
            }
            return true;
        }

        // "sos" / "emergency" / "help me" - send the SOS SMS to the emergency contacts.
        if (t.equals("sos") || t.contains("emergency") || t.contains("help me")) {
            int sent = sendSos(ctx);
            if (sent < 0) {
                reply.say(loc(lang, "SMS permission is missing. Allow it in Settings, then try again.",
                        "SMS পারমিশন দেওয়া নেই। Settings থেকে পারমিশন দাও, তারপর আবার চেষ্টা করো।",
                        "SMS परमिशन नहीं दी गई है। Settings से परमिशन दो, फिर दोबारा कोशिश करो।"));
            } else if (sent > 0) {
                reply.say(loc(lang, "SOS sent to " + sent + " contact" + (sent > 1 ? "s" : "") + ".",
                        sent + " জন কন্ট্যাক্টকে SOS পাঠানো হয়েছে।",
                        sent + " संपर्कों को SOS भेजा गया है।"));
            } else {
                reply.say(loc(lang, "No emergency contact is set. Add one in Settings, Emergency SOS.",
                        "কোনো জরুরি কন্ট্যাক্ট সেট করা নেই। Settings-এ Emergency SOS-এ গিয়ে যোগ করো।",
                        "कोई आपातकालीन संपर्क सेट नहीं है। Settings में Emergency SOS में जाकर जोड़ो।"));
            }
            return true;
        }


        if (t.contains("help") || t.contains("what can you do")) {
            reply.say("I can chat with Gemini AI, tell time, date, weather and news, crypto prices, convert currency, word meanings, facts, quotes, advice and horoscopes, calculate, translate, open any app, send WhatsApp messages and SMS by name, call people, set alarms, timers and reminders, search the web and YouTube, turn the flashlight on or off, save notes and tell jokes. Say good morning for a briefing, or remember my bike number is WB12AB3456 to save a memory. If the phone is lost, send BLACKMASK RING by SMS from any other phone and it will ring loudly. With auto-tap on (Settings, Accessibility) I can also turn Wi-Fi on or off, take a photo, take a screenshot and lock the phone by myself, and scroll any app with 'scroll down' or 'scroll up'. I can also set silent or vibrate mode, do not disturb, generate a password, flip a coin, roll a dice and tell you your IP address. Ask where am I for your location, time in London for the time in another city, or remind me every day at 7 am to drink water for a daily reminder. Turn on the AUTOMATION toggles in Settings for SMS find-my-phone, OTP auto-copy, driving mode, missed-call SMS, reading notifications aloud and call screening. In Settings, Emergency SOS, pick up to 3 contacts - shake the phone 3 times and an SOS SMS with your location goes to them, or just say 'sos'. Just speak. If I don't understand, I'll show you options.");
            return true;
        }
        if (t.contains("flashlight") || t.contains("torch")) {
            boolean on = !t.contains("off");
            if (setTorch(ctx, on)) { flashlightOn = on; reply.say(loc(lang, on ? "Flashlight on." : "Flashlight off.", on ? "টর্চ জ্বলছে।" : "টর্চ বন্ধ।", on ? "टॉर्च जल रही है।" : "टॉर्च बंद है।"));
            } else reply.say("Could not control the flashlight. Please allow the camera permission.");
            return true;
        }

        // "good morning" - time + weather + headlines in one briefing.
        if (t.contains("good morning")) {
            String time = new SimpleDateFormat("hh:mm a, EEEE, dd MMMM yyyy", Locale.getDefault()).format(new Date());
            reply.say(loc(lang, "Good morning! It is " + time + ". Getting your briefing...",
                    "শুভ সকাল! এখন " + time + "। তোমার ব্রিফিং আনছি...",
                    "सुप्रभात! अभी " + time + " है। आपकी ब्रीफिंग ला रहा हूँ..."));
            if (isOnline(ctx)) new Thread(() -> reply.say(getBriefingText())).start();
            return true;
        }
        // "good night" - alarm for 7 AM, flashlight off.
        if (t.contains("good night")) {
            setTorch(ctx, false);
            openAlarmIntent(ctx, 7, 0);
            reply.say(loc(lang, "Good night! Alarm set for 7:00 AM. Sleep well.",
                    "শুভ রাত্রি! সকাল ৭টা\u09DF অ্যালার্ম দেও\u09DFা হ\u09DFেছে। ভালো করে ঘুমাও।",
                    "शुभ रात्रि! सुबह 7 बजे का अलार्म सेट हो गया। अच्छी नींद सो।"));
            return true;
        }

        // "where is my phone" - ring it loudly for 30 seconds.
        if (t.contains("where is my phone")) {
            startRing(ctx);
            reply.say(loc(lang, "Ringing your phone at full volume for 30 seconds. Tap Stop on the notification to silence it.",
                    "তোমার ফোন ৩০ সেকেন্ড জোরে বাজছে। থামাতে নোটিফিকেশনে Stop চাপো।",
                    "तुम्हारा फोन 30 सेकंड तक तेज़ बज रहा है। रोकने के लिए नोटिफिकेशन में Stop दबाओ।"));
            return true;
        }

        // "where am i" / "আমি কোথা\u09DF আছি" - current location (needs location permission).
        if (t.contains("where am i") || t.contains("আমি কোথা\u09DF")) {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                reply.say(loc(lang, "Location permission is off. Allow location in the app settings, then ask again.",
                        "লোকেশন অনুমতি বন্ধ আছে। অ্যাপ সেটিংসে লোকেশন চালু করে আবার বলো।",
                        "लोकेशन की अनुमति बंद है। ऐप सेटिंग्स में लोकेशन चालू करके फिर से बोलो।"));
                return true;
            }
            reply.say(loc(lang, "Finding your location...", "তোমার লোকেশন খুঁজছি...", "तुम्हारी लोकेशन खोज रहा हूँ..."));
            new Thread(() -> {
                try {
                    LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                    android.location.Location found = null;
                    if (lm != null) {
                        found = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (found == null) found = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (found == null) {
                        reply.say(loc(lang, "No location yet. Turn on GPS and open Maps once, then ask again.",
                                "এখনও লোকেশন পেলাম না। GPS চালু করে একবার Maps খুলে আবার বলো।",
                                "अभी लोकेशन नहीं मिली। GPS चालू करके एक बार Maps खोलकर फिर से बोलो।"));
                        return;
                    }
                    String url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + found.getLatitude() + "&lon=" + found.getLongitude();
                    String json = httpGet(url);
                    String place = null;
                    if (json != null) {
                        try { place = new JSONObject(json).optString("display_name", null); } catch (Exception ignored) {}
                    }
                    if (place != null && !place.isEmpty()) {
                        reply.say(loc(lang, "You are near: " + place, "তুমি এখানে আছো: " + place, "तुम यहाँ हो: " + place));
                    } else {
                        reply.say(String.format(Locale.ENGLISH, "Your location: %.5f, %.5f", found.getLatitude(), found.getLongitude()));
                    }
                } catch (Exception e) {
                    reply.say("Could not find your location.");
                }
            }).start();
            return true;
        }

        // "take a photo" / "selfie" - opens the camera (front camera for selfies).
        if (t.contains("photo") || t.contains("selfie")) {
            try {
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (t.contains("selfie")) i.putExtra("android.intent.extras.CAMERA_FACING", 1);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                if (BlackMaskAccessibilityService.isEnabled(ctx)) {
                    BlackMaskAccessibilityService.setPendingAction(ctx,
                            BlackMaskAccessibilityService.ACTION_CAMERA_SHUTTER);
                    reply.say(loc(lang, "Taking the photo...", "ছবি তুলছি...", "फोटो ले रहा हूँ..."));
                } else {
                    reply.say(loc(lang, "Opening the camera.", "ক্যামেরা খুলছি।", "कैमरा खोल रहा हूँ।"));
                }
            } catch (Exception e) {
                reply.say("Could not open the camera.");
            }
            return true;
        }

        // Music control - play / pause / next / previous on the music player.
        // (Not YouTube: "play X on youtube" has "youtube" in it and is skipped here.)
        if (!t.contains("youtube")) {
            boolean musicCtrl = t.contains("music") && (t.contains("pause") || t.contains("stop")
                    || t.contains("play") || t.contains("next") || t.contains("previous") || t.contains("resume"));
            boolean songCtrl = t.contains("song") && (t.contains("pause") || t.contains("stop")
                    || t.contains("next") || t.contains("previous"));
            if (musicCtrl || songCtrl) {
                if (t.contains("pause") || t.contains("stop")) {
                    mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    reply.say(loc(lang, "Music paused.", "গান থামলো।", "गाना रुक गया।"));
                } else if (t.contains("next")) {
                    mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
                    reply.say(loc(lang, "Next song.", "পরের গান।", "अगला गाना।"));
                } else if (t.contains("previous")) {
                    mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    reply.say(loc(lang, "Previous song.", "আগের গান।", "पिछला गाना।"));
                } else {
                    mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
                    reply.say(loc(lang, "Playing music.", "গান চলছে।", "गाना बज रहा है।"));
                }
                return true;
            }
        }

        // WiFi / Bluetooth - Android 10+ does not let apps flip the switch
        // directly, so the system panel opens and the user taps it once.
        if (t.contains("wifi")) {
            boolean wantOn = !t.contains("off");
            boolean auto = BlackMaskAccessibilityService.isEnabled(ctx);
            if (auto) {
                BlackMaskAccessibilityService.setPendingAction(ctx,
                        wantOn ? BlackMaskAccessibilityService.ACTION_WIFI_ON
                                : BlackMaskAccessibilityService.ACTION_WIFI_OFF);
            }
            try {
                Intent i = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? new Intent(Settings.Panel.ACTION_WIFI)
                        : new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
            if (auto) {
                reply.say(loc(lang, wantOn ? "Turning WiFi on..." : "Turning WiFi off...",
                        wantOn ? "WiFi চালু করছি..." : "WiFi বন্ধ করছি...",
                        wantOn ? "WiFi चालू कर रहा हूँ..." : "WiFi बंद कर रहा हूँ..."));
            } else {
                reply.say(loc(lang, "WiFi settings opened - turn it on or off there. Turn on auto-tap in Settings for hands-free WiFi.",
                        "WiFi সেটিংস খুলছি - এখান থেকে চালু/বন্ধ করো। হাতে না ছুঁ\u09DFে করতে Settings-এ auto-tap চালু করো।",
                        "WiFi सेटिंग्स खोल रहा हूँ - वहाँ से चालू/बंद करो। बिना हाथ लगाए करने के लिए Settings में auto-tap चालू करो।"));
            }
            return true;
        }
        if (t.contains("bluetooth")) {
            try {
                // There is no Bluetooth panel - open the Bluetooth settings screen.
                Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
            reply.say(loc(lang, "Bluetooth settings opened - turn it on or off there.",
                    "ব্লুটুথ সেটিংস খুলছি - এখান থেকে চালু/বন্ধ করো।",
                    "ब्लूटूथ सेटिंग्स खोल रहा हूँ - वहाँ से चालू/बंद करो।"));
            return true;
        }

        // "volume up / down / 50" - media volume.
        if (t.contains("volume")) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) { reply.say("Could not control the volume."); return true; }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = cur;
            if (t.contains("up")) next = Math.min(max, cur + Math.max(1, max / 10));
            else if (t.contains("down")) next = Math.max(0, cur - Math.max(1, max / 10));
            else {
                Matcher vm = Pattern.compile("(\\d{1,3})").matcher(t);
                if (vm.find()) {
                    int pct = Integer.parseInt(vm.group(1));
                    next = Math.max(0, Math.min(max, pct * max / 100));
                }
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            int pct = max > 0 ? next * 100 / max : 0;
            reply.say(loc(lang, "Volume " + pct + "%.", "ভলিউম " + pct + "%।", "वॉल्यूम " + pct + "%।"));
            return true;
        }

        // "brightness up / down / 50" - needs "Modify system settings" once.
        if (t.contains("brightness")) {
            if (!Settings.System.canWrite(ctx)) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception ignored) {}
                reply.say("Allow 'Modify system settings' once, then ask me again to change the brightness.");
                return true;
            }
            int cur = 128;
            try { cur = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); } catch (Exception ignored) {}
            int next = cur;
            if (t.contains("up")) next = Math.min(255, cur + 64);
            else if (t.contains("down")) next = Math.max(10, cur - 64);
            else {
                Matcher bm = Pattern.compile("(\\d{1,3})").matcher(t);
                if (bm.find()) {
                    int pct = Integer.parseInt(bm.group(1));
                    next = Math.max(10, Math.min(255, pct * 255 / 100));
                }
            }
            try {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
            } catch (Exception e) {
                reply.say("Could not change the brightness.");
                return true;
            }
            int pct = next * 100 / 255;
            reply.say(loc(lang, "Brightness " + pct + "%.", "ব্রাইটনেস " + pct + "%।", "ब्राइटनेस " + pct + "%।"));
            return true;
        }

        // "battery koto" - level + charging state.
        if (t.contains("battery")) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            boolean charging = false;
            try {
                Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                }
            } catch (Exception ignored) {}
            if (pct < 0) { reply.say("Could not read the battery."); return true; }
            reply.say(loc(lang, "Battery is " + pct + "%" + (charging ? " and charging." : "."),
                    "ব্যাটারি " + pct + "%" + (charging ? ", চার্জ হচ্ছে।" : "।"),
                    "बैटरी " + pct + "%" + (charging ? " है, चार्ज हो रही है।" : " है।")));
            return true;
        }

        // "storage koto" - free and total internal storage.
        if (t.contains("storage")) {
            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                double freeGb = sf.getAvailableBytes() / 1e9;
                double totalGb = sf.getTotalBytes() / 1e9;
                reply.say(String.format(Locale.ENGLISH, "Storage: %.1f GB free of %.1f GB.", freeGb, totalGb));
            } catch (Exception e) {
                reply.say("Could not read the storage.");
            }
            return true;
        }

        // Screenshot / lock / home / back / notifications - need auto-tap (accessibility).
        if (t.contains("screenshot")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("screenshot")) {
                reply.say(loc(lang, "Screenshot taken.", "স্ক্রিনশট নেও\u09DFা হ\u09DFেছে।", "स्क्रीनशॉट ले लिया।"));
            } else {
                reply.say(loc(lang, "To take screenshots, turn on auto-tap in Settings (Accessibility).",
                        "স্ক্রিনশট নিতে Settings-এ গি\u09DFে auto-tap (Accessibility) চালু করো।",
                        "स्क्रीनशॉट लेने के लिए Settings में auto-tap (Accessibility) चालू करो।"));
            }
            return true;
        }
        if (t.contains("lock") && (t.contains("phone") || t.contains("screen"))) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("lock")) {
                reply.say(loc(lang, "Locking the phone.", "ফোন লক করছি।", "फोन लॉक कर रहा हूँ।"));
            } else {
                reply.say(loc(lang, "To lock the phone by voice, turn on auto-tap in Settings (Accessibility).",
                        "ভ\u09DFেসে ফোন লক করতে Settings-এ গি\u09DFে auto-tap (Accessibility) চালু করো।",
                        "आवाज़ से फोन लॉक करने के लिए Settings में auto-tap (Accessibility) चालू करो।"));
            }
            return true;
        }
        if (t.contains("go home")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("home")) {
                reply.say(loc(lang, "Going home.", "হোমে যাচ্ছি।", "होम जा रहा हूँ।"));
            } else {
                reply.say("To go home by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }
        if (t.contains("go back")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("back")) {
                reply.say(loc(lang, "Going back.", "পিছনে যাচ্ছি।", "पीछे जा रहा हूँ।"));
            } else {
                reply.say("To go back by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }
        if (t.contains("notifications")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("notifications")) {
                reply.say(loc(lang, "Showing notifications.", "নোটিফিকেশন দেখাচ্ছি।", "नोटिफिकेशन दिखा रहा हूँ।"));
            } else {
                reply.say("To show notifications by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }
        if (t.contains("quick settings")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("quick_settings")) {
                reply.say(loc(lang, "Opening quick settings.", "কুইক সেটিংস খুলছি।", "क्विक सेटिंग्स खोल रहा हूँ।"));
            } else {
                reply.say("To open quick settings by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }

        // "scroll down" / "scroll up" - swipe the screen, works in YouTube,
        // Instagram, Facebook - any app (needs auto-tap).
        if (t.contains("scroll down") || t.equals("scroll")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("scroll_down")) {
                reply.say(loc(lang, "Scrolling down.", "নিচে স্ক্রল করছি।", "नीचे स्क्रॉल कर रहा हूँ।"));
            } else {
                reply.say("To scroll by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }
        if (t.contains("scroll up")) {
            if (BlackMaskAccessibilityService.isEnabled(ctx)
                    && BlackMaskAccessibilityService.doGlobal("scroll_up")) {
                reply.say(loc(lang, "Scrolling up.", "উপরে স্ক্রল করছি।", "ऊपर स्क्रॉल कर रहा हूँ।"));
            } else {
                reply.say("To scroll by voice, turn on auto-tap in Settings (Accessibility).");
            }
            return true;
        }

        // "driving mode on / off".
        if (t.contains("driving mode")) {
            boolean on = !t.contains("off");
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("driving", on).apply();
            reply.say(loc(lang, on ? "Driving mode on. Missed calls will get a driving SMS." : "Driving mode off.",
                    on ? "ড্রাইভিং মোড চালু। মিসড কলে 'আমি ড্রাইভ করছি' SMS যাবে।" : "ড্রাইভিং মোড বন্ধ।",
                    on ? "ड्राइविंग मोड चालू। मिस्ड कॉल पर 'मैं ड्राइव कर रहा हूँ' SMS जाएगा।" : "ड्राइविंग मोड बंद।"));
            return true;
        }

        // "reply <text>" - reply to the last WhatsApp/Telegram sender.
        if (t.startsWith("reply ")) {
            String sender = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                    .getString("last_notif_sender", "");
            String message = src.substring(6).trim();
            if (sender.isEmpty() || message.isEmpty()) {
                reply.say("No message to reply to yet. When a WhatsApp message arrives, I will read it aloud, then you can say: reply <your message>.");
                return true;
            }
            String number = findContactNumber(ctx, sender);
            if (number == null) { reply.say(loc(lang, "I could not find " + sender + " in your contacts.", sender + "-কে তোমার কন্ট্যাক্টে পেলাম না।", "मुझे " + sender + " तुम्हारे कॉन्टैक्ट में नहीं मिला।")); return true; }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/" + number + "?text=" + URLEncoder.encode(message, "UTF-8")));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.say("Opening WhatsApp to " + sender + " with your reply ready. Tap send.");
            } catch (Exception e) {
                reply.say("Could not open WhatsApp.");
            }
            return true;
        }

        // Silent / vibrate / normal ringer mode.
        if (t.contains("silent") || t.contains("vibrate") || t.contains("normal mode") || t.contains("ring mode")) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) { reply.say("Could not change the ringer mode."); return true; }
            int mode = AudioManager.RINGER_MODE_NORMAL;
            String modeName = "normal";
            if (t.contains("silent")) { mode = AudioManager.RINGER_MODE_SILENT; modeName = "silent"; }
            else if (t.contains("vibrate")) { mode = AudioManager.RINGER_MODE_VIBRATE; modeName = "vibrate"; }
            am.setRingerMode(mode);
            reply.say(loc(lang, "Ringer mode: " + modeName + ".",
                    "রিংগার মোড: " + modeName + "।", "रिंगर मोड: " + modeName + "।"));
            return true;
        }

        // "do not disturb on / off" - needs Do Not Disturb access once.
        if (t.contains("do not disturb")) {
            boolean wantOn = !t.contains("off");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(wantOn
                        ? NotificationManager.INTERRUPTION_FILTER_NONE
                        : NotificationManager.INTERRUPTION_FILTER_ALL);
                reply.say(loc(lang, wantOn ? "Do not disturb on." : "Do not disturb off.",
                        wantOn ? "ডু নট ডিস্টার্ব চালু।" : "ডু নট ডিস্টার্ব বন্ধ।",
                        wantOn ? "डू नॉट डिस्टर्ब चालू।" : "डू नॉट डिस्टर्ब बंद।"));
            } else {
                try {
                    Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception ignored) {}
                reply.say("Allow 'Do Not Disturb access' once, then ask me again.");
            }
            return true;
        }

        // "generate password" - a random 12-character password (local, offline).
        if (t.contains("generate password") || t.contains("new password")) {
            String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$";
            StringBuilder pw = new StringBuilder();
            for (int i = 0; i < 12; i++) pw.append(chars.charAt(random.nextInt(chars.length())));
            reply.say(loc(lang, "Your new password: " + pw,
                    "তোমার নতুন পাসও\u09DFার্ড: " + pw, "तुम्हारा नया पासवर्ड: " + pw));
            return true;
        }

        // "flip a coin" / "roll a dice" - local, offline.
        if (t.contains("flip a coin") || t.contains("coin toss") || t.equals("coin")) {
            boolean heads = random.nextBoolean();
            reply.say(loc(lang, heads ? "Heads." : "Tails.",
                    heads ? "হেড।" : "টেল।", heads ? "हेड।" : "टेल।"));
            return true;
        }
        if (t.contains("roll a dice") || t.contains("dice")) {
            int roll = random.nextInt(6) + 1;
            reply.say(loc(lang, "You rolled a " + roll + ".",
                    "তুমি " + roll + " পে\u09DFেছো।", "तुम्हें " + roll + " मिला।"));
            return true;
        }

        // "what is my ip" - public IP of this phone.
        if (t.contains("my ip") || t.contains("আমার ip") || t.contains("मेरा ip")) {
            if (!isOnline(ctx)) { reply.say("Internet is off. Turn on the net to check the IP."); return true; }
            new Thread(() -> {
                String ip = httpGet("https://api.ipify.org");
                reply.say(ip != null ? "Your IP address is " + ip + "." : "Could not fetch the IP. Check your internet.");
            }).start();
            return true;
        }

        // "stopwatch" - open the Clock app.
        if (t.contains("stopwatch")) {
            if (openApp(ctx, "clock")) reply.say(loc(lang, "Opening the Clock app.", "ঘ\u09DCি অ্যাপ খুলছি।", "घ\u095Cी ऐप खोल रहा हूँ।"));
            else reply.say("Could not open the Clock app.");
            return true;
        }

        // "what is my bike number" / "আমার বাইকের নম্বর কী" - recall a memory.
        if (t.startsWith("what is ") || t.endsWith(" what") || t.startsWith("tell me my ")) {
            String keyPart = t.startsWith("what is ") ? t.substring(8).trim()
                    : t.endsWith(" what") ? t.substring(0, t.length() - 5).trim()
                    : t.substring(11).trim();
            String key = stripMemoryKey(normalizeCommand(keyPart));
            String value = recallMemory(ctx, key);
            if (value != null) {
                reply.say(loc(lang, key + " is " + value + ".", key + " হলো " + value + "।", key + " है " + value + "।"));
                return true;
            }
            // Not a memory - fall through to calculator / AI.
        }
        if (t.startsWith("forget ")) {
            String key = stripMemoryKey(normalizeCommand(t.substring(7).trim()));
            if (forgetMemory(ctx, key)) reply.say("Forgot " + key + ".");
            else reply.say("I don't remember anything about " + key + ".");
            return true;
        }
        if (t.equals("what do you remember") || t.equals("তুমি what remember")) {
            List<String> keys = listMemoryKeys(ctx);
            if (keys.isEmpty()) reply.say("I don't remember anything yet. Say: remember my bike number is WB12AB3456.");
            else reply.say("I remember: " + String.join(", ", keys) + ".");
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
                String phrase = m.group(1).trim();
                String tgt = langCode(m.group(2));
                if (tgt == null) { reply.say("I don't know that language yet. Try Bengali, Hindi or English."); return true; }
                final String p = phrase, l = tgt;
                new Thread(() -> reply.say(translate(p, l))).start();
            } else {
                reply.say("Say it like: translate hello in Bengali.");
            }
            return true;
        }

        // "remind me in 10 minutes to drink water" / "remind me at 7 pm to call Maa"
        // "remind me every day at 7 am to drink water" - a daily reminder.
        if (t.startsWith("remind me ")) {
            Matcher inM = Pattern.compile("remind me in (\\d+)\\s*(minute|minutes|min|hour|hours|second|seconds)\\s+to\\s+(.+)").matcher(t);
            Matcher atM = Pattern.compile("remind me at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+to\\s+(.+)").matcher(t);
            Matcher dailyM = Pattern.compile("remind me every day at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+to\\s+(.+)").matcher(t);
            long when = -1;
            String what = null;
            boolean daily = false;
            if (dailyM.find()) {
                int hour = Integer.parseInt(dailyM.group(1));
                int min = dailyM.group(2) != null ? Integer.parseInt(dailyM.group(2)) : 0;
                String ap = dailyM.group(3);
                if ("pm".equals(ap) && hour < 12) hour += 12;
                if ("am".equals(ap) && hour == 12) hour = 0;
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
                when = cal.getTimeInMillis();
                what = dailyM.group(4);
                daily = true;
            } else if (inM.find()) {
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
                if (daily) {
                    scheduleDailyReminder(ctx, when, what);
                    reply.say(loc(lang, "Daily reminder set: " + what + ".",
                            "প্রতিদিনের রিমাইন্ডার দেও\u09DFা হ\u09DFেছে: " + what + "।",
                            "रोज़ का रिमाइंडर सेट हो गया: " + what + "।"));
                } else {
                    scheduleReminder(ctx, when, what);
                    reply.say("Reminder set: " + what);
                }
            } else {
                reply.say("Say it like: remind me in 10 minutes to drink water. Or: remind me at 7 pm to call Maa.");
            }
            return true;
        }

        // "set alarm 7 am" / "set alarm 7:30 pm" - goes into the system Clock app.
        if (t.contains("alarm")) {
            Matcher m = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(t);
            if (m.find()) {
                int hour = Integer.parseInt(m.group(1));
                int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                String ap = m.group(3);
                // "সকাল ৭টা" puts am/pm before the number - catch it here.
                if (ap == null && (t.startsWith("am ") || t.contains(" am "))) ap = "am";
                if (ap == null && (t.startsWith("pm ") || t.contains(" pm "))) ap = "pm";
                if ("pm".equals(ap) && hour < 12) hour += 12;
                if ("am".equals(ap) && hour == 12) hour = 0;
                if (hour < 0 || hour > 23 || min < 0 || min > 59) {
                    reply.say(loc(lang, "Tell me a valid time, like: set alarm 7 am.", "সঠিক সম\u09DF বলো, যেমন: set alarm 7 am।", "सही समय बताओ, जैसे: set alarm 7 am।"));
                    return true;
                }
                final String hm = hour + ":" + String.format(Locale.ENGLISH, "%02d", min);
                if (openAlarmIntent(ctx, hour, min)) {
                    reply.say(loc(lang, "Alarm set for " + hm + ".", "অ্যালার্ম দেও\u09DFা হ\u09DFেছে " + hm + "।", "अलार्म " + hm + " पर सेट हो गया।"));
                } else {
                    reply.say(loc(lang, "Could not open the Clock app.", "ঘ\u09DCি অ্যাপ খুলতে পারলাম না।", "घ\u095Cी ऐप नहीं खोल पाया।"));
                }
            } else {
                reply.say(loc(lang, "Tell me the time, like: set alarm 7 am.", "সম\u09DF বলো, যেমন: set alarm 7 am।", "समय बताओ, जैसे: set alarm 7 am।"));
            }
            return true;
        }

        // "set timer 5 minutes" - also in the system Clock app.
        if (t.contains("timer")) {
            Matcher m = Pattern.compile("(\\d+)\\s*(hour|minute|min|second|sec)").matcher(t);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                String unit = m.group(2);
                int seconds = unit.startsWith("hour") ? v * 3600 : unit.startsWith("min") ? v * 60 : v;
                if (openTimerIntent(ctx, seconds)) {
                    reply.say(loc(lang, "Timer set for " + v + " " + unit + ".", "টাইমার দেও\u09DFা হ\u09DFেছে " + v + " " + unit + "।", "टाइमर " + v + " " + unit + " के लिए सेट हो गया।"));
                } else {
                    reply.say(loc(lang, "Could not open the Clock app.", "ঘ\u09DCি অ্যাপ খুলতে পারলাম না।", "घ\u095Cी ऐप नहीं खोल पाया।"));
                }
            } else {
                reply.say(loc(lang, "Tell me the length, like: set timer 5 minutes.", "কতক্ষণ, বলো — যেমন: set timer 5 minutes।", "कितनी देर, बताओ — जैसे: set timer 5 minutes।"));
            }
            return true;
        }

        // "call <name>" - calls directly, no tap needed (needs CALL_PHONE permission).
        if (t.startsWith("call ")) {
            String name = src.substring(5).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say(loc(lang, "I could not find " + name + " in your contacts.", name + "-কে তোমার কন্ট্যাক্টে পেলাম না।", "मुझे " + name + " तुम्हारे कॉन्टैक्ट में नहीं मिला।")); return true; }
            String dial = dialNumber(number);
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null) {
                        tm.placeCall(Uri.parse("tel:" + dial), null);
                        reply.say(loc(lang, "Calling " + name + " (" + dial + ").", name + "-কে কল করছি (" + dial + ")।", name + " को कॉल कर रहा हूँ (" + dial + ")।"));
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            // No call permission - open the dialer with the number ready.
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + dial));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            reply.say("Opening the dialer for " + name + ". Tap call to connect.");
            return true;
        }

        // "whatsapp <name> <message>" / "message <name> <message>"
        if (t.startsWith("whatsapp ") || t.startsWith("message ")) {
            String rest = src.substring(src.indexOf(' ') + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp < 0) { reply.say("Tell me the name and the message, like: whatsapp Rahul I am coming."); return true; }
            String name = rest.substring(0, sp).trim();
            String message = rest.substring(sp + 1).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say(loc(lang, "I could not find " + name + " in your contacts.", name + "-কে তোমার কন্ট্যাক্টে পেলাম না।", "मुझे " + name + " तुम्हारे कॉन्टैक्ट में नहीं मिला।")); return true; }
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
            String rest = src.substring(src.indexOf(' ') + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp < 0) { reply.say("Tell me the name and the message, like: sms Rahul I am coming."); return true; }
            String name = rest.substring(0, sp).trim();
            String message = rest.substring(sp + 1).trim();
            String number = findContactNumber(ctx, name);
            if (number == null) { reply.say(loc(lang, "I could not find " + name + " in your contacts.", name + "-কে তোমার কন্ট্যাক্টে পেলাম না।", "मुझे " + name + " तुम्हारे कॉन्टैक्ट में नहीं मिला।")); return true; }
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
            String name = src.substring(5).trim();
            if (openApp(ctx, name)) {
                reply.say(loc(lang, "Opening " + name + ".", name + " খুলছি।", name + " खोल रहा हूँ।"));
                return true;
            }
            // Extra words around the app name ("আমার ফোনে ইউটিউব খুলে দাও") - try each word.
            for (String w : name.split("\\s+")) {
                if (openApp(ctx, w)) {
                    reply.say(loc(lang, "Opening " + w + ".", w + " খুলছি।", w + " खोल रहा हूँ।"));
                    return true;
                }
            }
            if (name.toLowerCase(Locale.ENGLISH).contains("youtube")) {
                openUrl(ctx, "https://www.youtube.com");
                reply.say(loc(lang, "YouTube app was not found, opening YouTube in the browser.", "ইউটিউব অ্যাপ পেলাম না, ব্রাউজারে ইউটিউব খুলছি।", "YouTube ऐप नहीं मिला, ब्राउज़र में YouTube खोल रहा हूँ।"));
            } else {
                reply.say(loc(lang, "I could not find an app called " + name + ".", name + " নামে কোনো অ্যাপ খুঁজে পেলাম না।", "मुझे " + name + " नाम का कोई ऐप नहीं मिला।"));
            }
            return true;
        }
        if (t.startsWith("play ")) {
            String q = src.substring(5).trim().replaceAll("(?i)\\s+on youtube$", "").trim();
            openUrl(ctx, "https://www.youtube.com/results?search_query=" + urlEncode(q));
            reply.say("Playing " + q + " on YouTube.");
            return true;
        }
        if (t.startsWith("search ")) {
            String q = src.substring(7).trim();
            openUrl(ctx, "https://www.google.com/search?q=" + urlEncode(q));
            reply.say("Searching for " + q + ".");
            return true;
        }

        // "youtube" / "ইউটিউব" on its own - the intent is to open it.
        for (String app : KNOWN_APP_NAMES) {
            if (t.contains(app) && openApp(ctx, app)) {
                reply.say(loc(lang, "Opening " + app + ".", app + " খুলছি।", app + " खोल रहा हूँ।"));
                return true;
            }
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

    /** Well-known apps by package name. Tried first, because Android 11+ hides
     * other apps from the installed-apps list unless the manifest declares a
     * <queries> entry - getLaunchIntentForPackage works for a specific package
     * even when the app is hidden from that list. */
    private static String knownPackage(String name) {
        switch (name) {
            case "youtube": return "com.google.android.youtube";
            case "whatsapp": return "com.whatsapp";
            case "facebook": return "com.facebook.katana";
            case "instagram": return "com.instagram.android";
            case "chrome": return "com.android.chrome";
            case "gmail": return "com.google.android.gm";
            case "maps": return "com.google.android.apps.maps";
            case "play store": return "com.android.vending";
            case "telegram": return "org.telegram.messenger";
            case "spotify": return "com.spotify.music";
            case "snapchat": return "com.snapchat.android";
            case "twitter": case "x": return "com.twitter.android";
            case "tiktok": return "com.zhiliaoapp.musically";
            case "netflix": return "com.netflix.mediaclient";
            default: return null;
        }
    }

    private static boolean openApp(Context ctx, String name) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String want = name.toLowerCase(Locale.ENGLISH).trim();
            // Known apps first: direct package launch works even when Android
            // hides the app from the installed-apps list.
            String pkg = knownPackage(want);
            if (pkg == null) {
                // Fuzzy: "yutube" still opens YouTube.
                int bestDist = 3;
                for (String k : KNOWN_APP_NAMES) {
                    int d = levenshtein(want, k);
                    if (d < bestDist) { bestDist = d; pkg = knownPackage(k); }
                }
            }
            if (pkg != null) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return true;
                }
            }
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            ApplicationInfo best = null, fuzzyBest = null;
            int bestFuzzy = 3;
            for (ApplicationInfo app : apps) {
                String label = String.valueOf(pm.getApplicationLabel(app)).toLowerCase(Locale.ENGLISH);
                if (label.equals(want)) { best = app; break; }
                if (best == null && label.contains(want)) best = app;
                int d = levenshtein(want, label);
                if (d < bestFuzzy) { bestFuzzy = d; fuzzyBest = app; }
            }
            if (best == null) best = fuzzyBest;
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

    /** Set an alarm in the system Clock app. Some Clock apps reject the
     * "skip the editor" extra, so try with it, then without, then just
     * open the Clock app. Returns true if the Clock opened. */
    private static boolean openAlarmIntent(Context ctx, int hour, int min) {
        Intent base = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Black Mask AI alarm")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(new Intent(base).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
            return true;
        } catch (Exception ignored) {}
        try {
            ctx.startActivity(base);
            return true;
        } catch (Exception ignored) {}
        return openApp(ctx, "clock");
    }

    /** Same fallback chain for a timer. */
    private static boolean openTimerIntent(Context ctx, int seconds) {
        Intent base = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Black Mask AI timer")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(new Intent(base).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
            return true;
        } catch (Exception ignored) {}
        try {
            ctx.startActivity(base);
            return true;
        } catch (Exception ignored) {}
        return openApp(ctx, "clock");
    }

    /** Bengali / Devanagari name -> Latin letters, so "রাহুল" finds "Rahul"
     * in contacts saved in English. Approximate by design - the fuzzy
     * stage in findContactNumber forgives the rest. */
    private static String transliterateIndic(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'অ': case 'আ': out.append("a"); break;
                case 'ই': case 'ঈ': case 'ি': case 'ী': out.append("i"); break;
                case 'উ': case 'ঊ': case 'ু': case 'ূ': out.append("u"); break;
                case 'ঋ': case 'ৃ': out.append("ri"); break;
                case 'এ': case 'ে': out.append("e"); break;
                case 'ঐ': case 'ৈ': out.append("oi"); break;
                case 'ও': case 'ো': out.append("o"); break;
                case 'ঔ': case 'ৌ': out.append("ou"); break;
                case 'া': out.append("a"); break;
                case 'ং': out.append("ng"); break;
                case 'ঃ': out.append("h"); break;
                case 'ক': out.append("k"); break;
                case 'খ': out.append("kh"); break;
                case 'গ': out.append("g"); break;
                case 'ঘ': out.append("gh"); break;
                case 'ঙ': out.append("ng"); break;
                case 'চ': out.append("ch"); break;
                case 'ছ': out.append("chh"); break;
                case 'জ': out.append("j"); break;
                case 'ঝ': out.append("jh"); break;
                case 'ঞ': case 'ণ': case 'ন': out.append("n"); break;
                case 'ট': out.append("t"); break;
                case 'ঠ': out.append("th"); break;
                case 'ড': out.append("d"); break;
                case 'ঢ': out.append("dh"); break;
                case 'ত': out.append("t"); break;
                case 'থ': out.append("th"); break;
                case 'দ': out.append("d"); break;
                case 'ধ': out.append("dh"); break;
                case 'প': out.append("p"); break;
                case 'ফ': out.append("ph"); break;
                case 'ব': out.append("b"); break;
                case 'ভ': out.append("bh"); break;
                case 'ম': out.append("m"); break;
                case 'য': out.append("y"); break;
                case 'র': case '\u09DC': out.append("r"); break;
                case 'ল': out.append("l"); break;
                case 'শ': case 'ষ': out.append("sh"); break;
                case 'স': out.append("s"); break;
                case 'হ': out.append("h"); break;
                case '\u09DD': out.append("rh"); break;
                case '\u09DF': out.append("y"); break;
                case 'ৎ': out.append("t"); break;
                case 'अ': case 'आ': out.append("a"); break;
                case 'इ': case 'ई': case 'ि': case 'ी': out.append("i"); break;
                case 'उ': case 'ऊ': case 'ु': case 'ू': out.append("u"); break;
                case 'ऋ': case 'ृ': out.append("ri"); break;
                case 'ए': case 'े': out.append("e"); break;
                case 'ऐ': case 'ै': out.append("ai"); break;
                case 'ओ': case 'ो': out.append("o"); break;
                case 'औ': case 'ौ': out.append("au"); break;
                case 'ा': out.append("a"); break;
                case 'ं': out.append("ng"); break;
                case 'ः': out.append("h"); break;
                case 'क': out.append("k"); break;
                case 'ख': out.append("kh"); break;
                case 'ग': out.append("g"); break;
                case 'घ': out.append("gh"); break;
                case 'ङ': out.append("ng"); break;
                case 'च': out.append("ch"); break;
                case 'छ': out.append("chh"); break;
                case 'ज': out.append("j"); break;
                case 'झ': out.append("jh"); break;
                case 'ञ': out.append("n"); break;
                case 'ट': out.append("t"); break;
                case 'ठ': out.append("th"); break;
                case 'ड': out.append("d"); break;
                case 'ढ': out.append("dh"); break;
                case 'त': out.append("t"); break;
                case 'थ': out.append("th"); break;
                case 'द': out.append("d"); break;
                case 'ध': out.append("dh"); break;
                case 'प': out.append("p"); break;
                case 'फ': out.append("ph"); break;
                case 'ब': out.append("b"); break;
                case 'भ': out.append("bh"); break;
                case 'म': out.append("m"); break;
                case 'य': out.append("y"); break;
                case 'र': out.append("r"); break;
                case 'ल': out.append("l"); break;
                case 'श': case 'ष': out.append("sh"); break;
                case 'स': out.append("s"); break;
                case 'ह': out.append("h"); break;
                case '\u095C': out.append("r"); break;
                case '\u095D': out.append("rh"); break;
                default:
                    // Virama, nukta and chandrabindu are silent.
                    if (c == '্' || c == '्' || c == 'ঁ' || c == 'ँ' || c == '়' || c == '़') break;
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    /** Find a contact's phone number by name. Returns digits in international form, or null.
     * Three tries: the name as spoken, transliterated to Latin (for Bengali /
     * Hindi names), then a fuzzy match on the first word of a contact name. */
    private static String findContactNumber(Context ctx, String name) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String num = queryContactNumber(cr, "%" + name + "%");
            if (num != null) return num;
            String latin = transliterateIndic(name).trim();
            if (!latin.isEmpty() && !latin.equalsIgnoreCase(name)) {
                num = queryContactNumber(cr, "%" + latin + "%");
                if (num != null) return num;
            }
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
            if (c == null) return null;
            String best = null;
            int bestDist = 3;
            String target = latin.toLowerCase(Locale.ENGLISH);
            while (c.moveToNext()) {
                String disp = c.getString(0);
                String n = c.getString(1);
                if (disp == null || n == null || n.isEmpty()) continue;
                String first = disp.toLowerCase(Locale.ENGLISH).split("\\s+")[0];
                int d = levenshtein(target, first);
                if (d < bestDist) { bestDist = d; best = n; }
            }
            c.close();
            if (best == null) return null;
            return normalizePhone(best);
        } catch (Exception e) {
            return null;
        }
    }

    private static String queryContactNumber(ContentResolver cr, String like) {
        Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{like}, null);
        if (c == null) return null;
        String out = null;
        if (c.moveToFirst()) out = c.getString(0);
        c.close();
        return out != null ? normalizePhone(out) : null;
    }

    /** A tel: URI needs the leading "+" for an international number.
     * findContactNumber returns digits only (e.g. 919876543210), and dialing
     * "tel:919876543210" makes the Phone app read it as a 12-digit domestic
     * number and say "invalid number". */
    private static String dialNumber(String number) {
        if (number == null) return "";
        if (number.length() > 10) return "+" + number;
        return number;
    }

    private static String normalizePhone(String raw) {
        String r = raw.replaceAll("[^0-9+]", "");
        if (r.startsWith("+")) return r.substring(1);
        if (r.startsWith("0")) r = r.substring(1);
        if (r.length() == 10) return "91" + r; // India default
        return r;
    }

    /** Is this phone number saved in the contacts? (Used by call screening.) */
    public static boolean isContact(Context ctx, String number) {
        try {
            String digits = number.replaceAll("[^0-9]", "");
            if (digits.length() > 10) digits = digits.substring(digits.length() - 10);
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                    new String[]{"%" + digits + "%"}, null);
            boolean found = c != null && c.moveToFirst();
            if (c != null) c.close();
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    /** Toggle the flashlight (Home quick-action button). Returns the new state. */
    public static boolean toggleFlashlight(Context ctx) {
        boolean on = !flashlightOn;
        if (setTorch(ctx, on)) { flashlightOn = on; return on; }
        return flashlightOn;
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

    /** Weather for Kolkata, in the user's language (0 = English, 1 = Bengali, 2 = Hindi). */
    private static String fetchWeather(int lang) {
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
            String condEn = code == 0 ? "clear sky" : code <= 3 ? "cloudy" : code <= 67 ? "rainy" : code <= 77 ? "snowy" : "stormy";
            String condBn = code == 0 ? "পরিষ্কার আকাশ" : code <= 3 ? "মেঘলা" : code <= 67 ? "বৃষ্টি" : code <= 77 ? "তুষার" : "ঝ\u09DC";
            String condHi = code == 0 ? "साफ़ आसमान" : code <= 3 ? "बादल" : code <= 67 ? "बारिश" : code <= 77 ? "बर्फ़" : "तूफ़ान";
            String cond = lang == 1 ? condBn : lang == 2 ? condHi : condEn;
            if (lang == 1) return "কলকাতা\u09DF " + temp + "°, " + cond + ", বাতাস " + wind + " কিমি/ঘণ্টা।";
            if (lang == 2) return "कोलकाता में " + temp + "°, " + cond + ", हवा " + wind + " किमी/घंटा।";
            return "Kolkata: " + temp + "°C, " + cond + ", wind " + wind + " km/h.";
        } catch (Exception e) {
            return lang == 1 ? "আবহাও\u09DFা আনতে পারলাম না। ইন্টারনেট দেখো।"
                    : lang == 2 ? "मौसम नहीं ला पाया। इंटरनेट देखो।"
                    : "Could not fetch the weather. Check your internet.";
        }
    }

    /** Top headlines from Google News RSS (free, no key). */
    /** News in the user's language (0 = English, 1 = Bengali, 2 = Hindi). */
    private static String fetchNews(int lang) {
        try {
            String hl = lang == 1 ? "bn" : lang == 2 ? "hi" : "en-IN";
            String ceid = lang == 1 ? "IN:bn" : lang == 2 ? "IN:hi" : "IN:en";
            URL url = new URL("https://news.google.com/rss?hl=" + hl + "&gl=IN&ceid=" + ceid);
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
            if (titles.isEmpty()) return lang == 1 ? "এখন কোনো খবর নেই।" : lang == 2 ? "अभी कोई खबर नहीं।" : "No headlines right now.";
            StringBuilder sb = new StringBuilder(lang == 1 ? "আজকের প্রধান খবর:\n" : lang == 2 ? "आज की मुख्य खबरें:\n" : "Today's top headlines:\n");
            for (int i = 0; i < titles.size(); i++) sb.append((i + 1)).append(". ").append(titles.get(i)).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            return lang == 1 ? "খবর আনতে পারলাম না। ইন্টারনেট দেখো।" : lang == 2 ? "खबरें नहीं ला पाया। इंटरनेट देखो।" : "Could not fetch the news. Check your internet.";
        }
    }

    /** Small GET helper shared by the free no-key APIs. Returns null on failure. */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "BlackMaskAI/1.0");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String fmtMoney(double v) {
        if (v >= 1000) return String.format(Locale.ENGLISH, "%,.0f", v);
        if (v >= 1) return String.format(Locale.ENGLISH, "%,.2f", v);
        return String.format(Locale.ENGLISH, "%.4f", v);
    }

    /** Coin name in the user's words -> CoinGecko id. Null = show the top coins. */
    private static String coinId(String t) {
        if (t.contains("bitcoin") || t.contains("btc")) return "bitcoin";
        if (t.contains("ethereum") || t.contains(" eth")) return "ethereum";
        if (t.contains("dogecoin") || t.contains("doge")) return "dogecoin";
        if (t.contains("solana") || t.contains(" sol")) return "solana";
        if (t.contains("ripple") || t.contains("xrp")) return "ripple";
        if (t.contains("litecoin") || t.contains("ltc")) return "litecoin";
        if (t.contains("cardano") || t.contains("ada")) return "cardano";
        if (t.contains("polygon") || t.contains("matic")) return "matic-network";
        if (t.contains("binance") || t.contains("bnb")) return "binancecoin";
        if (t.contains("tron") || t.contains("trx")) return "tron";
        if (t.contains("tether") || t.contains("usdt")) return "tether";
        if (t.contains("shiba") || t.contains("shib")) return "shiba-inu";
        if (t.contains("pepe")) return "pepe";
        return null;
    }

    /** Crypto prices from CoinGecko (free, no key). */
    private static String fetchCrypto(String coin) {
        try {
            String ids = coin != null ? coin : "bitcoin,ethereum,dogecoin";
            String json = httpGet("https://api.coingecko.com/api/v3/simple/price?ids=" + ids + "&vs_currencies=usd,inr");
            if (json == null) return "Could not fetch crypto prices. Check your internet.";
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder();
            for (String id : ids.split(",")) {
                if (!o.has(id)) continue;
                JSONObject c = o.getJSONObject(id);
                String name = id.substring(0, 1).toUpperCase(Locale.ENGLISH) + id.substring(1);
                sb.append(name).append(": $").append(fmtMoney(c.getDouble("usd")))
                        .append("  (\u20B9").append(fmtMoney(c.optDouble("inr", 0))).append(")\n");
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? "Could not find that coin. Try: bitcoin price." : out;
        } catch (Exception e) {
            return "Could not fetch crypto prices. Check your internet.";
        }
    }

    /** Currency rates from exchangerate-api (free, no key). */
    private static String fetchCurrency(double amount, String from, String to) {
        try {
            String json = httpGet("https://open.er-api.com/v6/latest/" + from);
            if (json == null) return "Could not fetch exchange rates. Check your internet.";
            JSONObject o = new JSONObject(json);
            if (!"success".equals(o.optString("result"))) return "Could not fetch exchange rates.";
            double rate = o.getJSONObject("rates").getDouble(to);
            double out = amount * rate;
            return fmtMoney(amount) + " " + from + " = " + fmtMoney(out) + " " + to
                    + "  (1 " + from + " = " + fmtMoney(rate) + " " + to + ")";
        } catch (Exception e) {
            return "Could not convert. Try like: convert 100 usd to inr.";
        }
    }

    /** Word meaning from dictionaryapi.dev (free, no key). */
    private static String fetchMeaning(String word) {
        try {
            String json = httpGet("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8"));
            if (json == null) return "Could not fetch the meaning. Check your internet.";
            JSONObject entry = new JSONArray(json).getJSONObject(0);
            JSONArray meanings = entry.getJSONArray("meanings");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < meanings.length() && i < 2; i++) {
                JSONObject m = meanings.getJSONObject(i);
                JSONArray defs = m.getJSONArray("definitions");
                if (defs.length() == 0) continue;
                sb.append(m.optString("partOfSpeech", "")).append(": ")
                        .append(defs.getJSONObject(0).optString("definition", "")).append("\n");
                String ex = defs.getJSONObject(0).optString("example", "");
                if (!ex.isEmpty()) sb.append("Example: ").append(ex).append("\n");
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? "No meaning found for " + word + "." : word + ":\n" + out;
        } catch (Exception e) {
            return "No meaning found for " + word + ". Check the spelling.";
        }
    }

    /** Joke from JokeAPI (free, no key). Null = use a local joke instead. */
    private static String fetchJoke() {
        try {
            String json = httpGet("https://v2.jokeapi.dev/joke/Any?type=single&blacklistFlags=nsfw,religious,political,racist,sexist,explicit");
            if (json == null) return null;
            JSONObject o = new JSONObject(json);
            if (o.optBoolean("error", true)) return null;
            String j = o.optString("joke", null);
            return (j == null || j.isEmpty()) ? null : j;
        } catch (Exception e) {
            return null;
        }
    }

    /** Random fact from uselessfacts (free, no key). */
    private static String fetchFact() {
        try {
            String json = httpGet("https://uselessfacts.jsph.pl/api/v2/facts/random?language=en");
            if (json == null) return "Could not fetch a fact. Check your internet.";
            return new JSONObject(json).optString("text", "Could not fetch a fact right now.");
        } catch (Exception e) {
            return "Could not fetch a fact. Check your internet.";
        }
    }

    /** Inspirational quote from ZenQuotes (free, no key). */
    private static String fetchQuote() {
        try {
            String json = httpGet("https://zenquotes.io/api/random");
            if (json == null) return "Could not fetch a quote. Check your internet.";
            JSONObject o = new JSONArray(json).getJSONObject(0);
            return "\u201C" + o.optString("q", "") + "\u201D\n\u2014 " + o.optString("a", "");
        } catch (Exception e) {
            return "Could not fetch a quote. Check your internet.";
        }
    }

    /** Advice from Advice Slip (free, no key). */
    private static String fetchAdvice() {
        try {
            String json = httpGet("https://api.adviceslip.com/advice");
            if (json == null) return "Could not fetch advice. Check your internet.";
            return new JSONObject(json).getJSONObject("slip").optString("advice", "Could not fetch advice right now.");
        } catch (Exception e) {
            return "Could not fetch advice. Check your internet.";
        }
    }

    /** Daily horoscope from horoscope-app-api (free, no key). */
    private static String fetchHoroscope(String sign) {
        try {
            String json = httpGet("https://horoscope-app-api.vercel.app/api/v1/get-horoscope/daily?sign=" + sign + "&day=today");
            if (json == null) return "Could not fetch the horoscope. Check your internet.";
            JSONObject d = new JSONObject(json).getJSONObject("data");
            return d.optString("sign", sign) + " today (" + d.optString("date", "") + "):\n" + d.optString("horoscope", "");
        } catch (Exception e) {
            return "Could not fetch the horoscope. Check your internet.";
        }
    }

    /** Free keyless AI chat (Pollinations). Used when no Gemini key is saved, so the app talks with zero setup. Returns null on failure.
     * The prompt tells the AI which language the user speaks - Bengali written
     * in Latin letters gets a Bengali-script reply. Tries twice: the free
     * service sometimes drops a request. */
    public static String freeChat(Context ctx, String prompt) {
        String q;
        try {
            q = URLEncoder.encode(aiSystemPrompt(ctx, prompt) + " User: " + prompt, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://text.pollinations.ai/" + q).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "BlackMaskAI/1.0");
                if (conn.getResponseCode() != 200) continue;
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                String out = sb.toString().trim();
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                // Retry once, then give up.
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(1500); } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------- brain: understand any phrasing (v4.9.16) ----------

    /** The brain: turn what the user said into one canonical command the
     * app can run ("open youtube", "play arijit singh on youtube",
     * "call Maa") - no command words needed, any phrasing works, in
     * Bengali, Hindi or English, mixed if needed. Uses the Gemini key
     * when one is saved, otherwise the free keyless AI (Pollinations),
     * so it works WITH a key AND without one. Returns null when the
     * words map to no action - then the caller chats as usual.
     * Blocking - call on a background thread. */
    public static String parseIntentCommand(Context ctx, String heard) {
        if (heard == null || heard.trim().isEmpty()) return null;
        String prompt = intentPrompt(heard.trim());
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        String key = p.getString("gemini_key", "");
        String raw = null;
        if (!key.isEmpty()) raw = askGeminiIntent(key, prompt);
        if (raw == null) raw = askPollinationsIntent(prompt);
        String cmd = extractCmd(raw);
        if (cmd == null || cmd.isEmpty()) return null;
        // Same words the user said: handle() already failed on them,
        // so running them again would only loop back to chat.
        if (normalizeCommand(cmd).equalsIgnoreCase(normalizeCommand(heard))) return null;
        return cmd;
    }

    /** AI chat reply for auto-reply (v4.9.18): Gemini key if saved,
     * otherwise the free keyless AI. The prompt is sent as-is (no chat
     * persona added), so the caller controls the language and the
     * wording. Returns null on failure. Blocking - call on a
     * background thread. */
    public static String aiChatReply(Context ctx, String prompt) {
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        String key = p.getString("gemini_key", "");
        String out = null;
        if (!key.isEmpty()) out = askGeminiIntent(key, prompt);
        if (out == null) out = askPollinationsIntent(prompt);
        return out;
    }

    /** Short prompt: the actions the app can do, in the exact wordings
     * handle() understands. Kept short on purpose - the free AI is
     * called with a GET URL, which cannot be very long. */
    private static String intentPrompt(String heard) {
        return "You turn a spoken request into one app command. Reply with ONLY JSON like {\"cmd\": \"open youtube\"} or {\"cmd\": \"\"} if it is not an action. "
                + "The text may have speech-recognition errors; correct them from context. "
                + "Use these exact wordings: open <app>; play <song> on youtube; search <words>; call <name>; whatsapp <name> <message>; sms <name> <message>; "
                + "weather; weather in <city>; time; time in <city>; date; news; cricket score; meaning of <word>; translate <text> to <language>; calculate <expression>; "
                + "set alarm <time>; set timer <minutes>; remind me <thing> at <time>; turn flashlight on/off; turn wifi on/off; sos; remember <thing> is <value>. "
                + "Plain questions and chats get {\"cmd\": \"\"}. "
                + "User said (Bengali, Hindi or English, maybe mixed): \"" + heard + "\"";
    }

    /** Ask Gemini for the intent (same models + call shape as the
     * chat path). The prompt is sent as-is, no chat persona added. */
    private static String askGeminiIntent(String apiKey, String prompt) {
        for (String model : new String[]{"gemini-3.1-flash-lite", "gemini-3.5-flash-lite"}) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8"));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", prompt));
                content.put("parts", parts);
                contents.put(content);
                body.put("contents", contents);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                if (conn.getResponseCode() != 200) continue;
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject res = new JSONObject(sb.toString());
                String out = res.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim();
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                // Try the next model.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** Ask the free keyless AI for the intent (same shape as freeChat). */
    private static String askPollinationsIntent(String prompt) {
        String q;
        try {
            q = URLEncoder.encode(prompt, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://text.pollinations.ai/" + q).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "BlackMaskAI/1.0");
                if (conn.getResponseCode() != 200) continue;
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                String out = sb.toString().trim();
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                // Retry once, then give up.
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(1500); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Pull the command out of the AI's answer. Prefers the JSON block;
     * if the free AI answered in plain words instead, takes the first
     * line when it already reads like a command. */
    private static String extractCmd(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                String cmd = new JSONObject(s.substring(a, b + 1)).optString("cmd", "").trim();
                if (!cmd.isEmpty()) return cmd.length() > 200 ? null : cmd;
            } catch (Exception ignored) {}
        }
        String first = s.split("\n")[0].trim().replaceAll("^\"|\"$", "");
        String low = first.toLowerCase(Locale.ENGLISH);
        for (String v : new String[]{"open ", "play ", "search ", "call ", "whatsapp ", "sms ",
                "weather", "time", "date", "news", "translate ", "calculate ", "set alarm",
                "set timer", "turn flashlight", "turn wifi", "sos", "remind me", "remember ",
                "meaning of ", "cricket"}) {
            if (low.startsWith(v)) return first.length() > 200 ? null : first;
        }
        return null;
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

    /** Morning briefing text: time + weather + headlines (blocking - call on a thread). */
    public static String getBriefingText() {
        String time = new SimpleDateFormat("hh:mm a, EEEE, dd MMMM yyyy", Locale.getDefault()).format(new Date());
        StringBuilder sb = new StringBuilder("It is " + time + ".\n");
        sb.append(fetchWeather(0)).append("\n");
        sb.append(fetchNews(0));
        return sb.toString();
    }

    /** Daily 7 AM morning briefing on or off. */
    public static void setMorningBriefing(Context ctx, boolean on) {
        try {
            Intent i = new Intent(ctx, MorningBriefingReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            if (on) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 7);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
            } else {
                am.cancel(pi);
            }
        } catch (Exception ignored) {}
    }

    /** Send a play/pause/next/previous key to the active music player. */
    private static void mediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (Exception ignored) {}
    }

    // ---------- find my phone (ring) ----------

    private static Ringtone currentRing;
    private static boolean antiTheftRinging = false;
    private static final Handler ringHandler = new Handler(Looper.getMainLooper());
    private static Runnable volumeGuard;

    private static int smsSentSeq = 0;

    /** Send an SMS, split into parts when it is long. A long message with
     * Bengali text (like the SOS message with its Maps link) is 2+ parts, and
     * sendTextMessage fails on many carriers for those - that is why the SOS
     * SMS failed even with an unlimited pack. Only the LAST part carries the
     * sent intent, so the result is reported exactly once. */
    private static void sendMultipart(SmsManager sms, String num, String msg, PendingIntent sent) {
        java.util.ArrayList<String> parts = sms.divideMessage(msg);
        java.util.ArrayList<PendingIntent> sentIntents = new java.util.ArrayList<>();
        for (int i = 0; i < parts.size(); i++) sentIntents.add(i == parts.size() - 1 ? sent : null);
        sms.sendMultipartTextMessage(num, null, parts, sentIntents, null);
    }

    /** Send one SMS and get the radio's real result (SmsSentReceiver shows it).
     * Numbers are normalized first (spaces/dashes/parens removed).
     * The sent intent also carries the message + the subscription used, so
     * SmsSentReceiver can retry through the OTHER SIM when the first one fails.
     * Returns false if the SEND_SMS permission is missing or the send threw. */
    public static boolean sendSmsReported(Context ctx, String to, String msg, String tag) {
        if (to == null) return false;
        String num = to.replaceAll("[\\s\\-()]", "");
        if (num.isEmpty()) return false;
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            int sub = -1;
            try { sub = SubscriptionManager.getDefaultSmsSubscriptionId(); } catch (Exception ignored) {}
            Intent i = new Intent(ctx, SmsSentReceiver.class);
            i.putExtra("to", num);
            i.putExtra("msg", msg);
            i.putExtra("tag", tag);
            i.putExtra("sub", sub);
            i.putExtra("tried", sub >= 0 ? String.valueOf(sub) : "");
            PendingIntent sent = PendingIntent.getBroadcast(ctx, ++smsSentSeq, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            sendMultipart(SmsManager.getDefault(), num, msg, sent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Send one SMS through a specific subscription (used by the retry in
     * SmsSentReceiver when the default SIM failed). "tried" lists the sub ids
     * already attempted, so the retry never loops back to the same SIM. */
    public static boolean sendSmsViaSub(Context ctx, String to, String msg, String tag, int subId, String tried) {
        if (to == null || msg == null) return false;
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Intent i = new Intent(ctx, SmsSentReceiver.class);
            i.putExtra("to", to);
            i.putExtra("msg", msg);
            i.putExtra("tag", tag);
            i.putExtra("sub", subId);
            i.putExtra("tried", tried == null ? "" : tried);
            PendingIntent sent = PendingIntent.getBroadcast(ctx, ++smsSentSeq, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            sendMultipart(SmsManager.getSmsManagerForSubscriptionId(subId), to, msg, sent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Location as a Maps link (v4.9.9). Prefers a fresh fix; else the
     * last saved location (recorded every 15 min while location was on,
     * used if less than a day old); else the cell tower, which the owner
     * can look up online. A fresh fix is impossible while the Location
     * switch is off - Android blocks it for every app, so the saved
     * place + tower is the honest fallback. Returns "" only when
     * nothing is known at all. */
    public static String locationLink(Context ctx) {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                android.location.Location found = null;
                if (lm != null) {
                    found = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (found == null) found = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (found != null) {
                    ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                            .putString("saved_loc_lat", String.valueOf(found.getLatitude()))
                            .putString("saved_loc_lng", String.valueOf(found.getLongitude()))
                            .putLong("saved_loc_time", System.currentTimeMillis()).apply();
                    return "https://maps.google.com/?q=" + found.getLatitude() + "," + found.getLongitude();
                }
            }
            // No fresh fix (location off, or no fix yet): last saved place.
            SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            String lat = p.getString("saved_loc_lat", "");
            String lng = p.getString("saved_loc_lng", "");
            long when = p.getLong("saved_loc_time", 0);
            if (!lat.isEmpty() && !lng.isEmpty() && when > 0
                    && System.currentTimeMillis() - when < 24 * 60 * 60 * 1000L) {
                long mins = (System.currentTimeMillis() - when) / 60000;
                String age = mins < 1 ? "just now" : mins + " min ago";
                return "https://maps.google.com/?q=" + lat + "," + lng + " (last seen " + age + ")";
            }
            String cell = cellInfo(ctx);
            if (!cell.isEmpty()) return cell;
        } catch (Exception ignored) {}
        return "";
    }

    /** v4.9.10: turn Location on automatically with auto-tap. Opens the
     * system Location settings and the accessibility service taps the
     * switch. (There is no Settings.Panel for location, so the full
     * settings screen opens - it closes itself right after the tap.)
     * Needs auto-tap on; does nothing otherwise. */
    public static void ensureLocationOn(Context ctx) {
        try {
            if (!BlackMaskAccessibilityService.isEnabled(ctx)) return;
            // The PIN gate watches this flag: while our settings screen
            // is open it must not lock the phone (the screen covers the gate).
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                    .putLong("loc_panel_open", System.currentTimeMillis()).apply();
            BlackMaskAccessibilityService.setPendingAction(ctx,
                    BlackMaskAccessibilityService.ACTION_LOCATION_ON);
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Is the Location switch on (GPS or network provider)? */
    public static boolean isLocationOn(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** v4.9.10: get a fresh location before an SOS / LOC / anti-theft SMS.
     * If Location is already on, onReady runs at once. If it is off and
     * auto-tap is on, Location is turned on first and we wait up to
     * timeoutMs for a fresh fix. onReady ALWAYS runs (fix or timeout), so
     * the SMS is never stuck - on timeout the saved place / cell tower is
     * used instead. Skipped when the phone is locked (no panel can be
     * tapped there) or auto-tap is off. Returns true when the SMS will go
     * out later (location was off). */
    public static boolean withFreshLocation(Context ctx, long timeoutMs, Runnable onReady) {
        try {
            if (isLocationOn(ctx)) { onReady.run(); return false; }
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) { onReady.run(); return false; }
            if (!BlackMaskAccessibilityService.isEnabled(ctx)) { onReady.run(); return false; }
            ensureLocationOn(ctx);
            new Thread(() -> {
                try {
                    // Let the panel tap land first.
                    Thread.sleep(3000);
                    final CountDownLatch latch = new CountDownLatch(1);
                    LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                    LocationListener listener = new LocationListener() {
                        @Override public void onLocationChanged(Location l) {
                            if (l != null) saveLocation(ctx, l);
                            latch.countDown();
                        }
                        @Override public void onStatusChanged(String p, int s, Bundle e) {}
                        @Override public void onProviderEnabled(String p) {}
                        @Override public void onProviderDisabled(String p) {}
                    };
                    boolean requested = false;
                    try {
                        if (lm != null) {
                            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
                                requested = true;
                            }
                            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
                                requested = true;
                            }
                        }
                        if (requested) latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                    } finally {
                        try { if (lm != null) lm.removeUpdates(listener); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                try { onReady.run(); } catch (Exception ignored) {}
            }).start();
            return true;
        } catch (Exception e) {
            try { onReady.run(); } catch (Exception ignored) {}
            return false;
        }
    }

    /** Save a fix as the last-known place (same prefs the 15-min recorder uses). */
    private static void saveLocation(Context ctx, Location l) {
        try {
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                    .putString("saved_loc_lat", String.valueOf(l.getLatitude()))
                    .putString("saved_loc_lng", String.valueOf(l.getLongitude()))
                    .putLong("saved_loc_time", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    /** Callback for the SOS send result (used when the send is delayed). */
    public interface SosCallback { void onSent(int sent); }

    /** v4.9.10: SOS that turns Location on first if it is off (auto-tap).
     * Returns true when the SMS will go out later (location was off) - the
     * caller should tell the user to wait; false means it was sent at once. */
    public static boolean sendSosAuto(Context ctx, SosCallback cb) {
        return withFreshLocation(ctx, 15000, () -> {
            int sent = sendSos(ctx);
            if (cb != null) cb.onSent(sent);
        });
    }

    /** Cell tower identity, for when no location is available at all.
     * The owner can look the tower up at opencellid.org. */
    private static String cellInfo(Context ctx) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String op = tm.getNetworkOperator();
            android.telephony.CellLocation cl = tm.getCellLocation();
            if (cl instanceof android.telephony.gsm.GsmCellLocation) {
                android.telephony.gsm.GsmCellLocation g = (android.telephony.gsm.GsmCellLocation) cl;
                int lac = g.getLac(), cid = g.getCid();
                if (lac >= 0 && cid >= 0) {
                    return "Cell tower: " + (op != null ? op : "") + " LAC " + lac + " CID " + cid + " (look up at opencellid.org)";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Anti-theft alert (v4.9.7, location auto-on in v4.9.10): someone is
     * trying to switch the phone off - wrong PIN 3 times, or volume down
     * held 3s (the power + volume-down force-restart combo). Rings loudly
     * at once, turns Location on first if it is off (auto-tap) so the SMS
     * carries a fresh place, then sends a location SMS to the trusted
     * number and locks the phone. At most once a minute, so a thief
     * hammering the button does not spam SMS. Works without internet:
     * SMS + GPS need no net. */
    public static void sendAntiTheftAlert(Context ctx, String tag) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            if (!p.getBoolean("antitheft_on", false)) return;
            String num = p.getString("antitheft_num", "");
            if (num.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - p.getLong("antitheft_last_alert", 0) < 60000) return;
            p.edit().putLong("antitheft_last_alert", now).apply();
            // Ring first - the deterrent must not wait for a GPS fix.
            startAntiTheftRing(ctx);
            withFreshLocation(ctx, 15000, () -> {
                String loc = locationLink(ctx);
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                String msg = "Black Mask AI Anti-theft: someone is trying to switch off your phone! / কেউ তোমার ফোন বন্ধ করার চেষ্টা করছে!"
                        + (loc.isEmpty() ? "" : "\nLocation: " + loc)
                        + (bat >= 0 ? "\nBattery: " + bat + "%" : "")
                        + "\nTime: " + time + " — Black Mask AI";
                sendSmsReported(ctx, num, msg, tag);
                // Lock the phone now that the SMS is out.
                new Handler(Looper.getMainLooper()).post(() ->
                        BlackMaskAccessibilityService.doGlobal("lock"));
            });
        } catch (Exception ignored) {}
    }

    /** Anti-theft boot SMS (v4.9.7): after a restart the phone comes back
     * locked - quietly send the location to the trusted number, no ring.
     * The owner can then send BLACKMASK RING to make it ring. */
    public static void sendAntiTheftBootSms(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            if (!p.getBoolean("antitheft_on", false)) return;
            String num = p.getString("antitheft_num", "");
            if (num.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - p.getLong("antitheft_last_boot", 0) < 120000) return;
            p.edit().putLong("antitheft_last_boot", now).apply();
            String loc = locationLink(ctx);
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String msg = "Black Mask AI Anti-theft: your phone just restarted."
                    + (loc.isEmpty() ? "\nLocation: not available — GPS off." : "\nLocation: " + loc)
                    + "\nTime: " + time + " — Black Mask AI";
            sendSmsReported(ctx, num, msg, "BOOT");
        } catch (Exception ignored) {}
    }

    /** Send the SOS SMS to the saved emergency contacts (up to 3).
     * The message carries the phone's location (Maps link), battery % and time.
     * Returns how many contacts it was sent to. */
    public static int sendSos(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        String[] nums = {p.getString("sos_num_1", ""), p.getString("sos_num_2", ""), p.getString("sos_num_3", "")};
        String loc = locationLink(ctx);
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String msg = "SOS! I need help! / আমার সাহায্য দরকার!"
                + (loc.isEmpty() ? "" : "\nLocation: " + loc)
                + (bat >= 0 ? "\nBattery: " + bat + "%" : "")
                + "\nTime: " + time + " — Black Mask AI";
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return -1;
        int sent = 0;
        for (String num : nums) {
            if (num == null || num.isEmpty()) continue;
            if (sendSmsReported(ctx, num, msg, "SOS")) sent++;
        }
        return sent;
    }

    /** Ring the phone at full volume for 30 seconds, with a Stop action in the notification. */
    public static void startRing(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            stopRing();
            currentRing = RingtoneManager.getRingtone(ctx, uri);
            if (currentRing != null) currentRing.play();
            new Handler(Looper.getMainLooper()).postDelayed(() -> stopRing(), 30000);
            // Notification with a Stop action, so the user can silence it early.
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel("jarvis_channel", "Black Mask AI",
                            NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(ch);
                }
                Intent stop = new Intent(ctx, JarvisService.class).setAction(JarvisService.ACTION_STOP_RING);
                PendingIntent stopPi = PendingIntent.getService(ctx, 2, stop,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(ctx, "jarvis_channel")
                        : new Notification.Builder(ctx);
                b.setContentTitle("Black Mask AI")
                        .setContentText("Your phone is ringing - tap Stop to silence it.")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .addAction(new Notification.Action.Builder(
                                android.R.drawable.ic_delete, "Stop", stopPi).build())
                        .setAutoCancel(true);
                nm.notify(2, b.build());
            }
        } catch (Exception ignored) {}
    }

    /** Stop the find-my-phone ring (also stops the anti-theft ring). */
    public static void stopRing() {
        try {
            antiTheftRinging = false;
            ringHandler.removeCallbacksAndMessages(null);
            if (currentRing != null) {
                currentRing.stop();
                currentRing = null;
            }
        } catch (Exception ignored) {}
    }

    /** Anti-theft ring (v4.9.8): no Stop button, and the volume is forced
     * back to max every second, so a thief cannot silence it with the
     * volume keys. Rings up to 5 minutes, or until "BLACKMASK STOP"
     * arrives from the trusted number. */
    public static void startAntiTheftRing(Context ctx) {
        try {
            stopRing();
            antiTheftRinging = true;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            currentRing = RingtoneManager.getRingtone(ctx, uri);
            if (currentRing != null) currentRing.play();
            // Volume guard: a thief pressing volume-down gets max volume
            // back one second later, for as long as the ring lasts.
            volumeGuard = new Runnable() {
                @Override public void run() {
                    if (!antiTheftRinging) return;
                    try {
                        AudioManager a = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (a != null) a.setStreamVolume(AudioManager.STREAM_ALARM,
                                a.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                    } catch (Exception ignored) {}
                    ringHandler.postDelayed(this, 1000);
                }
            };
            ringHandler.postDelayed(volumeGuard, 1000);
            ringHandler.postDelayed(() -> stopRing(), 5 * 60 * 1000);
            // Notification with NO Stop action - only the owner can stop
            // it, by SMS from the trusted number.
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(new NotificationChannel("jarvis_channel", "Black Mask AI",
                            NotificationManager.IMPORTANCE_LOW));
                }
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(ctx, "jarvis_channel")
                        : new Notification.Builder(ctx);
                b.setContentTitle("Black Mask AI Anti-theft")
                        .setContentText("This phone is protected - wrong PIN entered.")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setAutoCancel(true);
                nm.notify(3, b.build());
            }
        } catch (Exception ignored) {}
    }

    /** "BLACKMASK UNLOCK" SMS (v4.9.8): the owner forgot the PIN. Only an
     * SMS from the trusted number works. Gives 5 minutes of grace,
     * silences the ring, dismisses the PIN gate, and confirms by SMS. */
    public static void remoteUnlock(Context ctx, String sender) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            if (!p.getBoolean("antitheft_on", false)) return;
            String trusted = p.getString("antitheft_num", "");
            if (trusted.isEmpty() || sender == null) return;
            if (!sameNumber(trusted, sender)) return;
            p.edit().putLong("antitheft_grace_until",
                    System.currentTimeMillis() + 5 * 60_000).apply();
            stopRing();
            Intent unlock = new Intent("com.blackmask.jarvis.ANTI_THEFT_UNLOCK");
            ctx.sendBroadcast(unlock);
            sendSmsReported(ctx, sender,
                    "Black Mask AI: phone unlocked for 5 minutes. You can power it off now.",
                    "UNLOCK");
        } catch (Exception ignored) {}
    }

    /** "BLACKMASK STOP": silence the ring, only from the trusted number.
     * If no trusted number is set, any sender may stop it. */
    public static void stopRingIfTrusted(Context ctx, String sender) {
        try {
            if (!isTrustedSender(ctx, sender)) return;
            stopRing();
        } catch (Exception ignored) {}
    }

    public static boolean isTrustedSender(Context ctx, String sender) {
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        String trusted = p.getString("antitheft_num", "");
        if (trusted.isEmpty()) return true;
        if (sender == null) return false;
        return sameNumber(trusted, sender);
    }

    /** Same phone number, ignoring country code, spaces and dashes. */
    private static boolean sameNumber(String a, String b) {
        String da = a.replaceAll("\\D", "");
        String db = b.replaceAll("\\D", "");
        if (da.length() > 10) da = da.substring(da.length() - 10);
        if (db.length() > 10) db = db.substring(db.length() - 10);
        return !da.isEmpty() && da.equals(db);
    }

    // ---------- memory ("remember my bike number is ...") ----------

    private static String readMemories(Context ctx) {
        return ctx.getSharedPreferences("memories", Context.MODE_PRIVATE).getString("all", "");
    }

    private static void saveMemory(Context ctx, String key, String value) {
        StringBuilder sb = new StringBuilder();
        String all = readMemories(ctx);
        if (!all.isEmpty()) {
            for (String pair : all.split("\u001f")) {
                String[] kv = pair.split("\u001e", 2);
                if (kv.length == 2 && !kv[0].equalsIgnoreCase(key)) sb.append(pair).append("\u001f");
            }
        }
        sb.append(key.toLowerCase(Locale.ENGLISH)).append("\u001e").append(value);
        ctx.getSharedPreferences("memories", Context.MODE_PRIVATE).edit().putString("all", sb.toString()).apply();
    }

    private static String recallMemory(Context ctx, String key) {
        String all = readMemories(ctx);
        if (all.isEmpty()) return null;
        for (String pair : all.split("\u001f")) {
            String[] kv = pair.split("\u001e", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) return kv[1];
        }
        return null;
    }

    private static boolean forgetMemory(Context ctx, String key) {
        String all = readMemories(ctx);
        if (all.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (String pair : all.split("\u001f")) {
            String[] kv = pair.split("\u001e", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) { removed = true; continue; }
            sb.append(pair).append("\u001f");
        }
        if (removed) ctx.getSharedPreferences("memories", Context.MODE_PRIVATE).edit().putString("all", sb.toString()).apply();
        return removed;
    }

    private static List<String> listMemoryKeys(Context ctx) {
        List<String> keys = new ArrayList<>();
        String all = readMemories(ctx);
        if (!all.isEmpty()) {
            for (String pair : all.split("\u001f")) {
                String[] kv = pair.split("\u001e", 2);
                if (kv.length == 2) keys.add(kv[0]);
            }
        }
        return keys;
    }

    /** Drop "my " / "আমার " from the start of a memory key. */
    private static String stripMemoryKey(String key) {
        String k = key.trim();
        for (String pfx : new String[]{"my ", "আমার ", "मेरा ", "मेरी ", "मेरे "}) {
            if (k.startsWith(pfx)) { k = k.substring(pfx.length()).trim(); break; }
        }
        return k;
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

    /** A reminder that fires every day at the same time. */
    private static void scheduleDailyReminder(Context ctx, long firstWhen, String what) {
        try {
            Intent i = new Intent(ctx, ReminderReceiver.class);
            i.putExtra("text", what);
            int id = (what + firstWhen).hashCode();
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.setInexactRepeating(AlarmManager.RTC_WAKEUP, firstWhen,
                    AlarmManager.INTERVAL_DAY, pi);
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
                if (pos < expr.length()) throw new RuntimeException("bad expr");
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
                } else throw new RuntimeException("bad factor");
                return x;
            }
        }.parse();
    }
}
