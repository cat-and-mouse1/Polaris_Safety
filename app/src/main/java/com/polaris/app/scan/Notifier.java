package com.polaris.app.scan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.polaris.app.BlockOffReceiver;
import com.polaris.app.R;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.PermUtil;
import com.polaris.app.util.Prefs;

import java.util.List;

/** 通知封装：扫描提醒 / 拦截常驻通知 / 应用监控告警。 */
public final class Notifier {

    public static final String CHANNEL_SCAN = "scan";
    public static final String CHANNEL_BLOCK = "block";
    public static final String CHANNEL_MONITOR = "monitor";
    public static final int BLOCK_NOTIFICATION_ID = 7;
    private static final int MONITOR_NOTIFICATION_ID = 3000;

    private Notifier() {}

    public static void ensureChannels(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel scan = new NotificationChannel(
                CHANNEL_SCAN,
                context.getString(R.string.notification_channel_scan),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(scan);
        NotificationChannel block = new NotificationChannel(
                CHANNEL_BLOCK,
                context.getString(R.string.block_mode_title),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(block);
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(monitor);
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

    // ---------- 拦截模式常驻通知 ----------

    /**
     * 刷新拦截模式常驻置顶通知（唯一入口）：
     * - 拦截模式未开启：不显示该通知（有则撤销）；
     * - 已开启且权限齐全：显示「北极星的光辉照耀储存——获得加护」+ 红色「关闭」按钮；
     * - 已开启但缺少权限：显示「北极星缺少XXX权限」+「去授权」按钮（跳转对应设置板块）+「关闭」。
     */
    public static void updateBlockNotification(Context context) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Prefs prefs = new Prefs(context);
        if (!prefs.isBlockMode()) {
            nm.cancel(BLOCK_NOTIFICATION_ID);
            return;
        }
        ensureChannels(context);

        // 找出第一个缺失的权限（无障碍 → 悬浮窗 → 使用情况访问）
        String missingPermName = null;
        Intent grantIntent = null;
        if (!PermUtil.isAccessibilityEnabled(context)) {
            missingPermName = context.getString(R.string.perm_name_accessibility);
            grantIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        } else if (!PermUtil.canDrawOverlays(context)) {
            missingPermName = context.getString(R.string.perm_name_overlay);
            grantIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
        } else if (!PermUtil.hasUsageAccess(context)) {
            missingPermName = context.getString(R.string.perm_name_usage);
            grantIntent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
        }

        // 红色「关闭」动作：关闭拦截模式并撤销通知
        Intent off = new Intent(context, BlockOffReceiver.class)
                .setAction(BlockOffReceiver.ACTION_BLOCK_OFF);
        PendingIntent offPi = PendingIntent.getBroadcast(context, 1, off,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_BLOCK)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.block_notification_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // 通知强调色（红色）：动作按钮文字跟随此颜色
                .setColor(ContextCompat.getColor(context, R.color.md_theme_error))
                .addAction(0, context.getString(R.string.block_action_off), offPi);

        if (missingPermName != null) {
            // 缺少权限：提示 + 去授权（跳转对应系统设置板块）
            b.setContentText(context.getString(
                    R.string.block_notification_missing_perm, missingPermName));
            if (grantIntent != null) {
                grantIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent grantPi = PendingIntent.getActivity(context, 3, grantIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(0, context.getString(R.string.block_action_grant), grantPi);
            }
        } else {
            b.setContentText(context.getString(R.string.block_notification_text));
        }

        // 点击通知体：回到扫描中心
        Intent open = new Intent(context, com.polaris.app.FileScanActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(context, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentIntent(contentPi);

        try {
            nm.notify(BLOCK_NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** 关闭拦截模式时撤销常驻通知。 */
    public static void cancelBlockNotification(Context context) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(BLOCK_NOTIFICATION_ID);
    }

    // ---------- 应用监控告警 ----------

    /** 监控的应用出现异常行为时，立即高优先级通知用户。 */
    public static void notifyMonitorAlert(Context context, String appName, String pkg,
                                          String reason) {
        ensureChannels(context);
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
        Notification n = new NotificationCompat.Builder(context, CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.monitor_alert_title))
                .setContentText(context.getString(R.string.monitor_alert_text, appName, reason))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.monitor_alert_text, appName, reason)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        try {
            NotificationManagerCompat.from(context).notify(
                    MONITOR_NOTIFICATION_ID + Math.abs(pkg.hashCode()) % 1000, n);
        } catch (SecurityException ignored) {
        }
        BehaviorLog.log(context, BehaviorLog.TYPE_MONITOR,
                appName, context.getString(R.string.monitor_alert_text, appName, reason));
    }
}
