package com.blackmask.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Fires a scheduled SMS (picked in the Tools page: number + message + time).
 * Sends via CommandEngine.sendSmsReported, so a long Bengali message goes
 * out as a multi-part SMS (single-part sendTextMessage fails on many carriers
 * for those) and the radio's real result is reported by SmsSentReceiver,
 * with a retry through the other SIM on failure. Clears the "scheduled"
 * prefs only when the send is accepted.
 */
public class ScheduledSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String num = intent.getStringExtra("num");
        String msg = intent.getStringExtra("msg");
        if (num == null || num.isEmpty() || msg == null || msg.isEmpty()) return;
        if (!CommandEngine.sendSmsReported(ctx, num, msg, "SCHED")) return;
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        p.edit().putBoolean("sms_scheduled", false).apply();
    }
}
