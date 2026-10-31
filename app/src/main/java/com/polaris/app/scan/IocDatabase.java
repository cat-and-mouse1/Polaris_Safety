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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        /** 本地已不落后于远端（无需下载）。默认回落到 onUpdated，UI 可覆盖以显示不同文案。 */
        default void onUpToDate(int version) { onUpdated(version); }
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

    /** 增量包叠加层：按 dbVersion 升序，链式基于 base（base v15 → v16 → v17 …）。 */
    private final List<IocBinaryIndex> overlays = new ArrayList<>();
    /** 叠加层数量上限：超过则放弃增量、整份重下全量库（防止无限膨胀）。 */
    private static final int MAX_OVERLAYS = 5;

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

        // 0) 优先二进制索引（mmap 只读，堆占用≈0）；随后叠加增量包（iodb-delta-*.bin）
        File binFile = new File(appContext.getFilesDir(),
                "point".equals(dbType) ? BIN_NAME_POINT : BIN_NAME);
        if (binFile.exists() && reloadBinary(binFile)) {
            loadOverlays();
            Log.i(TAG, "Loaded binary IOC db [" + dbType + "] v" + dbVersion
                    + (overlays.isEmpty() ? "" : " (+" + overlays.size() + " delta)"));
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
     * 增量包文件名：iodb.bin → iodb-delta-<基线版本>.bin（point: iodb_point-delta-<v>.bin）。
     * 与服务端发布约定一致，客户端按本地基线版本拼出地址去探测。
     */
    private static String deltaFileName(String type, int baseVer) {
        String binName = "point".equals(type) ? BIN_NAME_POINT : BIN_NAME;
        int dot = binName.lastIndexOf(".bin");
        return binName.substring(0, dot) + "-delta-" + baseVer + ".bin";
    }

    /** 扫描 files/ 下本库的全部增量包，返回 基线版本 → 目标版本 的映射（含非法文件过滤）。 */
    private static Map<Integer, Integer> readDeltaChain(Context context, String type) {
        Map<Integer, Integer> chain = new HashMap<>();
        String prefix = ("point".equals(type) ? BIN_NAME_POINT : BIN_NAME)
                .replace(".bin", "-delta-");
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return chain;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(prefix) || !n.endsWith(".bin")) continue;
            int[] vv = peekBinVersions(f);
            if (vv != null && vv[1] > 0 && vv[0] > vv[1]) chain.put(vv[1], vv[0]);
        }
        return chain;
    }

    /** 读取 bin 头部的 [dbVersion, baseVersion]；magic 不符返回 null。 */
    static int[] peekBinVersions(File f) {        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] head = new byte[96];
            raf.readFully(head);
            if (head[0] != 'P' || head[1] != 'O' || head[2] != 'L' || head[3] != 'A'
                    || head[4] != 'R' || head[5] != 'I' || head[6] != 'S' || head[7] != 'B') {
                return null;
            }
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return new int[]{b.getInt(12), b.getInt(88)};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 叠加本地全部合法增量包：从 base 版本开始沿 baseVersion → dbVersion 链加载，
     * 链断处停止（跳版或文件缺失），保证查询时叠加顺序与发布顺序一致。
     */
    private void loadOverlays() {
        closeOverlays();
        int v = (binIndex != null) ? binIndex.getDbVersion() : -1;
        for (int i = 0; i < MAX_OVERLAYS; i++) {
            File f = new File(appContext.getFilesDir(), deltaFileName(dbType, v));
            if (!f.exists()) break;
            int[] vv = peekBinVersions(f);
            if (vv == null || vv[1] != v || vv[0] <= v) {
                Log.w(TAG, "delta " + f.getName() + " 不构成 v" + v + " 的合法后继，忽略其后全部增量");
                break;
            }
            try {
                overlays.add(IocBinaryIndex.open(f));
            } catch (Exception e) {
                Log.w(TAG, "open delta " + f.getName() + " failed", e);
                break;
            }
            v = vv[0];
        }
        if (!overlays.isEmpty() && binIndex != null) {
            dbVersion = v;
        }
    }

    private void closeOverlays() {
        for (IocBinaryIndex o : overlays) {
            try { o.close(); } catch (Exception ignored) { }
        }
        overlays.clear();
    }

    /** 删除本地全部增量包（整份全量更新成功后调用）。 */
    private void clearOverlayFiles() {
        closeOverlays();
        File[] files = appContext.getFilesDir().listFiles();
        if (files == null) return;
        String prefix = ("point".equals(dbType) ? BIN_NAME_POINT : BIN_NAME)
                .replace(".bin", "-delta-");
        for (File f : files) {
            if (f.getName().startsWith(prefix) && f.getName().endsWith(".bin")) {
                if (f.delete()) Log.i(TAG, "removed delta " + f.getName());
            }
        }
    }

    /** 当前有效版本：叠加层链顶；无叠加层时即 base 版本。 */
    private int effectiveVersion() {
        if (!overlays.isEmpty()) return overlays.get(overlays.size() - 1).getDbVersion();
        return (binIndex != null) ? binIndex.getDbVersion() : dbVersion;
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

    /**
     * 查询顺序：增量叠加层（新 → 旧）优先，再查全量 base。
     * 增量包只含新增条目，与 base 无重叠；先查叠加层让「最新情报」优先返回，
     * 同键冲突时以新数据为准。
     */
    public IocEntry matchByHash(String sha256) {
        if (sha256 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) {
            for (int i = overlays.size() - 1; i >= 0; i--) {
                IocEntry e = overlays.get(i).matchByHash(sha256);
                if (e != null) return e;
            }
            return bin.matchByHash(sha256);
        }
        return byHash.get(sha256.toLowerCase(java.util.Locale.US));
    }

    /** MD5 匹配（v2 库才有；旧 JSON 库经 parse 分流后同样支持）。 */
    public IocEntry matchByMd5(String md5) {
        if (md5 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) {
            for (int i = overlays.size() - 1; i >= 0; i--) {
                IocEntry e = overlays.get(i).matchByMd5(md5);
                if (e != null) return e;
            }
            return bin.matchByMd5(md5);
        }
        return byMd5.get(md5.toLowerCase(java.util.Locale.US));
    }

    /** SHA-1 匹配（v2 库才有）。 */
    public IocEntry matchBySha1(String sha1) {
        if (sha1 == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) {
            for (int i = overlays.size() - 1; i >= 0; i--) {
                IocEntry e = overlays.get(i).matchBySha1(sha1);
                if (e != null) return e;
            }
            return bin.matchBySha1(sha1);
        }
        return bySha1.get(sha1.toLowerCase(java.util.Locale.US));
    }

    public IocEntry matchByPkg(String pkg) {
        if (pkg == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) {
            for (int i = overlays.size() - 1; i >= 0; i--) {
                IocEntry e = overlays.get(i).matchByPkg(pkg);
                if (e != null) return e;
            }
            return bin.matchByPkg(pkg);
        }
        return byPkg.get(pkg);
    }

    public IocEntry matchByUrl(String url) {
        if (url == null) return null;
        IocBinaryIndex bin = binIndex;
        if (bin != null) {
            for (int i = overlays.size() - 1; i >= 0; i--) {
                IocEntry e = overlays.get(i).matchByUrl(url);
                if (e != null) return e;
            }
            return bin.matchByUrl(url);
        }
        return byUrl.get(url.toLowerCase(java.util.Locale.US));
    }

    public int getDbVersion() { return dbVersion; }

    /**
     * 只读取库文件头部的 db_version（不解析全部条目，避免仅为了显示版本而加载 357MB 全量库）。
     * point 无缓存时读内置种子。
     * @return 版本号；region 无本地缓存时返回 -1（未下载）。
     */
    public static int peekDbVersion(Context context, String type) {
        // 二进制索引优先：只读头部，不触发任何解析；有效版本 = base 沿增量链走到顶
        File bin = new File(context.getFilesDir(),
                "point".equals(type) ? BIN_NAME_POINT : BIN_NAME);
        if (bin.exists()) {
            int[] vv = peekBinVersions(bin);
            if (vv != null) {
                int v = vv[0];
                Map<Integer, Integer> chain = readDeltaChain(context, type);
                for (int guard = 0; guard < MAX_OVERLAYS + 1; guard++) {
                    Integer next = chain.get(v);
                    if (next == null) break;
                    v = next;
                }
                return v;
            }
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
        for (String name : new String[]{CACHE_NAME, CACHE_NAME_POINT, BIN_NAME, BIN_NAME_POINT,
                BIN_NAME + ".delta", BIN_NAME_POINT + ".delta"}) {
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
    private static final java.util.concurrent.ConcurrentHashMap<String, RemoteInfo> REMOTE_INFO_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor();

    /** 远端库信息：版本号 + 总字节数（-1 = 未知）。 */
    public static class RemoteInfo {
        public final int version;
        public final long sizeBytes;

        RemoteInfo(int version, long sizeBytes) {
            this.version = version;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * 对单个 bin 分发地址发 Range 0-127 探测：从头部解析 dbVersion，
     * 并从 Content-Range 拿全量体积（LFS 的 media 域名对 Range 支持正常；
     * jsDelivr 的 Range 会返回压缩流 → magic 校验失败 → 视为该源不可用）。
     */
    private static RemoteInfo probeBinUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Range", "bytes=0-127");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return null;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[128];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1) n += r;
                if (n < 16) return null;
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(buf, 0, n)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                boolean magicOk = buf[0] == 'P' && buf[1] == 'O' && buf[2] == 'L' && buf[3] == 'A'
                        && buf[4] == 'R' && buf[5] == 'I' && buf[6] == 'S' && buf[7] == 'B';
                if (!magicOk || b.getInt(8) != 2) return null;
                int ver = b.getInt(12);
                if (ver <= 0) return null;
                long size = -1;
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) {
                        try { size = Long.parseLong(cr.substring(slash + 1).trim()); }
                        catch (NumberFormatException ignored) { }
                    }
                } else if (code == 200) {
                    size = conn.getContentLengthLong();
                }
                return new RemoteInfo(ver, size);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 依次探测 bin 多源，返回第一个成功的结果；全失败返回 null。 */
    private static RemoteInfo probeRemoteBinInfo(String type) {
        for (String url : ("point".equals(type) ? BIN_URLS_POINT : BIN_URLS_REGION)) {
            RemoteInfo ri = probeBinUrl(url);
            if (ri != null) return ri;
        }
        return null;
    }

    /**
     * 异步探测远端库的版本与体积（Range 只取 128 字节头，不下载全量库）。
     * 用于「未下载」状态下显示真实最新版本、以及更新前对话框的体积提示。
     * 成功结果进程内缓存，失败返回 version=-1。
     */
    public static void peekRemoteInfo(String type, java.util.function.Consumer<RemoteInfo> cb) {
        RemoteInfo cached = REMOTE_INFO_CACHE.get(type);
        if (cached != null) {
            cb.accept(cached);
            return;
        }
        PROBE_EXECUTOR.execute(() -> {
            RemoteInfo ri = probeRemoteBinInfo(type);
            if (ri != null) {
                REMOTE_INFO_CACHE.put(type, ri);
                REMOTE_VER_CACHE.put(type, ri.version);
            }
            cb.accept(ri != null ? ri : new RemoteInfo(-1, -1));
        });
    }

    /** 兼容旧调用：只关心版本号。 */
    public static void peekRemoteVersion(String type, RemoteVersionCallback cb) {
        peekRemoteInfo(type, ri -> cb.onVersion(ri.version));
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
     * 尝试下载并叠加增量包 iodb-delta-<localVer>.bin（多源依次尝试）。
     * 增量包通常只有几百 KB ~ 几 MB，条目与 base 无重叠，直接作为 mmap 叠加层生效。
     * 成功且链顶达到远端版本返回 true；任何失败（404/体积不匹配/校验失败）都清理现场
     * 并返回 false，由调用方回退全量下载。
     */
    private boolean tryApplyDelta(RemoteInfo ri, int localVer, String[] binUrls,
                                  RefreshCallback cb, ProgressCallback pc, DownloadToken token) {
        String binName = "point".equals(dbType) ? BIN_NAME_POINT : BIN_NAME;
        String deltaName = deltaFileName(dbType, localVer);
        File deltaPart = new File(appContext.getFilesDir(), binName + ".delta.part");
        File deltaTarget = new File(appContext.getFilesDir(), deltaName);
        Log.i(TAG, "probing delta " + deltaName + " for v" + localVer + " -> v" + ri.version);
        for (String binUrl : binUrls) {
            String deltaUrl = binUrl.substring(0, binUrl.lastIndexOf('/') + 1) + deltaName;
            try {
                long total = downloadToFileResume(deltaUrl, deltaPart, pc, token);
                if (token != null && token.isCancelled()) {
                    silentCancel(cb);
                    return true;
                }
                if (total > 0 && looksLikeIocBin(deltaPart)) {
                    int[] vv = peekBinVersions(deltaPart);
                    if (vv != null && vv[1] == localVer && vv[0] == ri.version
                            && deltaPart.renameTo(deltaTarget)) {
                        loadOverlays();
                        if (effectiveVersion() == ri.version) {
                            deletePartMeta(deltaPart);
                            Log.i(TAG, "IOC delta [" + dbType + "] applied: v" + localVer
                                    + " -> v" + ri.version + " (+" + deltaTarget.length() / 1024 + " KB)");
                            cb.onUpdated(ri.version);
                            return true;
                        }
                        deltaTarget.delete();
                    }
                    Log.w(TAG, "delta from " + deltaUrl + " not applicable (base/target mismatch)");
                }
            } catch (Exception e) {
                Log.w(TAG, "delta download failed from " + deltaUrl + ": " + e.getMessage());
            }
            if (deltaPart.exists()) deltaPart.delete();
            deletePartMeta(deltaPart);
            // 该源没有增量包（404 / 压缩流）→ 换下一个源
        }
        if (deltaPart.exists()) deltaPart.delete();
        deletePartMeta(deltaPart);
        return false;
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
                // -1) 探测远端最新版本与体积（Range 128B，秒级）
                RemoteInfo ri = probeRemoteBinInfo(dbType);
                int localVer = effectiveVersion();
                if (ri != null && ri.version > 0 && localVer > 0) {
                    if (ri.version <= localVer) {
                        // 本地（base + 增量叠加）已不落后于远端：不重复下载
                        Log.i(TAG, "IOC db [" + dbType + "] already latest: local v" + localVer
                                + " >= remote v" + ri.version);
                        cb.onUpToDate(localVer);
                        return;
                    }
                    // -2) 增量优先：iodb-delta-<本地版本>.bin 存在且基线匹配时，只下载新增条目
                    if (overlays.size() < MAX_OVERLAYS
                            && tryApplyDelta(ri, localVer, binUrls, cb, pc, token)) {
                        return;
                    }
                    // 无可用增量（跳版/文件不存在/叠加层已满）→ 走全量
                }

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
                        // 全量已是最新内容：旧增量包全部作废，删除以免叠加出错误版本
                        clearOverlayFiles();
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
                    cb.onError("服务器数据包过大（" + (knownTotal / 1024 / 1024)
                            + " MB），暂时无法在手机上使用，请等待后续版本");
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
                    cb.onError("服务器数据包过大（" + (target.length() / 1024 / 1024)
                            + " MB），暂时无法在手机上使用，请等待后续版本");
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
                    closeOverlays(); // JSON 重建了全量索引，增量叠加层随之作废
                    clearOverlayFiles();
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
