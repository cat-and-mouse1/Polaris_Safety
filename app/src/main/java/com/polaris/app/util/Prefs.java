package com.polaris.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 模式状态与运行数据存储。 */
public class Prefs {

    public static final int MODE_NONE = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_ACCESSIBILITY = 2;
    public static final int MODE_SHIZUKU = 3;
    public static final int MODE_ROOT = 4;

    private static final String FILE = "polaris_prefs";
    private static final String KEY_ACTIVE_MODE = "active_mode";
    private static final String KEY_LAST_FOREGROUND = "last_foreground_pkg";
    private static final String KEY_LAST_SCAN_MS = "last_scan_ms";
    private static final String KEY_MALICIOUS = "malicious_pkgs";
    private static final String KEY_ACCESSIBILITY_READY = "accessibility_ready";
    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_AI_API_KEY = "ai_api_key";
    private static final String KEY_AI_MODEL = "ai_model";
    private static final String KEY_PREFERRED_ENGINE = "preferred_engine"; // normal | ai
    private static final String KEY_FILE_ALLOWLIST = "file_allowlist";     // 放行的文件路径
    private static final String KEY_THEME_MODE = "theme_mode";             // 外观主题
    private static final String KEY_BLOCK_MODE = "block_mode";             // 拦截模式开关
    private static final String KEY_BLOCKED_APPS = "blocked_apps";         // 被拦截应用（逗号分隔）
    private static final String KEY_VIRUS_AUTO = "virus_auto";             // 病毒库自动更新
    private static final String KEY_UPDATE_SOURCE = "update_source";       // 更新下载源：github | mirror

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getActiveMode() {
        return sp.getInt(KEY_ACTIVE_MODE, MODE_NONE);
    }

    public void setActiveMode(int mode) {
        sp.edit().putInt(KEY_ACTIVE_MODE, mode).apply();
    }

    public String getLastForegroundPkg() {
        return sp.getString(KEY_LAST_FOREGROUND, null);
    }

    public void setLastForegroundPkg(String pkg) {
        sp.edit().putString(KEY_LAST_FOREGROUND, pkg).apply();
    }

    public long getLastScanMs() {
        return sp.getLong(KEY_LAST_SCAN_MS, 0);
    }

    public void setLastScanMs(long ms) {
        sp.edit().putLong(KEY_LAST_SCAN_MS, ms).apply();
    }

    public Set<String> getMaliciousPackages() {
        return new HashSet<>(Arrays.asList(
                sp.getString(KEY_MALICIOUS, "").split(",")));
    }

    public void setMaliciousPackages(Set<String> pkgs) {
        String joined = TextUtil.join(pkgs);
        sp.edit().putString(KEY_MALICIOUS, joined).apply();
    }

    public boolean isAccessibilityReady() {
        return sp.getBoolean(KEY_ACCESSIBILITY_READY, false);
    }

    public void setAccessibilityReady(boolean ready) {
        sp.edit().putBoolean(KEY_ACCESSIBILITY_READY, ready).apply();
    }

    // ---------- AI 引擎配置 ----------

    /** 当前接入的服务商 id（AiProvider.id），未配置为 null。 */
    public String getAiProviderId() {
        return sp.getString(KEY_AI_PROVIDER, null);
    }

    public void setAiProviderId(String id) {
        sp.edit().putString(KEY_AI_PROVIDER, id).apply();
    }

    public String getAiApiKey() {
        // 密文由 KeyStoreCrypto 解密；解密失败（密钥丢失/数据损坏）视为未配置。
        return KeyStoreCrypto.decrypt(sp.getString(KEY_AI_API_KEY, null));
    }

    public void setAiApiKey(String key) {
        // 升级为 Android Keystore + AES/GCM 加密落盘，不再明文存储。
        sp.edit().putString(KEY_AI_API_KEY, KeyStoreCrypto.encrypt(key)).apply();
    }

    /** 用户自定义模型名（可空，为空则使用服务商默认模型）。 */
    public String getAiModel() {
        return sp.getString(KEY_AI_MODEL, null);
    }

    public void setAiModel(String model) {
        sp.edit().putString(KEY_AI_MODEL, model).apply();
    }

    /** AI 是否已完整配置（选择了服务商且填写了 API Key）。 */
    public boolean isAiConfigured() {
        return getAiProviderId() != null
                && getAiApiKey() != null
                && !getAiApiKey().trim().isEmpty();
    }

    // ---------- 默认扫描引擎（点击模式后的二选一偏好） ----------

    public static final String ENGINE_NORMAL = "normal";
    public static final String ENGINE_AI = "ai";

    /** 点击模式后默认使用的引擎（normal / ai）。 */
    public String getPreferredEngine() {
        return sp.getString(KEY_PREFERRED_ENGINE, ENGINE_NORMAL);
    }

    public void setPreferredEngine(String engine) {
        sp.edit().putString(KEY_PREFERRED_ENGINE, engine).apply();
    }

    // ---------- 外观主题（深色 / 浅色 / 跟随系统） ----------

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public int getThemeMode() {
        return sp.getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public void setThemeMode(int mode) {
        sp.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    // ---------- 文件放行名单（允许运行的路径） ----------

    /** 放行的文件路径集合（扫描时跳过、不再提示）。 */
    public Set<String> getFileAllowlist() {
        return new HashSet<>(Arrays.asList(
                sp.getString(KEY_FILE_ALLOWLIST, "").split(",")));
    }

    public void setFileAllowlist(Set<String> paths) {
        sp.edit().putString(KEY_FILE_ALLOWLIST, TextUtil.join(paths)).apply();
    }

    public void addToFileAllowlist(String path) {
        if (path == null || path.isEmpty()) return;
        Set<String> set = getFileAllowlist();
        set.add(path);
        setFileAllowlist(set);
    }

    public void removeFromFileAllowlist(String path) {
        Set<String> set = getFileAllowlist();
        set.remove(path);
        setFileAllowlist(set);
    }

    public boolean isFileAllowed(String path) {
        if (path == null || path.isEmpty()) return false;
        Set<String> set = getFileAllowlist();
        if (set.contains(path)) return true;
        // 目录级放行：路径位于某个已放行的目录之下
        for (String allowed : set) {
            if (path.startsWith(allowed + "/")) return true;
        }
        return false;
    }

    // ---------- 拦截模式（红色守护栏） ----------

    /** 拦截模式是否开启（开启后常驻置顶通知并集中记录被拦截的应用）。 */
    public boolean isBlockMode() {
        return sp.getBoolean(KEY_BLOCK_MODE, false);
    }

    public void setBlockMode(boolean on) {
        sp.edit().putBoolean(KEY_BLOCK_MODE, on).apply();
    }

    /** 被拦截应用包名集合。 */
    public Set<String> getBlockedApps() {
        return new HashSet<>(Arrays.asList(
                sp.getString(KEY_BLOCKED_APPS, "").split(",")));
    }

    public void addBlockedApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> set = getBlockedApps();
        set.add(pkg);
        setBlockedApps(set);
    }

    public void removeBlockedApp(String pkg) {
        Set<String> set = getBlockedApps();
        set.remove(pkg);
        setBlockedApps(set);
    }

    private void setBlockedApps(Set<String> pkgs) {
        sp.edit().putString(KEY_BLOCKED_APPS, TextUtil.join(pkgs)).apply();
    }

    // ---------- 病毒库 · Polar Region ----------

    /** 是否开启病毒库自动更新（默认开启）。 */
    public boolean getVirusAutoUpdate() {
        return sp.getBoolean(KEY_VIRUS_AUTO, true);
    }

    public void setVirusAutoUpdate(boolean on) {
        sp.edit().putBoolean(KEY_VIRUS_AUTO, on).apply();
    }

    // ---------- 应用更新下载源 ----------

    public static final String UPDATE_SOURCE_GITHUB = "github";
    public static final String UPDATE_SOURCE_MIRROR = "mirror";

    /** 更新 APK 的下载源（默认原 GitHub）。 */
    public String getUpdateSource() {
        return sp.getString(KEY_UPDATE_SOURCE, UPDATE_SOURCE_GITHUB);
    }

    public void setUpdateSource(String source) {
        sp.edit().putString(KEY_UPDATE_SOURCE, source).apply();
    }
}
