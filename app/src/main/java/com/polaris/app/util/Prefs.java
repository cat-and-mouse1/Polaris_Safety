package com.polaris.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private static final String KEY_AI_PROVIDERS_JSON = "ai_providers_json";  // 多服务商配置 JSON
    private static final String KEY_AI_MULTI_STRATEGY = "ai_multi_strategy";  // ensemble | fastest
    private static final String KEY_PREFERRED_ENGINE = "preferred_engine"; // normal | ai
    private static final String KEY_FILE_ALLOWLIST = "file_allowlist";     // 放行的文件路径
    private static final String KEY_THEME_MODE = "theme_mode";             // 外观主题
    private static final String KEY_THEME_COLOR_MODE = "theme_color_mode";  // 自定义主题色模式
    private static final String KEY_CUSTOM_THEME_COLOR = "custom_theme_color"; // 自定义主题色种子色
    private static final String KEY_BLOCK_MODE = "block_mode";             // 拦截模式开关
    private static final String KEY_BLOCKED_APPS = "blocked_apps";         // 被拦截应用（逗号分隔）
    private static final String KEY_VIRUS_AUTO = "virus_auto";             // 病毒库自动更新
    private static final String KEY_VIRUS_DB = "virus_db";                 // 病毒库选择：region | point
    private static final String KEY_ML_ENABLED = "ml_enabled";             // ML 引擎开关
    private static final String KEY_ML_MODEL = "ml_model";               // ML 模型选择：mh100k | mh1m
    private static final String KEY_UPDATE_SOURCE = "update_source";       // 更新下载源：github | mirror
    private static final String KEY_MONITORED_APPS = "monitored_apps";     // 应用监控名单（逗号分隔）
    private static final String KEY_BEHAVIOR_LOG = "behavior_log";         // 行为日志（JSON 数组，最新在前）
    private static final String KEY_TOTAL_SCAN_COUNT = "total_scan_count"; // 累计扫描次数
    private static final String KEY_LAST_SCAN_TOTAL = "last_scan_total";     // 上次扫描总数
    private static final String KEY_LAST_SCAN_SAFE = "last_scan_safe";       // 上次扫描安全数
    private static final String KEY_LAST_SCAN_SUSPICIOUS = "last_scan_suspicious"; // 上次扫描可疑数
    private static final String KEY_LAST_SCAN_MALICIOUS = "last_scan_malicious";   // 上次扫描恶意数
    private static final String KEY_TUTORIAL_DONE = "tutorial_done";

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

    /**
     * 当前守护模式是否启用深层扫描。
     * Normal 只做浅层；Accessibility / Shizuku / Root 均可做深层
     * （额外检测无障碍服务滥用、设备管理员滥用）。
     */
    public boolean isDeepScanMode() {
        int m = getActiveMode();
        return m == MODE_ACCESSIBILITY || m == MODE_SHIZUKU || m == MODE_ROOT;
    }

    // ---------- 动画测试模式（隐藏：设置 → 应用信息 → 连点版本号 5 次） ----------

    private static final String KEY_ANIM_TEST = "anim_test_mode";

    /**
     * 本次进程是否开启过动画测试模式。
     *
     * <p>持久化值只作记录，实际判定以本标记为准：进程被杀 / 应用被关闭再打开后标记重置为
     * false，动画测试模式自动退出，避免忘记关闭后长期处于测试节奏。
     */
    private static volatile boolean animTestSessionOn = false;

    /** 动画测试模式：扫描进度与图标旋转按固定节奏完整播放一遍，无视实际扫描速度。 */
    public boolean isAnimTestMode() {
        return animTestSessionOn;
    }

    public void setAnimTestMode(boolean on) {
        animTestSessionOn = on;
        sp.edit().putBoolean(KEY_ANIM_TEST, on).apply();
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
        return splitSet(sp.getString(KEY_MALICIOUS, ""));
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

    /** AI 是否已完整配置（至少有一个服务商配置了 API Key）。 */
    public boolean isAiConfigured() {
        return getEnabledProviders().size() > 0;
    }

    // ---------- 多 AI 服务商配置 ----------

    public static final String MULTI_STRATEGY_ENSEMBLE = "ensemble";  // 交叉检测：一个判恶意就是恶意，多方确认重点标注
    public static final String MULTI_STRATEGY_FASTEST = "fastest";    // 最快优先：选择连接最快的模型

    /** 获取多模型策略。 */
    public String getMultiModelStrategy() {
        return sp.getString(KEY_AI_MULTI_STRATEGY, MULTI_STRATEGY_ENSEMBLE);
    }

    public void setMultiModelStrategy(String strategy) {
        if (!MULTI_STRATEGY_ENSEMBLE.equals(strategy) && !MULTI_STRATEGY_FASTEST.equals(strategy)) {
            strategy = MULTI_STRATEGY_ENSEMBLE;
        }
        sp.edit().putString(KEY_AI_MULTI_STRATEGY, strategy).apply();
    }

    /** 启用的 AI 服务商配置列表。 */
    public List<AiProviderConfig> getEnabledProviders() {
        List<AiProviderConfig> list = new ArrayList<>();
        String json = sp.getString(KEY_AI_PROVIDERS_JSON, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                if (obj.optBoolean("enabled", true)) {
                    AiProviderConfig cfg = new AiProviderConfig(
                            obj.getString("id"),
                            obj.getString("name"),
                            obj.getString("badge"),
                            obj.getString("baseUrl"),
                            obj.getString("model"),
                            obj.getString("apiKey"),
                            obj.optBoolean("enabled", true)
                    );
                    if (obj.has("models")) {
                        org.json.JSONArray modelsArr = obj.getJSONArray("models");
                        for (int j = 0; j < modelsArr.length(); j++) {
                            cfg.models.add(ModelEntry.fromJson(modelsArr.getJSONObject(j)));
                        }
                    }
                    list.add(cfg);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 保存/更新单个服务商配置。 */
    public void saveProviderConfig(AiProviderConfig cfg) {
        List<AiProviderConfig> list = getAllProviders();
        list.removeIf(p -> p.id.equals(cfg.id));
        list.add(cfg);
        saveAllProviders(list);
    }

    /** 禁用某服务商（保留配置但标记为禁用）。 */
    public void disableProvider(String providerId) {
        List<AiProviderConfig> list = getAllProviders();
        for (AiProviderConfig p : list) {
            if (p.id.equals(providerId)) {
                p.enabled = false;
                break;
            }
        }
        saveAllProviders(list);
    }

    /** 启用某服务商。 */
    public void enableProvider(String providerId) {
        List<AiProviderConfig> list = getAllProviders();
        for (AiProviderConfig p : list) {
            if (p.id.equals(providerId)) {
                p.enabled = true;
                break;
            }
        }
        saveAllProviders(list);
    }

    /** 删除服务商配置。 */
    public void removeProvider(String providerId) {
        List<AiProviderConfig> list = getAllProviders();
        list.removeIf(p -> p.id.equals(providerId));
        saveAllProviders(list);
    }

    /** 获取所有配置（含禁用的）。 */
    public List<AiProviderConfig> getAllProviders() {
        List<AiProviderConfig> list = new ArrayList<>();
        String json = sp.getString(KEY_AI_PROVIDERS_JSON, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                AiProviderConfig cfg = new AiProviderConfig(
                        obj.getString("id"),
                        obj.getString("name"),
                        obj.getString("badge"),
                        obj.getString("baseUrl"),
                        obj.getString("model"),
                        obj.getString("apiKey"),
                        obj.optBoolean("enabled", true)
                );
                // 解析模型列表
                if (obj.has("models")) {
                    org.json.JSONArray modelsArr = obj.getJSONArray("models");
                    for (int j = 0; j < modelsArr.length(); j++) {
                        cfg.models.add(ModelEntry.fromJson(modelsArr.getJSONObject(j)));
                    }
                }
                list.add(cfg);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void saveAllProviders(List<AiProviderConfig> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (AiProviderConfig p : list) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id", p.id);
                obj.put("name", p.name);
                obj.put("badge", p.badge);
                obj.put("baseUrl", p.baseUrl);
                obj.put("model", p.model);
                obj.put("apiKey", p.apiKey);
                obj.put("enabled", p.enabled);
                // 保存模型列表
                if (p.models != null && !p.models.isEmpty()) {
                    org.json.JSONArray modelsArr = new org.json.JSONArray();
                    for (ModelEntry m : p.models) {
                        modelsArr.put(m.toJson());
                    }
                    obj.put("models", modelsArr);
                }
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_AI_PROVIDERS_JSON, arr.toString()).apply();
    }

    /** AI 服务商配置数据类。 */
    public static class AiProviderConfig {
        public String id;
        public String name;
        public String badge;
        public String baseUrl;
        public String model;
        public String apiKey;
        public boolean enabled;
        public List<ModelEntry> models = new ArrayList<>();

        public AiProviderConfig(String id, String name, String badge, String baseUrl, String model, String apiKey, boolean enabled) {
            this.id = id;
            this.name = name;
            this.badge = badge;
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
            this.enabled = enabled;
        }

        /** 获取已启用的模型 ID 列表。若 models 为空则返回单个 model 字段。 */
        public List<String> getEnabledModelIds() {
            List<String> result = new ArrayList<>();
            if (models != null && !models.isEmpty()) {
                for (ModelEntry m : models) {
                    if (m.enabled) result.add(m.id);
                }
            }
            if (result.isEmpty() && model != null && !model.isEmpty()) {
                result.add(model);
            }
            return result;
        }

        /** 获取已启用模型数量。 */
        public int getEnabledModelCount() {
            if (models == null || models.isEmpty()) return (model != null && !model.isEmpty()) ? 1 : 0;
            int count = 0;
            for (ModelEntry m : models) {
                if (m.enabled) count++;
            }
            return count;
        }

        /** 获取总模型数量。 */
        public int getTotalModelCount() {
            if (models == null || models.isEmpty()) return (model != null && !model.isEmpty()) ? 1 : 0;
            return models.size();
        }
    }

    /** 模型条目（模型 ID + 启用状态）。 */
    public static class ModelEntry {
        public String id;
        public boolean enabled;

        public ModelEntry(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        public org.json.JSONObject toJson() throws Exception {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("id", id);
            obj.put("enabled", enabled);
            return obj;
        }

        public static ModelEntry fromJson(org.json.JSONObject obj) throws Exception {
            return new ModelEntry(obj.getString("id"), obj.optBoolean("enabled", true));
        }
    }

    // ---------- 自定义 AI 服务商 ----------
    private static final String KEY_CUSTOM_PROVIDER_NAME = "custom_provider_name";
    private static final String KEY_CUSTOM_PROVIDER_URL = "custom_provider_url";
    private static final String KEY_CUSTOM_PROVIDER_MODEL = "custom_provider_model";
    private static final String KEY_CUSTOM_PROVIDER_BADGE = "custom_provider_badge";

    /** 保存自定义服务商配置。 */
    public void setCustomProvider(String name, String url, String model, String badge) {
        sp.edit()
                .putString(KEY_CUSTOM_PROVIDER_NAME, name)
                .putString(KEY_CUSTOM_PROVIDER_URL, url)
                .putString(KEY_CUSTOM_PROVIDER_MODEL, model)
                .putString(KEY_CUSTOM_PROVIDER_BADGE, badge)
                .apply();
    }

    public String getCustomProviderName() {
        return sp.getString(KEY_CUSTOM_PROVIDER_NAME, null);
    }

    public String getCustomProviderUrl() {
        return sp.getString(KEY_CUSTOM_PROVIDER_URL, null);
    }

    public String getCustomProviderModel() {
        return sp.getString(KEY_CUSTOM_PROVIDER_MODEL, null);
    }

    public String getCustomProviderBadge() {
        return sp.getString(KEY_CUSTOM_PROVIDER_BADGE, null);
    }

    public boolean hasCustomProvider() {
        return getCustomProviderName() != null;
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

    // ---------- 自定义主题色（默认 / 系统莫奈 / 自定义种子色） ----------

    public static final int THEME_COLOR_DEFAULT = 0; // 应用内置主题（md_theme）
    public static final int THEME_COLOR_SYSTEM = 1;  // 系统莫奈（墙纸动态取色）
    public static final int THEME_COLOR_CUSTOM = 2;  // 自定义种子色

    public int getThemeColorMode() {
        return sp.getInt(KEY_THEME_COLOR_MODE, THEME_COLOR_DEFAULT);
    }

    public void setThemeColorMode(int mode) {
        sp.edit().putInt(KEY_THEME_COLOR_MODE, mode).apply();
    }

    /** 自定义种子色（ARGB），-1 表示未设置。 */
    public int getCustomThemeColor() {
        return sp.getInt(KEY_CUSTOM_THEME_COLOR, -1);
    }

    public void setCustomThemeColor(int color) {
        sp.edit().putInt(KEY_CUSTOM_THEME_COLOR, color).apply();
    }

    // ---------- 文件放行名单（允许运行的路径） ----------

    /** 放行的文件路径集合（扫描时跳过、不再提示）。 */
    public Set<String> getFileAllowlist() {
        return splitSet(sp.getString(KEY_FILE_ALLOWLIST, ""));
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
        return splitSet(sp.getString(KEY_BLOCKED_APPS, ""));
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

    // ---------- 病毒库选择 ----------

    /** 病毒库选择：region (Polar Region) | point (Polar Point)。默认 point（内置种子为 Polar Point）。 */
    public String getVirusDb() {
        return sp.getString(KEY_VIRUS_DB, "point");
    }

    public void setVirusDb(String db) {
        if (!"region".equals(db) && !"point".equals(db)) {
            db = "point";
        }
        sp.edit().putString(KEY_VIRUS_DB, db).apply();
    }

    // ---------- ML 引擎 ----------

    /** ML 引擎是否开启（默认开启）。 */
    public boolean isMlEnabled() {
        return sp.getBoolean(KEY_ML_ENABLED, true);
    }

    public void setMlEnabled(boolean on) {
        sp.edit().putBoolean(KEY_ML_ENABLED, on).apply();
    }

    /** ML 模型选择：mh100k (默认, Logistic Regression) | mh1m (XGBoost)。 */
    public String getMlModel() {
        return sp.getString(KEY_ML_MODEL, "mh100k");
    }

    public void setMlModel(String model) {
        if (!"mh100k".equals(model) && !"mh1m".equals(model)) {
            model = "mh100k";
        }
        sp.edit().putString(KEY_ML_MODEL, model).apply();
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

    // ---------- 应用监控名单 ----------

    /** 被用户选择监控的应用包名集合。 */
    public Set<String> getMonitoredApps() {
        return splitSet(sp.getString(KEY_MONITORED_APPS, ""));
    }

    public void setMonitoredApps(Set<String> pkgs) {
        sp.edit().putString(KEY_MONITORED_APPS, TextUtil.join(pkgs)).apply();
    }

    public void addMonitoredApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> set = getMonitoredApps();
        set.add(pkg);
        setMonitoredApps(set);
    }

    public void removeMonitoredApp(String pkg) {
        Set<String> set = getMonitoredApps();
        set.remove(pkg);
        setMonitoredApps(set);
    }

    // ---------- 行为日志（防护过程记录） ----------

    /**
     * 追加一条行为日志（JSON 文本，最新在前），超出上限自动截断旧记录。
     * 供「行为」板块展示，记录强制退出 / 拦截 / 隔离等防护过程。
     */
    public void appendBehaviorLog(String entryJson) {
        String raw = sp.getString(KEY_BEHAVIOR_LOG, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            arr.put(0, new org.json.JSONObject(entryJson));
            while (arr.length() > 300) {
                arr.remove(arr.length() - 1);
            }
            sp.edit().putString(KEY_BEHAVIOR_LOG, arr.toString()).apply();
        } catch (Exception ignored) {
            sp.edit().putString(KEY_BEHAVIOR_LOG, "[" + entryJson + "]").apply();
        }
    }

    /** 读取行为日志 JSON 数组（最新在前）。 */
    public org.json.JSONArray getBehaviorLog() {
        try {
            return new org.json.JSONArray(sp.getString(KEY_BEHAVIOR_LOG, "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    public void clearBehaviorLog() {
        sp.edit().putString(KEY_BEHAVIOR_LOG, "[]").apply();
    }

    // ---------- 累计扫描次数 ----------

    public int getTotalScanCount() {
        return sp.getInt(KEY_TOTAL_SCAN_COUNT, 0);
    }

    public void incrementScanCount() {
        sp.edit().putInt(KEY_TOTAL_SCAN_COUNT, getTotalScanCount() + 1).apply();
    }

    // ---------- 教程状态 ----------

    /** 教程是否已完成。同时检查标记文件，防止云备份恢复旧数据。 */
    public boolean isTutorialDone(Context context) {
        boolean spDone = sp.getBoolean(KEY_TUTORIAL_DONE, false);
        if (spDone) {
            // 二次验证：标记文件不存在则视为新安装
            java.io.File marker = new java.io.File(context.getFilesDir(), ".tutorial_done");
            if (!marker.exists()) {
                sp.edit().putBoolean(KEY_TUTORIAL_DONE, false).commit();
                return false;
            }
        }
        return spDone;
    }

    /** 标记教程完成状态。 */
    public void setTutorialDone(Context context, boolean done) {
        sp.edit().putBoolean(KEY_TUTORIAL_DONE, done).commit();
        java.io.File marker = new java.io.File(context.getFilesDir(), ".tutorial_done");
        try {
            if (done) {
                marker.createNewFile();
            } else {
                marker.delete();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    // ---------- 上次扫描结果摘要 ----------

    public void saveLastScanSummary(int total, int safe, int suspicious, int malicious) {
        sp.edit()
                .putInt(KEY_LAST_SCAN_TOTAL, total)
                .putInt(KEY_LAST_SCAN_SAFE, safe)
                .putInt(KEY_LAST_SCAN_SUSPICIOUS, suspicious)
                .putInt(KEY_LAST_SCAN_MALICIOUS, malicious)
                .apply();
    }

    public int getLastScanTotal() { return sp.getInt(KEY_LAST_SCAN_TOTAL, -1); }
    public int getLastScanSafe() { return sp.getInt(KEY_LAST_SCAN_SAFE, 0); }
    public int getLastScanSuspicious() { return sp.getInt(KEY_LAST_SCAN_SUSPICIOUS, 0); }
    public int getLastScanMalicious() { return sp.getInt(KEY_LAST_SCAN_MALICIOUS, 0); }

    /** 逗号分隔字符串 -> 集合（过滤空值，避免空字符串被误计为一项）。 */
    private static Set<String> splitSet(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.isEmpty()) return set;
        for (String s : raw.split(",")) {
            if (s != null && !s.isEmpty()) set.add(s);
        }
        return set;
    }
}
