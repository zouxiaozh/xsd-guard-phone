# -*- coding: utf-8 -*-
"""测试用迷你 adbd：在本机 TCP 端口模拟 Android 调试守护进程。

用途：让手机 App（或模拟器里的 App）在不动真实盒子、不受电脑 adb server
抢占的前提下，完整跑一遍 A_CNXN / A_AUTH / A_OPEN / A_WRTE / A_CLSE。

启动：
  python fake_adbd.py --port 5556 --init-dead
  python fake_adbd.py --port 5557 --init-dead --rom custom --fail-first-am
  python fake_adbd.py --port 5558 --init-dead --rom old

第一次判活返回「服务不在」，收到 am start 后改为「服务在」，
因此 App 会走完「检测消失 -> 静默拉起 -> 复核已恢复」整条链路。
--rom 可模拟 AOSP / 定制 ROM / 老电视的不同 dumpsys 输出，
--fail-first-am 让第一种拉起命令返回错误，验证多形态兜底。
"""
import argparse
import base64
import os
import socketserver
import struct
import threading
import time

HEADER = 24
A_CNXN = 0x4e584e43
A_AUTH = 0x48545541
A_OPEN = 0x4e45504f
A_OKAY = 0x59414b4f
A_CLSE = 0x45534c43
A_WRTE = 0x45545257
VERSION = 0x01000000
MAX_DATA = 4096
PKG = "com.xsdanser.tv"

STATE = {"alive": True, "lock": threading.Lock(), "launches": 0,
         "rom": "aosp", "fail_first_am": False}
PUB_N = None
PUB_E = None
# 联调开关（默认关闭，不影响原有测试）：免鉴权握手 + 模拟「未安装学时兑」
NOAUTH = False
NO_INSTALL = False


def load_pubkey():
    global PUB_N, PUB_E
    p = os.path.join(os.path.expanduser("~"), ".android", "adbkey.pub")
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        line = f.read().strip().split()[0]
    raw = base64.b64decode(line + "==")
    PUB_N = int.from_bytes(raw[8:8 + 256], "little")
    PUB_E = struct.unpack_from("<I", raw, 520)[0]
    print("[fake] 已载入 adbkey.pub，RSA 模数 %d bit" % PUB_N.bit_length(), flush=True)


def pkt(cmd, a0, a1, data=b""):
    chk = sum(data) & 0xffffffff
    return struct.pack("<IIIIII", cmd, a0, a1, len(data), chk,
                       cmd ^ 0xffffffff) + data


def recv_exact(f, n):
    b = b""
    while len(b) < n:
        x = f.read(n - len(b))
        if not x:
            raise EOFError("client closed")
        b += x
    return b


def read_msg(f):
    h = recv_exact(f, HEADER)
    cmd, a0, a1, ln, chk, magic = struct.unpack("<IIIIII", h)
    data = recv_exact(f, ln) if ln else b""
    return cmd, a0, a1, data


def verify_signature(sig, token):
    if PUB_N is None:
        return True
    em = pow(int.from_bytes(sig, "big"), PUB_E, PUB_N).to_bytes(256, "big")
    return (em[0:2] == b"\x00\x01" and em[220] == 0
            and em[221:236] == bytes([0x30, 0x21, 0x30, 0x09, 0x06, 0x05,
                                      0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05,
                                      0x00, 0x04, 0x14])
            and em[236:256] == token)


def respond(cmd):
    with STATE["lock"]:
        alive = STATE["alive"]
        rom = STATE["rom"]
        fail_first = STATE["fail_first_am"]
        launches = STATE["launches"]
    if cmd.startswith("pm path"):
        if NO_INSTALL:
            return b"\n"                     # 模拟电视未安装学时兑（无 package: 行）
        return b"package:/data/app/" + PKG.encode() + b"-2/base.apk\n"

    if cmd.startswith("dumpsys activity services"):
        if rom == "aosp":
            head = b"ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n"
            if alive:
                return head + b"  * ServiceRecord{abc u0 " + PKG.encode() + b"/.QuizOverlayService}\n"
            return head + b"  (nothing)\n"
        # 定制 ROM：不带标准头，逼客户端降级到 processes
        return b"CUSTOM ROM SERVICE DUMP v2 (no standard header)\n"

    if cmd.startswith("dumpsys activity processes"):
        head = b"ACTIVITY MANAGER PROCESSES (dumpsys activity processes)\n"
        if rom in ("custom", "aosp"):
            if alive:
                return head + b"  Proc # 3: prcp  F/A/T " + PKG.encode() + b"/u0a65\n"
            return head + b"  Proc # 0: fore  com.ktcp.video/u0a12\n"
        return b"OLD ROM: unsupported\n"

    if cmd.startswith("dumpsys meminfo"):
        if rom == "old":
            return b"OLD ROM: meminfo unsupported\n"
        if alive and rom == "mem":
            return b"** MEMINFO in pid 16604 [" + PKG.encode() + b"] **\n  TOTAL 12345\n"
        return b"No process found for: " + PKG.encode() + b"\n"

    if cmd.startswith("ps -A") or cmd.startswith("ps"):
        if rom == "old":
            base = (b"USER      PID  PPID  NAME\n"
                    b"root      1    0     /init\n"
                    b"root      880  1     zygote64\n")
            if alive:
                return base + b"u0_a65   16604 880  " + PKG.encode() + b"\n"
            return base + b"u0_a12   9999  880  com.ktcp.video\n"
        return b""

    if cmd.startswith("am start") or cmd.startswith("monkey"):
        if fail_first and launches == 0 and cmd.startswith("am start -n " + PKG + "/.MainActivity"):
            with STATE["lock"]:
                STATE["launches"] += 1
            print("[fake] 第 1 种拉起命令故意返回 Error（验证兜底）", flush=True)
            return b"Error type 3\nError: Activity class {x/x} does not exist.\n"
        with STATE["lock"]:
            STATE["alive"] = True
            STATE["launches"] += 1
            n = STATE["launches"]
        print("[fake] 收到静默拉起 #%d：%s" % (n, cmd[:100]), flush=True)
        return b"Starting: Intent { cmp=" + PKG.encode() + b"/.MainActivity (has extras) }\n"
    return b"ok\n"


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        f = self.request.makefile("rb")
        try:
            cmd, a0, a1, data = read_msg(f)
            if cmd != A_CNXN:
                print("[fake] 期望 A_CNXN，收到 %08x" % cmd, flush=True)
                return
            if NOAUTH:
                # 联调用：免鉴权，直接回 CNXN，省掉授权弹窗等待
                self.request.sendall(pkt(A_CNXN, VERSION, MAX_DATA, b"device::\0"))
                print("[fake] 免鉴权握手完成", flush=True)
                self.serve_shell(f)
                return
            token = os.urandom(20)
            self.request.sendall(pkt(A_AUTH, 1, 0, token))
            cmd, a0, a1, data = read_msg(f)
            if cmd != A_AUTH:
                return
            if a0 == 2:
                ok = verify_signature(data, token)
                print("[fake] AUTH 签名校验 %s" % ("PASS" if ok else "FAIL"), flush=True)
                if not ok:
                    return
            elif a0 == 3:
                print("[fake] 收到客户端公钥（新密钥授权路径）", flush=True)
            else:
                return
            self.request.sendall(pkt(A_CNXN, VERSION, MAX_DATA, b"device::\0"))
            print("[fake] 握手完成，客户端已连接", flush=True)
            self.serve_shell(f)
        except Exception as e:
            print("[fake] 连接结束: %s" % e, flush=True)
        finally:
            try: f.close()
            except Exception: pass

    def serve_shell(self, f):
        """A_OPEN / WRTE / CLSE 交互：免鉴权与正常握手两条路径共用。"""
        dev_id = 7
        while True:
            cmd, a0, a1, data = read_msg(f)
            if cmd == A_OPEN:
                dest = data.rstrip(b"\0").decode("utf-8", "replace")
                text = dest[len("shell:"):] if dest.startswith("shell:") else dest
                print("[fake] shell: %s" % text[:90], flush=True)
                out = respond(text)
                self.request.sendall(pkt(A_OKAY, dev_id, a0))
                # 单包写出，必要时分片
                for i in range(0, len(out), 1024):
                    self.request.sendall(pkt(A_WRTE, dev_id, a0, out[i:i + 1024]))
                    c2, b0, b1, _ = read_msg(f)
                    if c2 != A_OKAY:
                        return
                self.request.sendall(pkt(A_CLSE, dev_id, a0))
                read_msg(f)
                dev_id += 1
            elif cmd == A_CLSE:
                break


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    global NOAUTH, NO_INSTALL
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5556)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--init-dead", action="store_true")
    ap.add_argument("--rom", choices=["aosp", "custom", "old", "mem"], default="aosp")
    ap.add_argument("--fail-first-am", action="store_true")
    ap.add_argument("--noauth", action="store_true",
                    help="联调用：跳过 AUTH 鉴权，直接回 CNXN")
    ap.add_argument("--no-install", action="store_true",
                    help="联调用：pm path 返回空，模拟电视未安装学时兑")
    a = ap.parse_args()
    NOAUTH = a.noauth
    NO_INSTALL = a.no_install
    if not NOAUTH:
        load_pubkey()
    with STATE["lock"]:
        STATE["alive"] = not a.init_dead
        STATE["rom"] = a.rom
        STATE["fail_first_am"] = a.fail_first_am
    srv = Server((a.host, a.port), Handler)
    print("[fake] 监听 %s:%d，ROM=%s，初始服务状态=%s，首命令失败=%s" %
          (a.host, a.port, a.rom, "在" if STATE["alive"] else "不在",
           a.fail_first_am), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
