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

    /**
     * 二进制索引（由 tools/ioc_pack.py 生成）：161 万条也只需 ~110MB 磁盘、零堆内存，
     * 通过 mmap 二分查询。region 优先用它，服务器未提供时回退 JSON。
     */
    /**
     * 二进制索引的多源分发地址（按顺序回退）。
     * 两个库都以 LFS 存储，因此 media 域名为首选——它对 LFS 对象即时生效，
     * 而 jsDelivr 的 `@main` 别名在 push 后会滞后数小时（实测新 commit 可即时访问、
     * 但 @main 仍返回旧文件，purge 也无效），只能作为兜底。
     */
    public static final String[] BIN_URLS_REGION = {
            "https://media.githubusercontent.com/media/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.bin",
            "https://cdn.jsdelivr.net/gh/cat-and-mouse1/Polaris_Safety@main/polar-region/iodb.bin",
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-region/iodb.bin",
    };
    public static final String[] BIN_URLS_POINT = {
            "https://media.githubusercontent.com/media/cat-and-mouse1/Polaris_Safety/main/polar-point/iodb.bin",
            "https://cdn.jsdelivr.net/gh/cat-and-mouse1/Polaris_Safety@main/polar-point/iodb.bin",
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/polar-point/iodb.bin",
    };
    private static final String BIN_NAME = "iodb.bin";
    private static final String BIN_NAME_POINT = "iodb_point.bin";

    private static final String ASSET_NAME = "iodb_seed.json";
    private static final String CACHE_NAME = "iodb.json";
    private static final String CACHE_NAME_POINT = "iodb_point.json";
    /** 单个库文件的解析上限：超过则不做全量解析（避免超大库必然 OOM）。 */
    private static final long MAX_PARSE_BYTES = 64L * 1024 * 1024;

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

    private static volatile IocDatabase sInstanceRegion;
    private static volatile IocDatabase sInstancePoint;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String dbType; // "region" or "point"

    private int dbVersion = 0;
    private String updatedAt = "";
    private String source = "";
    /** 非空时走二进制索引（mmap 二分），此时上面三个 Map 保持空。 */
    private volatile IocBinaryIndex binIndex;

    private final Map<String, IocEntry> byHash = new HashMap<>();
    private final Map<String, IocEntry> byMd5 = new HashMap<>();
    private final Map<String, IocEntry> bySha1 = new HashMap<>();
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

        // 0) 优先二进制索引（mmap 只读，堆占用≈0）
        File binFile = new File(appContext.getFilesDir(),
                "point".equals(dbType) ? BIN_NAME_POINT : BIN_NAME);
        if (binFile.exists() && reloadBinary(binFile)) {
            Log.i(TAG, "Loaded binary IOC db [" + dbType + "] v" + dbVersion);
            return;
        }
        // 1) 优先加载运行时缓存（可能已被 refresh 更新）
        File cache = new File(appContext.getFilesDir(), cacheName);
        if (cache.exists()) {
            if (cache.length() > MAX_PARSE_BYTES) {
                // 超过解析上限：继续用 JSON 树解析必然 OOM，直接回退种子库
                Log.w(TAG, "IOC cache too large (" + cache.length() + " B > "
                        + MAX_PARSE_BYTES + " B), skip parsing; falling back to seed");
            } else {
                try (InputStream in = new FileInputStream(cache)) {
                    if (parse(in)) {
                        Log.i(TAG, "Loaded cached IOC db [" + dbType + "] v" + dbVersion);
                        return;
                    }
                } catch (IOException e) {
                    Log.w(TAG, "read IOC cache failed", e);
                }
            }
        }
        // 2) 回退内置种子库（assets/iodb_seed.json，内容现为 Polar Point）
        try (InputStream in = appContext.getAssets().open(assetSeed)) {
            if (parse(in)) {
                Log.i(TAG, "Loaded seed IOC db [" + dbType + "] v" + dbVersion);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load seed IOC db", e);
        }
    }

    /** 打开二进制索引并切换为 mmap 查询模式；失败则保持原有 JSON 索引。 */
    private boolean reloadBinary(File f) {
        IocBinaryIndex idx;
        try {
            idx = IocBinaryIndex.open(f);
        } catch (Exception e) {
            Log.w(TAG, "open iodb.bin failed: " + f, e);
            return false;
        }
        IocBinaryIndex old = binIndex;
        binIndex = idx;
        if (old != null) old.close();
        dbVersion = idx.getDbVersion();
        updatedAt = idx.getUpdatedAt();
        source = idx.getSource();
        // 二进制模式下不再使用 JSON 索引，释放其内存
        byHash.clear();
        byMd5.clear();
        bySha1.clear();
        byPkg.clear();
        byUrl.clear();
        return true;
    }

    /**
     * 流式解析库文件（android.util.JsonReader）。
     * 相比 JSONObject 全量树解析，峰值内存从「文件 String + 整棵 JSON 树」降到「条目对象本身」，
     * 避免超大库（region 全量）解析时 OOM。解析成功后才提交索引，失败不影响已加载数据。
     */
    private boolean parse(InputStream in) {
        if (in == null) return false;
        Map<String, IocEntry> nHash = new HashMap<>();
        Map<String, IocEntry> nMd5 = new HashMap<>();
        Map<String, IocEntry> nSha1 = new HashMap<>();
        Map<String, IocEntry> nPkg = new HashMap<>();
        Map<String, IocEntry> nUrl = new HashMap<>();
        int nVersion = 0;
        String nUpdatedAt = "";
        String nSource = "";
        boolean sawEntries = false;
        try (android.util.JsonReader r = new android.util.JsonReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ("db_version".equals(name)) {
                    nVersion = r.nextInt();
                } else if ("updated_at".equals(name)) {
                    nUpdatedAt = nextStringOr(r, "");
                } else if ("source".equals(name)) {
                    nSource = nextStringOr(r, "");
                } else if ("entries".equals(name)) {
                    sawEntries = true;
                    r.beginArray();
                    while (r.hasNext()) {
                        IocEntry e = new IocEntry();
                        r.beginObject();
                        while (r.hasNext()) {
                            String f = r.nextName();
                            switch (f) {
                                case "pkg": e.pkg = nextStringOr(r, ""); break;
                                case "sha256": e.sha256 = nextStringOr(r, ""); break;
                                case "md5": e.md5 = nextStringOr(r, ""); break;
                                case "sha1": e.sha1 = nextStringOr(r, ""); break;
                                case "family": e.family = nextStringOr(r, ""); break;
                                case "type": e.type = nextStringOr(r, ""); break;
                                case "severity": e.severity = nextStringOr(r, "medium"); break;
                                case "desc": e.desc = nextStringOr(r, ""); break;
                                case "url": e.url = nextStringOr(r, ""); break;
                                case "tags": {
                                    java.util.List<String> tags = new java.util.ArrayList<>();
                                    r.beginArray();
                                    while (r.hasNext()) tags.add(nextStringOr(r, ""));
                                    r.endArray();
                                    e.tags = tags.toArray(new String[0]);
                                    break;
                                }
                                default:
                                    r.skipValue();
                            }
                        }
                        r.endObject();
                        // 兼容未迁移的旧 JSON：历史管道把 MD5/SHA-1 写进了 sha256 字段
                        if (e.sha256 != null && e.sha256.length() == 32) {
                            if (e.md5 == null || e.md5.isEmpty()) e.md5 = e.sha256;
                            e.sha256 = "";
                        } else if (e.sha256 != null && e.sha256.length() == 40) {
                            if (e.sha1 == null || e.sha1.isEmpty()) e.sha1 = e.sha256;
                            e.sha256 = "";
                        }
                        if (e.sha256 != null && !e.sha256.isEmpty()) {
                            nHash.put(e.sha256.toLowerCase(java.util.Locale.US), e);
                        }
                        if (e.md5 != null && !e.md5.isEmpty()) {
                            nMd5.put(e.md5.toLowerCase(java.util.Locale.US), e);
                        }
                        if (e.sha1 != null && !e.sha1.isEmpty()) {
                            nSha1.put(e.sha1.toLowerCase(java.util.Locale.US), e);
                        }
                        if (e.pkg != null && !e.pkg.isEmpty()) nPkg.put(e.pkg, e);
                        if (e.url != null && !e.url.isEmpty()) {
                            nUrl.put(e.url.toLowerCase(java.util.Locale.US), e);
                        }
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        } catch (Exception e) {
            Log.w(TAG, "Bad IOC json", e);
            return false;
        }
        if (!sawEntries) return false;
        // 解析成功才提交，避免半途失败清空已有索引
        byHash.clear();
        byHash.putAll(nHash);
        byMd5.clear();
        byMd5.putAll(nMd5);
        bySha1.clear();
        bySha1.putAll(nSha1);
        byPkg.clear();
        byPkg.putAll(nPkg);
        byUrl.clear();
        byUrl.putAll(nUrl);
        dbVersion = nVersion;
        updatedAt = nUpdatedAt;
        source = nSource;
        return true;
    }

    /** 读取字符串；遇到 JSON null 时返回默认值（JsonReader 的 nextString 会返回 "null" 字面量）。 */
    private static String nextStringOr(android.util.JsonReader r, String def) throws IOException {
        if (r.peek() == android.util.JsonToken.NULL) {
            r.nextNull();
            return def;
        }
        return r.nextString();
    }

    // ---------- 查询 ----------

    public IocEntry matchByHash(String sha256) {
        if (sha256 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) return bin.matchByHash(sha256);
        return byHash.get(sha256.toLowerCase(java.util.Locale.US));
    }

    /** MD5 匹配（v2 库才有；旧 JSON 库经 parse 分流后同样支持）。 */
    public IocEntry matchByMd5(String md5) {
        if (md5 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) return bin.matchByMd5(md5);
        return byMd5.get(md5.toLowerCase(java.util.Locale.US));
    }

    /** SHA-1 匹配（v2 库才有）。 */
    public IocEntry matchBySha1(String sha1) {
        if (sha1 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) return bin.matchBySha1(sha1);
        return bySha1.get(sha1.toLowerCase(java.util.Locale.US));
    }

    public IocEntry matchByPkg(String pkg) {
        if (pkg == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) return bin.matchByPkg(pkg);
        return byPkg.get(pkg);
    }

    public IocEntry matchByUrl(String url) {
        if (url == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) return bin.matchByUrl(url);
        return byUrl.get(url.toLowerCase(java.util.Locale.US));
    }

    public int getDbVersion() { return dbVersion; }

    /**
     * 只读取库文件头部的 db_version（不解析全部条目，避免仅为了显示版本而加载 357MB 全量库）。
     * point 无缓存时读内置种子。
     * @return 版本号；region 无本地缓存时返回 -1（未下载）。
     */
    public static int peekDbVersion(Context context, String type) {
        // 二进制索引优先：只读 16 字节头，不触发任何解析
        File bin = new File(context.getFilesDir(),
                "point".equals(type) ? BIN_NAME_POINT : BIN_NAME);
        if (bin.exists()) {
            int v = peekBinaryVersion(bin);
            if (v >= 0) return v;
        }
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
    /**
     * 清理无法续传的残留分片：`.part` 存在但 `.part.meta` 缺失或损坏时，
     * 既不能续传又会让界面误显示「下载中」，直接删除释放空间。
     */
    /** 读取 iodb.bin 头部的 dbVersion（magic 不匹配返回 -1）。 */
    private static int peekBinaryVersion(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] head = new byte[16];
            raf.readFully(head);
            if (head[0] != 'P' || head[1] != 'O' || head[2] != 'L' || head[3] != 'A'
                    || head[4] != 'R' || head[5] != 'I' || head[6] != 'S' || head[7] != 'B') {
                return -1;
            }
            // 注意：ByteBuffer.getInt(int) 是「绝对索引」，与 wrap 的 offset 无关；
            // db_version 在 header 的第 12 字节，必须写 getInt(12)。
            return java.nio.ByteBuffer.wrap(head)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void cleanupOrphanedParts(Context context) {
        for (String name : new String[]{CACHE_NAME, CACHE_NAME_POINT, BIN_NAME, BIN_NAME_POINT}) {
            File part = new File(context.getFilesDir(), name + ".part");
            if (!part.exists()) continue;
            File meta = new File(context.getFilesDir(), name + ".part.meta");
            boolean resumable = false;
            if (meta.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(meta), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        Long.parseLong(line.trim());
                        resumable = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!resumable) {
                long size = part.length();
                if (part.delete()) {
                    Log.i(TAG, "Removed orphan IOC part " + part.getName() + " (" + size + " B)");
                }
                deletePartMeta(part);
            }
        }
    }

    public static PartialDownload peekPartialDownload(Context context, String type) {
        String cacheName = "point".equals(type) ? CACHE_NAME_POINT : CACHE_NAME;
        // 二进制优先：其 .part 也是有效的下载进度来源
        PartialDownload binPart = peekPart(context,
                ("point".equals(type) ? BIN_NAME_POINT : BIN_NAME) + ".part");
        if (binPart != null) return binPart;
        return peekPart(context, cacheName + ".part");
    }

    private static PartialDownload peekPart(Context context, String partName) {
        File part = new File(context.getFilesDir(), partName);
        if (!part.exists()) return null;
        long total = 0;
        File meta = new File(context.getFilesDir(), part.getName() + ".meta");
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
        final String[] binUrls = "point".equals(dbType) ? BIN_URLS_POINT : BIN_URLS_REGION;
        final String binName = "point".equals(dbType) ? BIN_NAME_POINT : BIN_NAME;
        File part = new File(appContext.getFilesDir(), cacheName + ".part");
        final File binPart = new File(appContext.getFilesDir(), binName + ".part");
        final File binTarget = new File(appContext.getFilesDir(), binName);

        executor.execute(() -> {
            try {
                // 0) 优先二进制索引：体积约为 JSON 的 1/3，且可 mmap 加载（161 万条也不 OOM）
                //    多源依次尝试，任一源成功即止；全部失败才回退 JSON。
                long binTotal = -1;
                for (String binUrl : binUrls) {
                    try {
                        binTotal = downloadToFileResume(binUrl, binPart, pc, token);
                    } catch (Exception binErr) {
                        Log.w(TAG, "bin download failed from " + binUrl + ": " + binErr.getMessage());
                        binTotal = -1;
                    }
                    if (token != null && token.isCancelled()) {
                        silentCancel(cb);
                        return;
                    }
                    // 下完不等于拿到干货：CDN 可能回 LFS 指针文本或压缩流，校验 magic
                    if (binTotal > 0 && looksLikeIocBin(binPart)) break;
                    if (binTotal > 0) {
                        Log.w(TAG, "bin from " + binUrl + " is not a valid iodb.bin, trying next source");
                    }
                    // 该源不可用：清掉空/坏 part，换下一个源从头下
                    if (binPart.exists()) binPart.delete();
                    deletePartMeta(binPart);
                    binTotal = -1;
                }
                if (binTotal > 0 && binPart.exists() && binPart.renameTo(binTarget)) {
                    if (reloadBinary(binTarget)) {
                        deletePartMeta(binPart);
                        Log.i(TAG, "IOC bin [" + dbType + "] refreshed to v" + dbVersion);
                        cb.onUpdated(dbVersion);
                        return;
                    }
                    binTarget.delete();   // 坏文件：删掉回退 JSON
                } else if (binTotal < 0) {
                    // 服务器暂无 .bin：清掉空的 part，继续走 JSON
                    if (binPart.exists()) binPart.delete();
                    deletePartMeta(binPart);
                }

                // 1) 回退 JSON（老分发格式 / 本地自建库）
                // 体积预检：region 全量 JSON 约 357MB，即使完整下完也必然被 MAX_PARSE_BYTES 拒绝，
                // 徒耗流量与时间（实测 ~140KB/s 需 40 分钟）。已知总大小时直接中止，不再落盘。
                long knownTotal = 0;
                PartialDownload prev = peekPart(appContext, part.getName());
                if (prev != null) knownTotal = prev.total;
                if (knownTotal <= 0) knownTotal = probeRemoteSize(baseUrl);
                if (knownTotal > MAX_PARSE_BYTES) {
                    if (part.exists()) part.delete();
                    deletePartMeta(part);
                    cb.onError("该病毒库 " + (knownTotal / 1024 / 1024)
                            + " MB 超过客户端解析上限（" + (MAX_PARSE_BYTES / 1024 / 1024)
                            + " MB），需服务端提供二进制索引（iodb.bin）");
                    return;
                }
                long total = downloadToFileResume(baseUrl, part, pc, token);
                if (token != null && token.isCancelled()) {
                    silentCancel(cb);
                    return;
                }
                if (total < 0) {
                    cb.onError("下载失败（HTTP 404，文件不存在）");
                    return;
                }
                if (!part.exists() || part.length() == 0) {
                    cb.onError("空响应");
                    return;
                }
                // 防御：若拿到的是 Git LFS 指针 (~130 字节) 而非真实内容（分发地址误用 raw 域名时），
                // 只读文件头部判断，避免把超大库整份读进内存
                if (isLfsPointer(part)) {
                    part.delete();
                    deletePartMeta(part);
                    cb.onError("下载到的是 Git LFS 指针，非病毒库内容（分发地址需用 media 域名）");
                    return;
                }
                // 签名校验（骨架）：若服务端提供 .sig 且配置了密钥则严格校验，
                // 否则接受（种子库/自建清单尚未签名，属预期内）。
                String sig = download(sigUrl);
                if (sig != null && !sig.isEmpty() && !HMAC_KEY.isEmpty()) {
                    if (!verifyHmac(part, sig, HMAC_KEY)) {
                        cb.onError("签名校验失败，已丢弃本次更新");
                        part.delete();
                        return;
                    }
                }
                // 原子替换缓存，再以流式解析热重载（不再整份读入 String，避免 OOM）
                File target = new File(appContext.getFilesDir(), cacheName);
                if (!part.renameTo(target)) {
                    part.delete();
                    deletePartMeta(part);
                    cb.onError("写入病毒库缓存失败");
                    return;
                }
                boolean parsed = false;
                if (target.length() > MAX_PARSE_BYTES) {
                    // 例如 region 全量库 357MB：解析后常驻内存约 558MB，必然 OOM。
                    // 这类库必须由服务端提供 .bin（见 tools/ioc_pack.py）。
                    target.delete();
                    deletePartMeta(part);
                    cb.onError("该病毒库体积过大（" + (target.length() / 1024 / 1024)
                            + " MB），需服务端提供二进制索引（iodb.bin）才能使用");
                    return;
                }
                try (InputStream in = new FileInputStream(target)) {
                    parsed = parse(in);
                }
                if (parsed) {
                    deletePartMeta(part); // 下载完成，总大小记录不再需要
                    if (binTarget.exists() && binTarget.delete()) {
                        // 以最新 JSON 为准，避免下次仍加载过期的 .bin
                        Log.i(TAG, "stale iodb.bin removed after json refresh");
                    }
                    if (binIndex != null) {
                        binIndex.close();
                        binIndex = null;
                    }
                    Log.i(TAG, "IOC db [" + dbType + "] refreshed to v" + dbVersion);
                    cb.onUpdated(dbVersion);
                } else {
                    // 解析失败：缓存文件已损坏，删除以便下次整文件重下
                    target.delete();
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

    /** 探测远端文件总字节数（Range: bytes=0-0 → 读 Content-Range）；未知或失败返回 0。 */
    private static long probeRemoteSize(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return 0;
            String cr = conn.getHeaderField("Content-Range"); // 形如 bytes 0-0/357240995
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    String t = cr.substring(slash + 1).trim();
                    if (!t.isEmpty() && !"*".equals(t)) return Long.parseLong(t);
                }
            }
            long len = conn.getContentLengthLong();
            return len > 0 ? len : 0;
        } catch (Exception e) {
            Log.w(TAG, "probe remote size failed: " + e.getMessage());
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 校验文件是否为合法的 iodb.bin（前 8 字节 magic）。
     * 用于在入库前拦截 CDN 错误响应、Git LFS 指针文本（132B）、压缩流等"看起来像文件"的垃圾数据。
     */
    private static boolean looksLikeIocBin(File f) {
        if (f == null || !f.exists() || f.length() < 128) return false;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] m = new byte[8];
            int r = in.read(m);
            return r == 8 && "POLARISB".equals(new String(m, StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    /** 流式下载到 part 文件并支持断点续传，返回总字节数（无 Content-Length 则为 0；404 返回 -1）。 */
    private long downloadToFileResume(String urlStr, File part, ProgressCallback pc, DownloadToken token) throws IOException {
        return downloadToFileResume(urlStr, part, pc, token, false);
    }

    /** retried=true 表示已因 HTTP 416 重试过一次，避免死循环。 */
    private long downloadToFileResume(String urlStr, File part, ProgressCallback pc, DownloadToken token,
                                     boolean retried) throws IOException {
        // jsDelivr 的 Range 请求会命中它自己的压缩缓存：<returns 206 但 Content-Range 总长是 gzip 后大小，
        // 内容也是压缩流>，续传必然写坏。该源一律整份重下。
        boolean resumeSupported = !urlStr.contains("cdn.jsdelivr.net");
        if (!resumeSupported && part.exists()) {
            part.delete();
            deletePartMeta(part);
        }
        long existing = (resumeSupported && part.exists()) ? part.length() : 0;
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

    /** 只读文件头部判断是否为 Git LFS 指针文本（避免全量读入内存）。 */
    private static boolean isLfsPointer(File f) {
        byte[] buf = new byte[256];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            if (n <= 0) return false;
            return new String(buf, 0, n, StandardCharsets.UTF_8)
                    .startsWith("version https://git-lfs.github.com/spec");
        } catch (IOException e) {
            return false;
        }
    }

    /** 文件版签名校验（占位实现，避免为校验把整个库读进内存）。 */
    private static boolean verifyHmac(File f, String sigHex, String key) {
        return verifyHmac("", sigHex, key);
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
