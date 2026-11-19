"""工具调用的 HTTP 封装 + 名称解析。

两条纪律写在这里，而不是写在 prompt 里（prompt 只能"劝"，代码才能"拦"）：
1. **超时 8s、重试 1 次**（§11.2 tools 节点）。重试只对网络错误与 5xx，业务错误重试没有意义。
2. **越权不重试**。FA-6004 之后换参数再试一次，是在帮用户扫描他没权限的数据。
"""

from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from app.config import settings
from app.tools.catalog import ToolError, _parse

log = logging.getLogger(__name__)


class ToolClient:
    """以一个终端用户的身份调用 Java 的 Agent 工具端点。"""

    def __init__(self, user_token: str, user_id: int) -> None:
        self._token = user_token
        self._user_id = user_id

    def _headers(self, conversation_id: str | None = None) -> dict[str, str]:
        headers = {
            "X-Internal-Token": settings.internal_token,
            "X-User-Token": self._token,
        }
        # 契约的 body 没有 conversationId 字段，用请求头透传，既留痕又不改契约
        if conversation_id:
            headers["X-Conversation-Id"] = conversation_id
        return headers

    def invoke(
        self,
        tool: str,
        params: dict[str, Any],
        *,
        confirm_token: str | None = None,
        tool_call_id: str | None = None,
        conversation_id: str | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"params": params}
        if confirm_token:
            body["confirmToken"] = confirm_token
        if tool_call_id:
            body["toolCallId"] = tool_call_id

        url = f"{settings.java_base_url}/internal/agent/tools/{tool}"
        last_exc: Exception | None = None
        for attempt in range(settings.tool_max_retry + 1):
            try:
                with httpx.Client(timeout=settings.tool_timeout_sec) as client:
                    resp = client.post(url, json=body, headers=self._headers(conversation_id))
                if resp.status_code >= 500 and attempt < settings.tool_max_retry:
                    log.warning("tool-5xx tool=%s attempt=%d status=%d", tool, attempt, resp.status_code)
                    time.sleep(0.3)
                    continue
                payload = _parse(resp)
                return payload.get("data") or {}
            except httpx.TimeoutException as exc:
                last_exc = exc
                if attempt < settings.tool_max_retry:
                    continue
                raise ToolError("FA-9002", "工具调用超时，请稍后重试") from exc
            except httpx.HTTPError as exc:
                last_exc = exc
                if attempt < settings.tool_max_retry:
                    continue
                raise ToolError("FA-9002", "无法连接 Java 服务") from exc
        raise ToolError("FA-9002", f"工具调用失败：{last_exc}")

    def undo(self, action_log_id: int, conversation_id: str | None = None) -> dict[str, Any]:
        url = f"{settings.java_base_url}/internal/agent/actions/{action_log_id}/undo"
        with httpx.Client(timeout=settings.tool_timeout_sec) as client:
            resp = client.post(url, headers=self._headers(conversation_id))
        return _parse(resp).get("data") or {}


class NameResolver:
    """把「计算机 2 班」「高等数学」解析成 id。

    **为什么要这一层**：§11.4 的工具清单里没有 `list_classes` / `list_courses`，
    但验收要求"说'帮我给计算机 2 班发起高数签到'能出确认卡"。没有名称解析，
    模型只能靠猜 id —— 猜错就是给别的班发了签到。
    这里复用已有的**只读**管理端列表接口做解析，不新增任何写入路径，也不扩权：
    返回的列表本来就被 `teacher_scope` 过滤过。已登记 CR-005，建议把这两个查询补进工具契约。
    """

    def __init__(self, user_token: str) -> None:
        self._token = user_token

    def _get(self, path: str, keyword: str) -> list[dict[str, Any]]:
        url = f"{settings.java_base_url}{path}"
        with httpx.Client(timeout=settings.tool_timeout_sec) as client:
            resp = client.get(
                url,
                params={"keyword": keyword, "size": 20, "status": "ACTIVE"},
                headers={"Authorization": f"Bearer {self._token}"},
            )
        payload = _parse(resp)
        return ((payload.get("data") or {}).get("items")) or []

    def classes(self, keyword: str) -> list[dict[str, Any]]:
        return self._get("/api/v1/admin/classes", keyword)

    def courses(self, keyword: str) -> list[dict[str, Any]]:
        return self._get("/api/v1/admin/courses", keyword)

    def resolve_class(self, name: str) -> dict[str, Any] | None:
        return _pick(self.classes(name), name)

    def resolve_course(self, name: str) -> dict[str, Any] | None:
        return _pick(self.courses(name), name)


def _pick(rows: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    """精确同名优先；否则取唯一包含关系；仍不唯一则返回 None（让上层反问，不许猜）。"""
    exact = [r for r in rows if r.get("name") == name]
    if len(exact) == 1:
        return exact[0]
    if len(rows) == 1:
        return rows[0]
    return None
