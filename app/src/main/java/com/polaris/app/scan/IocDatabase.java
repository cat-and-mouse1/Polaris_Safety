package com.polaris.app.scan;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polar Region 开源威胁情报（IOC）病毒库。
 *
 * 数据来源：abuse.ch MalwareBazaar 自动拉取 + 社区维护清单（GitHub raw 托管）。
 * 与内置 {@code KNOWN_MALWARE} 硬编码库、AI 判定三路融合，取最高风险。
 *
 * 匹配优先级：SHA-256 精确匹配（优先） → 包名匹配（重打包/改名兜底）。
 * 体积：纯哈希/包名清单，内存 Map 索引 O(1) 查询。
 */
public final class IocDatabase {

    private static final String TAG = "IocDatabase";

    /** 我们托管的 IOC 分发地址（GitHub raw，公开可读、零服务器成本）。 */
    public static final String BASE_URL =
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json";
    private static final String SIG_URL = BASE_URL + ".sig";

    private static final String ASSET_NAME = "iodb_seed.json";
    private static final String CACHE_NAME = "iodb.json";

    /** 种子库内置密钥为 0；上线前应由服务端用同款 HMAC-SHA256 对 iodb.json 签名。 */
    private static final String HMAC_KEY = "";

    public interface RefreshCallback {
        void onUpdated(int newVersion);
        void onError(String message);
    }

    /** 单条 IOC 记录。 */
    public static class IocEntry {
        public String pkg;
        public String sha256;
        public String family;
        public String type;
        public String severity;   // low | medium | high | critical
        public String desc;
        public String[] tags = new String[0];
    }

    private static volatile IocDatabase sInstance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int dbVersion = 0;
    private String updatedAt = "";
    private String source = "";
    private final Map<String, IocEntry> byHash = new HashMap<>();
    private final Map<String, IocEntry> byPkg = new HashMap<>();

    private IocDatabase(Context context) {
        this.appContext = context.getApplicationContext();
        load();
    }

    public static IocDatabase getInstance(Context context) {
        if (sInstance == null) {
            synchronized (IocDatabase.class) {
                if (sInstance == null) {
                    sInstance = new IocDatabase(context);
                }
            }
        }
        return sInstance;
    }

    // ---------- 加载 ----------

    private void load() {
        // 1) 优先加载运行时缓存（可能已被 refresh 更新）
        File cache = new File(appContext.getFilesDir(), CACHE_NAME);
        if (cache.exists() && parse(readFile(cache))) {
            Log.i(TAG, "Loaded cached IOC db v" + dbVersion);
            return;
        }
        // 2) 回退内置种子库（随 APK 发布，永不过期兜底）
        try (InputStream in = appContext.getAssets().open(ASSET_NAME)) {
            if (parse(readStream(in))) {
                Log.i(TAG, "Loaded seed IOC db v" + dbVersion);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load seed IOC db", e);
        }
    }

    private boolean parse(String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            dbVersion = root.optInt("db_version", 0);
            updatedAt = root.optString("updated_at", "");
            source = root.optString("source", "");
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return false;
            byHash.clear();
            byPkg.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                IocEntry e = new IocEntry();
                e.pkg = o.optString("pkg", "");
                e.sha256 = o.optString("sha256", "");
                e.family = o.optString("family", "");
                e.type = o.optString("type", "");
                e.severity = o.optString("severity", "medium");
                e.desc = o.optString("desc", "");
                JSONArray t = o.optJSONArray("tags");
                if (t != null) {
                    e.tags = new String[t.length()];
                    for (int j = 0; j < t.length(); j++) e.tags[j] = t.getString(j);
                }
                if (!e.sha256.isEmpty()) byHash.put(e.sha256.toLowerCase(java.util.Locale.US), e);
                if (!e.pkg.isEmpty()) byPkg.put(e.pkg, e);
            }
            return true;
        } catch (JSONException e) {
            Log.w(TAG, "Bad IOC json", e);
            return false;
        }
    }

    // ---------- 查询 ----------

    public IocEntry matchByHash(String sha256) {
        if (sha256 == null) return null;
        return byHash.get(sha256.toLowerCase(java.util.Locale.US));
    }

    public IocEntry matchByPkg(String pkg) {
        if (pkg == null) return null;
        return byPkg.get(pkg);
    }

    public int getDbVersion() { return dbVersion; }
    public String getUpdatedAt() { return updatedAt.isEmpty() ? "—" : updatedAt; }
    public String getSource() { return source; }
    public int size() { return byPkg.size(); }

    /** 严重度 → 风险加分（与 MalwareScanner 的硬编码库 60 分对齐）。 */
    public static int severityScore(String severity) {
        if ("critical".equals(severity)) return 70;
        if ("high".equals(severity)) return 60;
        if ("medium".equals(severity)) return 35;
        if ("low".equals(severity)) return 15;
        return 30;
    }

    // ---------- 更新（网络拉取 + 签名校验 + 落盘） ----------

    public void refresh(RefreshCallback cb) {
        executor.execute(() -> {
            try {
                String json = download(BASE_URL);
                if (json == null || json.isEmpty()) {
                    cb.onError("空响应");
                    return;
                }
                // 签名校验（骨架）：若服务端提供 .sig 且配置了密钥则严格校验，
                // 否则接受（种子库/自建清单尚未签名，属预期内）。
                String sig = download(SIG_URL);
                if (sig != null && !sig.isEmpty() && !HMAC_KEY.isEmpty()) {
                    if (!verifyHmac(json, sig, HMAC_KEY)) {
                        cb.onError("签名校验失败，已丢弃本次更新");
                        return;
                    }
                }
                int newVersion = 0;
                try {
                    newVersion = new JSONObject(json).optInt("db_version", 0);
                } catch (JSONException ignored) {
                }
                // 写入运行时缓存并热重载
                if (writeCache(json) && parse(json)) {
                    Log.i(TAG, "IOC db refreshed to v" + dbVersion);
                    cb.onUpdated(newVersion > 0 ? newVersion : dbVersion);
                } else {
                    cb.onError("解析失败");
                }
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        });
    }

    private static String download(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            // GitHub raw 对不存在的 .sig 返回 404，属正常
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean writeCache(String json) {
        File cache = new File(appContext.getFilesDir(), CACHE_NAME);
        try (FileOutputStream out = new FileOutputStream(cache)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Write IOC cache failed", e);
            return false;
        }
    }

    private static boolean verifyHmac(String data, String sigHex, String key) {
        // 占位实现：真实部署时改用 HMAC-SHA256(data, key) 与 sigHex 比对。
        // 此处仅做长度基本校验，避免无密钥时误判。
        return sigHex.length() == 64;
    }

    // ---------- IO 小工具 ----------

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return readStream(in);
        } catch (IOException e) {
            return null;
        }
    }
}
