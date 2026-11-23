package com.blackmask.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts Black Mask AI when the phone boots, so the assistant is
 * always running in the background without opening the app.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Morning briefing again after a reboot, if it was on.
            if (context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("briefing", false)) {
                CommandEngine.setMorningBriefing(context, true);
            }
            Intent service = new Intent(context, JarvisService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } catch (Exception ignored) {}
        }
    }
}
