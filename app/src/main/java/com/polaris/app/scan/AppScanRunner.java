package com.polaris.app.scan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.polaris.app.MainActivity;
import com.polaris.app.R;
import com.polaris.app.ResultActivity;
import com.polaris.app.util.Prefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * 应用级扫描的统一编排入口（三处调用方共用）：
 *
 * 1. 主页向导     MainActivity.startAppScan —— 带星图进度回调，扫描后进入两段结果页；
 * 2. 每日后台     ScanWorker                —— 同步等待，聚合后发通知；
 * 3. 设置页立即扫 SettingsActivity / SettingsDetectionActivity —— 按钮态 + 跳结果页。
 *
 * 抽取动机：三处的「扫描执行 + 结果聚合」（恶意名单 / 摘要 / 计数 / 时间戳）原是三份
 * 逐字重复的代码，行为修正容易漏改其中一处（FileScanner 不尊重 ML 开关的 bug 即源于此）。
 *
 * 语义约定：
 * - 扫描深度由调用方显式传入（三处语义一致：跟随守护模式 prefs.isDeepScanMode()）；
 * - 恶意名单是否更新由调用方决定：向导与后台扫描更新（GuardService 拦截依据），
 *   设置页快速扫描只刷新摘要，不写名单。
 */
public final class AppScanRunner {

    /** 扫描完成回调（后台线程，调用方自行切 UI 线程）。 */
    public interface Done {
        void onDone(List<AppRiskInfo> risks);
    }

    private AppScanRunner() {
    }

    /**
     * 异步执行应用级扫描。raw 非空时透传进度并在扫描线程回调 onResult（与原
     * MalwareScanner.scanAsync 语义一致，主页向导在此收尾）；done 供无 raw 的
     * 调用方（ScanWorker 同步版 / 设置页）承接结果，避免两个出口。
     */
    public static void scanApps(Context ctx, boolean deep,
                                MalwareScanner.ScanCallback raw, Done done) {
        new Thread(() -> {
            List<AppRiskInfo> risks = new MalwareScanner(ctx, deep).scan(raw);
            if (raw != null) raw.onResult(risks);
            if (done != null) done.onDone(risks);
        }, "polaris-app-scan").start();
    }

    /** 同步版：后台 Worker（ScanWorker）使用，阻塞至扫描完成。 */
    public static List<AppRiskInfo> scanAppsSync(Context ctx, boolean deep,
                                                 MalwareScanner.ScanCallback raw) {
        final List<AppRiskInfo>[] out = new List[1];
        CountDownLatch latch = new CountDownLatch(1);
        scanApps(ctx, deep, raw, risks -> {
            out[0] = risks;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0] != null ? out[0] : new ArrayList<>();
    }

    /**
     * 结果聚合（三处共用，替代原先三份重复循环）：
     * 恶意名单（updateMaliciousList=true 时写入，GuardService 拦截依据）、
     * 扫描摘要、扫描计数、最后扫描时间。
     *
     * @return 中/高风险条数
     */
    public static int persist(Prefs prefs, List<AppRiskInfo> risks, boolean updateMaliciousList) {
        Set<String> malicious = new HashSet<>();
        int safe = 0, suspicious = 0, malCount = 0;
        if (risks != null) {
            for (AppRiskInfo r : risks) {
                if (r.level == AppRiskInfo.LEVEL_SAFE) safe++;
                else if (r.level == AppRiskInfo.LEVEL_LOW) suspicious++;
                if (r.level >= AppRiskInfo.LEVEL_MEDIUM) {
                    malicious.add(r.packageName);
                    malCount++;
                }
            }
        }
        if (updateMaliciousList) prefs.setMaliciousPackages(malicious);
        prefs.saveLastScanSummary(risks != null ? risks.size() : 0, safe, suspicious, malCount);
        prefs.setLastScanMs(System.currentTimeMillis());
        prefs.incrementScanCount();
        return malCount;
    }

    /**
     * 设置页「立即扫描」整链（原 SettingsActivity / SettingsDetectionActivity 两份
     * 逐字重复的 startMlScan 合并）：ML 开关检查 → 按钮态 → 扫描 → 按 ML 分数排序 →
     * 写入 ResultActivity 缓存 → 聚合持久化（不更新恶意名单）→ 跳结果页。
     */
    public static void launchQuickScan(Activity act, Prefs prefs, MaterialButton btn) {
        if (!prefs.isMlEnabled()) {
            Toast.makeText(act, R.string.ml_engine_title, Toast.LENGTH_SHORT).show();
            return;
        }
        btn.setEnabled(false);
        btn.setText(R.string.ml_scanning);
        scanApps(act, prefs.isDeepScanMode(), null, risks -> act.runOnUiThread(() -> {
            btn.setEnabled(true);
            btn.setText(R.string.ml_scan_now);

            List<AppRiskInfo> result = (risks != null) ? risks : new ArrayList<>();
            // 按 ML 分数降序（mlScore=-1 的排最后）
            result.sort((a, b) -> Integer.compare(b.mlScore, a.mlScore));

            // 存入缓存供 ResultActivity 读取
            MainActivity.lastRisks = result;
            MainActivity.aiVerdicts = null;

            persist(prefs, result, false);

            Intent i = new Intent(act, ResultActivity.class);
            i.putExtra("deep", false);
            act.startActivity(i);
        }));
    }
}
