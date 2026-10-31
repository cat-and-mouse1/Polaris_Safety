package com.polaris.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.ViewGroup;

import android.view.View;

/**
 * HomeIconFx（MainActivity 拆分）：方法体自 MainActivity 原样搬移，
 * 字段/继承方法经 act. 引用；MainActivity 保留同名委托，调用点零改动。
 */
final class HomeIconFx {

    private final MainActivity act;

    HomeIconFx(MainActivity act) {
        this.act = act;
    }

         void collapseHomeIcon() {
        if (act.homeAppIcon == null) return;
        // 横屏：图标位于左栏垂直居中，结果页无需隐藏
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        if (dm.widthPixels > dm.heightPixels) return;
        if (act.homeAppIcon.getVisibility() != View.VISIBLE) return;
        int h = act.homeAppIcon.getHeight();
        if (h <= 0) {
            act.homeAppIcon.setVisibility(View.GONE);
            return;
        }
        act.homeAppIcon.animate().translationY(-h).alpha(0f).setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS)
                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_OUT_EASE)
                .withEndAction(() -> {
                    act.homeAppIcon.setVisibility(View.GONE);
                    act.homeAppIcon.setTranslationY(0f);
                    act.homeAppIcon.setAlpha(1f);
                }).start();

        // 同步把占位高度收到 0，否则会留下一块空白
        ValueAnimator va = ValueAnimator.ofInt(h, 0);
        va.setDuration(com.polaris.app.util.UiFx.SLIDE_OUT_MS);
        va.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = act.homeAppIcon.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            act.homeAppIcon.setLayoutParams(lp);
        });
        va.start();
    }

         void expandHomeIcon() {
        if (act.homeAppIcon == null || act.homeIconSize <= 0) return;
        // 横屏：图标常驻左栏，确保可见即可
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        if (dm.widthPixels > dm.heightPixels) {
            if (act.homeAppIcon.getVisibility() != View.VISIBLE) {
                act.homeAppIcon.setVisibility(View.VISIBLE);
                act.homeAppIcon.setAlpha(1f);
                act.homeAppIcon.setTranslationY(0f);
            }
            return;
        }
        if (act.homeAppIcon.getVisibility() == View.VISIBLE
                && act.homeAppIcon.getLayoutParams().height == act.homeIconSize) {
            return;
        }
        act.homeAppIcon.setVisibility(View.VISIBLE);
        act.homeAppIcon.setAlpha(1f);
        act.homeAppIcon.setTranslationY(0f);
        ValueAnimator va = ValueAnimator.ofInt(
                Math.max(0, act.homeAppIcon.getLayoutParams().height), act.homeIconSize);
        va.setDuration(com.polaris.app.util.UiFx.SLIDE_IN_MS);
        va.setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE);
        va.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = act.homeAppIcon.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            act.homeAppIcon.setLayoutParams(lp);
        });
        va.start();
    }

         void startIconSpin() {
        if (act.iconSpin != null) act.iconSpin.cancel();
        act.iconSpin = ObjectAnimator.ofFloat(act.homeStar, "rotation", 0f, 360f);
        act.iconSpin.setDuration(1100);
        act.iconSpin.setRepeatCount(ValueAnimator.INFINITE);
        act.iconSpin.start();
    }

         void stopIconSpin() {
        if (act.iconSpin != null) {
            act.iconSpin.cancel();
            act.iconSpin = null;
        }
        act.homeStar.setRotation(0f);
    }
}
