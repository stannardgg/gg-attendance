"""verify：判空、判歧义、判下一步去哪（§11.2）。

它是图里的"岔路口"，也是最容易被写成 if-else 泥潭的地方。
这里只保留三条判断，顺序不能换：
1. 有 pending（写操作预览）→ 去 confirm
2. 还有计划 → 继续 plan
3. 否则 → answer
"""

from __future__ import annotations

from app.graph.state import AgentState


def verify(state: AgentState) -> str:
    if state.get("pending"):
        return "confirm"
    if state.get("plan"):
        return "plan"
    return "answer"


def after_route(state: AgentState) -> str:
    if state.get("intent") in ("chitchat", "blocked"):
        return "answer"
    return "plan"


def after_plan(state: AgentState) -> str:
    """plan 可能直接给出结论（例如名称匹配不到，反问用户），此时不必再进 tools。"""
    return "tools" if state.get("plan") else "answer"
