import com.polaris.app.scan.IocBinaryIndex;
import com.polaris.app.scan.IocEntry;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 真实区域库（v2，161 万条）抽样验证：不依赖 Android/AGP，javac + java 直接运行。
 *
 * 用法：
 *   javac -d tools/verify/out \
 *       app/src/main/java/com/polaris/app/scan/IocEntry.java \
 *       app/src/main/java/com/polaris/app/scan/IocBinaryIndex.java \
 *       tools/verify/VerifyIocV2.java
 *   java -cp tools/verify/out VerifyIocV2 polar-region/iodb.bin
 *
 * 断言覆盖：v2 头字段、三张哈希表的首/中/尾抽样命中、跨表串扰、空表与 null 边界。
 */
public final class VerifyIocV2 {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String msg) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        File bin = new File(args[0]);
        RandomAccessFile raf = new RandomAccessFile(bin, "r");
        byte[] h = new byte[128];
        raf.readFully(h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);

        int fmt = bb.getInt(8);
        int entries = bb.getInt(24);
        int sha256Cnt = bb.getInt(28);
        int sha256Off = bb.getInt(44);
        int md5Cnt = bb.getInt(72);
        int md5Off = bb.getInt(76);
        int sha1Cnt = bb.getInt(80);
        System.out.println("header: fmt=" + fmt + " entries=" + entries
                + " sha256=" + sha256Cnt + "@" + sha256Off
                + " md5=" + md5Cnt + "@" + md5Off
                + " sha1=" + sha1Cnt);

        ok(fmt == 2, "formatVersion 应为 2，实际 " + fmt);
        ok(entries == 1627152, "entryCount 应为 1627152，实际 " + entries);
        ok(sha256Cnt == 110589, "sha256Count 应为 110589，实际 " + sha256Cnt);
        ok(md5Cnt == 1441791, "md5Count 应为 1441791，实际 " + md5Cnt);

        IocBinaryIndex idx = IocBinaryIndex.open(bin);
        System.out.println("open ok: v" + idx.getDbVersion() + " entries=" + idx.getEntryCount()
                + " updatedAt=" + idx.getUpdatedAt());

        // ---- SHA-256 表：首 / 中 / 尾 ----
        int[] sk = {0, sha256Cnt / 2, sha256Cnt - 1};
        for (int k : sk) {
            String hex = readHex(raf, sha256Off + (long) k * 36, 32);
            IocEntry e = idx.matchByHash(hex);
            ok(e != null, "sha256#" + k + " 应命中 " + hex.substring(0, 12));
            if (e != null) {
                ok(hex.equals(e.sha256), "sha256#" + k + " 回填不一致");
                ok(e.family != null && !e.family.isEmpty(), "sha256#" + k + " family 为空");
            }
        }

        // ---- MD5 表：首 / 中 / 尾（144 万条，此前全部查不到）----
        int[] mk = {0, md5Cnt / 2, md5Cnt - 1};
        for (int k : mk) {
            String hex = readHex(raf, md5Off + (long) k * 20, 16);
            IocEntry e = idx.matchByMd5(hex);
            ok(e != null, "md5#" + k + " 应命中 " + hex);
            if (e != null) {
                ok(hex.equals(e.md5), "md5#" + k + " 回填不一致 " + hex + " vs " + e.md5);
                ok("Local Hash Database".equals(e.family),
                        "md5#" + k + " family 应为 Local Hash Database，实际 " + e.family);
                ok("high".equals(e.severity), "md5#" + k + " severity=" + e.severity);
            }
        }

        // ---- MH-100K：Android 恶意包名 + 哈希（新增数据源，客户端首次能按包名命中）----
        IocEntry mh = idx.matchByPkg("com.wuba.jiazheng");
        ok(mh != null, "MH-100K 包名应命中");
        if (mh != null) {
            ok("MH-100K".equals(mh.family), "MH-100K family=" + mh.family);
            ok("high".equals(mh.severity), "MH-100K severity=" + mh.severity);
        }
        IocEntry mhHash = idx.matchByHash(
                "db802025f9ec474d79793ac2aac556d2b52162ebc493e2e70ad50b8f315eaa3f");
        ok(mhHash != null, "MH-100K sha256 应命中");
        if (mhHash != null) {
            ok("MH-100K".equals(mhHash.family), "MH-100K(sha) family=" + mhHash.family);
            ok("com.wuba.jiazheng".equals(mhHash.pkg), "MH-100K(sha) pkg=" + mhHash.pkg);
        }

        // ---- 跨表串扰 / 边界 ----
        ok(idx.matchByHash("2d75cc1bf8e57872781f9cd04a529256") == null,
                "32 位串不应进 sha256 表");
        ok(idx.matchByMd5("ff2af1bee37dabe5002e041af32916b011df23ae472a428f5b5056c984795d91") == null,
                "64 位串不应进 md5 表");
        ok(idx.matchByHash("0000000000000000000000000000000000000000000000000000000000000000") == null,
                "全零 sha256 应 miss（v2 已无零占位）");
        ok(idx.matchByMd5("00000000000000000000000000000000") == null, "全零 md5 应 miss");
        ok(idx.matchByMd5(null) == null, "null md5 应 miss");
        ok(idx.matchByHash(null) == null, "null sha256 应 miss");

        raf.close();
        idx.close();
        System.out.println("RESULT: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static String readHex(RandomAccessFile raf, long off, int len) throws Exception {
        raf.seek(off);
        byte[] b = new byte[len];
        raf.readFully(b);
        StringBuilder sb = new StringBuilder(len * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
