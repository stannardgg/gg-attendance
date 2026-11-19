"""最小 WebSocket 客户端（只读文本帧）。

为什么不用 `websockets` 包：验收栈的镜像里没有它，而为了一个断言往业务镜像
里加依赖不值得。大屏通道只推文本帧、服务端不发分片、我们只读不写，
RFC 6455 的握手 + 解帧 60 行就够。

用途：16.3 场景 1「教师大屏 1 秒内 +1」和场景 9「教师端 1 秒内收到提醒」，
都需要真的收到推送才算证据 —— 轮询接口证明不了"1 秒内"。
"""
from __future__ import annotations

import base64
import json
import os
import socket
import ssl
import struct
import time
import urllib.parse


class WsError(AssertionError):
    pass


class LiveChannel:
    """连到 /api/v1/admin/sessions/{sid}/live?token=... 并收集事件。"""

    def __init__(self, base: str, session_id: int, token: str, timeout: float = 5.0):
        u = urllib.parse.urlparse(base)
        self.secure = u.scheme == "https"
        self.host = u.hostname
        self.port = u.port or (443 if self.secure else 80)
        self.path = f"/api/v1/admin/sessions/{session_id}/live?token={token}"
        self.sock = None
        self.buf = b""
        self.events: list[dict] = []
        self.deadline = time.time() + timeout

        raw = socket.create_connection((self.host, self.port), timeout=timeout)
        if self.secure:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            raw = ctx.wrap_socket(raw, server_hostname=self.host)
        self.sock = raw
        self.sock.settimeout(timeout)
        self._handshake()

    def _handshake(self) -> None:
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {self.host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Origin: https://" + str(self.host) + "\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WsError("WS 握手期间连接被关闭")
            self.buf += chunk
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status_line = head.split(b"\r\n", 1)[0].decode(errors="replace")
        if "101" not in status_line:
            raise WsError(f"WS 握手失败：{status_line}")

    # ---- 解帧 ----
    def _need(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WsError("连接已关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self, timeout: float | None = None) -> dict | None:
        """读一条文本帧；超时返回 None。"""
        end = time.time() + (self.deadline - time.time() if timeout is None else timeout)
        while True:
            if time.time() > end:
                return None
            if len(self.buf) < 2:
                self.sock.settimeout(max(0.05, min(0.5, end - time.time())))
                try:
                    chunk = self.sock.recv(65536)
                except (socket.timeout, TimeoutError):
                    continue
                except ssl.SSLWantReadError:
                    continue
                if not chunk:
                    return None
                self.buf += chunk
                continue
            b0, b1 = self.buf[0], self.buf[1]
            opcode = b0 & 0x0F
            masked = b1 & 0x80
            length = b1 & 0x7F
            off = 2
            if length == 126:
                if len(self.buf) < 4:
                    continue
                length = struct.unpack(">H", self.buf[2:4])[0]
                off = 4
            elif length == 127:
                if len(self.buf) < 10:
                    continue
                length = struct.unpack(">Q", self.buf[2:10])[0]
                off = 10
            if masked:
                off += 4
            if len(self.buf) < off + length:
                continue
            payload = self.buf[off:off + length]
            self.buf = self.buf[off + length:]
            if opcode == 0x8:
                return None
            if opcode in (0x9, 0xA):
                continue
            try:
                evt = json.loads(payload.decode())
            except Exception:                                    # noqa: BLE001
                continue
            self.events.append(evt)
            if evt.get("type") != "EVT_CONNECTED":
                return evt

    def wait_for(self, event_type: str, timeout: float = 1.5) -> dict | None:
        """等到指定类型的事件，超时返回 None（用于断言「1 秒内」）。"""
        end = time.time() + timeout
        for e in self.events:
            if e.get("type") == event_type:
                return e
        while time.time() < end:
            evt = self.recv(timeout=end - time.time())
            if evt is None:
                break
            if evt.get("type") == event_type:
                return evt
        return None

    def close(self) -> None:
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
