"""plan：决定下一步调哪个工具（§11.2，最多 4 步）。

**每次只产出一个步骤**，不是一次性列出整个计划。
原因：后一步的参数常常依赖前一步的结果（先 draft_session 拿到应到人数，
再 create_session），一次列全就得在 planner 里写"占位符替换"，那等于把编排逻辑又写一遍。
"""

from __future__ import annotations

import datetime as dt
import logging
import re
import uuid
from typing import Any

from app.config import settings
from app.graph.common import fmt_date, monday_of_this_week, today
from app.graph.state import AgentState
from app.llm.client import LlmUnavailable, chat
from app.llm.prompts import SYSTEM_PROMPT
from app.tools.catalog import ToolError, fetch_catalog_cached
from app.tools.registry import NameResolver

log = logging.getLogger(__name__)

MAX_STEPS = 4

_CREATE = re.compile(r"(?:帮我|请|给我|麻烦)?(?:给|为)?(?P<cls>.+?)(?:发起|开一?场?|开个)(?P<crs>.+?)(?:的)?签到")
_RENAME = re.compile(r"(?:把|将)?(?P<old>.+?)(?:改名为|改名成|改名叫|重命名为|名称改成|名字改成)(?P<new>.+)$")
# 问"多少场/几次"是在数场次，不是人名——必须排在 _NAME_STATUS 前面，
# 否则"有多少场签到"会被当成"查叫做『有多少场』的学生"。
_SESSION_COUNT = re.compile(r"(?:多少|几)(?:场|次|节).{0,4}签到|签到.{0,6}(?:多少|几)(?:场|次|节)")
_NAME_STATUS = re.compile(r"(?P<name>[\u4e00-\u9fa5]{2,4})(?P<when>今天|昨天|本周|这周)?(?:的)?(?:签到|考勤|到了)")
_CLASS_RATE = re.compile(r"(?P<cls>.+?)(?:班|班级)?(?:的)?(?P<when>这周|本周|今天|近\d+周)?(?:的)?出勤率")

# 疑问词出现在"人名"位置，说明这压根不是人名
_QUESTION_WORDS = ("多少", "几", "哪些", "什么", "谁", "怎么", "如何", "各")
# 泛指词出现在"班级"位置，说明没有具体班名："本周各班出勤率"里的"本周各"不是班名。
# 规则解析不出 classId，必须交给模型反问/枚举，不能吐出"没有找到叫『本周各』的班级"。
_GENERIC_SCOPE = ("各", "所有", "全部", "每个", "哪些", "哪个", "多少", "几")


def plan(state: AgentState) -> dict:
    steps = int(state.get("steps") or 0)
    if steps >= MAX_STEPS:
        return {"plan": [], "answer": "这个问题需要拆成几步来问。你可以先问「今天有哪些签到」，"
                                      "再针对具体场次问「这场到了多少人」。"}

    forced = state.get("forced")
    if forced and not state.get("tool_results"):
        return {"plan": [_step(forced["tool"], forced.get("params") or {},
                               write=bool(forced.get("write")))], "steps": steps + 1}

    results = state.get("tool_results") or []
    if forced and results:
        return {"plan": []}

    intent = state.get("intent") or "query"
    text = state.get("query") or ""

    if intent == "action" and _CREATE.search(text):
        return _plan_create_session(state, text, steps)

    if intent == "action" and _RENAME.search(text):
        return _plan_rename(state, text, steps)

    if intent == "action":
        # 其他写操作必须带明确对象（哪一场、哪个学生），自然语言里没有就只能反问
        return {"plan": [], "answer": "这个操作需要先指定具体对象。请先在列表里选中那一行，"
                                      "再用行末的「智能助手」按钮发起，或告诉我具体场次标题。"}

    if intent in ("query", "draft"):
        if results:
            return {"plan": []}
        return _plan_query(state, text, steps)

    # chitchat / 未知
    return {"plan": [], "answer": guide_text()}


# ------------------------------------------------------------------ 写操作


def _plan_create_session(state: AgentState, text: str, steps: int) -> dict:
    m = _CREATE.search(text)
    if not m:
        return {"plan": [], "answer": "我没听清是哪门课。可以说「帮我给计算机 2 班发起高等数学签到」。"}
    cls_name = _clean(m.group("cls"))
    crs_name = _clean(m.group("crs"))
    resolver = NameResolver(state["token"])
    try:
        clazz = resolver.resolve_class(cls_name)
        course = resolver.resolve_course(crs_name)
    except ToolError as exc:
        return {"plan": [], "answer": f"查询班级/课程失败（{exc.code}）。"}

    if clazz is None or course is None:
        missing = []
        if clazz is None:
            missing.append(f"班级「{cls_name}」")
        if course is None:
            missing.append(f"课程「{crs_name}」")
        return {"plan": [], "answer": f"没有唯一匹配到{'、'.join(missing)}。"
                                      f"请确认名称，或换用完整名称再试（重名的班级我不会替你选）。"}

    results = state.get("tool_results") or []
    done = {r["tool"] for r in results}
    params = {"classId": int(clazz["id"]), "courseId": int(course["id"])}

    # 第一步：草稿（L1，不落库），拿到应到人数与告警
    if "draft_session" not in done:
        return {"plan": [_step("draft_session", params)], "steps": steps + 1}

    # 第二步：真正发起（L2，返回预览等确认）
    if "create_session" not in done:
        draft = next(r for r in results if r["tool"] == "draft_session")["result"]
        expected = (draft or {}).get("expectedCount", 0)
        title = f"{clazz['name']} × {course['name']} 签到"
        return {"plan": [_step("create_session", {
            **params, "title": title, "durationMinutes": 10, "lateAfterSec": 300,
        }, write=True)], "steps": steps + 1}

    return {"plan": []}


def _plan_rename(state: AgentState, text: str, steps: int) -> dict:
    """改名：先解析出唯一对象，再走 L2 预览。

    班级码不可改（契约 ClassUpdateReq 没有 code），所以这里只处理名称；
    用户如果说"把 XX 的班级码改成 YY"，会落到下面的"需要指定对象"分支，不会静默改错。
    """
    m = _RENAME.search(text)
    if not m:
        return {"plan": [], "answer": "我没听清要改哪个。可以说「把计算机 2 班改名为计算机 3 班」。"}
    old_name = _clean(m.group("old"))
    new_name = _clean(m.group("new"))
    if not new_name:
        return {"plan": [], "answer": "没有听到新名字。可以说「把计算机 2 班改名为计算机 3 班」。"}

    resolver = NameResolver(state["token"])
    try:
        clazz = resolver.resolve_class(old_name)
        if clazz is None:
            course = resolver.resolve_course(old_name)
            if course is not None:
                return {"plan": [_step("update_course", {
                    "courseId": int(course["id"]), "name": new_name,
                }, write=True)], "steps": steps + 1}
    except ToolError as exc:
        return {"plan": [], "answer": f"查询班级/课程失败（{exc.code}）。"}

    if clazz is None:
        return {"plan": [], "answer": f"没有唯一匹配到「{old_name}」。"
                                      f"请用完整名称再试（重名时我不会替你选）。"}
    return {"plan": [_step("update_class", {
        "classId": int(clazz["id"]), "name": new_name,
    }, write=True)], "steps": steps + 1}


# ------------------------------------------------------------------ 只读


def _plan_query(state: AgentState, text: str, steps: int) -> dict:
    resolver = NameResolver(state["token"])

    if re.search(r"(待处理|未处理).{0,2}申诉|有哪些申诉", text):
        return {"plan": [_step("list_pending_appeals", {})], "steps": steps + 1}

    if re.search(r"(有哪些|列表|哪些).{0,4}签到|签到列表", text):
        d = today().isoformat()
        return {"plan": [_step("list_sessions", {"dateFrom": d, "dateTo": d})], "steps": steps + 1}

    if _SESSION_COUNT.search(text):
        when = "这周" if re.search(r"本周|这周|这星期", text) else (
            "昨天" if "昨天" in text else "今天")
        date_from, date_to = _range_for(when)
        return {"plan": [_step("list_sessions", {
            "dateFrom": date_from.isoformat(),
            "dateTo": date_to.isoformat(),
        })], "steps": steps + 1}

    m = _CLASS_RATE.search(text)
    if m and ("出勤率" in text):
        name = _clean(m.group("cls"))
        when = m.group("when") or "这周"
        date_from, date_to = _range_for(when)
        # 泛指（"各班"/"所有班级"）解析不出唯一对象，直接落给模型；不落回"没找到"的分支
        if name and not any(w in name for w in _GENERIC_SCOPE):
            try:
                clazz = resolver.resolve_class(name)
                if clazz is not None:
                    return {"plan": [_step("get_class_stats", {
                        "classId": int(clazz["id"]),
                        "dateFrom": date_from.isoformat(),
                        "dateTo": date_to.isoformat(),
                    })], "steps": steps + 1}
                course = resolver.resolve_course(name)
                if course is not None:
                    return {"plan": [_step("get_course_stats", {
                        "courseId": int(course["id"]),
                        "dateFrom": date_from.isoformat(),
                        "dateTo": date_to.isoformat(),
                    })], "steps": steps + 1}
            except ToolError as exc:
                return {"plan": [], "answer": f"查询统计失败（{exc.code}）。"}
            return {"plan": [], "answer": f"没有找到叫「{name}」的班级或课程。请确认名称。"}

    m = _NAME_STATUS.search(text)
    # 护栏：命中规则但"人名"里含疑问词，说明这是一个问句（"有多少场签到"），不是姓名
    if m and not any(w in m.group("name") for w in _QUESTION_WORDS):
        when = m.group("when") or "今天"
        date_from, date_to = _range_for(when)
        return {"plan": [_step("get_attendance_status", {
            "studentName": m.group("name"),
            "dateFrom": date_from.isoformat(),
            "dateTo": date_to.isoformat(),
        })], "steps": steps + 1}

    # 规则没覆盖：有模型就让模型选工具，没有模型就给出可用问法
    if settings.llm_enabled:
        try:
            return _llm_plan(state, text, steps)
        except LlmUnavailable as exc:
            # 静默降级等于"看起来模型没连上"：把原因带给用户，配置问题才有人查
            log.warning("llm-plan-unavailable %s", exc)
            return {"plan": [], "answer": f"{guide_text()}\n\n> 本次未用上大模型：{exc}。已按规则兜底。"}
    return {"plan": [], "answer": guide_text()}


def _llm_plan(state: AgentState, text: str, steps: int) -> dict:
    catalog = fetch_catalog_cached(state["token"], int(state["user"]["accountId"]))
    tools = [
        {
            "type": "function",
            "function": {
                "name": spec["name"],
                "description": spec.get("description") or spec.get("title") or "",
                "parameters": spec.get("paramsSchema") or {"type": "object"},
            },
        }
        for spec in catalog.tools.values()
    ]
    done = [f"{r['tool']}({r['params']}) → {_brief(r['result'])}" for r in state.get("tool_results") or []]
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": SYSTEM_PROMPT},
        # 不给日期，模型会回"我不掌握当前日期，请补日期"——规则层明明算得出来，
        # 用户看到的就是"这助手不聪明"。日期由服务端给，不让模型自己推。
        {"role": "system", "content": f"今天是 {today().isoformat()}，本周一 {monday_of_this_week().isoformat()}"
                                      f"（都按 Asia/Shanghai）。用户说「今天/昨天/本周」时直接换算成日期参数。"},
        {"role": "system", "content": "可用工具（只能从中选，不要编造）: "
                                      + ", ".join(t["function"]["name"] for t in tools)},
        {"role": "user", "content": text},
    ]
    if done:
        messages.append({"role": "system", "content": "已经调用过：" + "；".join(done)})
    reply = chat(messages, tools)
    if not reply.tool_calls:
        return {"plan": [], "answer": reply.content or guide_text()}
    call = reply.tool_calls[0]
    spec = catalog.tools.get(call.name)
    if spec is None:
        return {"plan": [], "answer": "我不确定该查哪张表，能说得更具体一点吗？"}
    return {"plan": [{"tool": call.name, "params": call.arguments,
                      "id": call.id or uuid.uuid4().hex, "write": bool(spec.get("requiresConfirm"))}],
            "steps": steps + 1}


# ------------------------------------------------------------------ 小工具


def _step(tool: str, params: dict[str, Any], write: bool = False) -> dict[str, Any]:
    return {"tool": tool, "params": params, "id": uuid.uuid4().hex, "write": write}


def _clean(name: str) -> str:
    """去掉口语里的修饰词，保留名称本体。"""
    name = name.strip()
    name = re.sub(r"^(帮我|请|麻烦|给我|为|给)", "", name)
    name = re.sub(r"(的|这场|一场|一个)$", "", name)
    return name.strip()


def _range_for(when: str):
    if when == "昨天":
        d = today() - dt.timedelta(days=1)
        return d, d
    if when in ("这周", "本周") or when.startswith("近"):
        return monday_of_this_week(), today()
    return today(), today()


def _brief(result: Any) -> str:
    if isinstance(result, dict):
        for key in ("items", "records", "candidates", "rows"):
            if key in result and isinstance(result[key], list):
                return f"{len(result[key])} 条"
        return ", ".join(f"{k}={v}" for k, v in list(result.items())[:3])
    return str(result)[:60]


def guide_text() -> str:
    return (
        "我可以帮你查这些：\n"
        "· 「张三今天签到了吗」——查某个人的签到结果\n"
        "· 「计算机 2 班这周出勤率」——按班级或课程看统计\n"
        "· 「今天有哪些签到」——列当天的签到场次\n"
        "· 「有哪些待处理申诉」——看申诉队列\n"
        "· 「帮我给计算机 2 班发起高等数学签到」——发起签到（会先给你确认卡）"
        f"\n（今天是 {fmt_date(today())}）"
    )
