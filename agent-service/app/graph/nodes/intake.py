"""intake：鉴权、能力装配、越权意图拦截（§11.2）。

它**不调 LLM**——这一步只做确定的事：这个人是谁、能用哪些级别、这句话是不是明显越权。
把判断交给模型会让"要不要拦"变成一个概率问题。
"""

from __future__ import annotations

import logging

from app.graph.common import is_sensitive
from app.graph.state import AgentState
from app.tools.catalog import ToolError, fetch_catalog_cached

log = logging.getLogger(__name__)


def intake(state: AgentState) -> dict:
    text = (state.get("query") or "").strip()
    if not text:
        return {"intent": "blocked", "blocked_reason": "空问题",
                "answer": "请说明你想查什么，例如「张三今天签到了吗」。"}

    hit = is_sensitive(text)
    if hit:
        log.info("agent-blocked-intent hit=%s", hit)
        return {
            "intent": "blocked",
            "blocked_reason": hit,
            "answer": "这个请求超出了我的权限范围：我不能读取或导出手机号、身份证等个人信息，"
                      "也不能改变系统的运行规则。需要这类数据请联系管理员。",
        }

    user = state.get("user") or {}
    try:
        catalog = fetch_catalog_cached(state["token"], int(user.get("accountId", 0)))
    except ToolError as exc:
        return {
            "intent": "blocked",
            "blocked_reason": exc.code,
            "error": exc.code,
            "answer": _catalog_error_text(exc.code, exc.message),
        }

    enriched = dict(user)
    enriched["levels"] = catalog.levels
    enriched["classIds"] = catalog.class_ids
    enriched["courseIds"] = catalog.course_ids
    enriched["toolCount"] = len(catalog.tools)
    # 身份以每轮现算的 catalog 为准；旧版本 Java 没下发 identity 时才退回
    # 界面登录时的 profile 快照。负责教师的口径依赖它，快照过期 = 改错负责人。
    if catalog.identity_present:
        enriched["teacherId"] = catalog.teacher_id
        enriched["role"] = catalog.role
    return {"user": enriched, "intent": ""}


def _catalog_error_text(code: str, message: str) -> str:
    if code == "FA-1002":
        return "登录状态已失效，请回到管理端刷新页面后重新打开智能助手。"
    if code == "FA-6004":
        return "当前账号没有使用智能助手的权限（需要教师或管理员）。"
    return f"智能助手暂时不可用（{code}）：{message}"
