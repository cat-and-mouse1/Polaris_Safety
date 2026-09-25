package com.polaris.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.polaris.app.R;
import com.polaris.app.scan.Notifier;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前台拦截守护服务（Accessibility / Shizuku / Root 模式）。
 *
 * 以常驻前台服务轮询当前前台应用，命中恶意列表时立即强制停止，
 * Root 模式下额外执行冻结（pm disable-user），实现"提前拦截恶意程序的运行"。
 * 前台识别优先级：Shizuku/Root dumpsys → 使用情况访问（UsageStats）→ 无障碍记录。
 * 同时周期性刷新拦截模式常驻通知，保证后台守护期间通知内容与权限状态同步。
 */
public class GuardService extends Service {

    private static final String TAG = "PolarisGuard";
    private static final String CHANNEL_ID = "guard";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START = "com.polaris.app.action.GUARD_START";
    public static final String ACTION_STOP = "com.polaris.app.action.GUARD_STOP";
    public static final String EXTRA_MODE = "guard_mode";

    private static final long POLL_INTERVAL_MS = 2000L;
    /** 每 15 次轮询（约 30 秒）刷新一次拦截常驻通知。 */
    private static final int NOTIFY_REFRESH_POLLS = 15;
    private static final Pattern FOCUS_PATTERN =
            Pattern.compile("mCurrentFocus=.*?u\\d+ ([^/\\s}]+)/");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pollCount = 0;
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            try {
                pollAndBlock();
                pollCount++;
                if (pollCount % NOTIFY_REFRESH_POLLS == 0) {
                    // 后台守护期间保持拦截常驻通知与权限状态同步
                    Notifier.updateBlockNotification(GuardService.this);
                }
            } catch (Exception e) {
                Log.w(TAG, "poll failed: " + e.getMessage());
            } finally {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private int mode = Prefs.MODE_NONE;
    private Set<String> malicious;
    private Prefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra(EXTRA_MODE)) {
            mode = intent.getIntExtra(EXTRA_MODE, Prefs.MODE_NONE);
        }
        malicious = prefs.getMaliciousPackages();

        Notification notification = buildNotification();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        // 服务（重新）启动时恢复拦截模式常驻通知，后台守护不中断
        Notifier.updateBlockNotification(this);

        handler.removeCallbacks(poller);
        handler.post(poller);
        Log.i(TAG, "Guard started, mode=" + mode);
        return START_STICKY;
    }

    private void pollAndBlock() {
        // 每次轮询刷新恶意列表（后台扫描后可能更新）
        malicious = prefs.getMaliciousPackages();
        if (mode == Prefs.MODE_NONE || malicious == null || malicious.isEmpty()) return;

        String foreground = getForegroundPackage();
        if (foreground == null || foreground.equals(getPackageName())) return;

        // 系统/自身应用直接放行
        if (isSystemApp(foreground)) return;

        if (malicious.contains(foreground)) {
            Log.w(TAG, "Blocking malicious app in foreground: " + foreground);
            boolean ok;
            if (mode == Prefs.MODE_SHIZUKU) {
                ok = ShizukuHelper.runShell("am", "force-stop", foreground) != null;
            } else if (mode == Prefs.MODE_ROOT) {
                ok = RootChecker.runAsRoot("am force-stop " + foreground);
                // 全方位守护：冻结一次，防止自启复活
                if (ok) {
                    RootChecker.runAsRoot("pm disable-user --user 0 " + foreground);
                    BehaviorLog.log(this, BehaviorLog.TYPE_FREEZE, foreground,
                            getString(R.string.behavior_detail_freeze, foreground));
                }
            } else if (mode == Prefs.MODE_ACCESSIBILITY) {
                // 无特权模式：无法 force-stop，改为记录 + 拉起救援覆盖层按掉霸屏
                if (prefs.isBlockMode()) {
                    prefs.addBlockedApp(foreground);
                    BehaviorLog.log(this, BehaviorLog.TYPE_BLOCK, appLabel(foreground),
                            getString(R.string.behavior_detail_block, foreground));
                    if (android.provider.Settings.canDrawOverlays(this)) {
                        RescueOverlayService.show(this, foreground);
                    }
                }
                return;
            } else {
                return;
            }
            if (ok) {
                notifyBlocked(foreground);
                BehaviorLog.log(this, BehaviorLog.TYPE_FORCE_STOP, appLabel(foreground),
                        getString(R.string.behavior_detail_force_stop, foreground));
            }
        }
    }

    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** 使用情况访问（PACKAGE_USAGE_STATS）解析最近 15 秒内最后进入前台的应用。 */
    private String getForegroundByUsageStats() {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 15_000L, now);
            if (events == null) return null;
            String lastPkg = null;
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        && e.getPackageName() != null) {
                    lastPkg = e.getPackageName();
                }
            }
            return lastPkg;
        } catch (Exception ex) {
            return null;
        }
    }

    private String getForegroundPackage() {
        try {
            String dump = null;
            if (mode == Prefs.MODE_SHIZUKU) {
                dump = ShizukuHelper.runShell("sh", "-c", "dumpsys window | grep mCurrentFocus");
            } else if (mode == Prefs.MODE_ROOT) {
                dump = RootChecker.runAsRootOutput("dumpsys window | grep mCurrentFocus");
            }
            if (dump != null && !dump.isEmpty()) {
                Matcher m = FOCUS_PATTERN.matcher(dump);
                if (m.find()) return m.group(1);
            }
            // 兜底 1：使用情况访问（需用户授权「使用情况访问权限」）
            String byUsage = getForegroundByUsageStats();
            if (byUsage != null) return byUsage;
            // 兜底 2：无障碍服务记录的最后前台应用
            if (mode == Prefs.MODE_ACCESSIBILITY) {
                return prefs.getLastForegroundPkg();
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "getForegroundPackage failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isSystemApp(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_guard),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        String title = getString(R.string.app_name) + " · " + getString(R.string.app_subtitle);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(getString(R.string.toast_guard_started))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void notifyBlocked(String pkg) {
        // 拦截模式开启时，将被拦截应用记入「拦截」列表（扫描中心集中管理）
        if (prefs.isBlockMode()) prefs.addBlockedApp(pkg);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.notification_blocked_title))
                .setContentText(getString(R.string.notification_blocked_text, pkg))
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID + 1, n);
    }

    public static void start(Context context, int mode) {
        Intent i = new Intent(context, GuardService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, GuardService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poller);
        super.onDestroy();
    }
}
