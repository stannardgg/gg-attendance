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
from dataclasses import dataclass
from typing import Any

from app.config import settings
from app.graph.common import fmt_date, identity_text, llm_history_messages, monday_of_this_week, today
from app.graph.state import AgentState
from app.llm.client import LlmUnavailable, chat
from app.llm.prompts import SYSTEM_PROMPT
from app.tools.catalog import ToolError, fetch_catalog_cached
from app.tools.naming import compact, describe, find_all_in_text, pick, variants
from app.tools.registry import NameResolver

log = logging.getLogger(__name__)

MAX_STEPS = 4

# 发起签到：不再用一条正则去套语序。老师会说
# 「给大数据一班发起签到」「给大数据一班高数课程发起签到」「高数，大数据1班，开个签到」，
# 课程在动词前、没有课程、课程带「课程」后缀都出现过，正则越写越长且互相打架。
# 现在改为「词表驱动 + 库里名字反查」，见 parse_create_target。
_CREATE_VERB = re.compile(r"(发起|开始|新建|创建|安排|组织|搞|办|建|来|开)")
_CREATE_NOUN = re.compile(r"(签到|打卡)")
# 这些是别的写操作，明显不是"发起一场签到"，不能被上面的宽动词吃掉
_NOT_CREATE = re.compile(r"(结束|关闭|收尾|停止|补签|请假|免签|申诉|改名|重命名|归档|停用|导入|删除|移除)")

_RENAME = re.compile(r"(?:把|将)?(?P<old>.+?)(?:改名为|改名成|改名叫|重命名为|名称改成|名字改成)(?P<new>.+)$")

# 口语填充词：切名称前整块丢掉。按长度降序匹配，否则「开一场」会被「开」切掉半截。
_NOISE = tuple(sorted({
    "发起", "开始", "新建", "创建", "安排", "组织", "帮我", "帮忙", "麻烦",
    "我想", "我要", "我们", "咱们", "给我", "搞个", "搞一个", "搞", "办个", "办",
    "来一场", "来一个", "开一场", "开个", "开一个", "新建一个", "建一个", "建个", "开",
    "签到活动", "签到任务", "签到", "打卡", "考勤",
    "今天", "明天", "后天", "昨天", "现在", "马上", "立刻", "临时", "赶紧",
    "上午", "下午", "晚上", "本周", "这周", "下周", "最近",
    "这门课", "那门课", "这门", "那门", "这个", "那个", "一下", "一个", "一场",
    "的", "吧", "呢", "啊", "谢谢", "你好",
}, key=len, reverse=True))
_LEAD_NOISE = re.compile(r"^(?:帮我|请|麻烦|给我|我想|我要|我|我们|咱们|给|为|把|对|向)+")
_PUNCT_TRIM = "，。、；：！？,.;:!?~～…—-·（）()《》【】\"'“”‘’ \t\u3000"

# 只有班名 + 「签到」两个要素、没有任何疑问/统计词时，当成"发起签到"。
# 聊天框里最常见的省略说法就是「大数据1班高数签到」；判断放得很窄，
# 一旦出现「情况/记录/多少次/谁」就还是查询——宁可少认，不去抢查询。
_REALLY_QUERY = re.compile(
    r"(情况|记录|明细|统计|出勤率|名单|谁|多少|几场|几次|哪些|到了|来了|缺勤|迟到|导出|下载|查看|查一下)")

# 问"多少场/几次"是在数场次，不是人名——必须排在 _NAME_STATUS 前面，
# 否则"有多少场签到"会被当成"查叫做『有多少场』的学生"。
_SESSION_COUNT = re.compile(r"(?:多少|几)(?:场|次|节).{0,4}签到|签到.{0,6}(?:多少|几)(?:场|次|节)")
_NAME_STATUS = re.compile(r"(?P<name>[\u4e00-\u9fa5]{2,4})(?P<when>今天|昨天|本周|这周)?(?:的)?(?:签到|考勤|到了)")
_CLASS_RATE = re.compile(r"(?P<cls>.+?)(?:班|班级)?(?:的)?(?P<when>这周|本周|今天|近\d+周)?(?:的)?出勤率")

# 导出：必须是"导出动作 + 可导出的对象"两个条件都成立。
# 只判"导出"会把"导出全部数据"（敏感意图，intake 已拦）之外的正常提问也吃掉；
# 只判"考勤"又退化成普通查询。两个都命中才算导出。
_EXPORT = re.compile(r"(导出|下载|excel|xlsx|表格|报表|生成.{0,6}文件)", re.IGNORECASE)
_EXPORT_TARGET = re.compile(r"(考勤|签到|记录|明细|名单|到场|出勤)")
# 相对时间：「近两天」「最近 3 天」「过去两周」。中文数字也要认，
# 否则"近两天"里没有阿拉伯数字，落到默认分支就成了"只查今天"。
_REL_DAYS = re.compile(r"(?:最近|近|过去|前)\s*([0-9]{1,3}|[一二两三四五六七八九十]{1,3})\s*(?:天|日)")
_REL_WEEKS = re.compile(r"(?:最近|近|过去|前)\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*(?:周|星期|礼拜)")
# 显式日期区间：「2026-11-20 到 2026-11-22」。用户从表格里复制日期时最常见，
# 不认的话会被当成「今天」——正好是最容易出错的那种静默错误。
_ISO_RANGE = re.compile(r"(\d{4}-\d{1,2}-\d{1,2})\s*(?:到|至|~|～|-|—)\s*(\d{4}-\d{1,2}-\d{1,2})")
_CN_DIGITS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
              "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
_CLASS_IN_TEXT = re.compile(r"([\u4e00-\u9fa5A-Za-z0-9（）()＃#\-\s]{2,24}?班)")

# 疑问词出现在"人名"位置，说明这压根不是人名
_QUESTION_WORDS = ("多少", "几", "哪些", "什么", "谁", "怎么", "如何", "各")
# 这些词出现在"人名"位置，说明命中的是导出/统计类短语，不是姓名。
# 「导出近两天考勤记录」曾被截成 studentName=出近两天——正则只看到了"出近两天+考勤"。
# 「班」「课」同理：「大数据1班高数签到」会被截出"班高数"，然后回一句"没有查询到这个学生"。
_NOT_NAME_WORDS = ("导出", "下载", "表格", "报表", "记录", "明细", "名单", "全部", "所有",
                   "各班", "本周", "这周", "上周", "今天", "昨天", "最近", "统计", "汇总",
                   "两个", "两天", "三天", "近两", "近三", "近几", "班", "课")
# 泛指词出现在"班级"位置，说明没有具体班名："本周各班出勤率"里的"本周各"不是班名。
# 规则解析不出 classId，必须交给模型反问/枚举，不能吐出"没有找到叫『本周各』的班级"。
_GENERIC_SCOPE = ("各", "所有", "全部", "每个", "哪些", "哪个", "多少", "几")


def plan(state: AgentState) -> dict:
    """跑一步规划，并告诉图"还有没有下一步"。

    `planning_done` 是给 `verify` 的信号：一步工具跑完必须回来再问一次
    （发起签到是 draft → create 两步），但规划器说"没有了"就必须收尾，
    不然图会在 plan/tools 之间空转。写在入口这一层而不是每个 return 上，
    是为了以后加分支不用记得补标记。
    """
    out = _plan_once(state)
    out["planning_done"] = not out.get("plan")
    return out


def _plan_once(state: AgentState) -> dict:
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

    if intent in ("query", "action") and _is_create_request(text, intent):
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


def _looks_like_create(text: str) -> bool:
    """是不是"发起一场签到"。

    条件放宽到口语：有「签到/打卡」+ 任一动词（发起、开、搞、建、安排…），
    且不是结束、补签、请假这类别的写操作。判断只决定走哪条规划分支，
    真正的对象仍由库里的名字反查得出——这里多认一句不会写错任何数据。
    """
    return bool(_CREATE_NOUN.search(text) and _CREATE_VERB.search(text)
                and not _NOT_CREATE.search(text))


def _is_create_request(text: str, intent: str) -> bool:
    """带动词的说法，或"班名 + 签到"这种省略说法。

    省略说法只在句子是查询意图、含「班」、且没有任何疑问/统计词时才认；
    写操作后面还有确认卡兜底，认错一句的代价是点一次取消，比"没反应"好。
    """
    if _looks_like_create(text):
        return True
    return (intent == "query" and bool(_CREATE_NOUN.search(text)) and "班" in text
            and not _REALLY_QUERY.search(text))


def _plan_create_session(state: AgentState, text: str, steps: int) -> dict:
    resolver = NameResolver(state["token"])
    try:
        classes = resolver.all_classes()
        courses = resolver.all_courses()
    except ToolError as exc:
        return {"plan": [], "answer": f"查询班级/课程失败（{exc.code}）。"}

    target = parse_create_target(text, classes, courses)
    if target.answer:
        return {"plan": [], "answer": target.answer}

    clazz, course = target.clazz, target.course
    results = state.get("tool_results") or []
    done = {r["tool"] for r in results}
    params = {"classId": int(clazz["id"]), "courseId": int(course["id"])}

    # 第一步：草稿（L1，不落库），拿到应到人数与告警
    if "draft_session" not in done:
        return {"plan": [_step("draft_session", params)], "steps": steps + 1}

    # 第二步：真正发起（L2，返回预览等确认）
    if "create_session" not in done:
        title = f"{clazz['name']} × {course['name']} 签到"
        return {"plan": [_step("create_session", {
            **params, "title": title, "durationMinutes": 10, "lateAfterSec": 300,
        }, write=True)], "steps": steps + 1}

    return {"plan": []}


@dataclass(frozen=True)
class CreateTarget:
    """一次"发起签到"解析结果。`answer` 非空即为要给用户的话，不再往下走。"""

    clazz: dict[str, Any] | None = None
    course: dict[str, Any] | None = None
    answer: str = ""


def parse_create_target(text: str, classes: list[dict[str, Any]],
                        courses: list[dict[str, Any]]) -> CreateTarget:
    """从整句话里认出「哪个班 + 哪门课」。

    顺序（先认，后猜）：
    1. 库里真实存在的名字/编码**原样**出现在句子里 —— 最可信，不猜；
    2. 剩下没被认领的片段（「高数课程」→「高数」）走模糊匹配；
    3. 课程完全没提且老师名下只有一门课 —— 直接用它（这是"任何语句都能签到"的关键）；
       有多门就反问，不替他选。

    纯函数，不碰网络，单测直接喂假数据。
    """
    body = _strip_noise(compact(text))
    class_hits = find_all_in_text(text, classes, "class")
    if len(class_hits) > 1:
        return CreateTarget(answer=f"这句话里有多个班级：{describe(class_hits)}。"
                                  f"一次只能给一个班发起签到。")
    clazz = class_hits[0] if class_hits else None

    course_hits = find_all_in_text(text, courses, "course")
    if len(course_hits) > 1:
        return CreateTarget(answer=f"这句话里有多门课程：{describe(course_hits)}。"
                                  f"一次只能发起一门课的签到。")
    course = course_hits[0] if course_hits else None

    # 班级：先切出「…班」这个词，切完的剩余部分留给课程
    class_word, body = _take_word(body, kind="class")
    if clazz is None and class_word:
        hit = pick(classes, class_word, "class")
        if hit.row is not None:
            clazz = hit.row
        elif hit.ambiguous:
            return CreateTarget(answer=f"「{class_word}」匹配到多个同名班级："
                                      f"{describe(hit.candidates)}。请补上班级编码。")
        else:
            return CreateTarget(answer=f"班级「{class_word}」未存在。" + _available(classes, "班级"))
    if clazz is None:
        if class_word:
            return CreateTarget(answer=f"班级「{class_word}」未存在。" + _available(classes, "班级"))
        return CreateTarget(answer="请说明是哪个班级。" + _available(classes, "班级"))
    body = _drop_name(body, clazz, "class")

    if course is None:
        course_word = _take_course_word(body)
        if course_word:
            hit = pick(courses, course_word, "course")
            if hit.row is not None:
                course = hit.row
            elif hit.ambiguous:
                return CreateTarget(answer=f"「{course_word}」匹配到多门同名课程："
                                          f"{describe(hit.candidates)}。请补上课程编码。")
            else:
                return CreateTarget(answer=f"课程「{course_word}」未存在。"
                                          + _available(courses, "课程"))
        elif len(courses) == 1:
            course = courses[0]
        else:
            return CreateTarget(answer="请说明是哪门课程。" + _available(courses, "课程"))

    return CreateTarget(clazz=clazz, course=course)


def _strip_noise(body: str) -> str:
    """丢掉动作词与人称：「帮我给大数据1班发起签到」→「大数据1班」。"""
    body = _LEAD_NOISE.sub("", body)
    for word in _NOISE:
        body = body.replace(word, "")
    return body.strip(_PUNCT_TRIM)


def _take_word(body: str, kind: str) -> tuple[str, str]:
    """切出类别词并返回（词, 剩余串）。剩余串给下一个名称用，避免两个名字互相吞字。"""
    if kind == "class":
        m = re.search(r"([\u4e00-\u9fa5A-Za-z0-9]{1,20}班)", body)
    else:
        m = re.search(r"([\u4e00-\u9fa5A-Za-z0-9]{1,20})(?:课程|课)", body)
    if not m:
        return "", body
    return m.group(1).strip(_PUNCT_TRIM), (body[: m.start()] + body[m.end():])


def _take_course_word(body: str) -> str:
    """课程名的三种说法：带「课程/课」后缀、紧跟在班名后面、或就剩它自己。

    必须先剥掉班名再找，否则「大数据1班高数课程」会截出「大数据1班高数」。
    """
    m = re.search(r"(?:^|班)([^班课]{1,20}?)(?:课程|课)", body)
    if m:
        word = m.group(1).strip(_PUNCT_TRIM)
        if len(word) >= 2:
            return word
    rest = re.sub(r"[\s，。、；：！？,.;:!?~～…·\-—_（）()【】《》\"'“”‘’]+", " ", body).strip()
    for chunk in rest.split():
        if 2 <= len(chunk) <= 12:
            return chunk
    return ""


def _drop_name(body: str, row: dict[str, Any], kind: str) -> str:
    """把已认出的名字从剩余串里删掉，避免它被当成课程名。"""
    for variant in variants(row, kind):
        if variant in body:
            return body.replace(variant, "", 1)
    return body


def _available(rows: list[dict[str, Any]], label: str) -> str:
    if not rows:
        return f"你名下当前没有可用的{label}，请先让管理员授权。"
    return f"当前可用{label}：{describe(rows)}。"


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


def _plan_export(state: AgentState, text: str, steps: int) -> dict:
    """导出考勤明细（L0 只读，生成文件但不改任何数据）。

    这里查一次 catalog 而不是直接发工具调用：工具名在规则分支里是硬编码的，
    而 Java 可能还是旧版本。直接调只会得到「未知工具」这种读起来像参数写错的信息，
    用户会反复改措辞而不是找管理员升级。
    """
    try:
        catalog = fetch_catalog_cached(state["token"], int(state["user"]["accountId"]))
    except ToolError as exc:
        return {"plan": [], "answer": f"读取能力清单失败（{exc.code}），暂时无法导出。"}
    if not catalog.has("export_attendance"):
        return {"plan": [], "answer": "当前服务端还没有启用导出能力（export_attendance）。"
                                      "请让管理员确认后端已升级、L0 能力已开启。"}

    date_from, date_to = _range_from_text(text)
    params: dict[str, Any] = {
        "dateFrom": date_from.isoformat(),
        "dateTo": date_to.isoformat(),
    }

    class_name = _class_in_text(text)
    if class_name:
        try:
            clazz = NameResolver(state["token"]).resolve_class(class_name)
        except ToolError as exc:
            return {"plan": [], "answer": f"查询班级失败（{exc.code}）。"}
        if clazz is None:
            return {"plan": [], "answer": f"没有唯一匹配到班级「{class_name}」。请用完整名称再试，"
                                          f"或去掉班级名，按时间范围导出全部。"}
        params["classId"] = int(clazz["id"])

    return {"plan": [_step("export_attendance", params)], "steps": steps + 1}


def _plan_query(state: AgentState, text: str, steps: int) -> dict:
    resolver = NameResolver(state["token"])

    if re.search(r"(待处理|未处理).{0,2}申诉|有哪些申诉", text):
        return {"plan": [_step("list_pending_appeals", {})], "steps": steps + 1}

    # 导出必须排在 _NAME_STATUS 之前：「导出近两天考勤记录」里的「出近两天」
    # 会被那条正则当成姓名，而且规则命中后模型没有纠正机会。
    if _EXPORT.search(text) and _EXPORT_TARGET.search(text) and "申诉" not in text:
        return _plan_export(state, text, steps)

    if re.search(r"(有哪些|列表|哪些).{0,4}签到|签到列表", text):
        d = today().isoformat()
        return {"plan": [_step("list_sessions", {"dateFrom": d, "dateTo": d})], "steps": steps + 1}

    if _SESSION_COUNT.search(text):
        date_from, date_to = _range_from_text(text)
        return {"plan": [_step("list_sessions", {
            "dateFrom": date_from.isoformat(),
            "dateTo": date_to.isoformat(),
        })], "steps": steps + 1}

    m = _CLASS_RATE.search(text)
    if m and ("出勤率" in text):
        name = _clean(m.group("cls"))
        date_from, date_to = _range_from_text(text)
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
    # 护栏：命中规则但"人名"里含疑问词或导出类词，说明这不是姓名
    if m and not any(w in m.group("name") for w in _QUESTION_WORDS + _NOT_NAME_WORDS):
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
    ]
    identity = identity_text(state.get("user") or {})
    if identity:
        messages.append({"role": "system", "content": identity})
    # 跨轮记忆：没有它，「确认」「就查它」这类指代在模型眼里是无上下文的孤句，
    # 只能回"没有待确认的操作记录"。历史只作理解用，工具调用仍以本轮为准。
    history = llm_history_messages(state.get("history"))
    if history:
        messages.append({"role": "system", "content":
            "以下是本轮之前的对话记录（旧→新，单条可能被截断）。用户当前这句话可能是"
            "对上一轮的确认、补充或追问，请结合它理解；若上一轮你在等用户补参数或等确认，"
            "现在信息齐了就立即调用对应工具取预览，不要再重复追问。"})
        messages.extend(history)
    messages.append({"role": "user", "content": text})
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
    """固定词的日期口径。带数字的相对区间走 _range_from_text。"""
    if when == "昨天":
        d = today() - dt.timedelta(days=1)
        return d, d
    if when in ("这周", "本周"):
        return monday_of_this_week(), today()
    return today(), today()


def _range_from_text(text: str):
    """从整句里解析相对时间。

    顺序即优先级：显式区间 > 带数字的相对区间 > 昨天到今天 > 前天/昨天 > 上周 > 本周 > 本月 > 今天。
    旧实现把任何「近…」都当成本周一至今，于是「近两天」实际只查了当天——
    和「查无此人」叠在一起，就把一个日期解析问题伪装成了识别错误。
    """
    m = _ISO_RANGE.search(text)
    if m:
        try:
            start = dt.date.fromisoformat(m.group(1))
            end = dt.date.fromisoformat(m.group(2))
        except ValueError:
            start = end = None
        if start and end:
            return (start, end) if start <= end else (end, start)
    m = _REL_DAYS.search(text)
    if m:
        days = _bounded(_cn_int(m.group(1)), 1, 366)
        end = today()
        return end - dt.timedelta(days=days - 1), end
    m = _REL_WEEKS.search(text)
    if m:
        weeks = _bounded(_cn_int(m.group(1)), 1, 52)
        end = today()
        return end - dt.timedelta(days=weeks * 7 - 1), end
    if "昨天" in text and "今天" in text:
        return today() - dt.timedelta(days=1), today()
    if "前天" in text:
        d = today() - dt.timedelta(days=2)
        return d, d
    if "昨天" in text:
        d = today() - dt.timedelta(days=1)
        return d, d
    if "上周" in text:
        start = monday_of_this_week() - dt.timedelta(days=7)
        return start, start + dt.timedelta(days=6)
    if re.search(r"本周|这周|这星期", text):
        return monday_of_this_week(), today()
    if re.search(r"本月|这个月", text):
        return today().replace(day=1), today()
    return today(), today()


def _bounded(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def _cn_int(raw: str) -> int:
    """「两」→2、「十一」→11、「二十」→20。解析不了按 1 处理，不外抛。"""
    if raw.isdigit():
        return int(raw)
    if raw in _CN_DIGITS:
        return _CN_DIGITS[raw]
    if "十" in raw:
        head, _, tail = raw.partition("十")
        tens = _CN_DIGITS.get(head, 1) if head else 1
        ones = _CN_DIGITS.get(tail, 0) if tail else 0
        return tens * 10 + ones
    return 1


def _class_in_text(text: str) -> str | None:
    """从「导出计算机 2 班近两天考勤」里取出班名。

    必须先剥掉动作词与时间词，否则正则会把「导出计算机」一起截进班名，
    再去查一个不存在的班级。
    """
    stripped = _REL_DAYS.sub("", text)
    stripped = _REL_WEEKS.sub("", stripped)
    stripped = re.sub(r"(导出|下载|生成|一份|考勤|签到|记录|明细|表格|excel|xlsx|报表)",
                      "", stripped, flags=re.IGNORECASE)
    m = _CLASS_IN_TEXT.search(stripped)
    return _clean(m.group(1)) if m else None


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
        "· 「导出近两天考勤记录」——下载 Excel（缺勤行红底高亮）\n"
        "· 「有哪些待处理申诉」——看申诉队列\n"
        "· 「帮我给计算机 2 班发起高等数学签到」——发起签到（会先给你确认卡）"
        f"\n（今天是 {fmt_date(today())}）"
    )
