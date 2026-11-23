package com.blackmask.jarvis;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.List;

/**
 * Result of an SMS the app sent (SOS, scheduled SMS, LOC reply).
 * sendTextMessage returns as soon as the SMS is handed to the radio - it does
 * NOT mean the SMS arrived. This receiver gets the real result from the radio:
 * - OK -> a "SMS sent to <number>" notification.
 * - Failed -> if the phone has another SIM that was not tried yet, the SMS is
 *   silently retried through that SIM (a common fix: the default SIM has no
 *   SMS balance while the other one does). Only when no SIM is left does it
 *   show the failure notification, with a hint to check the SMS balance.
 */
public class SmsSentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String to = intent.getStringExtra("to");
        String msg = intent.getStringExtra("msg");
        String tag = intent.getStringExtra("tag");
        if (to == null) to = "";
        int result = getResultCode();
        if (result == Activity.RESULT_OK) {
            notify(ctx, "Black Mask AI" + (tag != null && !tag.isEmpty() ? " • " + tag : ""),
                    ctx.getString(R.string.sms_sent_to) + " " + to);
            return;
        }
        // Failed - try the other SIM first (if any), before telling the user.
        int triedSub = intent.getIntExtra("sub", -1);
        String tried = intent.getStringExtra("tried");
        if (tried == null) tried = "";
        if (msg != null) {
            try {
                List<SubscriptionInfo> subs = SubscriptionManager.from(ctx).getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo info : subs) {
                        int subId = info.getSubscriptionId();
                        if (subId == triedSub) continue;
                        if (("," + tried + ",").contains("," + subId + ",")) continue;
                        String nextTried = tried.isEmpty() ? String.valueOf(subId) : tried + "," + subId;
                        if (CommandEngine.sendSmsViaSub(ctx, to, msg, tag, subId, nextTried)) return;
                    }
                }
            } catch (Exception ignored) {}
        }
        String why;
        if (result == SmsManager.RESULT_ERROR_NO_SERVICE) why = ctx.getString(R.string.sms_no_service);
        else if (result == SmsManager.RESULT_ERROR_RADIO_OFF) why = ctx.getString(R.string.sms_radio_off);
        else why = ctx.getString(R.string.sms_generic_fail) + " - " + ctx.getString(R.string.sms_balance_hint);
        notify(ctx, "Black Mask AI" + (tag != null && !tag.isEmpty() ? " • " + tag : ""),
                ctx.getString(R.string.sms_send_fail) + " (" + why + ") " + to);
    }

    private void notify(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel("jarvis_channel", "Black Mask AI",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(ctx, "jarvis_channel");
            else b = new Notification.Builder(ctx);
            b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info);
            nm.notify(6, b.build());
        } catch (Exception ignored) {}
    }
}
