"""route：意图分类。

先说清楚一个取舍：**规则优先，模型兜底**。
规则能处理的句子（"张三今天签到了吗""计算机 2 班这周出勤率"）走规则，
是因为它们占实际使用的绝大多数，而规则是确定的、可测的、不花钱的。
剩下的交给模型。反过来（模型优先）会让同一句话今天走这条路、明天走那条路，
排查问题时无法复现。
"""

from __future__ import annotations

import logging
import re

from app.graph.state import AgentState

log = logging.getLogger(__name__)

_ACTION = [
    re.compile(r"(发起|开一?场?|开个).{0,12}签到"),
    # 口语里的「搞个签到」「建一场签到」「来一场签到」。只认紧挨着的动宾结构，
    # 不写成 `.{0,12}`：那会把「来查一下签到」也算成写操作。
    re.compile(r"(搞|建|办|开|安排|组织|来)(?:一?个|一?场)?(签到|打卡)"),
    re.compile(r"(结束|关闭|收尾).{0,6}签到"),
    re.compile(r"(补签|手工签到)"),
    re.compile(r"(标记|设为|改成).{0,6}(请假|免签)"),
    re.compile(r"(?<!待)(处理|通过|驳回).{0,4}申诉"),
    re.compile(r"(新建|创建|加一?个).{0,4}(班级|课程)"),
    re.compile(r"(归档|停用).{0,4}(班级|课程)"),
    re.compile(r"导入.{0,4}学生"),
    # 改名/重命名：不写这条规则的话，会落进 query，被 _NAME_STATUS 当成"查某人考勤"
    re.compile(r"(改名|重命名|名称改|名字改)"),
]
_DRAFT = [
    re.compile(r"(生成|出一?份|整理).{0,8}(名单|清单|小结|总结)"),
    re.compile(r"(预览|先看看|看一下).{0,8}(导入|名单)"),
    re.compile(r"(起草|拟一?条).{0,6}(回复|意见)"),
]
_QUERY = [
    re.compile(r"(签到|考勤|出勤|缺勤|迟到)"),
    re.compile(r"(名单|统计|趋势|人数)"),
    re.compile(r"申诉"),
    re.compile(r"(谁没来|到了多少|来了多少)"),
]
_CHITCHAT = [
    re.compile(r"^(你好|hi|hello|在吗|嗨)"),
    re.compile(r"(你是谁|你能做什么|怎么用|帮助)"),
    re.compile(r"^(谢谢|好的|收到|嗯)"),
]


def route(state: AgentState) -> dict:
    if state.get("intent") == "blocked":
        return {}

    text = (state.get("query") or "").strip()

    # 快捷问句自带 plan，意图已经确定，不必再猜
    if state.get("forced"):
        intent = "action" if state["forced"].get("write") else "query"
        return {"intent": intent}

    intent = _classify(text)
    log.info("agent-route intent=%s query=%s", intent, text[:40])
    return {"intent": intent}


def _classify(text: str) -> str:
    for pat in _CHITCHAT:
        if pat.search(text):
            return "chitchat"
    for pat in _ACTION:
        if pat.search(text):
            return "action"
    for pat in _DRAFT:
        if pat.search(text):
            return "draft"
    for pat in _QUERY:
        if pat.search(text):
            return "query"
    # 没命中任何规则：交给 planner 决定（有模型时模型会给出工具调用，没有模型时给出引导）
    return "query"
