import com.polaris.app.scan.IocBinaryIndex;
import com.polaris.app.scan.IocEntry;
import com.polaris.app.scan.NetConnScanner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 用真实的 /proc/net/tcp 抓取结果验证解析 + 查库链路（宿主机运行，代码与 App 内一致）。
 *
 * 用法：
 *   java -cp tools/verify/out VerifyTableFile _e2e/tcp_real.txt polar-region/iodb.bin
 */
public final class VerifyTableFile {

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        List<NetConnScanner.Conn> conns = NetConnScanner.parse(text);
        System.out.println("解析到 " + conns.size() + " 条连接：");
        for (NetConnScanner.Conn c : conns) {
            System.out.println("  " + c);
        }
        int hit = 0, miss = 0;
        if (args.length > 1) {
            IocBinaryIndex idx = IocBinaryIndex.open(new File(args[1]));
            for (NetConnScanner.Conn c : conns) {
                IocEntry e = idx.matchByPkg(c.ip);
                if (e == null) {
                    miss++;
                    System.out.println("  查库 " + c.ip + " -> 未命中");
                } else {
                    hit++;
                    System.out.println("  查库 " + c.ip + " -> 命中 " + e.family + " / " + e.type
                            + " / " + e.severity);
                }
            }
            idx.close();
        }
        System.out.println("RESULT: 连接 " + conns.size() + " 条，命中 " + hit + "，未命中 " + miss);
    }
}
