package com.polaris.app;

import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

/**
 * StarDetailController（MainActivity 拆分）：方法体自 MainActivity 原样搬移，
 * 字段/继承方法经 act. 引用；MainActivity 保留同名委托，调用点零改动。
 */
final class StarDetailController {

    private final MainActivity act;

    StarDetailController(MainActivity act) {
        this.act = act;
    }

         void applyStarDetailGeometry(boolean animate) {
        if (act.starDetailPanel == null) return;
        act.starDetailPanel.post(() -> {
            if (act.starDetailPanel == null) return;
            float d = act.getResources().getDisplayMetrics().density;
            int parentW = act.starDetailPanel.getParent() instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) act.starDetailPanel.getParent()).getWidth()
                    : act.getResources().getDisplayMetrics().widthPixels;
            int w = (int) Math.min(300 * d, parentW * 0.56f);
            if (w <= 0) w = (int) (300 * d);
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) act.starDetailPanel.getLayoutParams();
            if (lp == null) return;
            lp.width = w;
            lp.gravity = android.view.Gravity.END;     // 占满整个高度，贴右缘
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, 0, 0, 0);            // 抽屉：直角贴右缘、占满整个高度
            act.starDetailPanel.setLayoutParams(lp);
            if (!animate) act.starDetailPanel.setTranslationX(0f);
        });
    }

         void focusStarView(String key) {
        if (act.starMap == null || key == null) return;
        act.starMap.post(() -> {
            if (act.starMap == null || key == null) return;
            int vw = act.starMap.getWidth();
            if (vw <= 0) return;
            float d = act.getResources().getDisplayMetrics().density;
            act.starMap.setRightInsetPx(Math.min(300 * d, vw * 0.56f));
            act.starMap.focusStar(key, 0f);   // 0 = 按该恒星大小自动放大到合适尺寸
        });
    }

         void settleStarMapLayout() {
        if (act.starMap == null) return;
        act.starMap.post(() -> {
            if (act.starMap == null) return;
            act.starMap.settleFixedLayout();
            act.starMap.keepCurrentAsHome();   // 结果态延续扫描态视角，不再重新缩放
        });
    }

         void openStarDetailTarget() {
        if (act.starDetailOpenBtn == null) return;
        if (act.starDetailKey == null || !act.starDetailKey.startsWith("app:")) return;
        String pkg = packageOf(act.starDetailKey, null);
        if (pkg == null || pkg.isEmpty()) return;
        try {
            android.content.Intent i = act.getPackageManager()
                    .getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            }
        } catch (Exception ignored) {
            Toast.makeText(act, R.string.toast_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

         void applyStarNameFade() {
        if (act.starDetailNameFade == null) return;
        android.util.TypedValue tv = new android.util.TypedValue();
        int solid = android.graphics.Color.TRANSPARENT;
        if (act.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerHighest, tv, true)) {
            solid = tv.data;
        }
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0x00000000, solid});
        act.starDetailNameFade.setBackground(gd);
    }

         void hideStarDetail() {
        if (!act.starDetailOpen || act.starDetailPanel == null) return;
        act.starDetailOpen = false;
        if (act.starMap != null) {                          // 抽屉收起：停掉未完成的视角平移并复原选中星大小
            act.starMap.cancelPanAnim();
            act.starMap.clearFocusStar();
            act.starMap.setRightInsetPx(0f);
        }
        act.starDetailPanel.animate().translationX(panelSlideWidth()).setDuration(260L)
                .setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE)
                .withEndAction(() -> act.starDetailPanel.setVisibility(View.GONE))
                .start();
        slideDetailCompanions(false);
    }

         void showSatelliteDetail() {
        if (act.starDetailPanel == null) return;
        if (act.starDetailFacts != null) act.starDetailFacts.setVisibility(View.GONE);
        if (act.starDetailMap != null) act.starDetailMap.setVisibility(View.GONE);
        if (act.starDetailTypeLabel != null) act.starDetailTypeLabel.setText(R.string.star_detail_type);
        if (act.starDetailSizeLabel != null) {
            act.starDetailSizeLabel.setText(R.string.sat_detail_orbit_label);
        }
        if (act.starDetailChildrenLabel != null) {
            act.starDetailChildrenLabel.setText(R.string.sat_detail_status_label);
        }
        if (act.starDetailTimeLabel != null) {
            act.starDetailTimeLabel.setText(R.string.sat_detail_deploy_label);
        }
        if (act.starDetailName != null) act.starDetailName.setText(R.string.sat_detail_name);
        if (act.starDetailSub != null) act.starDetailSub.setText(R.string.sat_detail_sub);
        if (act.starDetailType != null) act.starDetailType.setText(R.string.sat_detail_type);
        if (act.starDetailSize != null) act.starDetailSize.setText(R.string.sat_detail_orbit_value);
        if (act.starDetailChildren != null) {
            act.starDetailChildren.setText(R.string.sat_detail_status_value);
        }
        if (act.starDetailTime != null) {
            if (act.lastScanFinishedAt > 0L) {
                act.starDetailTime.setText(act.getString(R.string.sat_detail_deploy_value_fmt,
                        android.text.format.DateFormat.getTimeFormat(act)
                                .format(act.lastScanFinishedAt)));
            } else {
                act.starDetailTime.setText(R.string.star_detail_never);
            }
        }
        if (act.starDetailRisk != null) act.starDetailRisk.setText(starRiskText(0));
        setStarDetailRisk(0);
        if (act.starDetailAdvice != null) {
            act.starDetailAdvice.setText(R.string.sat_detail_advice);
            act.starDetailAdvice.setTextColor(riskColor(0));
        }
        if (act.starDetailActionBar != null) act.starDetailActionBar.setVisibility(View.GONE);
        act.starDetailPanel.setVisibility(View.VISIBLE);
        if (act.starDetailOpen) return;      // 已在屏上：仅换内容
        act.starDetailOpen = true;
        slideDetailCompanions(true);
        act.starDetailPanel.post(() -> {
            if (act.starDetailPanel == null) return;
            float from = act.starDetailPanel.getWidth() > 0
                    ? act.starDetailPanel.getWidth() : act.starDetailPanel.getMeasuredWidth();
            act.starDetailPanel.setTranslationX(from);
            act.starDetailPanel.animate().translationX(0f)
                    .setDuration(300L).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE).start();
        });
    }

         void slideDetailCompanions(boolean shifted) {
        float d = act.getResources().getDisplayMetrics().density;
        float dx = shifted ? -(panelSlideWidth() + 10 * d) : 0f;
        slideCompanionForDetail(act.resultEntryBar, dx, 6 * d);
        slideCompanionForDetail(act.zoomSlider, dx, 4 * d);
    }

         void slideCompanionForDetail(android.view.View v, float dx, float keepPx) {
        if (v == null) return;
        float baseLeft = v.getX() - v.getTranslationX();   // 未平移时的左缘
        float tx = dx;
        float alpha = 1f;
        if (dx < -(baseLeft - keepPx)) {                   // 会出屏 → 不平移，淡出
            tx = 0f;
            alpha = 0f;
        }
        v.animate().translationX(tx).alpha(alpha)
                .setDuration(260L).setInterpolator(com.polaris.app.util.UiFx.SLIDE_IN_EASE)
                .setStartDelay(40L).start();
    }

         void hideStarDetailQuiet() {
        if (!act.starDetailOpen || act.starDetailPanel == null) return;
        act.starDetailOpen = false;
        act.starDetailPanel.setTranslationX(0f);
        act.starDetailPanel.setVisibility(View.GONE);
        if (act.starMap != null) {
            act.starMap.cancelPanAnim();
            act.starMap.clearFocusStar();
            act.starMap.setRightInsetPx(0f);
        }
        slideDetailCompanions(false);
    }

         float panelSlideWidth() {
        int w = act.starDetailPanel != null ? act.starDetailPanel.getWidth() : 0;
        if (w <= 0 && act.starDetailPanel != null) w = act.starDetailPanel.getMeasuredWidth();
        float d = act.getResources().getDisplayMetrics().density;
        return w > 0 ? w : 300 * d;
    }

         void renderStarDetailMap(String key, String label, long bytes, int level, boolean isApp) {
        if (act.starDetailMap == null) return;
        act.starDetailMap.post(() -> {
            if (act.starDetailMap == null) return;
            if (act.starDetailMap.getWidth() <= 0) {   // 首帧尚未测量：重投一次
                renderStarDetailMap(key, label, bytes, level, isApp);
                return;
            }
            act.starDetailMap.resetScanField();        // 清掉上一颗恒星
            act.starDetailMap.beginScanField(1);
            if (isApp) {
                act.starDetailMap.revealAppStar(packageOf(key, label), label, bytes, level);
            } else {
                act.starDetailMap.revealItem(level, bytes, label);
            }
        });
    }

         void setStarDetailRisk(int level) {
        if (act.starDetailDots == null) return;
        act.starDetailDots.removeAllViews();
        int lit = Math.max(0, Math.min(4, level + 1));
        int top = Math.max(0, Math.min(3, level));
        int litColor = riskColor(top);
        float d = act.getResources().getDisplayMetrics().density;
        android.util.TypedValue tv = new android.util.TypedValue();
        int outline = 0;
        if (act.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            outline = tv.data;
        }
        for (int i = 0; i < 4; i++) {
            android.view.View v = new android.view.View(act);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams((int) (20 * d), (int) (4 * d));
            lp.setMargins(i > 0 ? (int) (6 * d) : 0, 0, 0, 0);
            v.setLayoutParams(lp);
            if (i < lit) {
                v.setBackgroundColor(litColor);
            } else {
                v.setBackgroundColor(outline);
                v.setAlpha(0.45f);
            }
            act.starDetailDots.addView(v);
        }
    }

         void setStarDetailOrbit(int planetCount) {
        if (act.starDetailOrbit == null) return;
        act.starDetailOrbit.removeAllViews();
        int slots = Math.max(0, Math.min(6, planetCount));
        float d = act.getResources().getDisplayMetrics().density;
        android.util.TypedValue tv = new android.util.TypedValue();
        int outline = 0;
        if (act.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            outline = tv.data;
        }
        for (int i = 0; i < 6; i++) {
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setSize((int) (8 * d), (int) (8 * d));
            gd.setColor(i < slots ? outline : outline);
            gd.setAlpha(i < slots ? 0xFF : 0x40);
            android.view.View v = new android.view.View(act);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d));
            lp.setMargins(i > 0 ? (int) (8 * d) : 0, 0, 0, 0);
            v.setLayoutParams(lp);
            v.setBackground(gd);
            act.starDetailOrbit.addView(v);
        }
    }

         String starRiskText(int level) {
        if (level >= 3) return act.getString(R.string.risk_high);
        if (level == 2) return act.getString(R.string.risk_medium);
        if (level == 1) return act.getString(R.string.risk_low);
        if (level == 0) return act.getString(R.string.risk_safe);
        return act.getString(R.string.star_detail_risk_unknown);
    }

         int riskColor(int level) {
        int rid = level >= 3 ? R.color.risk_high
                : level == 2 ? R.color.risk_medium
                : level == 1 ? R.color.risk_low : R.color.risk_safe;
        return androidx.core.content.ContextCompat.getColor(act, rid);
    }

         static String packageOf(String key, String fallback) {
        if (key != null && key.startsWith("app:") && key.length() > 4) return key.substring(4);
        return fallback == null ? "" : fallback;
    }

         static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

         long appSizeBytes(String packageName) {
        try {
            android.content.pm.ApplicationInfo ai = act.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            long len = ai.sourceDir == null ? 0 : new java.io.File(ai.sourceDir).length();
            return len > 0 ? len : 1024L * 1024;
        } catch (Exception e) {
            return 1024L * 1024;
        }
    }
}
