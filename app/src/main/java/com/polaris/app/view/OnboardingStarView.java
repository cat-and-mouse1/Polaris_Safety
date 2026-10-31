package com.polaris.app.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.polaris.app.R;

/**
 * 欢迎页星图动画层（覆盖全屏、不拦截触摸）——「星引流转」三段式：
 * ① runIntro：四角星线条沿路径自绘成形（PathMeasure 逐段描绘），收尾带一次回弹自旋；
 * ② 待机：星体常驻，轻微呼吸光晕 + 小幅摆动（页面有生命感）；
 * ③ pulse：星体自旋一周 + 轻微缩放脉动，同时自星心射出扩散涟漪环（双环）与
 *    八向星尘粒子，onProgress 驱动卡片随涟漪依次上浮揭示。
 * 星体全程在场、不淡出；星体/涟漪/粒子取主题强调色，与主界面 ic_star 同一几何。
 */
public class OnboardingStarView extends View {

    /** 脉冲进度回调（t: 0→1，驱动卡片随涟漪依次揭示）。 */
    public interface PulseCallback {
        void onProgress(float t);

        void onDone();
    }

    private static final long INTRO_MS = 1400;      // 自绘 + 收尾自旋
    private static final float INTRO_DRAW_END = 0.6f; // 自绘阶段占比
    private static final long PULSE_MS = 1300;      // 单次脉冲时长
    private static final long IDLE_MS = 2800;       // 待机呼吸周期
    private static final float STAR_Y_FRAC = 0.26f;
    private static final float STAR_RADIUS_DP = 30f;
    private static final int SPARK_COUNT = 8;       // 星尘粒子数

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final Path drawPath = new Path();
    private final String caption;    // 星体右侧标题文字（随星体自绘收尾淡入）

    private int color = 0xFFFFFFFF;
    private int captionColor = 0xFFFFFFFF;  // 标题文字色（对齐主界面 homeGreeting 的 colorOnSurface）
    private float radius;            // 星体外接半径 px
    private float cx, cy;            // 星心
    private float rotation = 0f;     // 累计自转角（deg）
    private float scale = 0f;        // 星体缩放（intro 起 = 1）
    private float drawT = 0f;        // 自绘进度（0→1；=1 后整星绘制）
    private float idlePhase = 0f;    // 待机呼吸相位（rad）
    private boolean idleOn = false;  // 待机动画是否已启动
    private float pulseT = -1f;      // 脉冲进度；<0 未激活
    private PathMeasure pathMeasure;

    private ValueAnimator anim;
    private ValueAnimator idleAnim;

    public OnboardingStarView(Context context) {
        this(context, null);
    }

    public OnboardingStarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStyle(Paint.Style.FILL);
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeCap(Paint.Cap.ROUND);
        sparkPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.LEFT);
        // 主界面同位置文字为 textAppearanceTitleLarge（22sp）+ colorOnSurface，此处对齐
        textPaint.setTextSize(sp(22f));
        caption = context.getString(R.string.onboarding_star_caption);
        color = ContextCompat.getColor(context, R.color.md_theme_primary);
        textPaint.setColor(color);
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true)) {
            captionColor = ContextCompat.getColor(context, tv.resourceId);
            textPaint.setColor(captionColor);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = dp(STAR_RADIUS_DP);
        cx = w / 2f;
        cy = h * STAR_Y_FRAC;
    }

    /** 外部锚定星心位置（须在 runIntro 之前调用；布局完成后按标题上方定位）。 */
    public void setStarPosition(float x, float y) {
        cx = x;
        cy = y;
    }

    /** 实时切换星体/涟漪/粒子颜色（欢迎页主题实时应用）。 */
    public void setStarColor(int c) {
        color = c;
        textPaint.setColor(c);
        invalidate();
    }

    /** 标题文字颜色（由 applyWelcomeTheme 按 dark/light 传入，对齐主界面 colorOnSurface）。 */
    public void setCaptionColor(int c) {
        captionColor = c;
        invalidate();
    }

    /** 阶段一：星形线条自绘成形（PathMeasure）→ 回弹自旋收尾 → 进入待机，onDone 回调。 */
    public void runIntro(@Nullable final Runnable onDone) {
        cancelAnim();
        scale = 1f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(INTRO_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.2f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            if (t <= INTRO_DRAW_END) {
                drawT = t / INTRO_DRAW_END;              // 自绘成形
            } else {
                drawT = 1f;
                float s = (t - INTRO_DRAW_END) / (1f - INTRO_DRAW_END);
                rotation = 360f * s;                     // 收尾回弹自旋
            }
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                drawT = 1f;
                rotation = 0f;   // ≡0 (mod 90)：一角正上，与 ic_star 姿态一致
                startIdle();
                invalidate();
                if (onDone != null) onDone.run();
            }
        });
        anim.start();
    }

    /** 待机：呼吸光晕 + 小幅摆动（无限循环；脉冲时暂停、结束后恢复）。 */
    private void startIdle() {
        stopIdle();
        idleOn = true;
        idleAnim = ValueAnimator.ofFloat(0f, 1f);
        idleAnim.setDuration(IDLE_MS);
        idleAnim.setRepeatCount(ValueAnimator.INFINITE);
        idleAnim.addUpdateListener(a -> {
            idlePhase = (float) a.getAnimatedValue() * 2f * (float) Math.PI;
            invalidate();
        });
        idleAnim.start();
    }

    private void stopIdle() {
        if (idleAnim != null) {
            idleAnim.cancel();
            idleAnim = null;
        }
    }

    /** 阶段二：星体自旋一周 + 缩放脉动，涟漪双环与八向星尘自星心扩散（onProgress 驱动揭示）。 */
    public void pulse(long durationMs, @Nullable final PulseCallback cb) {
        cancelAnim();
        stopIdle();
        final float startRot = rotation;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs <= 0 ? PULSE_MS : durationMs);
        anim.setInterpolator(new DecelerateInterpolator(1.0f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pulseT = t;
            rotation = startRot + 360f * t;              // 恰好一周，收尾姿态不变
            invalidate();
            if (cb != null) cb.onProgress(t);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                pulseT = -1f;
                startIdle();
                invalidate();
                if (cb != null) cb.onDone();
            }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnim();
        stopIdle();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (scale <= 0f || drawT <= 0f) return;
        final int w = getWidth(), h = getHeight();

        // 待机呼吸光晕（径向渐隐；脉冲期由涟漪/粒子接管视觉）
        if (idleOn) {
            float glowA = 22f + 12f * (float) Math.sin(idlePhase);
            glowPaint.setShader(new RadialGradient(cx, cy, radius * 2.4f,
                    withAlpha(color, (int) glowA), withAlpha(color, 0), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, radius * 2.4f, glowPaint);
            glowPaint.setShader(null);
        }

        // 扩散涟漪双环：主环 + 延迟 0.12 的跟随环，向外扩散并渐隐
        if (pulseT >= 0f) {
            final float maxR = (float) Math.hypot(w, h);
            drawRipple(c, progress(pulseT, 0f, 0.85f), maxR, 150f);
            drawRipple(c, progress(pulseT, 0.12f, 0.97f), maxR, 90f);

            // 八向星尘：小菱形粒子沿固定角度飞散、渐隐（随星体微旋，方向更灵动）
            float sp = progress(pulseT, 0.08f, 0.7f);
            if (sp > 0f && sp < 1f) {
                float dist = dp(88f) * (1f - (1f - sp) * (1f - sp)); // easeOut
                int alpha = (int) (220f * (1f - sp));
                float size = dp(3f) * (1f - 0.4f * sp);
                sparkPaint.setColor(withAlpha(color, alpha));
                for (int i = 0; i < SPARK_COUNT; i++) {
                    double rad = Math.toRadians(i * (360f / SPARK_COUNT) - 90f
                            + rotation * 0.25f);
                    float px = cx + (float) Math.cos(rad) * dist;
                    float py = cy + (float) Math.sin(rad) * dist;
                    c.save();
                    c.translate(px, py);
                    c.rotate(45f);
                    c.drawRect(-size, -size, size, size, sparkPaint);
                    c.restore();
                }
            }
        }

        // 线条四角星：与主界面 ic_star 同一几何（弧线内凹四角星、圆角描边）。
        // ic_star 200 视口：尖端半径 88、控制点距 24、描边 9，此处按 radius 等比缩放
        float r = radius;
        float tip = r;
        float ctl = r * 24f / 88f;
        starPath.reset();
        starPath.moveTo(0, -tip);                       // 北尖
        starPath.cubicTo(0, -ctl, ctl, 0, tip, 0);      // 北 → 东（弧线内凹）
        starPath.cubicTo(ctl, 0, 0, ctl, 0, tip);       // 东 → 南
        starPath.cubicTo(0, ctl, -ctl, 0, -tip, 0);     // 南 → 西
        starPath.cubicTo(-ctl, 0, 0, -ctl, 0, -tip);    // 西 → 北
        starPath.close();

        strokePaint.setColor(withAlpha(color, 255));
        strokePaint.setStrokeWidth(radius * 9f / 88f);
        // 脉冲期轻微缩放脉动（1 → 1.12 → 1），星体更有弹性
        float bump = pulseT >= 0f ? 1f + 0.12f * (float) Math.sin(Math.PI * pulseT) : 1f;
        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        c.scale(bump, bump);
        if (drawT < 1f) {
            // 自绘阶段：沿路径逐段描绘
            if (pathMeasure == null) pathMeasure = new PathMeasure(starPath, false);
            drawPath.reset();
            pathMeasure.getSegment(0, drawT * pathMeasure.getLength(), drawPath, true);
            c.drawPath(drawPath, strokePaint);
        } else {
            c.drawPath(starPath, strokePaint);
        }
        c.restore();

        // 星体右侧标题文字：与星心垂直居中，随自绘收尾（最后 30%）淡入；
        // 颜色随主题强调色联动（setStarColor），间距 16dp
        if (drawT > 0.7f) {
            textPaint.setColor(captionColor);
            textPaint.setAlpha((int) (255f * Math.min(1f, (drawT - 0.7f) / 0.3f)));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            c.drawText(caption, cx + radius + dp(16f), baseline, textPaint);
        }
    }

    /** 单条涟漪环：p 0→1 时半径自星心扩至 maxR、透明度渐隐。 */
    private void drawRipple(Canvas c, float p, float maxR, float baseAlpha) {
        if (p <= 0f || p >= 1f) return;
        ripplePaint.setColor(withAlpha(color, (int) (baseAlpha * (1f - p))));
        ripplePaint.setStrokeWidth(dp(2f) * (1f - 0.5f * p));
        c.drawCircle(cx, cy, maxR * p, ripplePaint);
    }

    /** 子区间进度：把整体 t 映射到 [start, end) → [0, 1)，并夹取。 */
    private static float progress(float t, float start, float end) {
        return Math.max(0f, Math.min(1f, (t - start) / (end - start)));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
