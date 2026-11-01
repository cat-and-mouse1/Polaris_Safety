package com.polaris.app;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.AppScanRunner;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.scan.Notifier;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 开发者工具子页：从「设置 → 开发者模式」分区进入，集中提供单功能测试入口——
 * 结果页示例、动画预览、星图种子、病毒库调试、日志与诊断、重置与清理。
 */
public class DevToolsActivity extends BaseActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dev_tools);

        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 快速测试
        findViewById(R.id.devCrashButton).setOnClickListener(v -> {
            // 主动抛未捕获异常，验证崩溃捕获器（写日志 + Toast）
            throw new RuntimeException("Dev crash test @ DevToolsActivity");
        });
        findViewById(R.id.devNotifyButton).setOnClickListener(v ->
                Notifier.notifyDevTest(this));
        // launchQuickScan 需要一个 MaterialButton 作进度反馈载体；用未加入布局的临时按钮仅满足 API
        findViewById(R.id.devScanButton).setOnClickListener(v ->
                AppScanRunner.launchQuickScan(this, prefs,
                        new com.google.android.material.button.MaterialButton(this)));

        // 界面与动画
        findViewById(R.id.devOpenResult).setOnClickListener(v -> openResultSample());
        findViewById(R.id.devAnimPreview).setOnClickListener(v ->
                startActivity(new Intent(this, AnimationPreviewActivity.class)));
        findViewById(R.id.devStarSeed).setOnClickListener(v -> showStarSeedDialog());

        // 病毒库调试
        findViewById(R.id.devClearRegion).setOnClickListener(v -> {
            IocDatabase.clearLocalCache(getApplicationContext(), "region");
            Toast.makeText(this, R.string.dev_virusdb_cleared, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.devClearPoint).setOnClickListener(v -> {
            IocDatabase.clearLocalCache(getApplicationContext(), "point");
            Toast.makeText(this, R.string.dev_virusdb_cleared, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.devRedownload).setOnClickListener(v -> forceRedownload());
        findViewById(R.id.devVirusStatus).setOnClickListener(v -> showVirusStatus());

        // 日志与诊断
        findViewById(R.id.devViewCrash).setOnClickListener(v -> showCrashLog());
        findViewById(R.id.devWriteLog).setOnClickListener(v -> {
            BehaviorLog.log(getApplicationContext(), "DEV_TEST",
                    "developer_tools", "开发者模式：测试行为日志");
            Toast.makeText(this, R.string.dev_log_written, Toast.LENGTH_SHORT).show();
        });

        // 重置与清理
        findViewById(R.id.devResetSettings).setOnClickListener(v -> confirmReset());
        findViewById(R.id.devClearScanCache).setOnClickListener(v -> {
            MainActivity.lastRisks = null;
            MainActivity.aiVerdicts = null;
            prefs.saveLastScanSummary(0, 0, 0, 0);
            Toast.makeText(this, R.string.dev_scan_cache_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    // ---------- 结果页示例 ----------

    /** 注入一组示例风险条目并打开扫描结果页，验证布局与处置入口。 */
    private void openResultSample() {
        final Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_shield);
        List<AppRiskInfo> sample = new ArrayList<>();
        sample.add(makeRisk("com.example.risk.high", "示例·高危应用", 92,
                Arrays.asList("命中高危 IOC", "申请危险权限"), icon));
        sample.add(makeRisk("com.example.risk.medium", "示例·中危应用", 58,
                Arrays.asList("隐私行为异常", "后台频繁联网"), icon));
        sample.add(makeRisk("com.example.risk.low", "示例·低危应用", 28,
                Arrays.asList("含广告 SDK"), icon));
        sample.add(makeRisk("com.example.safe", "示例·安全应用", 5,
                Arrays.asList("未检测到风险"), icon));
        MainActivity.lastRisks = sample;
        MainActivity.aiVerdicts = null;
        Intent i = new Intent(this, ResultActivity.class);
        i.putExtra("deep", false);
        i.putExtra("ai", false);
        startActivity(i);
    }

    private AppRiskInfo makeRisk(String pkg, String name, int score,
                                List<String> reasons, Drawable icon) {
        AppRiskInfo r = new AppRiskInfo(pkg);
        r.appName = name;
        r.score = score;
        r.level = AppRiskInfo.levelOf(score);
        r.reasons = new ArrayList<>(reasons);
        r.icon = icon;
        r.isSystem = false;
        r.mlScore = score;
        return r;
    }

    // ---------- 星图种子 ----------

    private void showStarSeedDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_star_seed, null);
        final EditText input = view.findViewById(R.id.seedInput);
        TextView current = view.findViewById(R.id.seedCurrent);
        current.setText(getString(R.string.dev_star_seed_default));
        if (prefs.hasCustomStarSeed()) {
            current.setText("当前：0x" + Long.toHexString(prefs.getStarMapSeed()).toUpperCase());
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dev_star_seed_title)
                .setView(view)
                .setPositiveButton(R.string.dev_star_seed_apply, (d, w) -> {
                    Long seed = parseSeed(input.getText().toString());
                    if (seed == null) {
                        Toast.makeText(this, R.string.dev_star_seed_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.setStarMapSeed(seed);
                    Toast.makeText(this, R.string.dev_star_seed_toast, Toast.LENGTH_LONG).show();
                })
                .setNeutralButton(R.string.dev_star_seed_reset, (d, w) -> {
                    prefs.setStarMapSeed(0x501A5EEDL);
                    Toast.makeText(this, R.string.dev_star_seed_toast, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Long parseSeed(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            if (s.startsWith("#")) {
                return Long.parseLong(s.substring(1), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 病毒库调试 ----------

    private void forceRedownload() {
        Toast.makeText(this, R.string.dev_virusdb_redownload_started, Toast.LENGTH_SHORT).show();
        for (String type : new String[]{"region", "point"}) {
            final String t = type;
            new Thread(() -> {
                try {
                    IocDatabase.getInstance(getApplicationContext(), t)
                            .refresh(new IocDatabase.RefreshCallback() {
                                @Override
                                public void onUpdated(int version) {
                                    runOnUiThread(() -> Toast.makeText(DevToolsActivity.this,
                                            getString(R.string.dev_virusdb_redownload_done, version),
                                            Toast.LENGTH_SHORT).show());
                                }

                                @Override
                                public void onUpToDate(int version) {
                                    runOnUiThread(() -> Toast.makeText(DevToolsActivity.this,
                                            getString(R.string.dev_virusdb_redownload_done, version),
                                            Toast.LENGTH_SHORT).show());
                                }

                                @Override
                                public void onError(String message) {
                                    runOnUiThread(() -> Toast.makeText(DevToolsActivity.this,
                                            getString(R.string.dev_virusdb_redownload_error, message),
                                            Toast.LENGTH_SHORT).show());
                                }
                            });
                } catch (Throwable ex) {
                    runOnUiThread(() -> Toast.makeText(DevToolsActivity.this,
                            getString(R.string.dev_virusdb_redownload_error, ex.getMessage()),
                            Toast.LENGTH_SHORT).show());
                }
            }, "polaris-dev-redownload-" + t).start();
        }
    }

    private void showVirusStatus() {
        int vr = IocDatabase.peekDbVersion(getApplicationContext(), "region");
        int vp = IocDatabase.peekDbVersion(getApplicationContext(), "point");
        String msg = getString(R.string.virusdb_region_chip, "v" + (vr >= 0 ? vr : "?"))
                + "\n" + getString(R.string.virusdb_point_chip, "v" + (vp >= 0 ? vp : "?"))
                + "\n\n" + getString(R.string.dev_virusdb_status_desc);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dev_virusdb_status)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------- 日志与诊断 ----------

    private void showCrashLog() {
        File log = new File(getCacheDir(), "polaris_crash.log");
        StringBuilder sb = new StringBuilder();
        if (!log.exists()) {
            sb.append(getString(R.string.dev_crash_log_empty));
        } else {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(log), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception e) {
                sb.append("读取失败：").append(e.getMessage());
            }
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        tv.setPadding(48, 24, 48, 24);
        tv.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dev_view_crash_log)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------- 重置与清理 ----------

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dev_reset_confirm_title)
                .setMessage(R.string.dev_reset_confirm_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.clearAllSettings();
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
