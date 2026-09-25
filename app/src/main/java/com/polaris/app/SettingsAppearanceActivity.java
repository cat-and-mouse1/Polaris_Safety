package com.polaris.app;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.polaris.app.R;
import com.polaris.app.util.Prefs;

/**
 * 外观子页：深色 / 浅色 / 跟随系统切换 + 自定义主题色色板。
 * 从主设置页「外观」入口进入。
 */
public class SettingsAppearanceActivity extends BaseActivity {

    /** 默认主题色：基础主题主色 #00639B，作为色板首项与「默认配色」对应色。 */
    private static final int DEFAULT_THEME_BLUE = 0xFF00639B;

    /** 预设主题种子色（用于生成完整 Material 配色）。 */
    private static final int[] PRESET_COLORS = {
            DEFAULT_THEME_BLUE, // 蓝（默认主题色）
            0xFF7E57C2, // 紫
            0xFF00897B, // 青绿
            0xFFFB8C00, // 橙
            0xFFE53935, // 红
            0xFFD81B60, // 粉
    };

    private LinearLayout themeColorSwatches;
    private MaterialButtonToggleGroup themeModeGroup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_appearance);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        setupThemeColorSection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题色变更后本页会在 BaseActivity.onResume 自愈重建，无需额外处理
    }

    /** 外观：深色/浅色切换 + 自定义主题色色板 + 默认配色。 */
    private void setupThemeColorSection() {
        themeColorSwatches = findViewById(R.id.themeColorSwatches);
        themeModeGroup = findViewById(R.id.themeModeGroup);

        final int dp = (int) (getResources().getDisplayMetrics().density + 0.5f);

        // 深色 / 浅色 / 跟随系统
        Prefs prefs = new Prefs(this);
        int themeMode = prefs.getThemeMode();
        int checkedId = themeMode == Prefs.THEME_DARK ? R.id.themeModeDark
                : themeMode == Prefs.THEME_LIGHT ? R.id.themeModeLight
                : R.id.themeModeSystem;
        themeModeGroup.check(checkedId);
        themeModeGroup.addOnButtonCheckedListener((group, checkedId1, isChecked) -> {
            if (!isChecked) return;
            int mode;
            int toast;
            if (checkedId1 == R.id.themeModeDark) {
                mode = Prefs.THEME_DARK;
                toast = R.string.theme_toast_dark;
            } else if (checkedId1 == R.id.themeModeLight) {
                mode = Prefs.THEME_LIGHT;
                toast = R.string.theme_toast_light;
            } else {
                mode = Prefs.THEME_SYSTEM;
                toast = R.string.theme_toast_system;
            }
            prefs.setThemeMode(mode);
            PolarisApp.applyTheme(mode);
            Toast.makeText(SettingsAppearanceActivity.this, toast, Toast.LENGTH_SHORT).show();
            PolarisApp.recreateOtherActivities();
        });

        // 自定义主题色色板
        final int mode = prefs.getThemeColorMode();
        final int selectedColor = prefs.getCustomThemeColor();

        for (final int color : PRESET_COLORS) {
            final boolean selected = (mode == Prefs.THEME_COLOR_CUSTOM && selectedColor == color)
                    || (mode == Prefs.THEME_COLOR_DEFAULT && color == DEFAULT_THEME_BLUE);
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
            if (selected) gd.setStroke(3 * dp, 0xFFFFFFFF);
            dot.setBackground(gd);
            wrap.addView(dot);

            wrap.setOnClickListener(v -> {
                if (color == DEFAULT_THEME_BLUE) {
                    prefs.setThemeColorMode(Prefs.THEME_COLOR_DEFAULT);
                } else {
                    prefs.setThemeColorMode(Prefs.THEME_COLOR_CUSTOM);
                    prefs.setCustomThemeColor(color);
                }
                Toast.makeText(SettingsAppearanceActivity.this,
                        R.string.custom_theme_applied, Toast.LENGTH_SHORT).show();
                PolarisApp.refreshAllActivities();
            });
            themeColorSwatches.addView(wrap);
        }
    }
}
