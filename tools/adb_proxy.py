# -*- coding: utf-8 -*-
"""极简 TCP 转发：listen 0.0.0.0:5599 -> 192.168.1.100:5555（模拟可开关的 ADB 链路）"""
import socket
import threading
import sys

LISTEN = ("0.0.0.0", 5599)
TARGET = ("192.168.1.100", 5555)


def pipe(a, b):
    try:
        while True:
            d = a.recv(8192)
            if not d:
                break
            b.sendall(d)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LISTEN)
    srv.listen(16)
    sys.stderr.write("proxy up %s -> %s\n" % (LISTEN, TARGET))
    sys.stderr.flush()
    while True:
        try:
            c, _ = srv.accept()
        except Exception:
            break
        try:
            u = socket.create_connection(TARGET, timeout=8)
        except Exception:
            c.close()
            continue
        threading.Thread(target=pipe, args=(c, u), daemon=True).start()
        threading.Thread(target=pipe, args=(u, c), daemon=True).start()


if __name__ == "__main__":
    main()
