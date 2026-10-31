package com.polaris.app.scan;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * 威胁情报二进制索引（iodb.bin）读取器。
 *
 * <p>为什么需要：region 全量库是 161 万条 / 357 MB 的 JSON。若解析成对象 + HashMap，
 * 常驻内存约 558 MB，任何手机都会 OOM。iodb.bin 把数据组织成「排序哈希表 + 定长明细 +
 * 字符串字典」，用 {@link MappedByteBuffer} 只读映射，堆内存占用接近 0，查询为二分 O(log n)。
 *
 * <p>文件格式由 tools/ioc_pack.py 生成，两侧必须保持一致：
 * <pre>
 *   HEADER 128B: magic "POLARISB", formatVer, dbVersion, updatedAtId, sourceId,
 *                entryCount, hashCount, pkgCount, urlCount, strCount,
 *                hashOff, pkgOff, urlOff, detailOff, strIndexOff, strPoolOff, strPoolSize
 *   HASH  36B × n : sha256[32] + u32 detailIndex      （按 sha256 升序）
 *   PKG   12B × n : u64 fnv1a64(pkg) + u32 detailIndex
 *   URL   12B × n : u64 fnv1a64(lower(url)) + u32 detailIndex
 *   DETAIL 24B × n: u32 pkgId, familyId, typeId, sevId, descId, urlId
 *   STRIDX 4B × m : u32 → 字符串池偏移
 *   STRPOOL       : [u16 len + utf8] × m
 * </pre>
 */
public final class IocBinaryIndex implements Closeable {

    private static final byte[] MAGIC = {'P', 'O', 'L', 'A', 'R', 'I', 'S', 'B'};
    private static final int FORMAT_V1 = 1;
    private static final int FORMAT_V2 = 2;
    private static final int HEADER_SIZE = 128;
    private static final int HASH_REC = 36;   // sha256[32] + u32 detailIndex
    private static final int MD5_REC = 20;    // md5[16]    + u32 detailIndex
    private static final int SHA1_REC = 24;   // sha1[20]   + u32 detailIndex
    private static final int IDX_REC = 12;
    private static final int DETAIL_REC = 24;
    private static final int NONE = 0xFFFFFFFF;

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final MappedByteBuffer buf;

    private int formatVersion;
    private int dbVersion;
    /** 增量包基线版本（header 偏移 88）；全量包为 0。 */
    private int baseVersion;
    private int entryCount;
    private int hashCount;
    private int pkgCount;
    private int urlCount;
    private int strCount;
    private int hashOff;
    private int pkgOff;
    private int urlOff;
    private int detailOff;
    private int strIndexOff;
    private int strPoolOff;
    private int md5Count;     // v2
    private int md5Off;       // v2
    private int sha1Count;    // v2
    private int sha1Off;      // v2
    private String updatedAt = "";
    private String source = "";

    private IocBinaryIndex(RandomAccessFile raf, FileChannel channel, MappedByteBuffer buf) {
        this.raf = raf;
        this.channel = channel;
        this.buf = buf;
    }

    /** 打开并按需建立只读映射；格式不合法会抛 IOException。 */
    public static IocBinaryIndex open(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        FileChannel ch = raf.getChannel();
        try {
            if (ch.size() < HEADER_SIZE) throw new IOException("iodb.bin 太小");
            MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            b.order(ByteOrder.LITTLE_ENDIAN);
            IocBinaryIndex idx = new IocBinaryIndex(raf, ch, b);
            idx.readHeader();
            return idx;
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
            try {
                raf.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void readHeader() throws IOException {
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf.get(i) != MAGIC[i]) throw new IOException("iodb.bin magic 不匹配");
        }
        int fmt = buf.getInt(8);
        if (fmt != FORMAT_V1 && fmt != FORMAT_V2) {
            throw new IOException("iodb.bin 格式版本不支持: " + fmt);
        }
        formatVersion = fmt;
        dbVersion = buf.getInt(12);
        int updatedAtId = buf.getInt(16);
        int sourceId = buf.getInt(20);
        entryCount = buf.getInt(24);
        hashCount = buf.getInt(28);
        pkgCount = buf.getInt(32);
        urlCount = buf.getInt(36);
        strCount = buf.getInt(40);
        hashOff = buf.getInt(44);
        pkgOff = buf.getInt(48);
        urlOff = buf.getInt(52);
        detailOff = buf.getInt(56);
        strIndexOff = buf.getInt(60);
        strPoolOff = buf.getInt(64);
        if (fmt >= FORMAT_V2) {
            md5Count = buf.getInt(72);
            md5Off = buf.getInt(76);
            sha1Count = buf.getInt(80);
            sha1Off = buf.getInt(84);
            // 偏移 88（v2 预留区）：增量包的基线版本；全量包为 0。
            baseVersion = buf.getInt(88);
        }
        updatedAt = readString(updatedAtId);
        source = readString(sourceId);
    }

    public int getDbVersion() {
        return dbVersion;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    /** 增量包的基线版本（全量包为 0）。客户端据此判断增量能否叠加到本地全量库上。 */
    public int getBaseVersion() {
        return baseVersion;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getSource() {
        return source;
    }

    // ---------- 查询 ----------

    /** SHA-256 精确匹配（二分，32 字节字典序）。 */
    public IocEntry matchByHash(String sha256Hex) {
        if (sha256Hex == null || sha256Hex.length() != 64 || hashCount == 0) return null;
        IocEntry e = matchFixed(hashOff, hashCount, HASH_REC, 32, sha256Hex);
        if (e != null) e.sha256 = sha256Hex.toLowerCase(java.util.Locale.US);
        return e;
    }

    /** MD5 精确匹配（二分，16 字节字典序）。v1 格式无此表，恒返回 null。 */
    public IocEntry matchByMd5(String md5Hex) {
        if (md5Hex == null || md5Hex.length() != 32 || md5Count == 0) return null;
        IocEntry e = matchFixed(md5Off, md5Count, MD5_REC, 16, md5Hex);
        if (e != null) e.md5 = md5Hex.toLowerCase(java.util.Locale.US);
        return e;
    }

    /** SHA-1 精确匹配（二分，20 字节字典序）。v1 格式无此表，恒返回 null。 */
    public IocEntry matchBySha1(String sha1Hex) {
        if (sha1Hex == null || sha1Hex.length() != 40 || sha1Count == 0) return null;
        IocEntry e = matchFixed(sha1Off, sha1Count, SHA1_REC, 20, sha1Hex);
        if (e != null) e.sha1 = sha1Hex.toLowerCase(java.util.Locale.US);
        return e;
    }

    /** 定长哈希表的二分查找；命中返回明细下标对应的条目。 */
    private IocEntry matchFixed(int off, int count, int rec, int keyLen, String hex) {
        byte[] key = hexToBytes(hex);
        if (key == null) return null;
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int base = off + mid * rec;
            int cmp = compareBytes(base, key, keyLen);
            if (cmp == 0) return entryAt(buf.getInt(base + keyLen));
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    /** 包名匹配：先按 64 位哈希二分，再用明细里的包名做碰撞确认。 */
    public IocEntry matchByPkg(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkgCount == 0) return null;
        long h = fnv1a64(pkg.getBytes(StandardCharsets.UTF_8));
        int lo = 0, hi = pkgCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int base = pkgOff + mid * IDX_REC;
            long cur = buf.getLong(base);
            // 打包端（Python）按无符号 64 位排序，这里必须用无符号比较，
            // 否则最高位为 1 的哈希（约占一半）二分方向相反、永远查不到。
            int cmp = Long.compareUnsigned(cur, h);
            if (cmp == 0) {
                IocEntry e = entryAt(buf.getInt(base + 8));
                if (e != null && e.pkg != null && e.pkg.equals(pkg)) return e;
                return null;   // 哈希碰撞：不误报
            }
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    /** URL 匹配（大小写不敏感）。 */
    public IocEntry matchByUrl(String url) {
        if (url == null || url.isEmpty() || urlCount == 0) return null;
        String lower = url.toLowerCase(java.util.Locale.US);
        long h = fnv1a64(lower.getBytes(StandardCharsets.UTF_8));
        int lo = 0, hi = urlCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int base = urlOff + mid * IDX_REC;
            long cur = buf.getLong(base);
            int cmp = Long.compareUnsigned(cur, h);   // 与打包端的无符号排序一致
            if (cmp == 0) {
                IocEntry e = entryAt(buf.getInt(base + 8));
                if (e != null && e.url != null && e.url.equalsIgnoreCase(lower)) return e;
                return null;
            }
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    // ---------- 内部 ----------

    /** 按明细下标构造条目（字符串按需从字典读取）。 */
    private IocEntry entryAt(int detailIndex) {
        if (detailIndex < 0 || detailIndex >= entryCount) return null;
        int base = detailOff + detailIndex * DETAIL_REC;
        IocEntry e = new IocEntry();
        e.pkg = readString(buf.getInt(base));
        e.family = readString(buf.getInt(base + 4));
        e.type = readString(buf.getInt(base + 8));
        String sev = readString(buf.getInt(base + 12));
        e.severity = sev == null || sev.isEmpty() ? "medium" : sev;
        e.desc = readString(buf.getInt(base + 16));
        e.url = readString(buf.getInt(base + 20));
        e.sha256 = "";
        return e;
    }

    /** 读字符串字典；id 为 NONE 返回空串。 */
    private String readString(int id) {
        if (id == NONE || id < 0 || id >= strCount) return "";
        int poolOffset = buf.getInt(strIndexOff + id * 4);
        int at = strPoolOff + poolOffset;
        int len = buf.getShort(at) & 0xFFFF;
        byte[] raw = new byte[len];
        for (int i = 0; i < len; i++) raw[i] = buf.get(at + 2 + i);
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** 比较映射区 len 字节与给定 key（避免为每次二分分配数组）。 */
    private int compareBytes(int base, byte[] key, int len) {
        for (int i = 0; i < len; i++) {
            int a = buf.get(base + i) & 0xFF;
            int b = key[i] & 0xFF;
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 与打包脚本一致的 FNV-1a 64 位哈希。 */
    static long fnv1a64(byte[] data) {
        long h = 0xCBF29CE484222325L;
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x100000001B3L;
        }
        return h;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }
}
