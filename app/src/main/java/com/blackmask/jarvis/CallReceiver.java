package com.blackmask.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

/**
 * Missed-call automation: when a call rings and is never answered,
 * send an SMS back. What the SMS says depends on the toggles in Settings:
 * - unknown caller + screening on: ask who they are (once per number per hour)
 * - driving mode on: "আমি এখন ড্রাইভ করছি"
 * - missed-call SMS on: "পরে কল করছি"
 *
 * Honest limit: the app cannot pick up the call and talk - only the
 * default Phone app can do that. So screening happens by SMS.
 */
public class CallReceiver extends BroadcastReceiver {
    private static String ringingNumber;
    private static boolean answered;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            ringingNumber = number;
            answered = false;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            answered = true;
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            if (ringingNumber != null && !answered) onMissedCall(context, ringingNumber);
            ringingNumber = null;
            answered = false;
        }
    }

    private void onMissedCall(Context context, String number) {
        if (number == null || number.isEmpty()) return;
        SharedPreferences p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        try {
            boolean unknown = !CommandEngine.isContact(context, number);
            if (unknown && p.getBoolean("screening", true)) {
                // Ask who they are - at most once per number per hour.
                long last = p.getLong("screen_last_" + number, 0);
                if (System.currentTimeMillis() - last < 3600_000L) return;
                p.edit().putLong("screen_last_" + number, System.currentTimeMillis()).apply();
                sendSms(context, number, "নমস্কার, আপনি কে বলছেন? এই নম্বরটি এখন ব্যস্ত আছে। আপনার নাম SMS-এ লিখে পাঠান। — Black Mask AI");
                return;
            }
            if (p.getBoolean("driving", false)) {
                sendSms(context, number, "আমি এখন ড্রাইভ করছি, পরে কল করছি। — Black Mask AI");
            } else if (p.getBoolean("missed_sms", true)) {
                sendSms(context, number, "পরে কল করছি, এখন ব্যস্ত আছি। — Black Mask AI");
            }
        } catch (Exception ignored) {}
    }

    private void sendSms(Context context, String number, String text) {
        // Multi-part via sendSmsReported: the screening SMS is ~95 Bengali
        // chars (2 parts), and single-part sendTextMessage fails for those
        // on many carriers. The radio's real result is reported by
        // SmsSentReceiver, with a retry through the other SIM on failure.
        CommandEngine.sendSmsReported(context, number, text, "CALL");
    }
}
