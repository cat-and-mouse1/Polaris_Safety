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
import java.io.OutputStream;
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
 * Polaris 开源威胁情报（IOC）病毒库（region / point 共用，内置种子来自 Polar Region v14 过滤出的已命名家族威胁，随 APK 打包、离线可用）。
 *
 * 数据来源：abuse.ch MalwareBazaar + URLhaus + PyPI/NPM 恶意包数据集 + 社区维护清单。
 * 与内置 {@code KNOWN_MALWARE} 硬编码库、AI 判定三路融合，取最高风险。
 *
 * 匹配优先级：SHA-256 精确匹配（优先） → 包名匹配（重打包/改名兜底） → URL 匹配（恶意分发链接）。
 * 体积：纯哈希/包名/URL 清单，内存 Map 索引 O(1) 查询。
 */
public final class IocDatabase {

    private static final String TAG = "IocDatabase";

    /**
     * 我们托管的 IOC 分发地址（GitHub，公开可读、零服务器成本）。
     * 注意：region 是 Git LFS 对象，raw 域名只会返回 ~134 字节的 LFS 指针文本，
     * 必须走 media.githubusercontent.com/media/ 才能取到真实内容；
     * 而 point 是普通 blob，media 域名会 404，只能用 raw。
     */
    public static final String BASE_URL_REGION =
            "https://media.githubusercontent.com/media/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.json";
    public static final String BASE_URL_POINT =
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-point/iodb.json";
    private static final String SIG_URL_REGION = BASE_URL_REGION + ".sig";
    private static final String SIG_URL_POINT = BASE_URL_POINT + ".sig";

    private static final String ASSET_NAME = "iodb_seed.json";
    private static final String CACHE_NAME = "iodb.json";
    private static final String CACHE_NAME_POINT = "iodb_point.json";

    /** 种子库内置密钥为 0；上线前应由服务端用同款 HMAC-SHA256 对 iodb.json 签名。 */
    private static final String HMAC_KEY = "";

    /** 连接超时（毫秒）：握手阶段等待上限，超时即提示用户。 */
    private static final int CONNECT_TIMEOUT = 15000;
    /** 读取超时（毫秒）：流传输中连续无数据的时间上限，超时即视为中断并提示用户。 */
    private static final int READ_TIMEOUT = 30000;

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
        public String url;        // 恶意分发 URL（来自 URLhaus 等数据源）
    }

    private static volatile IocDatabase sInstanceRegion;
    private static volatile IocDatabase sInstancePoint;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String dbType; // "region" or "point"

    private int dbVersion = 0;
    private String updatedAt = "";
    private String source = "";
    private final Map<String, IocEntry> byHash = new HashMap<>();
    private final Map<String, IocEntry> byPkg = new HashMap<>();
    private final Map<String, IocEntry> byUrl = new HashMap<>();

    private IocDatabase(Context context, String dbType) {
        this.appContext = context.getApplicationContext();
        this.dbType = dbType;
        load();
    }

    /** 获取当前选择的病毒库实例。 */
    public static IocDatabase getInstance(Context context) {
        String type = new com.polaris.app.util.Prefs(context).getVirusDb();
        return getInstance(context, type);
    }

    /** 获取指定类型的病毒库实例。 */
    public static IocDatabase getInstance(Context context, String type) {
        if ("point".equals(type)) {
            if (sInstancePoint == null) {
                synchronized (IocDatabase.class) {
                    if (sInstancePoint == null) {
                        sInstancePoint = new IocDatabase(context, "point");
                    }
                }
            }
            return sInstancePoint;
        } else {
            if (sInstanceRegion == null) {
                synchronized (IocDatabase.class) {
                    if (sInstanceRegion == null) {
                        sInstanceRegion = new IocDatabase(context, "region");
                    }
                }
            }
            return sInstanceRegion;
        }
    }

    /** 切换病毒库时重新加载。 */
    public static void reload(Context context) {
        String type = new com.polaris.app.util.Prefs(context).getVirusDb();
        if ("point".equals(type)) {
            if (sInstancePoint != null) sInstancePoint.load();
        } else {
            if (sInstanceRegion != null) sInstanceRegion.load();
        }
    }

    /** 获取当前库类型。 */
    public String getDbType() { return dbType; }

    /** 是否为全量库。 */
    public boolean isFullDb() { return "point".equals(dbType); }

    // ---------- 加载 ----------

    private void load() {
        String cacheName = "point".equals(dbType) ? CACHE_NAME_POINT : CACHE_NAME;
        String assetSeed = ASSET_NAME; // 内置种子库（assets/iodb_seed.json）：内容来自 Polar Region v14 过滤，轻量可离线；point 与 region 共用

        // 1) 优先加载运行时缓存（可能已被 refresh 更新）
        File cache = new File(appContext.getFilesDir(), cacheName);
        if (cache.exists() && parse(readFile(cache))) {
            Log.i(TAG, "Loaded cached IOC db [" + dbType + "] v" + dbVersion);
            return;
        }
        // 2) 回退内置种子库（assets/iodb_seed.json，内容现为 Polar Point）
        try (InputStream in = appContext.getAssets().open(assetSeed)) {
            if (parse(readStream(in))) {
                Log.i(TAG, "Loaded seed IOC db [" + dbType + "] v" + dbVersion);
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
            byUrl.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                IocEntry e = new IocEntry();
                e.pkg = o.optString("pkg", "");
                e.sha256 = o.optString("sha256", "");
                e.family = o.optString("family", "");
                e.type = o.optString("type", "");
                e.severity = o.optString("severity", "medium");
                e.desc = o.optString("desc", "");
                e.url = o.optString("url", "");
                JSONArray t = o.optJSONArray("tags");
                if (t != null) {
                    e.tags = new String[t.length()];
                    for (int j = 0; j < t.length(); j++) e.tags[j] = t.getString(j);
                }
                if (!e.sha256.isEmpty()) byHash.put(e.sha256.toLowerCase(java.util.Locale.US), e);
                if (!e.pkg.isEmpty()) byPkg.put(e.pkg, e);
                if (!e.url.isEmpty()) byUrl.put(e.url.toLowerCase(java.util.Locale.US), e);
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

    public IocEntry matchByUrl(String url) {
        if (url == null) return null;
        return byUrl.get(url.toLowerCase(java.util.Locale.US));
    }

    public int getDbVersion() { return dbVersion; }

    /**
     * 只读取库文件头部的 db_version（不解析全部条目，避免仅为了显示版本而加载 357MB 全量库）。
     * point 无缓存时读内置种子。
     * @return 版本号；region 无本地缓存时返回 -1（未下载）。
     */
    public static int peekDbVersion(Context context, String type) {
        String cacheName = "point".equals(type) ? CACHE_NAME_POINT : CACHE_NAME;
        File cache = new File(context.getFilesDir(), cacheName);
        InputStream in = null;
        try {
            if (cache.exists()) {
                in = new FileInputStream(cache);
            } else if ("point".equals(type)) {
                in = context.getAssets().open(ASSET_NAME); // 内置种子现为 Polar Point
            } else {
                // region 无完整缓存：若有部分下载的 .part（从文件头开始下载），头部同样含 db_version
                File part = new File(context.getFilesDir(), cacheName + ".part");
                if (!part.exists()) return -1;
                in = new FileInputStream(part);
            }
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return -1;
            String head = new String(buf, 0, n, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"db_version\"\\s*:\\s*(\\d+)").matcher(head);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** 部分下载（iodb.json.part）的进度信息，供设置页显示「已下载 x%」。 */
    public static class PartialDownload {
        public final long downloaded;
        /** 远端总字节数；0 表示未知（旧版本下载时未记录）。 */
        public final long total;

        PartialDownload(long downloaded, long total) {
            this.downloaded = downloaded;
            this.total = total;
        }

        /** 已下载百分比；total 未知时返回 -1。 */
        public int percent() {
            return (total > 0 && downloaded <= total)
                    ? (int) (downloaded * 100 / total) : -1;
        }
    }

    /**
     * 查询某库的部分下载进度（iodb.json.part + 总大小记录 iodb.json.part.meta）。
     * 无部分下载时返回 null。
     */
    public static PartialDownload peekPartialDownload(Context context, String type) {
        String cacheName = "point".equals(type) ? CACHE_NAME_POINT : CACHE_NAME;
        File part = new File(context.getFilesDir(), cacheName + ".part");
        if (!part.exists()) return null;
        long total = 0;
        File meta = new File(context.getFilesDir(), cacheName + ".part.meta");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(meta), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line != null) total = Long.parseLong(line.trim());
        } catch (Exception ignored) {
        }
        return new PartialDownload(part.length(), total);
    }

    /** 远端版本号回调（在后台线程回调，UI 需自行切换线程）。 */
    public interface RemoteVersionCallback {
        void onVersion(int version);
    }

    /** 远端版本探测结果缓存（进程内）：同一类型只发一次请求。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> REMOTE_VER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 异步探测远端库文件的 db_version（Range 请求只取头部 512 字节，不下载全量库）。
     * 用于「未下载」状态下在设置页显示真实最新版本；成功结果进程内缓存，失败返回 -1。
     */
    public static void peekRemoteVersion(String type, RemoteVersionCallback cb) {
        Integer cached = REMOTE_VER_CACHE.get(type);
        if (cached != null) {
            cb.onVersion(cached);
            return;
        }
        PROBE_EXECUTOR.execute(() -> {
            int v = -1;
            HttpURLConnection conn = null;
            try {
                String urlStr = "point".equals(type) ? BASE_URL_POINT : BASE_URL_REGION;
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Range", "bytes=0-511");
                int code = conn.getResponseCode();
                if (code == 200 || code == 206) {
                    try (InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[512];
                        int n = 0, r;
                        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1) n += r;
                        String head = new String(buf, 0, n, StandardCharsets.UTF_8);
                        // 防御：拿到 Git LFS 指针（分发地址误用 raw 域名）时无版本可解析
                        if (!head.startsWith("version https://git-lfs.github.com/spec")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("\"db_version\"\\s*:\\s*(\\d+)").matcher(head);
                            if (m.find()) v = Integer.parseInt(m.group(1));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (v > 0) REMOTE_VER_CACHE.put(type, v);
            cb.onVersion(v);
        });
    }

    /** 记录部分下载的远端总大小（sidecar：iodb.json.part.meta，一行数字）。 */
    private static void writePartMeta(File part, long total) {
        if (total <= 0) return;
        File meta = new File(part.getParentFile(), part.getName() + ".meta");
        try (OutputStream os = new FileOutputStream(meta)) {
            os.write(String.valueOf(total).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** 删除部分下载的总大小记录（part 被清理或转正时调用）。 */
    private static void deletePartMeta(File part) {
        new File(part.getParentFile(), part.getName() + ".meta").delete();
    }
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

    public interface ProgressCallback {
        /** 下载进度回调（工作线程，调用方需自行切回 UI 线程）。 */
        void onProgress(long downloaded, long total);
    }

    /** 可取消刷新回调：在基础回调之上增加「用户主动取消」这一分支。 */
    public interface CancellableRefreshCallback extends RefreshCallback {
        void onCancelled();
    }

    /** 下载令牌：调用方持有，可在 UI 线程调用 cancel() 中断后台下载。 */
    public static class DownloadToken {
        private volatile boolean cancelled = false;
        private volatile HttpURLConnection conn;
        private volatile InputStream stream;

        /**
         * 置取消标志，并主动关闭正在读取的输入流 + 断开连接，以立即打断阻塞中的 read()。
         * 仅 disconnect() 在部分 Android 实现上不会立刻中断阻塞的 in.read()，导致要等
         * 读取超时（最长 READ_TIMEOUT）才会检查取消标志，表现为「点中断没反应」。
         */
        public void cancel() {
            cancelled = true;
            InputStream s = stream;
            HttpURLConnection c = conn;
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            try { if (c != null) c.disconnect(); } catch (Exception ignored) { }
        }

        public boolean isCancelled() { return cancelled; }

        void attach(HttpURLConnection c) { conn = c; }
        void attachStream(InputStream s) { stream = s; }
        void detach() { conn = null; stream = null; }
    }

    /** 下载被取消时由内部抛出的信号，不向外暴露。 */
    private static class CancelledException extends IOException {
        CancelledException() { super("cancelled"); }
    }

    /** 兼容旧调用：无进度回调、不可取消。 */
    public void refresh(RefreshCallback cb) {
        doRefresh(cb, null, null);
    }

    /** 兼容旧调用：带进度回调、不可取消。Settings / Worker 走此路径（自动断点续传）。 */
    public void refresh(RefreshCallback cb, ProgressCallback pc) {
        doRefresh(cb, pc, null);
    }

    /**
     * 欢迎页专用：带进度、可随时取消。返回 DownloadToken 供 UI 线程中断。
     * 取消或出错时均保留 .part 续传文件，用户稍后可在设置中继续补全。
     */
    public DownloadToken refreshResumable(RefreshCallback cb, ProgressCallback pc) {
        DownloadToken token = new DownloadToken();
        doRefresh(cb, pc, token);
        return token;
    }

    /**
     * 拉取最新病毒库并落盘缓存（支持断点续传 + 可取消 + 进度回调 + 超时提示）。
     * 大文件（region 357MB）写入 iodb.json.part；若该文件已存在则从断点用 HTTP Range
     * 续传，完成后原子 rename 为 iodb.json。取消/超时/网络出错均保留 .part 以便后续补全。
     */
    private void doRefresh(RefreshCallback cb, ProgressCallback pc, DownloadToken token) {
        String baseUrl = "point".equals(dbType) ? BASE_URL_POINT : BASE_URL_REGION;
        String sigUrl = "point".equals(dbType) ? SIG_URL_POINT : SIG_URL_REGION;
        String cacheName = "point".equals(dbType) ? CACHE_NAME_POINT : CACHE_NAME;
        File part = new File(appContext.getFilesDir(), cacheName + ".part");

        executor.execute(() -> {
            try {
                long total = downloadToFileResume(baseUrl, part, pc, token);
                if (token != null && token.isCancelled()) {
                    silentCancel(cb);
                    return;
                }
                if (total < 0) {
                    cb.onError("下载失败（HTTP 404，文件不存在）");
                    return;
                }
                String json = readFile(part);
                if (json == null || json.isEmpty()) {
                    cb.onError("空响应");
                    return;
                }
                // 防御：若拿到的是 Git LFS 指针 (~130 字节) 而非真实内容（分发地址误用 raw 域名时），直接报错
                if (json.startsWith("version https://git-lfs.github.com/spec")) {
                    part.delete();
                    deletePartMeta(part);
                    cb.onError("下载到的是 Git LFS 指针，非病毒库内容（分发地址需用 media 域名）");
                    return;
                }
                // 签名校验（骨架）：若服务端提供 .sig 且配置了密钥则严格校验，
                // 否则接受（种子库/自建清单尚未签名，属预期内）。
                String sig = download(sigUrl);
                if (sig != null && !sig.isEmpty() && !HMAC_KEY.isEmpty()) {
                    if (!verifyHmac(json, sig, HMAC_KEY)) {
                        cb.onError("签名校验失败，已丢弃本次更新");
                        part.delete();
                        return;
                    }
                }
                int newVersion = 0;
                try {
                    newVersion = new JSONObject(json).optInt("db_version", 0);
                } catch (JSONException ignored) {
                }
                // 原子替换缓存并热重载
                if (part.renameTo(new File(appContext.getFilesDir(), cacheName)) && parse(json)) {
                    deletePartMeta(part); // 下载完成，总大小记录不再需要
                    Log.i(TAG, "IOC db [" + dbType + "] refreshed to v" + dbVersion);
                    cb.onUpdated(newVersion > 0 ? newVersion : dbVersion);
                } else {
                    // 解析失败：本地 .part 可能已损坏，删除以便下次整文件重下
                    part.delete();
                    deletePartMeta(part);
                    cb.onError("解析失败（已清除损坏的临时文件）");
                }
            } catch (CancelledException ce) {
                silentCancel(cb);
            } catch (Exception e) {
                if (token != null && token.isCancelled()) {
                    // 取消导致的连接中断（disconnect）也归入取消分支
                    silentCancel(cb);
                } else {
                    // 网络/超时等异常：保留 .part 供后续断点续传补全
                    cb.onError(e.getMessage());
                }
            }
        });
    }

    private void silentCancel(RefreshCallback cb) {
        if (cb instanceof CancellableRefreshCallback) {
            ((CancellableRefreshCallback) cb).onCancelled();
        } else {
            cb.onError("已取消下载");
        }
    }

    /** 流式下载到 part 文件并支持断点续传，返回总字节数（无 Content-Length 则为 0；404 返回 -1）。 */
    private long downloadToFileResume(String urlStr, File part, ProgressCallback pc, DownloadToken token) throws IOException {
        return downloadToFileResume(urlStr, part, pc, token, false);
    }

    /** retried=true 表示已因 HTTP 416 重试过一次，避免死循环。 */
    private long downloadToFileResume(String urlStr, File part, ProgressCallback pc, DownloadToken token,
                                     boolean retried) throws IOException {
        long existing = part.exists() ? part.length() : 0;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT); // 读取停滞超时 → 抛 SocketTimeoutException，触发提示
            conn.setRequestProperty("Accept", "application/json");
            if (token != null) token.attach(conn);
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = conn.getResponseCode();
            if (code == 404) return -1;
            if (code == 416) {
                // 本地 .part 已完整或已过期（Range 起点 >= 远端大小）：丢弃后从头整下（仅重试一次）
                conn.disconnect();
                part.delete();
                deletePartMeta(part);
                if (retried) throw new IOException("续传失败：本地临时文件与远端不一致");
                return downloadToFileResume(urlStr, part, pc, token, true);
            }
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            final boolean resume = (code == 206);
            final long contentLen = conn.getContentLengthLong();
            final long total = resume ? (existing + contentLen) : contentLen;
            long downloaded = resume ? existing : 0;
            writePartMeta(part, total); // 记录远端总大小，供设置页计算「已下载 x%」
            try (InputStream in = conn.getInputStream();
                 OutputStream os = new FileOutputStream(part, resume)) {
                if (token != null) token.attachStream(in);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (token != null && token.isCancelled()) {
                        throw new CancelledException();
                    }
                    os.write(buf, 0, n);
                    downloaded += n;
                    if (pc != null) pc.onProgress(downloaded, total);
                }
            }
            return total;
        } finally {
            if (token != null) token.detach();
            if (conn != null) conn.disconnect();
        }
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

    private boolean writeCache(String json, String cacheName) {
        File cache = new File(appContext.getFilesDir(), cacheName);
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
