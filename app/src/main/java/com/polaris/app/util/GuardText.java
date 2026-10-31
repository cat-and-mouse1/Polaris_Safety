package com.polaris.app.util;

import android.content.Context;

import com.polaris.app.R;

/**
 * 守护面板 / 主页的文案生成（纯函数，MainActivity 拆分第一刀）。
 *
 * 抽取动机：MainActivity 里问候语「时段 → 文案」逻辑原本逐字重复两份
 * （updateHomeGreeting 与 loadGuardDashboard），timeAgo 的时长分段也是内联代码；
 * 统一到这里后可独立测试，且两处永远同步。
 */
public final class GuardText {

    private GuardText() {
    }

    /** 按时段问候语（6-12 早 / 12-18 午 / 其余晚）。 */
    public static String greeting(Context ctx, int hourOfDay) {
        if (hourOfDay >= 6 && hourOfDay < 12) {
            return ctx.getString(R.string.guard_dashboard_greeting_morning);
        } else if (hourOfDay >= 12 && hourOfDay < 18) {
            return ctx.getString(R.string.guard_dashboard_greeting_afternoon);
        }
        return ctx.getString(R.string.guard_dashboard_greeting_evening);
    }

    /** 「x 分钟 / 小时 / 天前」；从未扫描（<=0）返回「从未」。 */
    public static String timeAgo(Context ctx, long lastScanMs, long nowMs) {
        if (lastScanMs <= 0) return ctx.getString(R.string.guard_dashboard_time_never);
        long diff = nowMs - lastScanMs;
        if (diff < 60_000) {
            return ctx.getString(R.string.guard_dashboard_time_just_now);
        } else if (diff < 3_600_000) {
            return ctx.getString(R.string.guard_dashboard_time_minutes, (int) (diff / 60_000));
        } else if (diff < 86_400_000) {
            return ctx.getString(R.string.guard_dashboard_time_hours, (int) (diff / 3_600_000));
        }
        return ctx.getString(R.string.guard_dashboard_time_days, (int) (diff / 86_400_000));
    }

    /** 模式 key → 显示名（未知回落「标准模式」）。 */
    public static String modeName(Context ctx, int modeKey) {
        switch (modeKey) {
            case com.polaris.app.util.Prefs.MODE_ACCESSIBILITY:
                return ctx.getString(R.string.mode_accessibility_name);
            case com.polaris.app.util.Prefs.MODE_SHIZUKU:
                return ctx.getString(R.string.mode_shizuku_name);
            case com.polaris.app.util.Prefs.MODE_ROOT:
                return ctx.getString(R.string.mode_root_name);
            default:
                return ctx.getString(R.string.mode_normal_name);
        }
    }

    /**
     * 守护面板「最近扫描结果」多行摘要：总数 / 安全必列，可疑与恶意仅在 >0 时出现。
     * total < 0 表示从未扫描，返回空串（调用方显示「无扫描详情」）。
     */
    public static String recentScanSummary(Context ctx, int total, int safe,
                                           int suspicious, int malicious) {
        if (total < 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.guard_dashboard_scan_total, total)).append("\n");
        sb.append(ctx.getString(R.string.guard_dashboard_scan_safe, safe)).append("\n");
        if (suspicious > 0) {
            sb.append(ctx.getString(R.string.guard_dashboard_scan_suspicious, suspicious)).append("\n");
        }
        if (malicious > 0) {
            sb.append(ctx.getString(R.string.guard_dashboard_scan_malicious, malicious));
        }
        return sb.toString().trim();
    }
}
