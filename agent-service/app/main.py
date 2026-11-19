"""Streamlit 入口：只做界面装配，不含业务逻辑（D6）。

业务在 `app/session.py` + `app/graph/**`。这里只负责：
读 code、换 token、把消息画出来、把确认结果送回去。
"""

from __future__ import annotations

import datetime as dt
import logging

import streamlit as st

from app.auth.sso import exchange_code
from app.config import settings
from app.session import AgentSession
from app.tools.catalog import ToolError
from app.tools.registry import ToolClient
from app.ui.chat import render_history
from app.ui.confirm import render_confirm
from app.ui.sidebar import render_sidebar

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger(__name__)

st.set_page_config(page_title="智能助手 · 人脸签到", page_icon="🎓", layout="wide")


def _bootstrap_identity() -> bool:
    if st.session_state.get("token"):
        return True
    code = st.query_params.get("code")
    if not code:
        st.info("请从管理端点「智能助手」进入。这个页面不接受直接访问。")
        return False
    # 管理端的快捷问句用 ?q= 带进来，必须在 clear() 之前读走，否则会和 code 一起被抹掉
    ask = st.query_params.get("q")
    try:
        data = exchange_code(code if isinstance(code, str) else code[0])
    except ToolError as exc:
        st.error(f"登录失败：{exc.message}。请回到管理端重新打开。")
        return False
    # code 已消费，从 URL 里抹掉，避免用户刷新时看到"已失效"的困惑
    st.query_params.clear()
    st.session_state["token"] = data["token"]
    st.session_state["user"] = data["profile"]
    st.session_state["messages"] = []
    if ask:
        st.session_state["auto_prompt"] = ask if isinstance(ask, str) else ask[0]
    st.session_state["session"] = AgentSession(
        data["token"], data["profile"], st.session_state.setdefault("conv", None)
    )
    return True


def _ensure_session() -> AgentSession:
    if "session" not in st.session_state:
        st.session_state["session"] = AgentSession(
            st.session_state["token"], st.session_state["user"], st.session_state.get("conv")
        )
    return st.session_state["session"]


def main() -> None:
    st.title("智能助手")
    if not _bootstrap_identity():
        return

    user = st.session_state["user"]
    session = _ensure_session()
    messages: list[dict] = st.session_state.setdefault("messages", [])

    if not settings.llm_enabled:
        st.warning("未配置模型（LLM_API_KEY），自由问答不可用。下面的快捷问句仍然可以使用。")

    render_sidebar(user, on_quick=None, on_reset=_reset)

    render_history(messages)

    # 待确认的写操作：确认卡必须一直显示到用户处理或令牌过期
    pending = st.session_state.get("pending")
    if pending:
        decision = render_confirm(pending)
        if decision is not None:
            with st.spinner("执行中…"):
                result = session.resume(decision)
            st.session_state["pending"] = None
            _append_result(messages, result)
            st.rerun()

    if st.session_state.get("last_action_log_id") and not pending:
        _render_undo(user)

    prompt = st.chat_input("问我考勤、班级、申诉相关的问题…")
    quick = st.session_state.pop("quick_prompt", None)
    if quick:
        prompt = quick
    if not prompt:
        # 管理端快捷问句经 ?q= 进来，只消费一次，避免每次 rerun 都重发
        prompt = st.session_state.pop("auto_prompt", None)

    if prompt:
        messages.append({"role": "user", "content": prompt})
        with st.spinner("查询中…"):
            result = session.ask(prompt, forced=st.session_state.pop("quick_forced", None))
        _append_result(messages, result)
        st.rerun()


def _reset() -> None:
    import uuid

    st.session_state["conv"] = uuid.uuid4().hex[:12]
    st.session_state["messages"] = []
    st.session_state["pending"] = None
    st.session_state["last_action_log_id"] = None
    st.session_state["session"] = AgentSession(
        st.session_state["token"], st.session_state["user"], st.session_state["conv"]
    )


def _append_result(messages: list[dict], result: dict) -> None:
    if result.get("answer"):
        messages.append({"role": "assistant", "content": result["answer"],
                         "citations": result.get("citations") or []})
    if result.get("pending"):
        st.session_state["pending"] = result["pending"]
    if result.get("action_log_id"):
        st.session_state["last_action_log_id"] = result["action_log_id"]


def _render_undo(user: dict) -> None:
    log_id = st.session_state["last_action_log_id"]
    col1, col2 = st.columns([3, 1])
    with col1:
        st.caption(f"刚才的写操作已留痕：#{log_id}。30 分钟内可撤销。")
    with col2:
        if st.button("撤销这次操作", type="secondary", use_container_width=True):
            try:
                ToolClient(st.session_state["token"], int(user.get("accountId", 0))).undo(log_id)
                st.success("已撤销")
            except ToolError as exc:
                st.error(f"撤销失败：{exc.message}")
            st.session_state["last_action_log_id"] = None


if __name__ == "__main__":
    main()
