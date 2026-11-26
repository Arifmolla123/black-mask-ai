package com.blackmask.jarvis;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;

/**
 * Reads WhatsApp / Telegram / Messenger messages aloud (when the "Read
 * notifications aloud" toggle is on in Settings) and remembers the last
 * sender so the user can say "reply <text>".
 *
 * Auto-reply (v4.9.16, More Settings, below App Lock): when the toggle
 * is on, an incoming direct message gets one automatic reply -
 * "I am Black Mask AI, my owner is not online right now." - sent through
 * the notification's own reply button, the same official way a smartwatch
 * replies. No WhatsApp / Telegram login, no bot: it answers as the owner,
 * from the owner's own phone. Group messages are skipped, and each sender
 * gets at most one auto-reply every 30 minutes, so two auto-repliers can
 * never loop forever.
 *
 * The user must also enable notification access once:
 * Settings -> Notifications -> Notification access -> Black Mask AI.
 */
public class BlackMaskNotificationService extends NotificationListenerService {

    /** The automatic reply, in the owner's words. */
    public static final String AUTO_REPLY_TEXT = "আমি Black Mask AI — আমার মালিক এখন অনলাইনে নেই।";
    /** Answer to "who are you?" - just the identity, nothing more. */
    public static final String WHO_ARE_YOU_TEXT = "আমি Black Mask AI।";
    private static final long REPLY_COOLDOWN_MS = 30 * 60 * 1000;
    private static final int MAX_REPLIES_PER_WINDOW = 3;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)
                && !"org.telegram.messenger".equals(pkg)
                && !"com.facebook.orca".equals(pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || text == null) return;
        String sender = title.toString().trim();
        String msg = text.toString().trim();
        if (sender.isEmpty() || msg.isEmpty()) return;
        if (sender.contains("Black Mask")) return;
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        if (p.getBoolean("read_notif", false)) {
            p.edit().putString("last_notif_sender", sender).putString("last_notif_text", msg).apply();
            JarvisService.speakText(this, sender + " বলছে: " + msg);
        }
        if (p.getBoolean("autoreply_on", false)) {
            autoReply(n, p, pkg, sender, msg);
        }
    }

    /** Up to 3 automatic replies per sender every 30 minutes. Group
     * messages are skipped: their notification carries a conversation
     * title (the group name). */
    private void autoReply(Notification n, SharedPreferences p,
            String pkg, String sender, String msg) {
        try {
            if (msg.contains("Black Mask AI")) return; // our own echo
            if (n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null) return;
            String key = "autoreply_" + (pkg + "|" + sender).hashCode();
            long now = System.currentTimeMillis();
            long windowStart = p.getLong(key + "_window", 0);
            int count = p.getInt(key + "_count", 0);
            if (now - windowStart >= REPLY_COOLDOWN_MS) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_REPLIES_PER_WINDOW) return;
            String reply = isWhoAreYou(msg) ? WHO_ARE_YOU_TEXT : AUTO_REPLY_TEXT;
            if (!sendReply(n, reply)) return;
            p.edit().putLong(key + "_window", windowStart)
                    .putInt(key + "_count", count + 1).apply();
        } catch (Exception ignored) {}
    }

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
