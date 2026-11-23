package com.blackmask.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMS automation (each has an on/off toggle in Settings, AUTOMATION):
 * - "BLACKMASK RING" -> ring loudly 30s (find my phone)
 * - "BLACKMASK LOC" -> reply with a Google Maps link (find my phone's location)
 * - "BLACKMASK SCROLL DOWN/UP" -> scroll the screen (from another phone)
 * - Any SMS with an OTP -> copy it to the clipboard + notify
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        try {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;
            String format = bundle.getString("format");
            StringBuilder body = new StringBuilder();
            String sender = null;
            for (Object pdu : pdus) {
                SmsMessage msg;
                if (format != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg == null) continue;
                if (sender == null) sender = msg.getOriginatingAddress();
                if (msg.getMessageBody() != null) body.append(msg.getMessageBody());
            }
            String text = body.toString();
            String low = text.toLowerCase(Locale.ENGLISH);
            SharedPreferences p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            // Remember the last SMS that arrived (Settings shows it). This tells
            // the user whether the SMS even reaches the app - if it never updates,
            // the SMS permission is off or the phone blocks the app in background.
            try {
                p.edit().putLong("last_sms_time", System.currentTimeMillis())
                        .putString("last_sms_from", sender != null ? sender : "")
                        .putBoolean("last_sms_blackmask", low.contains("blackmask")).apply();
            } catch (Exception ignored) {}
            if (low.contains("blackmask ring")) {
                if (p.getBoolean("auto_ring", true)) CommandEngine.startRing(context);
                return;
            }
            if (low.contains("blackmask loc")) {
                if (p.getBoolean("auto_loc", true) && sender != null) sendLocation(context, sender);
                return;
            }
            if (low.contains("blackmask scroll")) {
                if (low.contains("up")) BlackMaskAccessibilityService.doGlobal("scroll_up");
                else BlackMaskAccessibilityService.doGlobal("scroll_down");
                return;
            }
            // Anti-theft (v4.9.8): "BLACKMASK UNLOCK" from the trusted number
            // unlocks the phone for 5 minutes (forgot PIN). "BLACKMASK STOP"
            // silences the anti-theft ring.
            if (low.contains("blackmask unlock")) {
                CommandEngine.remoteUnlock(context, sender);
                return;
            }
            if (low.contains("blackmask stop")) {
                CommandEngine.stopRingIfTrusted(context, sender);
                return;
            }
            // OTP auto-copy.
            if (p.getBoolean("auto_otp", true)) {
                String otp = findOtp(text);
                if (otp != null) {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OTP", otp));
                    notifyOtp(context, otp, sender);
                }
            }
        } catch (Exception ignored) {}
    }

    /** A 4-6 digit code in a message that mentions OTP / verification / code. */
    private String findOtp(String text) {
        String low = text.toLowerCase(Locale.ENGLISH);
        if (!low.contains("otp") && !low.contains("verification") && !low.contains("code")) return null;
        Matcher m = Pattern.compile("\\b(\\d{4,6})\\b").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Reply to the sender with the phone's location as a Maps link. */
    private void sendLocation(Context context, String to) {
        try {
            String loc = CommandEngine.locationLink(context);
            String msg = loc.isEmpty()
                    ? "Black Mask AI: ফোনের লোকেশন পাওয়া যায়নি — GPS চালু করো।"
                    : "Black Mask AI: আমি এখানে আছি: " + loc;
            CommandEngine.sendSmsReported(context, to, msg, "LOC");
        } catch (Exception ignored) {}
    }

    private void notifyOtp(Context context, String otp, String sender) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel("jarvis_channel", "Black Mask AI", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(context, "jarvis_channel");
            } else {
                b = new Notification.Builder(context);
            }
            b.setContentTitle("OTP copied: " + otp)
                    .setContentText(sender != null ? "From " + sender : "OTP copied to clipboard")
                    .setSmallIcon(android.R.drawable.ic_dialog_info);
            nm.notify(1001, b.build());
        } catch (Exception ignored) {}
    }
}
