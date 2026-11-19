"""pytest 公共装置：用假工具替代 Java，用内存检查点替代 Redis。

**为什么要这么测**：图的路由、中断、恢复是纯状态机行为，与网络无关。
把它绑到真实服务上测，失败时分不清是图错了还是环境错了。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from langgraph.checkpoint.memory import MemorySaver

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.graph.build import build  # noqa: E402
from app.tools.catalog import Catalog, ToolError  # noqa: E402


class FakeTools:
    """可编程的假 ToolClient：按工具名返回预置结果，或抛指定错误码。"""

    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.responses: dict[str, dict] = {}
        self.errors: dict[str, ToolError] = {}

    def invoke(self, tool, params, *, confirm_token=None, tool_call_id=None, conversation_id=None):
        self.calls.append({
            "tool": tool, "params": params, "confirmToken": confirm_token,
            "toolCallId": tool_call_id,
        })
        if tool in self.errors:
            raise self.errors[tool]
        return self.responses.get(tool, {"requiresConfirm": False, "result": {"ok": True}})

    def undo(self, action_log_id, conversation_id=None):
        self.calls.append({"tool": "undo", "actionLogId": action_log_id})
        return {"undone": True}


@pytest.fixture
def fake_catalog() -> Catalog:
    return Catalog(
        levels={"L0": True, "L1": True, "L2": True, "L3": False},
        class_ids=[1],
        course_ids=[1],
        tools={
            "list_sessions": {"name": "list_sessions", "level": "L0", "requiresConfirm": False,
                              "paramsSchema": {"type": "object"}, "title": "列表"},
            "list_pending_appeals": {"name": "list_pending_appeals", "level": "L0",
                                     "requiresConfirm": False, "paramsSchema": {"type": "object"}},
            "get_class_stats": {"name": "get_class_stats", "level": "L0", "requiresConfirm": False,
                                "paramsSchema": {"type": "object"}},
            "draft_session": {"name": "draft_session", "level": "L1", "requiresConfirm": False,
                              "paramsSchema": {"type": "object"}},
            "create_session": {"name": "create_session", "level": "L2", "requiresConfirm": True,
                               "undoable": True, "paramsSchema": {"type": "object"}},
        },
    )


@pytest.fixture
def user() -> dict:
    return {
        "accountId": 7,
        "role": "TEACHER",
        "teacherId": 3,
        "levels": {"L0": True, "L1": True, "L2": True, "L3": False},
        "classIds": [1],
        "courseIds": [1],
    }


@pytest.fixture
def memory_graph():
    return build(MemorySaver())


@pytest.fixture
def patch_graph(monkeypatch, memory_graph, fake_catalog, fake_tools):
    """把 session 用到的图、catalog、工具客户端全部换成假的。"""
    import app.graph.nodes.intake as intake_mod
    import app.graph.nodes.planner as planner_mod
    import app.session as session_mod
    import app.tools.registry as registry_mod

    monkeypatch.setattr(session_mod, "get_graph", lambda: memory_graph)
    monkeypatch.setattr(intake_mod, "fetch_catalog_cached", lambda *a, **k: fake_catalog)
    monkeypatch.setattr(planner_mod, "fetch_catalog_cached", lambda *a, **k: fake_catalog)
    monkeypatch.setattr(registry_mod, "ToolClient", lambda *a, **k: fake_tools)
    import app.graph.nodes.tool_node as tool_mod
    import app.graph.nodes.confirm as confirm_mod
    monkeypatch.setattr(tool_mod, "ToolClient", lambda *a, **k: fake_tools)
    monkeypatch.setattr(confirm_mod, "ToolClient", lambda *a, **k: fake_tools)
    return memory_graph


@pytest.fixture
def fake_tools() -> FakeTools:
    return FakeTools()
