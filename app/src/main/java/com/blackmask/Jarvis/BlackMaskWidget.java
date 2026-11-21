package com.blackmask.jarvis;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Home screen widget for Black Mask AI - tap the logo to talk.
 */
public class BlackMaskWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            Intent listen = new Intent(context, JarvisService.class)
                    .setAction(JarvisService.ACTION_LISTEN);
            PendingIntent listenPi = PendingIntent.getService(
                    context, 0, listen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent open = new Intent(context, MainActivity.class);
            PendingIntent openPi = PendingIntent.getActivity(
                    context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
            views.setOnClickPendingIntent(R.id.widgetMic, listenPi);
            views.setOnClickPendingIntent(R.id.widgetRoot, openPi);
            manager.updateAppWidget(id, views);
        }
    }
}
