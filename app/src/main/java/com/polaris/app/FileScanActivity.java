package com.polaris.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.polaris.app.R;
import com.polaris.app.MainActivity;
import com.polaris.app.scan.FileRiskInfo;
import com.polaris.app.scan.FileScanner;
import com.polaris.app.scan.Notifier;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.AiProvider;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.PermUtil;
import com.polaris.app.util.QuarantineManager;
import com.polaris.app.util.TextUtil;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 扫描中心：进入任一守护模式（Normal / AI 判定）后落地的文件扫描页面。
 *
 * - 「选择文件夹扫描」：SAF 授权文件夹，无需额外权限；
 * - 「全局扫描」：全盘遍历（需「所有文件访问」权限）；
 * - 扫描结果逐项可「隔离」（移入应用私有隔离区）或「放行」（加入白名单）；
 * - 「隔离区」页签：已隔离文件可「恢复」或「放行」；放行名单可「取消放行」。
 */
public class FileScanActivity extends BaseActivity {

    private static final int REQ_OPEN_TREE = 100;
    private static final int REQ_AI_SETUP = 101;
    private static final int REQ_STORAGE = 102;

    private Prefs prefs;
    private QuarantineManager qm;
    private FileScanner scanner;

    private TextView modeEngineText;
    private TextView tabScan, tabQuarantine;
    private View scanPanel, quarantinePanel;
    private View folderScanCard, globalScanCard;
    private TextView permHint;
    private View progressSection;
    private View aiThinkSection;
    private TextView aiThinkText;
    private TextView scanStatusText, scanProgressPath;
    private View resultHeader;
    private TextView resultCountText;
    private View resultEmpty;
    private LinearLayout resultList, quarantineList, allowList;
    private TextView quarantineCountText, allowCountText;
    private View quarantineEmpty, allowEmpty;
    private TextView tabBlock;
    private View blockPanel;
    private MaterialButton blockToggleButton;
    private TextView blockStateText;
    private View blockNotif;
    private LinearLayout blockList;
    private TextView blockCountText;
    private View blockEmpty;
    private LinearLayout permList;
    private View appMonitorCard;

    // 行为板块（蓝色主题，记录防护过程）
    private TextView tabBehavior;
    private View behaviorPanel;
    private LinearLayout behaviorList;
    private TextView behaviorCountText;
    private View behaviorEmpty;
    private View behaviorClearButton;

    private BroadcastReceiver blockStateReceiver;
    private static final int REQ_NOTIFICATION = 103;

    private int modeKey;
    private boolean useAi;
    private boolean blockEnabled;      // 拦截板块仅 Accessibility 及以上模式可用
    private Uri currentTreeUri;
    private String pendingScanAction;   // folder | global
    private Uri pendingTreeUri;

    private static final int TAB_SCAN = 0;
    private static final int TAB_BLOCK = 1;
    private static final int TAB_QUARANTINE = 2;
    private static final int TAB_BEHAVIOR = 3;

    private static final int[] MODE_NAME_RES = {
            R.string.mode_normal_name, R.string.mode_accessibility_name,
            R.string.mode_shizuku_name, R.string.mode_root_name
    };
    private static final int[] MODE_KEYS = {
            Prefs.MODE_NORMAL, Prefs.MODE_ACCESSIBILITY,
            Prefs.MODE_SHIZUKU, Prefs.MODE_ROOT
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_file_scan);

        prefs = new Prefs(this);
        qm = new QuarantineManager(this);
        scanner = new FileScanner(this);

        modeKey = getIntent().getIntExtra("mode", Prefs.MODE_NORMAL);
        useAi = Prefs.ENGINE_AI.equals(getIntent().getStringExtra("engine"));
        // 拦截板块仅 Accessibility / Shizuku / Root 模式可用，Normal 模式隐藏
        blockEnabled = modeKey == Prefs.MODE_ACCESSIBILITY
                || modeKey == Prefs.MODE_SHIZUKU
                || modeKey == Prefs.MODE_ROOT;

        bindViews();
        setupHeader();
        setupTabs();
        setupScanEntries();
        setupBlock();
        refreshQuarantine();

        blockStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshBlockStateFromNotification();
            }
        };
        // Android 14+ 要求显式声明 receiver 的导出属性，否则抛 SecurityException 崩溃
        ContextCompat.registerReceiver(this, blockStateReceiver,
                new IntentFilter(BlockOffReceiver.ACTION_BLOCK_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void bindViews() {
        modeEngineText = findViewById(R.id.modeEngineText);
        tabScan = findViewById(R.id.tabScan);
        tabQuarantine = findViewById(R.id.tabQuarantine);
        scanPanel = findViewById(R.id.scanPanel);
        quarantinePanel = findViewById(R.id.quarantinePanel);
        folderScanCard = findViewById(R.id.folderScanCard);
        globalScanCard = findViewById(R.id.globalScanCard);
        permHint = findViewById(R.id.permHint);
        progressSection = findViewById(R.id.progressSection);
        aiThinkSection = findViewById(R.id.aiThinkSection);
        aiThinkText = findViewById(R.id.aiThinkText);
        scanStatusText = findViewById(R.id.scanStatusText);
        scanProgressPath = findViewById(R.id.scanProgressPath);
        resultHeader = findViewById(R.id.resultHeader);
        resultCountText = findViewById(R.id.resultCountText);
        resultEmpty = findViewById(R.id.resultEmpty);
        resultList = findViewById(R.id.resultList);
        quarantineList = findViewById(R.id.quarantineList);
        allowList = findViewById(R.id.allowList);
        quarantineCountText = findViewById(R.id.quarantineCountText);
        allowCountText = findViewById(R.id.allowCountText);
        quarantineEmpty = findViewById(R.id.quarantineEmpty);
        allowEmpty = findViewById(R.id.allowEmpty);
        tabBlock = findViewById(R.id.tabBlock);
        blockPanel = findViewById(R.id.blockPanel);
        blockToggleButton = findViewById(R.id.blockToggleButton);
        blockStateText = findViewById(R.id.blockStateText);
        blockNotif = findViewById(R.id.blockNotif);
        blockList = findViewById(R.id.blockList);
        blockCountText = findViewById(R.id.blockCountText);
        blockEmpty = findViewById(R.id.blockEmpty);
        permList = findViewById(R.id.permList);
        appMonitorCard = findViewById(R.id.appMonitorCard);
        tabBehavior = findViewById(R.id.tabBehavior);
        behaviorPanel = findViewById(R.id.behaviorPanel);
        behaviorList = findViewById(R.id.behaviorList);
        behaviorCountText = findViewById(R.id.behaviorCountText);
        behaviorEmpty = findViewById(R.id.behaviorEmpty);
        behaviorClearButton = findViewById(R.id.behaviorClear);
        if (behaviorClearButton != null) {
            behaviorClearButton.setOnClickListener(v ->
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.behavior_clear_title)
                            .setMessage(R.string.behavior_clear_confirm)
                            .setPositiveButton(R.string.behavior_clear_yes, (d, w) -> {
                                prefs.clearBehaviorLog();
                                renderBehavior();
                            })
                            .setNegativeButton(R.string.update_cancel, null)
                            .show());
        }
    }

    private void setupHeader() {
        // 「重新配置」：回到主界面重新选择守护模式（reconfigure 标记阻止主界面自动跳回）
        findViewById(R.id.btnReconfigure).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra("reconfigure", true));
            finish();
        });
        findViewById(R.id.btnManagement).setOnClickListener(v ->
                startActivity(new Intent(this, ManagementActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        String modeName = "";
        for (int i = 0; i < MODE_KEYS.length; i++) {
            if (MODE_KEYS[i] == modeKey) {
                modeName = getString(MODE_NAME_RES[i]);
                break;
            }
        }
        String engine = useAi ? getString(R.string.ai_choice_ai)
                : getString(R.string.ai_choice_normal);
        modeEngineText.setText(getString(R.string.scan_center_subtitle, modeName, engine));
    }

    private void setupTabs() {
        tabScan.setOnClickListener(v -> switchTab(TAB_SCAN));
        tabBehavior.setOnClickListener(v -> switchTab(TAB_BEHAVIOR));
        tabQuarantine.setOnClickListener(v -> switchTab(TAB_QUARANTINE));
        if (blockEnabled) {
            tabBlock.setOnClickListener(v -> switchTab(TAB_BLOCK));
        } else {
            // Normal 模式：隐藏「拦截」tab，仅保留扫描 / 隔离区 / 行为
            tabBlock.setVisibility(View.GONE);
        }
        // 拦截板块的应用监控入口
        if (appMonitorCard != null) {
            appMonitorCard.setOnClickListener(v ->
                    startActivity(new Intent(this, AppMonitorActivity.class)));
        }
    }

    private void switchTab(int which) {
        // 面板切换：淡入 + 轻微上移动画，替代生硬的直接显隐
        showPanelAnimated(scanPanel, which == TAB_SCAN);
        showPanelAnimated(blockPanel, which == TAB_BLOCK);
        showPanelAnimated(quarantinePanel, which == TAB_QUARANTINE);
        showPanelAnimated(behaviorPanel, which == TAB_BEHAVIOR);

        tabScan.setBackgroundResource(which == TAB_SCAN ? R.drawable.bg_tab_active : 0);
        tabBlock.setBackgroundResource(which == TAB_BLOCK ? R.drawable.bg_tab_active_red : 0);
        tabQuarantine.setBackgroundResource(which == TAB_QUARANTINE ? R.drawable.bg_tab_active : 0);
        tabBehavior.setBackgroundResource(which == TAB_BEHAVIOR ? R.drawable.bg_tab_active : 0);

        tabScan.setTextColor(getColor(which == TAB_SCAN
                ? R.color.md_theme_onPrimaryContainer : R.color.md_theme_onSurfaceVariant));
        tabBlock.setTextColor(getColor(which == TAB_BLOCK
                ? R.color.md_theme_onErrorContainer : R.color.md_theme_onSurfaceVariant));
        tabQuarantine.setTextColor(getColor(which == TAB_QUARANTINE
                ? R.color.md_theme_onPrimaryContainer : R.color.md_theme_onSurfaceVariant));
        tabBehavior.setTextColor(getColor(which == TAB_BEHAVIOR
                ? R.color.md_theme_onPrimaryContainer : R.color.md_theme_onSurfaceVariant));

        if (which == TAB_BLOCK) renderBlock();
        if (which == TAB_QUARANTINE) refreshQuarantine();
        if (which == TAB_BEHAVIOR) renderBehavior();
    }

    /** 面板显隐过渡：目标面板淡入并轻浮上移，其余直接隐藏。 */
    private void showPanelAnimated(View panel, boolean show) {
        if (show) {
            if (panel.getVisibility() != View.VISIBLE) {
                panel.setAlpha(0f);
                panel.setTranslationY(24f);
                panel.setVisibility(View.VISIBLE);
                panel.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(260)
                        .start();
            }
        } else if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
        }
    }

    private void setupScanEntries() {
        folderScanCard.setOnClickListener(v -> startFolderScan());
        globalScanCard.setOnClickListener(v -> startGlobalScan());
    }

    // ---------- 扫描入口 ----------

    private void startFolderScan() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_OPEN_TREE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_scan_no_file_manager, Toast.LENGTH_LONG).show();
        }
    }

    private void startGlobalScan() {
        if (!hasFullStorageAccess()) {
            permHint.setVisibility(View.VISIBLE);
            requestFullStorageAccess();
            return;
        }
        permHint.setVisibility(View.GONE);
        beginScan("global", null);
    }

    private void beginScan(String action, Uri treeUri) {
        pendingScanAction = null;
        pendingTreeUri = null;
        showProgress();
        if ("folder".equals(action)) {
            scanner.scanTree(treeUri, scanCallback);
        } else {
            File root = Environment.getExternalStorageDirectory();
            scanner.scanDirectory(root, scanCallback);
        }
    }

    private final FileScanner.Callback scanCallback = new FileScanner.Callback() {
        @Override
        public void onProgress(String currentPath) {
            runOnUiThread(() -> {
                scanProgressPath.setVisibility(View.VISIBLE);
                scanProgressPath.setText(currentPath);
            });
        }

        @Override
        public void onResult(List<FileRiskInfo> risks) {
            // 保存文件扫描摘要（在 UI 更新之前，确保数据先写入）
            int total = risks.size();
            int safe = 0, suspicious = 0, malicious = 0;
            for (FileRiskInfo r : risks) {
                if (r.level == FileRiskInfo.LEVEL_SAFE) safe++;
                else if (r.level == FileRiskInfo.LEVEL_LOW) suspicious++;
                else if (r.level >= FileRiskInfo.LEVEL_MEDIUM) malicious++;
            }
            prefs.saveLastScanSummary(total, safe, suspicious, malicious);
            prefs.setLastScanMs(System.currentTimeMillis());
            prefs.incrementScanCount();

            runOnUiThread(() -> {
                hideProgress();
                if (useAi) {
                    ensureAiThenShow(risks);
                } else {
                    renderResult(risks);
                }
            });
        }
    };

    /** AI 引擎：未配置先跳转接入页，返回后继续；配置后执行文件级 AI 判定。 */
    private void ensureAiThenShow(List<FileRiskInfo> risks) {
        if (!prefs.isAiConfigured()) {
            Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_LONG).show();
            pendingScanAction = "ai_pending";
            pendingAiRisks = risks;
            startActivityForResult(new Intent(this, AiSetupActivity.class), REQ_AI_SETUP);
            return;
        }
        judgeWithAi(risks);
    }

    private List<FileRiskInfo> pendingAiRisks;

    private void judgeWithAi(List<FileRiskInfo> risks) {
        scanStatusText.setText(R.string.ai_scan_start);
        scanProgressPath.setVisibility(View.GONE);
        progressSection.setVisibility(View.VISIBLE);
        showAiThinking();

        new Thread(() -> {
            try {
                // 优先使用多供应商配置
                List<Prefs.AiProviderConfig> enabledProviders = prefs.getEnabledProviders();
                String strategy = prefs.getMultiModelStrategy();

                if (enabledProviders != null && !enabledProviders.isEmpty()) {
                    // 多供应商扫描
                    List<AiClient.AiVerdict> verdicts =
                            AiClient.judgeFilesMulti(this, enabledProviders, strategy, risks);
                    Map<String, AiClient.AiVerdict> index = AiClient.indexOf(verdicts);
                    for (FileRiskInfo r : risks) {
                        r.verdict = index.get(r.path);
                    }
                    runOnUiThread(() -> {
                        hideProgress();
                        renderResult(risks);
                    });
                } else {
                    // 回退到旧版单供应商配置
                    AiProvider provider = AiProvider.fromId(prefs.getAiProviderId());
                    String apiKey = prefs.getAiApiKey();
                    String model = prefs.getAiModel();
                    if (provider == null) {
                        runOnUiThread(() -> {
                            hideProgress();
                            renderResult(risks);
                        });
                        return;
                    }
                    if (model == null || model.trim().isEmpty()) model = provider.defaultModel;
                    List<AiClient.AiVerdict> verdicts =
                            AiClient.judgeFiles(this, provider, apiKey, model, risks);
                    Map<String, AiClient.AiVerdict> index = AiClient.indexOf(verdicts);
                    for (FileRiskInfo r : risks) {
                        r.verdict = index.get(r.path);
                    }
                    runOnUiThread(() -> {
                        hideProgress();
                        renderResult(risks);
                    });
                }
            } catch (AiClient.AiException e) {
                runOnUiThread(() -> {
                    hideProgress();
                    Toast.makeText(this,
                            getString(R.string.ai_judge_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    renderResult(risks);
                });
            }
        }).start();
    }

    // ---------- 结果渲染 ----------

    private void renderResult(List<FileRiskInfo> risks) {
        resultList.removeAllViews();
        if (risks.isEmpty()) {
            resultHeader.setVisibility(View.VISIBLE);
            resultCountText.setText(getString(R.string.file_result_count, 0));
            resultEmpty.setVisibility(View.VISIBLE);
            return;
        }
        resultHeader.setVisibility(View.VISIBLE);
        resultCountText.setText(getString(R.string.file_result_count, risks.size()));
        resultEmpty.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (FileRiskInfo info : risks) {
            View item = inflater.inflate(R.layout.item_file_risk, resultList, false);
            bindRiskItem(item, info);
            resultList.addView(item);
        }
    }

    private void bindRiskItem(View item, FileRiskInfo info) {
        TextView levelText = item.findViewById(R.id.riskLevelText);
        TextView fileName = item.findViewById(R.id.fileName);
        TextView filePath = item.findViewById(R.id.filePath);
        TextView fileMeta = item.findViewById(R.id.fileMeta);
        TextView fileReasons = item.findViewById(R.id.fileReasons);
        View aiPanel = item.findViewById(R.id.aiVerdictPanel);
        TextView mlBadge = item.findViewById(R.id.mlBadge);

        fileName.setText(info.name);
        filePath.setText(displayPath(info));
        fileMeta.setText(getString(R.string.file_meta,
                humanSize(info.size), info.score));
        fileReasons.setText(joinReasons(info.reasons));

        // ML 检测等级徽标（映射为风险等级文案，与风险等级同色）
        if (info.mlScore >= 0) {
            int mlLevel;
            if (info.mlScore >= 80) mlLevel = FileRiskInfo.LEVEL_HIGH;
            else if (info.mlScore >= 45) mlLevel = FileRiskInfo.LEVEL_MEDIUM;
            else if (info.mlScore >= 20) mlLevel = FileRiskInfo.LEVEL_LOW;
            else mlLevel = FileRiskInfo.LEVEL_SAFE;

            String mlLabel;
            int mlColor;
            switch (mlLevel) {
                case FileRiskInfo.LEVEL_HIGH:
                    mlLabel = getString(R.string.risk_high);
                    mlColor = getColor(R.color.md_theme_error);
                    break;
                case FileRiskInfo.LEVEL_MEDIUM:
                    mlLabel = getString(R.string.risk_medium);
                    mlColor = getColor(R.color.md_theme_tertiary);
                    break;
                case FileRiskInfo.LEVEL_LOW:
                    mlLabel = getString(R.string.risk_low);
                    mlColor = getColor(R.color.md_theme_secondary);
                    break;
                default:
                    mlLabel = getString(R.string.risk_safe);
                    mlColor = getColor(R.color.ai_success);
                    break;
            }

            mlBadge.setVisibility(View.VISIBLE);
            mlBadge.setText("ML: " + mlLabel);
            mlBadge.setTextColor(android.graphics.Color.WHITE);
            mlBadge.setBackgroundTintList(ColorStateList.valueOf(mlColor));
        } else {
            mlBadge.setVisibility(View.GONE);
        }

        switch (info.level) {
            case FileRiskInfo.LEVEL_HIGH:
                levelText.setText(R.string.risk_high);
                levelText.setTextColor(getColor(R.color.md_theme_error));
                break;
            case FileRiskInfo.LEVEL_MEDIUM:
                levelText.setText(R.string.risk_medium);
                levelText.setTextColor(getColor(R.color.md_theme_tertiary));
                break;
            default:
                levelText.setText(R.string.risk_low);
                levelText.setTextColor(getColor(R.color.md_theme_secondary));
                break;
        }

        // AI 判定
        if (useAi && info.verdict != null) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            TextView conf = item.findViewById(R.id.aiConfidenceText);
            TextView reason = item.findViewById(R.id.aiReasonText);
            aiPanel.setVisibility(View.VISIBLE);
            if (info.verdict.remove) {
                chip.setText(R.string.ai_verdict_remove);
                chip.setTextColor(android.graphics.Color.WHITE);
                chip.setBackgroundTintList(ColorStateList.valueOf(
                        getColor(R.color.md_theme_error)));
                conf.setText(getString(R.string.ai_confidence,
                        Math.round(info.verdict.confidence * 100)));
                reason.setText(getString(R.string.ai_reason_prefix, info.verdict.reason));
            } else {
                chip.setText(R.string.ai_verdict_keep);
                chip.setTextColor(getColor(R.color.ai_success));
                chip.setBackgroundTintList(ColorStateList.valueOf(
                        getColor(R.color.md_theme_surfaceContainerHighest)));
                conf.setText(getString(R.string.ai_confidence,
                        Math.round(info.verdict.confidence * 100)));
                reason.setText(getString(R.string.ai_reason_prefix, info.verdict.reason));
            }
            // Append ML detection details
            if (info.mlScore >= 0) {
                reason.append("\n\n[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        } else if (useAi) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            aiPanel.setVisibility(View.VISIBLE);
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(R.color.md_theme_surfaceContainerHighest)));
            // Show ML details even without AI verdict
            if (info.mlScore >= 0) {
                TextView reason = item.findViewById(R.id.aiReasonText);
                reason.setText("[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        }

        item.findViewById(R.id.btnQuarantine).setOnClickListener(v ->
                quarantine(info, item));
        item.findViewById(R.id.btnAllow).setOnClickListener(v ->
                allowFile(info, item));
        item.findViewById(R.id.btnDelete).setOnClickListener(v ->
                deleteFile(info, item));

        // ML 结果按钮
        MaterialButton mlBtn = item.findViewById(R.id.mlResultBtn);
        if (info.mlScore >= 0) {
            mlBtn.setVisibility(View.VISIBLE);
            mlBtn.setOnClickListener(v -> showMlResultDialog(info));
        } else {
            mlBtn.setVisibility(View.GONE);
        }
    }

    /** 显示单个文件的 ML 检测结果对话框。 */
    private void showMlResultDialog(FileRiskInfo info) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ml_results_title))
                .setMessage(getString(R.string.ml_detail_format,
                        info.name,
                        info.mlScore,
                        info.mlReason != null ? info.mlReason : getString(R.string.ml_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------- 隔离 / 放行（扫描结果项） ----------

    private void quarantine(FileRiskInfo info, View item) {
        QuarantineManager.QuarantineRecord rec;
        if ("saf".equals(info.source)) {
            rec = qm.quarantineSaf(currentTreeUri, Uri.parse(info.path), info.name,
                    info.name, info.size, joinReasons(info.reasons), info.score);
        } else {
            rec = qm.quarantineFile(new File(info.path),
                    joinReasons(info.reasons), info.score);
        }
        if (rec != null) {
            Toast.makeText(this, R.string.file_quarantined_toast, Toast.LENGTH_SHORT).show();
            ((LinearLayout) item.getParent()).removeView(item);
            BehaviorLog.log(this, BehaviorLog.TYPE_QUARANTINE, info.name,
                    joinReasons(info.reasons));
        } else {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void allowFile(FileRiskInfo info, View item) {
        prefs.addToFileAllowlist(info.path);
        Toast.makeText(this, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
        ((LinearLayout) item.getParent()).removeView(item);
    }

    /** 直接删除文件（扫描结果项）。 */
    private void deleteFile(FileRiskInfo info, View item) {
        boolean deleted = false;
        try {
            if ("saf".equals(info.source) && currentTreeUri != null) {
                DocumentFile df = DocumentFile.fromSingleUri(this, Uri.parse(info.path));
                deleted = df != null && df.exists() && df.delete();
            } else {
                File f = new File(info.path);
                deleted = f.exists() && f.delete();
            }
        } catch (Exception ignored) {
            deleted = false;
        }
        if (deleted) {
            Toast.makeText(this, R.string.file_deleted_toast, Toast.LENGTH_SHORT).show();
            ((LinearLayout) item.getParent()).removeView(item);
        } else {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** AI 扫描时，在进度条下方淡灰色逐行展示 AI 思考过程。 */
    private void showAiThinking() {
        aiThinkSection.setVisibility(View.VISIBLE);
        aiThinkText.setText("");
        final String[] steps = {
                getString(R.string.ai_think_1),
                getString(R.string.ai_think_2),
                getString(R.string.ai_think_3),
                getString(R.string.ai_think_4),
                getString(R.string.ai_think_5),
                getString(R.string.ai_think_6),
                getString(R.string.ai_think_7),
                getString(R.string.ai_think_8),
        };
        for (int i = 0; i < steps.length; i++) {
            final int idx = i;
            aiThinkText.postDelayed(() -> {
                if (idx == 0) aiThinkText.setText(steps[idx]);
                else aiThinkText.append("\n" + steps[idx]);
            }, 300 + i * 650);
        }
    }

    // ---------- 隔离区 / 放行名单渲染 ----------

    private void refreshQuarantine() {
        List<QuarantineManager.QuarantineRecord> records = qm.list();
        quarantineList.removeAllViews();
        if (records.isEmpty()) {
            quarantineEmpty.setVisibility(View.VISIBLE);
            quarantineCountText.setText(getString(R.string.quarantine_count, 0));
        } else {
            quarantineEmpty.setVisibility(View.GONE);
            quarantineCountText.setText(getString(R.string.quarantine_count, records.size()));
            LayoutInflater inflater = LayoutInflater.from(this);
            for (QuarantineManager.QuarantineRecord r : records) {
                View item = inflater.inflate(R.layout.item_quarantine, quarantineList, false);
                TextView name = item.findViewById(R.id.qFileName);
                TextView path = item.findViewById(R.id.qOriginalPath);
                TextView meta = item.findViewById(R.id.qMeta);
                name.setText(r.fileName);
                path.setText(r.originalPath);
                meta.setText(getString(R.string.quarantine_meta,
                        timeText(r.quarantinedAt), r.riskScore, r.reason));
                item.findViewById(R.id.btnRestore).setOnClickListener(v -> {
                    if (qm.restore(r.id)) {
                        Toast.makeText(this, R.string.file_restored_toast, Toast.LENGTH_SHORT).show();
                        refreshQuarantine();
                    } else {
                        Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
                    }
                });
                item.findViewById(R.id.btnAllow).setOnClickListener(v -> {
                    if (qm.allow(r.id)) {
                        Toast.makeText(this, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
                        refreshQuarantine();
                    } else {
                        Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
                    }
                });
                quarantineList.addView(item);
            }
        }

        java.util.Set<String> allowed = prefs.getFileAllowlist();
        allowList.removeAllViews();
        if (allowed.isEmpty()) {
            allowEmpty.setVisibility(View.VISIBLE);
            allowCountText.setText(getString(R.string.allow_count, 0));
        } else {
            allowEmpty.setVisibility(View.GONE);
            allowCountText.setText(getString(R.string.allow_count, allowed.size()));
            LayoutInflater inflater = LayoutInflater.from(this);
            for (String path : allowed) {
                if (path == null || path.isEmpty()) continue;
                View item = inflater.inflate(R.layout.item_allowed, allowList, false);
                TextView tv = item.findViewById(R.id.allowedPath);
                tv.setText(path);
                item.findViewById(R.id.btnUnallow).setOnClickListener(v -> {
                    prefs.removeFromFileAllowlist(path);
                    refreshQuarantine();
                });
                allowList.addView(item);
            }
        }
    }

    // ---------- 行为板块（防护过程记录，蓝色主题） ----------

    /** 渲染行为时间线：强制退出 / 拦截 / 隔离 / 救援 / 紧急遇险 / 监控告警等。 */
    private void renderBehavior() {
        java.util.List<BehaviorLog.Entry> entries = BehaviorLog.list(this);
        behaviorList.removeAllViews();
        if (entries.isEmpty()) {
            behaviorEmpty.setVisibility(View.VISIBLE);
            behaviorCountText.setText(getString(R.string.behavior_count, 0));
            return;
        }
        behaviorEmpty.setVisibility(View.GONE);
        behaviorCountText.setText(getString(R.string.behavior_count, entries.size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        int index = 0;
        for (BehaviorLog.Entry e : entries) {
            View item = inflater.inflate(R.layout.item_behavior, behaviorList, false);
            TextView typeChip = item.findViewById(R.id.behaviorType);
            TextView targetText = item.findViewById(R.id.behaviorTarget);
            TextView detailText = item.findViewById(R.id.behaviorDetail);
            TextView timeText = item.findViewById(R.id.behaviorTime);
            View dot = item.findViewById(R.id.behaviorDot);

            int labelRes;
            int colorRes;
            switch (e.type == null ? "" : e.type) {
                case BehaviorLog.TYPE_FORCE_STOP:
                    labelRes = R.string.behavior_type_force_stop;
                    colorRes = R.color.md_theme_error;
                    break;
                case BehaviorLog.TYPE_BLOCK:
                    labelRes = R.string.behavior_type_block;
                    colorRes = R.color.md_theme_error;
                    break;
                case BehaviorLog.TYPE_RESCUE:
                    labelRes = R.string.behavior_type_rescue;
                    colorRes = R.color.md_theme_error;
                    break;
                case BehaviorLog.TYPE_EMERGENCY:
                    labelRes = R.string.behavior_type_emergency;
                    colorRes = R.color.md_theme_error;
                    break;
                case BehaviorLog.TYPE_QUARANTINE:
                    labelRes = R.string.behavior_type_quarantine;
                    colorRes = R.color.md_theme_tertiary;
                    break;
                case BehaviorLog.TYPE_MONITOR:
                    labelRes = R.string.behavior_type_monitor;
                    colorRes = R.color.md_theme_primary;
                    break;
                case BehaviorLog.TYPE_UNINSTALL:
                    labelRes = R.string.behavior_type_uninstall;
                    colorRes = R.color.md_theme_secondary;
                    break;
                case BehaviorLog.TYPE_FREEZE:
                    labelRes = R.string.behavior_type_freeze;
                    colorRes = R.color.md_theme_secondary;
                    break;
                default:
                    labelRes = R.string.behavior_type_other;
                    colorRes = R.color.md_theme_secondary;
                    break;
            }
            int color = getColor(colorRes);
            typeChip.setText(labelRes);
            typeChip.setTextColor(color);
            dot.setBackgroundColor(color);
            targetText.setText(e.target);
            detailText.setText(e.detail);
            timeText.setText(BehaviorLog.timeText(e.ts));

            // 逐条错峰淡入动画
            item.setAlpha(0f);
            item.setTranslationY(18f);
            final int delay = Math.min(index, 12) * 40;
            item.postDelayed(() -> item.animate().alpha(1f).translationY(0f)
                    .setDuration(240).start(), delay);
            index++;

            behaviorList.addView(item);
        }
    }

    // ---------- 拦截模式（红色守护栏） ----------

    private void setupBlock() {        blockToggleButton.setOnClickListener(v -> {
            if (prefs.isBlockMode()) {
                disableBlockMode();
            } else {
                ensureNotificationThenEnable();
            }
        });
        renderBlock();
    }

    private void ensureNotificationThenEnable() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            return;
        }
        enableBlockMode();
    }

    /** 渲染「所需权限」索取栏目：无障碍 / 悬浮窗 / 使用情况访问，已授权则不显示。 */
    private void renderPerms() {
        if (permList == null || !blockEnabled) return;
        permList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        if (!PermUtil.isAccessibilityEnabled(this)) {
            permList.addView(buildPermRow(inflater,
                    R.drawable.ic_perm_accessibility,
                    getString(R.string.perm_accessibility_title),
                    getString(R.string.perm_accessibility_desc),
                    () -> PermUtil.openAccessibilitySettings(this)));
        }
        if (!PermUtil.canDrawOverlays(this)) {
            permList.addView(buildPermRow(inflater,
                    R.drawable.ic_perm_overlay,
                    getString(R.string.perm_overlay_title),
                    getString(R.string.perm_overlay_desc),
                    () -> PermUtil.openOverlaySettings(this)));
        }
        if (!PermUtil.hasUsageAccess(this)) {
            permList.addView(buildPermRow(inflater,
                    R.drawable.ic_perm_usage,
                    getString(R.string.perm_usage_title),
                    getString(R.string.perm_usage_desc),
                    () -> PermUtil.openUsageAccessSettings(this)));
        }
    }

    private View buildPermRow(LayoutInflater inflater, int iconRes,
                              String title, String desc, Runnable onGrant) {
        View row = inflater.inflate(R.layout.item_perm_row, permList, false);
        ImageView icon = row.findViewById(R.id.permIcon);
        TextView titleTv = row.findViewById(R.id.permTitle);
        TextView descTv = row.findViewById(R.id.permDesc);
        icon.setImageResource(iconRes);
        titleTv.setText(title);
        descTv.setText(desc);
        row.findViewById(R.id.permGrantButton).setOnClickListener(v -> onGrant.run());
        return row;
    }

    private void enableBlockMode() {
        prefs.setBlockMode(true);
        Notifier.updateBlockNotification(this);
        Toast.makeText(this, R.string.block_on_toast, Toast.LENGTH_SHORT).show();
        renderBlock();
    }

    private void disableBlockMode() {
        prefs.setBlockMode(false);
        Notifier.cancelBlockNotification(this);
        Toast.makeText(this, R.string.block_off_toast, Toast.LENGTH_SHORT).show();
        renderBlock();
    }

    /** 渲染拦截面板状态与被拦截应用列表（放行 / 删除）。 */
    private void renderBlock() {
        boolean on = prefs.isBlockMode();
        blockStateText.setText(on ? R.string.block_state_on : R.string.block_state_off);
        blockToggleButton.setText(on ? R.string.block_toggle_off : R.string.block_toggle_on);
        blockNotif.setVisibility(on ? View.VISIBLE : View.GONE);
        renderPerms();

        java.util.Set<String> apps = prefs.getBlockedApps();
        blockList.removeAllViews();
        if (apps.isEmpty()) {
            blockEmpty.setVisibility(View.VISIBLE);
            blockCountText.setText(getString(R.string.block_count, 0));
            return;
        }
        blockEmpty.setVisibility(View.GONE);
        blockCountText.setText(getString(R.string.block_count, apps.size()));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String pkg : apps) {
            if (pkg == null || pkg.isEmpty()) continue;
            View item = inflater.inflate(R.layout.item_blocked_app, blockList, false);
            ((TextView) item.findViewById(R.id.blockedPkg)).setText(appLabel(pkg));
            ((TextView) item.findViewById(R.id.blockedReason)).setText(pkg);
            item.findViewById(R.id.btnBlockAllow).setOnClickListener(v -> {
                prefs.removeBlockedApp(pkg);
                Toast.makeText(this, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
                renderBlock();
            });
            item.findViewById(R.id.btnBlockDelete).setOnClickListener(v -> {
                prefs.removeBlockedApp(pkg);
                renderBlock();
                launchUninstall(pkg);
            });
            blockList.addView(item);
        }
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void launchUninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 通知栏「关闭」后，同步界面开关与列表状态。 */
    private void refreshBlockStateFromNotification() {
        renderBlock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (blockStateReceiver != null) {
            try {
                unregisterReceiver(blockStateReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 紧急遇险：应用在前台时也能用三击音量上键逃生 ----------

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (com.polaris.app.service.PolarisAccessibilityService.handleVolumeUp(this)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------- 权限 ----------

    private boolean hasFullStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFullStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            currentTreeUri = treeUri;
            beginScan("folder", treeUri);
        } else if (requestCode == REQ_AI_SETUP && resultCode == RESULT_OK
                && "ai_pending".equals(pendingScanAction) && pendingAiRisks != null) {
            List<FileRiskInfo> risks = pendingAiRisks;
            pendingScanAction = null;
            pendingAiRisks = null;
            judgeWithAi(risks);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「所有文件访问」设置页返回后刷新权限提示
        if (hasFullStorageAccess()) {
            permHint.setVisibility(View.GONE);
        } else {
            permHint.setVisibility(View.VISIBLE);
        }
        // 从无障碍 / 悬浮窗 / 使用情况设置页返回后，刷新权限索取栏目（已授权则消失），
        // 并同步刷新常驻通知内容（缺权限提示 <-> 加护文案）
        renderPerms();
        if (prefs.isBlockMode()) Notifier.updateBlockNotification(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permHint.setVisibility(View.GONE);
            beginScan("global", null);
        } else if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableBlockMode();
            } else {
                Toast.makeText(this, R.string.block_need_notification, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- UI 辅助 ----------

    private void showProgress() {
        progressSection.setVisibility(View.VISIBLE);
        scanProgressPath.setVisibility(View.GONE);
        resultHeader.setVisibility(View.GONE);
        resultEmpty.setVisibility(View.GONE);
        resultList.removeAllViews();
    }

    private void hideProgress() {
        progressSection.setVisibility(View.GONE);
        aiThinkSection.setVisibility(View.GONE);
        aiThinkText.setText("");
    }

    private String displayPath(FileRiskInfo info) {
        if ("saf".equals(info.source)) {
            return getString(R.string.file_source_saf, info.name);
        }
        return info.path;
    }

    private static String humanSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", size / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String r : reasons) {
            if (sb.length() > 0) sb.append("；");
            sb.append(r);
        }
        return sb.toString();
    }

    private static String timeText(long millis) {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(millis));
    }
}
