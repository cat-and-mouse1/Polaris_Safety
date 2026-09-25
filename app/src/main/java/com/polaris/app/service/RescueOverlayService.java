package com.polaris.app.service;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.polaris.app.R;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;

import java.util.Set;

/**
 * 救援覆盖层服务（悬浮窗权限）。
 *
 * 当检测到前台出现全屏锁机 / 勒索 / 霸屏类恶意应用时，
 * 以最高优先级悬浮窗「再次霸屏」，在恶意应用之上盖一层救援界面：
 * - 屏幕中心显示四芒星图标 + 纯红救援提示
 * - 「清除」：强制停止霸屏应用；「放行」：放行该应用并撤下覆盖层
 * - 不间断重试挂载，防止恶意应用把覆盖层顶掉 / 关闭后再覆盖
 */
public class RescueOverlayService extends Service {

    private static final String TAG = "RescueOverlay";
    private static final String ACTION_SHOW = "com.polaris.app.action.RESCUE_SHOW";
    private static final String ACTION_DISMISS = "com.polaris.app.action.RESCUE_DISMISS";
    private static final String EXTRA_PACKAGE = "pkg";
    private static final long REATTACH_INTERVAL_MS = 1200L;

    private WindowManager wm;
    private View overlayView;
    private String targetPkg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attached = false;

    private final Runnable reattachTask = new Runnable() {
        @Override
        public void run() {
            try {
                attachOverlay();
            } catch (Exception e) {
                Log.w(TAG, "reattach failed: " + e.getMessage());
            } finally {
                handler.postDelayed(this, REATTACH_INTERVAL_MS);
            }
        }
    };

    public static void show(Context context, String targetPkg) {
        Intent i = new Intent(context, RescueOverlayService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_PACKAGE, targetPkg);
        context.startService(i);
    }

    public static void dismiss(Context context) {
        context.startService(new Intent(context, RescueOverlayService.class)
                .setAction(ACTION_DISMISS));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_DISMISS.equals(intent.getAction())) {
            teardown();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(intent.getAction())) {
            // 无悬浮窗权限则无法弹出覆盖层
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "no overlay permission, cannot show rescue overlay");
                stopSelf();
                return START_NOT_STICKY;
            }
            targetPkg = intent.getStringExtra(EXTRA_PACKAGE);
            if (targetPkg == null || targetPkg.isEmpty()) {
                targetPkg = resolveForegroundPackage();
            }
            attachOverlay();
            handler.removeCallbacks(reattachTask);
            handler.postDelayed(reattachTask, REATTACH_INTERVAL_MS);
        }
        return START_NOT_STICKY;
    }

    /** 以最高优先级挂载救援覆盖层；若已挂载则提升层级，若被移除则重新挂载。 */
    private void attachOverlay() {
        if (wm == null) return;
        if (overlayView == null) {
            overlayView = LayoutInflater.from(this)
                    .inflate(R.layout.overlay_rescue, null);
            Button clear = overlayView.findViewById(R.id.rescueClear);
            Button allow = overlayView.findViewById(R.id.rescueAllow);
            clear.setOnClickListener(v -> doClear());
            allow.setOnClickListener(v -> doAllow());

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            try {
                wm.addView(overlayView, lp);
                attached = true;
            } catch (Exception e) {
                Log.w(TAG, "addView failed: " + e.getMessage());
                attached = false;
            }
        } else {
            // 已存在：重新 add 以把它顶到最上层（对抗后续被覆盖）
            if (attached) {
                try {
                    wm.removeView(overlayView);
                } catch (Exception ignored) {
                }
                attached = false;
            }
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                wm.addView(overlayView, lp);
                attached = true;
            } catch (Exception e) {
                Log.w(TAG, "re-addView failed: " + e.getMessage());
                attached = false;
            }
        }
    }

    /** 清除：强制停止霸屏应用。优先 Shizuku / Root，否则用无障碍 API 按掉霸屏。 */
    private void doClear() {
        String pkg = targetPkg;
        boolean stopped = false;
        if (pkg == null) pkg = resolveForegroundPackage();

        if (pkg != null) {
            if (ShizukuHelper.hasShizukuPermission()) {
                stopped = ShizukuHelper.runShell("am", "force-stop", pkg) != null;
            }
            if (!stopped && RootChecker.isRootAvailable()) {
                stopped = RootChecker.runAsRoot("am force-stop " + pkg);
            }
            if (!stopped) {
                // 无特权：优先用无障碍 API 按 HOME 退出霸屏（普通模式也可用）
                boolean escaped = PolarisAccessibilityService.pressHome()
                        || PolarisAccessibilityService.pressBack();
                if (!escaped) {
                    // 无障碍也不可用：引导用户手动处置
                    try {
                        Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + pkg));
                        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(detail);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (stopped) {
                Prefs prefs = new Prefs(this);
                prefs.addBlockedApp(pkg);
                BehaviorLog.log(this, BehaviorLog.TYPE_FORCE_STOP, pkg,
                        getString(R.string.rescue_log_cleared, pkg));
                Toast.makeText(this, R.string.rescue_cleared, Toast.LENGTH_SHORT).show();
            } else {
                BehaviorLog.log(this, BehaviorLog.TYPE_RESCUE, pkg,
                        getString(R.string.rescue_log_escaped, pkg));
            }
        }
        teardown();
    }

    /** 放行：将霸屏应用加入放行（从恶意名单移除），并撤下覆盖层。 */
    private void doAllow() {
        String pkg = targetPkg;
        if (pkg != null) {
            Prefs prefs = new Prefs(this);
            Set<String> malicious = prefs.getMaliciousPackages();
            malicious.remove(pkg);
            prefs.setMaliciousPackages(malicious);
        }
        Toast.makeText(this, R.string.rescue_allowed, Toast.LENGTH_SHORT).show();
        teardown();
    }

    private void teardown() {
        handler.removeCallbacks(reattachTask);
        if (wm != null && overlayView != null && attached) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        overlayView = null;
        attached = false;
        stopSelf();
    }

    /** 尽力解析当前前台应用：优先无障碍服务记录，其次 UsageStats。 */
    private String resolveForegroundPackage() {
        Prefs prefs = new Prefs(this);
        String pkg = prefs.getLastForegroundPkg();
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) {
            return pkg;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents.Event lastFg = null;
            try {
                android.app.usage.UsageEvents events =
                        usm.queryEvents(now - 60_000L, now);
                while (events.hasNextEvent()) {
                    android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                    events.getNextEvent(e);
                    if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastFg = e;
                    }
                }
            } catch (Exception ignored) {
            }
            if (lastFg != null && lastFg.getPackageName() != null
                    && !lastFg.getPackageName().equals(getPackageName())) {
                return lastFg.getPackageName();
            }
        }
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(reattachTask);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
