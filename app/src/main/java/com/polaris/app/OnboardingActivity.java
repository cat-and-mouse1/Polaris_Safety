package com.polaris.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;

import com.polaris.app.scan.IocDatabase;
import com.polaris.app.util.Prefs;
import com.polaris.app.view.OnboardingStarView;

/**
 * 欢迎页——主题实时预览版：
 * 背景随主题实时切换，左上角空心四角星全程在场（自绘成形 → 呼吸待机）。
 * 点击「开始使用」后星体脉冲（自旋 + 涟漪扩散）揭示外观选项（显示模式 + 横向主题色板），
 * 页面背景/星体/文字/按钮随选择实时应用；随后同款脉冲揭示 ML+病毒库 →「开始体验」。
 */
public class OnboardingActivity extends AppCompatActivity {

    /** 默认主题色：基础主题主色 #00639B（蓝），作为色板首项与「默认配色」对应色。 */
    private static final int DEFAULT_THEME_BLUE = 0xFF00639B;

    /** 预设主题种子色（与外观设置页同一组预设，蓝为默认）。 */
    private static final int[] PRESET_COLORS = {
            DEFAULT_THEME_BLUE, // 蓝（默认主题色）
            0xFF7E57C2, // 紫
            0xFF00897B, // 青绿
            0xFFFB8C00, // 橙
            0xFFE53935, // 红
            0xFFD81B60, // 粉
    };

    // ---------- 设置页同族双调色板（与 values / values-night 的 md_theme 一致，代码层实时切换） ----------
    private static final int DARK_BG = 0xFF101418;      // 夜间 background/surface
    private static final int DARK_CARD = 0xFF181C21;    // 夜间 surfaceContainerLow
    private static final int DARK_STROKE = 0xFF43474E;  // 夜间 outlineVariant
    private static final int DARK_TEXT = 0xFFE0E2E9;    // 夜间 onSurface
    private static final int DARK_TEXT_SUB = 0xFFC3C6CF; // 夜间 onSurfaceVariant
    private static final int DARK_ON_ACCENT = 0xFF003355; // 夜间 onPrimary

    private static final int LIGHT_BG = 0xFFF8F9FF;      // 日间 background/surface
    private static final int LIGHT_CARD = 0xFFF2F3FA;    // 日间 surfaceContainerLow
    private static final int LIGHT_STROKE = 0xFFC3C6CF;  // 日间 outlineVariant
    private static final int LIGHT_TEXT = 0xFF191C20;    // 日间 onSurface
    private static final int LIGHT_TEXT_SUB = 0xFF43474E; // 日间 onSurfaceVariant

    private Prefs prefs;
    private MaterialButton btnEnter;
    private MaterialButton btnWelcome;
    private MaterialButton btnNext;
    private MaterialButton btnCancelDownload;
    private LinearProgressIndicator downloadProgress;
    private TextView downloadStatus;
    private LinearLayout cardArea;
    private LinearLayout appearancePanel;
    private TextView appearanceTitle;
    private TextView appearanceSubtitle;
    private View appearanceDivider;
    private LinearLayout mlCard;
    private LinearLayout virusDbCard;
    private TextView mlTitle;
    private TextView mlSubtitle;
    private TextView vdTitle;
    private TextView vdSubtitle;
    private LinearLayout aiPanel;
    private TextView aiTitle;
    private TextView aiSubtitle;
    private TextView aiStatus;
    private MaterialSwitch aiSwitch;
    private MaterialButton aiConfigBtn;
    /** 欢迎流程步骤：1=外观，2=AI 接入，3=ML+病毒库。 */
    private int stage = 1;
    private MaterialSwitch mlEngineSwitch;
    private RadioGroup mlModelRadioGroup;
    private RadioGroup virusDbRadioGroup;
    private LinearLayout obThemeSwatches;
    private MaterialButton obThemeDark;
    private MaterialButton obThemeLight;
    private OnboardingStarView starView;
    private IocDatabase.DownloadToken downloadToken;
    private boolean leaving = false;

    /** 欢迎页实时主题状态。 */
    private int welcomeColor = DEFAULT_THEME_BLUE;
    private boolean welcomeDark = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 固定浅色 AppCompat 基座：页面颜色全部由 applyWelcomeTheme 代码层实时刷写，
        // 不依赖夜间限定符资源（避免 values-night 翻转造成的配色混乱）。
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        prefs = new Prefs(this);

        downloadProgress = findViewById(R.id.downloadProgress);
        downloadStatus = findViewById(R.id.downloadStatus);
        btnCancelDownload = findViewById(R.id.btnCancelDownload);
        cardArea = findViewById(R.id.cardArea);
        appearancePanel = findViewById(R.id.appearancePanel);
        appearanceTitle = findViewById(R.id.appearanceTitle);
        appearanceSubtitle = findViewById(R.id.appearanceSubtitle);
        appearanceDivider = findViewById(R.id.appearanceDivider);
        mlCard = findViewById(R.id.mlEngineCard);
        virusDbCard = findViewById(R.id.virusDbCard);
        mlTitle = findViewById(R.id.mlTitle);
        mlSubtitle = findViewById(R.id.mlSubtitle);
        vdTitle = findViewById(R.id.vdTitle);
        vdSubtitle = findViewById(R.id.vdSubtitle);
        aiPanel = findViewById(R.id.aiPanel);
        aiTitle = findViewById(R.id.aiTitle);
        aiSubtitle = findViewById(R.id.aiSubtitle);
        aiStatus = findViewById(R.id.aiStatus);
        aiSwitch = findViewById(R.id.aiSwitch);
        aiConfigBtn = findViewById(R.id.aiConfigBtn);
        starView = findViewById(R.id.starView);
        btnWelcome = findViewById(R.id.btnWelcome);
        btnNext = findViewById(R.id.btnNext);
        btnEnter = findViewById(R.id.btnEnter);
        obThemeSwatches = findViewById(R.id.obThemeSwatches);
        obThemeDark = findViewById(R.id.obThemeDark);
        obThemeLight = findViewById(R.id.obThemeLight);

        // 已完成欢迎页 → 直接跳转主界面（日常启动入口，不带 from_onboarding）
        if (prefs.isTutorialDone(this)) {
            goMain(false);
            return;
        }

        // 外观默认值落地：深色 + 蓝色（用户要求「默认深色蓝色」）
        prefs.setThemeMode(Prefs.THEME_DARK);

        // 病毒库默认选择
        virusDbRadioGroup = findViewById(R.id.virusDbRadioGroup);
        virusDbRadioGroup.check(R.id.radioPoint);

        setupMlEngineCard();
        setupAppearancePanel();
        setupAiPanel();
        applyWelcomeTheme();

        // 进入按钮：保存病毒库选择（选 Region 需联网拉取并展示进度）
        btnCancelDownload.setOnClickListener(v -> {
            if (downloadToken != null) downloadToken.cancel();
            Toast.makeText(OnboardingActivity.this,
                    R.string.virusdb_cancelled_toast, Toast.LENGTH_LONG).show();
            goMainOnce();
        });
        btnEnter.setOnClickListener(v -> {
            String selectedDb = virusDbRadioGroup.getCheckedRadioButtonId() == R.id.radioPoint
                    ? "point" : "region";
            prefs.setVirusDb(selectedDb);
            prefs.setTutorialDone(OnboardingActivity.this, true);
            if ("region".equals(selectedDb)) {
                startRegionDownload();
            } else {
                goMain(true);
            }
        });

        // 星图开场：左上角锚定 → 缩放浮现自转 → 底部弹出「开始使用」
        starView.post(() -> {
            starView.setStarPosition(dp(52), dp(92));
            starView.runIntro(() -> {
                btnWelcome.setTranslationY(dp(48));
                btnWelcome.setVisibility(View.VISIBLE);
                btnWelcome.animate().alpha(1f).translationY(0f).setDuration(420)
                        .setInterpolator(new OvershootInterpolator(1.2f)).start();
            });
        });

        btnWelcome.setOnClickListener(v -> {
            if (v.getVisibility() != View.VISIBLE || leaving) return;
            v.setEnabled(false);
            v.animate().alpha(0f).scaleX(0.55f).scaleY(0.55f).setDuration(220)
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
            starView.postDelayed(this::sweepOne, 260);
        });

        // 「下一步」：按阶段推进——外观(1) → AI 接入(2) → ML+病毒库(3)
        btnNext.setOnClickListener(v -> {
            if (v.getVisibility() != View.VISIBLE || leaving) return;
            v.setEnabled(false);
            if (stage == 1) {
                // 外观面板让位，揭示 AI 接入面板，「下一步」保留供下一步
                appearancePanel.animate().alpha(0f).setDuration(240)
                        .withEndAction(() -> appearancePanel.setVisibility(View.GONE)).start();
                starView.postDelayed(this::sweepAi, 260);
            } else if (stage == 2) {
                aiPanel.animate().alpha(0f).setDuration(240)
                        .withEndAction(() -> aiPanel.setVisibility(View.GONE)).start();
                starView.postDelayed(this::sweepTwo, 260);
            }
        });
    }

    /** 第一段脉冲：星体自旋 + 涟漪扩散揭示外观选项面板，星体常驻不淡出，完成后弹出「下一步」。 */
    private void sweepOne() {
        starView.pulse(1300, new OnboardingStarView.PulseCallback() {
            @Override
            public void onProgress(float t) {
                revealCard(appearancePanel, t, 0.22f);
            }

            @Override
            public void onDone() {
                btnNext.setVisibility(View.VISIBLE);
                btnNext.animate().alpha(1f).setDuration(320).start();
            }
        });
    }

    /** 第二段脉冲：揭示 AI 接入面板，完成后保留「下一步」以推进到 ML+病毒库。 */
    private void sweepAi() {
        starView.pulse(1300, new OnboardingStarView.PulseCallback() {
            @Override
            public void onProgress(float t) {
                revealCard(aiPanel, t, 0.22f);
            }

            @Override
            public void onDone() {
                stage = 2;
                btnNext.setEnabled(true);
                btnNext.animate().alpha(1f).setDuration(320).start();
            }
        });
    }

    /** 第三段脉冲：同款涟漪依次揭示 ML 引擎与病毒库，随后浮现「开始体验」。 */
    private void sweepTwo() {
        starView.pulse(1300, new OnboardingStarView.PulseCallback() {
            @Override
            public void onProgress(float t) {
                revealCard(mlCard, t, 0.18f);
                revealCard(virusDbCard, t, 0.42f);
            }

            @Override
            public void onDone() {
                stage = 3;
                btnNext.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> btnNext.setVisibility(View.GONE)).start();
                btnEnter.setVisibility(View.VISIBLE);
                btnEnter.animate().alpha(1f).setDuration(380)
                        .setInterpolator(new OvershootInterpolator(1.1f)).start();
            }
        });
    }

    /** 光芒蒙版揭示：扫掠进度映射为卡片透明度与上滑位移（越过 threshold 后开始淡入）。 */
    private void revealCard(LinearLayout card, float sweepT, float threshold) {
        if (card == null) return;
        float a = (sweepT - threshold) / 0.5f;
        a = Math.max(0f, Math.min(1f, a));
        if (a > 0f && card.getVisibility() != View.VISIBLE) card.setVisibility(View.VISIBLE);
        card.setAlpha(a);
        card.setTranslationY((1f - a) * dp(36));
    }

    // ---------- 欢迎页实时主题 ----------

    /** 按当前 welcomeColor/welcomeDark 实时刷写页面全部颜色（对齐设置页风格：标准 M3 表面，主题色只做强调）。 */
    private void applyWelcomeTheme() {
        boolean dark = welcomeDark;
        int bg = dark ? DARK_BG : LIGHT_BG;
        int card = dark ? DARK_CARD : LIGHT_CARD;
        int stroke = dark ? DARK_STROKE : LIGHT_STROKE;
        int text = dark ? DARK_TEXT : LIGHT_TEXT;
        int sub = dark ? DARK_TEXT_SUB : LIGHT_TEXT_SUB;
        // 强调色 = 用户选的种子色；深色下提亮保证可见（对齐夜间 primary 的浅色调），浅色下用本色
        int accent = dark ? mix(welcomeColor, 0xFFFFFFFF, 0.45f) : welcomeColor;
        int onAccent = dark ? DARK_ON_ACCENT : 0xFFFFFFFF;

        findViewById(R.id.onboardingRoot).setBackgroundColor(bg);
        starView.setStarColor(accent);
        starView.setCaptionColor(text);   // 星旁标题文字对齐主界面 homeGreeting（colorOnSurface 语义）

        // 面板卡片底
        setCardBackground(appearancePanel, card, stroke);
        setCardBackground(aiPanel, card, stroke);
        setCardBackground(mlCard, card, stroke);
        setCardBackground(virusDbCard, card, stroke);

        // 文字
        appearanceTitle.setTextColor(text);
        appearanceSubtitle.setTextColor(sub);
        appearanceDivider.setBackgroundColor(stroke);
        aiTitle.setTextColor(text);
        aiSubtitle.setTextColor(sub);
        aiStatus.setTextColor(sub);
        aiConfigBtn.setTextColor(accent);
        mlTitle.setTextColor(text);
        mlSubtitle.setTextColor(sub);
        vdTitle.setTextColor(text);
        vdSubtitle.setTextColor(sub);
        tintRadios(mlModelRadioGroup, text);
        tintRadios(virusDbRadioGroup, text);

        // 深色/浅色切换按钮
        retintToggle(obThemeDark, accent, onAccent, card, sub, stroke);
        retintToggle(obThemeLight, accent, onAccent, card, sub, stroke);

        // 动作按钮
        tintPrimaryButton(btnWelcome, accent, onAccent);
        tintPrimaryButton(btnNext, accent, onAccent);
        tintPrimaryButton(btnEnter, accent, onAccent);

        // 下载 UI
        downloadStatus.setTextColor(sub);
        downloadProgress.setIndicatorColor(accent);
        downloadProgress.setTrackColor(stroke);
        btnCancelDownload.setTextColor(accent);

        refreshSwatchStrokes(text);
    }

    /** 面板卡片底：设置页同款表面色 + outlineVariant 细边 + 轻微浮起阴影（M3 表面质感）。 */
    private void setCardBackground(View v, int card, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(16));
        gd.setColor(card);
        gd.setStroke((int) dp(1), stroke);
        v.setBackground(gd);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setElevation(dp(2));
    }

    /** 主按钮：强调色实底 + onAccent 文字（同设置页「配置」胶囊）。 */
    private void tintPrimaryButton(MaterialButton b, int accent, int onAccent) {
        b.setBackgroundTintList(ColorStateList.valueOf(accent));
        b.setTextColor(onAccent);
        b.setStrokeWidth(0);
    }

    /** 深色/浅色切换按钮：选中=强调色实底；未选中=灰色表面 + 次要文字。 */
    private void retintToggle(MaterialButton b, int accent, int onAccent, int card, int sub, int stroke) {
        boolean checked = b.isChecked();
        b.setBackgroundTintList(ColorStateList.valueOf(checked ? accent : card));
        b.setTextColor(checked ? onAccent : sub);
        b.setStrokeWidth((int) dp(1));
        b.setStrokeColor(ColorStateList.valueOf(checked ? accent : stroke));
    }

    private void tintRadios(RadioGroup group, int color) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof RadioButton) ((RadioButton) c).setTextColor(color);
        }
    }

    /** 外观选项面板：左列色板填充 + 深色/浅色切换（实时应用）。 */
    private void setupAppearancePanel() {
        MaterialButtonToggleGroup group = findViewById(R.id.obThemeModeGroup);
        group.check(R.id.obThemeDark);   // 默认深色
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            welcomeDark = checkedId == R.id.obThemeDark;
            prefs.setThemeMode(welcomeDark ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
            applyWelcomeTheme();
        });

        // 初始主题色（尊重已有偏好；仅接受预设色板内的颜色）
        int cm = prefs.getThemeColorMode();
        int sc = prefs.getCustomThemeColor();
        if (cm == Prefs.THEME_COLOR_CUSTOM && sc != -1) {
            for (int c : PRESET_COLORS) {
                if (c == sc) {
                    welcomeColor = sc;
                    break;
                }
            }
        }

        // 横向主题色板（对齐设置页外观子页：40dp 圆点 + 选中 3dp 描边 + 12dp 间距）
        int dp = (int) (getResources().getDisplayMetrics().density + 0.5f);
        for (final int color : PRESET_COLORS) {
            FrameLayout wrap = new FrameLayout(this);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(48 * dp, 48 * dp);
            wp.setMargins(0, 0, 12 * dp, 0);
            wrap.setLayoutParams(wp);

            View dot = new View(this);
            FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(40 * dp, 40 * dp);
            dp2.gravity = Gravity.CENTER;
            dot.setLayoutParams(dp2);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(color);
            dot.setBackground(gd);
            wrap.addView(dot);

            wrap.setContentDescription(getString(R.string.custom_theme_applied));
            wrap.setOnClickListener(v -> {
                welcomeColor = color;
                if (color == DEFAULT_THEME_BLUE) {
                    prefs.setThemeColorMode(Prefs.THEME_COLOR_DEFAULT);
                } else {
                    prefs.setThemeColorMode(Prefs.THEME_COLOR_CUSTOM);
                    prefs.setCustomThemeColor(color);
                }
                applyWelcomeTheme();
            });
            obThemeSwatches.addView(wrap);
        }
        refreshSwatchStrokes(welcomeDark ? DARK_TEXT : LIGHT_TEXT);
    }

    /** 色板选中描边：选中项加正文色 3dp 描边环（深浅模式下均清晰可见）。 */
    private void refreshSwatchStrokes(int text) {
        int dp = (int) (getResources().getDisplayMetrics().density + 0.5f);
        for (int i = 0; i < obThemeSwatches.getChildCount(); i++) {
            View c = obThemeSwatches.getChildAt(i);
            if (!(c instanceof FrameLayout)) continue;
            FrameLayout wrap = (FrameLayout) c;
            View dot = wrap.getChildAt(0);
            if (!(dot.getBackground() instanceof GradientDrawable)) continue;
            GradientDrawable gd = (GradientDrawable) dot.getBackground();
            int c2 = PRESET_COLORS[i];
            boolean sel = c2 == welcomeColor;
            gd.setStroke(sel ? 3 * dp : 0, text);
        }
    }

    /** AI 接入面板：启用开关（写入首选引擎）+ 状态摘要 + 跳转 AiSetupActivity 配置提供方。 */
    private void setupAiPanel() {
        aiSwitch.setChecked(Prefs.ENGINE_AI.equals(prefs.getPreferredEngine()));
        refreshAiStatus();
        aiSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.setPreferredEngine(checked ? Prefs.ENGINE_AI : Prefs.ENGINE_NORMAL);
            refreshAiStatus();
        });
        aiConfigBtn.setOnClickListener(v ->
                startActivity(new Intent(OnboardingActivity.this, AiSetupActivity.class)));
    }

    /** 依据引擎启用与已配置提供方刷新状态文本与「配置提供方」按钮可见性。 */
    private void refreshAiStatus() {
        boolean enabled = Prefs.ENGINE_AI.equals(prefs.getPreferredEngine());
        aiConfigBtn.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            aiStatus.setText(R.string.onboarding_ai_status_disabled);
        } else {
            var eps = prefs.getEnabledProviders();
            if (eps != null && !eps.isEmpty()) {
                aiStatus.setText(getString(R.string.onboarding_ai_status, eps.get(0).name));
            } else {
                aiStatus.setText(R.string.onboarding_ai_status_none);
            }
        }
    }

    /** ML 引擎卡片：是否开启 + 选择本地检测模型（mh100k 轻量 / mh1m 强力）。 */
    private void setupMlEngineCard() {
        mlEngineSwitch = findViewById(R.id.mlEngineSwitch);
        mlModelRadioGroup = findViewById(R.id.mlModelRadioGroup);

        boolean mlOn = prefs.isMlEnabled();
        mlEngineSwitch.setChecked(mlOn);
        mlModelRadioGroup.setVisibility(mlOn ? View.VISIBLE : View.GONE);
        mlModelRadioGroup.check("mh1m".equals(prefs.getMlModel())
                ? R.id.radioMh1m : R.id.radioMh100k);

        mlEngineSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.setMlEnabled(checked);
            mlModelRadioGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        mlModelRadioGroup.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.radioMh1m) {
                prefs.setMlModel("mh1m");
            } else if (id == R.id.radioMh100k) {
                prefs.setMlModel("mh100k");
            }
        });
    }

    /** fromOnboarding=true 仅用于真正走完引导流程的进入；日常直跳传 false。 */
    private void goMain(boolean fromOnboarding) {
        // 外观选择在此统一生效（欢迎页中途 applyTheme 会重建本页、打断动画）
        PolarisApp.applyTheme(prefs.getThemeMode());
        Intent intent = new Intent(this, MainActivity.class);
        if (fromOnboarding) intent.putExtra("from_onboarding", true);
        startActivity(intent);
        finish();
    }

    /** 只跳转一次，避免用户取消后后台回调再次触发跳转。 */
    private void goMainOnce() {
        if (leaving) return;
        leaving = true;
        goMain(true);
    }

    /** 选择 Polar Region：内置不含该库，自动从仓库下载缺失的 iodb.json 并显示进度条。 */
    private void startRegionDownload() {
        // 隐藏卡片区（下载三件套在底部动作区，保持可见）
        cardArea.setVisibility(View.GONE);
        btnEnter.setEnabled(false);
        btnEnter.setText(R.string.virusdb_downloading);
        btnCancelDownload.setVisibility(View.VISIBLE);
        downloadStatus.setVisibility(View.VISIBLE);
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setIndeterminate(true);
        downloadStatus.setText(R.string.virusdb_download_start);

        downloadToken = IocDatabase.getInstance(this, "region").refreshResumable(
                new IocDatabase.CancellableRefreshCallback() {
                    @Override
                    public void onUpdated(int newVersion) {
                        runOnUiThread(() -> {
                            if (leaving) return;
                            downloadProgress.setIndeterminate(false);
                            downloadProgress.setProgressCompat(100, false);
                            downloadStatus.setText(R.string.virusdb_download_done);
                            goMainOnce();
                        });
                    }

                    @Override
                    public void onCancelled() {
                        // 用户主动中断：保留不完整文件，带内置库进入，稍后可在设置续传
                        runOnUiThread(() -> {
                            if (leaving) return;
                            Toast.makeText(OnboardingActivity.this,
                                    R.string.virusdb_cancelled_toast, Toast.LENGTH_LONG).show();
                            goMainOnce();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (leaving) return;
                            downloadProgress.setVisibility(View.GONE);
                            downloadStatus.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
                            new AlertDialog.Builder(OnboardingActivity.this)
                                    .setTitle(R.string.virusdb_download_failed_title)
                                    .setMessage(getString(R.string.virusdb_download_failed_msg, message))
                                    .setPositiveButton(R.string.virusdb_retry,
                                            (d, w) -> startRegionDownload())
                                    .setNegativeButton(R.string.virusdb_enter_builtin,
                                            (d, w) -> goMainOnce())
                                    .setCancelable(false)
                                    .show();
                        });
                    }
                },
                (downloaded, total) -> runOnUiThread(() -> updateProgress(downloaded, total))
        );
    }

    private void updateProgress(long downloaded, long total) {
        if (total > 0) {
            downloadProgress.setIndeterminate(false);
            int pct = (int) (downloaded * 100 / total);
            downloadProgress.setProgressCompat(pct, true);
            downloadStatus.setText(String.format(java.util.Locale.US,
                    "正在下载 Polar Region 病毒库… %.1f / %.1f MB",
                    downloaded / 1048576.0, total / 1048576.0));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 AiSetupActivity 返回后刷新 AI 接入状态
        if (aiStatus != null) refreshAiStatus();
    }

    @Override
    public void onBackPressed() {
        // 禁止返回键跳过欢迎页
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 颜色向 other 逐通道插值（t: 0→1），用于深色下提亮强调色。 */
    private static int mix(int c, int other, float t) {
        int r = Math.round(((c >> 16) & 0xFF) + (((other >> 16) & 0xFF) - ((c >> 16) & 0xFF)) * t);
        int g = Math.round(((c >> 8) & 0xFF) + (((other >> 8) & 0xFF) - ((c >> 8) & 0xFF)) * t);
        int b = Math.round((c & 0xFF) + ((other & 0xFF) - (c & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
