package com.xsdanser.guard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ADB 线协议（明文 TCP）。包格式与常量对照 AOSP adb / AdbLib：
 *   command, arg0, arg1, data_length, data_check, magic(=command^0xffffffff), [data]
 * 头部 24 字节，全部小端；data_check 是 payload 字节的无符号累加和，不是 CRC。
 */
final class AdbProtocol {
    static final int HEADER = 24;

    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    static final int VERSION = 0x01000000;
    static final int MAX_DATA = 4096;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSA_PUBLIC = 3;

    static final byte[] CONNECT_PAYLOAD;
    static {
        byte[] p = null;
        try { p = "host::\0".getBytes("UTF-8"); }
        catch (Exception e) { p = new byte[] {'h','o','s','t',':',':',0}; }
        CONNECT_PAYLOAD = p;
    }

    static int checksum(byte[] data) {
        int sum = 0;
        if (data != null) for (int i = 0; i < data.length; i++) sum += data[i] & 0xff;
        return sum;
    }

    static byte[] message(int cmd, int arg0, int arg1, byte[] data) {
        int len = data == null ? 0 : data.length;
        ByteBuffer b = ByteBuffer.allocate(HEADER + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(len);
        b.putInt(checksum(data));
        b.putInt(cmd ^ 0xffffffff);
        if (len > 0) b.put(data);
        return b.array();
    }

    static final class Msg {
        int cmd, arg0, arg1, len, check, magic;
        byte[] data;

        boolean valid() {
            if (cmd != (magic ^ 0xffffffff)) return false;
            if (len != 0 && checksum(data) != check) return false;
            return true;
        }
    }

    static Msg read(InputStream in) throws IOException {
        byte[] h = new byte[HEADER];
        fill(in, h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        m.len = b.getInt();
        m.check = b.getInt();
        m.magic = b.getInt();
        if (m.len < 0 || m.len > 1024 * 1024) throw new IOException("ADB 包长度非法: " + m.len);
        m.data = new byte[m.len];
        if (m.len > 0) fill(in, m.data);
        return m;
    }

    private static void fill(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("ADB 连接被对端关闭");
            off += n;
        }
    }

    private AdbProtocol() {}
}
