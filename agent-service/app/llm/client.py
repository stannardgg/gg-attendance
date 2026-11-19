"""OpenAI 兼容的 LLM 客户端。

只用 httpx 手写 chat/completions，不引 openai SDK / langchain-openai：
- 少一层依赖，换 base_url 就能接任何兼容端点（§11.8）；
- function calling 的报文结构在契约里已经定义得很清楚，包一层反而看不清传了什么。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.config import settings

log = logging.getLogger(__name__)


class LlmUnavailable(RuntimeError):
    """未配置模型或调用失败。上层据此降级到规则引擎，而不是把异常抛给界面。"""


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass
class Reply:
    content: str
    tool_calls: list[ToolCall] = field(default_factory=list)


def chat(messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None) -> Reply:
    if not settings.llm_enabled:
        raise LlmUnavailable("未配置模型")
    body: dict[str, Any] = {
        "model": settings.llm_model,
        "messages": messages,
        "temperature": settings.llm_temperature,
        "max_tokens": settings.llm_max_tokens,
    }
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    url = f"{settings.llm_base_url}/chat/completions"
    try:
        with httpx.Client(timeout=30) as client:
            resp = client.post(
                url,
                json=body,
                headers={"Authorization": f"Bearer {settings.llm_api_key}"},
            )
    except httpx.HTTPError as exc:
        raise LlmUnavailable(f"模型服务不可达：{exc}") from exc
    if resp.status_code >= 400:
        raise LlmUnavailable(f"模型服务返回 {resp.status_code}")
    try:
        data = resp.json()
        message = data["choices"][0]["message"]
    except (ValueError, KeyError, IndexError) as exc:
        raise LlmUnavailable("模型返回结构异常") from exc

    calls: list[ToolCall] = []
    for raw in message.get("tool_calls") or []:
        fn = raw.get("function") or {}
        args = fn.get("arguments") or "{}"
        try:
            parsed = json.loads(args) if isinstance(args, str) else dict(args)
        except json.JSONDecodeError:
            parsed = {}
        calls.append(ToolCall(id=str(raw.get("id") or ""), name=str(fn.get("name") or ""), arguments=parsed))
    return Reply(content=str(message.get("content") or ""), tool_calls=calls)
