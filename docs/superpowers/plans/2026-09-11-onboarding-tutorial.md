# Onboarding Tutorial Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-launch onboarding tutorial with animated welcome screen and feature introduction overlay.

**Architecture:** New `OnboardingActivity` as launcher, custom `OnboardingOverlayView` for dark overlay with highlight cutout and animated guide icon with trail effect. Prefs tracks tutorial completion state.

**Tech Stack:** Android SDK (ValueAnimator, Canvas, PorterDuff), Java, Material Design components

---

### Task 1: Prefs — Add Tutorial State

**Files:**
- Modify: `app/src/main/java/com/polaris/app/util/Prefs.java`

- [ ] **Step 1: Add KEY_TUTORIAL_DONE constant**

Add after line 43 (`KEY_LAST_SCAN_MALICIOUS`):
```java
private static final String KEY_TUTORIAL_DONE = "tutorial_done";
```

- [ ] **Step 2: Add getter and setter methods**

Add after `incrementScanCount()` method (after line 596):
```java
// ---------- 教程状态 ----------

public boolean isTutorialDone() {
    return sp.getBoolean(KEY_TUTORIAL_DONE, false);
}

public void setTutorialDone() {
    sp.edit().putBoolean(KEY_TUTORIAL_DONE, true).apply();
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/polaris/app/util/Prefs.java
git commit -m "feat: add tutorial completion state to Prefs"
```

---

### Task 2: String Resources — Add Tutorial Text

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`

- [ ] **Step 1: Add Chinese strings**

Add before `</resources>` in `values/strings.xml`:
```xml
    <!-- 教程 -->
    <string name="tutorial_welcome_title">欢迎使用 Polaris</string>
    <string name="tutorial_welcome_subtitle">守护你的设备安全</string>
    <string name="tutorial_enter">开始体验</string>
    <string name="tutorial_scan_title">全面扫描</string>
    <string name="tutorial_scan_desc">一键扫描所有应用，检测恶意程序</string>
    <string name="tutorial_next">下一步</string>
    <string name="tutorial_guard_prompt">向右滑动打开守护面板</string>
    <string name="tutorial_guard_desc">守护面板实时监控设备安全</string>
    <string name="tutorial_got_it">我知道了</string>
```

- [ ] **Step 2: Add English strings**

Add before `</resources>` in `values-en/strings.xml`:
```xml
    <!-- Tutorial -->
    <string name="tutorial_welcome_title">Welcome to Polaris</string>
    <string name="tutorial_welcome_subtitle">Protecting your device</string>
    <string name="tutorial_enter">Get Started</string>
    <string name="tutorial_scan_title">Full Scan</string>
    <string name="tutorial_scan_desc">Scan all apps to detect malware</string>
    <string name="tutorial_next">Next</string>
    <string name="tutorial_guard_prompt">Swipe right to open Guard panel</string>
    <string name="tutorial_guard_desc">Guard panel monitors device security</string>
    <string name="tutorial_got_it">Got it</string>
```

- [ ] **Step 3: Add Japanese strings**

Add before `</resources>` in `values-ja/strings.xml`:
```xml
    <!-- チュートリアル -->
    <string name="tutorial_welcome_title">Polarisへようこそ</string>
    <string name="tutorial_welcome_subtitle">デバイスを守ります</string>
    <string name="tutorial_enter">始める</string>
    <string name="tutorial_scan_title">全面スキャン</string>
    <string name="tutorial_scan_desc">すべてのアプリをスキャンして悪意のあるプログラムを検出</string>
    <string name="tutorial_next">次へ</string>
    <string name="tutorial_guard_prompt">右にスワイプしてガードパネルを開く</string>
    <string name="tutorial_guard_desc">ガードパネルはデバイスのセキュリティをリアルタイムで監視</string>
    <string name="tutorial_got_it">了解</string>
```

- [ ] **Step 4: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml app/src/main/res/values-ja/strings.xml
git commit -m "feat: add tutorial string resources (zh/en/ja)"
```

---

### Task 3: Welcome Page Layout

**Files:**
- Create: `app/src/main/res/layout/activity_onboarding.xml`

- [ ] **Step 1: Create welcome layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/onboardingRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#1A237E">

    <!-- 应用图标 -->
    <ImageView
        android:id="@+id/welcomeIcon"
        android:layout_width="80dp"
        android:layout_height="80dp"
        android:layout_gravity="center"
        android:src="@drawable/ic_launcher_foreground"
        android:scaleX="0"
        android:scaleY="0"
        app:tint="#FFFFFF" />

    <!-- 欢迎文字区域 -->
    <LinearLayout
        android:id="@+id/welcomeTextGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="200dp"
        android:gravity="center"
        android:orientation="vertical"
        android:alpha="0">

        <TextView
            android:id="@+id/welcomeTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/tutorial_welcome_title"
            android:textAppearance="?attr/textAppearanceHeadlineLarge"
            android:textColor="#FFFFFF"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/welcomeSubtitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/tutorial_welcome_subtitle"
            android:textAppearance="?attr/textAppearanceBodyLarge"
            android:textColor="#B0FFFFFF" />
    </LinearLayout>

    <!-- 进入按钮 -->
    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnEnter"
        style="@style/Widget.Material3.Button"
        android:layout_width="200dp"
        android:layout_height="56dp"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="100dp"
        android:text="@string/tutorial_enter"
        android:textColor="#FFFFFF"
        android:alpha="0"
        app:backgroundTint="#3F51B5"
        app:cornerRadius="28dp" />

    <!-- 教程覆盖层（步骤1-2） -->
    <com.polaris.app.OnboardingOverlayView
        android:id="@+id/overlayView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

</FrameLayout>
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (OnboardingOverlayView not yet created, may need to stub)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/activity_onboarding.xml
git commit -m "feat: add onboarding welcome page layout"
```

---

### Task 4: OnboardingOverlayView — Custom Overlay

**Files:**
- Create: `app/src/main/java/com/polaris/app/OnboardingOverlayView.java`

- [ ] **Step 1: Create the custom View**

```java
package com.polaris.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 半透明深色遮罩 + 高亮挖洞 + 引导四角星图标 + 拖尾效果。
 */
public class OnboardingOverlayView extends View {

    private static final int OVERLAY_COLOR = 0xCC000000;
    private static final int TRAIL_COUNT = 5;
    private static final long TRAIL_DELAY_MS = 50;

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RectF highlightRect = new RectF();
    private float highlightRadius = 20f;

    private float iconX, iconY;
    private float iconScale = 1f;
    private float iconAlpha = 1f;
    private Bitmap starBitmap;

    private final List<TrailGhost> ghosts = new ArrayList<>();

    private String titleText = "";
    private String descText = "";
    private float textAlpha = 0f;
    private float textX, textY;

    private ValueAnimator iconAnimator;

    public OnboardingOverlayView(Context context) {
        super(context);
        init();
    }

    public OnboardingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        overlayPaint.setColor(OVERLAY_COLOR);
        overlayPaint.setStyle(Paint.Style.FILL);

        clearPaint.setColor(0);
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        iconPaint.setAlpha(255);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.LEFT);

        starBitmap = createStarBitmap(96);
    }

    private Bitmap createStarBitmap(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);

        float cx = size / 2f;
        float cy = size / 2f;
        float outerR = size * 0.42f;
        float innerR = size * 0.18f;
        int points = 4;

        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < points * 2; i++) {
            float angle = (float) (Math.PI * i / points - Math.PI / 2);
            float r = (i % 2 == 0) ? outerR : innerR;
            float x = cx + r * (float) Math.cos(angle);
            float y = cy + r * (float) Math.sin(angle);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, p);
        return bmp;
    }

    public void setHighlight(RectF rect, float radius) {
        this.highlightRect.set(rect);
        this.highlightRadius = radius;
        invalidate();
    }

    public void setHighlightBounds(float left, float top, float right, float bottom, float radius) {
        this.highlightRect.set(left, top, right, bottom);
        this.highlightRadius = radius;
        invalidate();
    }

    public void setIconPosition(float x, float y) {
        this.iconX = x;
        this.iconY = y;
        invalidate();
    }

    public void setTexts(String title, String desc) {
        this.titleText = title;
        this.descText = desc;
    }

    public void setTextPosition(float x, float y) {
        this.textX = x;
        this.textY = y;
    }

    public void setTextAlpha(float alpha) {
        this.textAlpha = alpha;
        invalidate();
    }

    /**
     * 将引导图标非线性滑动到目标位置，附带拖尾。
     */
    public void animateIconTo(float targetX, float targetY, long duration,
                               Interpolator interpolator, Runnable onEnd) {
        if (iconAnimator != null && iconAnimator.isRunning()) {
            iconAnimator.cancel();
        }

        ghosts.clear();
        final float startX = iconX;
        final float startY = iconY;

        // 拖尾动画
        final long trailTotal = TRAIL_COUNT * TRAIL_DELAY_MS;
        final ValueAnimator trailAnimator = ValueAnimator.ofFloat(0, 1);
        trailAnimator.setDuration(duration);
        trailAnimator.setInterpolator(new LinearInterpolator());
        trailAnimator.addUpdateListener(a -> {
            float fraction = a.getAnimatedFraction();
            long elapsed = (long) (fraction * duration);

            // 添加新 ghost
            for (int i = 0; i < TRAIL_COUNT; i++) {
                long ghostTime = elapsed - (i + 1) * TRAIL_DELAY_MS;
                if (ghostTime >= 0 && ghosts.size() <= i) {
                    float t = Math.min(1f, ghostTime / (float) duration);
                    float easedT = interpolator != null ? interpolator.getInterpolation(t) : t;
                    TrailGhost g = new TrailGhost();
                    g.x = startX + (targetX - startX) * easedT;
                    g.y = startY + (targetY - startY) * easedT;
                    g.alpha = 0.6f - i * 0.12f;
                    g.scale = 1f - i * 0.04f;
                    ghosts.add(g);
                }
            }

            // 移除过期 ghost
            while (ghosts.size() > TRAIL_COUNT) {
                ghosts.remove(0);
            }

            // 淡化旧 ghost
            for (int i = 0; i < ghosts.size(); i++) {
                TrailGhost g = ghosts.get(i);
                g.alpha *= 0.98f;
                if (g.alpha < 0.02f) {
                    ghosts.remove(i);
                    i--;
                }
            }

            invalidate();
        });

        // 主图标动画
        iconAnimator = ValueAnimator.ofFloat(0, 1);
        iconAnimator.setDuration(duration);
        if (interpolator != null) {
            iconAnimator.setInterpolator(interpolator);
        }
        iconAnimator.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            iconX = startX + (targetX - startX) * t;
            iconY = startY + (targetY - startY) * t;
            invalidate();
        });
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ghosts.clear();
                invalidate();
                if (onEnd != null) onEnd.run();
            }
        });

        trailAnimator.start();
        iconAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 绘制深色遮罩
        canvas.save();
        canvas.drawRect(0, 0, getWidth(), getHeight(), overlayPaint);

        // 2. 挖出高亮区域
        if (highlightRect.width() > 0 && highlightRect.height() > 0) {
            canvas.drawRoundRect(highlightRect, highlightRadius, highlightRadius, clearPaint);
        }
        canvas.restore();

        // 3. 绘制拖尾 ghost
        for (TrailGhost g : ghosts) {
            if (starBitmap != null && !starBitmap.isRecycled()) {
                int alpha = (int) (g.alpha * 255);
                iconPaint.setAlpha(alpha);
                float half = starBitmap.getWidth() * g.scale / 2f;
                canvas.save();
                canvas.translate(g.x - half, g.y - half);
                canvas.scale(g.scale, g.scale);
                canvas.drawBitmap(starBitmap, 0, 0, iconPaint);
                canvas.restore();
            }
        }

        // 4. 绘制主图标
        if (starBitmap != null && !starBitmap.isRecycled()) {
            iconPaint.setAlpha((int) (iconAlpha * 255));
            float half = starBitmap.getWidth() * iconScale / 2f;
            canvas.save();
            canvas.translate(iconX - half, iconY - half);
            canvas.scale(iconScale, iconScale);
            canvas.drawBitmap(starBitmap, 0, 0, iconPaint);
            canvas.restore();
        }

        // 5. 绘制文本
        if (textAlpha > 0.01f && !titleText.isEmpty()) {
            textPaint.setAlpha((int) (textAlpha * 255));
            canvas.drawText(titleText, textX, textY, textPaint);

            if (!descText.isEmpty()) {
                textPaint.setTextSize(36f);
                textPaint.setAlpha((int) (textAlpha * 0.7f * 255));
                canvas.drawText(descText, textX, textY + 60f, textPaint);
                textPaint.setTextSize(48f);
            }
        }
    }

    private static class TrailGhost {
        float x, y;
        float alpha;
        float scale;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/polaris/app/OnboardingOverlayView.java
git commit -m "feat: add OnboardingOverlayView with highlight + animated guide icon"
```

---

### Task 5: OnboardingActivity — Tutorial Orchestrator

**Files:**
- Create: `app/src/main/java/com/polaris/app/OnboardingActivity.java`

- [ ] **Step 1: Create the Activity**

```java
package com.polaris.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.FastOutSlowInInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.polaris.app.util.Prefs;

/**
 * 首次启动教程：欢迎页 → 扫描介绍 → 守护介绍 → 完成。
 */
public class OnboardingActivity extends AppCompatActivity {

    private FrameLayout root;
    private ImageView welcomeIcon;
    private View welcomeTextGroup;
    private MaterialButton btnEnter;
    private OnboardingOverlayView overlayView;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private enum Step { WELCOME, SCAN_INTRO, GUARD_INTRO, DONE }
    private Step currentStep = Step.WELCOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        prefs = new Prefs(this);

        root = findViewById(R.id.onboardingRoot);
        welcomeIcon = findViewById(R.id.welcomeIcon);
        welcomeTextGroup = findViewById(R.id.welcomeTextGroup);
        btnEnter = findViewById(R.id.btnEnter);
        overlayView = findViewById(R.id.overlayView);

        btnEnter.setOnClickListener(v -> onEnterClicked());

        // 全屏沉浸式
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        playWelcomeAnimation();
    }

    // ==================== 步骤 0: 欢迎页 ====================

    private void playWelcomeAnimation() {
        currentStep = Step.WELCOME;

        // 图标弹入 (scale 0→1)
        welcomeIcon.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(600)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        handler.postDelayed(() -> animateIconUp(), 400);
                    }
                })
                .start();
    }

    private void animateIconUp() {
        // 计算目标 Y：屏幕上 1/3
        int screenHeight = root.getHeight();
        float targetY = screenHeight / 3f - welcomeIcon.getHeight() / 2f;

        // 图标上移
        ObjectAnimator iconUp = ObjectAnimator.ofFloat(welcomeIcon, "translationY",
                welcomeIcon.getTranslationY(), targetY);
        iconUp.setDuration(800);
        iconUp.setInterpolator(new FastOutSlowInInterpolator());
        iconUp.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                showWelcomeText();
            }
        });
        iconUp.start();
    }

    private void showWelcomeText() {
        // 文字淡入
        ObjectAnimator textIn = ObjectAnimator.ofFloat(welcomeTextGroup, "alpha", 0f, 1f);
        textIn.setDuration(400);
        textIn.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator textSlide = ObjectAnimator.ofFloat(welcomeTextGroup, "translationY", 20f, 0f);
        textSlide.setDuration(400);
        textSlide.setInterpolator(new DecelerateInterpolator());

        textIn.start();
        textSlide.start();

        handler.postDelayed(() -> showEnterButton(), 200);
    }

    private void showEnterButton() {
        ObjectAnimator btnIn = ObjectAnimator.ofFloat(btnEnter, "alpha", 0f, 1f);
        btnIn.setDuration(400);
        btnIn.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator btnSlide = ObjectAnimator.ofFloat(btnEnter, "translationY", 30f, 0f);
        btnSlide.setDuration(400);
        btnSlide.setInterpolator(new DecelerateInterpolator());

        btnIn.start();
        btnSlide.start();
    }

    // ==================== 步骤 1: 扫描介绍 ====================

    private void onEnterClicked() {
        if (currentStep == Step.WELCOME) {
            showScanIntro();
        } else if (currentStep == Step.SCAN_INTRO) {
            showGuardIntro();
        }
    }

    private void showScanIntro() {
        currentStep = Step.SCAN_INTRO;

        // 隐藏欢迎元素
        welcomeIcon.setVisibility(View.GONE);
        welcomeTextGroup.setVisibility(View.GONE);
        btnEnter.setVisibility(View.GONE);

        // 显示覆盖层
        overlayView.setVisibility(View.VISIBLE);

        // 设置高亮区域：模式卡片的大致位置（屏幕中间偏下）
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        float hlLeft = screenW * 0.05f;
        float hlTop = screenH * 0.35f;
        float hlRight = screenW * 0.95f;
        float hlBottom = screenH * 0.75f;
        overlayView.setHighlightBounds(hlLeft, hlTop, hlRight, hlBottom, 24f);

        // 引导图标从左侧滑入
        float startX = -48f;
        float startY = (hlTop + hlBottom) / 2f;
        float endX = hlLeft - 16f;
        float endY = startY;

        overlayView.setIconPosition(startX, startY);
        overlayView.setTexts(
                getString(R.string.tutorial_scan_title),
                getString(R.string.tutorial_scan_desc));
        overlayView.setTextPosition(hlRight + 24f, startY + 12f);

        overlayView.animateIconTo(endX, endY, 800,
                new FastOutSlowInInterpolator(), () -> {
                    // 文本淡入
                    ObjectAnimator textFade = ObjectAnimator.ofFloat(overlayView, "textAlpha", 0f, 1f);
                    textFade.setDuration(400);
                    textFade.setInterpolator(new DecelerateInterpolator());
                    textFade.start();

                    // 显示下一步按钮
                    btnEnter.setVisibility(View.VISIBLE);
                    btnEnter.setText(R.string.tutorial_next);
                    ObjectAnimator btnIn = ObjectAnimator.ofFloat(btnEnter, "alpha", 0f, 1f);
                    btnIn.setDuration(300);
                    btnIn.start();
                });
    }

    // ==================== 步骤 2: 守护介绍 ====================

    private void showGuardIntro() {
        currentStep = Step.GUARD_INTRO;

        // 隐藏按钮
        btnEnter.setVisibility(View.GONE);

        // 更新高亮：左侧主内容区域
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        float hlLeft = screenW * 0.05f;
        float hlTop = screenH * 0.1f;
        float hlRight = screenW * 0.7f;
        float hlBottom = screenH * 0.9f;
        overlayView.setHighlightBounds(hlLeft, hlTop, hlRight, hlBottom, 24f);

        // 文本提示
        overlayView.setTexts(
                getString(R.string.tutorial_guard_desc),
                getString(R.string.tutorial_guard_prompt));
        overlayView.setTextPosition(hlLeft + 16f, hlBottom - 80f);
        overlayView.setTextAlpha(1f);

        // 四角星从高亮中心滑向右侧（引导滑动方向）
        float startX = (hlLeft + hlRight) / 2f;
        float startY = (hlTop + hlBottom) / 2f;
        float endX = screenW * 0.88f;
        float endY = startY;

        overlayView.setIconPosition(startX, startY);

        // 延迟后开始滑动动画
        handler.postDelayed(() -> {
            overlayView.animateIconTo(endX, endY, 1200,
                    new FastOutSlowInInterpolator(), () -> {
                        // 动画完成，等待用户滑动或超时
                    });
        }, 600);

        // 5秒超时自动完成
        handler.postDelayed(() -> completeTutorial(), 5000);
    }

    // ==================== 完成 ====================

    private void completeTutorial() {
        if (currentStep == Step.DONE) return;
        currentStep = Step.DONE;

        prefs.setTutorialDone();

        // 淡出
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(root, "alpha", 1f, 0f);
        fadeOut.setDuration(300);
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
        fadeOut.start();
    }

    /**
     * 供外部调用（如检测到滑动打开 Drawer 时）。
     */
    public void onGuardDrawerOpened() {
        completeTutorial();
    }

    @Override
    public void onBackPressed() {
        // 教程中禁用返回键
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/polaris/app/OnboardingActivity.java
git commit -m "feat: add OnboardingActivity with welcome animation and tutorial steps"
```

---

### Task 6: AndroidManifest — Swap Launcher

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add OnboardingActivity as launcher**

Find the existing `<activity android:name=".MainActivity"...>` block and modify:

```xml
<!-- OnboardingActivity: launcher on first install -->
<activity
    android:name=".OnboardingActivity"
    android:exported="true"
    android:theme="@style/Theme.Polaris">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- MainActivity: normal activity (not launcher) -->
<activity
    android:name=".MainActivity"
    android:exported="false" />
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: set OnboardingActivity as launcher, MainActivity as standard"
```

---

### Task 7: MainActivity — Tutorial Check

**Files:**
- Modify: `app/src/main/java/com/polaris/app/MainActivity.java`

- [ ] **Step 1: Add tutorial check in onCreate**

In `onCreate()`, after `prefs = new Prefs(this);` (around line 132), add:

```java
// 首次启动 → 进入教程
if (!prefs.isTutorialDone()) {
    startActivity(new Intent(this, OnboardingActivity.class));
    finish();
    return;
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/polaris/app/MainActivity.java
git commit -m "feat: check tutorial state in MainActivity.onCreate"
```

---

### Task 8: Integration Test — Drawer Swipe Detection

**Files:**
- Modify: `app/src/main/java/com/polaris/app/MainActivity.java`

- [ ] **Step 1: Add DrawerLayout listener to detect guard drawer open**

In `onCreate()`, after `drawerLayout` is initialized, add:

```java
// 教程：检测守护面板打开
drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
    @Override
    public void onDrawerOpened(View drawerView) {
        if (!prefs.isTutorialDone()) {
            // 教程中打开守护面板 → 完成教程
            prefs.setTutorialDone();
        }
    }
});
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/polaris/app/MainActivity.java
git commit -m "feat: detect guard drawer open during tutorial"
```

---

### Task 9: Final Build & Verification

- [ ] **Step 1: Full clean build**

Run: `cd /d E:\Personal\操作\AI\Output\Polaris_GitHub_v2.1.0 && gradlew.bat :app:clean :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all files exist**

Check:
- `app/src/main/java/com/polaris/app/OnboardingActivity.java`
- `app/src/main/java/com/polaris/app/OnboardingOverlayView.java`
- `app/src/main/res/layout/activity_onboarding.xml`

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: complete onboarding tutorial system"
```
