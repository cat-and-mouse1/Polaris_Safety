package com.polaris.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * 聚光灯教程遮罩：半透明背景 + 镂空高亮区域 + 提示卡片 + 导航按钮。
 */
public class OnboardingOverlayView extends View {

    private static final int OVERLAY_COLOR = 0x99000000;
    private static final int CARD_BG_COLOR = 0xF0FFFFFF;
    private static final int CARD_TEXT_COLOR = 0xFF1A1A1A;
    private static final int CARD_SUB_TEXT_COLOR = 0xFF666666;
    private static final int CARD_BUTTON_COLOR = 0xFF3F51B5;
    private static final int CARD_BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    private static final int STEP_TEXT_COLOR = 0xFF999999;

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardSubTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stepTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RectF highlightRect = new RectF();
    private float highlightRadius = 24f;
    private float highlightPadding = 12f;

    private String titleText = "";
    private String descText = "";
    private String buttonText = "下一步";
    private int currentStep = 1;
    private int totalSteps = 3;

    private float cardX, cardY, cardWidth, cardHeight;
    private float buttonX, buttonY, buttonWidth, buttonHeight;
    private float cardAlpha = 1f;

    private OnClickListener onNextClickListener;
    private OnClickListener onSkipClickListener;

    public OnboardingOverlayView(Context context) {
        super(context);
        init();
    }

    public OnboardingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        overlayPaint.setColor(OVERLAY_COLOR);
        overlayPaint.setStyle(Paint.Style.FILL);

        clearPaint.setColor(0);
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        cardPaint.setColor(CARD_BG_COLOR);
        cardPaint.setStyle(Paint.Style.FILL);

        cardTextPaint.setColor(CARD_TEXT_COLOR);
        cardTextPaint.setTextSize(40f);
        cardTextPaint.setTextAlign(Paint.Align.LEFT);

        cardSubTextPaint.setColor(CARD_SUB_TEXT_COLOR);
        cardSubTextPaint.setTextSize(30f);
        cardSubTextPaint.setTextAlign(Paint.Align.LEFT);

        buttonPaint.setColor(CARD_BUTTON_COLOR);
        buttonPaint.setStyle(Paint.Style.FILL);

        buttonTextPaint.setColor(CARD_BUTTON_TEXT_COLOR);
        buttonTextPaint.setTextSize(32f);
        buttonTextPaint.setTextAlign(Paint.Align.CENTER);

        stepTextPaint.setColor(STEP_TEXT_COLOR);
        stepTextPaint.setTextSize(26f);
        stepTextPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setHighlightBounds(float left, float top, float right, float bottom, float radius) {
        this.highlightRect.set(left - highlightPadding, top - highlightPadding,
                right + highlightPadding, bottom + highlightPadding);
        this.highlightRadius = radius;
        calculateLayout(getWidth(), getHeight());
        invalidate();
    }

    public void setStepInfo(int current, int total) {
        this.currentStep = current;
        this.totalSteps = total;
        invalidate();
    }

    public void setContent(String title, String desc, String btnText) {
        this.titleText = title;
        this.descText = desc;
        this.buttonText = btnText;
        invalidate();
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCardAlpha(float alpha) {
        this.cardAlpha = alpha;
        invalidate();
    }

    public float getCardAlpha() {
        return cardAlpha;
    }

    public void setOnNextClickListener(OnClickListener listener) {
        this.onNextClickListener = listener;
    }

    public void setOnSkipClickListener(OnClickListener listener) {
        this.onSkipClickListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateLayout(w, h);
    }

    private void calculateLayout(int w, int h) {
        float margin = 40f;
        cardWidth = Math.min(w - margin * 2, 700f);
        cardHeight = 260f;

        // 卡片位置：高亮区域下方，居中
        float hlCenterX = highlightRect.centerX();
        float hlBottom = highlightRect.bottom + 30f;

        if (hlBottom + cardHeight + 100f > h) {
            // 高亮区域太靠下，卡片放到上方
            cardY = highlightRect.top - cardHeight - 30f;
        } else {
            cardY = hlBottom;
        }

        cardX = Math.max(margin, Math.min(hlCenterX - cardWidth / 2f, w - margin - cardWidth));

        // 按钮位置：卡片内底部
        buttonWidth = 200f;
        buttonHeight = 64f;
        buttonX = cardX + cardWidth - buttonWidth - 24f;
        buttonY = cardY + cardHeight - buttonHeight - 20f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // 1. 绘制半透明遮罩并镂空高亮区域
        canvas.save();
        canvas.drawRect(0, 0, w, h, overlayPaint);

        if (highlightRect.width() > 0 && highlightRect.height() > 0) {
            canvas.drawRoundRect(highlightRect, highlightRadius, highlightRadius, clearPaint);
        }
        canvas.restore();

        // 2. 绘制提示卡片
        if (cardAlpha > 0.01f && !titleText.isEmpty()) {
            canvas.save();
            canvas.concat(composeMatrix());

            // 卡片背景（圆角矩形）
            RectF cardRect = new RectF(cardX, cardY, cardX + cardWidth, cardY + cardHeight);
            canvas.drawRoundRect(cardRect, 20f, 20f, cardPaint);

            // 步骤指示器 "1/3"
            String stepText = currentStep + " / " + totalSteps;
            canvas.drawText(stepText, cardX + cardWidth - 24f, cardY + 40f, stepTextPaint);

            // 标题
            canvas.drawText(titleText, cardX + 24f, cardY + 44f, cardTextPaint);

            // 描述（支持多行）
            String[] lines = descText.split("\n");
            float lineY = cardY + 90f;
            for (String line : lines) {
                canvas.drawText(line, cardX + 24f, lineY, cardSubTextPaint);
                lineY += 38f;
            }

            // 按钮
            RectF btnRect = new RectF(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight);
            canvas.drawRoundRect(btnRect, 16f, 16f, buttonPaint);
            canvas.drawText(buttonText, buttonX + buttonWidth / 2f, buttonY + buttonHeight / 2f + 10f, buttonTextPaint);

            canvas.restore();
        }
    }

    private android.graphics.Matrix composeMatrix() {
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (cardAlpha < 1f) {
            m.setScale(cardAlpha, cardAlpha, cardX + cardWidth / 2f, cardY + cardHeight / 2f);
        }
        return m;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();

            // 点击按钮
            if (buttonAlpha() > 0.5f && x >= buttonX && x <= buttonX + buttonWidth
                    && y >= buttonY && y <= buttonY + buttonHeight) {
                if (onNextClickListener != null) {
                    onNextClickListener.onClick(this);
                }
                return true;
            }

            // 点击卡片外部 = 跳过
            if (onSkipClickListener != null) {
                RectF cardRect = new RectF(cardX - 20f, cardY - 20f,
                        cardX + cardWidth + 20f, cardY + cardHeight + 20f);
                if (!cardRect.contains(x, y)) {
                    onSkipClickListener.onClick(this);
                    return true;
                }
            }
        }
        return true;
    }

    private float buttonAlpha() {
        return cardAlpha;
    }

    public void animateCardIn() {
        ObjectAnimator anim = ObjectAnimator.ofFloat(this, "cardAlpha", 0f, 1f);
        anim.setDuration(350);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
    }

    public void animateCardOut(Runnable onEnd) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(this, "cardAlpha", 1f, 0f);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEnd != null) onEnd.run();
            }
        });
        anim.start();
    }
}
