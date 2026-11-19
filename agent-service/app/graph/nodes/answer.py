"""answer：组织回答 + 附引用（§11.7）。

**数字一律来自工具结果，不让模型复述**。模型只负责"把已经查到的数字说得顺"，
一旦让它重新组织数字，就会出现"应到 40 人说成 41 人"这类无法审计的错误。
所以这里的做法是：先按工具结果生成事实骨架，模型可用时只做润色；模型不可用时骨架直接用。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import settings
from app.graph.common import ratio
from app.graph.nodes.planner import guide_text
from app.graph.state import AgentState
from app.llm.client import LlmUnavailable, chat
from app.llm.prompts import SYSTEM_PROMPT

log = logging.getLogger(__name__)

_STATUS_LABEL = {
    "PRESENT": "出勤",
    "LATE": "迟到",
    "ABSENT": "缺勤",
    "LEAVE": "请假",
    "EXEMPT": "免签",
}


def answer(state: AgentState) -> dict:
    if state.get("answer"):
        return {}

    if state.get("intent") == "chitchat":
        return {"answer": guide_text()}

    results = state.get("tool_results") or []
    if not results:
        return {"answer": "我没有查到可用的数据。可以换个说法，或先在列表页确认数据是否存在。"}

    body = _compose(results)
    polished = _polish(state.get("query") or "", body)
    footer = _citations(results)

    text = polished
    if state.get("last_action_log_id"):
        text += (f"\n\n这次操作已留痕（操作记录 #{state['last_action_log_id']}）。"
                 f"如需撤销，点消息下方的「撤销」按钮，30 分钟内有效。")
    return {"answer": f"{text}\n\n{footer}".strip()}


def _compose(results: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for item in results:
        if item.get("error"):
            lines.append(f"· {item['tool']}：失败（{item['error']}）")
            continue
        lines.append(_describe(item["tool"], item.get("result")))
    return "\n".join(x for x in lines if x)


def _describe(tool: str, result: Any) -> str:
    if not isinstance(result, dict):
        return f"· {tool}：{result}"

    if tool == "get_attendance_status":
        if result.get("ambiguous"):
            names = "、".join(f"{c.get('name')}（{c.get('className')}）"
                              for c in result.get("candidates") or [])
            return f"「{result.get('studentName') or ''}」匹配到多人：{names}。请告诉我具体是哪一位。"
        if result.get("found") is False:
            return "没有查询到这个学生。"
        student = result.get("student") or {}
        records = result.get("records") or []
        if not records:
            return f"{student.get('name', '该学生')}在所选时间段内没有签到记录。"
        head = f"{student.get('name', '')}（{student.get('className', '')}）的签到记录："
        rows = [f"  · {r.get('date')} {r.get('courseName')}：{_STATUS_LABEL.get(r.get('status'), r.get('status'))}"
                + (f"（{str(r.get('checkinAt'))[11:19]}）" if r.get("checkinAt") else "")
                for r in records[:10]]
        return head + "\n" + "\n".join(rows)

    if tool == "get_session_summary":
        return (f"{result.get('className')} × {result.get('courseName')}："
                f"应到 {result.get('total')}，实到 {result.get('present')}，"
                f"迟到 {result.get('late')}，缺勤 {result.get('absent')}，"
                f"出勤率 {ratio(result.get('rate'))}。")

    if tool in ("get_class_stats", "get_course_stats"):
        name = result.get("className") or result.get("courseName")
        top = result.get("topAbsent") or []
        top_text = ""
        if top:
            top_text = "\n缺勤最多：" + "、".join(
                f"{r.get('name')}（{r.get('absentCount')} 次）" for r in top[:3])
        return (f"{name}：{result.get('sessionCount')} 场签到，共 {result.get('total')} 条记录，"
                f"出勤率 {ratio(result.get('rate'))}，迟到率 {ratio(result.get('lateRate'))}。"
                + top_text)

    if tool in ("list_sessions", "list_pending_appeals"):
        items = result.get("items") or []
        if not items:
            return "没有符合条件的记录。"
        if tool == "list_sessions":
            rows = [f"  · {str(i.get('startAt'))[:16].replace('T', ' ')} "
                    f"{i.get('className')} × {i.get('courseName')}（{i.get('status')}）" for i in items[:8]]
            return f"共 {len(items)} 场签到：\n" + "\n".join(rows)
        rows = [f"  · {i.get('studentName')}：{i.get('reason')}" for i in items[:8]]
        return f"有 {len(items)} 条待处理申诉：\n" + "\n".join(rows)

    if tool == "draft_session":
        warn = result.get("warnings") or []
        return (f"{result.get('className')} × {result.get('courseName')}："
                f"应到 {result.get('expectedCount')} 人。"
                + ("\n注意：" + "；".join(warn) if warn else ""))

    if tool == "create_session":
        return (f"已发起签到：{result.get('className')} × {result.get('courseName')}，"
                f"应到 {result.get('expectedCount')} 人，"
                f"结束时间 {str(result.get('endAt'))[:19].replace('T', ' ')}。")

    if tool == "close_session":
        return (f"已结束签到：实到 {result.get('presentCount')} 人，"
                f"缺勤 {result.get('absentCount')} 人。")

    if tool == "summarize_session":
        return str(result.get("text") or "")

    if tool == "draft_import_students":
        return (f"导入预览：共 {result.get('total')} 行，新增 {result.get('willCreate')}，"
                f"更新 {result.get('willUpdate')}，失败 {result.get('failed')}。")

    if tool == "draft_appeal_reply":
        return (f"建议{'通过' if result.get('suggestedDecision') == 'APPROVE' else '驳回'}。"
                f"依据：{result.get('rationale')}\n拟回复：{result.get('reply')}")

    if tool == "get_class_roster":
        items = result.get("items") or []
        return f"该班共 {len(items)} 名学生（含人脸状态），已按学号排序。"

    # 兜底：把结果的前几个字段列出来，不假装懂它
    keys = ", ".join(f"{k}={v}" for k, v in list(result.items())[:4])
    return f"· {tool}：{keys}"


def _polish(query: str, body: str) -> str:
    if not settings.llm_enabled:
        return body
    try:
        reply = chat([
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "system", "content": "下面是根据工具结果生成的事实骨架。请只做措辞润色，"
                                          "不得改动任何数字、姓名、日期，也不得补充骨架里没有的信息。"},
            {"role": "user", "content": f"问题：{query}\n事实：\n{body}"},
        ])
        return reply.content.strip() or body
    except LlmUnavailable as exc:
        log.info("llm-polish-skipped %s", exc)
        return body


def _citations(results: list[dict[str, Any]]) -> str:
    parts = []
    for item in results:
        params = item.get("params") or {}
        cond = "、".join(f"{k}={v}" for k, v in params.items()) or "无条件"
        count = _count(item.get("result"), item.get("error"))
        parts.append(f"{item['tool']} / {cond} / 命中 {count} 条")
    return "数据来源：" + "；".join(parts)


def _count(result: Any, error: Any) -> int:
    if error:
        return 0
    if isinstance(result, dict):
        for key in ("items", "records", "candidates", "rows"):
            value = result.get(key)
            if isinstance(value, list):
                return len(value)
        return 1
    return 1 if result else 0
