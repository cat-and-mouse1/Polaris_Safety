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
 * 深层扫描（无障碍 / Shizuku / Root 模式）入场动画：主题色地面（右侧）→ 发射架起竖 →
 * 运载火箭就位 → 塔吊吊装卫星（卫星沿缆绳降入整流罩）→ 视角上移；范围选择完成后
 * 播发射：点火 → 地面下移出屏、火箭相对地面升空（屏幕位置缓慢爬升，始终在屏内）→
 * 整流罩张开、卫星脱出并展开太阳能板、轨道环浮现 → 卫星留在屏上 → 动画结束回调（开始扫描）。
 *
 * <p>与 ObservatoryIntroView 同一套结构：线条框架、颜色全走主题色运行时解析；
 * 地面「先向上（入场视角上移）再向下（发射时整幕下移出屏）」。
 */
public class RocketIntroView extends View {

    // ---- 入场阶段（introT ∈ [0,1] 的分界）----
    private static final float P_GROUND = 0.16f;   // 地面：从左向右擦出
    private static final float P_PAD    = 0.34f;   // 发射架 + 塔从地面升起
    private static final float P_ROCKET = 0.52f;   // 火箭起竖就位
    private static final float P_SAT    = 0.74f;   // 吊装卫星降入整流罩
    private static final float P_PAN    = 1.00f;   // 视角上移：地平线上移

    // ---- 发射阶段（launchT ∈ [0,1] 的分界）----
    private static final float L_IGNITE = 0.10f;   // 点火：尾焰 + 烟雾（地面尚未动）
    private static final float L_ASCENT = 0.55f;   // 地面下移出屏 + 火箭相对地面升空（屏内缓慢爬升）
    private static final float L_SEP    = 0.75f;   // 整流罩张开、卫星脱出；火箭继续上升离屏
    private static final float L_DEPLOY = 1.00f;   // 卫星展开太阳能板 + 轨道环；卫星停住留下

    private static final long INTRO_MS   = 2600L;
    private static final long LAUNCH_MS  = 3200L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int cPrimary, cOnPrimary, cSurface, cSurfaceVariant, cOnSurfaceVariant;

    private float introT = P_PAN;        // 入场进度（结束后停在视角上移末态）
    private float launchT;               // 发射进度
    private boolean running, launching, cancelled;

    private ValueAnimator anim;
    private Runnable introEnd, launchEnd;

    // 拦截模式：单发火箭 + 对接舱载荷（发射后与星图常驻卫星交会对接）；地面抬升时通过 interceptReveal 暴露地下功能面板
    private boolean interceptMode;
    private float[] rocketCxs;
    private boolean revealFired;
    private Runnable interceptReveal;

    public RocketIntroView(Context context) {
        super(context);
        init();
    }

    public RocketIntroView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RocketIntroView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setVisibility(GONE);
        // 所有主题色都走当前主题解析（与 ObservatoryIntroView 同一口径），不硬编码色值
        cPrimary = resolveAttr(com.google.android.material.R.attr.colorPrimary);
        cOnPrimary = resolveAttr(com.google.android.material.R.attr.colorOnPrimary);
        cSurface = resolveAttr(com.google.android.material.R.attr.colorSurface);
        cSurfaceVariant = resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant);
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

    /** 播放入场；结束后停在「视角上移」末态（地面占据下方，火箭 + 发射架完整在屏内）。 */
    public void playIntro(Runnable onEnd) {
        stopAnim();
        introEnd = onEnd;
        launching = false;
        interceptMode = false;
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

    /**
     * 播发射：点火（尾焰 + 烟雾）→ 地面下移出屏、火箭相对地面升空（屏幕位置缓慢爬升，
     * 全程在屏内）→ 整流罩张开、卫星脱出并展开太阳能板（卫星留在屏上）→ 结束回调（开始扫描）。
     */
    public void playLaunch(Runnable onEnd) {
        stopAnim();
        launchEnd = onEnd;
        running = false;
        interceptMode = false;
        launching = true;
        launchT = 0f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        setTranslationY(0f);
        setClickable(true);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(LAUNCH_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { launchT = (float) v.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                launching = false;
                setVisibility(GONE);
                setClickable(false);
                if (!cancelled && launchEnd != null) launchEnd.run();
            }
        });
        anim.start();
    }

    /**
     * 拦截模式动画：与扫描流程共用同套火箭场景，但载荷改为对接舱（非卫星），
     * 且入场末态「视角上移」时通过 interceptReveal 暴露地下功能面板。
     * 入场结束自动接续发射（火箭升空 + 对接舱展开，地面不下移，场景留在屏上），最后回调 onEnd。
     */
    public void playInterceptIntro(Runnable onReveal, Runnable onEnd) {
        stopAnim();
        interceptMode = true;
        interceptReveal = onReveal;
        revealFired = false;
        launchEnd = onEnd;
        running = true;
        launching = false;
        introT = 0f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        setTranslationY(0f);
        setClickable(true);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(INTRO_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { introT = (float) v.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                running = false;
                setClickable(false);
                if (cancelled || launchEnd == null) return;
                startInterceptLaunch();   // 入场结束 → 自动发射（火箭 + 对接舱）
            }
        });
        anim.start();
    }

    /** 拦截发射段：火箭升空 + 对接舱展开，地面不下移，结束后停在屏上（不 GONE）。 */
    private void startInterceptLaunch() {
        launching = true;
        launchT = 0f;
        setClickable(true);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(LAUNCH_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { launchT = (float) v.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                launching = false;
                setClickable(false);
                if (!cancelled && launchEnd != null) launchEnd.run();
            }
        });
        anim.start();
    }

    /** 中断（离开流程 / 返回键）：停在原处，不触发回调。 */
    public void cancel() {
        cancelled = true;
        running = false;
        launching = false;
        interceptMode = false;
        stopAnim();
        setVisibility(GONE);
        setTranslationY(0f);
        setClickable(false);
    }

    private void stopAnim() {
        if (anim != null) { anim.removeAllUpdateListeners(); anim.cancel(); anim = null; }
    }

    /**
     * 自 {@link #playInterceptIntro} 起至载荷分离的时刻（星图预调度用）：
     * 入场 INTRO_MS + 发射段进行到分离 L_SEP·LAUNCH_MS。
     */
    public long getDeployDelayMs() {
        return INTRO_MS + (long) (LAUNCH_MS * L_SEP);
    }

    /**
     * 发射动画末态卫星释放点（本视图坐标系）：out[0]=x, out[1]=y。
     * 公式与 {@link #onDraw} 发射末态完全一致（pan / netRise 全量、satY = 头锥顶 + 0.6·noseH），
     * 供星图按释放点反求轨道圆 —— 两视图同尺寸（match_parent 同层）→ 坐标 1:1，交棒零跳变。
     */
    public void getSatelliteReleasePoint(float[] out) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0 || out == null || out.length < 2) return;
        float d = getResources().getDisplayMetrics().density;
        float padH = 10f * d, towerH = 158f * d;           // 与 onDraw 同源
        float rocketH = 148f * d, noseH = 36f * d;
        float totalH = padH + towerH + 22f * d;
        // 视角上移末态：拦截模式 panMax = 0.16H（与 onDraw 同源），普通模式原公式
        float pan = interceptMode ? 0.16f * H
                : Math.min(H * 0.38f, Math.max(0f, H * 0.62f - totalH));
        float padTop = (H * 0.62f - pan) - padH;           // 地平线末态 − 台厚
        float rocketTop = padTop - rocketH;                // 头锥顶点
        out[0] = W * 0.72f;                                // 火箭中轴
        out[1] = rocketTop - 0.04f * H + noseH * 0.6f;     // netRise 全量后的释放高度
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int W = getWidth(), H = getHeight();
        float d = getResources().getDisplayMetrics().density;

        // 火箭中轴：拦截与普通模式同为单发（地面右侧）；拦截载荷为对接舱（与卫星交会对接）
        rocketCxs = new float[]{W * 0.72f};

        // ---- 阶段进度（静止时按末态绘制，与天文台一致：入场结束后场景停住在屏内）----
        float g, pad, rkt, sat, dn, lt, ig;
        float aT, sT, dT;              // 发射子阶段：升空 / 分离 / 展开
        if (launching) {
            g = pad = rkt = sat = 1f;
            dn  = 1f;
            lt  = launchT;
            ig  = clamp01(lt / L_IGNITE);
            aT  = clamp01((lt - L_IGNITE) / (L_ASCENT - L_IGNITE));
            sT  = clamp01((lt - L_ASCENT) / (L_SEP - L_ASCENT));
            dT  = clamp01((lt - L_SEP) / (L_DEPLOY - L_SEP));
        } else {
            float t = running ? introT : P_PAN;
            lt = ig = aT = sT = dT = 0f;
            g   = clamp01(t / P_GROUND);
            pad = clamp01((t - P_GROUND) / (P_PAD - P_GROUND));
            rkt = clamp01((t - P_PAD) / (P_ROCKET - P_PAD));
            sat = clamp01((t - P_ROCKET) / (P_SAT - P_ROCKET));
            dn  = clamp01((t - P_SAT) / (P_PAN - P_SAT));
            // 拦截模式：视角上移到「地面以下」露出地下功能面板时，触发一次揭示回调
            if (interceptMode && !revealFired && dn > 0.02f) {
                revealFired = true;
                if (interceptReveal != null) interceptReveal.run();
            }
        }

        // ---- 几何尺寸（视角上移幅度按总高算，保证末态完整在屏内）----
        float padH = 10f * d;                              // 发射台厚度
        float padW = 120f * d;                             // 发射台宽
        float towerH = 158f * d;                           // 塔架高
        float towerW = 24f * d;                            // 塔架宽
        float rocketW = 34f * d;                           // 火箭宽
        float rocketH = 148f * d;                          // 火箭总高（含头锥）
        float noseH = 36f * d;                             // 头锥高
        // 视角上移：地平线上移。普通模式幅度 = min(0.38H, 0.62H − 总高)；
        // 拦截模式只抬升到「地面以下」刚好露出地下面板（≈0.16H，使地平线对齐面板顶）。
        float totalH = padH + towerH + 22f * d;            // 最高物（塔架）+ 顶部余量
        float panMax = interceptMode ? 0.16f * H
                : Math.min(H * 0.38f, Math.max(0f, H * 0.62f - totalH));
        float pan = easeInOut(dn) * panMax;
        float horizon = (H * 0.62f) - pan;
        // 发射：普通模式地面下移出屏；拦截模式地面不下移（面板顶对齐地平线，场景留在屏上）
        float sceneDrop = (interceptMode && launching) ? 0f : easeInOut(aT) * 1.08f * H;
        // 火箭升空 = 地面下移量 + 屏幕坐标内的净爬升（缓慢，确保火箭始终在屏内）
        float netRise = easeInOut(aT) * 0.04f * H;
        // 分离后火箭继续上升离屏
        float sepRise = easeInOut(sT) * 0.45f * H;
        float liftOff = sceneDrop + netRise + sepRise;
        float flick = 0.72f + 0.28f * (float) Math.sin(lt * 46f * Math.PI);  // 尾焰闪烁

        float wipe = easeOut(g);

        // 整幕不透明背景（场景色）：不透出底下星图
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(cSurface);
        canvas.drawRect(0f, 0f, W, H, paint);

        // ---- 地面（纯色，无渐变；发射时随场景一起下移出屏）----
        canvas.save();
        if (wipe < 1f) canvas.clipRect(0f, 0f, Math.max(1f, W * wipe), H);
        canvas.translate(0f, sceneDrop);
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
        // 地下土层横截面（视角上移时才浮现，与天文台一致；拦截模式不画——由地下功能面板覆盖）
        if (dn > 0f && !interceptMode) {
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

        // ---- 发射场（发射台 + 勤务塔 + 火箭 + 吊装/对接舱；地面右侧单发）----
        float rise = easeOut(pad);
        float riseOff = (1f - rise) * (0.42f * H);         // 从地面之下升起
        float baseY = horizon;                             // 地平线（已随视角上移）
        float padTop = baseY - padH;                       // 发射台顶面

        canvas.save();
        // baseY 已随视角扣除 pan，这里只做「从地下升起」+「发射时下移」的偏移；
        // 裁剪到地平线以上，升起阶段地面以下的部分被地面遮挡。
        // 发射时裁剪线随地面一起下移（baseY + sceneDrop），否则台架会在旧地平线处被凭空裁断
        canvas.clipRect(0f, 0f, W, baseY + sceneDrop);
        canvas.translate(0f, riseOff + sceneDrop);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);

        for (float cx : rocketCxs) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * d);
            paint.setColor(cPrimary);
            float towerX = cx + rocketW * 0.5f + 16f * d;  // 塔架左缘
            float rocketBase = padTop;                      // 火箭底部（未升空时）
            float rocketTop = rocketBase - rocketH;         // 头锥顶点

            // 发射台（底座轮廓 + 支腿）
            canvas.drawRect(cx - padW * 0.5f, padTop, cx + padW * 0.5f, baseY, paint);
            canvas.drawLine(cx - padW * 0.5f + 8f * d, baseY, cx - padW * 0.5f + 8f * d, baseY - 6f * d, paint);
            canvas.drawLine(cx + padW * 0.5f - 8f * d, baseY, cx + padW * 0.5f - 8f * d, baseY - 6f * d, paint);

            // 勤务塔：竖直格构（两侧立柱 + 交叉斜撑 + 顶部吊臂）
            float towerTop = padTop - towerH;
            canvas.drawLine(towerX, padTop, towerX, towerTop, paint);                   // 左立柱
            canvas.drawLine(towerX + towerW, padTop, towerX + towerW, towerTop, paint); // 右立柱
            float secH = towerH / 6f;
            for (int k = 0; k < 6; k++) {
                float y0 = padTop - k * secH;
                float y1 = padTop - (k + 1) * secH;
                canvas.drawLine(towerX, y0, towerX + towerW, y1, paint);                // 斜撑
                canvas.drawLine(towerX + towerW, y0, towerX, y1, paint);
                canvas.drawLine(towerX, y1, towerX + towerW, y1, paint);                // 横撑
            }
            // 吊臂：从塔顶伸到火箭中轴上方（吊装用）
            float jibTipY = towerTop - 14f * d;
            canvas.drawLine(towerX, towerTop, cx, jibTipY, paint);

            // 火箭：起竖（rkt）从台面下方升到位；发射阶段（liftOff）沿中轴升空。
            float rr = easeOut(rkt);
            float rocketOff = (1f - rr) * (rocketH + 24f * d) - liftOff;
            float clipTop = launching ? -2f * H : padTop - rocketH - noseH - 4f * d;
            canvas.save();
            canvas.clipRect(cx - rocketW * 2f, clipTop, cx + rocketW * 2f, padTop + 2f * d);
            canvas.translate(0f, rocketOff);
            drawRocket(canvas, d, cx, rocketBase, rocketW, rocketH, noseH, 1f,
                    lt > 0f ? 1f : 0f, sT);
            // 尾焰：点火后出现在喷口下方，随火箭一起升空
            if (ig > 0f) {
                float flameL = (rocketH * 0.30f + 26f * d) * flick * easeOut(ig);
                android.graphics.Path fp = new android.graphics.Path();
                fp.moveTo(cx - rocketW * 0.32f, rocketBase + 8f * d);
                fp.lineTo(cx, rocketBase + 8f * d + flameL);
                fp.lineTo(cx + rocketW * 0.32f, rocketBase + 8f * d);
                fp.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(cPrimary);
                paint.setAlpha((int) (200 * ig));
                canvas.drawPath(fp, paint);
                android.graphics.Path fp2 = new android.graphics.Path();
                fp2.moveTo(cx - rocketW * 0.16f, rocketBase + 8f * d);
                fp2.lineTo(cx, rocketBase + 8f * d + flameL * 0.55f);
                fp2.lineTo(cx + rocketW * 0.16f, rocketBase + 8f * d);
                fp2.close();
                paint.setColor(cOnPrimary);
                paint.setAlpha((int) (235 * ig));
                canvas.drawPath(fp2, paint);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * d);
            paint.setColor(cPrimary);

            // ---- 吊装载荷（sat ∈ (0,1]，仅入场）：卫星 or 对接舱沿缆绳从吊臂降入头锥 ----
            if (sat > 0f && !launching) {
                float s = easeInOut(sat);
                float satStartY = jibTipY + 6f * d;          // 吊臂下起点
                float satEndY = rocketTop + noseH * 0.6f;    // 整流罩内终点
                float satY = satStartY + (satEndY - satStartY) * s;
                paint.setStrokeWidth(1.4f * d);
                paint.setColor(cPrimary);
                paint.setAlpha((int) (255 * rr * (1f - s * 0.85f)));
                canvas.drawLine(cx, jibTipY, cx, satY - 8f * d, paint);
                float satAlpha = s < 0.8f ? 1f : 1f - (s - 0.8f) / 0.2f;
                if (interceptMode) {
                    drawDockCapsule(canvas, d, cx, satY, 1f, satAlpha, 1f);
                } else {
                    drawSatellite(canvas, d, cx, satY, 1f, satAlpha, 1f);
                }
                // 装载完成后头锥对接线收拢（两条斜线闪现闭合）
                if (s > 0.9f) {
                    float ha = (s - 0.9f) / 0.1f;
                    paint.setStrokeWidth(2.2f * d);
                    paint.setColor(cPrimary);
                    paint.setAlpha((int) (255 * ha));
                    float hw = rocketW * 0.5f;
                    canvas.drawLine(cx - hw, rocketTop + noseH, cx, rocketTop + noseH * ha, paint);
                    canvas.drawLine(cx + hw, rocketTop + noseH, cx, rocketTop + noseH * ha, paint);
                }
            }
        }
        paint.setAlpha(255);
        canvas.restore();

        // ---- 发射台烟雾：点火后随地面一起下移，在台面两侧扩散 ----
        if (launching && aT > 0f && aT < 0.5f) {
            canvas.save();
            canvas.clipRect(0f, 0f, W, baseY + sceneDrop);
            canvas.translate(0f, sceneDrop);
            float smokeA = 1f - aT / 0.5f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * d);
            paint.setColor(cOnSurfaceVariant);
            paint.setAlpha((int) (90 * smokeA));
            for (float cx : rocketCxs) {
                for (int k = 0; k < 4; k++) {
                    float ph = aT / 0.5f + k * 0.16f;
                    float sr = (0.25f + 0.55f * ph) * 46f * d;
                    float sx = cx + (k % 2 == 0 ? -1f : 1f) * (padW * 0.5f + sr * 0.5f);
                    float sy = padTop + 6f * d - ph * 18f * d;
                    canvas.drawCircle(sx, sy, sr, paint);
                }
            }
            paint.setAlpha(255);
            canvas.restore();
        }

        // ---- 星箭分离 + 载荷入轨（独立于地面坐标系：不受地面下移影响）----
        if (launching && sT > 0f) {
            for (float cx : rocketCxs) {
                // 载荷留在释放点（整流罩内位置）不再移动，火箭继续升离出屏
                float rocketTop = padTop - rocketH;
                float sepRocketTop = rocketTop - netRise;        // 分离开始时头锥顶的屏幕位置
                float satY = sepRocketTop + noseH * 0.6f;
                if (interceptMode) {
                    drawDockCapsule(canvas, d, cx, satY, 1f, 1f, easeOut(dT));
                } else {
                    drawSatellite(canvas, d, cx, satY, 1f, 1f, easeOut(dT));
                }
                // 入轨虚线环（展开过半后浮现，载荷停住留下；仅普通模式卫星——
                // 拦截载荷分离后即交由星图交会，无需入轨标记环）
                if (!interceptMode && dT > 0.5f) {
                    float oa = (dT - 0.5f) / 0.5f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.4f * d);
                    paint.setColor(cPrimary);
                    paint.setAlpha((int) (110 * oa));
                    paint.setPathEffect(new android.graphics.DashPathEffect(
                            new float[]{6f * d, 7f * d}, 0f));
                    canvas.drawOval(new android.graphics.RectF(
                            cx - 64f * d, satY - 18f * d, cx + 64f * d, satY + 18f * d), paint);
                    paint.setPathEffect(null);
                    paint.setAlpha(255);
                }
            }
        }
    }

    /**
     * 火箭（线框）：圆柱箭体 + 头锥 + 尾翼 + 喷口；fill>0 时箭体内部填场景色遮住背后线条。
     * sep>0 时头锥不再闭合：两瓣整流罩向外张开（角度随 sep 增大）。
     */
    private void drawRocket(Canvas canvas, float d, float cx, float base,
                            float w, float h, float noseH, float alpha, float fill, float sep) {
        if (alpha <= 0f) return;
        paint.setAlpha((int) (255 * alpha));
        float hw = w * 0.5f;
        float bodyTop = base - (h - noseH);
        if (fill > 0f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(cSurface);
            canvas.drawRect(cx - hw, bodyTop, cx + hw, base, paint);
            if (sep <= 0f) {
                canvas.drawArc(new android.graphics.RectF(
                        cx - hw, bodyTop - noseH, cx + hw, bodyTop + noseH), 180f, 180f, true, paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setColor(cPrimary);
        // 箭体
        canvas.drawRect(cx - hw, bodyTop, cx + hw, base, paint);
        // 头锥：闭合（半圆弧）或分离（两瓣向外张开）
        if (sep <= 0f) {
            canvas.drawArc(new android.graphics.RectF(
                    cx - hw, bodyTop - noseH, cx + hw, bodyTop + noseH), 180f, 180f, false, paint);
        } else {
            float sa = sep * 58f;                        // 两瓣张开角
            for (int side = -1; side <= 1; side += 2) {
                canvas.save();
                canvas.translate(cx + side * hw, bodyTop);
                canvas.rotate(side * sa);
                canvas.drawLine(0f, 0f, 0f, -noseH, paint);
                canvas.restore();
            }
        }
        // 水平段线（级间）
        canvas.drawLine(cx - hw, bodyTop + (base - bodyTop) * 0.38f, cx + hw,
                bodyTop + (base - bodyTop) * 0.38f, paint);
        canvas.drawLine(cx - hw, bodyTop + (base - bodyTop) * 0.72f, cx + hw,
                bodyTop + (base - bodyTop) * 0.72f, paint);
        // 尾翼（左右斜出）
        canvas.drawLine(cx - hw, base - 14f * d, cx - hw - 10f * d, base, paint);
        canvas.drawLine(cx - hw - 10f * d, base, cx - hw, base, paint);
        canvas.drawLine(cx + hw, base - 14f * d, cx + hw + 10f * d, base, paint);
        canvas.drawLine(cx + hw + 10f * d, base, cx + hw, base, paint);
        // 喷口
        canvas.drawLine(cx - hw * 0.5f, base, cx - hw * 0.62f, base + 8f * d, paint);
        canvas.drawLine(cx + hw * 0.5f, base, cx + hw * 0.62f, base + 8f * d, paint);
        canvas.drawLine(cx - hw * 0.62f, base + 8f * d, cx + hw * 0.62f, base + 8f * d, paint);
        paint.setAlpha(255);
    }

    /**
     * 卫星（线框）：主体方框 + 两侧太阳能板（unfold 0=折叠贴身，1=水平展开）+ 底部天线碟。
     * 板子展开用「铰链旋转 + 长度伸出」的合成：角度从竖直收到水平、长度同时伸足。
     */
    private void drawSatellite(Canvas canvas, float d, float cx, float cy,
                               float scale, float alpha, float unfold) {
        if (alpha <= 0f || scale <= 0f) return;
        paint.setAlpha((int) (255 * alpha));
        canvas.save();
        canvas.translate(cx, cy);
        float sc = Math.max(0.2f, scale);
        canvas.scale(sc, sc);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * d / sc);
        paint.setColor(cPrimary);
        // 主体
        float bw = 20f * d, bh = 14f * d;
        canvas.drawRect(-bw * 0.5f, -bh * 0.5f, bw * 0.5f, bh * 0.5f, paint);
        // 主体格线
        canvas.drawLine(-bw * 0.5f, 0f, bw * 0.5f, 0f, paint);
        // 天线碟（下方小弧 + 支杆）
        canvas.drawLine(0f, bh * 0.5f, 0f, bh * 0.5f + 7f * d, paint);
        canvas.drawArc(new android.graphics.RectF(
                -6f * d, bh * 0.5f + 3f * d, 6f * d, bh * 0.5f + 13f * d), 200f, 140f, false, paint);
        // 太阳能板：先折叠（竖直向下贴在主体两侧）后展开至水平
        float u = easeOut(unfold);
        float ang = (1f - u) * 84f;                       // 与水平的夹角
        float panelL = (8f + 22f * u) * d;                // 板长随展开伸长
        float panelW = 7f * d;
        for (int side = -1; side <= 1; side += 2) {
            canvas.save();
            canvas.translate(side * bw * 0.5f, 0f);       // 铰链点
            canvas.rotate(side * ang);
            // 板框 + 中线 + 分格
            canvas.drawRect(0f, -panelW * 0.5f, side * panelL, panelW * 0.5f, paint);
            canvas.drawLine(0f, 0f, side * panelL * 0.5f, 0f, paint);
            canvas.drawLine(side * panelL * 0.5f, -panelW * 0.5f,
                    side * panelL * 0.5f, panelW * 0.5f, paint);
            canvas.restore();
        }
        canvas.restore();
        paint.setAlpha(255);
    }

    /**
     * 对接舱（线框）：胶囊舱体 + 前向对接环（十字标）+ 舷窗 + 两侧短翼 + 尾喷口。
     * 发射后与星图常驻卫星交会对接（StarMapView 同形态对接舱沿轨跟随卫星）。
     */
    private void drawDockCapsule(Canvas canvas, float d, float cx, float cy,
                                 float scale, float alpha, float unfold) {
        if (alpha <= 0f || scale <= 0f) return;
        paint.setAlpha((int) (255 * alpha));
        canvas.save();
        canvas.translate(cx, cy);
        float sc = Math.max(0.2f, scale);
        canvas.scale(sc, sc);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * d / sc);
        paint.setColor(cPrimary);
        // 舱体（竖放胶囊：圆角矩形，上下半圆盖）
        float bw = 8f * d, bh = 13f * d;
        canvas.drawRoundRect(new android.graphics.RectF(-bw, -bh, bw, bh), bw, bw, paint);
        // 前向对接环（顶端软环 + 十字标）
        canvas.drawCircle(0f, -bh - 4.5f * d, 4.5f * d, paint);
        canvas.drawLine(-3f * d, -bh - 4.5f * d, 3f * d, -bh - 4.5f * d, paint);
        canvas.drawLine(0f, -bh - 7.5f * d, 0f, -bh - 1.5f * d, paint);
        // 舷窗（舱体中上部小圆）
        canvas.drawCircle(0f, -bh * 0.35f, 2.6f * d, paint);
        // 两侧短翼（展开度 unfold 控制）
        float wing = (5f + 7f * easeOut(unfold)) * d;
        canvas.drawLine(-bw, 0f, -bw - wing, 4f * d, paint);
        canvas.drawLine(bw, 0f, bw + wing, 4f * d, paint);
        // 尾部喷口
        canvas.drawLine(-3.5f * d, bh, 3.5f * d, bh, paint);
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
