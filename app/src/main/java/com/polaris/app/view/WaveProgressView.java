package com.polaris.app.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.polaris.app.R;

/**
 * 水波进度球：下载进度可视化。
 *
 * 圆形容器内两层正弦波随时间相位推移，水面高度 = 进度百分比；
 * 中心显示百分比数字。随进度上升，波形振幅逐渐收窄，营造「注水」动效。
 */
public class WaveProgressView extends View {

    private static final long WAVE_DURATION_MS = 1600L;

    private final Paint waveBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveFrontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path wavePath = new Path();
    private final RectF circleRect = new RectF();

    private ValueAnimator animator;
    private float phase = 0f;      // 波形相位（0..1 循环）
    private int percent = 0;

    public WaveProgressView(Context context) {
        this(context, null);
    }

    public WaveProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int accent = ContextCompat.getColor(context, R.color.md_theme_primary);
        int accentDim = ContextCompat.getColor(context, R.color.md_theme_primaryContainer);
        int onSurface = ContextCompat.getColor(context, R.color.md_theme_onSurface);

        waveBackPaint.setColor(accentDim);
        waveBackPaint.setAlpha(140);
        waveFrontPaint.setColor(accent);
        waveFrontPaint.setAlpha(210);
        ringPaint.setColor(accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(6f);
        textPaint.setColor(onSurface);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 设置进度（0..100），并确保波形动画运行中。 */
    public void setPercent(int p) {
        this.percent = Math.max(0, Math.min(100, p));
        invalidate();
        startWave();
    }

    private void startWave() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(WAVE_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** 停止波形动画（下载结束时调用）。 */
    public void stopWave() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWave();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float diameter = Math.min(w, h) - ringPaint.getStrokeWidth() * 2;
        float cx = w / 2f;
        float cy = h / 2f;
        circleRect.set(cx - diameter / 2f, cy - diameter / 2f,
                cx + diameter / 2f, cy + diameter / 2f);

        // 进度对应的水面高度（0 = 满底，1 = 顶部）
        float level = cy + diameter / 2f - (diameter * percent / 100f);
        // 振幅随进度收窄（注水越满波越平）
        float amplitude = diameter * 0.055f * (1f - percent / 140f);

        // 后波（相位落后 0.5）
        drawWave(canvas, cx, diameter, level, amplitude, -0.5f, waveBackPaint);
        // 前波
        drawWave(canvas, cx, diameter, level, amplitude, 0f, waveFrontPaint);

        // 外环
        canvas.drawCircle(cx, cy, diameter / 2f, ringPaint);

        // 百分比数字（跟随主题反色：高进度时用白字）
        textPaint.setColor(percent >= 55 && level < cy
                ? android.graphics.Color.WHITE
                : ContextCompat.getColor(getContext(), R.color.md_theme_onSurface));
        textPaint.setTextSize(diameter * 0.22f);
        int ty = (int) (cy - ((textPaint.descent() + textPaint.ascent()) / 2f));
        canvas.drawText(percent + "%", cx, ty, textPaint);
    }

    /** 绘制一层正弦波（用裁剪限制在圆内）。 */
    private void drawWave(Canvas canvas, float cx, float diameter, float level,
                          float amplitude, float phaseOffset, Paint paint) {
        float cy = getHeight() / 2f;
        canvas.save();
        canvas.clipPath(makeCirclePath(cx, diameter));
        wavePath.reset();
        float waveLen = diameter * 0.9f;
        float startX = cx - diameter;
        wavePath.moveTo(startX, level);
        float x = startX;
        while (x <= cx + diameter) {
            // 正弦波：相位随时间推移产生流动感
            float y = level + amplitude *
                    (float) Math.sin(((x + (phase + phaseOffset) * waveLen) / waveLen) * 2 * Math.PI);
            wavePath.lineTo(x, y);
            x += diameter / 40f;
        }
        wavePath.lineTo(cx + diameter, cy + diameter);
        wavePath.lineTo(startX, cy + diameter);
        wavePath.close();
        canvas.drawPath(wavePath, paint);
        canvas.restore();
    }

    private final Path circleClipPath = new Path();

    private Path makeCirclePath(float cx, float diameter) {
        float cy = getHeight() / 2f;
        circleClipPath.reset();
        circleClipPath.addCircle(cx, cy, diameter / 2f, Path.Direction.CW);
        return circleClipPath;
    }
}
