"""图装配（§11.2 的 mermaid 图逐节点对应）。

编译一次、全局复用：每轮对话都重新 compile 会重复构建节点表，
而且 checkpointer 里的索引也会被反复初始化。
"""

from __future__ import annotations

import logging
from typing import Any

from langgraph.graph import END, START, StateGraph

from app.checkpoint.redis_saver import RedisCheckpointSaver
from app.config import settings
from app.graph.nodes.answer import answer
from app.graph.nodes.confirm import after_confirm, commit, confirm
from app.graph.nodes.intake import intake
from app.graph.nodes.planner import plan
from app.graph.nodes.router import route
from app.graph.nodes.tool_node import tool_node
from app.graph.nodes.verify import after_plan, after_route, verify
from app.graph.state import AgentState

log = logging.getLogger(__name__)

_GRAPH: Any = None


def build(checkpointer: Any = None):
    graph = StateGraph(AgentState)
    graph.add_node("intake", intake)
    graph.add_node("route", route)
    graph.add_node("plan", plan)
    graph.add_node("tools", tool_node)
    graph.add_node("confirm", confirm)
    graph.add_node("commit", commit)
    graph.add_node("answer", answer)

    graph.add_edge(START, "intake")
    graph.add_edge("intake", "route")
    graph.add_conditional_edges("route", after_route, {"plan": "plan", "answer": "answer"})
    graph.add_conditional_edges("plan", after_plan, {"tools": "tools", "answer": "answer"})
    graph.add_conditional_edges("tools", verify,
                                {"plan": "plan", "confirm": "confirm", "answer": "answer"})
    graph.add_conditional_edges("confirm", after_confirm, {"commit": "commit", "answer": "answer"})
    graph.add_edge("commit", "answer")
    graph.add_edge("answer", END)
    return graph.compile(checkpointer=checkpointer)


def get_graph():
    """进程内单例。Redis 不可用时降级到内存检查点，**不**让整个服务起不来。"""
    global _GRAPH
    if _GRAPH is not None:
        return _GRAPH
    try:
        saver = RedisCheckpointSaver(settings.checkpoint_url, settings.checkpoint_ttl_sec)
        saver._redis.ping()  # noqa: SLF001 —— 只在这里探活一次，失败就走降级
        _GRAPH = build(saver)
        log.info("agent-graph-ready checkpointer=redis")
    except Exception as exc:  # noqa: BLE001 —— 任何连接问题都降级，不区分异常类型
        from langgraph.checkpoint.memory import MemorySaver

        _GRAPH = build(MemorySaver())
        log.warning("agent-graph-degraded checkpointer=memory reason=%s", exc)
    return _GRAPH


def reset_graph() -> None:
    """测试用：换掉 checkpointer 后必须重置，否则还会用旧的那个。"""
    global _GRAPH
    _GRAPH = None
