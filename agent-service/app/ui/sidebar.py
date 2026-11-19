"""侧栏：身份、能力级别、数据范围、快捷问句。

快捷问句的作用不止"省打字"：**它是不依赖模型的兜底入口**。
没配 Key 时，Agent 依然要能完成一次真实查询——否则"未配置模型"就变成了"整个功能不可用"。
"""

from __future__ import annotations

from typing import Any, Callable

import streamlit as st

_LEVEL_LABEL = {"L0": "只读查询", "L1": "生成草稿", "L2": "写操作（需确认）", "L3": "定时批量"}


def render_sidebar(user: dict[str, Any], on_quick: Callable[[], None] | None,
                   on_reset: Callable[[], None]) -> None:
    with st.sidebar:
        st.markdown("### 身份")
        st.write(f"账号 ID：`{user.get('accountId')}`")
        st.write(f"角色：`{user.get('role')}`")

        levels = user.get("levels") or {}
        if levels:
            st.markdown("### 能力级别")
            for key in ("L0", "L1", "L2", "L3"):
                on = levels.get(key)
                st.write(f"{'✅' if on else '⛔'} {key} · {_LEVEL_LABEL.get(key, '')}")

        scope_classes = user.get("classIds") or []
        scope_courses = user.get("courseIds") or []
        st.markdown("### 数据范围")
        if user.get("role") == "ADMIN":
            st.write("管理员：全部班级与课程")
        else:
            st.write(f"班级 {len(scope_classes)} 个 / 课程 {len(scope_courses)} 个")

        st.divider()
        st.markdown("### 快捷问句")
        if st.button("今天有哪些签到", use_container_width=True):
            _send("今天有哪些签到", {"tool": "list_sessions",
                                     "params": {"dateFrom": _today(), "dateTo": _today()}})
        if st.button("有哪些待处理申诉", use_container_width=True):
            _send("有哪些待处理申诉", {"tool": "list_pending_appeals", "params": {}})

        st.markdown("**发起签到（演示写操作）**")
        cls = st.text_input("班级名称", key="demo_class", placeholder="计算机 2024 级 1 班")
        crs = st.text_input("课程名称", key="demo_course", placeholder="高等数学（上）")
        if st.button("发起签到", use_container_width=True, disabled=not (cls and crs)):
            _send(f"帮我给{cls}发起{crs}签到", None)

        st.divider()
        if st.button("开始新会话", use_container_width=True):
            on_reset()
            st.rerun()


def _today() -> str:
    import datetime as dt

    return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat()


def _send(prompt: str, forced: dict[str, Any] | None) -> None:
    st.session_state["quick_prompt"] = prompt
    if forced:
        st.session_state["quick_forced"] = forced
    st.rerun()
