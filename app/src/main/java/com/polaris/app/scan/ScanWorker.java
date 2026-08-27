package com.polaris.app.scan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.polaris.app.util.Prefs;
import com.polaris.app.util.TextUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Normal 模式：每日定期浅层扫描。
 * 发现中/高风险应用时发出通知提醒，并更新恶意应用列表。
 */
public class ScanWorker extends Worker {

    private static final String TAG = "PolarisScanWorker";

    public ScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            List<AppRiskInfo> risks = new MalwareScanner(getApplicationContext(), false).scan();

            Prefs prefs = new Prefs(getApplicationContext());
            Set<String> malicious = new HashSet<>();
            for (AppRiskInfo r : risks) {
                if (r.level >= AppRiskInfo.LEVEL_MEDIUM) malicious.add(r.packageName);
            }
            prefs.setMaliciousPackages(malicious);
            prefs.setLastScanMs(System.currentTimeMillis());

            Notifier.notifyRisks(getApplicationContext(), risks);
            Log.i(TAG, "Periodic scan done, suspicious=" + malicious.size());
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Periodic scan failed: " + e.getMessage(), e);
            return Result.retry();
        }
    }
}
