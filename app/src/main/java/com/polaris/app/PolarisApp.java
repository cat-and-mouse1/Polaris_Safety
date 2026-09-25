package com.polaris.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.polaris.app.scan.IocRefreshWorker;
import com.polaris.app.util.Prefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 应用入口：恢复用户保存的外观主题、自定义主题色（全局生效），注册病毒库定时刷新。
 */
public class PolarisApp extends Application {

    /** 当前存活的 Activity，用于在主题色 / 深浅模式变更时整体刷新。 */
    private static final Set<Activity> sActiveActivities = new HashSet<>();
    /** 当前前台 Activity（用于「深浅模式变更」时只重建后台栈其余页面）。 */
    private static Activity sForegroundActivity = null;

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(new Prefs(this).getThemeMode());
        // 追踪所有 Activity 生命周期（用于主题变更时刷新前台页；自定义主题色由各页 BaseActivity 自行注入）
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle b) { sActiveActivities.add(activity); }
            @Override public void onActivityResumed(@NonNull Activity activity) { sForegroundActivity = activity; }
            @Override public void onActivityPaused(@NonNull Activity activity) { if (sForegroundActivity == activity) sForegroundActivity = null; }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle b) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                sActiveActivities.remove(activity);
                if (sForegroundActivity == activity) sForegroundActivity = null;
            }
        });
        // 注册病毒库每日自动更新（若用户开启）
        IocRefreshWorker.schedule(this);
    }

    /** 应用主题模式；与 Prefs.THEME_* 常量对应。 */
    public static void applyTheme(int mode) {
        AppCompatDelegate.setDefaultNightMode(
                mode == Prefs.THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES
                        : mode == Prefs.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /**
     * 按用户保存的模式注入主题色：默认=应用内置主题；自定义=以种子色生成完整 Material 配色（含深浅自适应）；
     * 系统=使用系统墙纸莫奈配色。需 Android 12+。
     */
    public static void applyDynamicColors(Activity activity) {
        if (!DynamicColors.isDynamicColorAvailable()) return;
        Prefs prefs = new Prefs(activity);
        int mode = prefs.getThemeColorMode();
        if (mode == Prefs.THEME_COLOR_DEFAULT) return;
        DynamicColorsOptions.Builder b = new DynamicColorsOptions.Builder();
        if (mode == Prefs.THEME_COLOR_CUSTOM) {
            int color = prefs.getCustomThemeColor();
            if (color == -1) return;
            b.setContentBasedSource(color);
        }
        // THEME_COLOR_SYSTEM 不设置内容源，使用系统墙纸莫奈配色
        DynamicColors.applyToActivityIfAvailable(activity, b.build());
    }

    /**
     * 主题色变更：重建当前前台页（前台 recreate 可靠），其余后台页在各自 onResume 时自愈
     * （应用主动对后台 Activity 调 recreate() 在 Android 上不可靠，故交给 BaseActivity 处理）。
     */
    public static void refreshAllActivities() {
        if (sForegroundActivity != null && !sForegroundActivity.isFinishing() && !sForegroundActivity.isDestroyed()) {
            sForegroundActivity.recreate();
        }
    }

    /** 深浅模式变更：当前页由 setDefaultNightMode 自动重建，这里只重建后台栈其余页面。 */
    public static void recreateOtherActivities() {
        Activity cur = sForegroundActivity;
        for (Activity a : new ArrayList<>(sActiveActivities)) {
            if (a != cur && !a.isFinishing() && !a.isDestroyed()) a.recreate();
        }
    }
}
