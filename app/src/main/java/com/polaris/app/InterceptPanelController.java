package com.polaris.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import androidx.core.content.ContextCompat;
import com.polaris.app.util.PermUtil;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.polaris.app.scan.Notifier;
import com.polaris.app.service.GuardService;
import com.polaris.app.util.Prefs;

/**
 * 拦截模式面板控制器（MainActivity 拆分第五刀）：
 * 火箭动画后的地下面板渲染、实时防护开关、拦截名单、权限引导、卫星联动。
 * 持有 MainActivity 引用，直接访问其包可见字段（同包，可见性已从 private 放宽）。
 */
final class InterceptPanelController {

    private final MainActivity act;

    InterceptPanelController(MainActivity act) {
        this.act = act;
    }

         void startInterceptFlow() {
        // 先收起模式/快捷面板（内容层在火箭场景之下，会盖住这幕）
        act.hidePanelsExcept(null);
        if (act.prefs.isBlockMode()) {
            // 拦截已开启：直接揭示功能面板，不重复播放对接动画
            //（动画只在「开启拦截」那一次作为揭示；常驻对接舱由 syncDockModule 保活）
            act.interceptFlowActive = false;
            if (act.rocketIntro != null) act.rocketIntro.setVisibility(View.GONE);
            showInterceptPanel();
            return;
        }
        if (act.rocketIntro == null) {
            act.interceptFlowActive = false;
            showInterceptPanel();
            return;
        }
        act.interceptFlowActive = true;
        final float[] rel = new float[]{-1f, -1f};   // 真实释放点（布局完成后读取）
        act.rocketIntro.playInterceptIntro(
                () -> renderInterceptPanel(),   // onReveal：地面抬升时预渲染（面板仍 GONE）
                () -> {                          // onEnd：发射完成、火箭离屏 → 收起火箭层，滑出功能面板
                    act.interceptFlowActive = false;
                    // 对接舱与常驻卫星交会对接（作为揭示动画；拦截关闭时由
                    // disableInterceptBlockMode 撤下，开启时由 syncDockModule 保活）
                    act.starMap.enableDockModule(rel[0], rel[1]);
                    if (act.rocketIntro != null) act.rocketIntro.setVisibility(View.GONE);
                    showInterceptPanel();
                });
        // 先启动动画（内部 setVisibility(VISIBLE) 且置 interceptMode）→ 等本帧布局
        // 完成后再读真实释放点：上轮流程结束时火箭层为 GONE（宽高为 0），直接读会
        // 拿到 {0,0} 退回默认释放点，造成星图交棒瞬间的位置跳变
        act.rocketIntro.post(new Runnable() {
            @Override public void run() {
                if (act.rocketIntro.getWidth() <= 0 || act.rocketIntro.getHeight() <= 0) {
                    act.rocketIntro.post(this);   // 等布局完成
                    return;
                }
                act.rocketIntro.getSatelliteReleasePoint(rel);
                if (act.starMap != null) {
                    act.starMap.prepareRendezvous(rel[0], rel[1], act.rocketIntro.getDeployDelayMs());
                }
            }
        });
    }

         void showInterceptPanel() {
        if (act.interceptUnderground == null) return;
        renderInterceptPanel();
        // 仅当拦截已开启且对接舱未在轨时补齐；此处不撤除，避免误删「发射动画」刚揭示的对接舱
        if (act.prefs.isBlockMode() && act.starMap != null && !act.starMap.isDockModuleEnabled()) {
            act.starMap.enableDockModule();
        }
        int h = (int) (act.getResources().getDisplayMetrics().density * 560f + 0.5f);
        act.interceptUnderground.setTranslationY(h);
        act.interceptUnderground.setAlpha(1f);
        act.interceptUnderground.setVisibility(View.VISIBLE);
        act.interceptUnderground.animate().setInterpolator(com.polaris.app.util.UiFx.FADE_EASE)
                .setDuration(420).translationY(0f).start();
        act.interceptActive = true;
        // 应用列表折叠按钮在两种高度下都显示（拦截未开启时面板停在 560dp 也需可见）
        if (act.itcBlockFold != null) {
            act.itcBlockFold.setVisibility(View.VISIBLE);
            act.itcBlockFold.setRotation(act.itcBlockFolded ? 0f : 180f);
        }
        if (act.itcBlockBody != null)
            act.itcBlockBody.setVisibility(act.itcBlockFolded ? View.GONE : View.VISIBLE);
        // 拦截模式已启用 → 打开面板自动全屏展开
        if (act.prefs.isBlockMode()) setInterceptExpanded(true);
    }

         void hideInterceptPanel() {
        if (act.interceptUnderground == null) return;
        act.interceptActive = false;
        // 若处于全屏展开态，先收回高度并恢复状态栏，再下滑隐藏
        if (act.itcExpanded) {
            act.itcExpanded = false;
            android.view.ViewGroup.LayoutParams ep = act.interceptUnderground.getLayoutParams();
            if (ep != null) {
                ep.height = (int) (act.getResources().getDisplayMetrics().density * 560f + 0.5f);
                act.interceptUnderground.setLayoutParams(ep);
            }
            act.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (act.itcExpand != null) act.itcExpand.setRotation(0f);
        }
        // 面板关闭且拦截未开启 → 清理「发射动画」揭示的对接舱（拦截开启则保留常驻）
        if (act.starMap != null && !act.prefs.isBlockMode()) act.starMap.disableDockModule();
        int h = (int) (act.getResources().getDisplayMetrics().density * 560f + 0.5f);
        act.interceptUnderground.animate().setInterpolator(com.polaris.app.util.UiFx.FADE_EASE)
                .setDuration(300).translationY(h)
                .withEndAction(() -> {
                    act.interceptUnderground.setVisibility(View.GONE);
                    act.interceptUnderground.setTranslationY(0f);
                    act.showModeStage();   // 恢复「以模式开始扫描 / 拦截」快捷面板
                }).start();
    }

         void toggleInterceptExpand() {
        setInterceptExpanded(!act.itcExpanded);
    }

         void setInterceptExpanded(boolean expand) {
        if (act.interceptUnderground == null || act.itcExpanded == expand) return;
        act.itcExpanded = expand;
        android.view.ViewGroup.LayoutParams p = act.interceptUnderground.getLayoutParams();
        if (p == null) p = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        p.height = expand ? android.view.ViewGroup.LayoutParams.MATCH_PARENT
                : (int) (act.getResources().getDisplayMetrics().density * 560f + 0.5f);
        act.interceptUnderground.setLayoutParams(p);
        android.view.Window w = act.getWindow();
        if (expand) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (act.itcExpand != null) act.itcExpand.setRotation(expand ? 180f : 0f);
        // 折叠按钮在 560dp 与全屏两种高度下都显示；按 act.itcBlockFolded 决定应用列表显隐
        if (act.itcBlockFold != null) act.itcBlockFold.setVisibility(View.VISIBLE);
        if (act.itcBlockFold != null) act.itcBlockFold.setRotation(act.itcBlockFolded ? 0f : 180f);
        if (act.itcBlockBody != null)
            act.itcBlockBody.setVisibility(act.itcBlockFolded ? View.GONE : View.VISIBLE);
    }

         void toggleInterceptBlockFold() {
        act.itcBlockFolded = !act.itcBlockFolded;
        if (act.itcBlockBody != null)
            act.itcBlockBody.setVisibility(act.itcBlockFolded ? View.GONE : View.VISIBLE);
        if (act.itcBlockFold != null) act.itcBlockFold.setRotation(act.itcBlockFolded ? 0f : 180f);
    }

         void renderInterceptPanel() {
        if (act.interceptUnderground == null) return;
        boolean on = act.prefs.isBlockMode();
        act.itcMonitorState.setText(on ? R.string.block_state_on : R.string.block_state_off);
        // 编程设值开关，避免触发监听器递归
        act.itcProgrammatic = true;
        act.itcToggle.setChecked(on);
        act.itcProgrammatic = false;
        renderInterceptPerms();
        act.itcCount.setText(act.getString(R.string.block_count, act.prefs.getBlockedApps().size()));
        renderInterceptBlockList();   // 内联已拦截应用列表
    }

         void renderInterceptBlockList() {
        java.util.Set<String> apps = act.prefs.getBlockedApps();
        if (act.itcBlockList == null) return;
        act.itcBlockList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(act);
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (final String pkg : apps) {
            if (pkg == null || pkg.isEmpty()) continue;
            if (!act.itcBlockQuery.isEmpty()
                    && !appLabel(pkg).toLowerCase().contains(act.itcBlockQuery)
                    && !pkg.toLowerCase().contains(act.itcBlockQuery)) {
                continue;   // 不匹配搜索词（按应用名或包名）
            }
            matched.add(pkg);
        }
        if (matched.isEmpty()) {
            if (act.itcBlockEmpty != null) {
                act.itcBlockEmpty.setVisibility(View.VISIBLE);
                act.itcBlockEmpty.setText(act.itcBlockQuery.isEmpty()
                        ? R.string.block_list_empty : R.string.block_search_empty);
            }
            return;
        }
        if (act.itcBlockEmpty != null) act.itcBlockEmpty.setVisibility(View.GONE);
        for (final String pkg : matched) {
            View item = inflater.inflate(R.layout.item_blocked_app, act.itcBlockList, false);
            ((TextView) item.findViewById(R.id.blockedPkg)).setText(appLabel(pkg));
            ((TextView) item.findViewById(R.id.blockedReason)).setText(pkg);
            item.findViewById(R.id.btnBlockAllow).setOnClickListener(v -> {
                act.prefs.removeBlockedApp(pkg);
                Toast.makeText(act, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
                renderInterceptPanel();
            });
            item.findViewById(R.id.btnBlockDelete).setOnClickListener(v -> {
                act.prefs.removeBlockedApp(pkg);
                renderInterceptPanel();
                launchUninstall(pkg);
            });
            act.itcBlockList.addView(item);
        }
    }

         void renderInterceptPerms() {
        if (act.itcPermList == null) return;
        act.itcPermList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(act);
        if (!PermUtil.isAccessibilityEnabled(act)) {
            act.itcPermList.addView(act.buildInterceptPermRow(inflater, R.drawable.ic_perm_accessibility,
                    act.getString(R.string.perm_accessibility_title), act.getString(R.string.perm_accessibility_desc),
                    () -> PermUtil.openAccessibilitySettings(act)));
        }
        if (!PermUtil.canDrawOverlays(act)) {
            act.itcPermList.addView(act.buildInterceptPermRow(inflater, R.drawable.ic_perm_overlay,
                    act.getString(R.string.perm_overlay_title), act.getString(R.string.perm_overlay_desc),
                    () -> PermUtil.openOverlaySettings(act)));
        }
        if (!PermUtil.hasUsageAccess(act)) {
            act.itcPermList.addView(act.buildInterceptPermRow(inflater, R.drawable.ic_perm_usage,
                    act.getString(R.string.perm_usage_title), act.getString(R.string.perm_usage_desc),
                    () -> PermUtil.openUsageAccessSettings(act)));
        }
        // 全部已授权 → 隐藏「所需权限」标题与列表区块；否则显示
        boolean anyMissing = act.itcPermList.getChildCount() > 0;
        int vis = anyMissing ? View.VISIBLE : View.GONE;
        if (act.itcPermTitle != null) act.itcPermTitle.setVisibility(vis);
        act.itcPermList.setVisibility(vis);
    }

         void onInterceptToggle(boolean on) {
        if (act.itcProgrammatic) return;
        if (on) {
            ensureInterceptNotificationThenEnable();
        } else {
            disableInterceptBlockMode();
        }
    }

         void ensureInterceptNotificationThenEnable() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(act,                     new String[]{Manifest.permission.POST_NOTIFICATIONS}, act.REQ_NOTIFICATION);
            return;
        }
        enableInterceptBlockMode();
    }

         void enableInterceptBlockMode() {
        // 拦截依赖守护服务：仅 Accessibility / Shizuku / Root 模式可开启
        if (!act.prefs.setBlockMode(true)) {
            Toast.makeText(act, R.string.block_need_deep_mode, Toast.LENGTH_SHORT).show();
            renderInterceptPanel();   // 开关回弹到真实状态
            return;
        }
        Notifier.updateBlockNotification(act);
        Toast.makeText(act, R.string.block_on_toast, Toast.LENGTH_SHORT).show();
        renderInterceptPanel();
        syncDockModule();   // 拦截开启 → 对接舱与卫星同轨常驻
    }

         void disableInterceptBlockMode() {
        act.prefs.setBlockMode(false);
        Notifier.cancelBlockNotification(act);
        Toast.makeText(act, R.string.block_off_toast, Toast.LENGTH_SHORT).show();
        renderInterceptPanel();
        act.starMap.disableDockModule();   // 拦截关闭 → 撤下对接舱
    }

         void syncDockModule() {
        if (act.starMap == null) return;
        if (act.prefs.isBlockMode()) {
            act.starMap.enableDockModule();   // 已启用时不重置交会相位
        } else {
            act.starMap.disableDockModule();
        }
    }

         void restoreResidentOrbits() {
        if (act.starMap == null) return;
        if (act.starMap.getWidth() <= 0 || act.starMap.getHeight() <= 0) {
            act.starMap.post(this::restoreResidentOrbits);   // 等布局完成再锚定（避免以 0 尺寸锚定跳跃）
            return;
        }
        if (act.isDeepMode()) {
            if (!act.starMap.isSatelliteOrbitEnabled()) {
                float W = act.starMap.getWidth(), H = act.starMap.getHeight();
                act.starMap.enableSatelliteOrbit(W * 0.72f, H * 0.12f);   // 默认释放点（顶部偏右）
            }
        } else {
            act.starMap.disableSatelliteOrbit();
        }
        syncDockModule();   // 拦截开启 → 对接舱常驻；关闭 → 撤下
    }

         void showBlockAppPicker() {
        PackageManager pm = act.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        java.util.List<String[]> apps = new java.util.ArrayList<>();   // {label, pkg}
        java.util.Set<String> blocked = act.prefs.getBlockedApps();
        for (android.content.pm.ResolveInfo ri : infos) {
            String p = ri.activityInfo != null ? ri.activityInfo.packageName : null;
            if (p == null || p.equals(act.getPackageName())) continue;
            boolean dup = false;
            for (String[] a : apps) if (a[1].equals(p)) { dup = true; break; }
            if (dup) continue;
            CharSequence l = ri.loadLabel(pm);
            apps.add(new String[]{l != null ? l.toString() : p, p});
        }
        if (apps.isEmpty()) return;
        java.util.Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        final int n = apps.size();
        String[] labels = new String[n];
        final String[] pkgs = new String[n];
        final boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            labels[i] = apps.get(i)[0];
            pkgs[i] = apps.get(i)[1];
            checked[i] = blocked.contains(pkgs[i]);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(R.string.block_add_apps)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    boolean added = false;
                    for (int i = 0; i < n; i++) {
                        if (checked[i] && !blocked.contains(pkgs[i])) {
                            act.prefs.addBlockedApp(pkgs[i]);
                            added = true;
                        }
                    }
                    if (added) renderInterceptPanel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

         String appLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    act.getPackageManager().getApplicationInfo(pkg, 0);
            return act.getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

         void launchUninstall(String pkg) {
        try {
            act.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(act, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }
}
