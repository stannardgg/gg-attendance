"""Agent 图状态（§11.3）。

用 `TypedDict` 而不是 dataclass：LangGraph 的检查点是按字段合并的，
dataclass 实例在反序列化时会因为版本差异报错，dict 更宽容。
"""

from __future__ import annotations

from typing import Any, TypedDict


class AgentState(TypedDict, total=False):
    # 输入
    query: str
    token: str
    user: dict[str, Any]
    conversation_id: str
    # 跨轮对话记忆：此前的用户/助手消息（role/content），由调用方每轮注入。
    # 检查点里的旧值每轮都会被新输入覆盖，不存在越用越长的堆积问题。
    history: list[dict[str, str]]

    # 会话内上下文
    intent: str             # chitchat | query | draft | action | blocked
    blocked_reason: str
    forced: dict[str, Any] | None   # 快捷问句带来的固定工具调用（不依赖 LLM）

    # 编排
    plan: list[dict[str, Any]]
    steps: int
    # planner 的收尾信号：这一步执行完之后还有没有下一步。见 verify.py
    planning_done: bool
    tool_results: list[dict[str, Any]]
    citations: list[dict[str, Any]]
    # 工具生成的文件（目前只有 export_attendance）。base64 由 UI 层渲染下载按钮，
    # 不参与回答正文，也不进 citations，否则「数据来源」会变成一屏乱码。
    attachments: list[dict[str, Any]]

    # 写操作
    pending: dict[str, Any] | None
    confirm_approved: bool
    last_action_log_id: int | None
    undoable_until: str | None

    # 输出
    answer: str
    error: str | None


def new_state(query: str, token: str, user: dict[str, Any],
              forced: dict[str, Any] | None = None,
              history: list[dict[str, str]] | None = None) -> AgentState:
    return AgentState(
        query=query,
        token=token,
        user=user,
        history=list(history or []),
        intent="",
        blocked_reason="",
        forced=forced,
        plan=[],
        steps=0,
        tool_results=[],
        citations=[],
        attachments=[],
        pending=None,
        last_action_log_id=None,
        undoable_until=None,
        answer="",
        error=None,
    )
