package com.polaris.app;

import android.os.Bundle;

import androidx.annotation.CallSuper;
import androidx.appcompat.app.AppCompatActivity;

import com.polaris.app.util.Prefs;

/**
 * 统一基类：所有需要跟随自定义主题色的页面都应继承本类。
 * 关键解决「主题色只在一个页面生效」的问题——
 * 过去依赖对后台 Activity 调 recreate() 来刷新，但 Android 对后台 Activity 的 recreate 不可靠，
 * 导致返回主界面时仍是旧主题。这里改为每个页面在 onCreate 注入主题色，并在回到前台时自愈。
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** 本页创建时实际应用的主题色模式/颜色，用于 onResume 检测是否需要自愈。 */
    private int mAppliedColorMode = -1;
    private int mAppliedCustomColor = -1;

    @Override
    @CallSuper
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate（含 setContentView）之前注入，否则已 inflate 的 View 不会变色
        Prefs prefs = new Prefs(this);
        mAppliedColorMode = prefs.getThemeColorMode();
        mAppliedCustomColor = prefs.getCustomThemeColor();
        PolarisApp.applyDynamicColors(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    @CallSuper
    protected void onResume() {
        super.onResume();
        // 后台页面回到前台时自愈：若主题色已变更，重建本页以应用新配色
        Prefs prefs = new Prefs(this);
        int mode = prefs.getThemeColorMode();
        boolean changed = (mode != mAppliedColorMode)
                || (mode == Prefs.THEME_COLOR_CUSTOM
                    && prefs.getCustomThemeColor() != mAppliedCustomColor);
        if (changed) {
            recreate();
        }
    }
}
