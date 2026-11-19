"""规则层与文案层（不依赖网络，跑得比图测试还快）。"""

from __future__ import annotations

import datetime as dt

from app.graph.nodes.answer import _citations, _describe
from app.graph.nodes.planner import _clean
from app.graph.nodes.router import _classify
from app.ui.confirm import _remaining_seconds


def test_intent_classification():
    assert _classify("张三今天签到了吗") == "query"
    assert _classify("计算机 2 班这周出勤率") == "query"
    assert _classify("有哪些待处理申诉") == "query"
    assert _classify("帮我给计算机 2 班发起高数签到") == "action"
    assert _classify("结束这场签到") == "action"
    assert _classify("生成一下缺勤名单") == "draft"
    assert _classify("你好") == "chitchat"


def test_intent_rename_is_action_not_query():
    """「把 X 改名为 Y」曾经落到 query，被 _NAME_STATUS 当成「查 X 的考勤」。"""
    assert _classify("把计算机 2 班改名为计算机 3 班") == "action"
    assert _classify("将 E2E课 重命名为 高等数学") == "action"
    # 「改成请假」仍应被更早的补签/请假规则吃掉，不能被改名规则抢走
    assert _classify("把张三改成请假") == "action"


def test_question_words_are_not_names():
    """「本周有多少场签到」问的是场次，不是叫「有多少场」的学生。"""
    from app.graph.nodes.planner import _NAME_STATUS, _QUESTION_WORDS, _SESSION_COUNT

    text = "本周有多少场签到"
    assert _SESSION_COUNT.search(text), "应先命中场次规则"
    m = _NAME_STATUS.search(text)
    assert m and any(w in m.group("name") for w in _QUESTION_WORDS), \
        "护栏要能识别出「有多少场」不是人名"


def test_clean_strips_colloquial_prefix():
    assert _clean("帮我 计算机 2 班") == "计算机 2 班"
    assert _clean("计算机 2 班 的") == "计算机 2 班"


def test_citations_format():
    text = _citations([{"tool": "list_sessions", "params": {"dateFrom": "2026-11-19"}, "result": {"items": [1, 2]}}])
    assert "list_sessions" in text
    assert "命中 2 条" in text


def test_describe_session_summary():
    text = _describe("get_session_summary", {
        "className": "计算机 1 班", "courseName": "高数",
        "total": 10, "present": 8, "late": 1, "absent": 1, "rate": 0.9,
    })
    assert "出勤率 90.00%" in text


def test_countdown_expired_and_future():
    past = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) - dt.timedelta(seconds=5)).isoformat()
    future = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(seconds=30)).isoformat()
    assert _remaining_seconds(past) == 0
    assert 25 <= _remaining_seconds(future) <= 30
