"""节点共用的小工具：日期口径、金额/比率格式化、敏感意图识别、对话记忆裁剪、负责教师口径。"""

from __future__ import annotations

import datetime as dt
import re

SHANGHAI = dt.timezone(dt.timedelta(hours=8))

# 「明显的越权/注入」规则。这些句子不该走到工具调用，也不该浪费一次模型调用。
# 注意只拦"要联系方式/要全量数据/改规则"这三类，不要拦正常提问——
# 误拦的代价是用户觉得助手坏了，比漏拦更常见。
SENSITIVE_PATTERNS = [
    re.compile(r"(手机号|电话|身份证|住址|家庭地址)"),
    re.compile(r"(忽略|无视|忘记)(之前|以上|前面)?的?(指令|规则|设定)"),
    re.compile(r"(系统提示|system prompt|你的提示词)"),
    re.compile(r"(把所有|全部)(学生|用户|账号)(的)?(信息|数据|列表)"),
    re.compile(r"(导出|dump)(全部|所有)数据"),
]


def now() -> dt.datetime:
    return dt.datetime.now(SHANGHAI)


def today() -> dt.date:
    return now().date()


def monday_of_this_week() -> dt.date:
    d = today()
    return d - dt.timedelta(days=d.weekday())


def is_sensitive(text: str) -> str | None:
    for pat in SENSITIVE_PATTERNS:
        m = pat.search(text)
        if m:
            return m.group(0)
    return None


def ratio(value: float | int | None) -> str:
    if value is None:
        return "-"
    return f"{float(value) * 100:.2f}%"


def fmt_date(value: dt.date | str | None) -> str:
    if value is None:
        return "-"
    if isinstance(value, str):
        return value[:10]
    return value.isoformat()


# ------------------------------------------------------------------ 对话记忆
# 模型只看得到当轮一句话是「确认」时最典型的失忆现场：
# 上一轮它自己说「回复确认我就提交」，这一轮它却回「没有待确认的操作记录」。
# 所以调用方（UI/测试）每轮把此前的对话注入 state，这里统一做裁剪与清洗，
# 保证注入提示词的体量有上界：条数和单条长度都封顶，长回答只留开头。

HISTORY_MAX_MSGS = 8
HISTORY_MSG_CHARS = 300


def llm_history_messages(history: list[dict] | None) -> list[dict[str, str]]:
    """把调用方注入的历史会话压成可直接放进 LLM messages 的形态。

    只保留 user/assistant 两种角色、剔除空内容；时间正序（旧→新），
    当前提问永远排在消息列表最后，模型按出现顺序理解对话。
    纯函数，不碰网络，单测直接喂假数据。
    """
    if not history:
        return []
    msgs: list[dict[str, str]] = []
    for m in history[-HISTORY_MAX_MSGS:]:
        role = (m.get("role") or "").strip()
        content = str(m.get("content") or "").strip()
        if role not in ("user", "assistant") or not content:
            continue
        msgs.append({"role": role, "content": content[:HISTORY_MSG_CHARS]})
    return msgs


# ------------------------------------------------------------------ 负责教师
# 教师建/改班级、课程时负责人恒为本人。这是产品口径，也必须是确定性规则：
# 模型把负责人填成用户随口提到的其他教师，就是一次给别人的班级改主的写入——
# 所以在工具调用前强制归一，而不是指望提示词管住模型。
# 管理员通道不做任何注入：负责人必须由用户显式提供教师 ID，没给就不传（= 不改）。

OWNER_TEACHER_TOOLS = frozenset({"create_class", "update_class", "create_course", "update_course"})


def apply_owner_teacher(tool: str, params: dict | None, user: dict | None) -> dict:
    """教师身份强制 ownerTeacherId = 本人；管理员与其他工具原样返回（拷贝入参）。"""
    params = dict(params or {})
    if tool not in OWNER_TEACHER_TOOLS:
        return params
    u = user or {}
    tid = u.get("teacherId")
    if u.get("role") == "TEACHER" and tid:
        params["ownerTeacherId"] = int(tid)
    return params


def identity_text(user: dict | None) -> str:
    """给模型身份消息：把 ownerTeacherId 的口径按角色写死，别让模型自己发挥。"""
    u = user or {}
    tid, role = u.get("teacherId"), u.get("role")
    if role == "TEACHER" and tid:
        return (f"当前登录用户是教师，teacherId={tid}。调用 create_class/update_class/"
                f"create_course/update_course 时 ownerTeacherId 一律填 {tid}（本人），"
                f"不要向用户询问负责教师，即使用户提到其他教师的姓名。")
    if role == "ADMIN":
        return ("当前登录用户是管理员。ownerTeacherId 必须由用户明确提供教师 ID（数字）；"
                "只给姓名时说明无法按姓名解析，请用户补 ID；用户未提负责人就不要传该字段，"
                "不要猜测或代填。")
    return ""
