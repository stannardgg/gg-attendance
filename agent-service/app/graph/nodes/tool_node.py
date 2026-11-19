"""tools：执行一个工具调用（§11.2 ToolNode：超时/重试/留痕）。

留痕不在这里做——`agent_query_log` / `agent_action_log` 由 Java 侧统一写，
Agent 再写一遍就会有两份口径不同的日志。这里只负责把「调了什么、拿到什么」放进 state。
"""

from __future__ import annotations

import logging

from app.graph.state import AgentState
from app.tools.catalog import ToolError
from app.tools.registry import ToolClient

log = logging.getLogger(__name__)


def tool_node(state: AgentState) -> dict:
    plan = list(state.get("plan") or [])
    if not plan:
        return {}
    step = plan.pop(0)
    tool = step["tool"]
    params = step.get("params") or {}
    tool_call_id = step.get("id")

    client = ToolClient(state["token"], int(state["user"]["accountId"]))
    results = list(state.get("tool_results") or [])
    citations = list(state.get("citations") or [])

    try:
        data = client.invoke(tool, params, tool_call_id=tool_call_id,
                             conversation_id=state.get("conversation_id"))
    except ToolError as exc:
        log.warning("tool-failed tool=%s code=%s", tool, exc.code)
        results.append({"tool": tool, "params": params, "error": exc.code, "message": exc.message})
        return {
            "plan": [],
            "tool_results": results,
            "pending": None,
            "error": exc.code,
            "answer": _error_text(exc.code, exc.message, tool),
        }

    if data.get("requiresConfirm"):
        # 预览阶段：库中没有任何变更，等用户点确认
        return {
            "plan": plan,
            "pending": {
                "tool": tool,
                "params": params,
                "preview": data.get("preview") or {},
                "confirmToken": data.get("confirmToken"),
                "expiresAt": data.get("expiresAt"),
                "toolCallId": tool_call_id,
            },
            "tool_results": results,
            "citations": citations,
        }

    result = data.get("result")
    results.append({"tool": tool, "params": params, "result": result})
    citations.append({"tool": tool, "params": params, "count": _count(result)})
    update = {
        "plan": plan,
        "tool_results": results,
        "citations": citations,
        "pending": None,
    }
    if data.get("actionLogId"):
        update["last_action_log_id"] = int(data["actionLogId"])
        update["undoable_until"] = (data.get("preview") or {}).get("undoableUntil")
    return update


def _count(result: object) -> int:
    if isinstance(result, dict):
        for key in ("items", "records", "candidates", "rows"):
            value = result.get(key)
            if isinstance(value, list):
                return len(value)
        if "count" in result and isinstance(result["count"], int):
            return int(result["count"])
        return 1
    if isinstance(result, list):
        return len(result)
    return 1 if result else 0


def _error_text(code: str, message: str, tool: str) -> str:
    if code == "FA-6004":
        return "你当前的数据范围不包含该记录。"
    if code == "FA-1002":
        return "登录状态已失效，请回到管理端刷新后重试。"
    if code in ("FA-9002",):
        return "后端服务暂时不可用，请稍后重试。"
    if code == "FA-2001":
        return f"参数不对：{message}。请补充必要的信息后重试。"
    return f"这次查询失败了（{code}）：{message}"
