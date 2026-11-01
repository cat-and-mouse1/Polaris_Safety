package com.polaris.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * 主页星图背景：半透明网格 + 随机星点 + 少量带十字光芒的亮星（缓慢闪烁）。
 * 扫描星域：每个应用单独成为一颗恒星（核心大小与温度色随应用大小变化），
 * 每个文件夹单独成为一颗恒星（大小/温度按文件夹字节数相对归一化，最大者=蓝白巨星），
 * 其行星为文件夹内扫描到的文件；应用恒星随机带 1-5 颗占位行星。
 * 恒星温度为主序星物理映射（小=红矮→大=蓝白）；行星威胁等级仍用主题色/等级色。
 */
public class StarMapView extends View {

    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint skyPaint = new Paint();

    /** 夜空天空渐变（深蓝，onSizeChanged 时按屏幕高创建）。 */
    private android.graphics.LinearGradient skyGradient;
    /** 主界面流星：{x, y, vx(px/ms), vy, bornAt, lifeMs, tailLenPx}。 */
    private final ArrayList<float[]> meteors = new ArrayList<>();
    /** 下一次流星雨时刻（uptimeMillis；0 = 未初始化）。 */
    private long nextMeteorAt = 0L;
    /** 柔光精灵（白色径向渐变，预渲染一次，按需缩放+调 alpha）。 */
    private android.graphics.Bitmap glowSprite;
    /** 色温精灵：蓝白（热星）/ 暖白（冷星）。 */
    private android.graphics.Bitmap coolSprite, warmSprite;
    /** 星云斑块精灵（暖褐色径向渐变）。 */
    private android.graphics.Bitmap nebulaSprite;

    /** 普通星点 {xRatio, yRatio, rDp, alpha}。 */
    private float[][] stars;
    /** 亮星 {xRatio, yRatio, rDp, phase}。 */
    private float[][] bigStars;
    private int baseColor = 0xFF888888;
    private int w = 0, h = 0;

    // ---------- 扫描星域：应用恒星 + 文件夹恒星 + 行星 ----------
    private boolean scanField = false;
    private long scanFieldStart = 0;
    private long constelRevealStart = 0;   // 星座连线统一淡入起点（扫描结束时置位，避免连线突现）
    // ---------- 单星模式（详情抽屉头部：只放大画一颗恒星，无天空/星云/星尘/连线） ----------
    private boolean soloMode = false;
    private float soloZoom = 1f;
    // ---------- 交互开关（详情抽屉头部的迷你星图为纯装饰，关闭交互） ----------
    private boolean interactive = true;

    /** 行星：绕所属恒星分布；level<0 表示占位行星（无威胁语义、无图标）。 */
    static final class Planet {
        final int level;      // 威胁等级（-1 = 占位行星）
        final float rRatio;   // 环带内半径比例 0-1（吸附轨道环位置）
        final float angle;    // 初始角度
        final float rDp;      // 行星显示半径
        final long bornAt;
        final long bytes;     // 文件大小（差分拆分时随行星迁移）
        Planet(int level, float rRatio, float angle, float rDp, long bornAt,
               long bytes) {
            this.level = level;
            this.rRatio = rRatio;
            this.angle = angle;
            this.rDp = rDp;
            this.bornAt = bornAt;
            this.bytes = bytes;
        }
    }

    /** 恒星：应用恒星（key=app:<pkg>）或文件夹恒星（key=folder:<键>，随文件夹字节数增长）。 */
    static final class Star {
        final String key;
        float coreDp;         // 核心半径 dp（星等：2dp 暗星 → 8dp 亮星）
        float magT;           // 星等 t 0-1（大小/亮度依据，相对归一化）
        long bytes;           // 累计字节数
        float x, y;           // 像素位置
        final long bornAt;
        final float phase;    // 呼吸相位
        final String label;   // 显示名（应用名 / 文件夹键，点按信息卡用）
        final int level;      // 应用恒星威胁等级 0-3（星座连线颜色）；文件夹恒星 -1
        final ArrayList<Planet> planets = new ArrayList<>();
        Star(String key, float coreDp, long bornAt, float phase,
             String label, int level) {
            this.key = key;
            this.coreDp = coreDp;
            this.bornAt = bornAt;
            this.phase = phase;
            this.label = label;
            this.level = level;
        }
    }

    /**
     * 单星模式的星体参数（供详情抽屉「星体参数」区展示）。
     * 行星数 = 绕该恒星运行的伴星数；星等/色温由该恒星的 magT（大小相对归一化）推导。
     */
    public static final class StarFacts {
        public int planetCount = 0;
        public float magT = 0f;
        public double mag = 0d;      // 视觉星等：0（最亮）→ 6.5（最暗）
        public int tempK = 0;        // 色温
        public String spec = "-";    // 光谱型
        public double radiusRs = 0d; // 半径（太阳半径倍数 R☉）
        public double massMs = 0d;   // 质量（太阳质量倍数 M☉）
    }

    /** 星座连线（同威胁等级应用恒星的最近邻链）。 */
    private final ArrayList<Star[]> constelLinks = new ArrayList<>();
    /** 单个星座（连通分量）的最大恒星数。 */
    private static final int MAX_CONSTEL_SIZE = 8;
    /** 威胁等级色：安全=蓝 / 低=黄 / 中=橙 / 高=红（仅连线表达语义）。 */
    private static final int[] CONSTEL_COLORS_NIGHT = {
            0xFF4FC3F7, 0xFFFFD54F, 0xFFFFB74D, 0xFFEF5350};
    /** 亮色主题：同语义的暗化版（浅底上可见）。 */
    private static final int[] CONSTEL_COLORS_DAY = {
            0xFF2E7DD0, 0xFFA67C00, 0xFFC05E10, 0xFFC43030};

    private int[] constelColors() {
        return nightMode ? CONSTEL_COLORS_NIGHT : CONSTEL_COLORS_DAY;
    }

    /** 主题模式（true=暗色夜空，false=亮色日间星图）。 */
    private final boolean nightMode;
    /** 天空渐变端色（colorPrimary 派生：不同颜色主题 → 不同色调星空）。 */
    private int skyTopColor = 0xFF122240, skyBottomColor = 0xFF070E1C;

    private final ArrayList<Star> scanStars = new ArrayList<>();
    private final java.util.HashMap<String, Star> starByKey = new java.util.HashMap<>();
    private long maxFolderBytes = 0;     // 当前最大文件夹字节数（文件夹恒星温度相对归一化基准）
    private long maxAppBytes = 0;        // 当前最大应用字节数（应用恒星温度相对归一化基准）
    /** 文件夹恒星分组：folderKey → 该文件夹的差分恒星列表。 */
    private final java.util.HashMap<String, ArrayList<Star>> folderGroups =
            new java.util.HashMap<>();
    /** 每个文件夹已用差分后缀数（0=主星原名，1=a，2=b…）。 */
    private final java.util.HashMap<String, Integer> folderSuffix =
            new java.util.HashMap<>();
    private final Random scanRandom = new Random(0x501A);
    private static final int MAX_STARS = 260;

    // ---------- 固定星图（单一随机种子 + 全屏随机散布 + 动态适配缩放） ----------
    /** 星图种子：整个星域布局只由它决定（同一颗星、同一屏 → 永远同一个落点）。 */
    private static final long MAP_SEED = 0x501A5EEDL;

    /**
     * 运行时星图种子：默认沿用 {@link #MAP_SEED}，开发者模式可在开发者工具里改
     * （写 SharedPreferences，重启后重新布局时生效）。
     */
    private long mapSeed() {
        try {
            return new com.polaris.app.util.Prefs(getContext()).getStarMapSeed();
        } catch (Throwable t) {
            return MAP_SEED;
        }
    }
    /** 动态适配缩放：让全部恒星都落进「可视区（避开抽屉）」所需的缩放。 */
    private float fitScale = 1f;
    /**
     * 星区（恒星可散布的世界矩形）相对「默认可视星图」的倍率。
     * >1 = 恒星允许散布到可视区之外：整片星区比屏幕大，默认视角看不全部，
     * 四周（屏幕边缘之外）还有星，用户可以拖动/放大去「散步」探索 —— 这才是星图，
     * 而不是把 n 颗星硬塞进一屏里挤成一坨。
     */
    private static final float WORLD_SPREAD = 1.7f;

    /**
     * 星区边界外扩系数：限位时把星区矩形再放大 8%（每边），
     * 这样靠边/屏外的恒星聚焦后仍能从屏幕中心附近看到，而不是被边界卡在屏幕最边缘；
     * 放大太多会导致拖出去时露出星区外的空白，所以只留一点点余量。
     */
    private static final float OVERSCAN = 1.08f;
    /**
     * 全景缩放相对「整片星域入屏」的倍率：>1 = 默认（全景）视角星区略微溢出屏幕，
     * 屏幕左右/上下边缘正好被星区切过 —— 一眼能看出星区比屏幕大、还能往外拖。
     */
    private static final float HOME_FILL = 1.18f;
    /** 缩放下限：= 整片星域刚好入屏的缩放，再小只会把星缩成中间一小坨（没有信息量）。 */
    private float minZoom = 0.4f;

    // ---------- 拖动 / 缩放（扫描星域激活时星图可交互） ----------
    private final android.widget.OverScroller scroller =
            new android.widget.OverScroller(getContext());
    private float panX = 0, panY = 0;
    private float scale = 1f;
    /** 缩放上限；缩放下限是动态的 {@link #minZoom}（整片星域入屏的缩放）。 */
    private static final float SCALE_MAX = 4.5f;
    /** 视角拉近（点选恒星）单独的上限，比手捏缩放下限更宽松。 */
    private static final float FOCUS_SCALE_MAX = 4.5f;
    private android.view.ScaleGestureDetector scaleDetector;
    private float scrollerX = 0, scrollerY = 0;
    private float lastTouchX = 0, lastTouchY = 0;
    private float downX = 0, downY = 0;
    private android.view.VelocityTracker velTracker;
    private boolean dragging = false;
    /** 视角平移补间（点按恒星后把星图视角推到「左侧可视区中央」）。 */
    private android.animation.ValueAnimator panAnim;
    /** 右侧被详情抽屉遮住的宽度（px）：决定「左侧中央」落点的 x。 */
    private float rightInsetPx = 0f;
    /** 「全景」视角的相机缩放（settleFixedLayout / fitAllStars 算出来的铺满值）。 */
    private float homeScale = 1f;
    /** 是否正处于「点选拉近视角」状态（抽屉收起时据此退回全景）。 */
    private boolean starFocused = false;
    /** 视角拉近后「选中星屏幕半径」占可视区短边的比例（比例越大 = 视角放得越大）。 */
    private static final float FOCUS_RADIUS_RATIO = 0.32f;
    /** 视角拉近至少在这个倍数之上（星很小或可视区很大时限幅用）。 */
    private static final float FOCUS_ZOOM_MIN_MUL = 2.0f;
    /**
     * 适配缩放的**绝对**下限：星区比可视区大（WORLD_SPREAD>1），要把整片星域收进屏里，
     * fit 必然 <1，所以这里不能再卡 0.8（否则整片星域塞不下，边缘星被切在外面拖不到）。
     * 「不让星缩成一小坨」由 {@link #minZoom} 负责 —— 它才是用户能拉到的最小视角。
     */
    private static final float FIT_SCALE_MIN = 0.4f;

    // ---- 常驻卫星轨道（深扫发射后）：世界坐标「地球」大圆，随星图位移/缩放同步移动，
    //      可视区内只露上缘一道弧；圆过发射动画真实释放点 → 交棒零跳变。
    //      仅深层扫描常驻；发起非深层扫描时撤下（disableSatelliteOrbit）----
    private boolean satelliteOrbit = false;
    private long satOrbitStart = 0L;
    private static final long SAT_ORBIT_MS = 240000L;  // 公转周期：可见弧段（约 16°）每次掠过约 10.7s，交会全程骑在其巡行上
    private float satRelX = 0f, satRelY = 0f;          // 释放点（屏幕坐标，来自发射动画末态）
    private boolean satAnchorSet = false;              // 释放点→世界坐标锚定（首帧按当前变换换算）
    private float orbitWcx = 0f, orbitWcy = 0f, orbitWr = 0f;  // 大圆（世界坐标）
    private float satTheta0 = 0f;                      // 释放点角位置（世界坐标）
    private float satScrX = -1f, satScrY = -1f, satScrR = 0f; // 卫星屏坐标缓存（触摸命中用）
    private final Paint satPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- onDraw 帧内复用对象（零分配改造）----
    // 绘制路径上原先每帧 new 的 RectF/Path/DashPathEffect/String[] 全部提为字段复用：
    // Canvas 的 draw* 调用是同步的，绘制完成后对象即可复用，无生命周期冲突。
    private final android.graphics.RectF tmpRect = new android.graphics.RectF();
    private final android.graphics.Path tmpPath = new android.graphics.Path();
    /** 载荷轨道虚线（参数只依赖 density，首次绘制时创建一次）。 */
    private android.graphics.DashPathEffect payloadDash;
    private static final String[] DOCK_LABELS =
            {"DEPLOY", "TRANSFER", "CATCH-UP", "FINAL APPROACH", "DOCKED"};
    private float densityCache;
    private int primaryColor = 0xFF4C6EF5;

    // ---- 对接舱（拦截模式发射后与常驻卫星交会对接）----
    //  两条独立轨道：卫星在其自身轨道（默认释放高度）巡行；载荷轨道独立锚定、
    //  圆过真实释放点（更高），与卫星轨道同半径 → 屏上为两条平行弧。
    //  交会为动态规划：入轨后按部署末的实时角差，规划「下降转移 + 同轨追赶」
    //  路线（两端速率连续：起点速率 = 入轨速率 ω、终点速率 = 目标速率 ω），
    //  交会点由目标实际运动自然涌现；随后终端低速接近 → 刚性对接随目标巡行。
    //  目标卫星全程自然公转，无冻结、无瞬移；拦截开启时常驻，关闭即撤下。
    private boolean dockModule = false;
    private boolean dockInited = false;
    private boolean dockResident = false;   // true=拦截开关/恢复路径：直接编队常驻（跳过交会动画）
    private int dockState = 0;                  // 0 部署 / 1 轨道转移 / 2 动态追赶 / 3 终端接近 / 4 对接
    private long dockStateStart = 0L;
    private float dockThetaRel = 0f;            // 入轨角（载荷轨道上 = 释放点方向）
    private float dockGap0 = 0f;                // 追赶起点：追踪器落后目标的弧角（下降末实时取）
    private float dockGap1 = 0f;                // 追赶终点：落后目标 14dp 的弧角
    private float dockPhaseMs = 0f;             // 追赶时长
    private float dockApproachStartDp = 14f;    // 终端接近起点差距（dp，按实际几何取）
    private long dockDeployDelayMs = 5000L;     // 预调度 → 载荷分离的延时（发射动画时序）
    private float satPhaseOffset = 0f;          // 卫星相位偏移（预调度入场定位，常量）
    // ---- 卫星受控再入（深层 → 浅层切换时，于天文台入场前播放）----
    //  从卫星当前轨道位置无缝接管，沿退轨弧线下降入大气层（屏下方），带主题色等离子
    //  尾迹与再入辉光，末端烧蚀淡出；对接舱若编队常驻则随卫星后方一起退轨。位置零跳变。
    private boolean reentryActive = false;
    private long reentryStart = 0L;
    private static final long REENTRY_MS = 2200L;   // 再入时长
    private float reentryWx0 = 0f, reentryWy0 = 0f; // 起点（世界坐标，捕获自当前轨道位）
    private float reentryWtx = 0f, reentryWty = 0f; // 终点（大气层，世界坐标）
    private float reentryHeading0 = 0f;             // 起点姿态（轨道切向）
    private boolean reentryHasDock = false;         // 再入前对接舱是否编队常驻
    private final java.util.ArrayList<float[]> reentryTrail = new java.util.ArrayList<>();
    private Runnable reentryOnDone = null;
    // 载荷轨道：独立于卫星轨道，圆过真实释放点（屏上与卫星轨道为平行弧）
    private float payRelX = -1f, payRelY = -1f;          // 载荷释放点（屏坐标，来自火箭视图实测公式）
    private float payWcy = 0f;                           // 载荷轨道圆心 y（圆心 x、半径与卫星轨道相同）
    // 交会预调度：拦截发射开始时把卫星相位放在屏右缘之外，令其自然公转、在载荷
    // 分离前从右侧入窗向左巡行 —— 追踪器在其上方轨道入轨，按实时角差规划转移路线
    private boolean dockPlacePending = false;
    private static final float ORBIT_ARC_SWEEP = 60f;   // 轨道线只画顶点 ±60° 的上缘弧
    private static final long DOCK_DEPLOY_MS = 400L;    // 部署：定点保持于释放点，等待发射窗口
    private static final long DOCK_WINDOW_MAX_MS = 12000L; // 发射窗口最长等待（超时走兜底规划；预留横屏下飞抵左半区的时间）
    private static final long DOCK_DESCENT_MS = 700L;   // 轨道转移：载荷轨道 → 卫星轨道（方向随两轨相对位置）
    private static final long DOCK_CATCHUP_MS = 1500L;  // 动态追赶：光滑 pursuit 曲线时长
    private static final long DOCK_APPROACH_MS = 1400L; // 终端 V-bar 低速接近
    // 追踪器落后量用屏距（dp）表达 —— 大半径轨道上一小角度就≈一整屏，
    // 必须用屏距才能把交会过程留在可见弧内
    private static final float DOCK_GAP_MIN_DP = 64f;  // 发射窗口：卫星须领先释放点此距离才出发对接（越大卫星越往前才追）
    private static final float DOCK_LEAD_DP = 48f;     // 释放时刻目标必须在释放点前方的前置量（保证「释放时目标在前」）
    private static final float DOCK_GAP1_DP = 14f;     // 追赶到达点：落后目标 14dp
    private static final float DOCK_GAP_DOCK_DP = 8f;  // 对接后：落后 8dp（对接口间距）

    public StarMapView(Context context) {
        this(context, null);
    }

    public StarMapView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StarMapView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        nightMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true)) {
            baseColor = tv.data;
        }
        // 主题色：天空渐变由 colorPrimary 派生（不同颜色主题 → 不同色调的星空底色）
        primaryColor = 0xFF4C6EF5;
        if (getContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true)) {
            primaryColor = tv.data;
        }
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(primaryColor, hsv);
        hsv[1] = Math.min(0.55f, Math.max(0.25f, hsv[1]));
        if (nightMode) {
            // 暗色：主题色调深空（colorPrimary 派生）
            hsv[2] = 0.14f;
            skyTopColor = android.graphics.Color.HSVToColor(hsv);
            hsv[2] = 0.06f;
            skyBottomColor = android.graphics.Color.HSVToColor(hsv);
        } else {
            // 亮色：纯白底（星点为深色系，白底上保持可见）
            skyTopColor = 0xFFFFFFFF;
            skyBottomColor = 0xFFFFFFFF;
        }
        float d = getResources().getDisplayMetrics().density;
        starPaint.setStyle(Paint.Style.FILL);
        flarePaint.setStyle(Paint.Style.STROKE);
        flarePaint.setStrokeWidth(Math.max(1f, d * 0.8f));
        glowPaint.setStyle(Paint.Style.FILL);
        // 柔光精灵：严格按参考图 PSF——核心 2-5px 实心过曝、晕仅核心 1.5-2 倍紧贴：
        // 中心平台 5% → 0.15 处已衰至 40% → 0.35 处 8% → 边缘 0（锐利小光点，非棉花球）。
        // 三档色温：蓝白（热星）/ 纯白 / 暖白（冷星），对应参考图的柔和色偏。
        // 亮色主题用深色系星点（日间星图），同 alpha 曲线
        float[] psfStops = {0f, 0.05f, 0.15f, 0.35f, 1f};
        if (nightMode) {
            glowSprite = makeRadialSprite(new int[]{
                    0xFFFFFFFF, 0xFFFFFFFF, 0xA6FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, psfStops);
            coolSprite = makeRadialSprite(new int[]{
                    0xFFD6E4FF, 0xFFD6E4FF, 0xA6D6E4FF, 0x14D6E4FF, 0x00D6E4FF}, psfStops);
            warmSprite = makeRadialSprite(new int[]{
                    0xFFFFE3C0, 0xFFFFE3C0, 0xA6FFE3C0, 0x14FFE3C0, 0x00FFE3C0}, psfStops);
        } else {
            glowSprite = makeRadialSprite(new int[]{
                    0xFF33415C, 0xFF33415C, 0xA633415C, 0x1433415C, 0x0033415C}, psfStops);
            coolSprite = makeRadialSprite(new int[]{
                    0xFF2E5A94, 0xFF2E5A94, 0xA62E5A94, 0x142E5A94, 0x002E5A94}, psfStops);
            warmSprite = makeRadialSprite(new int[]{
                    0xFF9E6234, 0xFF9E6234, 0xA69E6234, 0x149E6234, 0x009E6234}, psfStops);
        }
        nebulaSprite = makeRadialSprite(new int[]{0x50A87840, 0x28A87840, 0x00A87840},
                new float[]{0f, 0.5f, 1f});
        scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d2) {
                        float factor = d2.getScaleFactor();
                        float newScale = Math.max(minZoom,
                                Math.min(SCALE_MAX, scale * factor));
                        float focusX = d2.getFocusX(), focusY = d2.getFocusY();
                        // 保持手势焦点下的内容不动
                        panX = focusX - (focusX - panX) * (newScale / scale);
                        panY = focusY - (focusY - panY) * (newScale / scale);
                        scale = newScale;
                        // 缩放后按新缩放夹一次，别把手势拖到星区外露出空白
                        panX = clampPan(panX, visW(), w * 0.5f, worldW(), newScale);
                        panY = clampPan(panY, h, h * 0.5f, worldH(), newScale);
                        scroller.forceFinished(true);
                        if (zoomListener != null) zoomListener.onZoom(scale);
                        postInvalidate();
                        return true;
                    }
                });
    }

    /**
     * 扫描开始：重置星域（恒星懒创建：首个文件→所属文件夹恒星，首个应用→应用恒星）。
     * 幂等：组合扫描（文件段→应用段）第二段调用时不再清空已浮现的星体。
     */
    /** 缩放变化监听（滑条与捏合手势双向同步用）。 */
    public interface OnZoomListener {
        void onZoom(float scale);
    }

    private OnZoomListener zoomListener;

    public void setOnZoomListener(OnZoomListener l) {
        zoomListener = l;
    }

    /**
     * 把当前相机缩放回推给外部缩放条（MainActivity 用它同步 Slider）。
     * 所有改 scale 的入口（点选拉近 / 收回全景 / fit 适配 / 手势）都必须走这里，
     * 否则滑条会停在旧值上——表现为「视角放大了但缩放条不动」。
     */
    private void syncZoom() {
        if (zoomListener != null) zoomListener.onZoom(scale);
    }

    /** 缩放条入口：以视图中心为焦点设置缩放（钳制 minZoom-4.5x）。 */
    public void setZoom(float z) {
        float newScale = Math.max(minZoom, Math.min(SCALE_MAX, z));
        if (w <= 0 || h <= 0 || Math.abs(newScale - scale) < 0.0001f) return;
        cancelPanAnim();
        float cx = w * 0.5f, cy = h * 0.5f;
        panX = cx - (cx - panX) * (newScale / scale);
        panY = cy - (cy - panY) * (newScale / scale);
        scale = newScale;
        panX = clampPan(panX, visW(), w * 0.5f, worldW(), newScale);
        panY = clampPan(panY, h, h * 0.5f, worldH(), newScale);
        scroller.forceFinished(true);
        postInvalidate();
        if (zoomListener != null) zoomListener.onZoom(scale);
    }

    // ---------- 视角聚焦（点按恒星 → 星图视角平移到左侧可视区中央） ----------

    // ---------- 固定星图：种子槽位 / 自动补位 / 动态适配 ----------

    /** 右侧被详情抽屉遮住的宽度（px），由 MainActivity 在抽屉几何确定后写入。 */
    public void setRightInsetPx(float px) {
        float old = rightInsetPx;
        rightInsetPx = px > 0f ? px : 0f;
        if (Math.abs(old - rightInsetPx) < 0.5f) return;
        postInvalidate();
        post(() -> {
            if (w <= 0 || h <= 0) return;
            // 抽屉收起：视角退回「全景」（点选拉近过的话一并退回），避免回收结果页后还是局部视野
            if (rightInsetPx <= 0f) {
                if (starFocused || scale > homeScale * 1.05f) {
                    starFocused = false;
                    restoreHomeView();
                }
                return;
            }
            // 抽屉拉出：inset 只记录，相机由 focusStar 统一处理（它已经把选中星送到
            // 可视区中心），这里不再补一次平移，免得两次补间互相覆盖/跳动。
            if (rightInsetPx > 0f) { starFocused = starFocused || scale > homeScale * 1.05f; return; }
        });
    }

    /** 取消视角/缩放补间（用户手拖 / 缩放时立即接管）。 */
    public void cancelPanAnim() {
        if (panAnim != null) {
            panAnim.cancel();
            panAnim = null;
        }
    }

    /** 可视区（未被抽屉遮住的那片）宽度。 */
    private float visW() {
        return Math.max(1f, w - rightInsetPx);
    }

    /** 星区宽（世界矩形）：以可视区中心为中心、边长 = 可视区 × WORLD_SPREAD。 */
    private float worldW() {
        return Math.max(1f, w * WORLD_SPREAD);
    }

    /** 星区高（世界矩形）。 */
    private float worldH() {
        return Math.max(1f, h * WORLD_SPREAD);
    }

    /** 缩放下限（整片星域入屏的缩放），缩放条据此同步 valueFrom。 */
    public float getMinZoom() {
        return minZoom;
    }

    /**
     * 固定重排（每轮扫描调用若干次，扫描结束那次是最终布局）：
     * 恒星按 (威胁等级降序 → 字节降序 → key) 排序后依次占位 ——
     * 排位规则与随机无关，所以同一批风险数据每次都得到同一张星图。
     */
    /**
     * 扫描结束固定布局：保留扫描过程中星体的真实位置（不再重新散布，避免结果态
     * 与扫描态视觉瞬移突变），只补建星座连线并平滑淡入，重算适配缩放下限。
     * 这样「扫描中看到的星图」与「结果页星图」是同一份构图，仅连线柔和浮现。
     */
    public void settleFixedLayout() {
        synchronized (scanStars) {
            constelLinks.clear();
            if (scanStars.isEmpty() || w <= 0 || h <= 0) { fitScale = 1f; return; }
            buildConstellationsLocked();
            computeFitScaleLocked();
            constelRevealStart = SystemClock.uptimeMillis();   // 连线统一淡入起点
        }
        postInvalidate();
        if (zoomListener != null) zoomListener.onZoom(scale);
        logLayout("settle");
    }

    /** 布局/视角数值断言（便于 adb logcat 直接读，不用截图目测）。 */
    private void logLayout(String tag) {
        float[] bb;
        synchronized (scanStars) {
            bb = scanStars.isEmpty() ? null : boundsOfStarsLocked();
        }
        android.util.Log.d("StarMapDbg", tag
                + " n=" + scanStars.size() + " w=" + w + " h=" + h
                + " inset=" + (int) rightInsetPx + " scale=" + scale
                + " fit=" + fitScale + " pan=" + (int) panX + "," + (int) panY
                + " bb=" + (bb == null ? "-" : String.format("[%d,%d,%d,%d]",
                (int) bb[0], (int) bb[1], (int) bb[2], (int) bb[3])));
    }

    /**
     * 恒星散布：固定种子（MAP_SEED 掺屏幕尺寸）把恒星**铺满整片星图**，再做排斥松弛。
     * 落点用「单元 + 单元内抖动」：网格单元数贴近星数（round 而非 ceil），一个单元至多一星，
     * 四角单元强制占用，其余单元按固定种子洗牌后随机分配，位置在单元内完全随机 ——
     * ① 每个单元都有星，整片铺开、四角不留空；② 抖动吃掉整个单元，看不出栅格；
     * ③ 没有「向中心吸」的偏好，不会聚成中间一坨，也没有整齐的矩形轮廓。
     */
    private void scatterStarsLocked(ArrayList<Star> sorted) {
        int n = sorted.size();
        if (n == 0 || w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        // 星区 = 可视区 × WORLD_SPREAD（比屏幕大一圈）：恒星散步在这片**比屏幕大**的星区里，
        // 屏幕边缘之外仍有星 —— 可以拖出去探索，而不是把 n 颗星硬塞进一屏挤成中间一坨。
        float padX = 8f * d, top = 10f * d, bot = 10f * d;   // 星区边界留边（辉光别被切）
        float gw = worldW() - padX * 2f;
        float gh = worldH() - top - bot;
        if (gw < 1f || gh < 1f) return;
        float ox = w * 0.5f - gw * 0.5f, oy = h * 0.5f - gh * 0.5f;   // 星区左上角
        float aspect = gw / gh;
        // 单元数要**贴近** n（用 round 而不是 ceil 到远大于 n）：每单元至多一星，
        // 单元数≈星数 ⇒ 几乎每个单元都长星 ⇒ 整片铺开、四角必然有星。
        int cols = (int) Math.round(Math.sqrt(n * aspect));
        cols = Math.max(1, Math.min(20, cols));
        int rows = Math.max(1, (int) Math.ceil((double) n / cols));
        rows = Math.min(rows, 20);
        while (cols * rows < n) {                   // 兜底：单元数不能少于星数
            if (cols <= rows) cols++; else rows++;
        }
        int cells = cols * rows;
        float cellW = gw / cols, cellH = gh / rows;
        Random rnd = new Random(mapSeed() ^ ((long) w << 11) ^ ((long) h * 7L));
        // 单元分配：四角优先（保证四角有星，否则整片看着像缩在一角），其余单元随机抽
        int[] cand = {0, cols - 1, (rows - 1) * cols, cells - 1};
        ArrayList<Integer> corners = new ArrayList<>(4);
        for (int c : cand) {
            if (c >= 0 && c < cells && !corners.contains(c)) corners.add(c);
        }
        ArrayList<Integer> rest = new ArrayList<>(cells);
        boolean[] isCorner = new boolean[cells];
        for (int c : corners) isCorner[c] = true;
        for (int i = 0; i < cells; i++) if (!isCorner[i]) rest.add(i);
        java.util.Collections.shuffle(rest, rnd);
        for (int i = 0; i < n; i++) {
            Star s = sorted.get(i);
            int cell;
            if (i < corners.size()) cell = corners.get(i);                       // 四角
            else if (!rest.isEmpty()) cell = rest.get(i - corners.size());        // 其余随机单元
            else cell = i % cells;                                               // 兜底
            s.x = ox + (cell % cols + rnd.nextFloat()) * cellW;
            s.y = oy + (cell / cols + rnd.nextFloat()) * cellH;
        }
        // 轻排斥：只推开「单元内抖动 + 对角线相邻」造成的近邻，1 轮就够。
        // 推得太狠会把贴边的星推回中间，反而毁掉铺满整片的效果。
        float sep = Math.min(cellW, cellH) * 0.7f;
        for (int p = 0; p < 1; p++) {
            for (int i = 0; i < n; i++) {
                Star a = sorted.get(i);
                for (int j = i + 1; j < n; j++) {
                    Star b = sorted.get(j);
                    float dx = b.x - a.x, dy = b.y - a.y;
                    float dd = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dd >= sep) continue;
                    if (dd < 0.5f) {
                        dx = rnd.nextFloat() - 0.5f; dy = rnd.nextFloat() - 0.5f;
                        dd = (float) Math.sqrt(dx * dx + dy * dy) + 0.5f;
                    }
                    float push = (sep - dd) * 0.5f / dd;
                    a.x -= dx * push; a.y -= dy * push;
                    b.x += dx * push; b.y += dy * push;
                }
                // 夹在**星区**内（不是屏幕内）：星区比屏幕大，越界那部分就是「屏幕外可探索的星」
                a.x = Math.max(ox, Math.min(ox + gw, a.x));
                a.y = Math.max(oy, Math.min(oy + gh, a.y));
            }
        }
    }

    /**
     * 星座连线：只在同一威胁等级之间连边 ——
     * 不同危险等级的恒星永不进入同一个星座（连线是「星座」的唯一载体）。
     * 每个等级内按「最近未用邻居」贪心走一条折线（每颗星最多两个邻居、链长 ≤ MAX_CONSTEL_SIZE），
     * 得到的是链状星座，而不是多颗星一起挂在某一颗星上的辐条状树。
     */
    private void buildConstellationsLocked() {
        if (w <= 0 || h <= 0) return;
        constelLinks.clear();
        // 连线距离按星区尺度算（星区比可视区大 WORLD_SPREAD 倍），否则星一散开就全连不上
        float linkLim = Math.min(worldW(), worldH()) * 0.35f;
        for (int lv = 0; lv <= 3; lv++) {
            ArrayList<Star> grp = new ArrayList<>();
            for (Star s : scanStars) if (s.level == lv) grp.add(s);
            linkChainLocked(grp, linkLim);
        }
    }

    /** 组内链式连边：从任意未入链的星出发，每步走向「最近的未入链邻居」，直到无近邻为止。 */
    private void linkChainLocked(ArrayList<Star> grp, float linkLim) {
        int n = grp.size();
        if (n < 2) return;
        boolean[] onChain = new boolean[n];
        int remaining = n;
        while (remaining > 0) {
            int start = -1;
            for (int i = 0; i < n; i++) if (!onChain[i]) { start = i; break; }
            if (start < 0) break;
            onChain[start] = true;
            remaining--;
            int cur = start;
            int len = 1;
            while (remaining > 0 && len < MAX_CONSTEL_SIZE) {
                Star cs = grp.get(cur);
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int j = 0; j < n; j++) {
                    if (onChain[j]) continue;
                    Star t = grp.get(j);
                    float dx = cs.x - t.x, dy = cs.y - t.y;
                    float dd = dx * dx + dy * dy;
                    if (dd <= linkLim * linkLim && dd < bestD) { bestD = dd; best = j; }
                }
                if (best < 0) break;                       // 到链尾，另起一条链
                constelLinks.add(new Star[]{cs, grp.get(best)});
                onChain[best] = true;
                remaining--;
                len++;
                cur = best;
            }
        }
    }

    /** 全部恒星的包围盒（含视觉半径），用于动态适配缩放。 */
    private float[] boundsOfStarsLocked() {
        float d = getResources().getDisplayMetrics().density;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Star s : scanStars) {
            float r = Math.max(6f, s.coreDp * 2.6f) * d;
            minX = Math.min(minX, s.x - r); maxX = Math.max(maxX, s.x + r);
            minY = Math.min(minY, s.y - r); maxY = Math.max(maxY, s.y + r);
        }
        return new float[]{minX, maxX, minY, maxY};
    }

    /** 让「每一颗恒星都落在可视区内」所需的缩放（随恒星数量/分布动态计算）。 */
    private void computeFitScaleLocked() {
        if (scanStars.isEmpty() || w <= 0 || h <= 0) { fitScale = 1f; return; }
        float[] bb = boundsOfStarsLocked();
        float d = getResources().getDisplayMetrics().density;
        float pad = 22f * d;
        float bw = Math.max(1f, bb[1] - bb[0] + 2 * pad);
        float bh = Math.max(1f, bb[3] - bb[2] + 2 * pad);
        float s = Math.min(visW() / bw, h / bh);
        fitScale = Math.max(FIT_SCALE_MIN, Math.min(1f, s));
        // 缩放下限 = 整片星域刚好入屏：再小只是把星缩成中间一小坨（四周全是空的），没信息量。
        minZoom = fitScale;
    }

    public float getFitScale() {
        return fitScale;
    }

    /**
     * 星图大小动态适配：把全部恒星一次性框进「可视区（抽屉左侧那片）」的正中央，
     * 缩放 + 平移一起补间（与恒星数量无关，任何规模都保证每颗星在屏内）。
     */
    public void fitAllStars() {
        if (w <= 0 || h <= 0) return;
        synchronized (scanStars) {
            if (scanStars.isEmpty()) return;
            computeFitScaleLocked();
            float[] bb = boundsOfStarsLocked();
            // 记下「全景」缩放：抽屉收起时视角平滑退回到这个值。
            // 乘 HOME_FILL(>1) = 默认全景**略微溢出屏幕**：屏幕边缘正好被星区切过，
            // 一眼看得出星区比屏幕大、边缘外还有星可以拖出来看。
            homeScale = Math.max(minZoom, fitScale * HOME_FILL);
            float ns = homeScale;
            float tx = visW() * 0.5f - (bb[0] + bb[1]) * 0.5f * ns;
            float ty = h * 0.5f - (bb[2] + bb[3]) * 0.5f * ns;
            easeCamLocked(ns, tx, ty, 320L);
        }
        if (zoomListener != null) zoomListener.onZoom(scale);
        logLayout("fitAll");
    }

    /**
     * 结果态：把当前视角登记为「全景基线」，关闭面板时视角回到这里。
     * 不重新缩放——保证结果星图与扫描态同一视角（不再因 fit 而突兀缩放）。
     */
    public void keepCurrentAsHome() {
        homeScale = scale;
        if (zoomListener != null) zoomListener.onZoom(scale);
    }

    // ---------- 视角聚焦（点按恒星 → 相机拉近 + 移到左侧可视区中央） ----------

    /**
     * 视角聚焦：放大的是**视角（相机 scale）**，不是恒星本体。
     * 相机拉近后整片星域一起放大，选中星周围仍被星点包围（不会像「只把一颗星画大」
     * 那样在它旁边留一大片空）。相机只用「拉近」，不会把用户推远。
     *  @param zoomMul 手动目标缩放；<=0 = 自动算（让选中星的屏幕半径 ≈ 可视区短边
     *                 的 FOCUS_RADIUS_RATIO，且至少在 current scale 之上再拉近 FOCUS_ZOOM_MIN_MUL 倍）。
     */
    public void focusStar(String key, float zoomMul) {
        if (w <= 0 || h <= 0 || key == null) return;
        Star s;
        synchronized (scanStars) {
            s = starByKey.get(key);
        }
        if (s == null) return;
        float ns;
        if (zoomMul > 0f) {
            ns = zoomMul;
        } else {
            float d = getResources().getDisplayMetrics().density;
            // 该星在当前 scale=1 下的屏幕半径（与绘制同源：max(coreR*2.6, 6dp)）
            float rPx = Math.max(s.coreDp * 2.6f, 6f) * d * soloZoom;
            float wantPx = Math.min(visW(), h) * FOCUS_RADIUS_RATIO;
            float auto = wantPx / Math.max(1f, rPx);              // 绝对视角缩放
            ns = Math.max(scale * FOCUS_ZOOM_MIN_MUL, auto);
            ns = Math.min(FOCUS_SCALE_MAX, ns);
        }
        ns = Math.max(ns, scale);                 // 只放大视角，不缩小
        starFocused = true;
        // 平移：把该星送到可视区（未被抽屉遮住那片）的几何中心
        cancelPanAnim();
        // 平移：把该星送到可视区（抽屉左侧那片）的几何中心。
        // **不要在这里手动 clampPan**：它当时读到的还是聚焦前的旧 scale，
        // 限位会小一个量级把目标 pan 误夹，选中星反而被甩到屏幕边缘/屏外
        // （限位统一交给 easeCamLocked，它按目标缩放 ns 夹）。
        float tx = visW() * 0.5f - s.x * ns;
        float ty = h * 0.5f - s.y * ns;
        easeCamLocked(ns, tx, ty, 340L);
        postInvalidate();
    }

    /** 抽屉收起 / 收回结果页：视角平滑退回「全景」（铺满整屏的那个缩放）。 */
    public void clearFocusStar() {
        starFocused = false;
        restoreHomeView();
    }

    public void restoreHomeView() {
        if (w <= 0 || h <= 0) return;
        float[] bb;
        synchronized (scanStars) {
            if (scanStars.isEmpty()) return;
            bb = boundsOfStarsLocked();
        }
        // 与 fitAllStars 保持同一套「全景」定义：均以**可视区**（非整屏）中心为准，
        // 否则抽屉收起退回全景时视角会比首页偏出 rightInset/2 像素、看着像跳一下。
        float ns = Math.max(minZoom, Math.min(1f, homeScale));
        float tx = visW() * 0.5f - (bb[0] + bb[1]) * 0.5f * ns;
        float ty = h * 0.5f - (bb[2] + bb[3]) * 0.5f * ns;
        easeCam(ns, tx, ty, 320L);
    }

    /** 相机补间（缩放 + 平移同步插值）：手拖/新聚焦会立即打断。 */
    public void easeCam(float toScale, float toPanX, float toPanY, long dur) {
        cancelPanAnim();
        synchronized (scanStars) {
            easeCamLocked(toScale, toPanX, toPanY, dur);
        }
    }

    private void easeCamLocked(float toScale, float toPanX, float toPanY, long dur) {
        float ns = Math.max(minZoom, Math.min(SCALE_MAX, toScale));
        // 限位必须用**目标缩放** ns 算：聚焦时旧 scale 远小于 ns，用旧值会把目标 pan 误夹
        float xs = clampPan(toPanX, visW(), w * 0.5f, worldW(), ns);
        float ys = clampPan(toPanY, h, h * 0.5f, worldH(), ns);
        float f0 = scale, x0 = panX, y0 = panY;
        if (Math.abs(ns - f0) < 0.002f && Math.abs(xs - x0) < 0.5f && Math.abs(ys - y0) < 0.5f) {
            scale = ns; panX = xs; panY = ys;
            syncZoom();
            postInvalidate();
            return;
        }
        if (dur <= 0L) {
            scale = ns; panX = xs; panY = ys;
            syncZoom();
            postInvalidate();
            return;
        }
        panAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(dur);
        panAnim.addUpdateListener(v -> {
            float p = (float) v.getAnimatedValue();
            scale = f0 + (ns - f0) * p;
            panX = x0 + (xs - x0) * p;
            panY = y0 + (ys - y0) * p;
            // 相机每一帧都回推缩放条：点选拉近、收回全景、fit 适配都会让滑块跟着动
            syncZoom();
            postInvalidate();
        });
        panAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                panAnim = null;
                syncZoom();
            }
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                panAnim = null;
                syncZoom();
            }
        });
        scroller.forceFinished(true);
        panAnim.start();
    }

    /** 恒星点按监听（key/label 为 null = 点了空白处，收起详情面板）。
     *  @param key           恒星键（app:<包名> / folder:<键>#<后缀>）
     *  @param label         显示名（应用名 / 文件夹键）
     *  @param level         恒星自身威胁等级（应用恒星 0-3；文件夹恒星 -1）
     *  @param children      行星数（文件夹=文件数；应用=占位行星数）
     *  @param childMaxLevel 行星中最高威胁等级（-1 = 无/占位） */
    public interface OnStarTapListener {
        void onStarTapped(String key, String label, long bytes, int level,
                          boolean isApp, int children, int childMaxLevel);
    }

    /** 上滑手势监听（快速近垂直上滑，用于拉出结果面板）。 */
    public interface OnSwipeUpListener {
        void onSwipeUp();
    }

    private OnStarTapListener starTapListener;
    private OnSwipeUpListener swipeUpListener;

    public void setOnStarTapListener(OnStarTapListener l) {
        starTapListener = l;
    }

    public void setOnSwipeUpListener(OnSwipeUpListener l) {
        swipeUpListener = l;
    }

    /** 卫星点按回调：命中常驻卫星（深扫入轨后）时触发，优先于恒星命中测试。 */
    public interface OnSatelliteTapListener { void onSatelliteTapped(); }

    private OnSatelliteTapListener satelliteTapListener;

    public void setOnSatelliteTapListener(OnSatelliteTapListener l) {
        satelliteTapListener = l;
    }

    /**恒星含行星中的最高威胁等级（-1 = 无有效等级）；调用方需持有 scanStars 锁或已确认单例访问。*/
    private int maxPlanetLevel(Star s) {
        int best = -1;
        for (Planet p : s.planets) {
            if (p.level > best) best = p.level;
        }
        return best;
    }

    /** 点按命中测试：世界坐标内找最近恒星（半径 = max(12dp, 核心半径*2)）。 */
    private Star hitTestStar(float screenX, float screenY) {
        float d = getResources().getDisplayMetrics().density;
        float wx = (screenX - panX) / scale, wy = (screenY - panY) / scale;
        Star best = null;
        float bestD = Float.MAX_VALUE;
        synchronized (scanStars) {
            for (Star s : scanStars) {
                float dx = s.x - wx, dy = s.y - wy;
                float dd = dx * dx + dy * dy;
                if (dd < bestD) { bestD = dd; best = s; }
            }
        }
        if (best == null) return null;
        float hitR = Math.max(12 * d, best.coreDp * d * 2f) / scale;
        return bestD <= hitR * hitR ? best : null;
    }

    /** 立刻复位选中状态 / 右侧 inset（不给动画，扫描开始时同步生效）。 */
    private void resetFocusState() {
        rightInsetPx = 0f;
        homeScale = 1f;
        starFocused = false;
        if (panAnim != null) { panAnim.cancel(); panAnim = null; }
    }

    public void beginScanField(int total) {
        if (scanField) { postInvalidate(); return; }
        scanField = true;
        meteors.clear();   // 扫描态无流星
        constelRevealStart = 0;   // 新一轮扫描：连线淡入起点复位
        resetFocusState();   // 清掉上一轮的选中放大与右侧 inset 残留
        synchronized (scanStars) {
            recycleStars();
            scanStars.clear();
            starByKey.clear();
            constelLinks.clear();
            folderGroups.clear();
            folderSuffix.clear();
            maxFolderBytes = 0;
            maxAppBytes = 0;
        }
        cancelPanAnim();
        panX = 0;
        panY = 0;
        scale = 1f;
        // 固定星图：全部随机源只由 MAP_SEED 决定（掺屏幕尺寸，屏幕不同才有不同构图），
        // 不再用当前时间播种——否则每轮扫描的星尘/背景亮星都会重新摇一遍。
        scanRandom.setSeed(mapSeed() ^ ((long) w << 17) ^ ((long) h << 3));
        scanFieldStart = SystemClock.uptimeMillis();
        if (zoomListener != null) zoomListener.onZoom(scale);
        postInvalidate();
    }

    /** 结束扫描星域：清空全部恒星，下次 beginScanField 重新初始化。 */
    public void resetScanField() {
        scanField = false;
        synchronized (scanStars) {
            recycleStars();
            scanStars.clear();
            starByKey.clear();
            constelLinks.clear();
            folderGroups.clear();
            folderSuffix.clear();
            maxFolderBytes = 0;
            maxAppBytes = 0;
        }
        postInvalidate();
    }

    /**
     * 启用常驻卫星轨道（深层扫描发射动画结束后调用）：
     * 圆心在屏下方的「地球」大圆，只有上缘一道弧横跨星区；圆**过释放点**
     * {@code releaseX/releaseY}（发射动画卫星末态位置，两视图同尺寸 → 坐标 1:1），
     * 卫星自右向左掠过顶弧后隐入轨道路径的绝大部分（屏外），绕「地球」公转常驻，
     * 不受扫描重置影响。
     */
    public void enableSatelliteOrbit(float releaseX, float releaseY) {
        satRelX = releaseX;
        satRelY = releaseY;
        satAnchorSet = false;            // 首帧按当前变换锚定到世界坐标
        satelliteOrbit = true;
        satOrbitStart = SystemClock.uptimeMillis();
        postInvalidate();
    }

    /** 撤下常驻卫星（发起非深层扫描时调用）：卫星与轨道线从星图移除。 */
    public void disableSatelliteOrbit() {
        satelliteOrbit = false;
        satAnchorSet = false;
        satScrR = 0f;
        reentryActive = false;
        reentryTrail.clear();
        dockModule = false;          // 对接舱依赖卫星轨道，一并撤下
        dockInited = false;
        dockState = 0;
        satPhaseOffset = 0f;         // 整体撤除：清空预调度相位与待放置标记
        payRelX = -1f;
        payRelY = -1f;
        dockPlacePending = false;
        postInvalidate();
    }

    /**
     * 启动卫星受控再入（深层 → 浅层切换时调用）：从卫星当前轨道位置无缝接管，沿退轨
     * 弧下降入大气层，结束后回调 onDone（由 MainActivity 衔接天文台入场）。若卫星未
     * 在轨或再入已进行中，则直接回调，不阻塞流程。
     */
    public void startSatelliteReentry(Runnable onDone) {
        if (!satelliteOrbit || reentryActive) {
            if (onDone != null) onDone.run();
            return;
        }
        long t = SystemClock.uptimeMillis();
        float s = Math.max(0.01f, scale);
        float elapsed = t - satOrbitStart;
        float satBase = satTheta0 - elapsed / (float) SAT_ORBIT_MS * (float) (2 * Math.PI);
        float theta = satBase + satPhaseOffset;
        reentryWx0 = orbitWcx + orbitWr * (float) Math.cos(theta);
        reentryWy0 = orbitWcy + orbitWr * (float) Math.sin(theta);
        reentryHeading0 = (float) Math.toDegrees(
                (float) Math.atan2(-(float) Math.cos(theta), (float) Math.sin(theta))
                        - (float) Math.atan2(-(float) Math.cos(satTheta0), (float) Math.sin(satTheta0)));
        float h = getHeight(), w = getWidth();
        float targetScreenY = h * 0.82f;
        reentryWtx = reentryWx0;                  // 屏上 x 不变（世界 x 固定）
        reentryWty = (targetScreenY - panY) / s;
        reentryHasDock = dockModule && dockInited && dockState == 4;
        reentryOnDone = onDone;
        reentryStart = t;
        reentryActive = true;
        reentryTrail.clear();
        postInvalidate();
    }

    /**
     * 交会预调度（拦截发射动画开始时调用）：载荷轨道锚定到真实释放点（固定量，
     * 释放高度不可变）；卫星轨道保持常驻态——它反映用户对星图的操作（拖动/缩放），
     * 位置可变，绝不由载荷释放点推导或挪动。并**反解相位偏移**使卫星在载荷分离
     * 时刻（deployDelayMs 后）恰好行至释放点前方 {@link #DOCK_LEAD_DP} 处 —— 保证
     * 「释放时目标在前方」。追踪器自释放点入轨，按实时角差规划「转移 + 追赶」
     * 路线（转移方向随两轨相对位置，上/下皆可）。卫星全程不冻结、不被拉拽、轨道不被挪动。
     */
    public void prepareRendezvous(float relX, float relY, long deployDelayMs) {
        if (relX > 0f && relY > 0f) {
            payRelX = relX;
            payRelY = relY;
        }
        dockDeployDelayMs = Math.max(1000L, deployDelayMs);
        float w = getWidth(), h = getHeight();
        if (w > 0f && h > 0f) {
            if (!satelliteOrbit) {
                // 卫星轨道尚未启用：给一个与载荷解耦的默认位置（顶部偏右）；
                // 锚定首帧会同时按 payRelX/payRelY 算出载荷轨道
                enableSatelliteOrbit(w * 0.72f, h * 0.12f);
            } else if (satAnchorSet) {
                // 卫星已在轨且已锚定：仅按新释放点重算载荷轨道，完全不触碰卫星轨道
                float s0 = Math.max(0.01f, scale);
                float prWx = (payRelX - panX) / s0, prWy = (payRelY - panY) / s0;
                float pdxr = prWx - orbitWcx;
                payWcy = prWy + (float) Math.sqrt(
                        Math.max(0f, orbitWr * orbitWr - pdxr * pdxr));
                dockThetaRel = (float) Math.atan2(prWy - payWcy, prWx - orbitWcx);
            }
        }
        dockPlacePending = true;     // 锚定完成后首帧定位（见 onDraw）
        postInvalidate();
    }

    public boolean isSatelliteOrbitEnabled() {
        return satelliteOrbit;
    }

    /**
     * 卫星当前是否落在可视视口内（含留白余量，部分露出即视为可见）。
     * 用于「深层→浅层」切换：卫星被用户拖出屏外时再入动画不可见，
     * 此时应跳过再入、仅播天文台动画。
     */
    public boolean isSatelliteVisible() {
        if (!satelliteOrbit) return false;
        long t = android.os.SystemClock.uptimeMillis();
        float s = Math.max(0.01f, scale);
        float elapsed = t - satOrbitStart;
        float satBase = satTheta0 - elapsed / (float) SAT_ORBIT_MS * (float) (2 * Math.PI);
        float theta = satBase + satPhaseOffset;
        float wpx = orbitWcx + orbitWr * (float) Math.cos(theta);
        float wpy = orbitWcy + orbitWr * (float) Math.sin(theta);
        float px = panX + wpx * s, py = panY + wpy * s;
        float m = 80f;   // 留白余量（px），部分露出即视为可见
        return px >= -m && px <= getWidth() + m && py >= -m && py <= getHeight() + m;
    }

    /**
     * 启用对接舱（拦截模式发射动画结束后调用）：对接舱自释放点起，按交会对接动力学
     * 状态机（部署→转移→追赶→接近→对接）与巡行中的常驻卫星会合。若卫星未在轨则
     * 先启用其轨道（优先用真实释放点 relX/relY）。
     * 实际初始化在锚定完成后首帧进行（见 {@link #initDockSequence}）。
     * 此路径为「发射动画」已完成，需重播完整交会过程（resident=false）。
     */
    public void enableDockModule(float relX, float relY) {
        enableDockModule(relX, relY, false);
    }

    /**
     * 无释放点版本（拦截开关 / 重进恢复 / 面板补齐路径）：对接舱直接以「已对接
     * 编队」常驻态显示（resident=true，跳过发射→交会动画），代表拦截系统已部署就绪；
     * 用默认释放点兜底（与卫星轨道共圆、弧顶对齐）。
     */
    public void enableDockModule() {
        enableDockModule(-1f, -1f, true);
    }

    private void enableDockModule(float relX, float relY, boolean resident) {
        if (!dockModule) {
            dockModule = true;
            dockInited = false;       // 首帧（锚定后）初始化状态机
            dockResident = resident;  // 发射路径 false（播动画）/ 开关·恢复路径 true（直接编队常驻）
            // 卫星未在轨则先启用其自身轨道，保证对接舱一定渲染
            if (!satelliteOrbit) {
                float w = getWidth(), h = getHeight();
                if (w > 0f && h > 0f) enableSatelliteOrbit(w * 0.72f, h * 0.12f);
            }
            // 载荷释放点未记录时兜底（与卫星轨道重合的退化情形）
            if (relX > 0f && relY > 0f && payRelX <= 0f) {
                payRelX = relX;
                payRelY = relY;
                satAnchorSet = false;  // 重锚定以带上载荷轨道
            }
        }
        postInvalidate();
    }

    /** 撤下对接舱（拦截关闭时调用）：从卫星轨道移除；卫星本就自然巡行，无需恢复处理。 */
    public void disableDockModule() {
        dockModule = false;
        dockInited = false;
        dockState = 0;
        dockResident = false;
        dockPlacePending = false;
        postInvalidate();
    }

    /**
     * 初始化交会对接状态机（移动目标 + 动态规划）：轨道由释放点定义（弧顶 = 释放点，
     * 全程同轨同高）。追赶路线不在此时整段写死 —— 部署结束时按**实时角差**规划
     * 光滑 pursuit 曲线（两端速率连续），交会点由目标实际运动自然涌现。
     * 仅在锚定完成后的首帧调用一次。
     */
    private void initDockSequence(long t) {
        float d = getResources().getDisplayMetrics().density;
        float s = Math.max(0.01f, scale);
        dockThetaRel = satTheta0;                              // 入轨点 = 释放点 = 轨道弧顶
        dockGap1 = dockGapAngle(DOCK_GAP1_DP, orbitWr, d, s);  // 追赶终点：落后目标 14dp
        dockPhaseMs = DOCK_CATCHUP_MS;
        // 拦截开关 / 重进恢复路径：拦截系统已部署 → 对接舱直接以「已对接编队」常驻
        // （dockState=4，落后卫星固定间距同轨巡行），不重播发射→交会动画；
        // 发射动画路径（resident=false）：从部署态开始重播完整交会过程
        dockState = dockResident ? 4 : 0;
        dockStateStart = t;
        dockInited = true;
    }

    public boolean isDockModuleEnabled() {
        return dockModule;
    }

    private void recycleStars() {
        // 恒星核心为直绘圆，无可回收 bitmap
    }

    /**
     * 扫描到一个文件：作为所属文件夹恒星的行星。
     * 差分：恒星行星数 >5 时拆成两颗（行星对半 3+3），新恒星命名「原名a/b/c…」，
     * 最少负载分配使每颗恒星行星数自然收敛在 3-5 颗。
     * 大小/亮度相对归一化：以当前最大文件夹恒星为基准，发现更大者全体重排。
     * folderKey：SAF 授权扫描传 treeUri（每个授权文件夹一组恒星），全盘扫描传存储根下一级目录名。
     */
    public void revealItem(int level, long sizeBytes, String folderKey) {
        if (!scanField) return;
        int lv = Math.max(0, Math.min(3, level));
        String base = (folderKey == null || folderKey.length() == 0) ? "default" : folderKey;
        synchronized (scanStars) {
            ArrayList<Star> group = folderGroups.get(base);
            if (group == null) {
                group = new ArrayList<>();
                folderGroups.put(base, group);
            }
            // 最少负载：挑行星数最少的恒星加入
            Star s = null;
            int min = Integer.MAX_VALUE;
            for (Star o : group) {
                if (o.planets.size() < min) { min = o.planets.size(); s = o; }
            }
            if (s == null) {
                // 该文件夹首颗恒星：原名（无后缀）
                if (scanStars.size() >= MAX_STARS) return;
                s = newFolderStarLocked(base, 0);
                placeStarLocked(s);          // 先落在场心（避免星图空一帧）
                scanStars.add(s);
                starByKey.put(s.key, s);
                group.add(s);
            }
            // 文件行星吸附到两圈轨道位之一（0.33 / 0.77），紧贴母星成簇
            float rRatio = scanRandom.nextFloat() < 0.5f ? 0.33f : 0.77f;
            float angle = scanRandom.nextFloat() * 6.2832f;
            // 行星半径按文件大小映射：小 1.4dp / 中 1.8dp / 大 2.2dp（暗伴星）
            float rDp = sizeBytes <= 256L * 1024 ? 1.4f
                    : sizeBytes <= 32L * 1024 * 1024 ? 1.8f : 2.2f;
            s.planets.add(new Planet(lv, rRatio, angle, rDp,
                    SystemClock.uptimeMillis(), Math.max(0, sizeBytes)));
            s.bytes += Math.max(0, sizeBytes);
            if (s.bytes > maxFolderBytes) maxFolderBytes = s.bytes;
            // 差分：行星数 >5 → 对半拆分（6=3+3），后半移入新恒星「原名+后缀」
            if (s.planets.size() > 5 && scanStars.size() < MAX_STARS) {
                int idx = folderSuffix.containsKey(base) ? folderSuffix.get(base) : 0;
                folderSuffix.put(base, idx + 1);
                Star s2 = newFolderStarLocked(base, idx + 1);
                int half = s.planets.size() / 2;
                long moved = 0;
                for (int i = half; i < s.planets.size(); i++) {
                    Planet p = s.planets.get(i);
                    s2.planets.add(p);
                    moved += p.bytes;
                }
                s2.bytes = moved;
                s.bytes -= moved;
                s.planets.subList(half, s.planets.size()).clear();
                placeStarLocked(s2);
                scanStars.add(s2);
                starByKey.put(s2.key, s2);
                group.add(s2);
            }
            reheatFolderStarsLocked();
        }
        postInvalidate();
    }

    /** 创建文件夹差分恒星：idx=0 主星用原名，之后 a-1/b-1…z-1/a-2…（ label 与 key 唯一）。 */
    private Star newFolderStarLocked(String base, int idx) {
        String label = idx == 0 ? base : base + " " + suffixLetter(idx);
        return new Star("folder:" + base + "#" + idx, coreDpFor(0),
                SystemClock.uptimeMillis(), scanRandom.nextFloat() * 6.2832f,
                label, -1);
    }

    /** 差分后缀：1=a-1 … 26=z-1，27=a-2 …（letter-number 双段，保证唯一）。 */
    private static String suffixLetter(int idx) {
        if (idx < 1) idx = 1;
        int letter = (idx - 1) % 26;          // 0..25 → a..z
        int group = (idx - 1) / 26 + 1;       // 1,2,3…
        return (char) ('a' + letter) + "-" + group;
    }

    /**
     * 文件夹恒星重新归一化（调用方需持有 scanStars 锁）：
     * t = log1p(本文件夹字节) / log1p(当前最大文件夹字节)，最大者恒为最亮。
     * 扫描中发现更大文件夹时全体重新排星等。
     */
    private void reheatFolderStarsLocked() {
        double denom = Math.log1p(maxFolderBytes);
        if (denom <= 0) return;
        for (Star o : scanStars) {
            if (!o.key.startsWith("folder:")) continue;
            o.magT = (float) (Math.log1p(o.bytes) / denom);
            o.coreDp = coreDpFor(o.magT);
        }
    }

    /**
     * 扫描到一个应用：该应用单独成为一颗白色恒星（星等=应用大小相对归一化），
     * 随机 1-5 颗占位行星（吸附单圈轨道）。
     * 与同威胁等级的最近应用恒星连一条星座线（威胁语义只由连线颜色表达）。
     */
    public void revealAppStar(String packageName, String label, long sizeBytes, int level) {
        if (!scanField) return;
        String key = "app:" + (packageName == null ? "?" : packageName);
        synchronized (scanStars) {
            if (starByKey.containsKey(key) || scanStars.size() >= MAX_STARS) return;
            int lv = Math.max(0, Math.min(3, level));
            Star s = new Star(key, coreDpFor(0), SystemClock.uptimeMillis(),
                    scanRandom.nextFloat() * 6.2832f,
                    label == null ? packageName : label, lv);
            s.bytes = Math.max(1, sizeBytes);
            if (s.bytes > maxAppBytes) maxAppBytes = s.bytes;
            // 先按相对 t 定自身星等，再放置（放置间距公式依赖 coreDp）
            float at = (float) (Math.log1p(s.bytes) / Math.log1p(maxAppBytes));
            s.magT = at;
            s.coreDp = coreDpFor(at);
            placeStarLocked(s);
            // 星座连线不在扫描过程中增量加边（会长成「多星挂一星」的辐条树），
            // 统一在扫描结束的 settleFixedLayout() 里按等级走链式连边。
            // 占位伴星：1-3 颗极近的小暗点（参考图亮星旁的暗伴星观感，不喧宾）
            int n = 1 + scanRandom.nextInt(3);
            for (int i = 0; i < n; i++) {
                s.planets.add(new Planet(-1,
                        0.5f,
                        scanRandom.nextFloat() * 6.2832f,
                        1.2f + scanRandom.nextFloat() * 0.6f,
                        s.bornAt + i * (140 + scanRandom.nextInt(180)),
                        0L));
            }
            scanStars.add(s);
            starByKey.put(key, s);
            reheatAppStarsLocked();
        }
        postInvalidate();
    }

    /**
     * 应用恒星重新归一化（调用方需持有 scanStars 锁）：
     * t = log1p(本应用字节) / log1p(当前最大应用字节)，最大者恒为最亮。
     * 扫描中发现更大应用时全体重排星等。
     */
    private void reheatAppStarsLocked() {
        double denom = Math.log1p(maxAppBytes);
        if (denom <= 0) return;
        for (Star o : scanStars) {
            if (!o.key.startsWith("app:")) continue;
            o.magT = (float) (Math.log1p(o.bytes) / denom);
            o.coreDp = coreDpFor(o.magT);
        }
    }

    /** 星等 t → 核心半径 dp：2dp（暗星）→ 6dp（亮星，与绘制视觉一致）。 */
    private static float coreDpFor(float t) {
        return 2f + t * 4f;
    }

    /**
     * 为恒星采样位置（调用方需持有 scanStars 锁）：在**整片可视星图**（含四角，
     * 留出上下/左右安全边）里随机取 28 个候选，评分 = 间距达标度（最小间距/需要间距，封顶）
     * - 新质心离屏心偏移（弱，归一化到 0-60 分）；择优选点。
     * 候选必须铺满整片而不是绕中心取椭圆盘，星才会真的「散步」到四角。
     */
    private void placeStarLocked(Star s) {
        if (w <= 0 || h <= 0) { s.x = w * 0.5f; s.y = h * 0.5f; return; }
        if (soloMode) { s.x = w * 0.5f; s.y = h * 0.5f; return; }   // 单星模式：居中独占
        float d = getResources().getDisplayMetrics().density;
        float mx = 0, my = 0;
        int n = scanStars.size();
        for (Star o : scanStars) { mx += o.x; my += o.y; }
        if (n > 0) { mx /= n; my /= n; }
        // 候选点直接铺满「整片星区」（比可视区大，含可视区外的部分），不再绕屏幕中心取椭圆盘 ——
        // 中心盘 + 强质心惩罚会把每颗新星的择优结果都拽回屏幕中央，星全挤成一坨。
        float padX = 8f * d, top = 10f * d, bot = 10f * d;
        // 扫描早期（星还很少）先在**屏内**生长，免得刚冒出来的星就落在屏幕外看不见；
        // 星多起来（>12 颗）再铺到整片星区 —— 铺开由扫描收尾的 settleFixedLayout 统一完成。
        float spread = scanStars.size() <= 12 ? 1f : WORLD_SPREAD;
        float gw = w * spread - padX * 2f, gh = h * spread - top - bot;
        float ux0 = w * 0.5f - gw * 0.5f, ux1 = w * 0.5f + gw * 0.5f;   // 星区左右界
        float uy0 = h * 0.5f - gh * 0.5f, uy1 = h * 0.5f + gh * 0.5f;   // 星区上下界
        float need = (s.coreDp + minCoreDpLocked()) * 2.6f * d + 22 * d;
        float bestScore = -Float.MAX_VALUE;
        float bx = ux0 + (ux1 - ux0) * 0.5f, by = uy0 + (uy1 - uy0) * 0.5f;
        for (int i = 0; i < 28; i++) {
            float px = ux0 + (ux1 - ux0) * scanRandom.nextFloat();
            float py = uy0 + (uy1 - uy0) * scanRandom.nextFloat();
            float minDist = Float.MAX_VALUE;
            for (Star o : scanStars) {
                float dx = px - o.x, dy = py - o.y;
                minDist = Math.min(minDist, (float) Math.sqrt(dx * dx + dy * dy));
            }
            float score = Math.min(minDist / need, 1.5f) * 200f;
            // 打破平局：星一多，绝大多数候选都顶到 300 分同分，而 `>` 严格比较会一直
            // 保留第一个候选 → 所有星退化落到同一个点上。加 0-1 微扰同分即按随机取。
            score += scanRandom.nextFloat();
            if (n > 2) {
                // 弱居中：只压「整片质心飘走」，量纲归一化（占短边百分比×60），
                // 不再是百像素级的强惩罚 —— 否则四角候选永远输给中心。
                float ncx = (mx * n + px) / (n + 1), ncy = (my * n + py) / (n + 1);
                float off = (float) Math.sqrt((ncx - w * 0.5f) * (ncx - w * 0.5f)
                        + (ncy - h * 0.5f) * (ncy - h * 0.5f));
                score -= Math.min(60f, off / Math.max(1f, Math.min(w, h)) * 60f);
            }
            if (score > bestScore) { bestScore = score; bx = px; by = py; }
        }
        s.x = bx;
        s.y = by;
    }

    /** 当前最小恒星核心（间距按两星核心之和估算）。 */
    private float minCoreDpLocked() {
        float m = 11f;
        for (Star o : scanStars) m = Math.min(m, o.coreDp);
        return m;
    }

    /** 布局变化时重新散布全部恒星（保持质心居中的自然分布）。 */
    private void relayoutStars() {
        synchronized (scanStars) {
            for (Star s : scanStars) placeStarLocked(s);
        }
    }

    public boolean isScanFieldActive() {
        return scanField;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        this.w = w;
        this.h = h;
        skyGradient = null;   // 重建天空渐变（轨道为世界坐标，尺寸变化无需重建）
        if (w <= 0 || h <= 0) return;
        // 固定随机种子：布局变化时星点位置不跳动
        Random rnd = new Random(0x501A);

        // 背景星点：参考图同屏 300-500 颗——数量翻倍、星尘级半径、淡而可见
        int n = Math.max(100, w * h / 5000);
        stars = new float[n][4];
        for (int i = 0; i < n; i++) {
            stars[i][0] = rnd.nextFloat();
            stars[i][1] = rnd.nextFloat();
            stars[i][2] = 0.6f + rnd.nextFloat() * 1.6f;  // sprite 半径 0.6-2.2dp
            stars[i][3] = 26 + rnd.nextInt(74);           // alpha 26-100
        }

        // 3 颗亮星偏上半区（避开底部按钮区），带缓慢闪烁
        bigStars = new float[3][4];
        for (int i = 0; i < 3; i++) {
            bigStars[i][0] = 0.08f + 0.84f * rnd.nextFloat();
            bigStars[i][1] = 0.06f + 0.5f * rnd.nextFloat();
            bigStars[i][2] = 2.2f + rnd.nextFloat() * 1.2f;
            bigStars[i][3] = rnd.nextFloat() * 6.2832f;
        }
        if (scanField) relayoutStars();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (w <= 0 || h <= 0) return;
        float d = densityCache != 0 ? densityCache
                : (densityCache = getResources().getDisplayMetrics().density);
        long t = SystemClock.uptimeMillis();

        // 夜空背景：暗色=主题色调深空；亮色=主题色调晨空（均由 colorPrimary 派生）
        if (skyGradient == null) {
            skyGradient = new LinearGradient(0, 0, 0, h,
                    skyTopColor, skyBottomColor, Shader.TileMode.CLAMP);
        }
        if (!soloMode) {
            skyPaint.setShader(skyGradient);
            c.drawRect(0, 0, w, h, skyPaint);
            skyPaint.setShader(null);
        }
        float mr = Math.max(w, h);
        // 星云斑块：仅暗色主题（亮色主题的日间天空无星云）
        if (!soloMode && nightMode) {
            drawNebula(c, w * 0.20f, h * 0.30f, mr * 0.30f, 0.6f);
            drawNebula(c, w * 0.76f, h * 0.70f, mr * 0.26f, 0.5f);
            drawNebula(c, w * 0.52f, h * 0.14f, mr * 0.20f, 0.35f);
            drawNebula(c, w * 0.06f, h * 0.82f, mr * 0.22f, 0.35f);
        }

        // 拖动平移 + 缩放（扫描星域时星图可交互，全元素随动）
        c.save();
        c.translate(panX, panY);
        c.scale(scale, scale, 0f, 0f);
        // 平铺范围：按 pan/scale 动态求可视区覆盖的 tile（全域散布后 pan 可达 ±0.8 视口，拖远不露黑）
        float inv = 1f / scale;
        int tileX0 = (int) Math.floor((-panX * inv) / w), tileX1 = (int) Math.floor(((w - panX) * inv) / w);
        int tileY0 = (int) Math.floor((-panY * inv) / h), tileY1 = (int) Math.floor(((h - panY) * inv) / h);

        // 普通星点（平铺）——真星图风格：无正交网格，只有星点与亮星
        starPaint.setColor(baseColor);
        for (int ty = tileY0; ty <= tileY1; ty++) {
            for (int tx = tileX0; tx <= tileX1; tx++) {
                if (soloMode) continue;                 // 单星模式：不铺背景星尘
                float ox = tx * w, oy = ty * h;
                for (float[] s : stars) {
                    // 柔光精灵：星尘级小光点（几乎融入背景，撑起繁星密度）
                    float r = s[2] * d * 2.2f;
                    spritePaint.setAlpha((int) s[3]);
                    tmpRect.set(ox + s[0] * w - r, oy + s[1] * h - r,
                            ox + s[0] * w + r, oy + s[1] * h + r);
                    c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
                }
            }
        }

        // 亮星：单层点扩散精灵（过曝白核+一体晕），alpha 缓慢闪烁（平铺）
        for (int ty = tileY0; ty <= tileY1; ty++) {
            for (int tx = tileX0; tx <= tileX1; tx++) {
                if (soloMode) continue;                 // 单星模式：不铺背景亮星
                float ox = tx * w, oy = ty * h;
                for (float[] b : bigStars) {
                    float phase = b[3] + (t % 4000) / 4000f * 6.2832f;
                    int alpha = Math.max(60, 170 + (int) (Math.sin(phase) * 70));
                    float cx = ox + b[0] * w, cy = oy + b[1] * h;
                    float gr = b[2] * d * 3.2f;
                    spritePaint.setAlpha(alpha);
                    tmpRect.set(cx - gr, cy - gr, cx + gr, cy + gr);
                    c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
                }
            }
        }

        // 扫描星域（参考图摄影感星空）：
        // 纯星点海洋——无连线/无轨道环/无几何元素；每颗星 = 单层点扩散精灵
        // （中心过曝白平台 + 一体晕）；威胁语义只在点按信息卡中呈现
        if (scanField) {
            float rot = (t - scanFieldStart) / 1000f * 0.03f;
            synchronized (scanStars) {
                // 星座连线已按需求取消：星图只保留纯星点海洋（威胁语义只在点按信息卡中呈现）
                for (Star s : scanStars) {
                    // 恒星出生动画：400ms easeOutCubic 淡入+长大，与首批行星飞出同步绽放
                    float starBorn = Math.min(1f, (t - s.bornAt) / 400f);
                    if (starBorn <= 0) continue;
                    float starEase = 1f - (1f - starBorn) * (1f - starBorn) * (1f - starBorn);
                    float breath = 1f + (float) Math.sin(t / 2600.0 + s.phase) * 0.09f;
                    boolean isFolder = s.key.startsWith("folder:");
                    float bandIn = isFolder ? 10f : Math.max(7f, s.coreDp * 2f);
                    float bandW = isFolder ? 18f : 10f;
                    // 核心：幂律大小分布（已放大）——多数星 1.0-1.6dp，最大者 ~4.2dp；
                    // 叠加个体慢周期扰动（±5%，7.3s，相位错开）——细微的温度/大小变化
                    float jitter = 1f + (float) Math.sin(t / 7300.0 + s.phase * 1.7f) * 0.05f;
                    float coreR = (1.0f + s.magT * s.magT * 3.2f) * d * breath * starEase * jitter;
                    // 单层 PSF 精灵：晕仅核心 ~2.6 倍；可见度下限 6dp（区别于背景星尘）
                    // 详情抽屉的单星模式额外放大（只放大恒星本体，行星轨道保持原尺寸）
                    // 恒星大小只由星等/相机决定（点选是通过相机拉近视角，不是把单颗星画大）
                    float r = Math.max(coreR * 2.6f, 6f * d) * starEase * soloZoom;
                    // 色温连续混合：按星等在 邻档精灵 间插值（蓝白↔白↔暖白），
                    // 再叠加缓慢漂移（±5%，6.1s）——恒星色温细微变化而非三档跳变
                    android.graphics.Bitmap mainS, blendS = null;
                    float blendA;
                    float mt = s.magT;
                    if (mt >= 0.62f) {
                        mainS = coolSprite;
                        blendS = glowSprite;
                        blendA = (mt - 0.62f) / 0.38f * 0.3f;
                    } else if (mt >= 0.42f) {
                        mainS = coolSprite;
                        blendS = glowSprite;
                        blendA = (0.62f - mt) / 0.20f;
                    } else if (mt >= 0.30f) {
                        mainS = glowSprite;
                        blendS = warmSprite;
                        blendA = (0.30f - mt) / 0.12f * 0.5f;
                    } else {
                        mainS = warmSprite;
                        blendS = glowSprite;
                        blendA = mt / 0.30f * 0.3f;
                    }
                    blendA = Math.max(0f, Math.min(0.6f,
                            blendA + (float) Math.sin(t / 6100.0 + s.phase * 2.3f) * 0.05f));
                    int baseA = (int) ((150 + s.magT * 105) * starBorn);
                    if (blendS != null && blendA > 0.01f) {
                        tmpRect.set(s.x - r, s.y - r, s.x + r, s.y + r);
                        spritePaint.setAlpha((int) (baseA * (1f - blendA)));
                        c.drawBitmap(mainS, null, tmpRect, spritePaint);
                        spritePaint.setAlpha((int) (baseA * blendA));
                        c.drawBitmap(blendS, null, tmpRect, spritePaint);
                    } else {
                        spritePaint.setAlpha(baseA);
                        tmpRect.set(s.x - r, s.y - r, s.x + r, s.y + r);
                        c.drawBitmap(mainS, null, tmpRect, spritePaint);
                    }
                    // 行星：环绕的小星点（同款单层精灵）
                    for (Planet p : s.planets) {
                        float born = Math.min(1f, (t - p.bornAt) / 400f);
                        if (born <= 0) continue;
                        float ease = 1f - (1f - born) * (1f - born) * (1f - born); // easeOutCubic
                        float ang = p.angle + rot;
                        // 行星轨道随选中星一起放大，但不超出可视区短边 42%
                        float bandMax = (bandIn + bandW) * d;
                        float orbitFs = Math.min(1f,
                                (0.42f * Math.min(visW(), h)) / Math.max(1f, bandMax));
                        float rr = (bandIn + p.rRatio * bandW) * ease * d * orbitFs;
                        float px = s.x + (float) Math.cos(ang) * rr;
                        float py = s.y + (float) Math.sin(ang) * rr;
                        float pr = Math.max(p.rDp * d * 2f, 1.9f * d) * born;
                        spritePaint.setAlpha((int) (95 * born));
                        tmpRect.set(px - pr, py - pr, px + pr, py + pr);
                        c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
                    }
                }
            }
        }
        c.restore();

        // 主界面流星（等待扫描时）：随机每隔 4-10s 一场，每场 1-5 颗错峰飞过；
        // 头部亮点 + 反速度方向渐隐尾迹，生命末段淡出，出屏/超时移除
        if (!scanField) {
            if (nextMeteorAt == 0) {
                nextMeteorAt = t + 3000 + scanRandom.nextInt(5000);
            }
            if (t >= nextMeteorAt && meteors.size() < 10) {
                int n = 1 + scanRandom.nextInt(5);
                // 同一场流星方向一致（公共角度与朝向），只随机起点/速度/错峰时刻
                float ang = (float) Math.toRadians(18 + scanRandom.nextInt(22));
                float cosA = (float) Math.cos(ang), sinA = (float) Math.sin(ang);
                int dir = scanRandom.nextBoolean() ? 1 : -1;
                for (int i = 0; i < n; i++) {
                    float speed = (0.35f + scanRandom.nextFloat() * 0.3f) * d;
                    meteors.add(new float[]{
                            w * (0.05f + scanRandom.nextFloat() * 0.95f),
                            h * scanRandom.nextFloat() * 0.35f,
                            dir * cosA * speed,
                            sinA * speed,
                            t + i * (180 + scanRandom.nextInt(220)),
                            900 + scanRandom.nextInt(500),
                            (45 + scanRandom.nextFloat() * 55) * d});
                }
                nextMeteorAt = t + 4000 + scanRandom.nextInt(6000);
            }
            for (int i = meteors.size() - 1; i >= 0; i--) {
                float[] m = meteors.get(i);
                float age = t - m[4];
                if (age < 0) continue;                     // 错峰未出生
                float life = m[5];
                if (age > life) { meteors.remove(i); continue; }
                float px = m[0] + m[2] * age;
                float py = m[1] + m[3] * age;
                if (px < -80 || px > w + 80 || py > h + 80) {
                    meteors.remove(i);
                    continue;
                }
                float frac = age / life;
                float fade = frac < 0.15f ? frac / 0.15f
                        : frac > 0.6f ? Math.max(0f, 1f - (frac - 0.6f) / 0.4f) : 1f;
                // 尾迹：反速度方向，长度不超过已飞距离
                float speedLen = (float) Math.hypot(m[2], m[3]);
                float tailLen = Math.min(m[6], speedLen * age);
                float tx = px - m[2] / speedLen * tailLen;
                float ty = py - m[3] / speedLen * tailLen;
                flarePaint.setColor(baseColor);
                flarePaint.setStrokeWidth(d * 4f);
                flarePaint.setAlpha((int) (36 * fade));
                c.drawLine(px, py, tx, ty, flarePaint);
                flarePaint.setStrokeWidth(d * 1.4f);
                flarePaint.setAlpha((int) (150 * fade));
                c.drawLine(px, py, tx, ty, flarePaint);
                // 头部亮点
                float hr = 2.6f * d;
                spritePaint.setAlpha((int) (230 * fade));
                tmpRect.set(px - hr, py - hr, px + hr, py + hr);
                c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
            }
        }

        // ---- 常驻卫星轨道（深扫发射后）：世界坐标大圆，随星图位移/缩放同步移动；
        //      圆心在「地球」（屏下方），可视区内只露上缘一道弧；
        //      圆过发射动画真实释放点 → 交棒位置零跳变；朝向偏移从 0 起算 → 方向零跳变；
        //      本体与 RocketIntroView.drawSatellite 同尺寸同画法 → 大小零跳变 ----
        if (satelliteOrbit && w > 0 && h > 0) {
            if (!satAnchorSet) {
                // 首帧锚定：两条独立轨道 ——
                //  卫星轨道：圆过其自身释放高度点（圆心 x = 屏中轴）；
                //  载荷轨道：同圆心 x、同半径，圆过真实释放点 → 屏上两条平行弧；
                // 入轨发生在 pan=0/scale=1 时 → 世界=屏幕，此后随星图变换同步移动
                float s0 = Math.max(0.01f, scale);
                float relWx = (satRelX - panX) / s0, relWy = (satRelY - panY) / s0;
                orbitWr = h * 2.0f / s0;
                orbitWcx = (w * 0.5f - panX) / s0;
                float dxr = relWx - orbitWcx;
                orbitWcy = relWy + (float) Math.sqrt(orbitWr * orbitWr - dxr * dxr);
                satTheta0 = (float) Math.atan2(relWy - orbitWcy, relWx - orbitWcx);
                // 载荷轨道（payRel 未设置时与卫星轨道重合兜底）
                float prX = payRelX > 0f ? payRelX : satRelX;
                float prY = payRelY > 0f ? payRelY : satRelY;
                float prWx = (prX - panX) / s0, prWy = (prY - panY) / s0;
                float pdxr = prWx - orbitWcx;
                payWcy = prWy + (float) Math.sqrt(orbitWr * orbitWr - pdxr * pdxr);
                dockThetaRel = (float) Math.atan2(prWy - payWcy, prWx - orbitWcx);
                satAnchorSet = true;
            }
            // ---- 卫星受控再入（深层 → 浅层）：无缝接管当前轨道位置，下降入大气层 ----
            if (reentryActive) {
                drawSatelliteReentry(c, t);
                postInvalidateDelayed(33);
                return;
            }
            // 对接状态机初始化（锚定完成后首帧）
            if (dockModule && !dockInited && satAnchorSet) {
                initDockSequence(t);
            }
            // 交会预调度定位（锚定完成后首帧）：**反解相位偏移** —— 使卫星在载荷
            // 分离时刻（t + dockDeployDelayMs）恰好行至释放点前方 DOCK_LEAD_DP 处。
            // 由部署时刻直接反解，任何屏幕比例/横竖屏下都保证「释放时目标在前方」
            if (dockPlacePending && !dockInited && satAnchorSet) {
                float sA = Math.max(0.01f, scale);
                float omega = (float) (2.0 * Math.PI / (double) SAT_ORBIT_MS);
                float prWx = (payRelX - panX) / sA;
                float dxT = prWx - DOCK_LEAD_DP * d / sA - orbitWcx;   // 释放点前方 48dp 的世界 x
                dxT = Math.max(-orbitWr * 0.99f, Math.min(orbitWr * 0.99f, dxT));
                float thetaTarget = (float) Math.atan2(
                        -(float) Math.sqrt(orbitWr * orbitWr - dxT * dxT), dxT);
                float baseAtDeploy = satTheta0 - (t + dockDeployDelayMs - satOrbitStart)
                        / (float) SAT_ORBIT_MS * (float) (2 * Math.PI);
                satPhaseOffset = thetaTarget - baseAtDeploy;
                dockPlacePending = false;
            }
            float s = Math.max(0.01f, scale);
            float fadeIn = Math.min(1f, (t - satOrbitStart) / 600f);
            // 轨道线：半透明主题色（世界坐标 → 屏坐标）；只画顶点 ±60°
            // 的上缘弧，避免整圆下半段回扫进屏幕中下部
            satPaint.setStyle(Paint.Style.STROKE);
            satPaint.setStrokeWidth(Math.max(1f, d * 1.1f));
            satPaint.setColor(primaryColor);
            satPaint.setAlpha((int) (64 * fadeIn));
            tmpRect.set(
                    panX + (orbitWcx - orbitWr) * s, panY + (orbitWcy - orbitWr) * s,
                    panX + (orbitWcx + orbitWr) * s, panY + (orbitWcy + orbitWr) * s);
            c.drawArc(tmpRect, -90f - ORBIT_ARC_SWEEP, ORBIT_ARC_SWEEP * 2f, false, satPaint);
            // 卫星：全程惯性系自然公转（θ 递减 = 自右向左掠过顶部弧）——
            // 交会各阶段追踪器相对目标闭合差距，目标本身永不停住
            float elapsed = t - satOrbitStart;
            float satBase = satTheta0 - elapsed / (float) SAT_ORBIT_MS * (float) (2 * Math.PI);
            float theta = satBase + satPhaseOffset;
            float wpx = orbitWcx + orbitWr * (float) Math.cos(theta);
            float wpy = orbitWcy + orbitWr * (float) Math.sin(theta);
            float px = panX + wpx * s, py = panY + wpy * s;
            // 朝向：运动切线（θ 递减 → 切线 ∝ (sinθ, −cosθ)），减去起步切线角 →
            // 释放瞬间朝向与发射动画一致（偏移 0）；入轨后 0.9s 内平滑转到 +90°
            // 行驶姿态（面板垂直于航向），沿轨保持
            float heading0 = (float) Math.atan2(
                    -(float) Math.cos(satTheta0), (float) Math.sin(satTheta0));
            float heading = (float) Math.toDegrees((float) Math.atan2(
                    -(float) Math.cos(theta), (float) Math.sin(theta)) - heading0);
            float turn = Math.min(1f, elapsed / 900f);
            turn = 1f - (1f - turn) * (1f - turn);          // easeOut
            float gr = 9f * d * s;
            spritePaint.setAlpha((int) (110 * fadeIn));
            tmpRect.set(px - gr, py - gr, px + gr, py + gr);
            c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
            c.save();
            c.translate(px, py);
            c.rotate(heading + 90f * turn);
            satPaint.setStyle(Paint.Style.STROKE);
            satPaint.setStrokeWidth(2f * d * s);
            satPaint.setColor(primaryColor);
            satPaint.setAlpha(255);
            // 主体 + 格线（与 RocketIntroView 同尺寸：20d × 14d，随缩放同步）
            float bw = 20f * d * s, bh = 14f * d * s;
            c.drawRect(-bw * 0.5f, -bh * 0.5f, bw * 0.5f, bh * 0.5f, satPaint);
            c.drawLine(-bw * 0.5f, 0f, bw * 0.5f, 0f, satPaint);
            // 天线碟（下方支杆 + 弧）
            c.drawLine(0f, bh * 0.5f, 0f, bh * 0.5f + 7f * d * s, satPaint);
            tmpRect.set(
                    -6f * d * s, bh * 0.5f + 3f * d * s, 6f * d * s, bh * 0.5f + 13f * d * s);
            c.drawArc(tmpRect, 200f, 140f, false, satPaint);
            // 太阳能板（展开末态：板长 30d × 宽 7d，铰链在主体两侧 + 框/中线/分格）
            float panelL = 30f * d * s, panelW = 7f * d * s;
            for (int side = -1; side <= 1; side += 2) {
                float hinge = side * bw * 0.5f;
                c.drawRect(side < 0f ? hinge - panelL : hinge, -panelW * 0.5f,
                        side < 0f ? hinge : hinge + panelL, panelW * 0.5f, satPaint);
                c.drawLine(hinge, 0f, hinge + side * panelL * 0.5f, 0f, satPaint);
                c.drawLine(hinge + side * panelL * 0.5f, -panelW * 0.5f,
                        hinge + side * panelL * 0.5f, panelW * 0.5f, satPaint);
            }
            c.restore();
            spritePaint.setAlpha(255);
            // 缓存屏坐标/尺寸供触摸命中（半径 = 主体半宽 + 板长）
            satScrX = px;
            satScrY = py;
            satScrR = (10f + 30f) * d * s;

            // ---- 对接舱：动态规划交会（部署 → 转移 → 追赶 → 接近 → 对接）----
            if (dockModule && satAnchorSet && !reentryActive) {
                long dt = t - dockStateStart;
                float thetaSat = theta;                  // 卫星当前角（实时巡行，含 offset）
                float omega = (float) (2.0 * Math.PI / (double) SAT_ORBIT_MS);
                float thc;                               // 追踪器角（两弧同角距 ↔ 同屏距）
                // 追踪器所在圆的圆心 y：部署/下降段在载荷轨道，下降完成后（追赶/
                // 接近/对接）必须在卫星轨道 —— 此前每帧被重新初始化回载荷弧，
                // 导致对接后舱体仍画在载荷弧上、与卫星视觉上相距甚远
                float cyCap = dockState >= 2 ? orbitWcy : payWcy;
                int orientMode = 0;                      // 0 切向（轨道飞行）/ 1 朝向卫星（终端对接）
                if (dockState == 0) {                    // 部署：定点保持于释放点，等待发射窗口
                    thc = dockThetaRel;                  // 站保持（推进器悬停），不漂移
                    float minGapA = dockGapAngle(DOCK_GAP_MIN_DP, orbitWr, d, s);
                    // 发射窗口：目标卫星须同时 (a) 越过释放点前方 minGap（兜底），
                    // 且 (b) 屏幕 x 已进入左半区（≤ 屏宽一半）—— 即卫星飞到屏幕
                    // 左半边才开始对接，使追及过程横跨屏幕更明显；
                    // 超时兜底（防止异常时序下无限等待）
                    boolean windowOpen = (thetaSat <= dockThetaRel - minGapA)
                            && (satScrX > 0f && satScrX <= getWidth() * 0.5f);
                    if ((dt >= DOCK_DEPLOY_MS && windowOpen)
                            || dt >= DOCK_DEPLOY_MS + DOCK_WINDOW_MAX_MS) {
                        dockState = 1; dockStateStart = t; dt = 0;
                    }
                } else if (dockState == 1) {             // 转移：载荷轨道 → 卫星轨道（方向随两轨相对位置；逆行/顺行 burn）
                    float k = easeInOut(clamp01(dt / (float) DOCK_DESCENT_MS));
                    cyCap = payWcy + (orbitWcy - payWcy) * k;   // 圆心 y 插值 → 平行弧间平滑转移（方向随两轨）
                    thc = dockThetaRel - omega * dt;     // 角速率 ω 连续（自保持点起算）
                    if (dt >= DOCK_DESCENT_MS) {
                        cyCap = orbitWcy;
                        // 动态规划：以下降末**实时角差**为追赶初值。目标若尚未领先
                        // 到达差距（异常时序兜底）→ 跳过追赶直接终端接近 —— 任何
                        // 情况下不穿越目标、不虚报对接
                        float g0 = thc - thetaSat;
                        if (g0 > dockGap1) {
                            dockGap0 = g0;
                            dockState = 2;               // 正常：目标在前方足够远 → 追赶
                        } else {
                            dockGap0 = Math.max(g0, dockGapAngle(DOCK_GAP_DOCK_DP, orbitWr, d, s));
                            dockApproachStartDp = Math.max(
                                    dockGap0 * orbitWr * s / d, DOCK_GAP_DOCK_DP);
                            dockState = 3;               // 已在到达点附近 → 直接终端接近
                        }
                        dockStateStart = t; dt = 0;
                    }
                } else if (dockState == 2) {             // 动态追赶：光滑 pursuit 曲线（两端速率连续）
                    float k = clamp01(dt / dockPhaseMs);
                    float sk = k * k * (3f - 2f * k);    // smoothstep：额外推进速率两端 = 0
                    // 起点速率 = 轨道速率 ω（与下降段连续），终点速率 = 目标速率 ω；
                    // 追赶路线按下降末实时角差生成，交会点由目标实际运动自然涌现
                    float gap = dockGap1 + (dockGap0 - dockGap1) * (1f - sk);
                    thc = thetaSat + gap;
                    if (dt >= dockPhaseMs) {
                        dockApproachStartDp = DOCK_GAP1_DP;
                        dockState = 3; dockStateStart = t; dt = 0;
                    }
                } else if (dockState == 3) {             // 终端接近：同轨小间距，低速 V-bar 接近
                    float k = easeInOut(clamp01(dt / (float) DOCK_APPROACH_MS));
                    float gapDp = dockApproachStartDp + (DOCK_GAP_DOCK_DP - dockApproachStartDp) * k;
                    thc = thetaSat + dockGapAngle(gapDp, orbitWr, d, s);
                    orientMode = 1;
                    if (dt >= DOCK_APPROACH_MS) { dockState = 4; dockStateStart = t; dt = 0; }
                } else {                                 // 刚性对接：随目标同轨巡行
                    thc = thetaSat + dockGapAngle(DOCK_GAP_DOCK_DP, orbitWr, d, s);
                    orientMode = 1;
                }

                // 载荷轨道弧（淡虚线）：仅部署/下降段可见，入轨转移后淡出
                if (dockState <= 1) {
                    float pa = (dockState == 1) ? (1f - clamp01(dt / (float) DOCK_DESCENT_MS)) : 1f;
                    satPaint.setStyle(Paint.Style.STROKE);
                    satPaint.setStrokeWidth(Math.max(1f, d * 1.0f));
                    satPaint.setColor(primaryColor);
                    satPaint.setAlpha((int) (42 * fadeIn * pa));
                    if (payloadDash == null) {
                        payloadDash = new android.graphics.DashPathEffect(
                                new float[]{5f * d, 7f * d}, 0f);
                    }
                    satPaint.setPathEffect(payloadDash);
                    tmpRect.set(
                            panX + (orbitWcx - orbitWr) * s, panY + (payWcy - orbitWr) * s,
                            panX + (orbitWcx + orbitWr) * s, panY + (payWcy + orbitWr) * s);
                    c.drawArc(tmpRect, -90f - ORBIT_ARC_SWEEP, ORBIT_ARC_SWEEP * 2f, false, satPaint);
                    satPaint.setPathEffect(null);
                    satPaint.setAlpha(255);
                }

                // 对接舱 世界→屏 坐标（所在圆：载荷轨道 / 卫星轨道）
                float dwx = orbitWcx + orbitWr * (float) Math.cos(thc);
                float dwy = cyCap + orbitWr * (float) Math.sin(thc);
                float dpx = panX + dwx * s, dpy = panY + dwy * s;

                float dockFade = Math.min(1f, (t - satOrbitStart) / 600f); // 与卫星同淡入
                float dgr = 7f * d * s;
                spritePaint.setAlpha((int) (90 * dockFade));
                tmpRect.set(dpx - dgr, dpy - dgr, dpx + dgr, dpy + dgr);
                c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
                c.save();
                c.translate(dpx, dpy);
                // 朝向：轨道飞行段用切向；终端/对接段对接口（前向）指向卫星
                if (orientMode == 1) {
                    float ang = (float) Math.toDegrees(
                            Math.atan2(py - dpy, px - dpx));   // 卫星方向
                    c.rotate(ang + 90f);
                } else {
                    // 朝向：交接瞬间与火箭视图一致（鼻向上，偏移 0），0.9s 内平滑
                    // 转入行驶姿态（切向）；终端/对接段对接口（前向）指向卫星
                    float dHeading = (float) Math.toDegrees((float) Math.atan2(
                            -(float) Math.cos(thc), (float) Math.sin(thc)) - heading0);
                    float dTurn = Math.min(1f, dt / 900f);
                    dTurn = 1f - (1f - dTurn) * (1f - dTurn);  // easeOut
                    c.rotate(dHeading + 90f * dTurn);
                }
                satPaint.setStyle(Paint.Style.STROKE);
                satPaint.setStrokeWidth(1.8f * d * s);
                satPaint.setColor(primaryColor);
                satPaint.setAlpha((int) (255 * dockFade));
                // 舱体（胶囊）+ 前向对接口（十字标）+ 舷窗 + 尾喷口（与 RocketIntroView 同形态）
                float cbw = 5f * d * s, cbh = 8f * d * s;
                tmpRect.set(-cbw, -cbh, cbw, cbh);
                c.drawRoundRect(tmpRect, cbw, cbw, satPaint);
                c.drawCircle(0f, -cbh - 3f * d * s, 3f * d * s, satPaint);   // 前向对接口
                c.drawCircle(0f, -cbh * 0.35f, 1.7f * d * s, satPaint);       // 舷窗
                c.drawLine(-2.4f * d * s, cbh, 2.4f * d * s, cbh, satPaint);  // 尾喷口
                // 点火可视化：下降段逆行burn（鼻向焰，渐熄）；追赶段顺行推进
                // （尾向焰，强度 = pursuit 曲线推进速率剖面，中段峰值）
                if (dockState == 1 || dockState == 2) {
                    float burn;
                    boolean nose;
                    if (dockState == 1) {
                        burn = 1f - clamp01(dt / (float) DOCK_DESCENT_MS);
                        // 转移方向：卫星弧在载荷弧下方 → 降轨逆行减速（焰朝前）；
                        // 卫星弧在上方 → 升轨顺行加速（焰朝后）
                        nose = orbitWcy >= payWcy;
                    } else {
                        float kc = clamp01(dt / dockPhaseMs);
                        burn = clamp01(6f * kc * (1f - kc));  // smoothstep 导数（峰值 1.5 → 截 1）
                        nose = false;                         // 顺行：加速，焰朝后
                    }
                    if (burn > 0.05f) {
                        float fl = (5f + 8f * burn) * d * s;
                        float baseY = nose ? -cbh - 3f * d * s : cbh;
                        float dir = nose ? -1f : 1f;
                        tmpPath.reset();
                        tmpPath.moveTo(-1.8f * d * s, baseY);
                        tmpPath.lineTo(1.8f * d * s, baseY);
                        tmpPath.lineTo(0f, baseY + dir * fl);
                        tmpPath.close();
                        satPaint.setStyle(Paint.Style.FILL);
                        satPaint.setAlpha((int) (200 * dockFade * burn));
                        c.drawPath(tmpPath, satPaint);
                        satPaint.setStyle(Paint.Style.STROKE);
                        satPaint.setAlpha((int) (255 * dockFade));
                    }
                }
                c.restore();
                spritePaint.setAlpha(255);
                satPaint.setAlpha(255);

                // 阶段标签（科普：动态规划交会阶段）
                satPaint.setStyle(Paint.Style.FILL);
                satPaint.setColor(primaryColor);
                satPaint.setAlpha((int) (150 * dockFade));
                satPaint.setTextSize(10f * d * s);
                c.drawText(DOCK_LABELS[dockState], dpx + 11f * d * s, dpy - 11f * d * s, satPaint);
                satPaint.setAlpha(255);
            }
        }

        if (isAttachedToWindow() && isShown()) postInvalidateDelayed(33);
    }

    /**
     * 绘制卫星受控再入：从捕获的轨道位置沿二次贝塞尔退轨弧下降入大气层，带主题色
     * 等离子尾迹与再入辉光，末端烧蚀淡出；对接舱若编队常驻则随卫星后方一同退轨。
     * 起点严格等于卫星上一帧轨道位置（同尺寸同画法），故位置/朝向/大小均零跳变。
     */
    private void drawSatelliteReentry(Canvas c, long t) {
        float d = densityCache != 0 ? densityCache
                : (densityCache = getResources().getDisplayMetrics().density);
        float s = Math.max(0.01f, scale);
        long dt = t - reentryStart;
        float T = (float) REENTRY_MS;
        float p = clamp01(dt / T);

        // 二次贝塞尔（世界坐标）：起点=捕获轨道位，终点=大气层；控制点垂直偏移造退轨弧
        float dx = reentryWtx - reentryWx0, dy = reentryWty - reentryWy0;
        float dist = (float) Math.hypot(dx, dy) + 1e-3f;
        float off = dist * 0.16f;
        float ctrlX = (reentryWx0 + reentryWtx) * 0.5f - dy / dist * off;   // 垂直向量 (-dy,dx)/dist
        float ctrlY = (reentryWy0 + reentryWty) * 0.5f + dx / dist * off;
        float mt = 1f - p;
        float wx = mt * mt * reentryWx0 + 2f * mt * p * ctrlX + p * p * reentryWtx;
        float wy = mt * mt * reentryWy0 + 2f * mt * p * ctrlY + p * p * reentryWty;
        float sx = panX + wx * s, sy = panY + wy * s;

        // 切线（速度方向）→ 朝向与尾迹朝向
        float dvx = 2f * mt * (ctrlX - reentryWx0) + 2f * p * (reentryWtx - ctrlX);
        float dvy = 2f * mt * (ctrlY - reentryWy0) + 2f * p * (reentryWty - ctrlY);
        float velAng = (float) Math.toDegrees((float) Math.atan2(dvy, dvx));

        float turn = clamp01(p / 0.30f);    // 前 30% 由轨道姿态转入再入姿态
        float rotateAngle = (reentryHeading0 + 90f)
                + ((velAng - 90f) - (reentryHeading0 + 90f)) * turn;

        // 轨道弧淡出（卫星已离轨）
        float orbitA = (1f - p) * 64f;
        if (orbitA > 1f) {
            satPaint.setStyle(Paint.Style.STROKE);
            satPaint.setStrokeWidth(Math.max(1f, d * 1.1f));
            satPaint.setColor(primaryColor);
            satPaint.setAlpha((int) orbitA);
            tmpRect.set(
                    panX + (orbitWcx - orbitWr) * s, panY + (orbitWcy - orbitWr) * s,
                    panX + (orbitWcx + orbitWr) * s, panY + (orbitWcy + orbitWr) * s);
            c.drawArc(tmpRect, -90f - ORBIT_ARC_SWEEP, ORBIT_ARC_SWEEP * 2f, false, satPaint);
            satPaint.setAlpha(255);
        }

        // 等离子尾迹（主题色，再入越深越亮）：近期屏幕点由近及远递减
        reentryTrail.add(0, new float[]{sx, sy});
        while (reentryTrail.size() > 16) reentryTrail.remove(reentryTrail.size() - 1);
        for (int i = 1; i < reentryTrail.size(); i++) {
            float f = 1f - (float) i / reentryTrail.size();
            satPaint.setStyle(Paint.Style.STROKE);
            satPaint.setStrokeWidth((3.4f - 2.2f * ((float) i / reentryTrail.size())) * d * s);
            satPaint.setColor(primaryColor);
            satPaint.setAlpha((int) ((60f + 150f * p) * f));
            float[] b0 = reentryTrail.get(i - 1), b1 = reentryTrail.get(i);
            c.drawLine(b0[0], b0[1], b1[0], b1[1], satPaint);
        }
        satPaint.setAlpha(255);

        // 再入辉光（火球感，主题色）
        spritePaint.setAlpha((int) Math.min(255f, 60f + 170f * p));
        float gr = (10f + 16f * p) * d * s;
        tmpRect.set(sx - gr, sy - gr, sx + gr, sy + gr);
        c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
        spritePaint.setAlpha(255);

        // 本体（与常驻卫星同尺寸同画法 → 零跳变）；末端烧蚀淡出并缩扁
        float alpha = p > 0.85f ? (1f - (p - 0.85f) / 0.15f) : 1f;
        float sb = p > 0.85f ? (0.55f + 0.45f * alpha) : 1f;
        c.save();
        c.translate(sx, sy);
        c.rotate(rotateAngle);
        c.scale(sb, sb);
        satPaint.setStyle(Paint.Style.STROKE);
        satPaint.setStrokeWidth(2f * d * s);
        satPaint.setColor(primaryColor);
        satPaint.setAlpha((int) (255f * alpha));
        float bw = 20f * d * s, bh = 14f * d * s;
        c.drawRect(-bw * 0.5f, -bh * 0.5f, bw * 0.5f, bh * 0.5f, satPaint);
        c.drawLine(-bw * 0.5f, 0f, bw * 0.5f, 0f, satPaint);
        c.drawLine(0f, bh * 0.5f, 0f, bh * 0.5f + 7f * d * s, satPaint);
        tmpRect.set(-6f * d * s, bh * 0.5f + 3f * d * s, 6f * d * s, bh * 0.5f + 13f * d * s);
        c.drawArc(tmpRect, 200f, 140f, false, satPaint);
        float panelL = 30f * d * s, panelW = 7f * d * s;
        for (int side = -1; side <= 1; side += 2) {
            float hinge = side * bw * 0.5f;
            c.drawRect(side < 0f ? hinge - panelL : hinge, -panelW * 0.5f,
                    side < 0f ? hinge : hinge + panelL, panelW * 0.5f, satPaint);
            c.drawLine(hinge, 0f, hinge + side * panelL * 0.5f, 0f, satPaint);
            c.drawLine(hinge + side * panelL * 0.5f, -panelW * 0.5f,
                    hinge + side * panelL * 0.5f, panelW * 0.5f, satPaint);
        }
        c.restore();
        satPaint.setAlpha(255);

        // 对接舱（再入前编队常驻 → 随卫星后方一起退轨）
        if (reentryHasDock) {
            float back = 16f * d;
            float dl = (float) Math.hypot(dvx, dvy) + 1e-3f;
            float dbx = sx - dvx / dl * back, dby = sy - dvy / dl * back;
            float dgr = 7f * d * s;
            spritePaint.setAlpha((int) (90f * alpha));
            tmpRect.set(dbx - dgr, dby - dgr, dbx + dgr, dby + dgr);
            c.drawBitmap(glowSprite, null, tmpRect, spritePaint);
            spritePaint.setAlpha(255);
            c.save();
            c.translate(dbx, dby);
            float ang = (float) Math.toDegrees((float) Math.atan2(sy - dby, sx - dbx));
            c.rotate(ang + 90f);
            satPaint.setStyle(Paint.Style.STROKE);
            satPaint.setStrokeWidth(1.8f * d * s);
            satPaint.setColor(primaryColor);
            satPaint.setAlpha((int) (255f * alpha));
            float cbw = 5f * d * s, cbh = 8f * d * s;
            tmpRect.set(-cbw, -cbh, cbw, cbh);
            c.drawRoundRect(tmpRect, cbw, cbw, satPaint);
            c.drawCircle(0f, -cbh - 3f * d * s, 3f * d * s, satPaint);
            c.restore();
            satPaint.setAlpha(255);
        }

        satScrX = sx; satScrY = sy; satScrR = 12f * d * s;

        if (p >= 1f) {
            reentryActive = false;
            reentryTrail.clear();
            Runnable r = reentryOnDone; reentryOnDone = null;
            disableSatelliteOrbit();      // 一并撤下对接舱
            if (r != null) r.run();
        }
    }

    // ---------- 拖动星图（仅扫描星域激活时消费触摸） ----------

    /**
     * 把某一轴的 pan 夹到「星区始终盖住可视区」的合法区间。
     *   pan 是屏幕位移（screen = pan + world·scale），可视区在**世界坐标**里等于
     *   [−pan/s, (viewSize−pan)/s]，它必须落在星区矩形内，否则会拖到星区外露出黑边：
     *   ⇒ pan ≥ viewSize − worldHi·s，且 pan ≤ −worldLo·s。
     * 星区在屏幕上比可视区还小时（缩得太小）区间为空 → 直接让星区居中。
     *
     * 注意 {@code forScale} 必须传**目标缩放**：聚焦补间的目标 scale 往往比当前 scale
     * 大好几倍，拿旧的小 scale 算限位会让区间缩成一个极小的范围，把目标 pan 误夹，
     * 选中星就被甩到屏幕边缘甚至屏外 —— 这就是「点恒星视角平移不对」的根因。
     */
    private float clampPan(float v, float viewSize, float worldCenter,
                           float worldSize, float forScale) {
        float s = Math.max(0.01f, forScale);
        float span = worldSize * OVERSCAN;
        float wLo = worldCenter - span * 0.5f, wHi = worldCenter + span * 0.5f;
        float minPan = viewSize - wHi * s;      // 世界右边界不能跑到可视区右边界之外
        float maxPan = -wLo * s;                // 世界左边界不能跑到可视区左边界之外
        if (minPan > maxPan) return viewSize * 0.5f - worldCenter * s;   // 星区比视口小 → 居中
        return Math.max(minPan, Math.min(maxPan, v));
    }

    // ---------- 数学小工具 ----------
    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float easeOut(float v) {
        float t = clamp01(v);
        return 1f - (1f - t) * (1f - t);
    }

    private static float easeInOut(float v) {
        float t = clamp01(v);
        return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
    }

    // 把「落后屏距（dp）」换算成轨道角偏移：Δθ ≈ 弧长 / 半径。
    // 这样无论轨道半径多大（2·H 大圆），追踪器都只落后目标固定屏距，恒留在顶心附近可见区
    private static float dockGapAngle(float gapDp, float radiusWorld, float density, float scale) {
        float gapWorld = gapDp * density / Math.max(0.01f, scale); // 屏距(dp) → 世界单位
        return gapWorld / Math.max(1f, radiusWorld);
    }

    /**
     * 单星模式：详情抽屉头部只画这一颗恒星（不铺夜空/星云/星尘/星座线），
     * 恒星本体按 soloZoom 放大、居中独占。详情面板的迷你星图用它绘制。
     */
    public void setSoloStarMode(boolean solo) {
        soloMode = solo;
        soloZoom = solo ? 2.0f : 1f;
        setInteractive(!solo);      // 单星模式：纯展示，不吃触摸
        postInvalidate();
    }

    /** 是否响应触摸（点选恒星 / 拖拽 / 缩放）。详情抽屉的迷你星图设为 false 当装饰用。 */
    public void setInteractive(boolean on) {
        interactive = on;
        setClickable(on);
        setFocusable(on);
        setLongClickable(on);
    }

    public boolean isInteractive() {
        return interactive;
    }

    /** 解算当前单星模式的星体参数（行星数 / 星等 / 色温 / 光谱型）。 */
    public StarMapView.StarFacts getSoloStarFacts() {
        StarFacts f = new StarFacts();
        Star s = null;
        if (soloMode) {
            synchronized (scanStars) {
                if (!scanStars.isEmpty()) s = scanStars.get(0);
            }
        }
        if (s == null) {
            f.planetCount = 0;
            f.magT = 0f;
            f.mag = 6.5d;
            f.tempK = 3000;
            f.spec = specOf(3000);
            f.radiusRs = 0.3d;
            f.massMs = 0.25d;
            return f;
        }
        f.planetCount = s.planets.size();
        f.magT = s.magT;
        // 星等：最大恒星（magT=1）为 0 等，最小为 6.5 等
        f.mag = 6.5d * (1d - Math.max(0d, Math.min(1d, s.magT)));
        // 色温：体积越大越热（蓝白），小恒星偏红；危险等级再补一档
        double t = 3000d + s.magT * 17000d + (Math.max(0, s.level) + 1) * 1100d;
        f.tempK = (int) Math.max(2800d, Math.min(30000d, t));
        f.spec = specOf(f.tempK);
        // 半径 / 质量：同样由 magT（恒星体量）推导，与星图里的星体大小同源
        double rt = Math.max(0d, Math.min(1d, s.magT));
        f.radiusRs = Math.max(0.3d, Math.min(9.0d, 0.3d + 8.7d * Math.pow(rt, 2.1d)));
        f.massMs = Math.max(0.2d, Math.min(22.0d, 0.2d + 21.8d * Math.pow(rt, 2.6d)));
        return f;
    }

    /** 色温 → 光谱型（O/B/A/F/G/K/M）。 */
    private static String specOf(int tempK) {
        if (tempK >= 30000) return "O";
        if (tempK >= 10000) return "B";
        if (tempK >= 7500) return "A";
        if (tempK >= 6000) return "F";
        if (tempK >= 5200) return "G";
        if (tempK >= 3700) return "K";
        return "M";
    }

    /** 生成径向渐变精灵（柔光/星云共用）。 */
    private static android.graphics.Bitmap makeRadialSprite(int[] colors, float[] stops) {
        int sz = 128;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas gc = new android.graphics.Canvas(bmp);
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setShader(new RadialGradient(sz / 2f, sz / 2f, sz / 2f,
                colors, stops, Shader.TileMode.CLAMP));
        gc.drawCircle(sz / 2f, sz / 2f, sz / 2f, gp);
        return bmp;
    }

    /** 连通分量大小（沿 constelLinks BFS；调用方需持有 scanStars 锁）。 */
    private int componentSizeLocked(Star start) {
        java.util.HashSet<Star> seen = new java.util.HashSet<>();
        ArrayList<Star> queue = new ArrayList<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Star cur = queue.remove(queue.size() - 1);
            for (Star[] lk : constelLinks) {
                Star other = null;
                if (lk[0] == cur) other = lk[1];
                else if (lk[1] == cur) other = lk[0];
                if (other != null && seen.add(other)) queue.add(other);
            }
        }
        return seen.size();
    }

    /** 画一块星云斑块（nebulaSprite 按强度缩放 alpha）。 */
    private void drawNebula(Canvas c, float cx, float cy, float r, float strength) {
        spritePaint.setAlpha((int) (46 * strength));
        tmpRect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawBitmap(nebulaSprite, null, tmpRect, spritePaint);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (!interactive || !scanField) return false;   // 非交互模式（详情迷你星图）：事件放行，不当作手势
        // 双指缩放优先交给 ScaleGestureDetector；缩放进行中暂停拖动
        scaleDetector.onTouchEvent(e);
        boolean scaling = scaleDetector.isInProgress();
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                cancelPanAnim();          // 用户手拖：立即接管视角补间
                scroller.forceFinished(true);
                scrollerX = 0;
                scrollerY = 0;
                dragging = true;
                downX = e.getX();
                downY = e.getY();
                lastTouchX = e.getX();
                lastTouchY = e.getY();
                if (velTracker == null) velTracker = android.view.VelocityTracker.obtain();
                velTracker.clear();
                velTracker.addMovement(e);
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!dragging || scaling) return true;
                panX = clampPan(panX + (e.getX() - lastTouchX), visW(), w * 0.5f, worldW(), scale);
                panY = clampPan(panY + (e.getY() - lastTouchY), h, h * 0.5f, worldH(), scale);
                lastTouchX = e.getX();
                lastTouchY = e.getY();
                if (velTracker != null) velTracker.addMovement(e);
                postInvalidate();
                return true;
            case android.view.MotionEvent.ACTION_UP:
                dragging = false;
                // 点按（几乎无位移）：命中测试 → 信息卡回调；否则按拖动结束处理（fling）
                float dpx = getResources().getDisplayMetrics().density;
                float mdx = e.getX() - downX, mdy = e.getY() - downY;
                boolean tapped = mdx * mdx + mdy * mdy < 64 * dpx * dpx;
                if (tapped) {
                    if (velTracker != null) { velTracker.recycle(); velTracker = null; }
                    // 命中常驻卫星 → 卫星详情（优先于恒星命中测试；命中半径不小于 24dp）
                    if (satelliteOrbit && satScrR > 0f) {
                        float sdx = e.getX() - satScrX, sdy = e.getY() - satScrY;
                        float hitR = Math.max(satScrR, 24f * dpx);
                        if (sdx * sdx + sdy * sdy <= hitR * hitR
                                && satelliteTapListener != null) {
                            satelliteTapListener.onSatelliteTapped();
                            return true;
                        }
                    }
                    Star hit = hitTestStar(e.getX(), e.getY());
                    if (starTapListener != null) {
                        if (hit != null) {
                            starTapListener.onStarTapped(hit.key, hit.label, hit.bytes,
                                    hit.level, hit.key.startsWith("app:"),
                                    hit.planets.size(), maxPlanetLevel(hit));
                        } else {
                            starTapListener.onStarTapped(null, null, 0, 0, false, 0, -1);
                        }
                    }
                    return true;
                }
                if (velTracker != null) {
                    velTracker.computeCurrentVelocity(1000);
                    float vx = velTracker.getXVelocity();
                    float vy = velTracker.getYVelocity();
                    // 快速近垂直上滑：拉出结果面板（不触发 fling）
                    if (vy < -1600 && Math.abs(vx) < Math.abs(vy)
                            && swipeUpListener != null) {
                        velTracker.recycle();
                        velTracker = null;
                        scroller.forceFinished(true);
                        swipeUpListener.onSwipeUp();
                        return true;
                    }
                    velTracker.recycle();
                    velTracker = null;
                    scrollerX = 0;
                    scrollerY = 0;
                    int lim = (int) (Math.max(w, h) * 0.8f * scale);
                    scroller.fling(0, 0, (int) vx, (int) vy, -lim, lim, -lim, lim);
                    postInvalidate();
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (velTracker != null) {
                    velTracker.recycle();
                    velTracker = null;
                }
                return true;
        }
        return true;
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (scanField && scroller.computeScrollOffset()) {
            float dx = scroller.getCurrX() - scrollerX;
            float dy = scroller.getCurrY() - scrollerY;
            panX = clampPan(panX + dx, visW(), w * 0.5f, worldW(), scale);
            panY = clampPan(panY + dy, h, h * 0.5f, worldH(), scale);
            scrollerX = scroller.getCurrX();
            scrollerY = scroller.getCurrY();
            postInvalidate();
        }
    }
}
