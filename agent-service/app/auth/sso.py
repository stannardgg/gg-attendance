"""SSO：用一次性 code 换用户令牌（D6）。

**为什么不把 JWT 放进 iframe URL**：nginx 的 access_log 会记 query string，
令牌进日志就等于长期泄漏（日志保留期通常比令牌有效期长得多）。
code 只有 60 秒且单次消费，即使被记下来也已经失效。
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import settings
from app.tools.catalog import ToolError

log = logging.getLogger(__name__)


def exchange_code(code: str) -> dict[str, Any]:
    """返回 {"token": ..., "profile": {...}}。失败抛 ToolError，由界面提示。"""
    url = f"{settings.java_base_url}/internal/agent/sso/exchange"
    try:
        with httpx.Client(timeout=10) as client:
            resp = client.post(
                url,
                json={"code": code},
                headers={"X-Internal-Token": settings.internal_token},
            )
    except httpx.HTTPError as exc:
        raise ToolError("FA-9002", "无法连接后端服务") from exc

    try:
        payload = resp.json()
    except ValueError as exc:
        raise ToolError("FA-9002", f"后端返回了非 JSON 响应（HTTP {resp.status_code}）") from exc

    code_str = str(payload.get("code", ""))
    if resp.status_code >= 400 or (code_str and code_str != "FA-0000"):
        # code 过期/已用/伪造都归一到同一句提示，不给试探空间
        raise ToolError(code_str or "FA-1002", str(payload.get("message") or "登录已失效"))
    data = payload.get("data") or {}
    token = data.get("token")
    if not token:
        raise ToolError("FA-1002", "登录已失效")
    profile = dict(data.get("profile") or {})
    log.info("sso-exchange-ok account=%s role=%s", profile.get("accountId"), profile.get("role"))
    return {"token": token, "profile": profile}
