package com.polaris.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.polaris.app.util.Prefs;

/**
 * 应用入口：启动时恢复用户保存的外观主题（深色 / 浅色 / 跟随系统）。
 */
public class PolarisApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(new Prefs(this).getThemeMode());
    }

    /** 应用主题模式；与 Prefs.THEME_* 常量对应。 */
    public static void applyTheme(int mode) {
        AppCompatDelegate.setDefaultNightMode(
                mode == Prefs.THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES
                        : mode == Prefs.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
