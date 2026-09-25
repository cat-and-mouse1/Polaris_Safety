package com.polaris.app.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.polaris.app.scan.Notifier;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Accessibility 模式核心：监听前台窗口变化，实时记录当前前台应用，
 * 供深层扫描与 GuardService 使用。同时在服务生命周期内维护启用状态。
 *
 * 全域巡查：当拦截模式开启且前台出现命中恶意名单（锁机/勒索/霸屏）的应用时，
 * 触发救援覆盖层（悬浮窗）——见 {@link RescueOverlayService}。
 *
 * 紧急遇险：连续按三次音量上键，立即 force-stop 当前霸屏应用并直接卸载，
 * 用于覆盖层/其他入口均无法调用时的兜底逃生。检测逻辑为静态实现，
 * 无障碍服务未连接时仍可通过 Activity 的 dispatchKeyEvent 喂入事件。
 *
 * 应用监控：被用户选中监控的应用出现异常行为（后台启动界面 / 后台发通知）
 * 时立即通知用户，并记录到「行为」日志。
 */
public class PolarisAccessibilityService extends AccessibilityService {

    private static final String TAG = "PolarisAccess";

    /** 供外部（如 RescueOverlayService）调用无障碍能力时引用当前实例。 */
    private static volatile PolarisAccessibilityService sInstance;

    private Prefs prefs;
    private long lastRescueAt = 0L;
    private static final long RESCUE_COOLDOWN_MS = 8000L;

    // ---- 三击音量上键检测（静态：前台 Activity 亦可喂入） ----
    private static final long TRIPLE_PRESS_WINDOW_MS = 1500L;
    private static final long[] sVolUpTimes = new long[3];
    private static int sVolUpIndex = 0;

    // ---- 应用监控：每包告警冷却，避免刷屏 ----
    private static final long MONITOR_COOLDOWN_MS = 5 * 60 * 1000L;
    private final Map<String, Long> monitorLastAlert = new HashMap<>();

    /** 无障碍能力是否可用（服务已连接）。 */
    public static boolean isAvailable() {
        return sInstance != null;
    }

    /** 通过无障碍能力「按掉」霸屏界面（返回桌面），普通模式也可用。 */
    public static boolean pressHome() {
        PolarisAccessibilityService s = sInstance;
        if (s == null) return false;
        try {
            return s.performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception e) {
            Log.w(TAG, "pressHome failed: " + e.getMessage());
            return false;
        }
    }

    /** 通过无障碍能力返回上一屏，用于退出霸屏页面。 */
    public static boolean pressBack() {
        PolarisAccessibilityService s = sInstance;
        if (s == null) return false;
        try {
            return s.performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) {
            Log.w(TAG, "pressBack failed: " + e.getMessage());
            return false;
        }
    }

    // ---- 三击音量上键（静态入口，Activity 与服务共用） ----

    /**
     * 喂入一次音量上键按下事件（服务 onKeyEvent 与 Activity dispatchKeyEvent 均调用）。
     * 返回 true 表示三击命中（紧急遇险已触发），调用方应消费该事件。
     */
    public static boolean handleVolumeUp(android.content.Context fallbackCtx) {
        long now = SystemClock.elapsedRealtime();
        sVolUpTimes[sVolUpIndex] = now;
        sVolUpIndex = (sVolUpIndex + 1) % sVolUpTimes.length;
        if (isTriplePress(now)) {
            Log.w(TAG, "Triple volume-up detected, triggering emergency rescue");
            clearVolUpTimes();
            Context ctx = sInstance != null ? sInstance : fallbackCtx;
            if (ctx != null) triggerEmergencyRescue(ctx);
            return true;
        }
        return false;
    }

    private static boolean isTriplePress(long now) {
        int filled = 0;
        for (long t : sVolUpTimes) {
            if (t != 0L) filled++;
        }
        if (filled < 3) return false;
        long oldest = Long.MAX_VALUE;
        long newest = Long.MIN_VALUE;
        for (long t : sVolUpTimes) {
            if (t < oldest) oldest = t;
            if (t > newest) newest = t;
        }
        return (newest - oldest) <= TRIPLE_PRESS_WINDOW_MS;
    }

    private static void clearVolUpTimes() {
        for (int i = 0; i < sVolUpTimes.length; i++) sVolUpTimes[i] = 0L;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        prefs = new Prefs(this);
        prefs.setAccessibilityReady(true);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null || pkgCs.length() == 0) return;
        String pkg = pkgCs.toString();

        if (prefs == null) prefs = new Prefs(this);
        String prevForeground = prefs.getLastForegroundPkg();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            prefs.setLastForegroundPkg(pkg);
            maybeTriggerRescue(pkg);
        }

        // 应用监控：被监控应用出现异常行为立即提醒
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            checkMonitoredApp(pkg, type, prevForeground);
        }
    }

    /**
     * 全域巡查：拦截模式开启且前台应用命中恶意名单时，弹出救援覆盖层。
     * 仅在持有悬浮窗权限时触发；带冷却时间避免频繁弹窗。
     */
    private void maybeTriggerRescue(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        if (!prefs.isBlockMode()) return;
        if (!android.provider.Settings.canDrawOverlays(this)) return;

        Set<String> malicious = prefs.getMaliciousPackages();
        Set<String> blocked = prefs.getBlockedApps();
        if (!malicious.contains(pkg) && !blocked.contains(pkg)) return;

        long now = System.currentTimeMillis();
        if (now - lastRescueAt < RESCUE_COOLDOWN_MS) return;
        lastRescueAt = now;

        Log.w(TAG, "Rescue overlay triggered for malicious foreground app: " + pkg);
        BehaviorLog.log(this, BehaviorLog.TYPE_RESCUE, appLabel(pkg),
                getString(com.polaris.app.R.string.rescue_log_shown, pkg));
        RescueOverlayService.show(this, pkg);
    }

    // ---- 应用监控：异常行为检测 ----

    /**
     * 检查被监控应用是否出现异常行为：
     * - 在后台启动界面（Activity）——窗口状态变化但活跃窗口不属于该应用；
     * - 在后台发送通知——通知事件到来时该应用不在前台。
     * 命中即高优先级通知用户（每应用 5 分钟冷却一次）。
     */
    private void checkMonitoredApp(String pkg, int eventType, String prevForeground) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        Set<String> monitored = prefs.getMonitoredApps();
        if (monitored == null || monitored.isEmpty() || !monitored.contains(pkg)) return;

        String reason = null;
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // 后台发通知：事件到来时该应用不是当前前台应用
            if (prevForeground == null || !prevForeground.equals(pkg)) {
                reason = getString(com.polaris.app.R.string.monitor_reason_notification);
            }
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 后台启动界面：窗口状态变化，但当前活跃窗口不属于该应用
            String activePkg = activeWindowPkg();
            if (activePkg != null && !activePkg.equals(pkg)
                    && (prevForeground == null || !prevForeground.equals(pkg))) {
                reason = getString(com.polaris.app.R.string.monitor_reason_background_activity);
            }
        }
        if (reason == null) return;

        long now = System.currentTimeMillis();
        Long last = monitorLastAlert.get(pkg);
        if (last != null && now - last < MONITOR_COOLDOWN_MS) return;
        monitorLastAlert.put(pkg, now);

        Log.w(TAG, "Monitored app anomaly: " + pkg + " (" + reason + ")");
        Notifier.notifyMonitorAlert(this, appLabel(pkg), pkg, reason);
    }

    /** 当前活跃窗口的包名（用于判断某应用是否真正在前台）。 */
    private String activeWindowPkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * 拦截按键事件：检测「连续三次音量上键」触发紧急遇险。
     * 返回 true 表示消费该事件；仅在三击命中的那次消费，其余放行给系统调音量。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (handleVolumeUp(this)) {
                return true;
            }
        }
        return super.onKeyEvent(event);
    }

    /**
     * 紧急遇险：立即终止当前霸屏应用并直接卸载，防止复活。
     * 优先 Shizuku / Root force-stop + pm uninstall（静默卸载）；
     * 无特权则按 HOME 退出霸屏并拉起系统卸载界面由用户确认。
     */
    private static void triggerEmergencyRescue(android.content.Context ctx) {
        Prefs prefs = new Prefs(ctx);
        String pkg = prefs.getLastForegroundPkg();
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) {
            // 无明确霸屏目标：仅按 HOME 兜底逃生
            pressHome();
            BehaviorLog.log(ctx, BehaviorLog.TYPE_EMERGENCY,
                    ctx.getString(com.polaris.app.R.string.app_name),
                    ctx.getString(com.polaris.app.R.string.emergency_no_target));
            return;
        }

        boolean stopped = false;
        if (ShizukuHelper.hasShizukuPermission()) {
            stopped = ShizukuHelper.runShell("am", "force-stop", pkg) != null;
        }
        if (!stopped && RootChecker.isRootAvailable()) {
            stopped = RootChecker.runAsRoot("am force-stop " + pkg);
        }
        if (!stopped) {
            // 无特权：用无障碍按 HOME 退出霸屏界面
            pressHome();
        }
        prefs.addBlockedApp(pkg);
        BehaviorLog.log(ctx, BehaviorLog.TYPE_EMERGENCY, pkg,
                ctx.getString(com.polaris.app.R.string.emergency_log_stopped));

        // 防复活：优先静默卸载（Shizuku / Root），失败则拉起系统卸载界面
        boolean silentUninstalled = false;
        if (ShizukuHelper.hasShizukuPermission()) {
            silentUninstalled = ShizukuHelper.runShell(
                    "pm", "uninstall", "--user", "0", pkg) != null;
        }
        if (!silentUninstalled && RootChecker.isRootAvailable()) {
            silentUninstalled = RootChecker.runAsRoot("pm uninstall --user 0 " + pkg);
        }
        if (silentUninstalled) {
            BehaviorLog.log(ctx, BehaviorLog.TYPE_UNINSTALL, pkg,
                    ctx.getString(com.polaris.app.R.string.emergency_log_uninstalled));
        } else {
            BehaviorLog.log(ctx, BehaviorLog.TYPE_UNINSTALL, pkg,
                    ctx.getString(com.polaris.app.R.string.emergency_log_uninstall_ask));
            launchUninstall(ctx, pkg);
        }
    }

    private static void launchUninstall(android.content.Context ctx, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "launchUninstall failed: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        if (prefs != null) prefs.setAccessibilityReady(false);
        Log.i(TAG, "Accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (prefs != null) prefs.setAccessibilityReady(false);
        super.onDestroy();
    }
}
