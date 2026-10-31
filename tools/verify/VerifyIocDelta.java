import com.polaris.app.scan.IocBinaryIndex;
import com.polaris.app.scan.IocEntry;

import java.io.File;

/**
 * 增量包叠加层验证：真实 base（region v15）+ 真实生成的增量包（v15 → v16，1 条）。
 * 断言：头部字段、base 与增量的命中/不命中边界、明细字段回填。
 */
public class VerifyIocDelta {

    private static int passed = 0;

    private static void ok(boolean cond, String msg) {
        if (!cond) {
            System.out.println("FAIL: " + msg);
            System.exit(1);
        }
        passed++;
        System.out.println("  ok  " + msg);
    }

    public static void main(String[] args) throws Exception {
        File baseFile = new File(args[0]);
        File deltaFile = new File(args[1]);
        String testSha = args[2]; // 只存在于增量包里的 sha256
        String testMd5 = args[3]; // 同一条目的 md5

        try (IocBinaryIndex base = IocBinaryIndex.open(baseFile);
             IocBinaryIndex delta = IocBinaryIndex.open(deltaFile)) {

            ok(base.getFormatVersion() == 2, "base fmt=2");
            ok(delta.getFormatVersion() == 2, "delta fmt=2");
            ok(delta.getBaseVersion() == base.getDbVersion(),
                    "delta 基线 " + delta.getBaseVersion() + " == base 版本 " + base.getDbVersion());
            ok(delta.getDbVersion() > base.getDbVersion(),
                    "delta 目标 " + delta.getDbVersion() + " > base " + base.getDbVersion());
            ok(delta.getEntryCount() >= 1, "delta 条目数 " + delta.getEntryCount() + " >= 1");

            // base 不含测试条目，增量含（SHA-256 与 MD5 两条表都应命中）
            ok(base.matchByHash(testSha) == null, "base 不含测试条目");
            IocEntry hit = delta.matchByHash(testSha);
            ok(hit != null, "增量包命中测试条目(sha256)");
            if (hit != null) {
                ok("E2E-DELTA".equals(hit.family), "family=E2E-DELTA，实际 " + hit.family);
                ok("high".equals(hit.severity), "severity=high");
            }
            IocEntry hitMd5 = delta.matchByMd5(testMd5);
            ok(hitMd5 != null, "增量包命中测试条目(md5)");
            if (hitMd5 != null) {
                ok("E2E-DELTA".equals(hitMd5.family), "md5 命中 family=E2E-DELTA");
            }

            // base 原有条目不受影响
            IocEntry mh = base.matchByPkg("com.wuba.jiazheng");
            ok(mh != null, "base 的 MH-100K 包名仍命中");
            // 增量包不含 MH-100K 条目（差集正确性）
            ok(delta.matchByPkg("com.wuba.jiazheng") == null, "增量包不含 base 已有条目");
        }

        System.out.println("\nALL CHECKS PASSED (" + passed + ")");
    }
}
