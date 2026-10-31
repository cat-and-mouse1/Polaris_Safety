import java.nio.file.*;
import java.io.InputStream;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 病毒库签名链路 JVM 验证：真实公钥 + 真实 .sig + 真实 bin。
 * 断言：验签通过；篡改一个字节后必须失败；错误公钥必须失败。
 * （与 IocDatabase.verifySigned 相同的 java.security API 路径）
 */
public class VerifySign {

    static final String PUBKEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfRSiv/WxW030iTtxrBb5TG6rUvELKTl1ALi/GNlkLP7+biH0BJ1U21dA30W4xhWBylXGQ3OMGdz1PtBJe1386Q==";

    static int passed = 0;

    static void ok(boolean c, String m) {
        if (!c) { System.out.println("FAIL: " + m); System.exit(1); }
        passed++;
        System.out.println("  ok  " + m);
    }

    static boolean verify(Path f, byte[] sig, PublicKey key) throws Exception {
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(key);
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) v.update(buf, 0, n);
        }
        return v.verify(sig);
    }

    static PublicKey pubkey(String b64) throws Exception {
        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    public static void main(String[] args) throws Exception {
        Path bin = Paths.get(args[0]);
        Path sig = Paths.get(args[0] + ".sig");
        PublicKey key = pubkey(PUBKEY_B64);
        byte[] hex = Files.readAllBytes(sig);
        byte[] sigHex = hexDecode(new String(hex).trim());
        ok(verify(bin, sigHex, key), "真实 bin + 真实 sig 验签通过");

        // 篡改 bin 一个字节 → 必须失败
        byte[] data = Files.readAllBytes(bin);
        data[data.length / 2] ^= 0x01;
        Path tampered = Paths.get(args[0] + ".tampered");
        Files.write(tampered, data);
        ok(!verify(tampered, sigHex, key), "篡改 bin 一个字节 → 验签失败");
        Files.delete(tampered);

        // 错误公钥 → 必须失败
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        PublicKey wrong = kpg.generateKeyPair().getPublic();
        ok(!verify(bin, sigHex, wrong), "错误公钥 → 验签失败");

        System.out.println("\nALL CHECKS PASSED (" + passed + ")");
    }

    static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
