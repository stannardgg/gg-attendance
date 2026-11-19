"""极简 HTTP 客户端：只用标准库。

为什么不用 requests：验收套件跑在 `fa/face` 镜像里（那里已经有 cv2/numpy/pytest），
再加一个依赖就要重打镜像。urllib 足够，multipart 自己拼 15 行。
"""
from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.request
import uuid


class ApiError(AssertionError):
    """期望 200 却拿到别的状态码。"""


class Resp:
    def __init__(self, status: int, raw: bytes):
        self.status = status
        self.raw = raw
        try:
            self.body = json.loads(raw.decode("utf-8"))
        except Exception:                                        # noqa: BLE001
            self.body = {"_raw": raw.decode("utf-8", "replace")[:500]}

    @property
    def code(self) -> str | None:
        return self.body.get("code")

    @property
    def data(self):
        return self.body.get("data")

    @property
    def message(self) -> str:
        return self.body.get("message") or ""

    def __repr__(self) -> str:
        return f"<{self.status} {self.code} {self.body}>"


def _multipart(fields: dict[str, str], files: dict[str, tuple[str, bytes, str]]):
    boundary = "----fa" + uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for k, (filename, content, ctype) in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"; "
                f"filename=\"{filename}\"\r\nContent-Type: {ctype}\r\n\r\n").encode()
        out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


class Client:
    def __init__(self, base: str, token: str | None = None):
        self.base = base.rstrip("/")
        self.token = token
        self._ctx = ssl.create_default_context()
        self._ctx.check_hostname = False
        self._ctx.verify_mode = ssl.CERT_NONE

    def with_token(self, token: str) -> "Client":
        c = Client(self.base, token)
        return c

    def request(self, method: str, path: str, body=None, token: str | None = None,
                headers: dict[str, str] | None = None,
                files: dict | None = None, form: dict | None = None) -> Resp:
        url = self.base + path
        hdrs = dict(headers or {})
        tok = token if token is not None else self.token
        if tok:
            hdrs.setdefault("Authorization", "Bearer " + tok)
        data = None
        if files or form:
            data, ctype = _multipart(form or {}, files or {})
            hdrs["Content-Type"] = ctype
        elif body is not None:
            data = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=90, context=self._ctx) as resp:
                return Resp(resp.status, resp.read())
        except urllib.error.HTTPError as e:
            return Resp(e.code, e.read())
        except urllib.error.URLError as e:
            raise ApiError(f"{method} {url} 连不上：{e}") from e

    # ---- 便捷方法 ----
    def get(self, path, **kw) -> Resp:
        return self.request("GET", path, **kw)

    def post(self, path, body=None, **kw) -> Resp:
        return self.request("POST", path, body=body, **kw)

    def put(self, path, body=None, **kw) -> Resp:
        return self.request("PUT", path, body=body, **kw)

    def delete(self, path, **kw) -> Resp:
        return self.request("DELETE", path, **kw)

    # ---- 断言辅助 ----
    def ok(self, path, body=None, method="POST", **kw) -> Resp:
        r = self.request(method, path, body=body, **kw)
        if r.status != 200:
            raise ApiError(f"{method} {path} 期望 200，实际 {r.status} body={r.body}")
        return r
