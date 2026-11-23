"""跨轮对话记忆（会话上下文）。

验收场景即线上事故：上一轮模型说「回复『确认』我就提交」，用户回复「确认」，
下一轮模型却答「没有待确认的操作记录」——因为 LLM 每轮只看得到当前这一句。
这里验证三件事：历史能装进 state、能裁剪、能真正出现在发给模型的 messages 里。
"""

from __future__ import annotations

import dataclasses

import pytest
from langgraph.types import Command

from app.graph.common import (
    HISTORY_MAX_MSGS,
    HISTORY_MSG_CHARS,
    llm_history_messages,
)
from app.llm.client import Reply, ToolCall


# ------------------------------------------------------------------ 纯函数裁剪


def test_history_none_is_empty():
    assert llm_history_messages(None) == []
    assert llm_history_messages([]) == []


def test_history_keeps_only_user_and_assistant():
    msgs = llm_history_messages([
        {"role": "system", "content": "x"},
        {"role": "tool", "content": "y"},
        {"role": "user", "content": "建班"},
        {"role": "assistant", "content": "请确认"},
        {"role": "user", "content": ""},          # 空内容剔除
        {"content": "没有 role"},                  # 缺角色剔除
    ])
    assert msgs == [
        {"role": "user", "content": "建班"},
        {"role": "assistant", "content": "请确认"},
    ]


def test_history_caps_count_and_length():
    long = "长" * 999
    msgs = llm_history_messages(
        [{"role": "user", "content": f"m{i}"} for i in range(30)]
        + [{"role": "user", "content": long}]
    )
    assert len(msgs) == HISTORY_MAX_MSGS          # 只留最近 8 条
    assert msgs[-1]["content"] == long[:HISTORY_MSG_CHARS]
    assert msgs[0]["content"] == f"m{30 + 1 - HISTORY_MAX_MSGS}"


def test_history_keeps_chronological_order():
    msgs = llm_history_messages([
        {"role": "user", "content": "第一句"},
        {"role": "assistant", "content": "第一答"},
        {"role": "user", "content": "第二句"},
    ])
    assert [m["content"] for m in msgs] == ["第一句", "第一答", "第二句"]


# ------------------------------------------------------------------ 注入 LLM 报文


def test_planner_receives_history(patch_graph, fake_tools, user, monkeypatch):
    """「确认」必须带着上一轮的记忆去问模型——这是截图事故的直接回归。"""
    import app.graph.nodes.planner as planner_mod
    from app.config import settings

    enabled = dataclasses.replace(settings, llm_api_key="k", llm_model="m",
                                  llm_base_url="http://llm.test")
    monkeypatch.setattr(planner_mod, "settings", enabled)

    captured: dict = {}

    def fake_chat(messages, tools=None):
        captured["messages"] = messages
        return Reply(content="", tool_calls=[ToolCall(
            id="c1", name="create_class",
            arguments={"name": "网络安全一班", "code": "6767"})])

    monkeypatch.setattr(planner_mod, "chat", fake_chat)

    prior = [
        {"role": "user", "content": "帮我创建一个班级，名称网络安全一班，班级码 6767"},
        {"role": "assistant", "content": "好的，先跟你核对一下这次建班的信息：班级名称：网络安全一班。"},
    ]
    _ = fake_tools  # patch_graph 已把 ToolClient 换成 fake，这里仅显式声明依赖
    from app.session import AgentSession
    AgentSession("token-abc", user, "conv1").ask("确认", history=prior)

    msgs = captured["messages"]
    roles = [m["role"] for m in msgs]
    # 历史在当前提问之前，且「确认」是最后一条 user 消息
    assert roles[-1] == "user" and msgs[-1]["content"] == "确认"
    assert any(m.get("role") == "assistant"
               and "核对一下这次建班" in m.get("content", "") for m in msgs)
    # 注入历史时会附一段用法说明，避免模型把历史当成当轮新指令
    assert any(m.get("role") == "system" and "本轮之前" in m.get("content", "")
               for m in msgs)


def test_planner_without_history_has_no_context_block(patch_graph, fake_tools, user,
                                                      monkeypatch):
    """没有历史时不得混入空的上下文说明，报文结构与旧版一致。"""
    import app.graph.nodes.planner as planner_mod
    from app.config import settings

    enabled = dataclasses.replace(settings, llm_api_key="k", llm_model="m",
                                  llm_base_url="http://llm.test")
    monkeypatch.setattr(planner_mod, "settings", enabled)

    captured: dict = {}

    def fake_chat(messages, tools=None):
        captured["messages"] = messages
        return Reply(content="好的，可以告诉我具体要查什么吗？", tool_calls=[])

    monkeypatch.setattr(planner_mod, "chat", fake_chat)

    from app.session import AgentSession
    out = AgentSession("token-abc", user, "conv1").ask("这个助手能干什么")

    msgs = captured["messages"]
    assert not any("本轮之前" in m.get("content", "") for m in msgs)
    assert out["answer"] == "好的，可以告诉我具体要查什么吗？"


def test_chitchat_receives_history(patch_graph, fake_tools, user, monkeypatch):
    """闲聊也要衔接上文：问「你怎么又问我这个」时模型得知道之前聊了什么。"""
    import app.graph.nodes.answer as answer_mod
    import app.graph.nodes.planner as planner_mod
    from app.config import settings

    enabled = dataclasses.replace(settings, llm_api_key="k", llm_model="m",
                                  llm_base_url="http://llm.test")
    monkeypatch.setattr(planner_mod, "settings", enabled)
    monkeypatch.setattr(answer_mod, "settings", enabled)

    captured: dict = {}

    def fake_chat(messages, tools=None):
        captured["messages"] = messages
        return Reply(content="我们刚聊到建班的事，你想继续哪一步？", tool_calls=[])

    monkeypatch.setattr(answer_mod, "chat", fake_chat)

    prior = [{"role": "assistant", "content": "我可以帮你新建班级。"}]
    from app.session import AgentSession
    out = AgentSession("token-abc", user, "conv1").ask("你是谁", history=prior)

    msgs = captured["messages"]
    assert any(m.get("role") == "assistant" and "新建班级" in m.get("content", "")
               for m in msgs)
    assert "刚聊到建班" in out["answer"]


def test_history_survives_checkpoint_roundtrip(patch_graph, fake_tools, user):
    """history 注入后写操作流程不受影响：预览 → 断点 → 恢复，行为与旧行为一致。"""
    fake_tools.responses["create_session"] = {
        "requiresConfirm": True, "confirmToken": "tk-1",
        "expiresAt": "2099-01-01T00:00:00",
        "preview": {"actionLabel": "发起一场签到", "affectedCount": 3, "undoable": True},
    }
    from app.session import AgentSession

    session = AgentSession("token-abc", user, "conv1")
    history = [{"role": "assistant", "content": "先核对一下信息"}]
    session.ask("发起签到", forced={"tool": "create_session", "params": {}, "write": True},
                history=history)

    # 恢复阶段是真正执行：与现有用例同款做法，把预览响应换成提交结果
    fake_tools.responses["create_session"] = {
        "requiresConfirm": False,
        "result": {"sessionId": 9, "className": "计算机 1 班", "courseName": "高数",
                   "expectedCount": 3, "endAt": "2026-11-19T10:10:00"},
        "actionLogId": 55,
    }
    out = session.resume(True)

    assert "已发起签到" in out["answer"]
    assert fake_tools.calls[-1]["confirmToken"] == "tk-1"
