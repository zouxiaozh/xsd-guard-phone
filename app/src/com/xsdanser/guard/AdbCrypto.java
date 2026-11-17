package com.xsdanser.guard;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;

/**
 * ADB 的 2048 位 RSA 密钥与 AUTH 认证。
 *
 * 注意：ADB 不是「对 token 做 SHA1withRSA 签名」，而是把设备发来的 20 字节
 * token 直接当作 SHA1 摘要，手工拼 PKCS#1 v1.5 块后用私钥做一次 RSA 运算。
 * 因此这里用 Cipher(RSA/ECB/NoPadding) + 固定前缀，与 AOSP adb_auth 一致。
 */
final class AdbCrypto {
    static final int KEY_BITS = 2048;
    static final int KEY_BYTES = 256;
    static final int WORDS = 64;

    private final PrivateKey priv;
    private final byte[] pubPayload;   // base64 + " unknown@unknown" + NUL
    private final byte[] privDer;

    private AdbCrypto(PrivateKey priv, byte[] privDer, byte[] pubPayload) {
        this.priv = priv;
        this.privDer = privDer;
        this.pubPayload = pubPayload;
    }

    /** 生成新密钥对（首次运行时调用；盒子上会弹一次「允许 USB 调试吗」）。 */
    static AdbCrypto generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(KEY_BITS);
        KeyPair kp = g.genKeyPair();
        byte[] der = kp.getPrivate().getEncoded();
        byte[] pub = publicKeyPayload((RSAPublicKey) kp.getPublic());
        return new AdbCrypto(kp.getPrivate(), der, pub);
    }

    /**
     * 用已有的 adb 密钥对构造（PC 上的 ~/.android/adbkey 已授权时可零弹窗）。
     * privDer 是 PKCS#8 DER；pubLine 是 adbkey.pub 里的整行 base64 + 注释。
     */
    static AdbCrypto fromParts(byte[] privDer, String pubLine) throws GeneralSecurityException {
        KeyFactory f = KeyFactory.getInstance("RSA");
        PrivateKey pk = f.generatePrivate(new PKCS8EncodedKeySpec(privDer));
        String line = pubLine.trim();
        byte[] payload = null;
        try { payload = (line + "\0").getBytes("UTF-8"); }
        catch (Exception e) { payload = (line + "\0").getBytes(); }
        return new AdbCrypto(pk, privDer, payload);
    }

    byte[] privateDer() { return privDer; }

    String publicLine() {
        String s = new String(pubPayload, 0, pubPayload.length);
        if (s.endsWith("\0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    byte[] authPublicKey() { return pubPayload; }

    byte[] signToken(byte[] token) throws GeneralSecurityException {
        if (token == null || token.length != 20)
            throw new GeneralSecurityException("AUTH token 长度应为 20，实际 " + (token == null ? -1 : token.length));
        // PKCS#1 v1.5: 00 01 FF*218 00 || DigestInfo(SHA1) || token(20) = 256 字节
        byte[] em = new byte[KEY_BYTES];
        em[0] = 0;
        em[1] = 1;
        Arrays.fill(em, 2, 220, (byte) 0xff);
        em[220] = 0;
        byte[] di = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05,
                      0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05,
                      0x00, 0x04, 0x14 };
        System.arraycopy(di, 0, em, 221, di.length);
        System.arraycopy(token, 0, em, 236, 20);
        Cipher c;
        try { c = Cipher.getInstance("RSA/ECB/NoPadding"); }
        catch (GeneralSecurityException e) { c = Cipher.getInstance("RSA/None/NoPadding"); }
        c.init(Cipher.ENCRYPT_MODE, priv);
        return c.doFinal(em);
    }

    /** 把标准 RSA 公钥编码成 ADB 的 RSAPublicKey 结构（524 字节，小端）。 */
    static byte[] publicKeyPayload(RSAPublicKey pk) throws GeneralSecurityException {
        String s;
        try { s = (adbPublicKeyBase64(pk) + " unknown@unknown" + '\0'); }
        catch (Exception e) { throw new GeneralSecurityException(e); }
        try { return s.getBytes("UTF-8"); }
        catch (Exception e) { return s.getBytes(); }
    }

    /** 供 PC 端自检用：编码结果应与 adbkey.pub 的 base64 部分逐字一致。 */
    static String adbPublicKeyBase64(RSAPublicKey pk) throws GeneralSecurityException {
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger r = BigInteger.ZERO.setBit(WORDS * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger rem = n.remainder(r32);
        BigInteger n0inv = rem.modInverse(r32);
        int[] myN = new int[WORDS];
        int[] myRr = new int[WORDS];
        BigInteger nn = n;
        BigInteger rv = rr;
        for (int i = 0; i < WORDS; i++) {
            BigInteger[] q = rv.divideAndRemainder(r32);
            rv = q[0]; myRr[i] = q[1].intValue();
            q = nn.divideAndRemainder(r32);
            nn = q[0]; myN[i] = q[1].intValue();
        }
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(WORDS);
        b.putInt(n0inv.negate().intValue());
        for (int i = 0; i < WORDS; i++) b.putInt(myN[i]);
        for (int i = 0; i < WORDS; i++) b.putInt(myRr[i]);
        b.putInt(pk.getPublicExponent().intValue());
        return B64.encode(b.array());
    }
}
