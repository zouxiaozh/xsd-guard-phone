package com.xsdanser.guard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/** PC 端自检：复用 App 的 ADB 核心，连真实小米盒子验证握手与 shell。 */
public class AdbCliTest {
    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0] : "192.168.1.100:5555";
        int colon = target.lastIndexOf(':');
        String host = colon > 0 ? target.substring(0, colon) : target;
        int port = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 5555;
        File dir = new File(System.getProperty("user.home"), ".android");
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");

        AdbCrypto crypto;
        if (priv.isFile() && pub.isFile()) {
            byte[] der = pemToDer(read(priv));
            String pubLine = read(pub).trim();
            crypto = AdbCrypto.fromParts(der, pubLine);
            // 关键自检：我们用同一套编码器算出的公钥，必须与系统 adbkey.pub 完全一致
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) pk;
            RSAPublicKey reconstructed = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
            String computed = AdbCrypto.adbPublicKeyBase64(reconstructed);
            String expected = pubLine.split("\\s+")[0];
            System.out.println("PUBKEY-ENCODE " + (computed.equals(expected) ? "MATCH" : "MISMATCH"));
            if (!computed.equals(expected)) {
                System.out.println("  computed=" + computed.substring(0, 40) + "...");
                System.out.println("  expected=" + expected.substring(0, 40) + "...");
                System.exit(2);
            }
            System.out.println("KEY 使用已有 adbkey（盒子应已授权，不会弹窗）");
        } else {
            crypto = AdbCrypto.generate();
            System.out.println("KEY 未找到 ~/.android/adbkey，已生成临时密钥；"
                             + "盒子会弹授权窗口，请点「始终允许」");
        }

        System.out.println("CONNECT " + host + ":" + port);
        AdbClient c = new AdbClient(host, port, crypto);
        try {
            c.connect(5000, 25000, 12000);
            System.out.println("HANDSHAKE OK");
            String[] cmds = {
                "getprop ro.product.model",
                "pm path com.xsdanser.tv",
                "dumpsys activity services com.xsdanser.tv"
            };
            for (int i = 0; i < cmds.length; i++) {
                String out = c.shell(cmds[i]);
                String flat = out.replace("\r", " ").replace("\n", " | ").trim();
                if (flat.length() > 240) flat = flat.substring(0, 240) + "...";
                System.out.println("SHELL[" + cmds[i] + "] => " + flat);
                if (i == 2) {
                    System.out.println("PROBE " + (out.contains("ServiceRecord")
                        ? "PASS (服务在)" : "FAIL (未见 ServiceRecord)"));
                }
            }
            // v2.14：三态安装判定的真机校验 —— 已装 / 未装 / 不存在都要分得清
            String pmOut = c.shell("pm path com.xsdanser.tv");
            int st1 = TvCompat.installState(c, "com.xsdanser.tv", pmOut);
            int st2 = TvCompat.installState(c, "com.xsdanser.not.exist", null);
            System.out.println("INSTALL-STATE 已装包=" + st1 + "（应为 1） 不存在包=" + st2 + "（应为 -1）");
            System.out.println("INSTALL-STATE " + (st1 == 1 && st2 == -1 ? "PASS" : "FAIL"));
        } finally {
            c.close();
        }
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    private static byte[] pemToDer(String pem) throws Exception {
        StringBuilder b = new StringBuilder();
        String[] lines = pem.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.length() == 0 || l.startsWith("-----")) continue;
            b.append(l);
        }
        return B64.decode(b.toString());
    }
}
