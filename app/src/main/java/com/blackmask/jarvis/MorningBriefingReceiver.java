package com.blackmask.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Daily 7 AM morning briefing: time + weather + headlines in one
 * notification. Scheduled from Settings ("Morning briefing at 7 AM")
 * and re-scheduled on boot by BootReceiver.
 */
public class MorningBriefingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new Thread(() -> {
            String text = CommandEngine.getBriefingText();
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel(
                            "briefing_channel", "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT);
                    nm.createNotificationChannel(ch);
                }
                Intent open = new Intent(context, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(context, "briefing_channel")
                        : new Notification.Builder(context);
                b.setContentTitle("Good morning from Black Mask AI")
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setAutoCancel(true);
                nm.notify(3, b.build());
            } catch (Exception ignored) {}
        }).start();
    }
}
