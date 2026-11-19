"""会话门面：把「图 + 检查点 + 中断恢复」包成两个方法。

Streamlit 和 pytest 都调用这一层，保证**测试跑的就是界面跑的那条路**。
如果 UI 里自己拼 invoke 参数，测试通过而界面出问题就是必然的。
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from langgraph.types import Command

from app.config import settings
from app.graph.build import get_graph
from app.graph.state import new_state

log = logging.getLogger(__name__)


class AgentSession:
    def __init__(self, token: str, user: dict[str, Any], conversation_id: str | None = None) -> None:
        self.token = token
        self.user = user
        self.thread_id = f"{user.get('accountId', 0)}:{conversation_id or uuid.uuid4().hex[:12]}"

    def _config(self) -> dict[str, Any]:
        return {
            "configurable": {"thread_id": self.thread_id},
            "recursion_limit": settings.recursion_limit,
        }

    def ask(self, query: str, forced: dict[str, Any] | None = None) -> dict[str, Any]:
        state = new_state(query, self.token, self.user, forced)
        state["conversation_id"] = self.thread_id.split(":")[-1]
        return self._run(state)

    def resume(self, approved: bool) -> dict[str, Any]:
        """从 confirm 断点继续。thread_id 不变 —— 这正是不把会话存在内存里的意义。"""
        return self._run(Command(resume={"approved": approved}))

    def _run(self, payload: Any) -> dict[str, Any]:
        graph = get_graph()
        result = graph.invoke(payload, self._config())
        out = normalise(result)
        out["thread_id"] = self.thread_id
        return out


def normalise(result: dict[str, Any]) -> dict[str, Any]:
    """把图的返回值压成 UI/测试都用得上的扁平结构。"""
    interrupts = result.get("__interrupt__") or ()
    pending = None
    if interrupts:
        # Interrupt 对象在不同版本里字段名有差异，取 value 即可
        first = interrupts[0]
        pending = getattr(first, "value", first)
    return {
        "answer": result.get("answer") or "",
        "intent": result.get("intent"),
        "pending": pending if pending else None,
        "action_log_id": result.get("last_action_log_id"),
        "undoable_until": result.get("undoable_until"),
        "error": result.get("error"),
        "citations": result.get("citations") or [],
        "tool_results": result.get("tool_results") or [],
        "thread_id": ((result.get("__config__") or {}).get("configurable") or {}).get("thread_id"),
    }
