package com.polaris.app.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.polaris.app.R;

/**
 * 在自身边界内居中绘制 3 圈同心圆环（从外到内半径递减、透明度递增）。
 * 圆环颜色取自主题 colorPrimary，随动态取色（莫奈/自定义主题色）自动变化。
 *
 * 扫描模式下（setScanning(true)）3 圈装饰环保持显示（仅略压暗），进度弧按「外 → 中 → 内」
 * 接力扫过，每圈各承担 1/3 进度；setProgress(0..1) 更新整体进度。
 */
public class ConcentricRingsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int ringColor;

    private boolean scanning = false;
    private float progress = 0f;

    public ConcentricRingsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        TypedArray ta = context.getTheme().obtainStyledAttributes(new int[]{android.R.attr.colorPrimary});
        ringColor = ta.getColor(0, ContextCompat.getColor(context, android.R.color.darker_gray));
        ta.recycle();
    }

    public void setScanning(boolean scanning) {
        this.scanning = scanning;
        if (!scanning) this.progress = 0f;
        invalidate();
    }

    /** 设置扫描进度（0..1），仅扫描态生效。 */
    public void setProgress(float p) {
        this.progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = Math.min(w, h) / 2f - getStroke(2.5f);

        if (scanning) {
            // 扫描态：3 圈装饰环照旧保留（只略压暗），避免「三个环变成一个环」的观感跳变
            float[] ratios = {0.95f, 0.75f, 0.55f};
            int[] baseAlphas = {70, 150, 210};
            for (int i = 0; i < 3; i++) {
                paint.setColor(ringColor);
                paint.setAlpha(baseAlphas[i]);
                paint.setStrokeWidth(getStroke(2.5f));
                canvas.drawCircle(cx, cy, maxR * ratios[i], paint);
            }

            // 进度弧：外 → 中 → 内 接力，每圈各承担 1/3 进度，三环一起完成一次扫描
            float p = Math.max(0f, Math.min(1f, progress)) * 3f;   // 0..3
            for (int i = 0; i < 3; i++) {
                float seg = Math.max(0f, Math.min(1f, p - i));      // 该圈已扫过的比例
                if (seg <= 0f) break;
                float r = maxR * ratios[i];
                RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
                paint.setColor(ringColor);
                paint.setAlpha(255);
                paint.setStrokeWidth(getStroke(3f));
                canvas.drawArc(oval, -90f, seg * 360f, false, paint);
            }
            return;
        }

        // 常态：3 圈同心圆环（从外到内：半径比例 0.95/0.75/0.55，透明度 90/150/210）
        float[] ratios = {0.95f, 0.75f, 0.55f};
        int[] alphas = {90, 150, 210};
        for (int i = 0; i < 3; i++) {
            paint.setColor(ringColor);
            paint.setAlpha(alphas[i]);
            paint.setStrokeWidth(getStroke(2.5f));
            canvas.drawCircle(cx, cy, maxR * ratios[i], paint);
        }
    }

    private float getStroke(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
