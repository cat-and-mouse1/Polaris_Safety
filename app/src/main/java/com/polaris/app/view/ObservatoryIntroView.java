package com.polaris.app.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.TypedValue;   // 与 StarMapView 同口径：本项目统一用 android.util.TypedValue
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 浅层扫描（Normal 模式）入场动画：主题色地面 → 天文台 → 穹顶展开 → 望远镜伸出 → 视角向下
 * 展示地面以下（土层横截面）→ 外部把范围选择面板推上来 → 退出时整幕上移出屏，露出星图。
 *
 * <p>颜色全部走主题色（colorPrimary / colorOnSurface / colorSurface...），运行时解析，不硬编码。
 *
 * <p>层级：作为星图之上的一层、主内容之下的一个兄弟，因此云台上的范围选择面板能正常浮在这幕之上。
 */
public class ObservatoryIntroView extends View {

    // ---- 入场阶段（introT ∈ [0,1] 的分界）----
    private static final float P_GROUND = 0.18f;   // 地面：从左向右擦出
    private static final float P_BUILD  = 0.38f;   // 天文台从地面升起
    private static final float P_DOME   = 0.52f;   // 穹顶两瓣向外展开
    private static final float P_TELE   = 0.66f;   // 天文望远镜伸出
    private static final float P_DOWN   = 1.00f;   // 视角向下：地平线上移，露出地面以下

    private static final long INTRO_MS = 2600L;
    private static final long EXIT_MS  = 640L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int cPrimary, cOnPrimary, cSurface, cSurfaceVariant, cOnSurface, cOnSurfaceVariant;

    private float introT = P_DOWN;      // 入场进度（结束后停在 look-down 末态）
    private float exitT;                 // 退出进度
    private boolean running, exiting, cancelled;

    private ValueAnimator anim;
    private Runnable introEnd, exitEnd;

    public ObservatoryIntroView(Context context) {
        super(context);
        init();
    }

    public ObservatoryIntroView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ObservatoryIntroView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setVisibility(GONE);
        // 所有主题色都走当前主题解析（与 setCardSelected 同一口径），不硬编码色值
        cPrimary = resolveAttr(com.google.android.material.R.attr.colorPrimary);
        cOnPrimary = resolveAttr(com.google.android.material.R.attr.colorOnPrimary);
        cSurface = resolveAttr(com.google.android.material.R.attr.colorSurface);
        cSurfaceVariant = resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant);
        cOnSurface = resolveAttr(com.google.android.material.R.attr.colorOnSurface);
        cOnSurfaceVariant = resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    private int resolveAttr(int attr) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return Color.GRAY;
    }

    // ---------- 对外接口 ----------

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** 播放入场；结束后停在「视角向下」末态（地面占据下方大部分，上方露出星图）。 */
    public void playIntro(Runnable onEnd) {
        stopAnim();
        introEnd = onEnd;
        exiting = false;
        running = true;
        introT = 0f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        setTranslationY(0f);
        setClickable(true);            // 动画期间吞掉触摸，避免误触底下的星图
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(INTRO_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { introT = (float) v.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                running = false;
                setClickable(false);
                if (!cancelled && introEnd != null) introEnd.run();
            }
        });
        anim.start();
    }

    /** 退出：整幕（地面 + 天文台 + 望远镜）向上移出屏幕，露出星图。 */
    public void playExit(Runnable onEnd) {
        stopAnim();
        exitEnd = onEnd;
        running = false;
        exiting = true;
        exitT = 0f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        setTranslationY(0f);
        setClickable(true);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(EXIT_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> {
            exitT = (float) v.getAnimatedValue();
            setTranslationY(getHeight() * 1.08f * exitT);
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                exiting = false;
                setVisibility(GONE);
                setTranslationY(0f);
                setClickable(false);
                if (!cancelled && exitEnd != null) exitEnd.run();
            }
        });
        anim.start();
    }

    /** 中断（离开流程 / 返回键）：停在原处，不触发回调。 */
    public void cancel() {
        cancelled = true;
        running = false;
        exiting = false;
        stopAnim();
        setVisibility(GONE);
        setTranslationY(0f);
        setClickable(false);
    }

    private void stopAnim() {
        if (anim != null) { anim.removeAllUpdateListeners(); anim.cancel(); anim = null; }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int W = getWidth(), H = getHeight();
        float d = getResources().getDisplayMetrics().density;

        float t = running ? introT : P_DOWN;
        float g   = clamp01(t / P_GROUND);
        float bld = clamp01((t - P_GROUND) / (P_BUILD - P_GROUND));
        float dm  = clamp01((t - P_BUILD) / (P_DOME - P_BUILD));
        float tel = clamp01((t - P_DOME) / (P_TELE - P_DOME));
        float dn  = clamp01((t - P_TELE) / (P_DOWN - P_TELE));

        // 天文台几何尺寸（提前声明：视角下移幅度要按它算，保证末态完整在屏内）
        float bh = 54f * d;                                 // 基座（建筑）高
        float wallH = 14f * d;                              // 胶囊直壁高（半圆两侧的竖直边）
        float R = 62f * d;                                  // 穹顶半圆半径
        float ringT = 16f * d;                              // 顶盖环厚
        // 视角向下：地平线上移。下移幅度 = min(0.38H, 0.62H − 天文台总高)——
        // 固定 0.38H 会把地平线压到 0.24H，天文台(≈146d)上半截连同经线/环带/顶盖环被推出屏外
        float obsH = bh + wallH + R + ringT + 22f * d;      // 天文台总高 + 顶部余量
        float pan = easeInOut(dn) * Math.min(H * 0.38f, Math.max(0f, H * 0.62f - obsH));
        float horizon = (H * 0.62f) - pan;

        float wipe = easeOut(g);

        // 整幕不透明背景（夜空/场景色）：使半圆环内、建筑背后都是纯色，而不是透出底下星图
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(cSurface);
        canvas.drawRect(0f, 0f, W, H, paint);

        // ---- 地面（纯色，无渐变）----
        canvas.save();
        if (wipe < 1f) canvas.clipRect(0f, 0f, Math.max(1f, W * wipe), H);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(cSurfaceVariant);
        canvas.drawRect(0f, horizon, W, H, paint);
        // 地平线亮边（主题色）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);
        paint.setAlpha(230);
        canvas.drawLine(0f, horizon, W, horizon, paint);
        paint.setAlpha(255);
        // 地下土层横截面（视角向下时才浮现）
        if (dn > 0f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(cOnSurfaceVariant);
            paint.setAlpha((int) (30 * dn));
            for (int k = 0; k < 4; k++) {
                float y0 = horizon + (k * 0.17f + 0.10f) * H;
                canvas.drawRect(0f, y0, W, y0 + 0.055f * H * dn, paint);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * d);
            paint.setColor(cOnSurfaceVariant);
            paint.setAlpha((int) (70 * dn));
            for (int k = 1; k <= 5; k++) {
                float y = horizon + k * 0.13f * H;
                canvas.drawLine(0f, y, W, y, paint);
            }
            paint.setAlpha(255);
        }
        canvas.restore();

        // ---- 天文台（经典圆顶台：带门窗的方形基座 + 半圆穹顶 + 水平滑开的顶盖）----
        float rise = easeOut(bld);
        float riseOff = (1f - rise) * (0.42f * H);          // 从地面之下升起
        float cx = W * 0.72f;                               // 天文台整体移到地面右侧
        float baseY = horizon;                              // 建筑落在地平线上（随视角一起上移）
        float bw = 150f * d;                                // 基座宽
        float domeCy = baseY - bh - wallH;                  // 半圆心：胶囊上半部 = 半圆顶 + 两侧直壁

        canvas.save();
        // baseY = horizon 已随视角扣除 pan，这里只做「从地下升起」的偏移，不能再扣一次 pan；
        // 并裁剪到地平线以上，升起阶段地面以下的部分被地面遮挡
        canvas.clipRect(0f, 0f, W, baseY);
        canvas.translate(0f, riseOff);
        canvas.scale(-1f, 1f, cx, 0f);                      // 整体水平镜像：望远镜指向左侧、滑环滑向右肩

        // 基座建筑
        android.graphics.RectF house = new android.graphics.RectF(
                cx - bw * 0.5f, baseY - bh, cx + bw * 0.5f, baseY);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);                 // 建筑只留轮廓，不填充
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);
        paint.setAlpha((int) (255 * rise));
        canvas.drawRect(house, paint);

        // 门窗（升起完成后浮现，仅轮廓无填充）：4 扇方窗 + 中央门
        if (rise > 0.55f) {
            float wa = (rise - 0.55f) / 0.45f;
            paint.setAlpha((int) (255 * wa));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * d);
            paint.setColor(cPrimary);
            float ws = 9f * d;
            float wy = baseY - bh * 0.66f;
            float[] wxs = {-0.37f, -0.23f, 0.23f, 0.37f};
            for (float f : wxs) {
                canvas.drawRect(cx + bw * f - ws * 0.5f, wy,
                        cx + bw * f + ws * 0.5f, wy + ws, paint);
            }
            float dwW = 17f * d, dwH = bh * 0.52f;
            canvas.drawRect(cx - dwW * 0.5f, baseY - dwH, cx + dwW * 0.5f, baseY, paint);
        }
        paint.setAlpha((int) (255 * rise));

        // 天文望远镜：从穹顶中心向左上穿过开缝伸出，画在穹顶图层之下（只露出缝外部分）
        if (tel > 0f) {
            float ext = easeOut(tel);
            float len = R * 1.7f;
            canvas.save();
            canvas.translate(cx, domeCy);
            canvas.rotate(-42f);                            // 出口 318°：右上，正对滑环收拢后的开缝
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(14f * d);
            paint.setColor(cPrimary);
            canvas.drawLine(0f, 0f, len * ext, 0f, paint);
            paint.setStrokeWidth(4f * d);
            paint.setColor(cOnPrimary);
            canvas.drawLine(0f, 0f, len * ext, 0f, paint);
            // 目镜
            paint.setStyle(Paint.Style.FILL);
            float ex = len * ext;
            canvas.drawRoundRect(new android.graphics.RectF(ex - 6f * d, -9f * d, ex + 14f * d, 9f * d),
                    4f * d, 4f * d, paint);
            canvas.restore();
        }

        // 穹顶：胶囊上半部（半圆顶 + 两侧竖直边墙，坐在建筑顶上）
        android.graphics.RectF dome = new android.graphics.RectF(
                cx - R, domeCy - R, cx + R, domeCy + R);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(cSurface);
        canvas.drawRect(cx - R, domeCy, cx + R, baseY - bh, paint);   // 直壁内部
        canvas.drawArc(dome, 180f, 180f, false, paint);               // 半圆顶内部
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);
        canvas.drawArc(dome, 180f, 180f, false, paint);
        canvas.drawLine(cx - R, domeCy, cx - R, baseY - bh, paint);   // 左直壁
        canvas.drawLine(cx + R, domeCy, cx + R, baseY - bh, paint);   // 右直壁

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * d);
        paint.setColor(cPrimary);
        // 经线弧（穹顶中间的竖向弧线）
        canvas.drawArc(new android.graphics.RectF(
                cx - R * 0.5f, domeCy - R, cx + R * 0.5f, domeCy + R), 180f, 180f, false, paint);
        // 环带横线（与参考图一致的水平线）
        float bandY = domeCy - R * 0.42f;
        float bandX = R * (float) Math.sqrt(1f - 0.42f * 0.42f);
        canvas.drawLine(cx - bandX, bandY, cx + bandX, bandY, paint);

        // 顶盖（天窗）：半圆环，贴在穹顶【外侧】的外壳——
        // 外缘 Ro=R+ringT、内缘=R（贴穹顶轮廓），闭合时包住穹顶顶部，
        // 绕穹顶圆心沿球面滑走后露出内层穹顶（经线+环带）。
        // 滑动角收敛为 28°：末态环正好停在左肩线（180°），下端不越过肩线穿模
        float Ro = R + ringT;                               // 外半径
        float capA = 208f, capS = 124f;
        float capRoll = 28f * easeOut(dm);
        android.graphics.RectF capO = new android.graphics.RectF(
                cx - Ro, domeCy - Ro, cx + Ro, domeCy + Ro);
        android.graphics.RectF capI = new android.graphics.RectF(
                cx - R, domeCy - R, cx + R, domeCy + R);
        canvas.save();
        canvas.rotate(-capRoll, cx, domeCy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);
        canvas.drawArc(capO, capA, capS, false, paint);     // 外缘
        canvas.drawArc(capI, capA, capS, false, paint);     // 内缘（贴穹顶轮廓）
        double a0 = Math.toRadians(capA), a1 = Math.toRadians(capA + capS);
        canvas.drawLine((float) (cx + Ro * Math.cos(a0)), (float) (domeCy + Ro * Math.sin(a0)),
                (float) (cx + R * Math.cos(a0)), (float) (domeCy + R * Math.sin(a0)), paint);
        canvas.drawLine((float) (cx + Ro * Math.cos(a1)), (float) (domeCy + Ro * Math.sin(a1)),
                (float) (cx + R * Math.cos(a1)), (float) (domeCy + R * Math.sin(a1)), paint);
        canvas.restore();
        paint.setAlpha(255);
    }

    // ---------- 小工具 ----------

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private static float easeOut(float v) {
        float t = clamp01(v);
        return 1f - (1f - t) * (1f - t);
    }

    private static float easeInOut(float v) {
        float t = clamp01(v);
        return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
    }
}
