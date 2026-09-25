package com.polaris.app.util;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import com.polaris.app.service.PolarisAccessibilityService;

/**
 * 特殊权限检测与授权跳转（无障碍 / 悬浮窗 / 使用情况访问）。
 *
 * 这三类权限均无法通过普通 runtime permission 一次性申请，
 * 必须引导用户到系统设置页手动开启：
 * - 无障碍：ACTION_ACCESSIBILITY_SETTINGS
 * - 悬浮窗：ACTION_MANAGE_OVERLAY_PERMISSION（包级）
 * - 使用情况访问：ACTION_USAGE_ACCESS_SETTINGS
 */
public final class PermUtil {

    private PermUtil() {}

    // ---------- 无障碍权限 ----------

    /** 本应用的无障碍服务是否已在系统设置中开启。 */
    public static boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = context.getPackageName() + "/"
                + PolarisAccessibilityService.class.getName();
        return enabled.contains(component);
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---------- 悬浮窗权限 ----------

    /** 本应用是否拥有悬浮窗（SYSTEM_ALERT_WINDOW）权限。 */
    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static void openOverlaySettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            // 部分设备无包级入口，退回通用悬浮窗管理页
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 使用情况访问权限 ----------

    /** 本应用是否拥有「使用情况访问」（PACKAGE_USAGE_STATS）权限。 */
    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager aom = (AppOpsManager)
                    context.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED
                    || mode == AppOpsManager.MODE_DEFAULT;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openUsageAccessSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }
}
