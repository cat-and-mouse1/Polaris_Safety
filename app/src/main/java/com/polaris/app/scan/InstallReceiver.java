package com.polaris.app.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.polaris.app.util.Prefs;

/**
 * 应用安装 / 更新后立即扫描这个包。
 *
 * <p>为什么需要：如果只用每日定时扫描，用户装完恶意应用后要等到当天扫描才可能发现，
 * 中间有十几个小时空窗。
 *
 * <p><b>注意 Android 8+ 的限制：</b>{@code PACKAGE_ADDED} / {@code PACKAGE_REPLACED}
 * 已被移出「隐式广播豁免名单」，静态注册在真机上收不到（本机实测确认）。
 * 因此实际生效路径是 {@code GuardService} 在守护运行时**动态注册**本接收器
 * （见 {@code GuardService#registerInstallWatcher()}）。静态声明保留仅为兼容旧系统。
 */
public class InstallReceiver extends BroadcastReceiver {

    private static final String TAG = "PolarisInstall";

    /** 同一个包 3 秒内只扫一次：静态广播与 GuardService 的动态注册可能同时送达。 */
    private static final java.util.Map<String, Long> RECENT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 扫描指定包，命中中危及以上则发通知。
     * 可由本接收器、也可由 GuardService 的动态注册直接调用（调用方负责在后台线程执行）。
     */
    public static void scanInstalled(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(context.getPackageName())) return;   // 自身更新跳过
        long now = System.currentTimeMillis();
        Long last = RECENT.get(pkg);
        if (last != null && now - last < 3000L) return;      // 去重
        RECENT.put(pkg, now);
        if (RECENT.size() > 200) RECENT.clear();
        try {
            Prefs prefs = new Prefs(context);
            MalwareScanner scanner = new MalwareScanner(context, prefs.isDeepScanMode());
            AppRiskInfo info = scanner.scanPackage(pkg);
            Log.i(TAG, "scanned on install: " + pkg + " -> "
                    + (info == null ? "skip" : ("score=" + info.score + " level=" + info.level)));
            if (info != null && info.level >= AppRiskInfo.LEVEL_MEDIUM) {
                Log.w(TAG, "new app flagged: " + pkg + " score=" + info.score
                        + " level=" + info.level);
                Notifier.notifyInstalledRisk(context, info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan on install failed: " + pkg, t);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        String pkg = data != null ? data.getSchemeSpecificPart() : null;
        if (pkg == null || pkg.isEmpty()) return;

        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                scanInstalled(app, pkg);
            } finally {
                try {
                    pending.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "polaris-install-scan").start();
    }
}
