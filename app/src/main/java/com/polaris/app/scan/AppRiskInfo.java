package com.polaris.app.scan;

import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/** 单个应用的扫描结果（启发式风险评分）。 */
public class AppRiskInfo {

    public static final int LEVEL_SAFE = 0;
    public static final int LEVEL_LOW = 1;
    public static final int LEVEL_MEDIUM = 2;
    public static final int LEVEL_HIGH = 3;

    public String packageName;
    public CharSequence appName;
    public Drawable icon;
    public int score;               // 0..100
    public int level;               // LEVEL_*
    public List<String> reasons = new ArrayList<>();
    public boolean isSystem;        // 系统应用（降权处理）
    public boolean blocked;         // Shizuku/Root 已拦截标记

    public AppRiskInfo(String packageName) {
        this.packageName = packageName;
    }

    public static int levelOf(int score) {
        if (score >= 80) return LEVEL_HIGH;
        if (score >= 45) return LEVEL_MEDIUM;
        if (score >= 20) return LEVEL_LOW;
        return LEVEL_SAFE;
    }
}
