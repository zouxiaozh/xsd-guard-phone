# -*- coding: utf-8 -*-
"""模拟盒子 adbd 的「授权弹窗」行为，测试守护 App 的「始终允许」检测（v2.5）。

状态文件 adb_auth_state.txt（与本脚本同目录，内容为下列关键字之一，可随时改写）：
  pending  : 弹窗挂着没人点 —— 提交公钥后不回包，等客户端自己超时
  once     : 用户只点了「允许」没勾选 —— 4 秒后放行，但密钥不记住
  always   : 用户勾选了「始终允许」并点允许 —— 4 秒后放行，并把密钥标记为已记住
  auto     : 设备自动放行（无弹窗）—— 0.2 秒后即放行，密钥不记住
  noauth   : 设备免鉴权 —— 收到 CNXN 直接回 CNXN（不走 AUTH）

trusted 标记写入 adb_auth_trusted.txt（存在即表示密钥已被记住）；
提交过的公钥会追加保存到 adb_auth_pubkeys.txt。
"""
import os
import socket
import struct
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "adb_auth_state.txt")
TRUSTED = os.path.join(HERE, "adb_auth_trusted.txt")
PUBKEYS = os.path.join(HERE, "adb_auth_pubkeys.txt")

A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
AUTH_TOKEN = 1
AUTH_SIGNATURE = 2
AUTH_RSA_PUBLIC = 3


def state():
    try:
        return open(STATE, encoding="utf-8").read().strip() or "pending"
    except OSError:
        return "pending"


def trusted():
    return os.path.exists(TRUSTED)


def msg(cmd, arg0, arg1, data=b""):
    s = (sum(data) & 0xFFFFFFFF) if data else 0
    return struct.pack("<6I", cmd, arg0, arg1, len(data), s, cmd ^ 0xFFFFFFFF) + data


def read_msg(sock):
    h = b""
    while len(h) < 24:
        n = sock.recv(24 - len(h))
        if not n:
            return None
        h += n
    cmd, arg0, arg1, ln, chk, magic = struct.unpack("<6I", h)
    data = b""
    while len(data) < ln:
        n = sock.recv(ln - len(data))
        if not n:
            return None
        data += n
    return cmd, arg0, arg1, data


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def serve(sock):
    peer = sock.getpeername()
    m = read_msg(sock)
    if not m:
        sock.close()
        return
    if state() == "noauth":
        sock.sendall(msg(A_CNXN, 0x01000000, 4096, b"device::\0"))
        log(peer, "noauth -> CNXN")
        sock.close()
        return
    # 走 AUTH：先发一个 TOKEN
    sock.sendall(msg(A_AUTH, AUTH_TOKEN, 0, b"T" * 20))
    m = read_msg(sock)
    if not m:
        sock.close()
        return
    cmd, arg0, _, data = m
    if cmd == A_AUTH and arg0 == AUTH_SIGNATURE:
        if trusted():
            sock.sendall(msg(A_CNXN, 0x01000000, 4096, b"device::\0"))
            log(peer, "signature + trusted -> CNXN")
            sock.close()
            return
        # 不认识这把钥匙：再要一次 TOKEN
        sock.sendall(msg(A_AUTH, AUTH_TOKEN, 0, b"T" * 20))
        m = read_msg(sock)
        if not m:
            sock.close()
            return
        cmd, arg0, _, data = m
    if not (cmd == A_AUTH and arg0 == AUTH_RSA_PUBLIC):
        log(peer, "unexpected", hex(cmd), arg0)
        sock.close()
        return
    with open(PUBKEYS, "ab") as f:
        f.write(data + b"\n")
    st = state()
    if st == "auto":
        time.sleep(0.2)
        sock.sendall(msg(A_CNXN, 0x01000000, 4096, b"device::\0"))
        log(peer, "pubkey -> auto grant CNXN（不记住）")
    elif st == "once":
        time.sleep(4)
        sock.sendall(msg(A_CNXN, 0x01000000, 4096, b"device::\0"))
        log(peer, "pubkey -> 允许但未勾选，CNXN（不记住）")
    elif st == "always":
        time.sleep(4)
        sock.sendall(msg(A_CNXN, 0x01000000, 4096, b"device::\0"))
        open(TRUSTED, "wb").close()
        log(peer, "pubkey -> 勾选了始终允许，CNXN（已记住）")
    else:  # pending：弹窗挂着，不回包
        log(peer, "pubkey -> 弹窗等待中，不回包")
        try:
            while sock.recv(4096):
                pass
        except OSError:
            pass
        log(peer, "客户端等不及断开了")
    sock.close()


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 5599))
    srv.listen(5)
    log("fake auth-adbd on 127.0.0.1:5599, state =", state(), "trusted =", trusted())
    while True:
        sock, _ = srv.accept()
        threading.Thread(target=serve, args=(sock,), daemon=True).start()


if __name__ == "__main__":
    main()
