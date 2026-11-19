"""confirm + commit：两阶段确认的落点（§11.5 第 3、4 道闸）。

`interrupt()` 会把整个图挂起，检查点写进 Redis。用户刷新页面后只要 thread_id 不变，
仍然能看到同一张确认卡 —— 这是"进程能重启、确认不丢"的关键。

**commit 节点用 toolCallId 作为幂等键**：断点恢复可能把同一个节点再跑一次，
带同一个 toolCallId 时 Java 会直接返回上次的结果，不会发起两场签到。
"""

from __future__ import annotations

import logging

from langgraph.types import interrupt

from app.graph.state import AgentState
from app.tools.catalog import ToolError
from app.tools.registry import ToolClient

log = logging.getLogger(__name__)


def confirm(state: AgentState) -> dict:
    pending = state.get("pending") or {}
    payload = {
        "kind": "confirm",
        "tool": pending.get("tool"),
        "params": pending.get("params") or {},
        "preview": pending.get("preview") or {},
        "expiresAt": pending.get("expiresAt"),
    }
    decision = interrupt(payload)

    # 恢复值可能是 {"approved": bool}，也可能只是一个 bool（命令行/测试里更顺手）
    if isinstance(decision, dict):
        approved = bool(decision.get("approved"))
    else:
        approved = bool(decision)

    if not approved:
        log.info("agent-confirm-rejected tool=%s", pending.get("tool"))
        return {"pending": None, "plan": [], "confirm_approved": False,
                "answer": "已取消，本次没有执行任何写操作。"}
    return {"confirm_approved": True}


def after_confirm(state: AgentState) -> str:
    return "commit" if state.get("confirm_approved") else "answer"


def commit(state: AgentState) -> dict:
    pending = state.get("pending") or {}
    tool = pending.get("tool")
    params = pending.get("params") or {}
    client = ToolClient(state["token"], int(state["user"]["accountId"]))
    results = list(state.get("tool_results") or [])
    citations = list(state.get("citations") or [])
    try:
        data = client.invoke(
            tool,
            params,
            confirm_token=pending.get("confirmToken"),
            tool_call_id=pending.get("toolCallId"),
            conversation_id=state.get("conversation_id"),
        )
    except ToolError as exc:
        log.warning("agent-commit-failed tool=%s code=%s", tool, exc.code)
        return {
            "pending": None,
            "plan": [],
            "error": exc.code,
            "answer": _commit_error_text(exc.code, exc.message),
        }

    result = data.get("result")
    results.append({"tool": tool, "params": params, "result": result})
    citations.append({"tool": tool, "params": params, "count": 1})
    update = {
        "pending": None,
        "plan": [],
        "tool_results": results,
        "citations": citations,
    }
    if data.get("actionLogId"):
        update["last_action_log_id"] = int(data["actionLogId"])
        update["undoable_until"] = (data.get("preview") or {}).get("undoableUntil")
    return update


def _commit_error_text(code: str, message: str) -> str:
    if code == "FA-6002":
        return "确认令牌已过期（60 秒有效），请重新发起这次操作。"
    if code == "FA-6003":
        return "参数在确认过程中发生了变化，为安全起见没有执行。请重新发起。"
    if code == "FA-6004":
        return "你当前的数据范围不包含该记录，操作未执行。"
    return f"执行失败（{code}）：{message}"
