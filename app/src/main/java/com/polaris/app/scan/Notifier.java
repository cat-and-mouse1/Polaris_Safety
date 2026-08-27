package com.polaris.app.scan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.polaris.app.R;

import java.util.List;

/** 通知封装：扫描提醒 / 拦截提醒。 */
public final class Notifier {

    public static final String CHANNEL_SCAN = "scan";

    private Notifier() {}

    public static void ensureChannels(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel scan = new NotificationChannel(
                CHANNEL_SCAN,
                context.getString(R.string.notification_channel_scan),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(scan);
    }

    public static void notifyRisks(Context context, List<AppRiskInfo> risks) {
        ensureChannels(context);
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;

        int count = 0;
        for (AppRiskInfo r : risks) {
            if (r.level >= AppRiskInfo.LEVEL_MEDIUM) count++;
        }
        if (count == 0) return;

        Notification n = new NotificationCompat.Builder(context, CHANNEL_SCAN)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.notification_scan_title))
                .setContentText(context.getString(R.string.scan_done_risk, count))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.scan_done_risk, count)))
                .setAutoCancel(true)
                .build();
        try {
            NotificationManagerCompat.from(context).notify(1001, n);
        } catch (SecurityException ignored) {
        }
    }
}
