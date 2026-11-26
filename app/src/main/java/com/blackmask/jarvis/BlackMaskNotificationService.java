package com.blackmask.jarvis;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reads WhatsApp / Telegram / Messenger messages aloud (when the "Read
 * notifications aloud" toggle is on in Settings) and remembers the last
 * sender so the user can say "reply <text>".
 *
 * Auto-reply (v4.9.16-19, More Settings, below App Lock): when the toggle
 * is on, an incoming direct message gets an automatic reply, sent through
 * the notification's own reply button - the same official way a smartwatch
 * replies. No WhatsApp / Telegram login, no bot: it answers as the owner,
 * from the owner's own phone.
 *
 * Rules (v4.9.19), per sender per 30 minutes:
 * - At most 4 replies. The first 3 answer on the topic of the message
 *   (AI: Gemini key if saved, else the free keyless AI), in the same
 *   language the sender wrote in - no setting needed. The 4th reply
 *   closes the chat: "My owner is not here. I am AI."
 * - "Who are you?" (English, Bengali or Hindi, script or Roman letters)
 *   is answered with just "I am Black Mask AI." in that language.
 * - Per-app choice: WhatsApp, Telegram, Messenger, Instagram and
 *   Facebook can each be switched on or off in More Settings.
 * - Schedule: it can be limited to set hours (e.g. only at night).
 * - The owner can write his own reply text, and choose to always use
 *   it instead of the AI replies.
 * - Groups: off by default; the owner picks which groups get replies
 *   (only groups seen in notifications can be listed).
 * - Contacts: once the owner picks contacts, only those contacts get
 *   replies - plus unknown numbers, if that box is ticked. Matching is
 *   by number as well as by name (v4.9.20): the name the picker stored
 *   and the name WhatsApp shows can differ, but the number is the same.
 * - Diagnosis (v4.9.20): the last message the service saw, and why it
 *   was answered or skipped, are shown in More Settings, so a missing
 *   reply can be traced without guessing.
 * - It never answers the owner's own messages: the same words are
 *   never answered twice within a minute (WhatsApp re-posts its
 *   notifications, and the owner's sent messages show up as
 *   notifications too), and it stays quiet while the owner is online
 *   in a chat app himself (the accessibility service remembers when a
 *   chat app was last on screen - needs auto-tap on).
 * - Group messages are skipped unless group replies are on and the
 *   group is picked. If the AI cannot be reached, the fixed away-line
 *   (or the owner's own reply text) is sent instead, in the sender's
 *   language.
 *
 * The user must also enable notification access once:
 * Settings -> Notifications -> Notification access -> Black Mask AI.
 */
public class BlackMaskNotificationService extends NotificationListenerService {

    /** Fixed lines, one set per language: 0=English, 1=Bengali, 2=Hindi. */
    private static final String[] AWAY_TEXT = {
            "I am Black Mask AI — my owner is not online right now.",
            "আমি Black Mask AI — আমার মালিক এখন অনলাইনে নেই।",
            "मैं Black Mask AI हूँ — मेरा मालिक अभी ऑनलाइन नहीं है।"};
    private static final String[] WHO_TEXT = {
            "I am Black Mask AI.",
            "আমি Black Mask AI।",
            "मैं Black Mask AI हूँ।"};
    private static final String[] CLOSING_TEXT = {
            "My owner is not here. I am AI.",
            "আমার মালিক নেই, আমি AI।",
            "मेरा मालिक नहीं है, मैं AI हूँ।"};
    private static final long REPLY_COOLDOWN_MS = 30 * 60 * 1000;
    private static final int MAX_REPLIES_PER_WINDOW = 4;
    /** Same words answered twice within this long = one re-posted
     * notification, not a new message. */
    private static final long DEDUPE_MS = 60 * 1000;
    /** The owner was in a chat app this recently: he is online himself. */
    private static final long ONLINE_MS = 120 * 1000;

    /** Cached contact names/numbers for the contact filter (10 min). */
    private static Set<String> cachedNames = null;
    private static Set<String> cachedNumbers = null;
    private static long cachedAt = 0;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)
                && !"org.telegram.messenger".equals(pkg)
                && !"com.facebook.orca".equals(pkg)
                && !"com.instagram.android".equals(pkg)
                && !"com.facebook.katana".equals(pkg)) return;
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || text == null) return;
        String sender = title.toString().trim();
        String msg = text.toString().trim();
        if (sender.isEmpty() || msg.isEmpty()) return;
        if (sender.contains("Black Mask")) return;
        // Remember the last message seen, so the owner can check in
        // More Settings whether the app saw a message at all (v4.9.20:
        // this is the first thing to look at when a reply is missing).
        String seenText = sender + ": " + (msg.length() > 40 ? msg.substring(0, 40) + "…" : msg);
        p.edit().putString("autoreply_last_seen", seenText)
                .putLong("autoreply_last_seen_at", System.currentTimeMillis()).apply();
        // Remember groups we have seen, so the group picker can list them.
        CharSequence conv = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (conv != null) {
            String g = normalizeGroupName(conv.toString());
            if (!g.isEmpty()) {
                Set<String> seen = new HashSet<>(p.getStringSet("autoreply_groups_seen", new HashSet<>()));
                if (seen.add(g)) {
                    p.edit().putStringSet("autoreply_groups_seen", seen).apply();
                }
            }
        }
        if (p.getBoolean("read_notif", false)) {
            p.edit().putString("last_notif_sender", sender).putString("last_notif_text", msg).apply();
            JarvisService.speakText(this, sender + " বলছে: " + msg);
        }
        if (p.getBoolean("autoreply_on", false) && isAppEnabled(p, pkg)) {
            // The smart reply needs the network: never on this thread.
            new Thread(() -> autoReply(n, p, pkg, sender, msg)).start();
        }
    }

    /** Is auto-reply switched on for this app? */
    private static boolean isAppEnabled(SharedPreferences p, String pkg) {
        if ("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)) {
            return p.getBoolean("autoreply_app_whatsapp", true);
        }
        if ("org.telegram.messenger".equals(pkg)) {
            return p.getBoolean("autoreply_app_telegram", true);
        }
        if ("com.facebook.orca".equals(pkg)) {
            return p.getBoolean("autoreply_app_messenger", true);
        }
        if ("com.instagram.android".equals(pkg)) {
            return p.getBoolean("autoreply_app_instagram", true);
        }
        if ("com.facebook.katana".equals(pkg)) {
            return p.getBoolean("autoreply_app_facebook", true);
        }
        return false;
    }

    /** Inside the set hours? Off means any time. Handles overnight
     * windows (e.g. 22:00-07:00). */
    private static boolean inSchedule(SharedPreferences p) {
        if (!p.getBoolean("autoreply_sched_on", false)) return true;
        int start = p.getInt("autoreply_sched_start", 22 * 60);
        int end = p.getInt("autoreply_sched_end", 7 * 60);
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (start <= end) return now >= start && now < end;
        return now >= start || now < end;
    }

    /** WhatsApp sometimes titles a group notification with the unread
     * count, e.g. "ONLY FOR DEVLOPER (2 messages)" - the same group
     * then looks like three different groups, and only the ticked
     * variant gets replies. Strip a trailing "(...digits...)" so one
     * group is one group. (v4.9.21) */
    public static String normalizeGroupName(String g) {
        if (g == null) return "";
        g = g.trim();
        if (g.endsWith(")")) {
            int open = g.lastIndexOf('(');
            if (open > 0) {
                String inside = g.substring(open + 1, g.length() - 1);
                boolean hasDigit = false;
                for (int i = 0; i < inside.length(); i++) {
                    char c = inside.charAt(i);
                    if ((c >= '0' && c <= '9') || (c >= '\u09E6' && c <= '\u09EF')) {
                        hasDigit = true;
                        break;
                    }
                }
                if (hasDigit) g = g.substring(0, open).trim();
            }
        }
        return g;
    }

    /** Is this group in the picked set? Both sides are normalized,
     * so an old "(2 messages)" entry still matches. */
    private static boolean containsGroup(Set<String> groups, String name) {
        String n = normalizeGroupName(name);
        for (String g : groups) {
            if (normalizeGroupName(g).equals(n)) return true;
        }
        return false;
    }

    /** The owner's own reply text in the sender's language, if he
     * wrote one for it (v4.9.21): English, Bengali or Hindi. Falls
     * back to any reply he wrote, then to the old single text. */
    private static String customReplyForLang(SharedPreferences p, int lang) {
        String c = p.getString(lang == 1 ? "autoreply_custom_bn"
                : lang == 2 ? "autoreply_custom_hi" : "autoreply_custom_en", "").trim();
        if (!c.isEmpty()) return c;
        for (String key : new String[]{"autoreply_custom_en", "autoreply_custom_bn",
                "autoreply_custom_hi", "autoreply_custom_text"}) {
            String v = p.getString(key, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** The owner's own answer for this message, if it matches one of
     * his question-answer pairs (v4.9.22): the message contains the
     * question text (case-insensitive, extra spaces ignored). */
    private String qaReply(SharedPreferences p, String body) {
        Set<String> saved = p.getStringSet("autoreply_qa", null);
        if (saved == null || saved.isEmpty()) return null;
        String norm = normalizeText(body);
        for (String e : saved) {
            int nl = e.indexOf('\n');
            if (nl <= 0) continue;
            String q = normalizeText(e.substring(0, nl));
            if (!q.isEmpty() && norm.contains(q)) return e.substring(nl + 1).trim();
        }
        return null;
    }

    /** Lowercase, trimmed, single spaces - for matching messages
     * against the owner's questions. */
    private static String normalizeText(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH).trim().replaceAll("\\s+", " ");
    }

    /** Remember why the last message was answered or skipped, so the
     * owner can see it in More Settings (v4.9.20). The code is mapped
     * to the owner's language there. */
    private static void noteCheck(SharedPreferences p, String code) {
        try {
            p.edit().putString("autoreply_last_check", code)
                    .putLong("autoreply_last_check_at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    /** One automatic reply to a direct message. See the class comment
     * for the full rules. */
    private void autoReply(Notification n, SharedPreferences p,
            String pkg, String sender, String msg) {
        try {
            if (msg.contains("Black Mask AI")) return; // our own echo
            if (!inSchedule(p)) { noteCheck(p, "schedule"); return; }
            CharSequence conv = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            boolean isGroup = conv != null;
            String groupName = isGroup ? conv.toString().trim() : null;
            if (isGroup) {
                if (!p.getBoolean("autoreply_groups_on", false)) { noteCheck(p, "group"); return; }
                Set<String> groups = p.getStringSet("autoreply_groups", null);
                if (groups == null || !containsGroup(groups, groupName)) { noteCheck(p, "group"); return; }
            }
            // The owner is online in a chat app himself: stay quiet,
            // he can answer himself.
            long chatFg = p.getLong("chat_fg_time", 0);
            if (System.currentTimeMillis() - chatFg < ONLINE_MS) { noteCheck(p, "online"); return; }
            // Contact filter: only active once the owner picks contacts.
            if (!isGroup) {
                Set<String> selected = p.getStringSet("autoreply_contacts", null);
                if (selected != null && !selected.isEmpty() && contactsAvailable()) {
                    boolean isSel = isSelectedSender(sender, selected);
                    boolean isUnknown = !isKnownSender(sender);
                    boolean unknownOn = p.getBoolean("autoreply_unknown_on", true);
                    if (!isSel && !(isUnknown && unknownOn)) { noteCheck(p, "not-selected"); return; }
                }
            }
            String key = "autoreply_" + (pkg + "|" + (isGroup ? "g|" + normalizeGroupName(groupName) : sender)).hashCode();
            long now = System.currentTimeMillis();
            long windowStart = p.getLong(key + "_window", 0);
            int count = p.getInt(key + "_count", 0);
            if (now - windowStart >= REPLY_COOLDOWN_MS) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_REPLIES_PER_WINDOW) { noteCheck(p, "cap"); return; }
            // Same words within a minute: a re-posted notification (or
            // the owner's own sent message), not a new message.
            if (msg.equals(p.getString(key + "_lastmsg", ""))
                    && now - p.getLong(key + "_lastat", 0) < DEDUPE_MS) { noteCheck(p, "dedupe"); return; }
            // Group messages arrive as "SenderName: message".
            String body = msg;
            if (isGroup) {
                int colon = msg.indexOf(": ");
                if (colon > 0) body = msg.substring(colon + 2).trim();
                if (body.isEmpty()) body = msg;
            }
            int lang = detectReplyLang(body);
            String custom = customReplyForLang(p, lang);
            boolean useCustom = p.getBoolean("autoreply_custom_use", false) && !custom.isEmpty();
            String reply;
            String qa = qaReply(p, body);
            if (qa != null) {
                reply = qa; // the owner's own answer for this question
            } else if (isWhoAreYou(body)) {
                reply = WHO_TEXT[lang];
            } else if (count >= MAX_REPLIES_PER_WINDOW - 1) {
                reply = CLOSING_TEXT[lang]; // 4th reply: close the chat
            } else if (useCustom) {
                reply = custom;
            } else {
                reply = smartReply(body, lang);
                if (reply == null) reply = custom.isEmpty() ? AWAY_TEXT[lang] : custom;
            }
            if (!sendReply(n, reply)) { noteCheck(p, "no-button"); return; }
            noteCheck(p, "replied:" + sender);
            p.edit().putLong(key + "_window", windowStart)
                    .putInt(key + "_count", count + 1)
                    .putString(key + "_lastmsg", msg)
                    .putLong(key + "_lastat", now).apply();
        } catch (Exception ignored) {}
    }

    /** Answer on the topic of the message, in the sender's language.
     * Returns null when the AI cannot be reached. */
    private String smartReply(String msg, int lang) {
        try {
            String langName = lang == 1 ? "Bengali" : lang == 2 ? "Hindi" : "English";
            String script = lang == 1 ? "Bengali script" : lang == 2 ? "Devanagari script" : "English";
            String prompt = "You are Black Mask AI, replying to a chat message for your owner, who is not online right now. "
                    + "Reply briefly, in 1-2 short lines, in " + langName + " (" + script + ") - the same language as the message below. "
                    + "Answer on the topic of the message. Do not pretend to be the owner. Do not make promises for the owner. Do not ask for personal details. "
                    + "Message: \"" + msg + "\"";
            String out = CommandEngine.aiChatReply(this, prompt);
            if (out == null) return null;
            out = out.trim().replaceAll("^[\"“”]+|[\"“”]+$", "").trim();
            if (out.isEmpty() || out.length() > 300) return null;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Which language did the sender write in? 0=English, 1=Bengali,
     * 2=Hindi. Script first, then Roman-letter words. */
    private static int detectReplyLang(String msg) {
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') return 1; // Bengali script
            if (c >= '\u0900' && c <= '\u097F') return 2; // Devanagari
        }
        String[] words = msg.toLowerCase(Locale.ENGLISH).split("[^a-z]+");
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (inList(BN_ROMAN, w)) return 1;
            if (inList(HI_ROMAN, w)) return 2;
        }
        return 0;
    }

    private static boolean inList(String[] arr, String w) {
        for (String s : arr) if (s.equals(w)) return true;
        return false;
    }

    /** Roman-letter Bengali words. Short ambiguous ones (a, o, na)
     * are left out on purpose. */
    private static final String[] BN_ROMAN = {
            "ami", "tumi", "apni", "tui", "kemon", "kemn", "acho", "aacho",
            "korcho", "koro", "korbe", "ki", "kothay", "kothai", "bhalo",
            "valo", "tomake", "amake", "ekhon", "akhon", "nei", "achi",
            "keno", "kokhon", "kobe", "bolcho", "amar", "tomar", "khub",
            "onek", "thik", "ache", "jabo", "asbo", "kotha", "kemonacho"};

    /** Roman-letter Hindi words. */
    private static final String[] HI_ROMAN = {
            "tum", "aap", "kaise", "kaisa", "kaisi", "kya", "hai", "ho",
            "mera", "meri", "mere", "naam", "nam", "kaun", "kahan", "kaha",
            "abhi", "nahi", "nahin", "theek", "karo", "batao", "accha",
            "acha", "bahut", "bohot", "kab", "mujhe", "tumhe", "tumhara",
            "tumhari", "aapka", "aapki", "kyu", "kyun", "main", "mai",
            "hun", "hoon", "raha", "rahi", "kaisa"};

    /** "Who are you?" in English, Bengali or Hindi, in script or Roman
     * letters - "what is your name?" counts too. */
    private static boolean isWhoAreYou(String msg) {
        String m = msg.toLowerCase(Locale.ENGLISH).trim();
        for (String q : new String[]{
                "who are you", "who r u", "who are u", "who r you",
                "who is this", "who's this", "who is it",
                "what is your name", "whats your name", "what's your name",
                "তুমি কে", "তুই কে", "আপনি কে",
                "তোমার নাম কি", "তোমার নাম কী", "আপনার নাম কি",
                "tumi ke", "tui ke", "apni ke", "ke tumi",
                "tomar nam ki", "tomar naam ki", "tumar nam ki",
                "tum kaun", "tu kaun", "aap kaun",
                "तुम कौन", "तू कौन", "आप कौन",
                "tumhara naam", "aapka naam", "तुम्हारा नाम", "आपका नाम"}) {
            if (m.contains(q)) return true;
        }
        return false;
    }

    /** Can we read contacts? If not, the contact filter stays off. */
    private boolean contactsAvailable() {
        try {
            return checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void ensureContacts(BlackMaskNotificationService svc) {
        if (cachedNames != null && System.currentTimeMillis() - cachedAt < 10 * 60 * 1000) return;
        cachedNames = CommandEngine.allContactNames(svc);
        cachedNumbers = CommandEngine.allContactNumbers(svc);
        cachedAt = System.currentTimeMillis();
    }

    /** Is this sender saved in contacts? A number title is matched by
     * its last 10 digits, a name title by the display name. */
    private boolean isKnownSender(String sender) {
        try {
            ensureContacts(this);
            String d = CommandEngine.digitsOnly(sender);
            if (d.length() >= 7) {
                String tail = d.length() > 10 ? d.substring(d.length() - 10) : d;
                for (String n : cachedNumbers) {
                    String t2 = n.length() > 10 ? n.substring(n.length() - 10) : n;
                    if (t2.equals(tail)) return true;
                }
                return false;
            }
            for (String n : cachedNames) {
                if (n.equalsIgnoreCase(sender.trim())) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // fail open: reply as before
        }
    }

    /** Is this sender one of the picked contacts? First by name, then
     * by number: the name the picker stored and the name WhatsApp
     * shows can differ (SIM copy vs Google copy, nickname, extra
     * spaces), but the number is the same. (v4.9.20: name-only matching
     * was the bug - a picked contact whose WhatsApp name differed by
     * one letter was treated as "not selected" and never answered.) */
    private boolean isSelectedSender(String sender, Set<String> selected) {
        try {
            ensureContacts(this);
            String d = CommandEngine.digitsOnly(sender);
            if (d.length() >= 7) {
                String tail = tail10(d);
                for (String name : selected) {
                    String num = CommandEngine.findContactNumber(this, name);
                    if (num == null) continue;
                    if (tail10(CommandEngine.digitsOnly(num)).equals(tail)) return true;
                }
                return false;
            }
            for (String name : selected) {
                if (name.equalsIgnoreCase(sender.trim())) return true;
            }
            // Same person, different name: look the sender up in
            // contacts and compare numbers instead.
            String senderNum = CommandEngine.findContactNumber(this, sender.trim());
            if (senderNum != null) {
                String st = tail10(CommandEngine.digitsOnly(senderNum));
                for (String name : selected) {
                    String num = CommandEngine.findContactNumber(this, name);
                    if (num == null) continue;
                    if (tail10(CommandEngine.digitsOnly(num)).equals(st)) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Last 10 digits of a number, for comparing numbers. */
    private static String tail10(String digits) {
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** Send text through the notification's own reply button (RemoteInput)
     * - the official Android quick-reply path, the same one a smartwatch
     * uses. Returns false when the notification has no reply button
     * (e.g. a missed-call notice). */
    private boolean sendReply(Notification n, String text) {
        try {
            if (n.actions == null) return false;
            for (Notification.Action action : n.actions) {
                RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs == null || inputs.length == 0) continue;
                Intent intent = new Intent();
                Bundle results = new Bundle();
                results.putCharSequence(inputs[0].getResultKey(), text);
                RemoteInput.addResultsToIntent(inputs, intent, results);
                action.actionIntent.send(this, 0, intent);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
