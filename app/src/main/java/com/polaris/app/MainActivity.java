package com.polaris.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.DynamicColors;
import com.polaris.app.R;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.MalwareScanner;
import com.polaris.app.scan.ScanWorker;
import com.polaris.app.service.GuardService;
import com.polaris.app.service.PolarisAccessibilityService;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.AiProvider;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;
import com.polaris.app.util.TextUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Polaris Safety 主界面：四种守护模式入口。
 *
 * Lv.1 Normal        - 定期浅层扫描 + 恶意程序提醒
 * Lv.2 Accessibility - 无障碍权限深层扫描 + 尝试直接处置
 * Lv.3 Shizuku       - Shizuku 权限深度扫描 + 提前拦截恶意程序运行
 * Lv.4 Root          - Root 权限全方位拦截与守护
 *
 * 点击任一模式后，可选择「Normal（机械扫描）」或「Artificial Intelligence（AI 判定）」。
 * AI 路径：接入 7 家大模型 API（Hy3/GLM/Kimi/ChatGPT/Gemini/Claude/DeepSeek），
 * 由 AI 判断恶意应用应「清除」还是「保留」。
 */
public class MainActivity extends AppCompatActivity {

    /** 最近一次扫描结果缓存（同进程内由 ResultActivity 读取）。 */
    public static List<AppRiskInfo> lastRisks;
    /** 最近一次 AI 判定结果缓存。 */
    public static List<AiClient.AiVerdict> aiVerdicts;

    private static final int REQ_NOTIFICATION = 10;
    private static final int REQ_AI_FROM_CHOICE = 1041;

    private LinearLayout modeList;
    private TextView guardStatusText;
    private TextView aiStatusText;
    private View scanNowButton;
    private View guideCard;
    private Prefs prefs;
    private boolean pendingShizukuActivation = false;
    private boolean pendingAiMode = false;   // Shizuku 授权回调后是否走 AI 判定
    private int pendingChoiceMode = -1;      // 引擎选择弹窗跳转到接入页后，返回时重新打开

    private static final int[] MODE_KEYS = {
            Prefs.MODE_NORMAL, Prefs.MODE_ACCESSIBILITY,
            Prefs.MODE_SHIZUKU, Prefs.MODE_ROOT
    };
    private static final int[] MODE_NAME_RES = {
            R.string.mode_normal_name, R.string.mode_accessibility_name,
            R.string.mode_shizuku_name, R.string.mode_root_name
    };
    private static final int[] MODE_LEVEL_RES = {
            R.string.mode_level_1, R.string.mode_level_2,
            R.string.mode_level_3, R.string.mode_level_4
    };
    private static final int[] MODE_DESC_RES = {
            R.string.mode_normal_desc, R.string.mode_accessibility_desc,
            R.string.mode_shizuku_desc, R.string.mode_root_desc
    };
    private static final int[] MODE_ICON_RES = {
            R.drawable.ic_mode_normal, R.drawable.ic_mode_accessibility,
            R.drawable.ic_mode_shizuku, R.drawable.ic_mode_root
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == ShizukuHelper.REQUEST_CODE_PERMISSION) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED && pendingShizukuActivation) {
                        pendingShizukuActivation = false;
                        performShizukuActivation(pendingAiMode);
                    } else {
                        Toast.makeText(this, R.string.toast_no_shizuku_permission,
                                Toast.LENGTH_LONG).show();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        // 已选定守护模式：跳过模式选择，直接进入扫描中心
        // （「重新配置」入口会带 reconfigure 标记回到本页重新选择）
        boolean reconfigure = getIntent().getBooleanExtra("reconfigure", false);
        if (!reconfigure && prefs.getActiveMode() != Prefs.MODE_NONE) {
            Intent i = new Intent(this, FileScanActivity.class);
            i.putExtra("mode", prefs.getActiveMode());
            i.putExtra("engine", prefs.getPreferredEngine());
            startActivity(i);
            finish();
            return;
        }

        guardStatusText = findViewById(R.id.guardStatusText);
        aiStatusText = findViewById(R.id.aiStatusText);
        modeList = findViewById(R.id.modeList);
        scanNowButton = findViewById(R.id.scanNowButton);
        guideCard = findViewById(R.id.guideCard);

        buildModeCards();
        scanNowButton.setOnClickListener(v -> runScan(isDeepMode()));
        findViewById(R.id.aiEntryCard).setOnClickListener(v ->
                startActivity(new Intent(this, AiSetupActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnTheme).setOnClickListener(v -> showThemeSheet());

        Shizuku.addRequestPermissionResultListener(shizukuResultListener);
    }

    private void buildModeCards() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < MODE_KEYS.length; i++) {
            final int key = MODE_KEYS[i];
            View card = inflater.inflate(R.layout.item_mode_card, modeList, false);

            ImageView icon = card.findViewById(R.id.modeIcon);
            TextView name = card.findViewById(R.id.modeName);
            TextView level = card.findViewById(R.id.modeLevel);
            TextView desc = card.findViewById(R.id.modeDesc);
            TextView status = card.findViewById(R.id.modeStatus);
            ViewGroup powerBar = card.findViewById(R.id.modePowerBar);

            icon.setImageResource(MODE_ICON_RES[i]);
            name.setText(MODE_NAME_RES[i]);
            level.setText(MODE_LEVEL_RES[i]);
            desc.setText(MODE_DESC_RES[i]);

            // 能力等级条：点亮前 i+1 个点
            int dotIds[] = {R.id.powerDot1, R.id.powerDot2, R.id.powerDot3, R.id.powerDot4};
            for (int d = 0; d < dotIds.length; d++) {
                View dot = card.findViewById(dotIds[d]);
                dot.setBackgroundResource(d < i + 1
                        ? R.drawable.bg_power_dot_active
                        : R.drawable.bg_power_dot);
            }

            card.setOnClickListener(v -> showModeChoiceSheet(key));
            modeList.addView(card);
        }
    }

    // ---------- 模式点击：Normal / Artificial Intelligence 二选一 ----------

    private void showModeChoiceSheet(final int modeKey) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_mode_choice, null);

        boolean aiOk = prefs.isAiConfigured();
        View choiceAi = v.findViewById(R.id.choiceAi);
        View connect = v.findViewById(R.id.choiceAiConnect);

        // 已接入任意 AI：AI 判定亮起、隐藏「去接入」；未接入：AI 灰色 + 显示「去接入」
        choiceAi.setAlpha(aiOk ? 1f : 0.5f);
        connect.setVisibility(aiOk ? View.GONE : View.VISIBLE);

        v.findViewById(R.id.choiceNormal).setOnClickListener(x -> {
            dialog.dismiss();
            activateMode(modeKey, false);
        });
        choiceAi.setOnClickListener(x -> {
            if (aiOk) {
                dialog.dismiss();
                activateMode(modeKey, true);
            } else {
                // 未接入 AI：点击 AI 判定选项本身也跳转到接入界面
                dialog.dismiss();
                pendingChoiceMode = modeKey;
                startActivityForResult(
                        new Intent(this, AiSetupActivity.class), REQ_AI_FROM_CHOICE);
            }
        });
        connect.setOnClickListener(x -> {
            dialog.dismiss();
            pendingChoiceMode = modeKey;
            startActivityForResult(
                    new Intent(this, AiSetupActivity.class), REQ_AI_FROM_CHOICE);
        });
        dialog.setContentView(v);
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AI_FROM_CHOICE && resultCode == RESULT_OK
                && pendingChoiceMode != -1) {
            int mode = pendingChoiceMode;
            pendingChoiceMode = -1;
            // 接入成功后重新打开引擎选择：此时 AI 判定已亮起
            showModeChoiceSheet(mode);
        }
    }

    private void activateMode(int key, boolean useAi) {
        switch (key) {
            case Prefs.MODE_NORMAL:
                activateNormal(useAi);
                break;
            case Prefs.MODE_ACCESSIBILITY:
                activateAccessibility(useAi);
                break;
            case Prefs.MODE_SHIZUKU:
                activateShizuku(useAi);
                break;
            case Prefs.MODE_ROOT:
                activateRoot(useAi);
                break;
        }
    }

    // ---------- 模式激活 ----------

    /** 激活模式后进入「扫描中心」页面（文件夹扫描 / 全局扫描 / 隔离区管理）。
     *  激活后本页结束，返回键不会回到模式选择；再次打开应用将直接进入扫描中心。 */
    private void openScanCenter(boolean useAi) {
        Intent i = new Intent(this, FileScanActivity.class);
        i.putExtra("mode", prefs.getActiveMode());
        i.putExtra("engine", useAi ? Prefs.ENGINE_AI : Prefs.ENGINE_NORMAL);
        startActivity(i);
        finish();
    }

    private void activateNormal(boolean useAi) {
        requestNotificationPermissionIfNeeded();
        prefs.setActiveMode(Prefs.MODE_NORMAL);
        GuardService.stop(this);
        scheduleDailyScan();
        Toast.makeText(this, R.string.toast_activate_normal, Toast.LENGTH_SHORT).show();
        openScanCenter(useAi);
    }

    private void activateAccessibility(boolean useAi) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        prefs.setActiveMode(Prefs.MODE_ACCESSIBILITY);
        GuardService.start(this, Prefs.MODE_ACCESSIBILITY);
        Toast.makeText(this, R.string.toast_activate_accessibility, Toast.LENGTH_SHORT).show();
        openScanCenter(useAi);
    }

    private void activateShizuku(boolean useAi) {
        ShizukuHelper.CheckResult r = ShizukuHelper.checkAll(this);
        if (!r.appInstalled) {
            Toast.makeText(this, R.string.toast_no_shizuku_app, Toast.LENGTH_LONG).show();
            return;
        }
        if (!r.running) {
            Toast.makeText(this, R.string.toast_no_shizuku_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (!r.permissionGranted) {
            pendingShizukuActivation = true;
            pendingAiMode = useAi;
            ShizukuHelper.requestPermission();
            return;
        }
        performShizukuActivation(useAi);
    }

    private void performShizukuActivation(boolean useAi) {
        prefs.setActiveMode(Prefs.MODE_SHIZUKU);
        GuardService.start(this, Prefs.MODE_SHIZUKU);
        Toast.makeText(this, R.string.toast_activate_shizuku, Toast.LENGTH_SHORT).show();
        openScanCenter(useAi);
    }

    private void activateRoot(boolean useAi) {
        if (!RootChecker.isRootAvailable()) {
            Toast.makeText(this, R.string.toast_no_root, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.setActiveMode(Prefs.MODE_ROOT);
        GuardService.start(this, Prefs.MODE_ROOT);
        Toast.makeText(this, R.string.toast_activate_root, Toast.LENGTH_SHORT).show();
        openScanCenter(useAi);
    }

    // ---------- 外观主题三选一 ----------

    private void showThemeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_theme_choice, null);

        final int current = prefs.getThemeMode();
        updateThemeRadio(v, current);

        v.findViewById(R.id.themeSystem).setOnClickListener(x -> applyThemeChoice(dialog, Prefs.THEME_SYSTEM, R.string.theme_toast_system));
        v.findViewById(R.id.themeLight).setOnClickListener(x -> applyThemeChoice(dialog, Prefs.THEME_LIGHT, R.string.theme_toast_light));
        v.findViewById(R.id.themeDark).setOnClickListener(x -> applyThemeChoice(dialog, Prefs.THEME_DARK, R.string.theme_toast_dark));

        dialog.setContentView(v);
        dialog.show();
    }

    private void updateThemeRadio(View v, int mode) {
        v.findViewById(R.id.radioSystem).setBackgroundResource(
                mode == Prefs.THEME_SYSTEM ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
        v.findViewById(R.id.radioLight).setBackgroundResource(
                mode == Prefs.THEME_LIGHT ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
        v.findViewById(R.id.radioDark).setBackgroundResource(
                mode == Prefs.THEME_DARK ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
    }

    private void applyThemeChoice(BottomSheetDialog dialog, int mode, int toastRes) {
        prefs.setThemeMode(mode);
        PolarisApp.applyTheme(mode);
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    }

    // ---------- 立即扫描（应用级，机械判定） ----------

    private boolean isDeepMode() {
        int m = prefs.getActiveMode();
        return m == Prefs.MODE_ACCESSIBILITY || m == Prefs.MODE_SHIZUKU || m == Prefs.MODE_ROOT;
    }

    private void runScan(boolean deep) {
        Toast.makeText(this, R.string.toast_scan_start, Toast.LENGTH_SHORT).show();
        MalwareScanner.scanAsync(this, deep, risks -> {
            lastRisks = risks;
            aiVerdicts = null;

            Set<String> malicious = new HashSet<>();
            for (AppRiskInfo r : risks) {
                if (r.level >= AppRiskInfo.LEVEL_MEDIUM) malicious.add(r.packageName);
            }
            prefs.setMaliciousPackages(malicious);
            prefs.setLastScanMs(System.currentTimeMillis());

            runOnUiThread(() -> {
                Intent i = new Intent(this, ResultActivity.class);
                i.putExtra("deep", deep);
                startActivity(i);
            });
        });
    }

    private void scheduleDailyScan() {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                ScanWorker.class, 1, TimeUnit.DAYS).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "polaris_daily_scan", ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    // ---------- 权限与状态 ----------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = getPackageName() + "/"
                + PolarisAccessibilityService.class.getName();
        return enabled.contains(component);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        // 未选定守护模式时隐藏「立即扫描」并显示引导提示
        boolean hasMode = prefs.getActiveMode() != Prefs.MODE_NONE;
        scanNowButton.setVisibility(hasMode ? View.VISIBLE : View.GONE);
        guideCard.setVisibility(hasMode ? View.GONE : View.VISIBLE);

        // 顶部守护状态
        switch (prefs.getActiveMode()) {
            case Prefs.MODE_NORMAL:
                guardStatusText.setText(R.string.guard_status_normal);
                break;
            case Prefs.MODE_ACCESSIBILITY:
                guardStatusText.setText(R.string.guard_status_accessibility);
                break;
            case Prefs.MODE_SHIZUKU:
                guardStatusText.setText(R.string.guard_status_shizuku);
                break;
            case Prefs.MODE_ROOT:
                guardStatusText.setText(R.string.guard_status_root);
                break;
            default:
                guardStatusText.setText(R.string.guard_status_idle);
                break;
        }

        // AI 引擎状态
        AiProvider p = AiProvider.fromId(prefs.getAiProviderId());
        aiStatusText.setText(p != null && prefs.isAiConfigured()
                ? getString(R.string.ai_home_status_on, p.displayName(this))
                : getString(R.string.ai_home_status_off));

        // 各卡片状态
        for (int i = 0; i < modeList.getChildCount(); i++) {
            View card = modeList.getChildAt(i);
            TextView status = card.findViewById(R.id.modeStatus);
            int key = MODE_KEYS[i];
            switch (key) {
                case Prefs.MODE_NORMAL:
                    status.setText(R.string.mode_status_ready);
                    break;
                case Prefs.MODE_ACCESSIBILITY:
                    status.setText(isAccessibilityServiceEnabled()
                            ? R.string.mode_status_active : R.string.mode_status_need_auth);
                    break;
                case Prefs.MODE_SHIZUKU:
                    status.setText(ShizukuHelper.checkAll(this).allReady()
                            ? R.string.mode_status_ready : R.string.mode_status_need_auth);
                    break;
                case Prefs.MODE_ROOT:
                    status.setText(RootChecker.isRootAvailable()
                            ? R.string.mode_status_ready : R.string.mode_status_need_auth);
                    break;
            }
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResultListener);
        super.onDestroy();
    }
}
