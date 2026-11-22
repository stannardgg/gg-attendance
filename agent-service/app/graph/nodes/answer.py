"""answer：组织回答 + 附引用（§11.7）。

**数字一律来自工具结果，不让模型复述**。模型只负责"把已经查到的数字说得顺"，
一旦让它重新组织数字，就会出现"应到 40 人说成 41 人"这类无法审计的错误。
所以这里的做法是：先按工具结果生成事实骨架，模型可用时只做润色；模型不可用时骨架直接用。
"""

from __future__ import annotations

import logging
import re
from typing import Any

from app.config import settings
from app.graph.common import ratio
from app.graph.nodes.planner import guide_text
from app.graph.state import AgentState
from app.llm.client import LlmUnavailable, chat
from app.llm.prompts import SYSTEM_PROMPT

log = logging.getLogger(__name__)

# 纯应答词不值得等一次模型往返（思考型模型要 1~2 秒），保持轻量固定回应
_ACK = re.compile(r"^(谢谢|多谢|辛苦|好的|好嘞|收到|嗯+|哦+|ok|okay)[，,！!。.~～\s]*$", re.IGNORECASE)

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
        return {"answer": _chitchat_reply(state.get("query") or "")}

    results = state.get("tool_results") or []
    if not results:
        return {"answer": "我没有查到可用的数据。可以换个说法，或先在列表页确认数据是否存在。"}

    body = _compose(results)
    # 导出类回答里有一句「点消息下方的下载按钮」——这是操作指引，不是措辞。
    # 让模型润色会把「下方」改成「右侧/附件区」，用户照着找会找不到。
    polished = body if any(r.get("tool") == "export_attendance" for r in results) \
        else _polish(state.get("query") or "", body)
    footer = _citations(results)

    text = polished
    if state.get("last_action_log_id"):
        text += (f"\n\n这次操作已留痕（操作记录 #{state['last_action_log_id']}）。"
                 f"如需撤销，点消息下方的「撤销」按钮，30 分钟内有效。")
    return {"answer": f"{text}\n\n{footer}".strip()}


def _chitchat_reply(text: str) -> str:
    """寒暄/问用法：有模型时自然作答，没模型时回落固定引导（§11.8 降级语义不变）。

    无论走不走模型，回答都要落到"能问什么"——引导文案是产品行为的一部分，
    模型的自我介绍只是包装，不能把用户引向工具清单之外的能力。
    """
    text = (text or "").strip()
    if _ACK.match(text):
        return "好的。想查数据可以直接说，比如「张三今天签到了吗」「计算机 2 班这周出勤率」。"
    if not settings.llm_enabled:
        return guide_text()
    try:
        reply = chat([
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "system", "content":
                "用户在寒暄、闲聊或询问你的能力。本轮没有调用任何工具：不要输出「数据来源」行，"
                "也不要给出任何考勤数字。用两三句话自然回应；跑题的话题礼貌说明你只处理"
                "签到、考勤、班级课程相关的事。最后附上这些标准问法，引导用户下一步：\n"
                + guide_text()},
            {"role": "user", "content": text},
        ])
        return reply.content.strip() or guide_text()
    except LlmUnavailable as exc:
        log.info("llm-chitchat-skipped %s", exc)
        return guide_text()


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

    if tool == "export_attendance":
        rows = int(result.get("rowCount") or 0)
        name = result.get("fileName") or "考勤记录.xlsx"
        if rows == 0:
            return (f"该范围内没有考勤记录。已生成仅含表头的「{name}」，"
                    f"可以打开确认筛选条件是否符合预期。")
        return (f"已生成「{name}」，共 {rows} 行（缺勤行已整行红底高亮）。"
                f"点消息下方的「下载 Excel」保存文件。")

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
        # 与 tool_node._count 保持同一口径：found=false 就是 0 条，
        # 否则「未查询到该学生」后面会跟一句「命中 1 条」。
        if result.get("found") is False:
            return 0
        if isinstance(result.get("rowCount"), int):
            return int(result["rowCount"])
        return 1
    return 1 if result else 0
