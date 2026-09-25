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
 * 每日定期扫描（应用级）。
 * 扫描深度跟随当前守护模式：Normal 为浅层；Accessibility / Shizuku / Root 为深层
 * （额外检测非系统应用的无障碍服务滥用与设备管理员滥用）。
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
            Prefs prefs = new Prefs(getApplicationContext());
            boolean deep = prefs.isDeepScanMode();
            List<AppRiskInfo> risks =
                    new MalwareScanner(getApplicationContext(), deep).scan();

            Set<String> malicious = new HashSet<>();
            int safe = 0, suspicious = 0, malCount = 0;
            for (AppRiskInfo r : risks) {
                if (r.level == AppRiskInfo.LEVEL_SAFE) safe++;
                else if (r.level == AppRiskInfo.LEVEL_LOW) suspicious++;
                if (r.level >= AppRiskInfo.LEVEL_MEDIUM) {
                    malicious.add(r.packageName);
                    malCount++;
                }
            }
            prefs.setMaliciousPackages(malicious);
            prefs.setLastScanMs(System.currentTimeMillis());
            prefs.incrementScanCount();
            prefs.saveLastScanSummary(risks.size(), safe, suspicious, malCount);

            Notifier.notifyRisks(getApplicationContext(), risks);
            Log.i(TAG, "Periodic scan done, suspicious=" + malicious.size());
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Periodic scan failed: " + e.getMessage(), e);
            return Result.retry();
        }
    }
}
