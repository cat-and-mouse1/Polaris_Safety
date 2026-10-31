package com.polaris.app.util;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 结果页 / 向导的滑动与淡入动效 + 主题化底座样式（MainActivity 拆分第三刀）。
 * 全部为「View / Context 入参」的静态辅助，无业务状态。
 */
public final class UiFx {

    /** 进入用减速曲线（快速启动平缓收尾），退出用加速曲线 —— Material 强调曲线。 */
    public static final Interpolator SLIDE_IN_EASE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    public static final Interpolator SLIDE_OUT_EASE = new PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f);
    /** 结果页内容逐项淡入的缓动（比滑入更柔和，强调层级揭示）。 */
    public static final Interpolator FADE_EASE = new DecelerateInterpolator(1.3f);
    public static final long SLIDE_IN_MS = 360L;
    public static final long SLIDE_OUT_MS = 240L;
    public static final long FADE_MS = 420L;

    private UiFx() {
    }

    /** 解析主题属性色值（与 ObservatoryIntroView 同口径；未解析到返回不透明黑）。 */
    public static int resolveAttr(Context ctx, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return 0xFF000000;
    }

    public static void slideInLeft(View v) {
        v.setVisibility(View.VISIBLE);
        int w = v.getResources().getDisplayMetrics().widthPixels;
        v.setTranslationX(-w);
        v.setAlpha(1f);
        v.animate().translationX(0f).setDuration(SLIDE_IN_MS)
                .setInterpolator(SLIDE_IN_EASE).start();
    }

    public static void slideInRight(View v) {
        v.setVisibility(View.VISIBLE);
        int w = v.getResources().getDisplayMetrics().widthPixels;
        v.setTranslationX(w);
        v.setAlpha(1f);
        v.animate().translationX(0f).setDuration(SLIDE_IN_MS)
                .setInterpolator(SLIDE_IN_EASE).start();
    }

    /** 从下方滑入（结果页用，配合顶部图标上滑收起）。 */
    public static void slideInUp(View v) {
        v.setVisibility(View.VISIBLE);
        int h = v.getResources().getDisplayMetrics().heightPixels;
        v.setTranslationY(h * 0.22f);
        v.setAlpha(0f);
        v.animate().translationY(0f).alpha(1f).setDuration(SLIDE_IN_MS)
                .setInterpolator(SLIDE_IN_EASE).start();
    }

    public static void slideOutRight(View v) {
        int w = v.getResources().getDisplayMetrics().widthPixels;
        v.animate().translationX(w).alpha(0f).setDuration(SLIDE_OUT_MS)
                .setInterpolator(SLIDE_OUT_EASE)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    public static void slideOutLeft(View v) {
        int w = v.getResources().getDisplayMetrics().widthPixels;
        v.animate().translationX(-w).alpha(0f).setDuration(SLIDE_OUT_MS)
                .setInterpolator(SLIDE_OUT_EASE)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /** 单个视图淡入：从透明 + 轻微下移，回到原位，带起始延迟。 */
    public static void fadeInView(View v, long delay) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.setAlpha(0f);
        v.setTranslationY(14f);
        v.animate().alpha(1f).translationY(0f)
                .setDuration(FADE_MS).setStartDelay(delay).setInterpolator(FADE_EASE).start();
    }

    /** 结果页底座：90% 不透明的主题表面（透出一丝星图）+ 顶部主题色微光。 */
    public static void applySheetStyle(View resultArea) {
        if (resultArea == null) return;
        int surface = resolveAttr(resultArea.getContext(),
                com.google.android.material.R.attr.colorSurface);
        int primary = resolveAttr(resultArea.getContext(),
                com.google.android.material.R.attr.colorPrimary);
        int baseColor = (surface & 0x00FFFFFF) | 0xE6000000;   // 90% 不透明
        ColorDrawable base = new ColorDrawable(baseColor);
        int sheen = (primary & 0x00FFFFFF) | 0x1C000000;        // 顶部主题色微光（~11%）
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{sheen, 0x00000000});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, grad});
        resultArea.setBackground(layers);
    }

    /** 段面板背景：圆角 + 主题表面容器色 + 细描边的「玻璃卡」。 */
    public static Drawable panelBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(20f * d);
        g.setColor(resolveAttr(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow));
        g.setStroke((int) (1f * d),
                resolveAttr(ctx, com.google.android.material.R.attr.colorOutlineVariant));
        return g;
    }

    /** 四角星背后的柔光晕（主题色径向渐变），强化星空主题的焦点感。 */
    public static void starGlow(View icon) {
        if (icon == null) return;
        float d = icon.getResources().getDisplayMetrics().density;
        int primary = resolveAttr(icon.getContext(),
                com.google.android.material.R.attr.colorPrimary);
        int glow = (primary & 0x00FFFFFF) | 0x33000000;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(46f * d);
        g.setGradientCenter(0.5f, 0.5f);
        g.setColors(new int[]{glow, 0x00000000});
        icon.setBackground(g);
    }
}
