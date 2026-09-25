package com.polaris.app.scan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.polaris.app.util.Prefs;

import java.util.concurrent.TimeUnit;

/**
 * 病毒库 (Polar Region IOC) 定时刷新 Worker。
 * <p>
 * 仅刷新 Polar Region 全量库；Polar Point 为内置种子（随 APK 打包、离线可用），不在此处自动更新。
 * - 每日执行一次（需网络、非低电量）
 * - 尊重用户设置 {@link Prefs#getVirusAutoUpdate()}
 * - 失败按指数退避重试
 */
public class IocRefreshWorker extends Worker {

    private static final String TAG = "IocRefreshWorker";
    public static final String WORK_NAME = "polaris_ioc_daily_refresh";

    public IocRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Prefs prefs = new Prefs(getApplicationContext());
        if (!prefs.getVirusAutoUpdate()) {
            Log.i(TAG, "Auto-update disabled by user");
            return Result.success();
        }

        Log.i(TAG, "Starting IOC database refresh...");

        try {
            // 同步等待异步刷新完成（最长 60 秒）
            final Object lock = new Object();
            final Result[] resultHolder = {Result.retry()};

            IocDatabase.getInstance(getApplicationContext(), "region").refresh(new IocDatabase.RefreshCallback() {
                @Override
                public void onUpdated(int newVersion) {
                    Log.i(TAG, "IOC database updated to version " + newVersion);
                    synchronized (lock) {
                        resultHolder[0] = Result.success();
                        lock.notifyAll();
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "IOC refresh failed: " + message);
                    synchronized (lock) {
                        resultHolder[0] = Result.retry();
                        lock.notifyAll();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(60_000); // 等待回调，最多 60 秒
            }

            return resultHolder[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during IOC refresh", e);
            return Result.retry();
        }
    }

    /** 在 Application 启动时调用，注册每日周期性任务。 */
    public static void schedule(Context context) {
        Prefs prefs = new Prefs(context);
        if (!prefs.getVirusAutoUpdate()) {
            Log.i(TAG, "Auto-update disabled, skipping schedule");
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(IocRefreshWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
        Log.i(TAG, "IOC daily refresh scheduled");
    }

    /** 取消周期性任务（用户关闭自动更新时调用）。 */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Log.i(TAG, "IOC daily refresh cancelled");
    }

    /** 立即触发一次性刷新（用户点击「立即更新」时调用）。 */
    public static void triggerNow(Context context) {
        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(IocRefreshWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueue(request);
        Log.i(TAG, "IOC immediate refresh triggered");
    }
}
