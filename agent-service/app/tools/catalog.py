"""工具目录：由 Java 下发，Agent 无法自行扩权（§11.4）。

启动或每轮对话时拉一次，按 `level` 过滤后装配到 prompt 里。
**不在 Agent 侧维护一份工具清单的副本**——一旦两边漂移，模型会调用一个服务端不认识的名字。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.config import settings

log = logging.getLogger(__name__)


class ToolError(RuntimeError):
    """工具调用失败。`code` 就是契约错误码，Agent 据此决定「反问 / 拒绝 / 换步骤」。"""

    def __init__(self, code: str, message: str, status: int = 0) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.status = status


@dataclass
class Catalog:
    levels: dict[str, bool] = field(default_factory=dict)
    class_ids: list[int] = field(default_factory=list)
    course_ids: list[int] = field(default_factory=list)
    tools: dict[str, dict[str, Any]] = field(default_factory=dict)

    def has(self, name: str) -> bool:
        return name in self.tools

    def requires_confirm(self, name: str) -> bool:
        return bool(self.tools.get(name, {}).get("requiresConfirm"))

    def level_of(self, name: str) -> str:
        return str(self.tools.get(name, {}).get("level", "?"))

    def enabled_levels(self) -> list[str]:
        return [lv for lv, on in self.levels.items() if on]


def fetch_catalog(user_token: str, user_id: int) -> Catalog:
    url = f"{settings.java_base_url}/internal/agent/tools/catalog"
    with httpx.Client(timeout=settings.tool_timeout_sec) as client:
        resp = client.get(
            url,
            params={"userId": user_id},
            headers={
                "X-Internal-Token": settings.internal_token,
                "X-User-Token": user_token,
            },
        )
    payload = _parse(resp)
    data = payload.get("data") or {}
    tools = {t["name"]: t for t in (data.get("tools") or [])}
    scope = data.get("scope") or {}
    log.info("catalog-loaded tools=%d levels=%s", len(tools), data.get("level"))
    return Catalog(
        levels=data.get("level") or {},
        class_ids=[int(x) for x in (scope.get("classIds") or [])],
        course_ids=[int(x) for x in (scope.get("courseIds") or [])],
        tools=tools,
    )


def _parse(resp: httpx.Response) -> dict[str, Any]:
    try:
        payload = resp.json()
    except ValueError as exc:  # 非 JSON（网关 502 的 HTML 等）
        raise ToolError("FA-9002", f"Agent 无法解析 Java 响应（HTTP {resp.status_code}）", resp.status_code) from exc
    code = str(payload.get("code", ""))
    if resp.status_code >= 400 or (code and code != "FA-0000"):
        raise ToolError(code or f"HTTP-{resp.status_code}", str(payload.get("message", "")), resp.status_code)
    return payload


# 进程内短缓存：一轮对话里 planner 可能被调用 1~4 次，每次都拉 catalog 是纯浪费。
# 30 秒足够——能力级别的变化不需要毫秒级生效，但不能永久缓存（否则管理员关掉 L2 后旧进程还放行）。
_CACHE: dict[str, tuple[float, Catalog]] = {}
_CACHE_TTL = 30.0


def fetch_catalog_cached(user_token: str, user_id: int) -> Catalog:
    key = f"{user_id}:{user_token[:12]}"
    now = time.time()
    hit = _CACHE.get(key)
    if hit and now - hit[0] < _CACHE_TTL:
        return hit[1]
    catalog = fetch_catalog(user_token, user_id)
    _CACHE[key] = (now, catalog)
    return catalog


def invalidate_cache() -> None:
    _CACHE.clear()
