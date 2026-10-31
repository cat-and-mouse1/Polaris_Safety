import com.polaris.app.scan.IocBinaryIndex;
import com.polaris.app.scan.IocEntry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * iodb.bin 读取器的独立验证程序（不依赖 Android/AGP，可直接 javac + java 运行）。
 *
 * 用法：
 *   javac -d tools/verify/out \
 *       app/src/main/java/com/polaris/app/scan/IocEntry.java \
 *       app/src/main/java/com/polaris/app/scan/IocBinaryIndex.java \
 *       tools/verify/VerifyIocBin.java
 *   java -cp tools/verify/out VerifyIocBin app/src/test/resources/iodb_sample.bin
 *
 * 样本由 tools/ioc_pack.py 从固定数据集（unit-0 … unit-4999）生成，断言覆盖：
 * 头部元信息、哈希命中/未命中、包名命中（含碰撞确认）、URL 大小写不敏感匹配。
 */
public final class VerifyIocBin {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File bin = new File(args.length > 0 ? args[0] : "app/src/test/resources/iodb_sample.bin");
        IocBinaryIndex idx = IocBinaryIndex.open(bin);
        System.out.println("open ok: v" + idx.getDbVersion()
                + " entries=" + idx.getEntryCount()
                + " updatedAt=" + idx.getUpdatedAt()
                + " source=" + idx.getSource());

        check("dbVersion", idx.getDbVersion(), 21);
        check("entryCount", idx.getEntryCount(), 5000);
        check("updatedAt", idx.getUpdatedAt(), "2026-10-30T22:00:00Z");
        check("source", idx.getSource(), "unit-test");

        // 哈希命中：unit-0（i%3==0 有包名；i%6==0 Local Hash Database；i%4==0 high）
        IocEntry e0 = idx.matchByHash(sha256("unit-0"));
        check("unit-0 pkg", e0 == null ? "<miss>" : e0.pkg, "com.evil.app0");
        check("unit-0 family", e0 == null ? "<miss>" : e0.family, "Local Hash Database");
        check("unit-0 severity", e0 == null ? "<miss>" : e0.severity, "high");
        check("unit-0 desc", e0 == null ? "<miss>" : e0.desc,
                "Malicious hash from local database");

        // 字段映射：unit-3 → AgentTesla / critical；unit-7 → 带 URL
        IocEntry e3 = idx.matchByHash(sha256("unit-3"));
        check("unit-3 family", e3 == null ? "<miss>" : e3.family, "Mirai");
        check("unit-3 severity", e3 == null ? "<miss>" : e3.severity, "critical");
        IocEntry e7 = idx.matchByHash(sha256("unit-7"));
        check("unit-7 url", e7 == null ? "<miss>" : e7.url, "http://m7.example/x.apk");

        // 未命中
        check("random hash", idx.matchByHash("00".repeat(32)) == null, true);
        check("bad hash", idx.matchByHash("not-a-hash") == null, true);
        check("null hash", idx.matchByHash(null) == null, true);

        // 包名
        IocEntry ep = idx.matchByPkg("com.evil.app42");
        check("pkg hit", ep == null ? "<miss>" : ep.pkg, "com.evil.app42");
        check("pkg miss", idx.matchByPkg("com.evil.not.exists") == null, true);

        // URL（大小写不敏感）
        check("url hit", idx.matchByUrl("http://m7.example/x.apk") == null, false);
        check("url upper", idx.matchByUrl("HTTP://M7.EXAMPLE/X.APK") == null, false);
        check("url miss", idx.matchByUrl("http://unknown.example/x.apk") == null, true);

        idx.close();

        if (failed == 0) System.out.println("\nALL CHECKS PASSED");
        else {
            System.out.println("\n" + failed + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (!ok) failed++;
        System.out.printf("%-16s %s  (actual=%s, expected=%s)%n",
                name, ok ? "OK  " : "FAIL", actual, expected);
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
