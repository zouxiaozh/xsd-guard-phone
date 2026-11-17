package com.xsdanser.guard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 极简 ADB 客户端：明文 TCP 5555，CNXN -> AUTH -> OPEN(shell:) -> WRTE/OKAY/CLSE。
 * 纯 Java SE，不依赖 android.*，因此同一份代码既能在 App 里跑，也能在 PC 上自检。
 * 不支持 Android 11+ 的 TLS 无线调试（A_STLS），遇到会给出明确错误。
 */
final class AdbClient {
    private final String host;
    private final int port;
    private final AdbCrypto crypto;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private boolean connected;
    /** 本次 connect 是否已把公钥提交给设备（即电视上出现了授权弹窗等待确认）。 */
    private boolean authDialogShown;
    /** 快速复核模式：设备不认识本密钥时立即报错，不再提交公钥（不会再弹授权窗）。 */
    private boolean failFastAuth;
    /** 公钥提交时刻；用于判断放行是「设备自动放行」还是「人工在电视上点了允许」。 */
    private long pubKeySentAt;
    /** 公钥提交 → 连接放行（CNXN）的毫秒数；-1 表示未走公钥授权路径（密钥已记住 / 免鉴权）。 */
    private long authGrantMillis = -1;
    /** 设备明确不认识本机密钥（即电视未勾选「始终允许」记住这把钥匙）时的错误标记。 */
    static final String ERR_NOT_TRUSTED = "adb_key_not_trusted";

    AdbClient(String host, int port, AdbCrypto crypto) {
        this.host = host;
        this.port = port;
        this.crypto = crypto;
    }

    void setFailFastAuth(boolean b) { failFastAuth = b; }

    boolean authDialogShown() { return authDialogShown; }

    long authGrantMillis() { return authGrantMillis; }

    void connect(int tcpTimeoutMs, int authTimeoutMs, int readTimeoutMs)
            throws IOException, java.security.GeneralSecurityException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), tcpTimeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(authTimeoutMs);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());

        send(AdbProtocol.A_CNXN, AdbProtocol.VERSION,
             AdbProtocol.MAX_DATA, AdbProtocol.CONNECT_PAYLOAD);

        boolean sentSignature = false;
        authDialogShown = false;
        authGrantMillis = -1;
        pubKeySentAt = 0;
        while (true) {
            AdbProtocol.Msg m = AdbProtocol.read(in);
            if (!m.valid()) throw new IOException("ADB 包校验失败");
            if (m.cmd == AdbProtocol.A_STLS) {
                throw new IOException("目标要求 TLS 无线调试配对（Android 11+），明文 5555 不支持");
            }
            if (m.cmd == AdbProtocol.A_CNXN) {
                connected = true;
                if (pubKeySentAt > 0)
                    authGrantMillis = System.currentTimeMillis() - pubKeySentAt;
                socket.setSoTimeout(readTimeoutMs);
                return;
            }
            if (m.cmd == AdbProtocol.A_AUTH && m.arg0 == AdbProtocol.AUTH_TOKEN) {
                if (!sentSignature) {
                    send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0,
                         crypto.signToken(m.data));
                    sentSignature = true;
                } else {
                    if (failFastAuth) throw new IOException(ERR_NOT_TRUSTED);
                    // 设备不认识这把公钥：提交公钥，盒子会弹「允许 USB 调试吗」，
                    // 用户勾选「始终允许」后设备会补发 A_CNXN。授权需要人操作，放长超时。
                    authDialogShown = true;
                    pubKeySentAt = System.currentTimeMillis();
                    socket.setSoTimeout(90000);
                    send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSA_PUBLIC, 0,
                         crypto.authPublicKey());
                }
                continue;
            }
            // 其它包（A_SYNC 等）在握手阶段忽略
        }
    }

    /** 执行一条 shell 命令并返回 stdout（plain shell: 服务，stderr 不保证合并）。 */
    String shell(String cmd) throws IOException {
        if (!connected) throw new IOException("尚未完成 ADB 握手");
        int localId = nextLocalId++;
        byte[] dest;
        try { dest = ("shell:" + cmd + "\0").getBytes("UTF-8"); }
        catch (Exception e) { dest = ("shell:" + cmd + "\0").getBytes(); }
        send(AdbProtocol.A_OPEN, localId, 0, dest);

        int remoteId = -1;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            AdbProtocol.Msg m = AdbProtocol.read(in);
            if (!m.valid()) continue;
            if (m.cmd == AdbProtocol.A_OKAY) {
                if (m.arg1 == localId || m.arg1 == 0) remoteId = m.arg0;
            } else if (m.cmd == AdbProtocol.A_WRTE) {
                if (m.arg1 == localId) {
                    if (remoteId < 0) remoteId = m.arg0;
                    if (m.data != null && m.data.length > 0) buf.write(m.data);
                    send(AdbProtocol.A_OKAY, localId, remoteId, null);
                }
            } else if (m.cmd == AdbProtocol.A_CLSE) {
                if (m.arg1 == localId || m.arg1 == 0 || m.arg0 == remoteId) {
                    send(AdbProtocol.A_CLSE, localId,
                         remoteId < 0 ? m.arg0 : remoteId, null);
                    break;
                }
            }
        }
        try { return new String(buf.toByteArray(), "UTF-8"); }
        catch (Exception e) { return new String(buf.toByteArray()); }
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        out.write(AdbProtocol.message(cmd, arg0, arg1, data));
        out.flush();
    }

    // ------------------------------------------------------------- 文件推送
    /**
     * ADB sync 推送：把本地输入流写入设备 remote 路径（用于安装 APK）。
     * 必须在 connect() 之后调用；仅新建一条 sync 流，推送完即关闭该流，
     * socket 仍保持连接，可继续调用 shell() 执行 pm install 等命令。
     *
     * 协议要点（对齐 adbd file_sync_service 行为）：
     * - 设备对我们每一个 WRTE（SEND/DATA/DONE/QUIT）只回 ADB 级 OKAY（流控确认），
     *   并不会逐块回 sync 级应答；
     * - 仅在收到 DONE 之后，设备才回一条 sync 级结果（"OKAY\0" 或 "FAIL..."）；
     * - 因此每块发完只等流控 OKAY 即可继续，DONE 之后才读结果，否则会一直等到超时。
     */
    void push(InputStream data, String remote) throws IOException {
        if (!connected) throw new IOException("尚未完成 ADB 握手");
        int localId = nextLocalId++;
        send(AdbProtocol.A_OPEN, localId, 0, SYNC_SERVICE);
        int remoteId = -1;

        // 等待设备确认 OPEN（ADB 级 OKAY）
        while (remoteId < 0) {
            AdbProtocol.Msg m = AdbProtocol.read(in);
            if (!m.valid()) continue;
            if (m.cmd == AdbProtocol.A_OKAY
                    && (m.arg1 == localId || m.arg1 == 0)) {
                remoteId = m.arg0;
            } else if (m.cmd == AdbProtocol.A_WRTE
                    && (m.arg1 == localId || m.arg1 == 0)) {
                remoteId = m.arg0;
                send(AdbProtocol.A_OKAY, localId, remoteId, null);
            } else if (m.cmd == AdbProtocol.A_CLSE) {
                throw new IOException("sync 通道被对端立即关闭");
            }
        }

        // SEND：路径 + ",权限" 拼进载荷（经典 sync 协议），帧头两字段：指令 + 载荷长度
        byte[] path = toUtf8(remote + ",0644");
        writeSync("SEND", path.length, path, localId, remoteId);
        remoteId = awaitFlowOk(localId, remoteId);

        // DATA：分块推送（每块不超过 ADB 单包上限，兼容老盒子 MAX_DATA）
        byte[] buf = new byte[SYNC_CHUNK];
        int n;
        while ((n = data.read(buf)) > 0) {
            byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
            writeSync("DATA", n, chunk, localId, remoteId);
            remoteId = awaitFlowOk(localId, remoteId);
        }

        // DONE：帧头第二字段即 mtime，无载荷
        writeSync("DONE", (int) (System.currentTimeMillis() / 1000), null, localId, remoteId);
        remoteId = awaitFlowOk(localId, remoteId);

        // 读 sync 级结果（设备此时才回一条 WRTE：OKAY / FAIL）
        readSyncStatus(localId, remoteId);

        // QUIT 结束 sync 会话；设备随后关闭该流（CLSE），短超时内不强求收到
        int oldTimeout = 30000;
        try { oldTimeout = socket.getSoTimeout(); } catch (Exception ignored) {}
        try { socket.setSoTimeout(3000); } catch (Exception ignored) {}
        writeSync("QUIT", 0, null, localId, remoteId);
        try {
            while (true) {
                AdbProtocol.Msg m = AdbProtocol.read(in);
                if (!m.valid()) continue;
                if (m.cmd == AdbProtocol.A_CLSE) break;
                if (m.cmd == AdbProtocol.A_WRTE)
                    send(AdbProtocol.A_OKAY, localId, m.arg0, null);
            }
        } catch (Exception ignored) {
            // 设备可能直接关流不回 CLSE，忽略
        } finally {
            try { socket.setSoTimeout(oldTimeout); } catch (Exception ignored) {}
        }
        // 我方也关闭该流（socket 不关，留待后续 shell 命令复用）
        send(AdbProtocol.A_CLSE, localId, remoteId, null);
    }

    private void writeSync(String id, int arg, byte[] data,
                           int localId, int remoteId) throws IOException {
        send(AdbProtocol.A_WRTE, localId, remoteId, syncMessage(id, arg, data));
    }

    /** 经典 sync 帧头 8 字节：4 字节指令 + 4 字节小端参数（SEND/DATA=载荷长度，DONE=mtime）。 */
    private static byte[] syncMessage(String id, int arg, byte[] data) {
        int dl = (data == null) ? 0 : data.length;
        ByteBuffer b = ByteBuffer.allocate(8 + dl).order(ByteOrder.LITTLE_ENDIAN);
        b.put(toUtf8(id));          // 4 字节 ASCII 指令：SEND / DATA / DONE / QUIT
        b.putInt(arg);
        if (dl > 0) b.put(data);
        return b.array();
    }

    /**
     * 等设备对我们 WRTE 的 ADB 级 OKAY（流控确认）。
     * 期间若意外收到 sync 级 WRTE（如设备提前报 FAIL），解析并抛错。
     */
    private int awaitFlowOk(int localId, int remoteId) throws IOException {
        while (true) {
            AdbProtocol.Msg m = AdbProtocol.read(in);
            if (!m.valid()) continue;
            if (m.cmd == AdbProtocol.A_OKAY
                    && (m.arg1 == localId || m.arg0 == remoteId)) {
                return (remoteId >= 0) ? remoteId : m.arg0;
            }
            if (m.cmd == AdbProtocol.A_WRTE
                    && (m.arg1 == localId || m.arg0 == remoteId)) {
                if (remoteId < 0) remoteId = m.arg0;
                if (m.data != null && m.data.length >= 4
                        && "FAIL".equals(new String(m.data, 0, 4, "US-ASCII"))) {
                    String msg = (m.data.length > 4)
                            ? new String(m.data, 4, m.data.length - 4, "UTF-8") : "unknown";
                    send(AdbProtocol.A_OKAY, localId, m.arg0, null);
                    throw new IOException("设备拒绝写入：" + msg);
                }
                send(AdbProtocol.A_OKAY, localId, m.arg0, null);
                continue;
            }
            if (m.cmd == AdbProtocol.A_CLSE) {
                throw new IOException("sync 通道被对端关闭");
            }
        }
    }

    /** 读取设备的 sync 级最终结果：OKAY 成功；FAIL（+4 字节消息长度+消息）抛出原因。 */
    private void readSyncStatus(int localId, int remoteId) throws IOException {
        while (true) {
            AdbProtocol.Msg m = AdbProtocol.read(in);
            if (!m.valid()) continue;
            if (m.cmd == AdbProtocol.A_OKAY) continue;   // 流控确认，继续等结果
            if (m.cmd == AdbProtocol.A_WRTE) {
                if (m.data == null || m.data.length < 4) {
                    send(AdbProtocol.A_OKAY, localId, m.arg0, null);
                    continue;
                }
                String sid = new String(m.data, 0, 4, "US-ASCII");
                if ("FAIL".equals(sid)) {
                    String msg = "unknown";
                    if (m.data.length > 8) {
                        int ml = (m.data[4] & 0xFF) | ((m.data[5] & 0xFF) << 8)
                                | ((m.data[6] & 0xFF) << 16) | ((m.data[7] & 0xFF) << 24);
                        if (ml > 0 && 8 + ml <= m.data.length) {
                            msg = new String(m.data, 8, ml, "UTF-8");
                        } else if (m.data.length > 4) {
                            msg = new String(m.data, 4, m.data.length - 4, "UTF-8");
                        }
                    } else if (m.data.length > 4) {
                        msg = new String(m.data, 4, m.data.length - 4, "UTF-8");
                    }
                    send(AdbProtocol.A_OKAY, localId, m.arg0, null);
                    throw new IOException("推送失败：" + msg);
                }
                send(AdbProtocol.A_OKAY, localId, m.arg0, null);
                return;   // "OKAY"
            }
            if (m.cmd == AdbProtocol.A_CLSE) {
                throw new IOException("sync 通道被对端关闭（未收到推送结果）");
            }
        }
    }

    private static byte[] toUtf8(String s) {
        try { return s.getBytes("UTF-8"); }
        catch (Exception e) { return s.getBytes(); }
    }

    private static final byte[] SYNC_SERVICE;
    /** sync DATA 单块上限：保证 12 字节头 + 数据 ≤ ADB MAX_DATA(4096)，兼容老盒子。 */
    private static final int SYNC_CHUNK = 4072;
    static {
        byte[] p = null;
        try { p = "sync:\0".getBytes("UTF-8"); }
        catch (Exception e) { p = new byte[] {'s','y','n','c',0}; }
        SYNC_SERVICE = p;
    }

    /** 调整读超时（例如 pm install 这类长命令前放宽，完成后恢复）。 */
    void setReadTimeout(int ms) {
        try { if (socket != null) socket.setSoTimeout(ms); } catch (Exception ignored) {}
    }

    void close() {
        connected = false;
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        in = null; out = null; socket = null;
    }

    boolean isConnected() { return connected; }
}
