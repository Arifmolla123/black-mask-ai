package com.blackmask.jarvis;

import android.app.Notification;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Reads WhatsApp / Telegram messages aloud (when the "Read notifications
 * aloud" toggle is on in Settings) and remembers the last sender so the
 * user can say "reply <text>".
 *
 * The user must also enable notification access once:
 * Settings -> Notifications -> Notification access -> Black Mask AI.
 */
public class BlackMaskNotificationService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)
                && !"org.telegram.messenger".equals(pkg)) return;
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        if (!p.getBoolean("read_notif", false)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || text == null) return;
        String sender = title.toString().trim();
        String msg = text.toString().trim();
        if (sender.isEmpty() || msg.isEmpty()) return;
        if (sender.contains("Black Mask")) return;
        p.edit().putString("last_notif_sender", sender).putString("last_notif_text", msg).apply();
        JarvisService.speakText(this, sender + " বলছে: " + msg);
    }
}
