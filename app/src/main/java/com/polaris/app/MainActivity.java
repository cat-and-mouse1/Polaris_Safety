package com.polaris.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.color.DynamicColors;
import com.polaris.app.R;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.MalwareScanner;
import com.polaris.app.scan.ScanWorker;
import com.polaris.app.service.GuardService;
import com.polaris.app.service.PolarisAccessibilityService;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.QuarantineManager;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;
import com.polaris.app.util.TextUtil;
import com.polaris.app.util.AiProvider;
import com.polaris.app.view.ToolsPanelView;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import androidx.documentfile.provider.DocumentFile;
import com.polaris.app.scan.FileRiskInfo;
import com.polaris.app.scan.FileScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class MainActivity extends BaseActivity {

    /** 最近一次扫描结果缓存（同进程内由 ResultActivity 读取）。 */
    public static List<AppRiskInfo> lastRisks;
    /** 最近一次 AI 判定结果缓存。 */
    public static List<AiClient.AiVerdict> aiVerdicts;
    /** 最近一次 ML 扫描结果缓存（按模式存储），供结果页查看。 */
    public static java.util.Map<Integer, java.util.List<AppRiskInfo>> mlResultsByMode = new java.util.HashMap<>();

    private static final int REQ_NOTIFICATION = 10;

    private LinearLayout modeList;
    private Prefs prefs;
    private boolean pendingShizukuActivation = false;

    private DrawerLayout drawerLayout;
    private TextView guardGreetingText;
    private TextView guardStatusText;
    private TextView guardModeText;
    private TextView lastGuardTime;
    private TextView totalGuardCount;
    private TextView recentScanResult;
    private LinearLayout recentFilesList;
    private TextView noRecentFilesText;

    // ---------- 主页滑动向导（点模式不再开新界面） ----------
    private static final int REQ_OPEN_TREE = 200;
    private static final int REQ_STORAGE = 300;
    private int pendingModeKey = -1;
    private Uri currentTreeUri = null;
    private boolean pendingGlobalScan = false;

    /** 范围勾选：全盘 / 已安装应用可同时选中；「选择文件夹」与两者互斥。 */
    private boolean scopeGlobal = true;
    private boolean scopeApps = false;
    private boolean scopeFolder = false;

    /** 本次扫描收集的两段结果（文件级 + 应用级）。 */
    private List<FileRiskInfo> lastFileRisks;
    private List<AppRiskInfo> lastAppRisks;
    private List<AiClient.AiVerdict> lastAppVerdicts;

    /** 文件夹扫描：用户可添加多个 SAF 授权树，扫描时逐个遍历。 */
    private final List<Uri> selectedFolders = new ArrayList<>();

    private View stageArea, configScope, configFolders, resultArea;
    private ViewGroup folderListContainer;
    private LinearLayout appResultList;
    private TextView folderEmptyText;
    private TextView scanStatusText, resultTitle, fileSectionTitle, appSectionTitle;
    /** 顶部大图标（含 3 圈环）：进结果页时上滑收起，离开时恢复。 */
    private View homeAppIcon;
    private int homeIconSize = 0;

    /** 动画测试模式：进度与旋转按固定时长完整播放一遍，与实际扫描速度解耦。 */
    private static final long ANIM_TEST_MS = 3200L;
    private ValueAnimator animTestProgress;
    private long animTestStartMs = 0;
    private LinearLayout resultList;
    private com.polaris.app.view.ConcentricRingsView homeRings;
    private ImageView homeStar;
    private FileScanner scanner;
    private QuarantineManager qm;
    private ObjectAnimator iconSpin;
    private int totalToScan = 1;
    private int scannedCount = 0;
    private boolean scanning = false;
    private static final int COUNT_CAP = 200000;

    // ---------- 下拉面板（应用使用的权限 + 最近添加文件） ----------
    // 下拉面板（全屏承载工具箱：权限使用 / 最近文件 / 单文件扫描）
    private View pullOverlay;
    private ToolsPanelView pullPanel;

    private boolean pullOpen = false;
    private int pullSheetHeight = 0;

    private static final int[] MODE_KEYS = {
            Prefs.MODE_NORMAL, Prefs.MODE_ACCESSIBILITY,
            Prefs.MODE_SHIZUKU, Prefs.MODE_ROOT
    };
    private static final int[] MODE_NAME_RES = {
            R.string.mode_normal_name, R.string.mode_accessibility_name,
            R.string.mode_shizuku_name, R.string.mode_root_name
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
                        enterScopeForMode(Prefs.MODE_SHIZUKU);
                    } else {
                        pendingShizukuActivation = false;
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

        // 首次启动 → 进入教程
        if (!prefs.isTutorialDone(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        // 已选定守护模式：跳过模式选择，直接进入扫描中心
        // （「重新配置」入口会带 reconfigure 标记回到本页重新选择）
        boolean reconfigure = getIntent().getBooleanExtra("reconfigure", false);
        boolean fromOnboarding = getIntent().getBooleanExtra("from_onboarding", false);
        if (!reconfigure && !fromOnboarding && prefs.getActiveMode() != Prefs.MODE_NONE) {
            Intent i = new Intent(this, FileScanActivity.class);
            i.putExtra("mode", prefs.getActiveMode());
            i.putExtra("engine", prefs.getPreferredEngine());
            startActivity(i);
            finish();
            return;
        }

        modeList = findViewById(R.id.modeList);

        // 滑动向导相关视图绑定
        stageArea = findViewById(R.id.stageArea);
        configScope = findViewById(R.id.configScope);
        configFolders = findViewById(R.id.configFolders);
        resultArea = findViewById(R.id.resultArea);
        folderListContainer = findViewById(R.id.folderListContainer);
        folderEmptyText = findViewById(R.id.folderEmptyText);
        scanStatusText = findViewById(R.id.scanStatusText);
        resultTitle = findViewById(R.id.resultTitle);
        resultList = findViewById(R.id.resultList);
        appResultList = findViewById(R.id.appResultList);
        fileSectionTitle = findViewById(R.id.fileSectionTitle);
        appSectionTitle = findViewById(R.id.appSectionTitle);
        homeRings = findViewById(R.id.homeRings);
        homeStar = findViewById(R.id.homeStar);
        scanner = new FileScanner(this);
        qm = new QuarantineManager(this);

        findViewById(R.id.scopeOptGlobal).setOnClickListener(v -> toggleScope("global"));
        findViewById(R.id.scopeOptFolder).setOnClickListener(v -> toggleScope("folder"));
        findViewById(R.id.scopeOptApps).setOnClickListener(v -> toggleScope("apps"));
        findViewById(R.id.scopeStart).setOnClickListener(v -> onScopeStart());
        findViewById(R.id.scopeBack).setOnClickListener(v -> backToModes());
        findViewById(R.id.folderAddButton).setOnClickListener(v -> pickFolder());
        findViewById(R.id.folderStart).setOnClickListener(v -> startFolderScan());
        findViewById(R.id.folderBack).setOnClickListener(v -> backToScope());
        findViewById(R.id.resultBack).setOnClickListener(v -> backToModes());
        findViewById(R.id.resultAgain).setOnClickListener(v -> enterScopeStep());
        resetScope();
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerLayout.setScrimColor(0x00000000);

        // 应用图标整体放大到原来的 2 倍：容器 = 屏宽 84%；最外圈直径 = 星标直径 3 倍
        // 外圈半径比例 0.95 → 外圈直径 ≈ 0.95*size，故星标取 0.95/3 ≈ 0.317*size
        homeAppIcon = findViewById(R.id.homeAppIcon);
        if (homeAppIcon != null) {
            // 竖屏：屏宽 84%；横屏：左栏仅占 1/3 宽，按 min(屏宽/3, 屏高) 的 92% 防止溢出（横屏图标更大）
            int dw = getResources().getDisplayMetrics().widthPixels;
            int dh = getResources().getDisplayMetrics().heightPixels;
            int size = (int) ((dw > dh ? Math.min(dw / 3f, dh) : dw) * (dw > dh ? 0.92f : 0.84f) + 0.5f);
            homeIconSize = size;
            ViewGroup.LayoutParams lp = homeAppIcon.getLayoutParams();
            lp.width = size;
            lp.height = size;
            homeAppIcon.setLayoutParams(lp);

            // 中心圆盘 = 容器 45%
            View disc = findViewById(R.id.homeIconDisc);
            if (disc != null) {
                int discSize = (int) (size * 0.45f + 0.5f);
                ViewGroup.LayoutParams dlp = disc.getLayoutParams();
                dlp.width = discSize;
                dlp.height = discSize;
                disc.setLayoutParams(dlp);
            }

            // 星标 = 容器 0.317（使最外圈直径恰为星标 3 倍）
            View star = findViewById(R.id.homeStar);
            if (star != null) {
                int iconSize = (int) (size * 0.317f + 0.5f);
                ViewGroup.LayoutParams ilp = star.getLayoutParams();
                ilp.width = iconSize;
                ilp.height = iconSize;
                star.setLayoutParams(ilp);
            }
        }

        // 教程：检测守护面板打开
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (!prefs.isTutorialDone(MainActivity.this)) {
                    prefs.setTutorialDone(MainActivity.this, true);
                }
            }
        });

        // 动态设置抽屉宽度为屏幕 4/5
        View guardDashboard = findViewById(R.id.guardDashboard);
        guardDashboard.post(() -> {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            // 横屏时抽屉上限 520dp（保证问候语完整一行），避免 4/5 屏宽盖满主界面
            int maxLand = (int) (getResources().getDisplayMetrics().density * 520 + 0.5f);
            ViewGroup.LayoutParams lp = guardDashboard.getLayoutParams();
            lp.width = (screenWidth > screenHeight)
                    ? Math.min(screenWidth * 4 / 5, maxLand)
                    : screenWidth * 4 / 5;
            guardDashboard.setLayoutParams(lp);
        });
        guardGreetingText = findViewById(R.id.guardGreetingText);
        guardStatusText = findViewById(R.id.guardStatusText);
        guardModeText = findViewById(R.id.guardModeText);
        lastGuardTime = findViewById(R.id.lastGuardTime);
        totalGuardCount = findViewById(R.id.totalGuardCount);
        recentScanResult = findViewById(R.id.recentScanResult);
        recentFilesList = findViewById(R.id.recentFilesList);
        noRecentFilesText = findViewById(R.id.noRecentFilesText);

        buildModeCards();

        // 头部：展开侧栏
        findViewById(R.id.btnDrawer).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));
        // 头部：打开工具箱（全屏下拉面板）
        findViewById(R.id.btnToolbox).setOnClickListener(v -> openPullDown());

        // 综合管理 / 设置：已从头部移入侧滑抽屉底部
        findViewById(R.id.drawerManagement).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new Intent(this, ManagementActivity.class));
        });
        findViewById(R.id.drawerSettings).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        setupPullDown();

        Shizuku.addRequestPermissionResultListener(shizukuResultListener);
    }

    // ---------- 工具箱面板：打开 / 关闭 ----------

    private void setupPullDown() {
        pullOverlay = findViewById(R.id.pullOverlay);
        pullPanel = findViewById(R.id.pullSheet);
        pullPanel.setOnCloseListener(this::closePullDown);
    }

    private void openPullDown() {
        if (pullOpen) return;
        pullOpen = true;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        pullSheetHeight = screenH;
        pullOverlay.setVisibility(View.VISIBLE);
        // 先整块移出屏幕，避免出现一帧“已展开”闪烁
        pullPanel.setTranslationY(-screenH);
        pullPanel.post(() -> pullPanel.animate().translationY(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start());

        // 展开时刷新工具箱数据（20 秒内不重复加载）
        pullPanel.refresh();
    }

    private void closePullDown() {
        if (!pullOpen) return;
        pullOpen = false;
        if (pullSheetHeight <= 0) {
            pullSheetHeight = getResources().getDisplayMetrics().heightPixels;
        }
        pullPanel.animate().translationY(-pullSheetHeight).setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    pullOverlay.setVisibility(View.GONE);
                    pullPanel.setTranslationY(0f);
                }).start();
    }

    @Override
    public void onBackPressed() {
        if (pullOpen) {
            closePullDown();
            return;
        }
        // 文件夹多选页：返回键先退回范围选择
        if (configFolders != null && configFolders.getVisibility() == View.VISIBLE) {
            backToScope();
            return;
        }
        super.onBackPressed();
    }

    private void buildModeCards() {
        LayoutInflater inflater = LayoutInflater.from(this);
        
        for (int i = 0; i < MODE_KEYS.length; i++) {
            final int key = MODE_KEYS[i];
            View card = inflater.inflate(R.layout.item_mode_card, modeList, false);

            ImageView icon = card.findViewById(R.id.modeIcon);
            TextView name = card.findViewById(R.id.modeName);
            TextView desc = card.findViewById(R.id.modeDesc);
            TextView status = card.findViewById(R.id.modeStatus);
            ViewGroup powerBar = card.findViewById(R.id.modePowerBar);

            icon.setImageResource(MODE_ICON_RES[i]);
            name.setText(MODE_NAME_RES[i]);
            desc.setText(MODE_DESC_RES[i]);

            // 能力等级条：点亮前 i+1 个点
            int dotIds[] = {R.id.powerDot1, R.id.powerDot2, R.id.powerDot3, R.id.powerDot4};
            for (int d = 0; d < dotIds.length; d++) {
                View dot = card.findViewById(dotIds[d]);
                dot.setBackgroundResource(d < i + 1
                        ? R.drawable.bg_power_dot_active
                        : R.drawable.bg_power_dot);
            }

            card.setOnClickListener(v -> onModePicked(key));
            modeList.addView(card);
        }
    }

    // ---------- 主页滑动向导：模式 → 范围 → 扫描/结果（不再开新界面） ----------
    // 扫描引擎不在向导内选择，统一由「设置 → 扫描引擎」决定（Prefs.getPreferredEngine()）。

    private void onModePicked(int key) {
        // 先校验该模式的前置条件（无障碍是否开启 / Shizuku 是否可用 / 是否有 root）
        if (!ensureModePrereq(key)) return;
        enterScopeForMode(key);
    }

    /** 前置条件满足后：激活模式并滑入范围选择页。 */
    private void enterScopeForMode(int key) {
        pendingModeKey = key;
        activateModeInPlace(key);
        slideOutLeft(modeList);
        slideInRight(configScope);
        resetScope();
    }

    /**
     * 激活模式前的可用性检查。
     * 返回 false 表示条件不足（已给出 Toast / 已跳转系统设置 / 正等待 Shizuku 授权回调），不继续。
     */
    private boolean ensureModePrereq(int key) {
        switch (key) {
            case Prefs.MODE_ACCESSIBILITY:
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return false;
                }
                return true;
            case Prefs.MODE_SHIZUKU: {
                ShizukuHelper.CheckResult r = ShizukuHelper.checkAll(this);
                if (!r.appInstalled) {
                    Toast.makeText(this, R.string.toast_no_shizuku_app, Toast.LENGTH_LONG).show();
                    return false;
                }
                if (!r.running) {
                    Toast.makeText(this, R.string.toast_no_shizuku_running, Toast.LENGTH_LONG).show();
                    return false;
                }
                if (!r.permissionGranted) {
                    pendingShizukuActivation = true;
                    ShizukuHelper.requestPermission();
                    return false;
                }
                return true;
            }
            case Prefs.MODE_ROOT:
                if (!RootChecker.isRootAvailable()) {
                    Toast.makeText(this, R.string.toast_no_root, Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            default:
                return true;
        }
    }

    /** 结果页「重新扫描」/ 范围返回：回到范围选择。 */
    private void enterScopeStep() {
        expandHomeIcon();
        hidePanelsExcept(configScope);
        slideInRight(configScope);
        resetScope();
    }

    /** 返回模式选择（清空扫描态）。 */
    private void backToModes() {
        stopIconSpin();
        cancelAnimTestProgress();
        homeRings.setScanning(false);
        scanStatusText.setVisibility(View.GONE);
        expandHomeIcon();
        modeList.setVisibility(View.VISIBLE);
        modeList.setAlpha(1f);
        modeList.setTranslationX(0f);
        hidePanelsExcept(modeList);
        slideInRight(modeList);
    }

    /** 是否使用 AI 引擎（由设置页统一配置）。 */
    private boolean useAiEngine() {
        return Prefs.ENGINE_AI.equals(prefs.getPreferredEngine());
    }

    /** 切换某个范围的勾选状态：全盘 / 已安装应用可多选；「选择文件夹」与两者互斥。 */
    private void toggleScope(String a) {
        if ("folder".equals(a)) {
            scopeFolder = !scopeFolder;
            if (scopeFolder) {
                scopeGlobal = false;
                scopeApps = false;
            }
        } else if ("global".equals(a)) {
            scopeGlobal = !scopeGlobal;
            if (scopeGlobal) scopeFolder = false;
        } else if ("apps".equals(a)) {
            scopeApps = !scopeApps;
            if (scopeApps) scopeFolder = false;
        }
        if (!scopeGlobal && !scopeApps && !scopeFolder) scopeGlobal = true;   // 至少保留一项
        refreshScopeUi();
    }

    /** 回到范围页时的默认状态：只勾「全盘扫描」。 */
    private void resetScope() {
        scopeGlobal = true;
        scopeApps = false;
        scopeFolder = false;
        refreshScopeUi();
    }

    private void refreshScopeUi() {
        setCardSelected(findViewById(R.id.scopeOptGlobal), scopeGlobal);
        setCardSelected(findViewById(R.id.scopeOptFolder), scopeFolder);
        setCardSelected(findViewById(R.id.scopeOptApps), scopeApps);
        findViewById(R.id.scopeCheckGlobal).setVisibility(scopeGlobal ? View.VISIBLE : View.GONE);
        findViewById(R.id.scopeCheckFolder).setVisibility(scopeFolder ? View.VISIBLE : View.GONE);
        findViewById(R.id.scopeCheckApps).setVisibility(scopeApps ? View.VISIBLE : View.GONE);
        // 选文件夹要先进多选页，主按钮文案改为「下一步」
        ((com.google.android.material.button.MaterialButton) findViewById(R.id.scopeStart))
                .setText(scopeFolder ? R.string.scan_next : R.string.toast_scan_start);
    }

    private void setCardSelected(View v, boolean on) {
        if (v instanceof com.google.android.material.card.MaterialCardView) {
            ((com.google.android.material.card.MaterialCardView) v).setStrokeColor(
                    getColor(on ? R.color.md_theme_primary : R.color.md_theme_outlineVariant));
        }
    }

    /** 范围页主按钮：勾了「选择文件夹」→ 进多选页；否则按勾选项开始组合扫描。 */
    private void onScopeStart() {
        if (scopeFolder) {
            selectedFolders.clear();
            refreshFolderList();
            slideOutLeft(configScope);
            slideInRight(configFolders);
            return;
        }
        // 全盘扫描需要「所有文件访问」权限，先申请
        if (scopeGlobal && !hasFullStorageAccess()) {
            pendingGlobalScan = true;
            requestFullStorageAccess();
            return;
        }
        startCombinedScan();
    }

    // ---------- 组合扫描：文件段 + 应用段 ----------

    /**
     * 按范围勾选依次执行：先文件扫描（勾了全盘时），再应用扫描（勾了已安装应用时），
     * 两段结果最后合并渲染到同一个结果页。
     */
    private void startCombinedScan() {
        lastFileRisks = null;
        lastAppRisks = null;
        lastAppVerdicts = null;
        if (scopeGlobal) {
            beginScan("global", null);        // 完成 → onScanFinished → afterFileStage()
        } else {
            afterFileStage();
        }
    }

    /** 文件段收尾：还有应用段就继续，否则进入结果渲染。 */
    private void afterFileStage() {
        if (scopeApps) startAppScan();
        else finishCombinedScan();
    }

    /** 应用级扫描（深度跟随守护模式），结果写入 prefs 并驱动后续拦截名单。 */
    private void startAppScan() {
        expandHomeIcon();          // 扫描期间图标 + 圆环要显示进度
        scanStatusText.setVisibility(View.VISIBLE);
        scanStatusText.setText(R.string.scan_apps_scanning);
        homeRings.setScanning(true);
        homeRings.setProgress(0f);
        startIconSpin();
        startAnimTestProgress();

        MalwareScanner.scanAsync(this, isDeepMode(), new MalwareScanner.ScanCallback() {
            private final int[] done = {0};
            private volatile int total = 0;

            @Override
            public void onTotal(int t) {
                total = t;
            }

            @Override
            public void onProgress(String label) {
                done[0]++;
                final int d = done[0];
                final int t = total;
                runOnUiThread(() -> scanStatusText.setText(t > 0
                        ? getString(R.string.scan_apps_scanning_n, d, t, label)
                        : getString(R.string.scan_scanning_n, d, label)));
            }

            @Override
            public void onResult(List<AppRiskInfo> risks) {
                lastAppRisks = risks;
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
                runOnUiThread(MainActivity.this::finishCombinedScan);
            }
        });
    }

    /** 所有扫描结束：按需做 AI 判定，然后统一渲染两段结果。 */
    private void finishCombinedScan() {
        scanning = false;

        // 动画测试模式：无视实际扫描速度，等进度与旋转完整播完一遍再出结果
        if (prefs.isAnimTestMode()) {
            long wait = animTestRemainingMs();
            if (wait > 16L) {
                homeRings.postDelayed(this::finishCombinedScan, wait);
                return;
            }
        }
        cancelAnimTestProgress();

        homeRings.setScanning(false);
        homeRings.setProgress(1f);
        stopIconSpin();

        if (useAiEngine() && prefs.isAiConfigured()) {
            judgeAllWithAi();
            return;
        }
        if (useAiEngine() && !prefs.isAiConfigured()) {
            Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_LONG).show();
        }
        renderCombined();
    }

    // ---------- 文件夹多选页 ----------

    /** 文件夹页「返回」：回到范围选择。 */
    private void backToScope() {
        slideOutRight(configFolders);
        slideInRight(configScope);
    }

    /** 打开系统文件夹选择器；选中的文件夹会追加进列表（可重复添加）。 */
    private void pickFolder() {
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

    /** 把用户选中的授权树加入列表（去重）并刷新列表界面。 */
    private void addSelectedFolder(Uri treeUri) {
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        for (Uri u : selectedFolders) {
            if (u.equals(treeUri)) return;
        }
        selectedFolders.add(treeUri);
        refreshFolderList();
    }

    /** 重建已选文件夹列表（每行 = 文件夹名 + 移除按钮）。 */
    private void refreshFolderList() {
        if (folderListContainer == null) return;
        folderListContainer.removeAllViews();
        folderEmptyText.setVisibility(selectedFolders.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < selectedFolders.size(); i++) {
            final int index = i;
            Uri uri = selectedFolders.get(i);
            View row = inflater.inflate(R.layout.item_selected_folder,
                    folderListContainer, false);
            ((TextView) row.findViewById(R.id.folderRowName)).setText(folderDisplayName(uri));
            row.findViewById(R.id.folderRowRemove).setOnClickListener(v -> {
                selectedFolders.remove(index);
                refreshFolderList();
            });
            folderListContainer.addView(row);
        }
    }

    /** 从 SAF tree uri 里取出可读的文件夹名。 */
    private String folderDisplayName(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                int colon = docId.indexOf(':');
                String tail = colon >= 0 ? docId.substring(colon + 1) : docId;
                if (!tail.isEmpty()) {
                    int slash = tail.lastIndexOf('/');
                    return slash >= 0 ? tail.substring(slash + 1) : tail;
                }
                return docId;
            }
        } catch (Exception ignored) {
        }
        String seg = treeUri.getLastPathSegment();
        return seg != null ? seg : treeUri.toString();
    }

    /** 文件夹页「开始扫描」：至少需要一个文件夹；只产出文件段结果。 */
    private void startFolderScan() {
        if (selectedFolders.isEmpty()) {
            Toast.makeText(this, R.string.scan_folder_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        lastFileRisks = null;
        lastAppRisks = null;
        lastAppVerdicts = null;
        beginScan("folder", selectedFolders);
    }

    /** 开始文件扫描；treeUris 非空表示 SAF 多文件夹扫描（逐个遍历授权树）。 */
    private void beginScan(String action, List<Uri> treeUris) {
        scanning = true;
        scannedCount = 0;
        currentTreeUri = (treeUris != null && !treeUris.isEmpty()) ? treeUris.get(0) : null;
        if (configScope.getVisibility() == View.VISIBLE) slideOutRight(configScope);
        if (configFolders.getVisibility() == View.VISIBLE) slideOutRight(configFolders);
        expandHomeIcon();          // 扫描期间图标 + 圆环要显示进度
        scanStatusText.setVisibility(View.VISIBLE);
        scanStatusText.setText(getString(R.string.scan_counting, 0));
        homeRings.setScanning(true);
        homeRings.setProgress(0f);
        startIconSpin();
        startAnimTestProgress();

        final String act = action;
        final List<Uri> uris = treeUris == null ? null : new ArrayList<>(treeUris);
        new Thread(() -> {
            int total;
            if ("folder".equals(act) && uris != null) {
                total = 0;
                for (Uri u : uris) total += countTree(u);
            } else {
                total = countFiles(Environment.getExternalStorageDirectory());
            }
            totalToScan = Math.max(total, 1);
            if ("folder".equals(act) && uris != null) {
                scanner.scanTrees(uris, scanCallback);
            } else {
                scanner.scanDirectory(Environment.getExternalStorageDirectory(), scanCallback);
            }
        }).start();
    }

    private final FileScanner.Callback scanCallback = new FileScanner.Callback() {
        @Override
        public void onProgress(String currentPath) {
            scannedCount++;
            final float p = Math.min(1f, scannedCount / (float) totalToScan);
            final boolean animTest = prefs.isAnimTestMode();
            runOnUiThread(() -> {
                // 动画测试模式：进度交给固定时长动画，忽略实际扫描节奏
                if (!animTest) homeRings.setProgress(p);
                scanStatusText.setText(getString(R.string.scan_scanning_n, scannedCount, currentPath));
            });
        }

        @Override
        public void onResult(List<FileRiskInfo> risks) {
            runOnUiThread(() -> onScanFinished(risks));
        }
    };

    private void onScanFinished(List<FileRiskInfo> risks) {
        lastFileRisks = risks;

        int total = risks.size(), safe = 0, suspicious = 0, malicious = 0;
        for (FileRiskInfo r : risks) {
            if (r.level == FileRiskInfo.LEVEL_SAFE) safe++;
            else if (r.level == FileRiskInfo.LEVEL_LOW) suspicious++;
            else if (r.level >= FileRiskInfo.LEVEL_MEDIUM) malicious++;
        }
        prefs.saveLastScanSummary(total, safe, suspicious, malicious);
        prefs.setLastScanMs(System.currentTimeMillis());
        prefs.incrementScanCount();

        afterFileStage();
    }

    /** AI 引擎：文件段与应用段依次送判定，全部完成后渲染。 */
    private void judgeAllWithAi() {
        scanStatusText.setVisibility(View.VISIBLE);
        scanStatusText.setText(R.string.ai_scan_start);

        final List<FileRiskInfo> fileRisks = lastFileRisks;
        final List<AppRiskInfo> appRisks = lastAppRisks;
        new Thread(() -> {
            try {
                List<Prefs.AiProviderConfig> providers = prefs.getEnabledProviders();
                String strategy = prefs.getMultiModelStrategy();
                if (providers != null && !providers.isEmpty()) {
                    if (fileRisks != null && !fileRisks.isEmpty()) {
                        List<AiClient.AiVerdict> fv =
                                AiClient.judgeFilesMulti(this, providers, strategy, fileRisks);
                        Map<String, AiClient.AiVerdict> index = AiClient.indexOf(fv);
                        for (FileRiskInfo r : fileRisks) r.verdict = index.get(r.path);
                    }
                    if (appRisks != null && !appRisks.isEmpty()) {
                        lastAppVerdicts = AiClient.judgeMulti(this, providers, strategy, appRisks);
                    }
                } else {
                    AiProvider provider = AiProvider.fromId(prefs.getAiProviderId());
                    String apiKey = prefs.getAiApiKey();
                    String model = prefs.getAiModel();
                    if (provider != null) {
                        if (model == null || model.trim().isEmpty()) model = provider.defaultModel;
                        if (fileRisks != null && !fileRisks.isEmpty()) {
                            List<AiClient.AiVerdict> fv =
                                    AiClient.judgeFiles(this, provider, apiKey, model, fileRisks);
                            Map<String, AiClient.AiVerdict> index = AiClient.indexOf(fv);
                            for (FileRiskInfo r : fileRisks) r.verdict = index.get(r.path);
                        }
                        if (appRisks != null && !appRisks.isEmpty()) {
                            lastAppVerdicts =
                                    AiClient.judge(this, provider, apiKey, model, appRisks);
                        }
                    }
                }
            } catch (AiClient.AiException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.ai_judge_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
            runOnUiThread(this::renderCombined);
        }).start();
    }

    /** 渲染合并结果页：文件段 + 应用段（只显示本次实际跑过的段）。 */
    private void renderCombined() {
        scanStatusText.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        int fileCount = lastFileRisks == null ? 0 : lastFileRisks.size();
        int appCount = lastAppRisks == null ? 0 : lastAppRisks.size();

        if (lastFileRisks != null && lastAppRisks != null) {
            resultTitle.setText(getString(R.string.scan_result_count_label, fileCount + appCount));
        } else if (lastAppRisks != null) {
            resultTitle.setText(getString(R.string.scan_section_apps, appCount));
        } else {
            resultTitle.setText(getString(R.string.scan_section_files, fileCount));
        }

        // 文件段
        resultList.removeAllViews();
        if (lastFileRisks != null) {
            fileSectionTitle.setVisibility(View.VISIBLE);
            fileSectionTitle.setText(getString(R.string.scan_section_files, fileCount));
            if (fileCount == 0) {
                resultList.addView(buildEmptyView());
            } else {
                List<FileRiskInfo> lowFiles = new ArrayList<>();
                for (FileRiskInfo info : lastFileRisks) {
                    if (info.level >= FileRiskInfo.LEVEL_MEDIUM) {
                        View item = inflater.inflate(R.layout.item_file_risk, resultList, false);
                        bindRiskItem(item, info);
                        resultList.addView(item);
                    } else {
                        lowFiles.add(info);
                    }
                }
                if (!lowFiles.isEmpty()) {
                    addLowRiskFold(resultList, lowFiles.size(), content -> {
                        for (FileRiskInfo info : lowFiles) {
                            View item = inflater.inflate(
                                    R.layout.item_file_risk_compact, content, false);
                            bindFileRiskCompact(item, info);
                            content.addView(item);
                        }
                    });
                }
            }
        } else {
            fileSectionTitle.setVisibility(View.GONE);
        }

        // 应用段
        appResultList.removeAllViews();
        if (lastAppRisks != null) {
            appSectionTitle.setVisibility(View.VISIBLE);
            appSectionTitle.setText(getString(R.string.scan_section_apps, appCount));
            if (appCount == 0) {
                appResultList.addView(buildEmptyView());
            } else {
                List<AppRiskInfo> lowApps = new ArrayList<>();
                for (AppRiskInfo info : lastAppRisks) {
                    if (info.level >= AppRiskInfo.LEVEL_MEDIUM) {
                        View item = inflater.inflate(R.layout.item_risk, appResultList, false);
                        bindAppRiskItem(item, info);
                        appResultList.addView(item);
                    } else {
                        lowApps.add(info);
                    }
                }
                if (!lowApps.isEmpty()) {
                    addLowRiskFold(appResultList, lowApps.size(), content -> {
                        for (AppRiskInfo info : lowApps) {
                            View item = inflater.inflate(
                                    R.layout.item_risk_compact, content, false);
                            bindAppRiskCompact(item, info);
                            content.addView(item);
                        }
                    });
                }
            }
        } else {
            appSectionTitle.setVisibility(View.GONE);
        }

        hidePanelsExcept(resultArea);
        collapseHomeIcon();
        slideInUp(resultArea);
    }

    /**
     * 往段容器追加「低风险与安全项」折叠组：标题行 + 默认收起的内容容器。
     *
     * @param filler 负责把低风险条目填进折叠容器
     */
    private void addLowRiskFold(LinearLayout container, int count,
                                java.util.function.Consumer<LinearLayout> filler) {
        View header = LayoutInflater.from(this)
                .inflate(R.layout.item_section_fold, container, false);
        TextView title = header.findViewById(R.id.foldTitle);
        ImageView arrow = header.findViewById(R.id.foldArrow);
        title.setText(getString(R.string.scan_low_risk_group, count));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        filler.accept(content);

        header.setOnClickListener(v -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(show ? 90f : 0f).setDuration(160).start();
        });

        container.addView(header);
        container.addView(content);
    }

    // ---------- 顶部图标（含圆环）的收起与恢复 ----------

    /** 进结果页：图标与圆环整体上滑收起，把纵向空间让给结果列表。 */
    private void collapseHomeIcon() {
        if (homeAppIcon == null) return;
        // 横屏：图标位于左栏垂直居中，结果页无需隐藏
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm.widthPixels > dm.heightPixels) return;
        if (homeAppIcon.getVisibility() != View.VISIBLE) return;
        int h = homeAppIcon.getHeight();
        if (h <= 0) {
            homeAppIcon.setVisibility(View.GONE);
            return;
        }
        homeAppIcon.animate().translationY(-h).alpha(0f).setDuration(SLIDE_OUT_MS)
                .setInterpolator(SLIDE_OUT_EASE)
                .withEndAction(() -> {
                    homeAppIcon.setVisibility(View.GONE);
                    homeAppIcon.setTranslationY(0f);
                    homeAppIcon.setAlpha(1f);
                }).start();

        // 同步把占位高度收到 0，否则会留下一块空白
        ValueAnimator va = ValueAnimator.ofInt(h, 0);
        va.setDuration(SLIDE_OUT_MS);
        va.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = homeAppIcon.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            homeAppIcon.setLayoutParams(lp);
        });
        va.start();
    }

    /** 离开结果页：把图标与圆环滑回来并恢复占位高度。 */
    private void expandHomeIcon() {
        if (homeAppIcon == null || homeIconSize <= 0) return;
        // 横屏：图标常驻左栏，确保可见即可
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm.widthPixels > dm.heightPixels) {
            if (homeAppIcon.getVisibility() != View.VISIBLE) {
                homeAppIcon.setVisibility(View.VISIBLE);
                homeAppIcon.setAlpha(1f);
                homeAppIcon.setTranslationY(0f);
            }
            return;
        }
        if (homeAppIcon.getVisibility() == View.VISIBLE
                && homeAppIcon.getLayoutParams().height == homeIconSize) {
            return;
        }
        homeAppIcon.setVisibility(View.VISIBLE);
        homeAppIcon.setAlpha(1f);
        homeAppIcon.setTranslationY(0f);
        ValueAnimator va = ValueAnimator.ofInt(
                Math.max(0, homeAppIcon.getLayoutParams().height), homeIconSize);
        va.setDuration(SLIDE_IN_MS);
        va.setInterpolator(SLIDE_IN_EASE);
        va.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = homeAppIcon.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            homeAppIcon.setLayoutParams(lp);
        });
        va.start();
    }


    private TextView buildEmptyView() {
        TextView empty = new TextView(this);
        empty.setText(R.string.scan_no_risk);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
        empty.setTextSize(15);
        empty.setPadding(0, 24, 0, 24);
        return empty;
    }

    /** 紧凑文件条目（折叠组内）：等级 + 文件名 + 路径，点击弹出操作菜单。 */
    private void bindFileRiskCompact(View item, FileRiskInfo info) {
        TextView levelText = item.findViewById(R.id.riskLevelText);
        TextView fileName = item.findViewById(R.id.fileName);
        TextView filePath = item.findViewById(R.id.filePath);

        fileName.setText(info.name);
        filePath.setText(displayPath(info));

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
        item.setOnClickListener(v -> showFileActions(info, v));
    }

    /** 紧凑应用条目（折叠组内）：图标 + 名称 + 包名 + 等级，点击直接处置。 */
    private void bindAppRiskCompact(View item, AppRiskInfo info) {
        ImageView icon = item.findViewById(R.id.appIcon);
        TextView name = item.findViewById(R.id.appName);
        TextView pkg = item.findViewById(R.id.packageName);
        TextView chip = item.findViewById(R.id.riskLevelChip);

        if (info.icon != null) icon.setImageDrawable(info.icon);
        name.setText(info.appName != null ? info.appName : info.packageName);
        pkg.setText(info.packageName);

        switch (info.level) {
            case AppRiskInfo.LEVEL_HIGH:
                chip.setText(R.string.risk_high);
                chip.setTextColor(getColor(R.color.md_theme_error));
                break;
            case AppRiskInfo.LEVEL_MEDIUM:
                chip.setText(R.string.risk_medium);
                chip.setTextColor(getColor(R.color.md_theme_tertiary));
                break;
            case AppRiskInfo.LEVEL_LOW:
                chip.setText(R.string.risk_low);
                chip.setTextColor(getColor(R.color.md_theme_secondary));
                break;
            default:
                chip.setText(R.string.risk_safe);
                chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
                break;
        }
        item.setOnClickListener(v -> handleApp(info));
    }

    /** 紧凑文件条目的操作菜单：隔离 / 放行 / 删除。 */
    private void showFileActions(FileRiskInfo info, View anchor) {
        String[] actions = {
                getString(R.string.file_action_quarantine),
                getString(R.string.file_action_allow),
                getString(R.string.file_action_delete),
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(info.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) quarantine(info, anchor);
                    else if (which == 1) allowFile(info);
                    else deleteFile(info, anchor);
                })
                .show();
    }

    /** 渲染单条应用风险（复用 ResultActivity 的 item_risk 布局）。 */
    private void bindAppRiskItem(View item, AppRiskInfo info) {
        ImageView icon = item.findViewById(R.id.appIcon);
        TextView name = item.findViewById(R.id.appName);
        TextView pkg = item.findViewById(R.id.packageName);
        TextView score = item.findViewById(R.id.scoreText);
        TextView reason = item.findViewById(R.id.reasonText);
        TextView chip = item.findViewById(R.id.riskLevelChip);
        TextView mlBadge = item.findViewById(R.id.mlBadge);
        com.google.android.material.button.MaterialButton handle =
                item.findViewById(R.id.handleButton);
        com.google.android.material.button.MaterialButton mlBtn =
                item.findViewById(R.id.mlResultBtn);

        if (icon != null && info.icon != null) icon.setImageDrawable(info.icon);
        name.setText(info.appName != null ? info.appName : info.packageName);
        pkg.setText(info.packageName);
        score.setText("风险指数 " + info.score + " / 100"
                + (info.isSystem ? " · 系统应用" : ""));

        switch (info.level) {
            case AppRiskInfo.LEVEL_HIGH:
                chip.setText(R.string.risk_high);
                chip.setTextColor(Color.WHITE);
                chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.md_theme_error)));
                break;
            case AppRiskInfo.LEVEL_MEDIUM:
                chip.setText(R.string.risk_medium);
                chip.setTextColor(getColor(R.color.md_theme_onTertiaryContainer));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(getColor(R.color.md_theme_tertiaryContainer)));
                break;
            case AppRiskInfo.LEVEL_LOW:
                chip.setText(R.string.risk_low);
                chip.setTextColor(getColor(R.color.md_theme_onSecondaryContainer));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(getColor(R.color.md_theme_secondaryContainer)));
                break;
            default:
                chip.setText(R.string.risk_safe);
                chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(getColor(R.color.md_theme_surfaceContainerHighest)));
                break;
        }

        String reasonText = joinReasons(info.reasons);
        reason.setText(reasonText.isEmpty() ? getString(R.string.scan_no_risk) : reasonText);

        if (info.mlScore >= 0) {
            mlBadge.setVisibility(View.VISIBLE);
            mlBadge.setText("ML: " + info.mlScore + "/100");
            mlBadge.setTextColor(Color.WHITE);
            mlBadge.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.md_theme_tertiary)));
            mlBtn.setVisibility(View.VISIBLE);
            mlBtn.setText("查看");
            mlBtn.setOnClickListener(v -> showMlDialog(info));
        } else {
            mlBadge.setVisibility(View.GONE);
            mlBtn.setVisibility(View.GONE);
        }

        bindAppAiPanel(item, info);

        handle.setText(info.level >= AppRiskInfo.LEVEL_MEDIUM
                ? R.string.action_handle : R.string.action_app_details);
        handle.setOnClickListener(v -> handleApp(info));
    }

    /** 应用条目的 AI 判定面板（AI 引擎未启用时整块隐藏）。 */
    private void bindAppAiPanel(View item, AppRiskInfo info) {
        View panel = item.findViewById(R.id.aiVerdictPanel);
        TextView chip = item.findViewById(R.id.aiVerdictChip);
        TextView confidence = item.findViewById(R.id.aiConfidenceText);
        TextView reasonText = item.findViewById(R.id.aiReasonText);

        if (!useAiEngine()) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);

        AiClient.AiVerdict v = null;
        if (lastAppVerdicts != null) {
            for (AiClient.AiVerdict x : lastAppVerdicts) {
                if (info.packageName.equals(x.packageName)) {
                    v = x;
                    break;
                }
            }
        }
        if (v == null) {
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.md_theme_surfaceContainerHighest)));
            confidence.setText("");
            reasonText.setText("");
            return;
        }
        if (v.remove) {
            chip.setText(R.string.ai_verdict_remove);
            chip.setTextColor(getColor(R.color.md_theme_onError));
            chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.md_theme_error)));
        } else {
            chip.setText(R.string.ai_verdict_keep);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.ai_success)));
        }
        confidence.setText(getString(R.string.ai_confidence, Math.round(v.confidence * 100)));
        reasonText.setText(v.reason != null && !v.reason.isEmpty()
                ? getString(R.string.ai_reason_prefix, v.reason) : "");
    }

    private void showMlDialog(AppRiskInfo info) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ml_results_title)
                .setMessage(getString(R.string.ml_detail_format,
                        info.appName != null ? info.appName : info.packageName,
                        info.mlScore,
                        info.mlReason != null ? info.mlReason : getString(R.string.ml_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 应用条目处置：按当前守护模式强停 / 冻结，最后打开系统应用详情页。 */
    private void handleApp(AppRiskInfo info) {
        int mode = prefs.getActiveMode();
        if (mode == Prefs.MODE_SHIZUKU) {
            boolean ok = ShizukuHelper.runShell("am", "force-stop", info.packageName) != null;
            Toast.makeText(this, ok ? R.string.action_force_stop : R.string.mode_status_no_permission,
                    Toast.LENGTH_SHORT).show();
        } else if (mode == Prefs.MODE_ROOT) {
            boolean stopped = RootChecker.runAsRoot("am force-stop " + info.packageName);
            boolean frozen = RootChecker.runAsRoot("pm disable-user --user 0 " + info.packageName);
            Toast.makeText(this,
                    stopped && frozen ? R.string.action_freeze : R.string.mode_status_no_permission,
                    Toast.LENGTH_SHORT).show();
        }
        openAppDetails(info.packageName);
    }

    private void openAppDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.action_app_details, Toast.LENGTH_SHORT).show();
        }
    }

    private void bindRiskItem(View item, FileRiskInfo info) {
        TextView levelText = item.findViewById(R.id.riskLevelText);
        TextView fileName = item.findViewById(R.id.fileName);
        TextView filePath = item.findViewById(R.id.filePath);
        TextView fileMeta = item.findViewById(R.id.fileMeta);
        TextView fileReasons = item.findViewById(R.id.fileReasons);
        TextView mlBadge = item.findViewById(R.id.mlBadge);

        fileName.setText(info.name);
        filePath.setText(displayPath(info));
        fileMeta.setText(getString(R.string.file_meta, humanSize(info.size), info.score));
        fileReasons.setText(joinReasons(info.reasons));

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
            mlBadge.setTextColor(Color.WHITE);
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

        View aiPanel = item.findViewById(R.id.aiVerdictPanel);
        if (useAiEngine() && info.verdict != null) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            TextView conf = item.findViewById(R.id.aiConfidenceText);
            TextView reason = item.findViewById(R.id.aiReasonText);
            aiPanel.setVisibility(View.VISIBLE);
            if (info.verdict.remove) {
                chip.setText(R.string.ai_verdict_remove);
                chip.setTextColor(Color.WHITE);
                chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.md_theme_error)));
                conf.setText(getString(R.string.ai_confidence, Math.round(info.verdict.confidence * 100)));
                reason.setText(getString(R.string.ai_reason_prefix, info.verdict.reason));
            } else {
                chip.setText(R.string.ai_verdict_keep);
                chip.setTextColor(getColor(R.color.ai_success));
                chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.md_theme_surfaceContainerHighest)));
                conf.setText(getString(R.string.ai_confidence, Math.round(info.verdict.confidence * 100)));
                reason.setText(getString(R.string.ai_reason_prefix, info.verdict.reason));
            }
            if (info.mlScore >= 0) {
                reason.append("\n\n[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        } else if (useAiEngine()) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            aiPanel.setVisibility(View.VISIBLE);
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.md_theme_surfaceContainerHighest)));
            if (info.mlScore >= 0) {
                TextView reason = item.findViewById(R.id.aiReasonText);
                reason.setText("[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        } else {
            aiPanel.setVisibility(View.GONE);
        }

        item.findViewById(R.id.btnQuarantine).setOnClickListener(v -> quarantine(info, v));
        item.findViewById(R.id.btnAllow).setOnClickListener(v -> allowFile(info));
        item.findViewById(R.id.btnDelete).setOnClickListener(v -> deleteFile(info, v));

        com.google.android.material.button.MaterialButton mlBtn = item.findViewById(R.id.mlResultBtn);
        if (info.mlScore >= 0) {
            mlBtn.setVisibility(View.VISIBLE);
            mlBtn.setOnClickListener(v -> showMlResultDialog(info));
        } else {
            mlBtn.setVisibility(View.GONE);
        }
    }

    private void quarantine(FileRiskInfo info, View btn) {
        QuarantineManager.QuarantineRecord rec;
        if ("saf".equals(info.source)) {
            // 多文件夹扫描：优先使用该文件所属的那棵授权树
            Uri ownerTree = info.treeUri != null ? Uri.parse(info.treeUri) : currentTreeUri;
            rec = qm.quarantineSaf(ownerTree, Uri.parse(info.path), info.name,
                    info.name, info.size, joinReasons(info.reasons), info.score);
        } else {
            rec = qm.quarantineFile(new File(info.path), joinReasons(info.reasons), info.score);
        }
        if (rec != null) {
            Toast.makeText(this, R.string.file_quarantined_toast, Toast.LENGTH_SHORT).show();
            removeResultItem(btn);
        } else {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void allowFile(FileRiskInfo info) {
        prefs.addToFileAllowlist(info.path);
        Toast.makeText(this, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
        // 该条目在下次扫描时跳过；此处不立即移除以免打断浏览
    }

    private void deleteFile(FileRiskInfo info, View btn) {
        boolean deleted = false;
        try {
            if ("saf".equals(info.source)
                    && (info.treeUri != null || currentTreeUri != null)) {
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
            removeResultItem(btn);
        } else {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void removeResultItem(View btn) {
        try {
            View card = (View) ((View) btn.getParent()).getParent();
            if (card.getParent() instanceof ViewGroup) {
                ((ViewGroup) card.getParent()).removeView(card);
            }
        } catch (Exception ignored) {
        }
    }

    private void showMlResultDialog(FileRiskInfo info) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ml_results_title))
                .setMessage(getString(R.string.ml_detail_format,
                        info.name, info.mlScore,
                        info.mlReason != null ? info.mlReason : getString(R.string.ml_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void startIconSpin() {
        if (iconSpin != null) iconSpin.cancel();
        iconSpin = ObjectAnimator.ofFloat(homeStar, "rotation", 0f, 360f);
        iconSpin.setDuration(1100);
        iconSpin.setRepeatCount(ValueAnimator.INFINITE);
        iconSpin.start();
    }

    private void stopIconSpin() {
        if (iconSpin != null) {
            iconSpin.cancel();
            iconSpin = null;
        }
        homeStar.setRotation(0f);
    }

    // ---------- 动画测试模式（设置 → 应用信息 → 连点版本号 5 次开启） ----------

    /**
     * 启动固定时长的完整进度动画：进度环从 0 走到 1（三环接力各 1/3），
     * 与实际扫描速度无关；一次扫描只启动一次（组合扫描里文件段/应用段不会重复触发）。
     */
    private void startAnimTestProgress() {
        if (!prefs.isAnimTestMode()) return;
        if (animTestProgress != null) return;   // 本次扫描已启动过，组合扫描不重复触发

        animTestStartMs = android.os.SystemClock.uptimeMillis();
        animTestProgress = ValueAnimator.ofFloat(0f, 1f);
        animTestProgress.setDuration(ANIM_TEST_MS);
        animTestProgress.setInterpolator(new android.view.animation.LinearInterpolator());
        animTestProgress.addUpdateListener(a ->
                homeRings.setProgress((float) a.getAnimatedValue()));
        animTestProgress.start();
    }

    /** 动画测试模式下，距离进度动画播完还剩多少毫秒（已播完返回 0）。 */
    private long animTestRemainingMs() {
        long used = android.os.SystemClock.uptimeMillis() - animTestStartMs;
        return Math.max(0L, ANIM_TEST_MS - used);
    }

    private void cancelAnimTestProgress() {
        if (animTestProgress != null) {
            animTestProgress.cancel();
            animTestProgress = null;
        }
    }

    private int countFiles(File dir) {
        if (dir == null) return 0;
        int[] c = {0};
        countFilesRec(dir, c, 0);
        return c[0];
    }

    private void countFilesRec(File dir, int[] c, int depth) {
        if (c[0] >= COUNT_CAP) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (c[0] >= COUNT_CAP) return;
            if (f.isDirectory()) {
                if (depth < 20) countFilesRec(f, c, depth + 1);
            } else {
                c[0]++;
            }
        }
    }

    private int countTree(Uri treeUri) {
        if (treeUri == null) return 0;
        int[] c = {0};
        countTreeRec(treeUri, treeUri, c, 0);
        return c[0];
    }

    private void countTreeRec(Uri treeUri, Uri dirUri, int[] c, int depth) {
        if (c[0] >= COUNT_CAP) return;
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(dirUri));
        } catch (Exception e) {
            return;
        }
        try (Cursor cur = getContentResolver().query(childrenUri,
                new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext() && c[0] < COUNT_CAP) {
                String docId = cur.getString(0);
                String mime = cur.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 20) {
                        countTreeRec(treeUri, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), c, depth + 1);
                    }
                } else {
                    c[0]++;
                }
            }
        } catch (Exception ignored) {
        }
    }

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
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    /** 模式激活（设置当前模式 + 守护服务），但不跳转到扫描中心界面。 */
    private void activateModeInPlace(int key) {
        requestNotificationPermissionIfNeeded();
        prefs.setActiveMode(key);
        if (key == Prefs.MODE_NORMAL) {
            GuardService.stop(this);
            scheduleDailyScan();
            Toast.makeText(this, R.string.toast_activate_normal, Toast.LENGTH_SHORT).show();
        } else {
            GuardService.start(this, key);
            int toast = key == Prefs.MODE_ACCESSIBILITY ? R.string.toast_activate_accessibility
                    : key == Prefs.MODE_SHIZUKU ? R.string.toast_activate_shizuku
                    : R.string.toast_activate_root;
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 滑动动画 ----------
    // 非线性缓动：进入用「减速」曲线（快速启动后平缓收尾），退出用「加速」曲线（缓慢起步后加速离场）。
    // 两条均为 Material 强调曲线（emphasized decelerate / accelerate），取代原先的匀速位移。
    private static final Interpolator SLIDE_IN_EASE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator SLIDE_OUT_EASE = new PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f);
    private static final long SLIDE_IN_MS = 360L;
    private static final long SLIDE_OUT_MS = 240L;

    private void slideInRight(View v) {
        v.setVisibility(View.VISIBLE);
        int w = getResources().getDisplayMetrics().widthPixels;
        v.setTranslationX(w);
        v.setAlpha(1f);
        v.animate().translationX(0f).setDuration(SLIDE_IN_MS)
                .setInterpolator(SLIDE_IN_EASE).start();
    }

    /** 从下方滑入（结果页用，配合顶部图标上滑收起）。 */
    private void slideInUp(View v) {
        v.setVisibility(View.VISIBLE);
        int h = getResources().getDisplayMetrics().heightPixels;
        v.setTranslationY(h * 0.22f);
        v.setAlpha(0f);
        v.animate().translationY(0f).alpha(1f).setDuration(SLIDE_IN_MS)
                .setInterpolator(SLIDE_IN_EASE).start();
    }

    private void slideOutRight(View v) {
        int w = getResources().getDisplayMetrics().widthPixels;
        v.animate().translationX(w).alpha(0f).setDuration(SLIDE_OUT_MS)
                .setInterpolator(SLIDE_OUT_EASE)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    private void slideOutLeft(View v) {
        int w = getResources().getDisplayMetrics().widthPixels;
        v.animate().translationX(-w).alpha(0f).setDuration(SLIDE_OUT_MS)
                .setInterpolator(SLIDE_OUT_EASE)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    private void hidePanelsExcept(View keep) {
        View[] panels = {modeList, configScope, configFolders, resultArea};
        for (View p : panels) {
            if (p != keep) {
                p.setVisibility(View.GONE);
                p.setAlpha(1f);
                p.setTranslationX(0f);
            }
        }
    }

    // ---------- 结果渲染辅助 ----------

    private String displayPath(FileRiskInfo info) {
        return "saf".equals(info.source) ? getString(R.string.file_source_saf, info.name) : info.path;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            // 多选页：每次选择只把文件夹追加到列表，不立即开始扫描
            addSelectedFolder(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingGlobalScan = false;
            // 授权后按勾选项重新编排（内部会清掉上一轮的两段结果）
            startCombinedScan();
        }
    }

    // ---------- 立即扫描（应用级，机械判定） ----------

    private boolean isDeepMode() {
        return prefs.isDeepScanMode();
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
        loadGuardDashboard();
        // 从「所有文件访问」设置页返回后，若已授权则继续组合扫描
        if (pendingGlobalScan && hasFullStorageAccess()) {
            pendingGlobalScan = false;
            startCombinedScan();
        }
    }

    private void loadGuardDashboard() {
        // 根据时间显示问候语 + 设备名
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 6 && hour < 12) {
            greeting = getString(R.string.guard_dashboard_greeting_morning);
        } else if (hour >= 12 && hour < 18) {
            greeting = getString(R.string.guard_dashboard_greeting_afternoon);
        } else {
            greeting = getString(R.string.guard_dashboard_greeting_evening);
        }
        guardGreetingText.setText(greeting + " User");

        // 加载守护状态
        int activeMode = prefs.getActiveMode();
        if (activeMode == Prefs.MODE_NONE) {
            guardStatusText.setText(R.string.guard_status_idle);
            guardModeText.setText(R.string.guard_dashboard_mode_none);
        } else {
            guardStatusText.setText(R.string.mode_status_active);
            String modeName;
            switch (activeMode) {
                case Prefs.MODE_NORMAL:
                    modeName = getString(R.string.mode_normal_name);
                    break;
                case Prefs.MODE_ACCESSIBILITY:
                    modeName = getString(R.string.mode_accessibility_name);
                    break;
                case Prefs.MODE_SHIZUKU:
                    modeName = getString(R.string.mode_shizuku_name);
                    break;
                case Prefs.MODE_ROOT:
                    modeName = getString(R.string.mode_root_name);
                    break;
                default:
                    modeName = getString(R.string.mode_normal_name);
            }
            guardModeText.setText(getString(R.string.guard_dashboard_mode_active, modeName));
        }

        // 加载最近扫描时间
        long lastScanMs = prefs.getLastScanMs();
        if (lastScanMs > 0) {
            long diff = System.currentTimeMillis() - lastScanMs;
            String timeAgo;
            if (diff < 60_000) {
                timeAgo = getString(R.string.guard_dashboard_time_just_now);
            } else if (diff < 3_600_000) {
                timeAgo = getString(R.string.guard_dashboard_time_minutes, (int) (diff / 60_000));
            } else if (diff < 86_400_000) {
                timeAgo = getString(R.string.guard_dashboard_time_hours, (int) (diff / 3_600_000));
            } else {
                timeAgo = getString(R.string.guard_dashboard_time_days, (int) (diff / 86_400_000));
            }
            lastGuardTime.setText(timeAgo);
        } else {
            lastGuardTime.setText(getString(R.string.guard_dashboard_time_never));
        }

        // 加载累计守护次数
        int totalScans = prefs.getTotalScanCount();
        totalGuardCount.setText(String.valueOf(totalScans));

        // 加载最近扫描结果
        loadRecentScanResult();

        // 加载最近添加文件
        loadRecentFiles();
    }

    private void loadRecentScanResult() {
        int total = prefs.getLastScanTotal();
        if (total < 0) {
            recentScanResult.setText(getString(R.string.guard_dashboard_no_scan_detail));
            return;
        }

        int safe = prefs.getLastScanSafe();
        int suspicious = prefs.getLastScanSuspicious();
        int malicious = prefs.getLastScanMalicious();

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.guard_dashboard_scan_total, total)).append("\n");
        sb.append(getString(R.string.guard_dashboard_scan_safe, safe)).append("\n");
        if (suspicious > 0) {
            sb.append(getString(R.string.guard_dashboard_scan_suspicious, suspicious)).append("\n");
        }
        if (malicious > 0) {
            sb.append(getString(R.string.guard_dashboard_scan_malicious, malicious));
        }
        recentScanResult.setText(sb.toString().trim());
    }

    private void loadRecentFiles() {
        recentFilesList.removeAllViews();

        java.util.List<String> recentFiles = new java.util.ArrayList<>();
        String[] projection = {
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.DATE_ADDED,
                android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE
        };
        String selection = android.provider.MediaStore.Files.FileColumns.DATE_ADDED + " > 0";
        String sortOrder = android.provider.MediaStore.Files.FileColumns.DATE_ADDED + " DESC";

        try (android.database.Cursor cursor = getContentResolver().query(
                android.provider.MediaStore.Files.getContentUri("external"),
                projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndexOrThrow(
                        android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME);
                int typeIdx = cursor.getColumnIndexOrThrow(
                        android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE);
                while (cursor.moveToNext() && recentFiles.size() < 5) {
                    String name = cursor.getString(nameIdx);
                    int mediaType = cursor.getInt(typeIdx);
                    if (name != null && !name.isEmpty() && mediaType != 0) {
                        recentFiles.add(name);
                    }
                }
            }
        }

        if (recentFiles.isEmpty()) {
            noRecentFilesText.setVisibility(View.VISIBLE);
            recentFilesList.setVisibility(View.GONE);
            return;
        }

        noRecentFilesText.setVisibility(View.GONE);
        recentFilesList.setVisibility(View.VISIBLE);

        for (String file : recentFiles) {
            TextView tv = new TextView(this);
            tv.setText("• " + file);
            tv.setTextSize(13);
            tv.setTextColor(getResources().getColor(R.color.md_theme_onSurfaceVariant, getTheme()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 4;
            tv.setLayoutParams(lp);
            recentFilesList.addView(tv);
        }
    }

    private void refreshStatus() {
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
        if (pullPanel != null) pullPanel.release();
        super.onDestroy();
    }

    // ---------- 紧急遇险：应用在前台时也能用三击音量上键逃生 ----------

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (PolarisAccessibilityService.handleVolumeUp(this)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
