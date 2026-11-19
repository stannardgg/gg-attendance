"""checkinTicket 的签发与校验。

票据格式（B3 与 B4 的对接契约，Java 侧用同一密钥签发）：
    base64url(json{sid,studentId,jti,exp}) + "." + hex(HMAC-SHA256(secret, payload))

为什么不用 JWT：票据 TTL 只有 60 秒、字段固定 4 个，JWT 的头/声明开销
和库依赖都不划算；HMAC 一行搞定，且 Java 侧同样 10 行。

B4 注意：Java 签发时必须用同一个 FA_TICKET_SECRET，否则 start 一律 401。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from app.config import settings


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _b64d(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


class TicketError(ValueError):
    """票据无效或过期。对上层一律表现成 401，不区分具体原因（防探测）。"""


def issue(session_id: int, student_id: int, jti: str, ttl_sec: int = 60) -> str:
    secret = settings.java.internal_token.encode()
    payload = json.dumps({
        "sid": session_id,
        "studentId": student_id,
        "jti": jti,
        "exp": int(time.time()) + ttl_sec,
    }, separators=(",", ":")).encode()
    body = _b64e(payload)
    sig = hmac.new(secret, body.encode(), hashlib.sha256).hexdigest()
    return f"{body}.{sig}"


def parse(token: str) -> dict:
    """校验签名与过期。任何问题都抛 TicketError。"""
    if not token or "." not in token:
        raise TicketError("票据格式错误")
    body, sig = token.rsplit(".", 1)
    secret = settings.java.internal_token.encode()
    expect = hmac.new(secret, body.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig, expect):
        raise TicketError("票据签名不匹配")
    try:
        data = json.loads(_b64d(body))
    except Exception as exc:                                # noqa: BLE001
        raise TicketError("票据内容无法解析") from exc
    if data.get("exp", 0) < time.time():
        raise TicketError("票据已过期")
    return data
