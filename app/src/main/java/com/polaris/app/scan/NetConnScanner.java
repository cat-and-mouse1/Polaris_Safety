package com.polaris.app.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 {@code /proc/net/tcp}，取出「已建立 / 正在建立」连接的远端 IPv4 与所属 uid。
 *
 * <p>为什么要它：威胁情报库里有 23,783 条恶意 IP（Blocklist.de / Emerging Threats /
 * Feodo Tracker），但它们一直躺在 pkg 字段里没有任何查询入口。要判断「哪个应用连了恶意 IP」，
 * Android 的 {@code NetworkStatsManager} 只给 uid 维度的流量汇总、拿不到对端地址，
 * 只能读内核连接表。
 *
 * <p>权限：整张表只有 root 或 shell（Shizuku）能读；普通应用只能看到自己的连接。
 * 因此该能力只在深层模式下启用。
 *
 * <p>行格式（空白分隔）：
 * <pre>
 * sl local_address rem_address st tx:rx tr:tm-&gt;when retrnsmt uid timeout inode ...
 *  2: 0A00000A:C350 A5A40001:01BB 01 ...                        uid=10086 ...
 * </pre>
 * rem_address 形如 {@code A5A40001:01BB}：8 位十六进制（小端序 IPv4）+ 4 位十六进制端口。
 */
public final class NetConnScanner {

    /** 一条连接：远端 IP、端口、发起它的 uid、是否已建立（否则为 SYN_SENT）。 */
    public static final class Conn {
        public final String ip;
        public final int port;
        public final int uid;
        public final boolean established;

        Conn(String ip, int port, int uid, boolean established) {
            this.ip = ip;
            this.port = port;
            this.uid = uid;
            this.established = established;
        }

        @Override
        public String toString() {
            return ip + ":" + port + " uid=" + uid + (established ? " EST" : " SYN");
        }
    }

    private NetConnScanner() {
    }

    /**
     * 纯解析，便于 JVM 单元测试。保留 st=01（ESTABLISHED）与 st=02（SYN_SENT），
     * 丢弃无远端（0.0.0.0）、回环（127.x）、IPv6 行。
     */
    public static List<Conn> parse(String text) {
        List<Conn> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("sl")) continue;
            String[] f = line.split("\\s+");
            if (f.length < 8) continue;
            String rem = f[2];
            String st = f[3];
            if (!"01".equals(st) && !"02".equals(st)) continue;
            int colon = rem.indexOf(':');
            if (colon != 8) continue;           // 仅 IPv4；IPv6 是 32 位 hex
            String ip;
            int port;
            int uid;
            try {
                ip = hexToIp(rem.substring(0, 8));
                port = Integer.parseInt(rem.substring(9), 16);
                uid = Integer.parseInt(f[7]);
            } catch (RuntimeException e) {
                continue;                       // 格式异常行直接跳过，不影响整表
            }
            if ("0.0.0.0".equals(ip) || ip.startsWith("127.")) continue;
            out.add(new Conn(ip, port, uid, "01".equals(st)));
        }
        return out;
    }

    /** 小端 8 位十六进制转点分十进制：{@code 0100007F} → {@code 127.0.0.1}。 */
    static String hexToIp(String h) {
        return Integer.parseInt(h.substring(6, 8), 16) + "."
                + Integer.parseInt(h.substring(4, 6), 16) + "."
                + Integer.parseInt(h.substring(2, 4), 16) + "."
                + Integer.parseInt(h.substring(0, 2), 16);
    }
}
