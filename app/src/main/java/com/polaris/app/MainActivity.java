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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.color.DynamicColors;
import com.polaris.app.R;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.AppScanRunner;
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
import com.polaris.app.scan.Notifier;
import com.polaris.app.util.PermUtil;
import com.polaris.app.view.ObservatoryIntroView;
import com.polaris.app.view.RocketIntroView;
import com.polaris.app.view.StarMapView;
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

     static final int REQ_NOTIFICATION = 10;

     LinearLayout modeList;
     LinearLayout modeResume;
     com.google.android.material.button.MaterialButton btnResumeScan;
    /** 快捷面板「拦截」按钮：深层扫描模式显示，直达扫描中心拦截板块。 */
     com.google.android.material.button.MaterialButton btnResumeBlock;
     com.google.android.material.button.MaterialButton btnPickBack;
     StarMapView starMap;
     com.google.android.material.slider.Slider zoomSlider;
    // 恒星详情面板（点按星图恒星 → 从右划出，占满整个高度）+ 其迷你星图与内容视图
     android.view.View starDetailPanel;
     StarMapView starDetailMap;
    private android.widget.TextView starDetailName, starDetailSub, starDetailType;
    private android.widget.TextView starDetailSize, starDetailRisk;
    private android.widget.TextView starDetailChildren, starDetailTime, starDetailAdvice;
    private android.widget.TextView starDetailPlanets, starDetailMag, starDetailTemp, starDetailSpec;
    private android.widget.TextView starDetailRadius, starDetailMass;
     android.widget.LinearLayout starDetailOrbit;
    // 信息行左侧标签（卫星详情模式会改写文字，恒星模式恢复标准文案）
    private android.widget.TextView starDetailTypeLabel, starDetailSizeLabel;
    private android.widget.TextView starDetailChildrenLabel, starDetailTimeLabel;
    /** 星体/星系参数区（迷你星图解算）：卫星详情模式下整体隐藏。 */
     android.view.View starDetailFacts;
     com.google.android.material.button.MaterialButton starDetailOpenBtn;
    /** 抽屉贴底操作区容器（细分隔线 + 「打开」按钮）：非应用段整体隐藏。 */
     android.widget.LinearLayout starDetailActionBar;
    /** 当前详情抽屉对应的星键（app:<pkg> / folder:<键>#<后缀>），供「打开」定位对象。 */
     String starDetailKey;
    /** 最近一次扫描出结果的时间戳（0 = 尚未扫描）。 */
     long lastScanFinishedAt = 0L;
     android.widget.LinearLayout starDetailDots;
    /** 名称右侧淡出遮罩（渐变透明 → 抽屉底色），名字过长时自然淡出。 */
     android.view.View starDetailNameFade;
     boolean starDetailOpen = false;
    // 底部双进度条：全局（整体扫描）+ 局部（当前对象：文件夹/应用）
     android.view.View scanProgressPanel;
     android.widget.TextView scanObjText;
     android.view.View progressGlobalBar;
     android.view.View progressLocalBar;
    private final java.util.HashMap<String, Integer> folderTotals = new java.util.HashMap<>();
    private final java.util.HashMap<String, Integer> folderDone = new java.util.HashMap<>();
     float globalFraction = 0f, localFraction = 0f;
     String scanObjName = "";
     long lastProgressUiMs = 0;
    // 结果面板：扫描完成后显示入口块（竖屏底部横块 / 横屏右缘竖块），点击拉出（星图禁交互）
     android.view.View resultEntryBar;
     boolean resultSheetShown = false;
    /** 扫描完成后是否进入结果界面（临时开关：false = 保留星图交互，不弹结果）。 */
    /** 扫描完成后渲染主页内嵌结果面板（上滑查看）。 */
     static final boolean SCAN_RESULT_ENABLED = true;
     com.google.android.material.button.MaterialButton btnPickMode;
    // 竖屏选择流程状态：按钮两态（选模式 ⇄ 下一步）与当前选中的模式卡
     boolean modeSelecting = false;
     int selectedModeKey = -1;
     com.google.android.material.card.MaterialCardView selectedModeCard;
    // 点击图标后上方浮现的详细面板：单卡，上半部检查逻辑区可收起/展开（收回时=紧凑模式卡）
     LinearLayout modeDetail;
     com.google.android.material.card.MaterialCardView detailCard;
    // 卡内可收起的「检查逻辑」区
     View checkSection;
     LinearLayout checkSteps;
     boolean checkExpanded = true;
    // 模式就绪状态缓存（refreshStatus 填充；detail 面板按选中模式展示）
     static final int ST_CHECKING = 0, ST_READY = 1, ST_ACTIVE = 2, ST_NEED_AUTH = 3;
     final int[] modeStatusState = new int[MODE_KEYS.length];
     ViewGroup behaviorRows;
     TextView behaviorEmpty;
     Prefs prefs;
     boolean pendingShizukuActivation = false;

     DrawerLayout drawerLayout;
     TextView guardGreetingText;
     TextView guardStatusText;
     TextView guardModeText;
     TextView lastGuardTime;
     TextView totalGuardCount;
     TextView recentScanResult;
     LinearLayout recentFilesList;
     TextView noRecentFilesText;

    // ---------- 主页滑动向导（点模式不再开新界面） ----------
     static final int REQ_OPEN_TREE = 200;
     static final int REQ_STORAGE = 300;
     int pendingModeKey = -1;
     Uri currentTreeUri = null;
     boolean pendingGlobalScan = false;

    /** 范围勾选：全盘 / 已安装应用可同时选中；「选择文件夹」与两者互斥。 */
     boolean scopeGlobal = true;
     boolean scopeApps = false;
     boolean scopeFolder = false;

    /** 本次扫描收集的两段结果（文件级 + 应用级）。 */
     List<FileRiskInfo> lastFileRisks;
     List<AppRiskInfo> lastAppRisks;
     List<AiClient.AiVerdict> lastAppVerdicts;
    /** 文件段元信息：扫描范围描述（全盘 / 所选文件夹）与文件总数（结果页文件栏显示）。 */
     String lastFileScopeLabel;
     int lastFileScanCount;

    /** 文件夹扫描：用户可添加多个 SAF 授权树，扫描时逐个遍历。 */
    private final List<Uri> selectedFolders = new ArrayList<>();

    private View stageArea, configScope, configFolders, resultArea;
     View contentRoot;

    /** 模式状态探测的共享后台执行器（避免每次 onResume 新建/关闭线程池）。 */
    private static final ExecutorService STATUS_EXEC = Executors.newSingleThreadExecutor();

    /** 浅层扫描（Normal）入场：主题色地面 → 天文台 → 穹顶展开 → 望远镜伸出 → 视角向下。 */
     ObservatoryIntroView observatoryIntro;
    /** 深层扫描模式（无障碍/Shizuku/Root）的火箭发射入场动画层。 */
     RocketIntroView rocketIntro;
    /** 深扫发射末态卫星释放点（屏幕坐标）：runCombinedScan 里 resetScanField 之后再入轨，
     *  保证锚定时 pan=0/scale=1（世界=屏幕），交棒位置零跳变。 */
     float[] pendingSatRelease;

    /** 拦截模式地下功能面板容器（点击拦截按钮后由火箭动画揭示，常驻在星图下方）。 */
     android.widget.FrameLayout interceptUnderground;
    android.widget.TextView itcMonitorState, itcCount;
     android.widget.LinearLayout itcPermList;
     android.widget.TextView itcPermTitle;
     android.widget.LinearLayout itcBlockList;   // 面板内已拦截应用列表（内联）
     android.widget.TextView itcBlockEmpty;      // 面板内空态提示
     android.widget.EditText itcBlockSearch;     // 面板内搜索框
     com.google.android.material.button.MaterialButton itcAddApp; // 面板内添加应用
     String itcBlockQuery = "";                  // 面板内搜索关键词（小写）
     com.google.android.material.materialswitch.MaterialSwitch itcToggle;
     android.widget.ImageButton itcClose;
     android.widget.ImageButton itcExpand;      // 面板全屏展开/收起（空心三角）
     android.widget.ImageButton itcBlockFold;   // 应用列表折叠按钮（560dp 与全屏两种高度都显示）
     android.widget.LinearLayout itcBlockBody;  // 应用列表可折叠容器
     boolean itcExpanded = false;               // 面板是否已全屏展开
     boolean itcBlockFolded = false;            // 应用列表是否已折叠
     boolean interceptActive;   // 拦截面板是否处于展开/激活态
     boolean itcProgrammatic;    // 编程设置开关时跳过监听器
     boolean interceptFlowActive; // 是否正处于拦截火箭动画流程（区别于普通深扫范围流程）
    /** 天文台入场动画是否已播过（每会话只播一次，避免反复选 Normal 都被拖 2.6s）。 */
     boolean introPlayed;
     ViewGroup folderListContainer;
     LinearLayout appResultList;
     TextView folderEmptyText;
     TextView scanStatusText;
    /** 分数右侧简要说明：文件风险（N）/ 应用风险（M），逐段显示。 */
    private TextView resultBriefFiles, resultBriefApps;
    /** 双段都有结果时显示的文件/应用分栏（hero 区）。 */
     com.google.android.material.button.MaterialButtonToggleGroup resultTabs;
    private LinearLayout filePanel, appPanel;
    private TextView fileMetaText, fileScoreText, appMetaText, appScoreText;
    /** 结果页顶部百分制评分：大号主题色分数 + 灰色 /100 后缀。 */
    private TextView resultScore, resultScoreMax;
     View resultScoreRow;
    /** 结果页顶部「向下收回」按钮：点击把结果页滑回星图。 */
     View resultCollapseBtn;
    /** 顶部大图标（含 3 圈环）：进结果页时上滑收起，离开时恢复。 */
     View homeAppIcon;
     TextView homeGreeting;
     int homeIconSize = 0;

    /** 动画测试模式：进度与旋转按固定时长完整播放一遍，与实际扫描速度解耦。 */
    private final com.polaris.app.util.AnimTestProgress animTest = new com.polaris.app.util.AnimTestProgress();
     LinearLayout resultList;
    /** 应用段折叠组句柄，用于「先展开后自动收回」的微交互。 */
     SectionHold appSectionHold;
     Runnable appAutoCollapse;
     boolean appPeekShown;        // 展开自动收回只在当前结果页第一次展示时播放
     static final long APP_PEEK_MS = 500L;
     com.polaris.app.view.ConcentricRingsView homeRings;
     ImageView homeStar;
     FileScanner scanner;
     QuarantineManager qm;
     ObjectAnimator iconSpin;
     int totalToScan = 1;
     int scannedCount = 0;
     boolean scanning = false;
     static final int COUNT_CAP = 200000;

    // ---------- 下拉面板（应用使用的权限 + 最近添加文件） ----------
    // 下拉面板（全屏承载工具箱：权限使用 / 最近文件 / 单文件扫描）
     View pullOverlay;
     ToolsPanelView pullPanel;

     boolean pullOpen = false;
     int pullSheetHeight = 0;

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

        // 已选定守护模式：主页直接显示「以（模式）开始扫描」快捷面板（不再跳扫描中心）；
        // 「重新配置模式」按钮切回模式选择列表；旧「重新配置」入口仍带 reconfigure 标记
        boolean reconfigure = getIntent().getBooleanExtra("reconfigure", false);
        boolean fromOnboarding = getIntent().getBooleanExtra("from_onboarding", false);
        boolean resumeMode = !reconfigure && !fromOnboarding && prefs.getActiveMode() != Prefs.MODE_NONE;

        modeList = findViewById(R.id.modeList);
        modeResume = findViewById(R.id.modeResume);
        btnResumeScan = findViewById(R.id.btnResumeScan);
        btnResumeBlock = findViewById(R.id.btnResumeBlock);
        behaviorRows = findViewById(R.id.behaviorRows);
        behaviorEmpty = findViewById(R.id.behaviorEmpty);

        // 滑动向导相关视图绑定
        stageArea = findViewById(R.id.stageArea);
        contentRoot = findViewById(R.id.contentRoot);
        observatoryIntro = findViewById(R.id.observatoryIntro);
        rocketIntro = findViewById(R.id.rocketIntro);
        interceptUnderground = findViewById(R.id.interceptUnderground);
        if (interceptUnderground != null) {
            itcToggle = interceptUnderground.findViewById(R.id.itcToggle);
            itcMonitorState = interceptUnderground.findViewById(R.id.itcMonitorState);
            itcCount = interceptUnderground.findViewById(R.id.itcCount);
            itcBlockList = interceptUnderground.findViewById(R.id.itcBlockList);
            itcBlockEmpty = interceptUnderground.findViewById(R.id.itcBlockEmpty);
            itcBlockSearch = interceptUnderground.findViewById(R.id.itcSearch);
            itcAddApp = interceptUnderground.findViewById(R.id.itcAddApp);
            if (itcAddApp != null) itcAddApp.setOnClickListener(v -> showBlockAppPicker());
            if (itcBlockSearch != null) {
                itcBlockSearch.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(android.text.Editable s) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                        itcBlockQuery = s.toString().trim().toLowerCase();
                        renderInterceptBlockList();
                    }
                });
            }
            itcPermList = interceptUnderground.findViewById(R.id.itcPermList);
            itcPermTitle = interceptUnderground.findViewById(R.id.itcPermTitle);
            itcClose = interceptUnderground.findViewById(R.id.itcClose);
            itcExpand = interceptUnderground.findViewById(R.id.itcExpand);
            itcBlockFold = interceptUnderground.findViewById(R.id.itcBlockFold);
            itcBlockBody = interceptUnderground.findViewById(R.id.itcBlockBody);
            itcToggle.setOnCheckedChangeListener((v, on) -> onInterceptToggle(on));
            itcClose.setOnClickListener(v -> hideInterceptPanel());
            if (itcExpand != null) itcExpand.setOnClickListener(v -> toggleInterceptExpand());
            if (itcBlockFold != null) itcBlockFold.setOnClickListener(v -> toggleInterceptBlockFold());
        }
        configScope = findViewById(R.id.configScope);
        configFolders = findViewById(R.id.configFolders);
        resultArea = findViewById(R.id.resultArea);
        folderListContainer = findViewById(R.id.folderListContainer);
        folderEmptyText = findViewById(R.id.folderEmptyText);
        scanStatusText = findViewById(R.id.scanStatusText);
        resultBriefFiles = findViewById(R.id.resultBriefFiles);
        resultBriefApps = findViewById(R.id.resultBriefApps);
        resultTabs = findViewById(R.id.resultTabs);
        if (resultTabs != null) {
            resultTabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                boolean files = checkedId == R.id.tabFiles;
                filePanel.setVisibility(files ? View.VISIBLE : View.GONE);
                appPanel.setVisibility(files ? View.GONE : View.VISIBLE);
                // 切到应用段时，重放「先展开后自动收回」的微交互
                if (!files) runAppPeek();
            });
        }
        filePanel = findViewById(R.id.filePanel);
        appPanel = findViewById(R.id.appPanel);
        fileMetaText = findViewById(R.id.fileMetaText);
        fileScoreText = findViewById(R.id.fileScoreText);
        appMetaText = findViewById(R.id.appMetaText);
        appScoreText = findViewById(R.id.appScoreText);
        resultScore = findViewById(R.id.resultScore);
        resultScoreMax = findViewById(R.id.resultScoreMax);
        resultCollapseBtn = findViewById(R.id.resultCollapse);
        if (resultCollapseBtn != null) {
            resultCollapseBtn.setOnClickListener(v -> collapseResultSheet());
        }
        resultList = findViewById(R.id.resultList);
        appResultList = findViewById(R.id.appResultList);
        resultScoreRow = findViewById(R.id.resultScoreRow);
        applyResultSheetStyle();
        if (filePanel != null) filePanel.setBackground(makePanelBg());
        if (appPanel != null) appPanel.setBackground(makePanelBg());
        homeRings = findViewById(R.id.homeRings);
        homeStar = findViewById(R.id.homeStar);
        scanner = new FileScanner(this);
        qm = new QuarantineManager(this);

        findViewById(R.id.scopeOptGlobal).setOnClickListener(v -> toggleScope("global"));
        findViewById(R.id.scopeOptFolder).setOnClickListener(v -> toggleScope("folder"));
        findViewById(R.id.scopeOptApps).setOnClickListener(v -> toggleScope("apps"));
        // 「下一步」仅在勾选「选择文件夹」时可见，点击进入文件夹多选页（复用 onScopeStart 的 folder 分支）
        findViewById(R.id.scopeStart).setOnClickListener(v -> onScopeStart());
        findViewById(R.id.scopeBack).setOnClickListener(v -> backToModes());
        findViewById(R.id.folderAddButton).setOnClickListener(v -> pickFolder());
        findViewById(R.id.folderStart).setOnClickListener(v -> startFolderScan());
        findViewById(R.id.folderBack).setOnClickListener(v -> backToScope());
        findViewById(R.id.resultBack).setOnClickListener(v -> {
            closeResultSheet();
            backToModes();
        });
        findViewById(R.id.resultAgain).setOnClickListener(v -> enterScopeStep());
        resetScope();
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerLayout.setScrimColor(0x00000000);

        // 头部徽标：固定尺寸（80dp 容器 / 72dp 星标，均写在布局里）；
        // homeIconSize 供 collapseHomeIcon/expandHomeIcon 尺寸动画基准使用
        homeAppIcon = findViewById(R.id.homeAppIcon);
        homeIconSize = (int) (80 * getResources().getDisplayMetrics().density + 0.5f);
        homeGreeting = findViewById(R.id.homeGreeting);
        updateHomeGreeting();
        // 欢迎文字入场：从左往右淡入
        homeGreeting.setAlpha(0f);
        homeGreeting.setTranslationX(-48f);
        homeGreeting.animate().alpha(1f).translationX(0f)
                .setStartDelay(120).setDuration(600)
                .setInterpolator(new DecelerateInterpolator()).start();

        // 四角星入场：轻微放大 + 淡入（与问候语错峰，营造星空主题的高级感）
        if (homeStar != null) {
            homeStar.setAlpha(0f);
            homeStar.setScaleX(0.82f);
            homeStar.setScaleY(0.82f);
            homeStar.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(80).setDuration(720)
                    .setInterpolator(com.polaris.app.util.UiFx.FADE_EASE).start();
        }
        applyHomeStarGlow();

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
        btnResumeScan.setOnClickListener(v -> enterScopeStep());
        // 「拦截」：播放火箭动画（单发 + 对接舱与卫星对接），地面抬升后在地面以下揭示功能面板，
        // 不再打开独立拦截页（面板常驻星图下方，替代原拦截页）
        btnResumeBlock.setOnClickListener(v -> startInterceptFlow());
        findViewById(R.id.btnReconfigureMode).setOnClickListener(v -> showModeListFromResume());
        // 竖屏左下角「选择模式以开始扫描」：两态按钮——
        // 未展开：平移到右下角 + 文字变「下一步」+ 模式列表滑入；
        // 已展开（下一步）：校验选中 → 前置检查 → 激活并进入范围页
        btnPickMode = findViewById(R.id.btnPickMode);
        btnPickBack = findViewById(R.id.btnPickBack);
        starMap = findViewById(R.id.starMap);
        // 星图缩放条：滑条→以视图中心为焦点缩放；捏合手势→回调同步滑条（差值防循环）
        zoomSlider = findViewById(R.id.zoomSlider);
        if (zoomSlider != null) {
            zoomSlider.addOnChangeListener((s, v, fromUser) -> {
                if (fromUser) starMap.setZoom(v);
            });
            starMap.setOnZoomListener(z -> {
                if (Math.abs(zoomSlider.getValue() - z) > 0.001f) zoomSlider.setValue(z);
            });
        }
        // 恒星详情面板：点按恒星从右划出全高面板（迷你星图 + 类型/大小/安全程度）
        starDetailPanel = findViewById(R.id.starDetailPanel);
        if (starDetailPanel != null) {
            starDetailMap = starDetailPanel.findViewById(R.id.starDetailMap);
            starDetailName = starDetailPanel.findViewById(R.id.starDetailName);
            starDetailSub = starDetailPanel.findViewById(R.id.starDetailSub);
            starDetailType = starDetailPanel.findViewById(R.id.starDetailType);
            starDetailSize = starDetailPanel.findViewById(R.id.starDetailSize);
            starDetailRisk = starDetailPanel.findViewById(R.id.starDetailRisk);
            starDetailDots = starDetailPanel.findViewById(R.id.starDetailDots);
            starDetailNameFade = starDetailPanel.findViewById(R.id.starDetailNameFade);
            starDetailChildren = starDetailPanel.findViewById(R.id.starDetailChildren);
            starDetailTime = starDetailPanel.findViewById(R.id.starDetailTime);
            starDetailAdvice = starDetailPanel.findViewById(R.id.starDetailAdvice);
            starDetailOpenBtn = starDetailPanel.findViewById(R.id.starDetailOpen);
            starDetailActionBar = starDetailPanel.findViewById(R.id.starDetailActionBar);
            starDetailTypeLabel = starDetailPanel.findViewById(R.id.starDetailTypeLabel);
            starDetailSizeLabel = starDetailPanel.findViewById(R.id.starDetailSizeLabel);
            starDetailChildrenLabel = starDetailPanel.findViewById(R.id.starDetailChildrenLabel);
            starDetailTimeLabel = starDetailPanel.findViewById(R.id.starDetailTimeLabel);
            starDetailFacts = starDetailPanel.findViewById(R.id.starDetailFacts);
            starDetailPlanets = starDetailPanel.findViewById(R.id.starDetailPlanets);
            starDetailMag = starDetailPanel.findViewById(R.id.starDetailMag);
            starDetailTemp = starDetailPanel.findViewById(R.id.starDetailTemp);
            starDetailSpec = starDetailPanel.findViewById(R.id.starDetailSpec);
            starDetailRadius = starDetailPanel.findViewById(R.id.starDetailRadius);
            starDetailMass = starDetailPanel.findViewById(R.id.starDetailMass);
            starDetailOrbit = starDetailPanel.findViewById(R.id.starDetailOrbit);
            if (starDetailMap != null) {
                starDetailMap.setSoloStarMode(true);   // 抽屉头部只画一颗恒星（同时关掉交互）
            }
            if (starDetailOpenBtn != null) {
                starDetailOpenBtn.setOnClickListener(v -> openStarDetailTarget());
            }
            applyStarNameFade();
            starDetailPanel.findViewById(R.id.starDetailClose)
                    .setOnClickListener(v -> hideStarDetail());
            applyStarDetailGeometry(false);
        }
        // 底部双进度条（全局 + 当前对象局部）
        scanProgressPanel = findViewById(R.id.scanProgressPanel);
        scanObjText = findViewById(R.id.scanObjText);
        progressGlobalBar = findViewById(R.id.progressGlobalBar);
        progressLocalBar = findViewById(R.id.progressLocalBar);
        // 结果入口块（竖屏底部横块 / 横屏右缘竖块）
        resultEntryBar = findViewById(R.id.resultEntryBar);
        if (resultEntryBar != null) {
            resultEntryBar.setOnClickListener(v -> showResultSheet());
        }
        // 已取消「上滑打开结果」手势：结果面板只由底部入口块点击唤起。
        applyEntryBarOrientation();
        starMap.setOnStarTapListener((key, label, bytes, level, isApp, children, childMaxLevel) -> {
            if (key == null) {           // 点空白处
                hideStarDetail();
                return;
            }
            showStarDetail(key, label, bytes, level, isApp, children, childMaxLevel);
        });
        // 点按常驻卫星（深扫入轨后）→ 同一侧边详情抽屉展示卫星信息
        starMap.setOnSatelliteTapListener(this::showSatelliteDetail);
        if (btnPickBack != null) {
            btnPickBack.setOnClickListener(v -> exitPickingState());
        }
        if (btnPickMode != null) {
            btnPickMode.setOnClickListener(v -> {
                if (!modeSelecting) {
                    enterPickingState();
                } else {
                    if (selectedModeKey < 0 || selectedModeCard == null) {
                        Toast.makeText(this, R.string.mode_pick_first, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int key = selectedModeKey;
                    if (!ensureModePrereq(key)) return;
                    enterScopeForMode(key);
                }
            });
        }

        // 冷启动静态决定：已有激活模式 → 快捷面板；竖屏无激活模式 → 左下角选择按钮；横屏 → 模式列表
        if (resumeMode) {
            if (btnPickMode != null) btnPickMode.setVisibility(View.GONE);
            modeList.setVisibility(View.GONE);
            modeResume.setVisibility(View.VISIBLE);
            btnResumeScan.setText(getString(R.string.mode_resume_scan, modeDisplayName(prefs.getActiveMode())));
            // 「拦截」按钮仅深层扫描模式（无障碍/Shizuku/Root）显示
            btnResumeBlock.setVisibility(prefs.isDeepScanMode() ? View.VISIBLE : View.GONE);
            refreshBehaviorPanel();
        } else if (btnPickMode != null) {
            // 竖屏：无激活模式 → 左下角「选择模式以开始扫描」按钮（XML 默认显示），模式列表隐藏
            modeList.setVisibility(View.GONE);
        }

        // 头部：展开侧栏
        findViewById(R.id.btnDrawer).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));
        // 工具箱入口：已从头部移入侧滑抽屉底部（drawerToolbox）
        findViewById(R.id.drawerToolbox).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            openPullDown();
        });

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
        // 天文台/火箭入场播放中按返回：直接落到范围选择，别把人困在星图上
        if (observatoryIntro != null && observatoryIntro.isShowing()) {
            skipObservatoryIntro();
            return;
        }
        if (rocketIntro != null && rocketIntro.isShowing()) {
            skipRocketIntro();
            return;
        }
        // 拦截功能面板展开时：返回键先收起面板，回到模式快捷面板
        if (interceptActive) {
            hideInterceptPanel();
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
        // 竖屏（有 btnPickMode）：一行四个纯图标按钮，点击后上方浮现详细面板；横屏保持原卡直接激活
        final boolean compact = findViewById(R.id.btnPickMode) != null;

        for (int i = 0; i < MODE_KEYS.length; i++) {
            final int key = MODE_KEYS[i];
            View card;
            if (compact) {
                card = inflater.inflate(R.layout.item_mode_icon, modeList, false);
                ((ImageView) card.findViewById(R.id.modeIcon)).setImageResource(MODE_ICON_RES[i]);
                card.setOnClickListener(v -> selectMode(key,
                        (com.google.android.material.card.MaterialCardView) card));
            } else {
                card = inflater.inflate(R.layout.item_mode_card, modeList, false);
                bindModeCardContent(card, i);
                card.setOnClickListener(v -> onModePicked(key));
            }
            modeList.addView(card);
        }

        if (compact) {
            // 详细面板单例（单卡）：检查逻辑区在卡内上方，收回时整卡缩为紧凑模式卡
            modeDetail = findViewById(R.id.modeDetail);
            detailCard = (com.google.android.material.card.MaterialCardView)
                    inflater.inflate(R.layout.item_mode_detail, modeDetail, false);
            checkSection = detailCard.findViewById(R.id.checkSection);
            checkSteps = detailCard.findViewById(R.id.checkSteps);
            modeDetail.addView(detailCard);
            // 点击卡片：收回/再次展开检查逻辑（初始自动展开）
            detailCard.setOnClickListener(v -> toggleCheckExpand());
        }
    }

    /** 填充模式卡内容：图标/名称/介绍/状态/等级条（横屏列表卡与竖屏详细面板共用）。 */
    private void bindModeCardContent(View card, int i) {
        int key = MODE_KEYS[i];
        ((ImageView) card.findViewById(R.id.modeIcon)).setImageResource(MODE_ICON_RES[i]);
        ((TextView) card.findViewById(R.id.modeName)).setText(MODE_NAME_RES[i]);
        TextView desc = card.findViewById(R.id.modeDesc);
        if (desc != null) desc.setText(MODE_DESC_RES[i]);

        // 能力等级条：点亮前 i+1 个点
        int dotIds[] = {R.id.powerDot1, R.id.powerDot2, R.id.powerDot3, R.id.powerDot4};
        for (int d = 0; d < dotIds.length; d++) {
            View dot = card.findViewById(dotIds[d]);
            dot.setBackgroundResource(d < i + 1
                    ? R.drawable.bg_power_dot_active
                    : R.drawable.bg_power_dot);
        }
        refreshCardStatus(card, key);
    }

    /** 按 modeStatusState 缓存刷新指定卡的状态徽章文本。 */
    private void refreshCardStatus(View card, int key) {
        TextView status = card.findViewById(R.id.modeStatus);
        if (status == null) return;
        int idx = -1;
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i] == key) idx = i;
        if (idx < 0) return;
        status.setText(statusTextFor(modeStatusState[idx]));
    }

    private int statusTextFor(int state) {
        switch (state) {
            case ST_READY: return R.string.mode_status_ready;
            case ST_ACTIVE: return R.string.mode_status_active;
            case ST_NEED_AUTH: return R.string.mode_status_need_auth;
            default: return R.string.mode_status_checking;
        }
    }

    /** 竖屏选择展开态：图标行、「下一步」「返回」均从左侧滑入。
     *  返回为文字样式（TextButton），位于下一步左侧；点击回上一态。 */
    private void enterPickingState() {
        // 重新配置模式回到模式选择：结果入口块只属于「星区」视图，离开即隐藏
        if (resultEntryBar != null) resultEntryBar.setVisibility(View.GONE);
        if (detailCard != null) {
            detailCard.animate().cancel();
            detailCard.setTranslationX(0f);
            detailCard.setTranslationY(0f);
        }
        modeSelecting = true;
        selectedModeKey = -1;
        selectedModeCard = null;
        setZoomSliderVisible(false);   // 模式选择中不显示缩放条
        hidePanelsExcept(modeList);
        if (modeDetail != null) modeDetail.setVisibility(View.GONE);
        if (modeList != null) {
            for (int i = 0; i < modeList.getChildCount(); i++) {
                setCardSelected(modeList.getChildAt(i), false);
            }
            // 列表底部留出按钮高度+间距，避免图标行与右下角按钮重叠
            android.util.DisplayMetrics dms = getResources().getDisplayMetrics();
            modeList.setPadding(modeList.getPaddingLeft(), modeList.getPaddingTop(),
                    modeList.getPaddingRight(), (int) (56 * dms.density + 0.5f));
            slideInLeft(modeList);
        }
        if (btnPickMode == null) return;
        btnPickMode.animate().cancel();
        btnPickMode.setText(R.string.scan_next);
        btnPickMode.setVisibility(View.VISIBLE);
        btnPickMode.setAlpha(1f);
        btnPickMode.setTranslationX(0f);
        btnPickMode.setTranslationY(0f);
        if (btnPickBack != null) {
            btnPickBack.setVisibility(View.VISIBLE);
            btnPickBack.setAlpha(1f);
            btnPickBack.setTranslationX(0f);
            btnPickBack.setTranslationY(0f);
        }
        // post：等「下一步」短文案重排完成后再按新宽度定位；两按钮均从左侧滑入
        btnPickMode.post(() -> {
            if (btnPickMode == null) return;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int pad = (int) (20 * dm.density + 0.5f);
            float tx = dm.widthPixels - btnPickMode.getWidth() - 2 * pad;
            btnPickMode.setTranslationX(tx - dm.widthPixels);
            btnPickMode.animate().translationX(tx).setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS)
                    .setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
        });
        if (btnPickBack != null) {
            btnPickBack.post(() -> {
                if (btnPickBack == null || btnPickMode == null) return;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                // 返回按钮目标位：下一步按钮左侧 8dp（自身布局位与下一步同位，左移其宽度+间距）
                float txBack = -(btnPickMode.getWidth() + 8 * dm.density);
                btnPickBack.setTranslationX(txBack - dm.widthPixels);
                btnPickBack.animate().translationX(txBack).setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS)
                        .setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
            });
        }
    }

    /** 展开态「返回」：图标行滑出，回到上一态（有激活模式→快捷面板；否则左下角选择按钮）。 */
    private void exitPickingState() {
        modeSelecting = false;
        selectedModeKey = -1;
        selectedModeCard = null;
        if (modeDetail != null) modeDetail.setVisibility(View.GONE);
        slideOutLeft(modeList);
        if (btnPickBack != null) {
            btnPickBack.animate().cancel();
            btnPickBack.setVisibility(View.GONE);
            btnPickBack.setTranslationX(0f);
            btnPickBack.setTranslationY(0f);
        }
        if (btnPickMode != null) {
            btnPickMode.animate().cancel();
            btnPickMode.setVisibility(View.GONE);
            btnPickMode.setTranslationX(0f);
            btnPickMode.setTranslationY(0f);
            btnPickMode.setText(R.string.mode_pick_to_start);
        }
        if (prefs.getActiveMode() != Prefs.MODE_NONE) {
            btnResumeScan.setText(getString(R.string.mode_resume_scan, modeDisplayName(prefs.getActiveMode())));
            // 「拦截」按钮仅深层扫描模式（无障碍/Shizuku/Root）显示
            btnResumeBlock.setVisibility(prefs.isDeepScanMode() ? View.VISIBLE : View.GONE);
            slideInRight(modeResume);
            refreshBehaviorPanel();
        } else {
            btnPickMode.setVisibility(View.VISIBLE);
        }
    }

    /** 竖屏：点图标 → 高亮该图标并在上方浮现/切换详细面板。
     *  已显示时切换：滑动方向按图标相对位置决定（点右侧图标→旧左出新右入，反之相反）；
     *  滑动过程中检测逻辑不展开，滑动停止后自动展开。 */
    private void selectMode(int key, com.google.android.material.card.MaterialCardView card) {
        // 记录切换前状态与新旧图标索引（决定滑动方向）
        int oldIdx = -1;
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i] == selectedModeKey) oldIdx = i;
        boolean switching = modeDetail != null && detailCard != null
                && modeDetail.getVisibility() == View.VISIBLE
                && oldIdx >= 0 && selectedModeKey != key;
        selectedModeKey = key;
        selectedModeCard = card;
        for (int i = 0; i < modeList.getChildCount(); i++) {
            View c = modeList.getChildAt(i);
            setCardSelected(c, c == card);
        }
        if (modeDetail == null || detailCard == null) return;
        int idx = -1;
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i] == key) idx = i;
        if (idx < 0) return;

        if (switching) {
            // 切换动画：滑动方向随图标位置；滑动时不展开，停止后自动展开
            final boolean leftward = idx > oldIdx; // 点更右的图标 → 内容向左流动
            final int targetKey = key, targetIdx = idx;
            int w = getResources().getDisplayMetrics().widthPixels;
            detailCard.animate().cancel();
            detailCard.animate().translationX(leftward ? -w : w).setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS)
                    .setInterpolator(com.polaris.app.util.UiFx.SLIDE_OUT_EASE)
                    .withEndAction(() -> {
                        if (selectedModeKey != targetKey) return; // 期间又切换，由新调用接管
                        bindModeCardContent(detailCard, targetIdx);
                        fillCheckSteps(targetKey);
                        // 滑动时不展开
                        checkExpanded = false;
                        applyCheckExpanded(false);
                        detailCard.setTranslationX(leftward ? w : -w);
                        detailCard.animate().translationX(0f).setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS)
                                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE)
                                .withEndAction(() -> {
                                    // 滑动停止后自动展开
                                    if (selectedModeKey != targetKey) return;
                                    checkExpanded = true;
                                    applyCheckExpanded(true);
                                }).start();
                    }).start();
            return;
        }

        bindModeCardContent(detailCard, idx);
        if (checkSteps != null) {
            fillCheckSteps(key);
            applyCheckExpanded(false);
        }
        boolean wasGone = modeDetail.getVisibility() != View.VISIBLE;
        modeDetail.setVisibility(View.VISIBLE);
        if (wasGone) slideInRight(modeDetail);
    }

    /** 按模式填充检查逻辑步骤行（01/02/… 编号 + 说明文本）。 */
    private void fillCheckSteps(int key) {
        if (checkSteps == null) return;
        checkSteps.removeAllViews();
        int resId;
        switch (key) {
            case Prefs.MODE_ACCESSIBILITY: resId = R.array.mode_check_steps_accessibility; break;
            case Prefs.MODE_SHIZUKU: resId = R.array.mode_check_steps_shizuku; break;
            case Prefs.MODE_ROOT: resId = R.array.mode_check_steps_root; break;
            default: resId = R.array.mode_check_steps_normal; break;
        }
        String[] steps = getResources().getStringArray(resId);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.TOP);
            row.setPadding(0, (int) (i == 0 ? 0 : 8 * d), 0, 0);

            TextView num = new TextView(this);
            num.setText(String.format("%02d", i + 1));
            num.setTextSize(12);
            num.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            num.setTextColor(getResources().getColor(R.color.md_theme_primary, getTheme()));
            num.setPadding(0, 0, (int) (10 * d), 0);

            TextView txt = new TextView(this);
            txt.setText(steps[i]);
            txt.setTextSize(12);
            txt.setLineSpacing(0, 1.15f);
            txt.setTextColor(getResources().getColor(R.color.md_theme_onSurfaceVariant, getTheme()));
            txt.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(num);
            row.addView(txt);
            checkSteps.addView(row);
        }
    }

    /** 应用检查逻辑区展开/收回状态（animate=true 带浮入动画）。展开时整卡向上长高，收回时缩为紧凑模式卡。 */
    private void applyCheckExpanded(boolean animate) {
        if (checkSection == null) return;
        if (checkExpanded) {
            checkSection.setVisibility(View.VISIBLE);
            if (animate) {
                checkSection.setAlpha(0f);
                checkSection.setTranslationY(24f);
                checkSection.animate().alpha(1f).translationY(0f)
                        .setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
            } else {
                checkSection.setAlpha(1f);
                checkSection.setTranslationY(0f);
            }
        } else {
            checkSection.setVisibility(View.GONE);
        }
    }

    /** 详细卡片点击：收回 / 再次展开检查逻辑。 */
    private void toggleCheckExpand() {
        checkExpanded = !checkExpanded;
        applyCheckExpanded(true);
    }

    // ---------- 主页滑动向导：模式 → 范围 → 扫描/结果（不再开新界面） ----------
    // 扫描引擎不在向导内选择，统一由「设置 → 扫描引擎」决定（Prefs.getPreferredEngine()）。

    private void onModePicked(int key) {
        // 先校验该模式的前置条件（无障碍是否开启 / Shizuku 是否可用 / 是否有 root）
        if (!ensureModePrereq(key)) return;
        enterScopeForMode(key);
    }

    /** 前置条件满足后：激活模式并滑入范围选择页（浅层扫描先播天文台入场动画）。 */
    private void enterScopeForMode(int key) {
        pendingModeKey = key;
        activateModeInPlace(key);
        setZoomSliderVisible(false);   // 范围选择中不显示缩放条
        resetScope();
        resetPickModeButton();
        // 浅层扫描（Normal）：深层 → 浅层时，先在星图上播「卫星受控再入大气层」，结束后再进
        // 天文台入场（再入衔接到天文台之前）；纯浅层激活（无常驻卫星）则按原逻辑。
        if (key == Prefs.MODE_NORMAL) {
            if (starMap != null && starMap.isSatelliteOrbitEnabled()
                    && starMap.isSatelliteVisible()) {
                // 深层 → 浅层且卫星在可视区：先播卫星受控再入（无缝接管当前轨道），
                // 结束后再进天文台/范围页
                hidePanelsExcept(null);     // 动画期间藏掉模式区
                if (stageArea != null) stageArea.setVisibility(View.GONE);   // 露出星图，再入可见
                final boolean needIntro = !introPlayed;
                starMap.startSatelliteReentry(() -> {
                    introPlayed = true;
                    if (needIntro) {
                        playObservatoryIntro();
                    } else {
                        if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
                        slideOutLeft(modeList);
                        slideInRight(configScope);
                    }
                });
                return;
            }
            if (starMap != null && starMap.isSatelliteOrbitEnabled()) {
                // 卫星在轨但已拖出可视区：再入动画不可见，直接干净撤除卫星与对接舱，
                // 仅播天文台/进范围页（不卡在屏外的不可见动画上）
                starMap.disableSatelliteOrbit();
            }
            if (!introPlayed && observatoryIntro != null) {
                introPlayed = true;
                hidePanelsExcept(null);     // 动画期间藏掉模式区（它在内容层，会盖住这幕）
                playObservatoryIntro();
                return;
            }
            syncDockModule();   // 无卫星时确保对接舱干净撤下
            slideOutLeft(modeList);
            slideInRight(configScope);
            return;
        }
        // 深层扫描（无障碍/Shizuku/Root）进范围页时插播火箭入场：地面 → 发射架
        // → 火箭起竖 → 吊装卫星 → 视角上移；播完再浮出扫描范围面板（每次选择都播）。
        if (key != Prefs.MODE_NORMAL && rocketIntro != null) {
            hidePanelsExcept(null);
            playRocketIntro();
            return;
        }
        slideOutLeft(modeList);
        slideInRight(configScope);
    }

    /** 播火箭入场；结束后把范围选择面板滑上来。 */
    private void playRocketIntro() {
        if (stageArea != null) stageArea.setVisibility(View.GONE);
        rocketIntro.playIntro(() -> {
            if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
            slideOutLeft(modeList);
            slideInRight(configScope);
        });
    }

    /** 播天文台入场；结束后把范围选择面板滑上来。 */
    private void playObservatoryIntro() {
        if (stageArea != null) stageArea.setVisibility(View.GONE);
        observatoryIntro.playIntro(() -> {
            if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
            slideOutLeft(modeList);
            slideInRight(configScope);
        });
    }

    /** 跳过入场动画（如返回键打断）：直接落到范围选择，避免流程卡在星图上。 */
    private void skipObservatoryIntro() {
        if (observatoryIntro == null) return;
        introPlayed = true;
        observatoryIntro.cancel();
        if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
        slideOutLeft(modeList);
        slideInRight(configScope);
    }

    /** 跳过火箭入场动画（如返回键打断）：直接落到范围选择，避免流程卡在星图上。 */
    private void skipRocketIntro() {
        if (rocketIntro == null) return;
        rocketIntro.cancel();
        if (interceptFlowActive) {   // 拦截动画被打断：回到模式快捷面板，不进范围选择
            interceptFlowActive = false;
            showModeStage();
            return;
        }
        if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
        slideOutLeft(modeList);
        slideInRight(configScope);
    }

    // ---------- 拦截模式：火箭动画 → 地面抬升 → 地下功能面板 ----------

    private final InterceptPanelController interceptpanelcontroller = new InterceptPanelController(this);

    private void startInterceptFlow() { interceptpanelcontroller.startInterceptFlow(); }
    private void showInterceptPanel() { interceptpanelcontroller.showInterceptPanel(); }
    private void hideInterceptPanel() { interceptpanelcontroller.hideInterceptPanel(); }
    private void toggleInterceptExpand() { interceptpanelcontroller.toggleInterceptExpand(); }
    private void setInterceptExpanded(boolean expand) { interceptpanelcontroller.setInterceptExpanded(expand); }
    private void toggleInterceptBlockFold() { interceptpanelcontroller.toggleInterceptBlockFold(); }
    private void renderInterceptPanel() { interceptpanelcontroller.renderInterceptPanel(); }
    private void renderInterceptBlockList() { interceptpanelcontroller.renderInterceptBlockList(); }
    private void renderInterceptPerms() { interceptpanelcontroller.renderInterceptPerms(); }
    private void onInterceptToggle(boolean on) { interceptpanelcontroller.onInterceptToggle(on); }
    private void ensureInterceptNotificationThenEnable() { interceptpanelcontroller.ensureInterceptNotificationThenEnable(); }
    private void enableInterceptBlockMode() { interceptpanelcontroller.enableInterceptBlockMode(); }
    private void disableInterceptBlockMode() { interceptpanelcontroller.disableInterceptBlockMode(); }
    private void syncDockModule() { interceptpanelcontroller.syncDockModule(); }
    private void restoreResidentOrbits() { interceptpanelcontroller.restoreResidentOrbits(); }
    private void showBlockAppPicker() { interceptpanelcontroller.showBlockAppPicker(); }
    private String appLabel(String pkg) { return interceptpanelcontroller.appLabel(pkg); }
    private void launchUninstall(String pkg) { interceptpanelcontroller.launchUninstall(pkg); }

    // 替代原独立拦截页（FileScanActivity open_tab=block），面板常驻星图下方，
    // 复用 Prefs / GuardService(自读 isBlockMode) / Notifier / PermUtil 真实逻辑。











     View buildInterceptPermRow(LayoutInflater inflater, int iconRes,
                                       String title, String desc, Runnable onGrant) {
        View row = inflater.inflate(R.layout.item_perm_row, itcPermList, false);
        ((android.widget.ImageView) row.findViewById(R.id.permIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.permTitle)).setText(title);
        ((TextView) row.findViewById(R.id.permDesc)).setText(desc);
        row.findViewById(R.id.permGrantButton).setOnClickListener(v -> onGrant.run());
        return row;
    }










    /** 竖屏选择流程结束/复位：隐藏按钮与详细面板并还原两态状态（文字、位移、选中记录）。 */
    private void resetPickModeButton() {
        if (btnPickMode == null) return;
        if (detailCard != null) {
            detailCard.animate().cancel();
            detailCard.setTranslationX(0f);
            detailCard.setTranslationY(0f);
        }
        btnPickMode.animate().cancel();
        btnPickMode.setVisibility(View.GONE);
        btnPickMode.setTranslationX(0f);
        btnPickMode.setTranslationY(0f);
        btnPickMode.setAlpha(1f);
        btnPickMode.setText(R.string.mode_pick_to_start);
        if (btnPickBack != null) {
            btnPickBack.animate().cancel();
            btnPickBack.setVisibility(View.GONE);
            btnPickBack.setTranslationX(0f);
            btnPickBack.setTranslationY(0f);
        }
        modeSelecting = false;
        selectedModeKey = -1;
        selectedModeCard = null;
        if (modeDetail != null) modeDetail.setVisibility(View.GONE);
        // 清除图标 tile 的选中描边（进范围页再回来不残留上次高亮）
        if (modeList != null) {
            for (int i = 0; i < modeList.getChildCount(); i++) {
                setCardSelected(modeList.getChildAt(i), false);
            }
        }
        modeList.setPadding(modeList.getPaddingLeft(), modeList.getPaddingTop(),
                modeList.getPaddingRight(), 0);
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
        setZoomSliderVisible(false);   // 范围选择中不显示缩放条
        resetScope();
    }

    /** 返回模式选择（清空扫描态）：已有激活模式 → 快捷面板；否则 → 模式列表。 */
    private void backToModes() {
        // 退出到主页/模式页：结果入口块只属于「星区」视图，离开即隐藏
        if (resultEntryBar != null) resultEntryBar.setVisibility(View.GONE);
        stopIconSpin();
        cancelAnimTestProgress();
        homeRings.setScanning(false);
        scanStatusText.setVisibility(View.GONE);
        expandHomeIcon();
        // 范围选择页背后的入场场景（天文台/火箭）一并收起，否则会叠在模式区背后
        if (observatoryIntro != null && observatoryIntro.isShowing()) {
            observatoryIntro.cancel();
            introPlayed = true;
        }
        if (rocketIntro != null && rocketIntro.isShowing()) {
            rocketIntro.cancel();
            interceptFlowActive = false;
        }
        if (stageArea != null) stageArea.setVisibility(View.VISIBLE);
        showModeStage();
    }

    /** 模式阶段入口：已有激活模式 → 快捷面板（以模式开始扫描/重新配置）；
     *  竖屏无激活模式 → 左下角「选择模式以开始扫描」按钮；横屏无激活模式 → 模式列表。 */
     void showModeStage() {
        // 运行时返回路径不读启动 extras：reconfigure / from_onboarding 只属于冷启动静态决定
        // （onCreate 已消费）。曾因 getIntent() 整个会话残留 from_onboarding=true，
        // 结果页「返回模式选择」→ 此处误入无激活模式分支，快捷面板「以 X 开始扫描」不出现。
        if (prefs.getActiveMode() != Prefs.MODE_NONE) {
            btnResumeScan.setText(getString(R.string.mode_resume_scan, modeDisplayName(prefs.getActiveMode())));
            // 「拦截」按钮仅深层扫描模式（无障碍/Shizuku/Root）显示
            btnResumeBlock.setVisibility(prefs.isDeepScanMode() ? View.VISIBLE : View.GONE);
            modeList.setVisibility(View.GONE);
            hidePanelsExcept(modeResume);
            slideInRight(modeResume);
            refreshBehaviorPanel();
            // 快捷面板 = 非选择态，星图浏览可缩放
            setZoomSliderVisible(true);
        } else if (btnPickMode != null) {
            // 竖屏：左下角按钮替代模式列表直显（复位两态状态后显示）
            hidePanelsExcept(btnPickMode);
            resetPickModeButton();
            btnPickMode.setVisibility(View.VISIBLE);
            setZoomSliderVisible(false);   // 模式选择中不显示缩放条
        } else {
            modeResume.setVisibility(View.GONE);
            hidePanelsExcept(modeList);
            slideInRight(modeList);
            setZoomSliderVisible(false);   // 模式选择中不显示缩放条
        }
    }

    /** 「重新配置模式」：从快捷面板切回模式选择——图标行、「下一步」「返回」从左侧滑入。 */
    private void showModeListFromResume() {
        enterPickingState();
    }

    private String modeDisplayName(int key) {
        for (int i = 0; i < MODE_KEYS.length; i++) {
            if (MODE_KEYS[i] == key) return getString(MODE_NAME_RES[i]);
        }
        return getString(MODE_NAME_RES[0]);
    }

    /** 快捷面板「应用行为」区：BehaviorLog 最近 3 条（最新在前）；无记录显示空态文案。 */
    private void refreshBehaviorPanel() {
        if (behaviorRows == null || behaviorEmpty == null) return;
        java.util.List<BehaviorLog.Entry> all = BehaviorLog.list(this);
        behaviorRows.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        int n = Math.min(3, all.size());
        for (int i = 0; i < n; i++) {
            BehaviorLog.Entry e = all.get(i);
            View row = inf.inflate(R.layout.item_behavior_mini, behaviorRows, false);
            ImageView icon = row.findViewById(R.id.behaviorIcon);
            TextView target = row.findViewById(R.id.behaviorTarget);
            TextView time = row.findViewById(R.id.behaviorTime);
            int[] it = behaviorIconFor(e.type);
            icon.setImageResource(it[0]);
            icon.setColorFilter(getResources().getColor(it[1], getTheme()));
            target.setText(e.target);
            time.setText(BehaviorLog.timeText(e.ts));
            behaviorRows.addView(row);
        }
        boolean empty = n == 0;
        behaviorRows.setVisibility(empty ? View.GONE : View.VISIBLE);
        behaviorEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 行为类型 → {图标, 颜色}，与 ManagementActivity.BehaviorAdapter 保持一致。 */
    private int[] behaviorIconFor(String type) {
        switch (type) {
            case BehaviorLog.TYPE_FORCE_STOP:  return new int[]{R.drawable.ic_delete,     R.color.mode_color_1};
            case BehaviorLog.TYPE_BLOCK:       return new int[]{R.drawable.ic_shield,     R.color.mode_color_4};
            case BehaviorLog.TYPE_RESCUE:      return new int[]{R.drawable.ic_restore,    R.color.mode_color_2};
            case BehaviorLog.TYPE_EMERGENCY:   return new int[]{R.drawable.ic_quarantine, R.color.mode_color_4};
            case BehaviorLog.TYPE_QUARANTINE:  return new int[]{R.drawable.ic_quarantine, R.color.mode_color_3};
            case BehaviorLog.TYPE_MONITOR:     return new int[]{R.drawable.ic_monitor,    R.color.mode_color_1};
            case BehaviorLog.TYPE_UNINSTALL:   return new int[]{R.drawable.ic_delete,     R.color.mode_color_4};
            case BehaviorLog.TYPE_DELETE:      return new int[]{R.drawable.ic_delete,     R.color.mode_color_1};
            case BehaviorLog.TYPE_FREEZE:      return new int[]{R.drawable.ic_restore,    R.color.mode_color_3};
            default:                           return new int[]{R.drawable.ic_shield,     R.color.mode_color_1};
        }
    }

    /** 是否使用 AI 引擎（由设置页统一配置）。 */
    private boolean useAiEngine() {
        return Prefs.ENGINE_AI.equals(prefs.getPreferredEngine());
    }

    /** 切换某个范围的勾选状态：三项均可自由组合；
     *  仅「选择文件夹」与「全盘扫描」互斥（全盘已覆盖所有文件夹，语义重复）。 */
    private void toggleScope(String a) {
        if ("folder".equals(a)) {
            scopeFolder = !scopeFolder;
            if (scopeFolder) scopeGlobal = false;
        } else if ("global".equals(a)) {
            scopeGlobal = !scopeGlobal;
            if (scopeGlobal) scopeFolder = false;
        } else if ("apps".equals(a)) {
            scopeApps = !scopeApps;
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
        // 勾「选择文件夹」→「开始扫描」按钮文字变「下一步」（进入文件夹多选页；仍可勾选「已安装应用」，扫描时为 文件夹+应用 组合）
        ((android.widget.TextView) findViewById(R.id.scopeStart)).setText(scopeFolder
                ? R.string.scan_next : R.string.toast_scan_start);
    }

    private void setCardSelected(View v, boolean on) {
        if (v instanceof com.google.android.material.card.MaterialCardView) {
            // 选中描边 = 主题 colorPrimary（运行时解析，跟随主题色/主题 overlay）；
            // 未选中 = colorOutlineVariant（而非静态 R.color 资源——不随主题 overlay 变化）
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(
                    on ? com.google.android.material.R.attr.colorPrimary
                       : com.google.android.material.R.attr.colorOutlineVariant,
                    tv, true);
            ((com.google.android.material.card.MaterialCardView) v).setStrokeColor(tv.data);
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
        // 范围选完、要开扫：火箭入场还在时播发射（点火 → 升空 → 卫星展开 → 地面下移出屏），再开始扫描
        if (rocketIntro != null && rocketIntro.isShowing()) {
            setZoomSliderVisible(false);
            rocketIntro.playLaunch(() -> {
                // 卫星入轨：先记下发射末态释放点，runCombinedScan 里 resetScanField 之后再启用
                // （轨道按释放点反求并锚定到世界坐标，交棒位置零跳变；此后随星图位移/缩放同步）
                float[] rp = new float[2];
                rocketIntro.getSatelliteReleasePoint(rp);
                pendingSatRelease = rp;
                runCombinedScan();
            });
            return;
        }
        // 天文台入场还在时：把这幕（地面 + 天文台 + 望远镜）下移出屏幕，露出星图，再开始扫描
        if (observatoryIntro != null && observatoryIntro.isShowing()) {
            setZoomSliderVisible(false);
            observatoryIntro.playExit(() -> runCombinedScan());
            return;
        }
        runCombinedScan();
    }

    private void runCombinedScan() {
        // 每次用户发起的扫描重置星域；组合扫描的文件段/应用段共享同一片星域（beginScanField 幂等）
        starMap.resetScanField();
        // 卫星常驻规则：深扫发射后入轨（此刻 pan=0/scale=1，世界=屏幕，交棒零跳变）；
        // 发起非深层扫描时撤下（卫星只在深层扫描模式常驻）
        if (pendingSatRelease != null) {
            starMap.enableSatelliteOrbit(pendingSatRelease[0], pendingSatRelease[1]);
            pendingSatRelease = null;
        } else if (!isDeepMode()) {
            starMap.disableSatelliteOrbit();
        }
        setZoomSliderVisible(false);
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
        homeRings.setScanning(true);
        homeRings.setProgress(0f);
        startAnimTestProgress();

        AppScanRunner.scanApps(this, isDeepMode(), new MalwareScanner.ScanCallback() {
            private final int[] done = {0};
            private volatile int total = 0;

            @Override
            public void onTotal(int t) {
                total = t;
                starMap.beginScanField(t);
            }

            @Override
            public void onItem(com.polaris.app.scan.AppRiskInfo info) {
                // 扫描星域：每个应用一颗白色恒星（星等=应用大小相对归一化），
                // 威胁等级用于星座连线颜色；点按恒星弹出信息卡
                String name = info.appName == null ? info.packageName : info.appName.toString();
                starMap.revealAppStar(info.packageName, name, appSizeBytes(info.packageName),
                        info.level);
            }

            @Override
            public void onProgress(String label) {
                done[0]++;
                globalFraction = 0.5f + 0.5f * Math.min(1f,
                        total > 0 ? done[0] / (float) total : 0f);
                localFraction = Math.min(1f, done[0] / (float) Math.max(total, 1));
                scanObjName = label;
                updateScanProgressUi();
            }

            @Override
            public void onResult(List<AppRiskInfo> risks) {
                lastAppRisks = risks;
                AppScanRunner.persist(prefs, risks, true);
                runOnUiThread(MainActivity.this::finishCombinedScan);
            }
        }, null);
    }

    /** 文件夹恒星分组键：SAF 授权扫描按 treeUri；全盘扫描按存储根下一级目录。 */
    private String folderKeyFor(com.polaris.app.scan.FileRiskInfo info) {
        return com.polaris.app.util.SafNames.keyFor(info);
    }

    /** 缩放条显隐：跟随扫描星域激活状态。 */
    private void setZoomSliderVisible(boolean visible) {
        if (zoomSlider == null) return;
        int v = visible ? View.VISIBLE : View.GONE;
        if (zoomSlider.getVisibility() != v) zoomSlider.setVisibility(v);
        // 缩放下限是动态的（= 整片星域入屏的缩放）：星区比屏幕大时它 <0.5，
        // 滑条 must 跟着走，否则用户滑到 0.5 看到的又是一坨缩在中间的小星图。
        if (visible && starMap != null) {
            float min = starMap.getMinZoom();
            if (min > 0.001f && Math.abs(zoomSlider.getValueFrom() - min) > 0.001f) {
                zoomSlider.setValueFrom(min);
                if (zoomSlider.getValue() < min) zoomSlider.setValue(min);
            }
        }
    }

    // ---------- 恒星详情面板（点按恒星 → 从右侧划出，占满整个高度） ----------

    /** 面板几何：右缘贴边、竖排居中；窄屏按 56% 屏宽收敛（含 include 根为材质卡片）。 */
    private void applyStarDetailGeometry(boolean animate) {
        if (starDetailPanel == null) return;
        starDetailPanel.post(() -> {
            if (starDetailPanel == null) return;
            float d = getResources().getDisplayMetrics().density;
            int parentW = starDetailPanel.getParent() instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) starDetailPanel.getParent()).getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            int w = (int) Math.min(300 * d, parentW * 0.56f);
            if (w <= 0) w = (int) (300 * d);
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) starDetailPanel.getLayoutParams();
            if (lp == null) return;
            lp.width = w;
            lp.gravity = android.view.Gravity.END;     // 占满整个高度，贴右缘
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, 0, 0, 0);            // 抽屉：直角贴右缘、占满整个高度
            starDetailPanel.setLayoutParams(lp);
            if (!animate) starDetailPanel.setTranslationX(0f);
        });
    }

    /**
     * 点按恒星后把星图视角推到「左侧可视区中央」：抽屉贴右缘遮掉 min(300dp, 56% 屏宽)，
     * 该宽度写入星图作为右侧 inset，星图据此把选中恒星移到未遮挡区域的几何中心（与抽屉同步滑入）。
     */
    private void focusStarView(String key) {
        if (starMap == null || key == null) return;
        starMap.post(() -> {
            if (starMap == null || key == null) return;
            int vw = starMap.getWidth();
            if (vw <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            starMap.setRightInsetPx(Math.min(300 * d, vw * 0.56f));
            starMap.focusStar(key, 0f);   // 0 = 按该恒星大小自动放大到合适尺寸
        });
    }

    /**
     * 固定星图重排 + 动态适配：恒星按 MAP_SEED 槽位自动补位（同等级优先占中心），
     * 再把全部恒星框进「可视区」正中央——任何恒星数量下都保证每颗星在屏内可见。
     * 放在结果渲染末尾：用户收回结果页回到星图时看到的就是适配后的最终构图。
     */
    private void settleStarMapLayout() {
        if (starMap == null) return;
        starMap.post(() -> {
            if (starMap == null) return;
            starMap.settleFixedLayout();
            starMap.keepCurrentAsHome();   // 结果态延续扫描态视角，不再重新缩放
        });
    }

    /** 「打开」按钮：应用段启动该包名主_activity，其余（文件/文件夹）隐藏。 */
    private void openStarDetailTarget() {
        if (starDetailOpenBtn == null) return;
        if (starDetailKey == null || !starDetailKey.startsWith("app:")) return;
        String pkg = packageOf(starDetailKey, null);
        if (pkg == null || pkg.isEmpty()) return;
        try {
            android.content.Intent i = getPackageManager()
                    .getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.toast_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 名称右侧淡出遮罩：透明 → 抽屉底色（与抽屉背景同色，文字自然淡出而非硬截断）。 */
    private void applyStarNameFade() {
        if (starDetailNameFade == null) return;
        android.util.TypedValue tv = new android.util.TypedValue();
        int solid = android.graphics.Color.TRANSPARENT;
        if (getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerHighest, tv, true)) {
            solid = tv.data;
        }
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0x00000000, solid});
        starDetailNameFade.setBackground(gd);
    }

    /** 点按恒星：填充详情面板并从右侧划出；已展开时只换内容（不再播滑入）。 */
    private void showStarDetail(String key, String label, long bytes, int level,
                                boolean isApp, int children, int childMaxLevel) {
        if (starDetailPanel == null) return;
        // 恒星模式：恢复标准行标签与星体参数区（卫星详情模式会改写/隐藏它们）
        if (starDetailTypeLabel != null) starDetailTypeLabel.setText(R.string.star_detail_type);
        if (starDetailSizeLabel != null) starDetailSizeLabel.setText(R.string.star_detail_size);
        if (starDetailChildrenLabel != null) {
            starDetailChildrenLabel.setText(R.string.star_detail_children);
        }
        if (starDetailTimeLabel != null) starDetailTimeLabel.setText(R.string.star_detail_time);
        if (starDetailFacts != null) starDetailFacts.setVisibility(View.VISIBLE);
        if (starDetailMap != null) starDetailMap.setVisibility(View.VISIBLE);
        starDetailKey = key;
        focusStarView(key);      // 星图视角同步平移到左侧可视区中央（与抽屉滑入同时进行）
        if (starDetailName != null) starDetailName.setText(label == null ? "" : label);
        if (starDetailType != null) {
            starDetailType.setText(isApp ? getString(R.string.star_detail_type_app)
                    : getString(R.string.star_detail_type_folder));
        }
        if (starDetailSize != null) starDetailSize.setText(formatBytes(bytes));
        if (starDetailSub != null) {
            String sub = isApp ? getString(R.string.star_detail_sub_app, packageOf(key, label))
                    : getString(R.string.star_detail_sub_folder, label == null ? "" : label);
            starDetailSub.setText(sub);
        }
        // 包含项：子对象个数 + 里面的最高威胁等级
        int risk = isApp ? level : childMaxLevel;
        if (starDetailChildren != null) {
            if (children > 0) {
                starDetailChildren.setText(getString(R.string.star_detail_children_fmt,
                        children, starRiskText(childMaxLevel >= 0 ? childMaxLevel : 0)));
            } else {
                starDetailChildren.setText(getString(R.string.star_detail_children_fmt,
                        0, getString(R.string.star_detail_risk_unknown)));
            }
        }
        // 最近扫描时间
        if (starDetailTime != null) {
            if (lastScanFinishedAt > 0L) {
                starDetailTime.setText(android.text.format.DateFormat.getTimeFormat(this)
                        .format(lastScanFinishedAt));
            } else {
                starDetailTime.setText(R.string.star_detail_never);
            }
        }
        if (starDetailRisk != null) starDetailRisk.setText(starRiskText(risk));
        setStarDetailRisk(risk);
        // 结论 / 建议（文字颜色只表达威胁等级）
        if (starDetailAdvice != null) {
            switch (risk) {
                case 3: starDetailAdvice.setText(R.string.star_detail_advice_high); break;
                case 2: starDetailAdvice.setText(R.string.star_detail_advice_medium); break;
                case 1: starDetailAdvice.setText(R.string.star_detail_advice_low); break;
                default: starDetailAdvice.setText(R.string.star_detail_advice_safe); break;
            }
            starDetailAdvice.setTextColor(riskColor(Math.max(0, Math.min(3, risk))));
        }
        // 「打开」仅应用段可用（连同上方那条分隔线一起显隐，避免按钮隐藏后底部留一根孤立细线）
        if (starDetailActionBar != null) {
            starDetailActionBar.setVisibility(isApp ? View.VISIBLE : View.GONE);
        }
        // 星体 / 星系参数（在 post 里读，确保星图已绘制出这颗恒星）
        if (starDetailMap != null) starDetailMap.post(() -> {
            StarMapView.StarFacts f = starDetailMap.getSoloStarFacts();
            if (f == null) return;
            if (starDetailPlanets != null) {
                starDetailPlanets.setText(getString(R.string.star_detail_planets_fmt,
                        f.planetCount));
            }
            if (starDetailMag != null) {
                starDetailMag.setText(getString(R.string.star_detail_mag_fmt, f.mag));
            }
            if (starDetailTemp != null) {
                starDetailTemp.setText(getString(R.string.star_detail_temp_fmt, f.tempK));
            }
            if (starDetailSpec != null) starDetailSpec.setText(f.spec);
            if (starDetailRadius != null) {
                starDetailRadius.setText(getString(R.string.star_detail_radius_fmt, f.radiusRs));
            }
            if (starDetailMass != null) {
                starDetailMass.setText(getString(R.string.star_detail_mass_fmt, f.massMs));
            }
            setStarDetailOrbit(f.planetCount);
        });
        renderStarDetailMap(key, label, bytes, level, isApp);
        starDetailPanel.setVisibility(View.VISIBLE);
        if (starDetailOpen) return;      // 已在屏上：仅换内容
        starDetailOpen = true;
        slideDetailCompanions(true);
        starDetailPanel.post(() -> {
            if (starDetailPanel == null) return;
            float from = starDetailPanel.getWidth() > 0
                    ? starDetailPanel.getWidth() : starDetailPanel.getMeasuredWidth();
            starDetailPanel.setTranslationX(from);
            starDetailPanel.animate().translationX(0f)
                    .setDuration(300L).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
        });
    }

    /** 关闭详情面板：右滑收回 + 复位入口块/缩放条位置。 */
    private void hideStarDetail() {
        if (!starDetailOpen || starDetailPanel == null) return;
        starDetailOpen = false;
        if (starMap != null) {                          // 抽屉收起：停掉未完成的视角平移并复原选中星大小
            starMap.cancelPanAnim();
            starMap.clearFocusStar();
            starMap.setRightInsetPx(0f);
        }
        starDetailPanel.animate().translationX(panelSlideWidth()).setDuration(260L)
                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE)
                .withEndAction(() -> starDetailPanel.setVisibility(View.GONE))
                .start();
        slideDetailCompanions(false);
    }

    /**
     * 卫星详情：点按星图常驻卫星（深扫入轨后）→ 同一侧边抽屉，内容换成卫星信息。
     * 星体参数区与迷你星图隐藏；行标签换成卫星语义（类型/轨道/状态/部署）；
     * 风险恒为安全（蓝，等级 0）；无「打开」操作。不做视角平移（卫星沿轨移动，无固定焦点）。
     */
    private void showSatelliteDetail() {
        if (starDetailPanel == null) return;
        if (starDetailFacts != null) starDetailFacts.setVisibility(View.GONE);
        if (starDetailMap != null) starDetailMap.setVisibility(View.GONE);
        if (starDetailTypeLabel != null) starDetailTypeLabel.setText(R.string.star_detail_type);
        if (starDetailSizeLabel != null) {
            starDetailSizeLabel.setText(R.string.sat_detail_orbit_label);
        }
        if (starDetailChildrenLabel != null) {
            starDetailChildrenLabel.setText(R.string.sat_detail_status_label);
        }
        if (starDetailTimeLabel != null) {
            starDetailTimeLabel.setText(R.string.sat_detail_deploy_label);
        }
        if (starDetailName != null) starDetailName.setText(R.string.sat_detail_name);
        if (starDetailSub != null) starDetailSub.setText(R.string.sat_detail_sub);
        if (starDetailType != null) starDetailType.setText(R.string.sat_detail_type);
        if (starDetailSize != null) starDetailSize.setText(R.string.sat_detail_orbit_value);
        if (starDetailChildren != null) {
            starDetailChildren.setText(R.string.sat_detail_status_value);
        }
        if (starDetailTime != null) {
            if (lastScanFinishedAt > 0L) {
                starDetailTime.setText(getString(R.string.sat_detail_deploy_value_fmt,
                        android.text.format.DateFormat.getTimeFormat(this)
                                .format(lastScanFinishedAt)));
            } else {
                starDetailTime.setText(R.string.star_detail_never);
            }
        }
        if (starDetailRisk != null) starDetailRisk.setText(starRiskText(0));
        setStarDetailRisk(0);
        if (starDetailAdvice != null) {
            starDetailAdvice.setText(R.string.sat_detail_advice);
            starDetailAdvice.setTextColor(riskColor(0));
        }
        if (starDetailActionBar != null) starDetailActionBar.setVisibility(View.GONE);
        starDetailPanel.setVisibility(View.VISIBLE);
        if (starDetailOpen) return;      // 已在屏上：仅换内容
        starDetailOpen = true;
        slideDetailCompanions(true);
        starDetailPanel.post(() -> {
            if (starDetailPanel == null) return;
            float from = starDetailPanel.getWidth() > 0
                    ? starDetailPanel.getWidth() : starDetailPanel.getMeasuredWidth();
            starDetailPanel.setTranslationX(from);
            starDetailPanel.animate().translationX(0f)
                    .setDuration(300L).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
        });
    }

    /** 面板右缘划出时，让「查看结果」入口块与缩放条整体左移让位（反之复位）。
     *  窄屏下面板已占掉大半宽度、左移会把入口块推出屏幕外 → 改为缩小淡出（关闭时淡回）。 */
    private void slideDetailCompanions(boolean shifted) {
        float d = getResources().getDisplayMetrics().density;
        float dx = shifted ? -(panelSlideWidth() + 10 * d) : 0f;
        slideCompanionForDetail(resultEntryBar, dx, 6 * d);
        slideCompanionForDetail(zoomSlider, dx, 4 * d);
    }

    /** 单个伴生控件让位：dx 后可完整留在左缘之外则平移；否则淡出/复位。 */
    private void slideCompanionForDetail(android.view.View v, float dx, float keepPx) {
        if (v == null) return;
        float baseLeft = v.getX() - v.getTranslationX();   // 未平移时的左缘
        float tx = dx;
        float alpha = 1f;
        if (dx < -(baseLeft - keepPx)) {                   // 会出屏 → 不平移，淡出
            tx = 0f;
            alpha = 0f;
        }
        v.animate().translationX(tx).alpha(alpha)
                .setDuration(260L).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE)
                .setStartDelay(40L).start();
    }

    /** 结果面板需要独占右侧/整屏时：详情面板直接撤下，不做滑出动画。 */
    private void hideStarDetailQuiet() {
        if (!starDetailOpen || starDetailPanel == null) return;
        starDetailOpen = false;
        starDetailPanel.setTranslationX(0f);
        starDetailPanel.setVisibility(View.GONE);
        if (starMap != null) {
            starMap.cancelPanAnim();
            starMap.clearFocusStar();
            starMap.setRightInsetPx(0f);
        }
        slideDetailCompanions(false);
    }

    /** 面板滑出距离（px）：以实际宽度为准，未测量时按 300dp 兜底。 */
    private float panelSlideWidth() {
        int w = starDetailPanel != null ? starDetailPanel.getWidth() : 0;
        if (w <= 0 && starDetailPanel != null) w = starDetailPanel.getMeasuredWidth();
        float d = getResources().getDisplayMetrics().density;
        return w > 0 ? w : 300 * d;
    }

    /** 面板迷你星图：只画这一颗恒星（含其行星）。 */
    private void renderStarDetailMap(String key, String label, long bytes, int level, boolean isApp) {
        if (starDetailMap == null) return;
        starDetailMap.post(() -> {
            if (starDetailMap == null) return;
            if (starDetailMap.getWidth() <= 0) {   // 首帧尚未测量：重投一次
                renderStarDetailMap(key, label, bytes, level, isApp);
                return;
            }
            starDetailMap.resetScanField();        // 清掉上一颗恒星
            starDetailMap.beginScanField(1);
            if (isApp) {
                starDetailMap.revealAppStar(packageOf(key, label), label, bytes, level);
            } else {
                starDetailMap.revealItem(level, bytes, label);
            }
        });
    }

    /** 安全程度：四段等级条，点亮数 = 等级+1；颜色只表达威胁等级。 */
    private void setStarDetailRisk(int level) {
        if (starDetailDots == null) return;
        starDetailDots.removeAllViews();
        int lit = Math.max(0, Math.min(4, level + 1));
        int top = Math.max(0, Math.min(3, level));
        int litColor = riskColor(top);
        float d = getResources().getDisplayMetrics().density;
        android.util.TypedValue tv = new android.util.TypedValue();
        int outline = 0;
        if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            outline = tv.data;
        }
        for (int i = 0; i < 4; i++) {
            android.view.View v = new android.view.View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams((int) (20 * d), (int) (4 * d));
            lp.setMargins(i > 0 ? (int) (6 * d) : 0, 0, 0, 0);
            v.setLayoutParams(lp);
            if (i < lit) {
                v.setBackgroundColor(litColor);
            } else {
                v.setBackgroundColor(outline);
                v.setAlpha(0.45f);
            }
            starDetailDots.addView(v);
        }
    }

    /** 行星轨道：把行星数量画成圆点（最多 6 个坑位，未占的用描边点）。 */
    private void setStarDetailOrbit(int planetCount) {
        if (starDetailOrbit == null) return;
        starDetailOrbit.removeAllViews();
        int slots = Math.max(0, Math.min(6, planetCount));
        float d = getResources().getDisplayMetrics().density;
        android.util.TypedValue tv = new android.util.TypedValue();
        int outline = 0;
        if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            outline = tv.data;
        }
        for (int i = 0; i < 6; i++) {
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setSize((int) (8 * d), (int) (8 * d));
            gd.setColor(i < slots ? outline : outline);
            gd.setAlpha(i < slots ? 0xFF : 0x40);
            android.view.View v = new android.view.View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d));
            lp.setMargins(i > 0 ? (int) (8 * d) : 0, 0, 0, 0);
            v.setLayoutParams(lp);
            v.setBackground(gd);
            starDetailOrbit.addView(v);
        }
    }

    /** 威胁等级 → 显示文字（runtime 字符串，三语一致）。 */
    private String starRiskText(int level) {
        if (level >= 3) return getString(R.string.risk_high);
        if (level == 2) return getString(R.string.risk_medium);
        if (level == 1) return getString(R.string.risk_low);
        if (level == 0) return getString(R.string.risk_safe);
        return getString(R.string.star_detail_risk_unknown);
    }

    /** 威胁等级 → 资源色（日/夜两套由资源限定符切换）。 */
    private int riskColor(int level) {
        int rid = level >= 3 ? R.color.risk_high
                : level == 2 ? R.color.risk_medium
                : level == 1 ? R.color.risk_low : R.color.risk_safe;
        return androidx.core.content.ContextCompat.getColor(this, rid);
    }

    /** 恒星键（app:<包名> / folder:<键>#<后缀>）→ 包名。 */
    private static String packageOf(String key, String fallback) {
        if (key != null && key.startsWith("app:") && key.length() > 4) return key.substring(4);
        return fallback == null ? "" : fallback;
    }

    /** 字节数 → 人类可读大小（KB/MB/GB）。 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    private long appSizeBytes(String packageName) {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager()
                    .getApplicationInfo(packageName, 0);
            long len = ai.sourceDir == null ? 0 : new java.io.File(ai.sourceDir).length();
            return len > 0 ? len : 1024L * 1024;
        } catch (Exception e) {
            return 1024L * 1024;
        }
    }

    /** 所有扫描结束：按需做 AI 判定，然后统一渲染两段结果。 */    private void finishCombinedScan() {
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
        // 底部双进度条：扫描结束隐藏，缩放条恢复显示
        if (scanProgressPanel != null) scanProgressPanel.setVisibility(View.GONE);
        setZoomSliderVisible(true);

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
        setZoomSliderVisible(false);   // 范围选择中不显示缩放条
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
        return com.polaris.app.util.SafNames.displayName(treeUri);
    }

    /** 文件夹页「开始扫描」：至少需要一个文件夹；只产出文件段结果。 */
    private void startFolderScan() {
        if (selectedFolders.isEmpty()) {
            Toast.makeText(this, R.string.scan_folder_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        starMap.resetScanField();
        setZoomSliderVisible(false);
        lastFileRisks = null;
        lastAppRisks = null;
        lastAppVerdicts = null;
        beginScan("folder", selectedFolders);
    }

    /** 开始文件扫描；treeUris 非空表示 SAF 多文件夹扫描（逐个遍历授权树）。 */
    private void beginScan(String action, List<Uri> treeUris) {
        scanning = true;
        hideStarDetailQuiet();       // 新一轮扫描：撤下恒星详情面板
        scannedCount = 0;
        globalFraction = 0f;
        localFraction = 0f;
        scanObjName = getString(R.string.scan_counting, 0);
        currentTreeUri = (treeUris != null && !treeUris.isEmpty()) ? treeUris.get(0) : null;
        // 文件段元信息：结果页文件栏显示扫描范围与文件总数
        lastFileScopeLabel = (treeUris != null && !treeUris.isEmpty())
                ? getString(R.string.result_scope_folders, treeUris.size())
                : getString(R.string.scan_scope_global);
        if (configScope.getVisibility() == View.VISIBLE) slideOutRight(configScope);
        if (configFolders.getVisibility() == View.VISIBLE) slideOutRight(configFolders);
        expandHomeIcon();          // 扫描期间图标 + 圆环要显示进度
        homeRings.setScanning(true);
        homeRings.setProgress(0f);
        startAnimTestProgress();
        // 底部双进度条：显示；缩放条与信息卡让位隐藏；复位结果面板态
        if (scanProgressPanel != null) scanProgressPanel.setVisibility(View.VISIBLE);
        setZoomSliderVisible(false);
        resultSheetShown = false;
        resultArea.setVisibility(View.GONE);
        starMap.setEnabled(true);
        if (resultEntryBar != null) resultEntryBar.setVisibility(View.GONE);
        applyScanProgressUi();

        final String act = action;
        final List<Uri> uris = treeUris == null ? null : new ArrayList<>(treeUris);
        android.util.Log.d("PolarisScan", "beginScan act=" + act + " uris=" + uris);
        new Thread(() -> {
            int total;
            if ("folder".equals(act) && uris != null) {
                total = 0;
                for (Uri u : uris) total += com.polaris.app.scan.FolderCounter.countTree(
                        this, u, folderTotals, COUNT_CAP);
            } else {
                total = countFiles(Environment.getExternalStorageDirectory());
            }
            totalToScan = Math.max(total, 1);
            android.util.Log.d("PolarisScan", "count done total=" + totalToScan);
            runOnUiThread(() -> starMap.beginScanField(totalToScan));
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
            globalFraction = Math.min(1f, scopeApps
                    ? 0.5f * scannedCount / (float) totalToScan
                    : scannedCount / (float) totalToScan);
            updateScanProgressUi();
        }

        @Override
        public void onItem(com.polaris.app.scan.FileRiskInfo info, String displayName) {
            // 扫描星域：文件作为所属文件夹恒星的行星（差分：>5 颗拆分「原名a/b」）
            String key = folderKeyFor(info);
            int done = folderDone.merge(key, 1, Integer::sum);
            int total = folderTotals.containsKey(key) ? folderTotals.get(key) : done;
            localFraction = Math.min(1f, done / (float) Math.max(total, 1));
            scanObjName = key.equals("/") ? "根目录" : key;
            starMap.revealItem(info.level, info.size, key);
        }

        @Override
        public void onResult(List<FileRiskInfo> risks) {
            android.util.Log.d("PolarisScan", "onResult risks=" + risks.size());
            runOnUiThread(() -> onScanFinished(risks));
        }
    };

    /** 入口块按视觉方向变形：横屏视觉=右缘竖块（文字竖排）；竖屏视觉=左下横块（文字横排）。
     *  MuMu 等模拟器旋转后 configuration/DisplayMetrics 均不更新（仍报横屏尺寸），
     *  必须按 starMap 实际视图宽高判断，post 到布局完成后执行。 */
    private void applyEntryBarOrientation() {
        if (resultEntryBar == null || starMap == null) return;
        starMap.post(() -> {
            if (resultEntryBar == null) return;
            int vw = starMap.getWidth(), vh = starMap.getHeight();
            if (vw == 0 || vh == 0) return;
            boolean landscape = vw > vh;
            float d = getResources().getDisplayMetrics().density;
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) resultEntryBar.getLayoutParams();
            TextView label = resultEntryBar.findViewById(R.id.entryText);
            if (landscape) {
                lp.width = (int) (44 * d);
                lp.height = (int) (160 * d);
                lp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
                lp.setMargins(0, 0, (int) (12 * d), 0);
                if (label != null) label.setEms(1);
            } else {
                lp.width = (int) (180 * d);
                lp.height = (int) (44 * d);
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
                lp.setMargins((int) (20 * d), 0, 0, (int) (20 * d));
                if (label != null) {
                    label.setEms(0);
                    label.setMaxEms(Integer.MAX_VALUE);
                    label.setWidth((int) (160 * d));
                }
            }
            resultEntryBar.setLayoutParams(lp);
            applyResultSheetTop(landscape);   // 竖屏结果面板顶部贴小四角星下方
        });
    }

    /** 竖屏结果面板顶部对齐：贴着头部小四角星下方（不压住图标），
     *  按 homeAppIcon 实际底边动态算 topMargin（density / 状态栏 / 横竖屏差异都自适应）。
     *  横屏不使用 topMargin（面板右缘全高）。 */
    private void applyResultSheetTop(boolean landscape) {
        if (resultArea == null) return;
        resultArea.post(() -> {
            if (resultArea == null) return;
            // resultArea 是 stageArea（FrameLayout）的直接子 view → params 必为 FrameLayout.LayoutParams
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) resultArea.getLayoutParams();
            if (lp == null) return;
            float d = getResources().getDisplayMetrics().density;
            int top = 0;
            if (!landscape && homeAppIcon != null && stageArea != null) {
                // 去掉入场/收起动画的位移，取图标静止底边
                int iconBottom = homeAppIcon.getTop() + homeAppIcon.getHeight()
                        - (int) homeAppIcon.getTranslationY();
                int stageTop = stageArea.getTop();
                top = Math.max(0, iconBottom - stageTop) + (int) (10 * d);
                // 安全下限：面板至少保留 150dp 可视高度
                int stageH = stageArea.getHeight();
                if (stageH > 0) top = Math.min(top, Math.max(0, stageH - (int) (150 * d)));
            }
            lp.topMargin = top;
            resultArea.setLayoutParams(lp);
        });
    }

    /** 星图上滑/点击入口块：拉出结果面板并禁用星图交互。
     *  竖屏自下而上滑入；横屏从右侧水平划出（占满整个高度）。 */
    private void showResultSheet() {
        if (resultSheetShown || resultArea == null
                || resultArea.getVisibility() == View.VISIBLE) return;
        resultSheetShown = true;
        hideStarDetailQuiet();       // 详情面板让位给结果面板
        if (resultEntryBar != null) resultEntryBar.setVisibility(View.GONE);
        starMap.setEnabled(false);   // 结果态星图不可交互
        setZoomSliderVisible(false);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        applyResultSheetTop(landscape);
        if (landscape) {
            slideInRight(resultArea);
        } else {
            slideInUp(resultArea);
        }
        if (resultArea != null) resultArea.post(this::revealResultContents);
    }

    /**
     * 收回结果页：结果页顶部「向下」按钮 → 竖屏向下 / 横屏向右滑出并淡出，
     * 结束后复位面板并恢复星图交互（回到星图）。入口块重新浮现，可再次拉出结果。
     */
    private void collapseResultSheet() {
        if (resultArea == null || !resultSheetShown
                || resultArea.getVisibility() != View.VISIBLE) return;
        resultSheetShown = false;
        hideStarDetailQuiet();      // 详情抽屉一并让位
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        float dx = landscape ? dm.widthPixels : 0f;
        float dy = landscape ? 0f : resultArea.getHeight() * 0.22f;
        if (dy <= 0f && dx <= 0f) dy = dm.heightPixels * 0.22f;
        resultArea.animate()
                .translationX(dx).translationY(dy).alpha(0f)
                .setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS)
                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_OUT_EASE)
                .withEndAction(this::closeResultSheet)
                .start();
    }

    /** 关闭结果面板：复位面板几何并恢复星图交互；入口块重新浮现（点击可再次查看结果）。 */
    private void closeResultSheet() {
        resultSheetShown = false;
        if (resultArea != null) {
            resultArea.setVisibility(View.GONE);
            resultArea.setAlpha(1f);
            resultArea.setTranslationX(0f);
            resultArea.setTranslationY(0f);
        }
        starMap.setEnabled(true);
        setZoomSliderVisible(true);
        if (resultEntryBar != null) {
            resultEntryBar.setVisibility(View.VISIBLE);
            resultEntryBar.setAlpha(0f);
            resultEntryBar.animate().alpha(1f).setDuration(400).start();
        }
    }

    /** 更新底部双进度条（33ms 节流；须在 UI 线程调用）。 */
    private void updateScanProgressUi() {        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastProgressUiMs < 33 && globalFraction < 1f && localFraction < 1f) return;
        lastProgressUiMs = now;
        runOnUiThread(this::applyScanProgressUi);
    }

    private void applyScanProgressUi() {
        if (progressGlobalBar == null || progressLocalBar == null) return;
        android.view.ViewGroup tp = (android.view.ViewGroup) progressGlobalBar.getParent();
        int trackW = tp.getWidth();
        if (trackW <= 0) {
            tp.post(this::applyScanProgressUi);
            return;
        }
        progressGlobalBar.getLayoutParams().width =
                Math.max(1, (int) (trackW * Math.min(1f, globalFraction)));
        progressGlobalBar.requestLayout();
        progressLocalBar.getLayoutParams().width =
                Math.max(1, (int) (trackW * Math.min(1f, localFraction)));
        progressLocalBar.requestLayout();
        if (scanObjText != null) scanObjText.setText(scanObjName);
    }

    private void onScanFinished(List<FileRiskInfo> risks) {
        if (isFinishing() || isDestroyed()) return;   // 扫描结束时页面可能已退出
        lastFileRisks = risks;
        lastFileScanCount = totalToScan;

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
        final boolean independent = prefs.isAiIndependentMode();
        final boolean onlyFlagged = !independent;
        new Thread(() -> {
            List<AiClient.AiVerdict> allVerdicts = new ArrayList<>();
            try {
                List<Prefs.AiProviderConfig> providers = prefs.getEnabledProviders();
                String strategy = prefs.getMultiModelStrategy();
                if (providers != null && !providers.isEmpty()) {
                    if (fileRisks != null && !fileRisks.isEmpty()) {
                        List<AiClient.AiVerdict> fv =
                                AiClient.judgeFilesMulti(this, providers, strategy, fileRisks, onlyFlagged);
                        Map<String, AiClient.AiVerdict> index = AiClient.indexOf(fv);
                        for (FileRiskInfo r : fileRisks) AiClient.stamp(r, index.get(r.path), independent);
                        allVerdicts.addAll(fv);
                    }
                    if (appRisks != null && !appRisks.isEmpty()) {
                        List<AiClient.AiVerdict> av =
                                AiClient.judgeMulti(this, providers, strategy, appRisks, onlyFlagged);
                        if (independent) {
                            Map<String, AiClient.AiVerdict> idx = AiClient.indexOf(av);
                            for (AppRiskInfo r : appRisks) AiClient.stamp(r, idx.get(r.packageName), true);
                        }
                        lastAppVerdicts = av;
                        allVerdicts.addAll(av);
                    }
                } else {
                    AiProvider provider = AiProvider.fromId(prefs.getAiProviderId());
                    String apiKey = prefs.getAiApiKey();
                    String model = prefs.getAiModel();
                    if (provider != null) {
                        if (model == null || model.trim().isEmpty()) model = provider.defaultModel;
                        if (fileRisks != null && !fileRisks.isEmpty()) {
                            List<AiClient.AiVerdict> fv =
                                    AiClient.judgeFiles(this, provider, apiKey, model, fileRisks, onlyFlagged);
                            Map<String, AiClient.AiVerdict> index = AiClient.indexOf(fv);
                            for (FileRiskInfo r : fileRisks) AiClient.stamp(r, index.get(r.path), independent);
                            allVerdicts.addAll(fv);
                        }
                        if (appRisks != null && !appRisks.isEmpty()) {
                            List<AiClient.AiVerdict> av =
                                    AiClient.judge(this, provider, apiKey, model, appRisks, onlyFlagged);
                            if (independent) {
                                Map<String, AiClient.AiVerdict> idx = AiClient.indexOf(av);
                                for (AppRiskInfo r : appRisks) AiClient.stamp(r, idx.get(r.packageName), true);
                            }
                            lastAppVerdicts = av;
                            allVerdicts.addAll(av);
                        }
                    }
                }
            } catch (AiClient.AiException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.ai_judge_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
            // 独立检测模式下：AI 复核了全部候选但无新增风险时给出确认反馈
            if (independent) {
                int aiFlagged = 0;
                for (AiClient.AiVerdict v : allVerdicts) {
                    if (v.level >= AppRiskInfo.LEVEL_LOW) aiFlagged++;
                }
                if (aiFlagged == 0 && !allVerdicts.isEmpty()) {
                    final int reviewed = (fileRisks != null ? fileRisks.size() : 0)
                            + (appRisks != null ? appRisks.size() : 0);
                    runOnUiThread(() -> scanStatusText.setText(
                            getString(R.string.ai_independent_clean, reviewed)));
                }
            }
            runOnUiThread(this::renderCombined);
        }).start();
    }

    /**
     * 结果页顶部百分制评分：0 分最差、100 分干净。
     * 扣分权重：高危 15 / 中危 5 / 低危 1，整体夹在 0~100；无结果即满分。
     */
    private void applyResultScore() {
        if (resultScore == null) {
            return;
        }
        int high = 0, medium = 0, low = 0;
        if (lastFileRisks != null) {
            for (FileRiskInfo info : lastFileRisks) {
                if (info.level >= FileRiskInfo.LEVEL_HIGH) high++;
                else if (info.level >= FileRiskInfo.LEVEL_MEDIUM) medium++;
                else if (info.level >= FileRiskInfo.LEVEL_LOW) low++;
            }
        }
        if (lastAppRisks != null) {
            for (AppRiskInfo info : lastAppRisks) {
                if (info.level >= AppRiskInfo.LEVEL_HIGH) high++;
                else if (info.level >= AppRiskInfo.LEVEL_MEDIUM) medium++;
                else if (info.level >= AppRiskInfo.LEVEL_LOW) low++;
            }
        }
        int score = 100 - (high * 15 + medium * 5 + low);
        if (score < 0) score = 0;
        if (score > 100) score = 100;
        resultScore.setText(String.valueOf(score));
        if (resultScoreMax != null) {
            resultScoreMax.setText(R.string.result_score_max);
        }
    }

    /**
     * 渲染合并结果页：分数右侧简要说明（文件/应用风险计数）+ 分栏（双段才显示，
     * 单段直接显示对应面板）；文件栏显示范围/总数/段评分，应用栏列出逐应用评分，
     * 两个列表均默认折叠。
     */
    private void renderCombined() {
        lastScanFinishedAt = System.currentTimeMillis();   // 详情抽屉「最近扫描」取这个时刻
        // 取消上一轮可能挂起的应用段自动收回，避免作用于已移除的旧视图
        if (appSectionHold != null && appAutoCollapse != null) {
            appSectionHold.content.removeCallbacks(appAutoCollapse);
            appAutoCollapse = null;
        }
        appSectionHold = null;
        appPeekShown = false;          // 新一轮渲染 = 新结果页，允许重放一次展开收回
        scanStatusText.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean hasFiles = lastFileRisks != null;
        boolean hasApps = lastAppRisks != null;
        int fileRiskCount = hasFiles ? lastFileRisks.size() : 0;
        int appRiskCount = 0;
        if (hasApps) {
            for (AppRiskInfo info : lastAppRisks) {
                if (info.level >= AppRiskInfo.LEVEL_LOW) appRiskCount++;
            }
        }

        // 分数右侧简要说明
        resultBriefFiles.setVisibility(hasFiles ? View.VISIBLE : View.GONE);
        resultBriefApps.setVisibility(hasApps ? View.VISIBLE : View.GONE);
        if (hasFiles) {
            resultBriefFiles.setText(getString(R.string.scan_section_files, fileRiskCount));
        }
        if (hasApps) {
            resultBriefApps.setText(getString(R.string.scan_section_apps, appRiskCount));
        }

        applyResultScore();

        // 分栏（hero 区）：双段才显示，默认选中文件；单段隐藏分栏直接展示
        resultTabs.setVisibility(hasFiles && hasApps ? View.VISIBLE : View.GONE);
        filePanel.setVisibility(hasFiles ? View.VISIBLE : View.GONE);
        appPanel.setVisibility(hasApps ? View.VISIBLE : View.GONE);
        if (hasFiles && hasApps) {
            resultTabs.check(R.id.tabFiles);
            // check() 对已选中项不会回调监听器，显式按默认 tab 复位，避免两块面板同时可见
            filePanel.setVisibility(View.VISIBLE);
            appPanel.setVisibility(View.GONE);
        }

        // ---- 文件段：范围 + 总数 + 段评分 + 默认折叠的结果列表 ----
        resultList.removeAllViews();
        if (hasFiles) {
            fileMetaText.setText(getString(R.string.result_file_scope, lastFileScopeLabel)
                    + "\n" + getString(R.string.result_file_total, lastFileScanCount));
            fileScoreText.setText(getString(R.string.result_file_score, sectionScore(true)));
            if (fileRiskCount == 0) {
                resultList.addView(buildEmptyView());
            } else {
                LinearLayout content = addCollapsibleSection(resultList,
                        getString(R.string.result_fold_files, fileRiskCount)).content;
                List<FileRiskInfo> lowFiles = new ArrayList<>();
                for (FileRiskInfo info : lastFileRisks) {
                    if (info.level >= FileRiskInfo.LEVEL_MEDIUM) {
                        View item = inflater.inflate(R.layout.item_file_risk, content, false);
                        bindRiskItem(item, info);
                        content.addView(item);
                    } else {
                        lowFiles.add(info);
                    }
                }
                if (!lowFiles.isEmpty()) {
                    addLowRiskFold(content, lowFiles.size(), c -> {
                        for (FileRiskInfo info : lowFiles) {
                            View item = inflater.inflate(
                                    R.layout.item_file_risk_compact, c, false);
                            bindFileRiskCompact(item, info);
                            c.addView(item);
                        }
                    });
                }
            }
        }

        // ---- 应用段：应用总数 + 段评分 + 默认折叠的逐应用评分列表 ----
        appResultList.removeAllViews();
        if (hasApps) {
            appMetaText.setText(getString(R.string.result_app_total, lastAppRisks.size()));
            appScoreText.setText(getString(R.string.result_app_score, sectionScore(false)));
            if (appRiskCount == 0) {
                // 未发现有风险应用：列表上方补一句提示（列表本身保留）
                appResultList.addView(buildEmptyView(R.string.result_no_app_risk));
            }
            appSectionHold = addCollapsibleSection(appResultList,
                    getString(R.string.result_fold_apps, lastAppRisks.size()));
            LinearLayout content = appSectionHold.content;
            for (AppRiskInfo info : lastAppRisks) {
                View row = inflater.inflate(R.layout.item_app_score, content, false);
                String name = info.appName == null ? info.packageName : info.appName.toString();
                ((TextView) row.findViewById(R.id.appScoreName)).setText(name);
                ((TextView) row.findViewById(R.id.appScoreValue)).setText(
                        String.valueOf(appScoreForLevel(info.level)));
                content.addView(row);
            }
            // 默认展开：进入应用段即见完整列表，随后自动收回（计时仅在面板可见时启动）
            appSectionHold.content.setVisibility(View.VISIBLE);
            appSectionHold.arrow.setRotation(90f);
            if (appPanel.getVisibility() == View.VISIBLE) runAppPeek();
        }

        // 固定星图重排 + 动态适配缩放：全部恒星按种子槽位补位，再一次性框进可视区中央
        settleStarMapLayout();
        hidePanelsExcept(resultArea);
        // 四角星保持可见（结果面板由入口块/上滑拉出，无需收起图标让位）
        // 结果面板由入口块（或上滑手势）拉出，星图保留可交互；显示入口块
        resultArea.setVisibility(View.GONE);
        if (resultEntryBar != null) {
            resultEntryBar.setVisibility(View.VISIBLE);
            resultEntryBar.setAlpha(0f);
            resultEntryBar.animate().alpha(1f).setDuration(400).start();
        }
    }

    /**
     * 往段容器追加「标题行 + 默认收起的内容容器」折叠组，返回句柄由调用方填充。
     */
    private SectionHold addCollapsibleSection(LinearLayout container, CharSequence title) {
        View header = LayoutInflater.from(this)
                .inflate(R.layout.item_section_fold, container, false);
        TextView titleView = header.findViewById(R.id.foldTitle);
        ImageView arrow = header.findViewById(R.id.foldArrow);
        titleView.setText(title);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);

        header.setOnClickListener(v -> {
            // 用户手动操作时取消待执行的自动收回，避免互相打架
            if (appAutoCollapse != null) {
                content.removeCallbacks(appAutoCollapse);
                appAutoCollapse = null;
            }
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(show ? 90f : 0f).setDuration(160).start();
        });

        container.addView(header);
        container.addView(content);
        return new SectionHold(header, arrow, content);
    }

    /**
     * 应用段「先展开后自动收回」：展开逐应用评分列表，停留 APP_PEEK_MS 后自动收起。
     * 若应用段当前不可见（双段模式下未切到应用 tab）则跳过，待切到时再触发。
     */
    private void runAppPeek() {
        if (appSectionHold == null) return;
        if (appPeekShown) return;      // 只在当前结果页第一次展示应用段时播放，之后保持手动
        if (appPanel == null || appPanel.getVisibility() != View.VISIBLE) return;
        appPeekShown = true;
        final SectionHold hold = appSectionHold;
        if (appAutoCollapse != null) {
            hold.content.removeCallbacks(appAutoCollapse);
            appAutoCollapse = null;
        }
        // 展开
        hold.content.setVisibility(View.VISIBLE);
        hold.arrow.setRotation(90f);
        // 定时收回（带高度收拢动画）
        appAutoCollapse = () -> {
            appAutoCollapse = null;
            hold.arrow.animate().rotation(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
            int startH = hold.content.getHeight();
            if (startH > 0) {
                ValueAnimator va = ValueAnimator.ofInt(startH, 0);
                va.setDuration(240);
                va.setInterpolator(new DecelerateInterpolator());
                va.addUpdateListener(a -> {
                    ViewGroup.LayoutParams lp = hold.content.getLayoutParams();
                    lp.height = (Integer) a.getAnimatedValue();
                    hold.content.setLayoutParams(lp);
                });
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator a) {
                        hold.content.setVisibility(View.GONE);
                        ViewGroup.LayoutParams lp = hold.content.getLayoutParams();
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        hold.content.setLayoutParams(lp);
                    }
                });
                va.start();
            } else {
                hold.content.setVisibility(View.GONE);
            }
        };
        hold.content.postDelayed(appAutoCollapse, APP_PEEK_MS);
    }

    /** 折叠组句柄：标题行 + 箭头 + 内容容器。 */
    private static final class SectionHold {
        final View header;
        final ImageView arrow;
        final LinearLayout content;
        SectionHold(View h, ImageView a, LinearLayout c) {
            header = h; arrow = a; content = c;
        }
    }

    /** 「低风险与安全项」折叠组：内容条目由 filler 填充。 */
    private void addLowRiskFold(LinearLayout container, int count,
                                java.util.function.Consumer<LinearLayout> filler) {
        LinearLayout content = addCollapsibleSection(container,
                getString(R.string.scan_low_risk_group, count)).content;
        filler.accept(content);
    }

    /**
     * 段评分：与整体评分同权重（高危 15 / 中危 5 / 低危 1，夹 0~100）。
     * files=true 算文件段，否则应用段。
     */
    private int sectionScore(boolean files) {
        int high = 0, medium = 0, low = 0;
        if (files) {
            if (lastFileRisks != null) {
                for (FileRiskInfo info : lastFileRisks) {
                    if (info.level >= FileRiskInfo.LEVEL_HIGH) high++;
                    else if (info.level >= FileRiskInfo.LEVEL_MEDIUM) medium++;
                    else if (info.level >= FileRiskInfo.LEVEL_LOW) low++;
                }
            }
        } else {
            if (lastAppRisks != null) {
                for (AppRiskInfo info : lastAppRisks) {
                    if (info.level >= AppRiskInfo.LEVEL_HIGH) high++;
                    else if (info.level >= AppRiskInfo.LEVEL_MEDIUM) medium++;
                    else if (info.level >= AppRiskInfo.LEVEL_LOW) low++;
                }
            }
        }
        return Math.max(0, Math.min(100, 100 - (high * 15 + medium * 5 + low)));
    }

    /** 单应用评分映射：安全 100 / 低危 75 / 中危 40 / 高危 10。 */
    private static int appScoreForLevel(int level) {
        if (level >= AppRiskInfo.LEVEL_HIGH) return 10;
        if (level >= AppRiskInfo.LEVEL_MEDIUM) return 40;
        if (level >= AppRiskInfo.LEVEL_LOW) return 75;
        return 100;
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
        homeAppIcon.animate().translationY(-h).alpha(0f).setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS)
                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_OUT_EASE)
                .withEndAction(() -> {
                    homeAppIcon.setVisibility(View.GONE);
                    homeAppIcon.setTranslationY(0f);
                    homeAppIcon.setAlpha(1f);
                }).start();

        // 同步把占位高度收到 0，否则会留下一块空白
        ValueAnimator va = ValueAnimator.ofInt(h, 0);
        va.setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS);
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
        va.setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS);
        va.setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE);
        va.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = homeAppIcon.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            homeAppIcon.setLayoutParams(lp);
        });
        va.start();
    }


    private TextView buildEmptyView() {
        return buildEmptyView(R.string.scan_no_risk);
    }

    private TextView buildEmptyView(int textRes) {
        TextView empty = new TextView(this);
        empty.setText(textRes);
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
            if (ok) BehaviorLog.log(this, BehaviorLog.TYPE_FORCE_STOP,
                    String.valueOf(info.appName),
                    getString(R.string.behavior_detail_force_stop, info.appName));
        } else if (mode == Prefs.MODE_ROOT) {
            boolean stopped = RootChecker.runAsRoot("am force-stop " + info.packageName);
            boolean frozen = RootChecker.runAsRoot("pm disable-user --user 0 " + info.packageName);
            Toast.makeText(this,
                    stopped && frozen ? R.string.action_freeze : R.string.mode_status_no_permission,
                    Toast.LENGTH_SHORT).show();
            if (stopped) BehaviorLog.log(this, BehaviorLog.TYPE_FORCE_STOP,
                    String.valueOf(info.appName),
                    getString(R.string.behavior_detail_force_stop, info.appName));
            if (frozen) BehaviorLog.log(this, BehaviorLog.TYPE_FREEZE,
                    String.valueOf(info.appName),
                    getString(R.string.behavior_detail_freeze, info.appName));
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
            BehaviorLog.log(this, BehaviorLog.TYPE_QUARANTINE, info.name,
                    joinReasons(info.reasons));
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
            BehaviorLog.log(this, BehaviorLog.TYPE_DELETE, info.name,
                    joinReasons(info.reasons));
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
        animTest.start(homeRings);
    }

    /** 动画测试模式下，距离进度动画播完还剩多少毫秒（已播完返回 0）。 */
    private long animTestRemainingMs() {
        return animTest.remainingMs();
    }

    private void cancelAnimTestProgress() { animTest.cancel(); }

    private int countFiles(File dir) {
        folderTotals.clear();
        folderDone.clear();
        return com.polaris.app.scan.FolderCounter.countLocal(dir, folderTotals, COUNT_CAP);
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
            // 切回浅层（Normal）时拦截守护已停，但需同步复位拦截状态，
            // 否则 block_mode 持久化仍为 true → 下次 restoreResidentOrbits
            // 会误画出对接舱、且常驻通知残留（实际已无守护）。
            prefs.setBlockMode(false);
            Notifier.cancelBlockNotification(this);
            scheduleDailyScan();
            Toast.makeText(this, R.string.toast_activate_normal, Toast.LENGTH_SHORT).show();
            // 注：卫星/对接舱的撤除改由 enterScopeForMode 在播完「受控再入」后统一处理，
            // 避免切回浅层瞬间对接舱被直接 pop 掉（跳变）。
        } else {
            GuardService.start(this, key);
            int toast = key == Prefs.MODE_ACCESSIBILITY ? R.string.toast_activate_accessibility
                    : key == Prefs.MODE_SHIZUKU ? R.string.toast_activate_shizuku
                    : R.string.toast_activate_root;
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 滑动动画 ----------


    private void slideInLeft(View v) { com.polaris.app.util.UiFx.slideInLeft(v); }

    private void slideInRight(View v) { com.polaris.app.util.UiFx.slideInRight(v); }

    /** 从下方滑入（结果页用，配合顶部图标上滑收起）。 */
    private void slideInUp(View v) { com.polaris.app.util.UiFx.slideInUp(v); }

    private void slideOutRight(View v) { com.polaris.app.util.UiFx.slideOutRight(v); }

    private void slideOutLeft(View v) { com.polaris.app.util.UiFx.slideOutLeft(v); }

     void hidePanelsExcept(View keep) {
        View[] panels = {modeList, modeResume, btnPickMode, configScope, configFolders, resultArea};
        for (View p : panels) {
            if (p != null && p != keep) {
                p.setVisibility(View.GONE);
                p.setAlpha(1f);
                p.setTranslationX(0f);
            }
        }
    }

    // ---------- 结果页 / 首页 视觉美化（全部取主题色，运行时解析，不硬编码）----------

    /** 解析主题属性色值（与 ObservatoryIntroView 同口径）。 */
    private int resolveAttr(int attr) { return com.polaris.app.util.UiFx.resolveAttr(this, attr); }

    /** 结果页底座：90% 不透明的主题表面（透出一丝星图）+ 顶部主题色微光，营造星空高级感。 */
    private void applyResultSheetStyle() {
        com.polaris.app.util.UiFx.applySheetStyle(resultArea);
    }

    /** 段面板背景：圆角 + 主题表面容器色 + 细描边的「玻璃卡」，承载文件/应用段内容。 */
    private Drawable makePanelBg() {
        return com.polaris.app.util.UiFx.panelBg(this);
    }


    /** 四角星背后的柔光晕（主题色径向渐变），强化星空主题的焦点感。 */
    private void applyHomeStarGlow() {
        com.polaris.app.util.UiFx.starGlow(homeAppIcon);
    }


    /** 单个视图淡入：从透明 + 轻微下移，回到原位，带起始延迟。 */
    private void fadeInView(View v, long delay) { com.polaris.app.util.UiFx.fadeInView(v, delay); }

    /** 结果页内容逐项淡入：分数行 → 分栏 → 各段逐块（标题/评分/折叠组），形成层级揭示。 */
    private void revealResultContents() {
        long delay = 150L;
        fadeInView(resultScoreRow, delay); delay += 90;
        if (resultTabs != null && resultTabs.getVisibility() == View.VISIBLE) {
            fadeInView(resultTabs, delay); delay += 90;
        }
        if (filePanel != null && filePanel.getVisibility() == View.VISIBLE) {
            fadeInView(fileMetaText, delay); delay += 70;
            fadeInView(fileScoreText, delay); delay += 70;
            for (int i = 0; i < resultList.getChildCount(); i++) {
                fadeInView(resultList.getChildAt(i), delay); delay += 55;
            }
        }
        if (appPanel != null && appPanel.getVisibility() == View.VISIBLE) {
            fadeInView(appMetaText, delay); delay += 70;
            fadeInView(appScoreText, delay); delay += 70;
            for (int i = 0; i < appResultList.getChildCount(); i++) {
                fadeInView(appResultList.getChildAt(i), delay); delay += 55;
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
        } else if (requestCode == REQ_NOTIFICATION) {
            // 拦截实时防护开启所需的通知权限：授予则启用，拒绝则回退开关
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableInterceptBlockMode();
            } else {
                itcProgrammatic = true;
                itcToggle.setChecked(false);
                itcProgrammatic = false;
                renderInterceptPanel();
            }
        }
    }

    // ---------- 立即扫描（应用级，机械判定） ----------

     boolean isDeepMode() {
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
        applyEntryBarOrientation();   // 视觉方向变化（模拟器旋转）时重切入口块形态
        refreshStatus();
        loadGuardDashboard();
        restoreResidentOrbits();   // 重建/重进后按模式恢复卫星与对接舱常驻轨道
        // 从系统权限设置页（无障碍/悬浮窗/使用情况）返回时重渲染拦截面板，
        // 已授权的卡片随之移除
        if (interceptActive) renderInterceptPanel();
        // 从「所有文件访问」设置页返回后，若已授权则继续组合扫描
        if (pendingGlobalScan && hasFullStorageAccess()) {
            pendingGlobalScan = false;
            startCombinedScan();
        }
    }

    /** 主页头部欢迎文字：按时段问候 + 设备名（与守护面板问候语同源）。 */
    private void updateHomeGreeting() {
        if (homeGreeting == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        homeGreeting.setText(com.polaris.app.util.GuardText.greeting(this,
                cal.get(java.util.Calendar.HOUR_OF_DAY)) + " User");
    }

    private void loadGuardDashboard() {
        // 根据时间显示问候语 + 设备名
        java.util.Calendar cal = java.util.Calendar.getInstance();
        guardGreetingText.setText(com.polaris.app.util.GuardText.greeting(this,
                cal.get(java.util.Calendar.HOUR_OF_DAY)) + " User");

        // 加载守护状态
        int activeMode = prefs.getActiveMode();
        if (activeMode == Prefs.MODE_NONE) {
            guardStatusText.setText(R.string.guard_status_idle);
            guardModeText.setText(R.string.guard_dashboard_mode_none);
        } else {
            guardStatusText.setText(R.string.mode_status_active);
            guardModeText.setText(getString(R.string.guard_dashboard_mode_active,
                    com.polaris.app.util.GuardText.modeName(this, activeMode)));
        }

        // 加载最近扫描时间
        lastGuardTime.setText(com.polaris.app.util.GuardText.timeAgo(
                this, prefs.getLastScanMs(), System.currentTimeMillis()));

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
        recentScanResult.setText(com.polaris.app.util.GuardText.recentScanSummary(this,
                total, prefs.getLastScanSafe(), prefs.getLastScanSuspicious(),
                prefs.getLastScanMalicious()));
    }

    private void loadRecentFiles() {
        recentFilesList.removeAllViews();

        java.util.List<com.polaris.app.util.RecentFilesUtil.Entry> recentFiles =
                com.polaris.app.util.RecentFilesUtil.load(5);

        if (recentFiles.isEmpty()) {
            noRecentFilesText.setVisibility(View.VISIBLE);
            recentFilesList.setVisibility(View.GONE);
            return;
        }

        noRecentFilesText.setVisibility(View.GONE);
        recentFilesList.setVisibility(View.VISIBLE);

        for (com.polaris.app.util.RecentFilesUtil.Entry e : recentFiles) {
            TextView tv = new TextView(this);
            tv.setText("• " + e.name + "（" + e.dateText + "）");
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
        // 模式状态写入缓存（NORMAL/ACCESSIBILITY 同步；SHIZUKU/ROOT 走后台线程，
        // 避免 onResume 主线程跑 binder / su 命令）；详细面板可见时同步刷新其徽章
        for (int i = 0; i < MODE_KEYS.length; i++) {
            int key = MODE_KEYS[i];
            if (key == Prefs.MODE_NORMAL) {
                modeStatusState[i] = ST_READY;
            } else if (key == Prefs.MODE_ACCESSIBILITY) {
                modeStatusState[i] = isAccessibilityServiceEnabled() ? ST_ACTIVE : ST_NEED_AUTH;
            } else {
                final int idx = i;
                final boolean isShizuku = (key == Prefs.MODE_SHIZUKU);
                modeStatusState[idx] = ST_CHECKING;
                STATUS_EXEC.execute(() -> {
                    boolean ready = isShizuku
                            ? ShizukuHelper.checkAll(getApplicationContext()).allReady()
                            : RootChecker.isRootAvailable();
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        modeStatusState[idx] = ready ? ST_READY : ST_NEED_AUTH;
                        refreshDetailStatus();
                    });
                });
            }
        }
        refreshDetailStatus();
    }

    /** 详细面板可见时，按缓存刷新其状态徽章。 */
    private void refreshDetailStatus() {
        if (detailCard == null || modeDetail == null
                || modeDetail.getVisibility() != View.VISIBLE || selectedModeKey < 0) return;
        for (int i = 0; i < MODE_KEYS.length; i++) {
            if (MODE_KEYS[i] == selectedModeKey) {
                TextView status = detailCard.findViewById(R.id.modeStatus);
                if (status != null) status.setText(statusTextFor(modeStatusState[i]));
                break;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.cancel();   // 停止后台遍历，避免回调已销毁页面
        Shizuku.removeRequestPermissionResultListener(shizukuResultListener);
        if (observatoryIntro != null) observatoryIntro.cancel();   // 别让入场动画回调打到已销毁的 Activity
        if (rocketIntro != null) rocketIntro.cancel();
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
