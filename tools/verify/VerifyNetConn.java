import com.polaris.app.scan.IocBinaryIndex;
import com.polaris.app.scan.IocEntry;
import com.polaris.app.scan.NetConnScanner;

import java.io.File;
import java.util.List;

/**
 * NetConnScanner 的独立验证（不依赖 Android）：解析规则 + 与真实 IOC 库的联动。
 *
 * 用法：
 *   javac -d tools/verify/out \
 *       app/src/main/java/com/polaris/app/scan/IocEntry.java \
 *       app/src/main/java/com/polaris/app/scan/IocBinaryIndex.java \
 *       app/src/main/java/com/polaris/app/scan/NetConnScanner.java \
 *       tools/verify/VerifyNetConn.java
 *   java -cp tools/verify/out VerifyNetConn polar-region/iodb.bin
 */
public final class VerifyNetConn {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String msg) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + msg);
        }
    }

    // 真实 /proc/net/tcp 的裁剪样本：表头 + 回环 + LISTEN + ESTABLISHED + SYN_SENT
    private static final String SAMPLE =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                    + "   0: 0100007F:1F90 0100007F:9C40 01 00000000:00000000 00:00000000 00000000 10145        0 1\n"
                    + "   1: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 2\n"
                    + "   2: 0A00000A:C350 A5A40001:01BB 01 00000000:00000000 00:00000000 00000000 10086        0 3\n"
                    + "   3: 0100007F:D431 0A00000B:0050 02 00000000:00000000 00:00000000 00000000 10087        0 4\n";

    public static void main(String[] args) throws Exception {
        List<NetConnScanner.Conn> conns = NetConnScanner.parse(SAMPLE);
        ok(conns.size() == 2, "应保留 2 条连接（回环/LISTEN 被过滤），实际 " + conns.size());

        NetConnScanner.Conn est = conns.get(0);
        ok("1.0.164.165".equals(est.ip), "EST ip=" + est.ip);
        ok(est.port == 443, "EST port=" + est.port);
        ok(est.uid == 10086, "EST uid=" + est.uid);
        ok(est.established, "EST established 应为 true");

        NetConnScanner.Conn syn = conns.get(1);
        ok("11.0.0.10".equals(syn.ip), "SYN ip=" + syn.ip);
        ok(syn.port == 80, "SYN port=" + syn.port);
        ok(syn.uid == 10087, "SYN uid=" + syn.uid);
        ok(!syn.established, "SYN established 应为 false");

        // 边界：null / 空 / 只有表头 / 乱行
        ok(NetConnScanner.parse(null).isEmpty(), "null 应返回空");
        ok(NetConnScanner.parse("").isEmpty(), "空串应返回空");
        ok(NetConnScanner.parse("sl local_address rem_address st\n").isEmpty(), "仅表头应返回空");
        ok(NetConnScanner.parse("garbage line\n").isEmpty(), "乱行应返回空");

        // 与真实 IOC 库联动：1.0.164.165 是 Blocklist.de 的恶意 IP
        if (args.length > 0) {
            IocBinaryIndex idx = IocBinaryIndex.open(new File(args[0]));
            IocEntry e = idx.matchByPkg(est.ip);
            ok(e != null, "恶意 IP " + est.ip + " 应在库中命中");
            if (e != null) {
                ok("Blocklist.de".equals(e.family), "family=" + e.family);
                ok("malicious-ip".equals(e.type), "type=" + e.type);
            }
            IocEntry miss = idx.matchByPkg("203.0.113.77");
            ok(miss == null, "非库中 IP 不应命中");
            idx.close();
        }

        System.out.println("RESULT: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
