"""tools：执行一个工具调用（§11.2 ToolNode：超时/重试/留痕）。

留痕不在这里做——`agent_query_log` / `agent_action_log` 由 Java 侧统一写，
Agent 再写一遍就会有两份口径不同的日志。这里只负责把「调了什么、拿到什么」放进 state。
"""

from __future__ import annotations

import logging

from app.graph.common import apply_owner_teacher
from app.graph.state import AgentState
from app.tools.catalog import ToolError
from app.tools.registry import ToolClient

log = logging.getLogger(__name__)

_XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def tool_node(state: AgentState) -> dict:
    plan = list(state.get("plan") or [])
    if not plan:
        return {}
    step = plan.pop(0)
    tool = step["tool"]
    params = step.get("params") or {}
    # 教师身份下建/改班级、课程，负责人强制为本人——确定性规则，不靠模型自觉
    params = apply_owner_teacher(tool, params, state.get("user"))
    tool_call_id = step.get("id")

    client = ToolClient(state["token"], int(state["user"]["accountId"]))
    results = list(state.get("tool_results") or [])
    citations = list(state.get("citations") or [])
    attachments = list(state.get("attachments") or [])

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
            "attachments": attachments,
        }

    result = data.get("result")
    attachment = _extract_attachment(tool, result)
    if attachment:
        # 文件体不进 tool_results：它会被检查点反复序列化、也会被引用格式化逻辑扫到。
        # 元数据（文件名/行数）留在 result 里供回答使用。
        result = {k: v for k, v in result.items() if k != "contentBase64"}
        attachments.append(attachment)
    results.append({"tool": tool, "params": params, "result": result})
    citations.append({"tool": tool, "params": params, "count": _count(result)})
    update = {
        "plan": plan,
        "tool_results": results,
        "citations": citations,
        "attachments": attachments,
        "pending": None,
    }
    if data.get("actionLogId"):
        update["last_action_log_id"] = int(data["actionLogId"])
        update["undoable_until"] = (data.get("preview") or {}).get("undoableUntil")
    return update


def _extract_attachment(tool: str, result: object) -> dict[str, object] | None:
    """把工具返回的文件体从 result 里摘出来。

    只有 export_attendance 会带 contentBase64。用白名单判断而不是
    `if "contentBase64" in result`：别的工具将来若碰巧返回同名字段，
    也不该被当成可下载文件。
    """
    if tool != "export_attendance" or not isinstance(result, dict):
        return None
    content = result.get("contentBase64")
    if not isinstance(content, str) or not content:
        return None
    return {
        "fileName": str(result.get("fileName") or "考勤记录.xlsx"),
        "mimeType": str(result.get("contentType") or _XLSX_MIME),
        "contentBase64": content,
        "rowCount": int(result.get("rowCount") or 0),
    }


def _count(result: object) -> int:
    if isinstance(result, dict):
        for key in ("items", "records", "candidates", "rows"):
            value = result.get(key)
            if isinstance(value, list):
                return len(value)
        # found=false 是「查无此人/无记录」，此前落到下面的 return 1，
        # 引用里就出现「未查询到该学生 … 命中 1 条」这种自相矛盾的行。
        if result.get("found") is False:
            return 0
        # 导出类结果的「命中」就是数据行数，不是 1 个文件。
        if isinstance(result.get("rowCount"), int):
            return int(result["rowCount"])
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
