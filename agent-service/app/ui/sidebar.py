"""侧栏：身份、数据范围、历史会话、快捷问句。

快捷问句的作用不止"省打字"：**它是不依赖模型的兜底入口**。
没配 Key 时，Agent 依然要能完成一次真实查询——否则"未配置模型"就变成了"整个功能不可用"。

历史会话按账号隔离存 Redis（app/history.py），点击即恢复消息记录并继续原 thread；
侧栏只做展示与回调，业务逻辑不在 UI 层（D6）。
"""

from __future__ import annotations

import time
from typing import Any, Callable

import streamlit as st


def render_sidebar(
    user: dict[str, Any],
    on_quick: Callable[[], None] | None,
    on_reset: Callable[[], None],
    history_convs: list[dict[str, Any]] | None = None,
    current_conv: str | None = None,
    on_open_history: Callable[[str], None] | None = None,
    on_delete_history: Callable[[str], None] | None = None,
) -> None:
    with st.sidebar:
        st.markdown("### 身份")
        st.write(f"账号 ID：`{user.get('accountId')}`")
        st.write(f"角色：`{user.get('role')}`")

        scope_classes = user.get("classIds") or []
        scope_courses = user.get("courseIds") or []
        st.markdown("### 数据范围")
        if user.get("role") == "ADMIN":
            st.write("管理员：全部班级与课程")
        else:
            st.write(f"班级 {len(scope_classes)} 个 / 课程 {len(scope_courses)} 个")

        # ---- 历史会话 ----
        st.divider()
        st.markdown("### 历史会话")
        if history_convs:
            for conv in history_convs:
                is_current = conv["id"] == current_conv
                row = st.columns([5, 1])
                label = f"{'📌 ' if is_current else ''}{conv['title']}"
                if row[0].button(label, key=f"hist-open-{conv['id']}",
                                 use_container_width=True,
                                 help=_relative_time(conv.get("ts"))):
                    if on_open_history:
                        on_open_history(conv["id"])
                        st.rerun()
                if row[1].button("🗑", key=f"hist-del-{conv['id']}",
                                 help="删除这条历史会话"):
                    if on_delete_history:
                        on_delete_history(conv["id"])
                        st.rerun()
        else:
            st.caption("暂无历史会话")

        st.divider()
        st.markdown("### 快捷问句")
        if st.button("今天有哪些签到", use_container_width=True):
            _send("今天有哪些签到", {"tool": "list_sessions",
                                     "params": {"dateFrom": _today(), "dateTo": _today()}})
        if st.button("有哪些待处理申诉", use_container_width=True):
            _send("有哪些待处理申诉", {"tool": "list_pending_appeals", "params": {}})
        # 不塞 forced：让 planner 走导出规则，服务端没升级时会得到
        # 「还没有启用导出能力」的明确提示，而不是一句「未知工具」。
        if st.button("导出近两天考勤记录", use_container_width=True):
            _send("导出近两天考勤记录", None)

        st.markdown("**发起签到（演示写操作）**")
        cls = st.text_input("班级名称", key="demo_class", placeholder="计算机 2024 级 1 班")
        crs = st.text_input("课程名称", key="demo_course", placeholder="高等数学（上）")
        if st.button("发起签到", use_container_width=True, disabled=not (cls and crs)):
            _send(f"帮我给{cls}发起{crs}签到", None)

        st.divider()
        if st.button("开始新会话", use_container_width=True):
            on_reset()
            st.rerun()


def _relative_time(ts: int | float | None) -> str:
    if not ts:
        return ""
    delta = int(time.time() - ts)
    if delta < 60:
        return "刚刚"
    if delta < 3600:
        return f"{delta // 60} 分钟前"
    if delta < 86400:
        return f"{delta // 3600} 小时前"
    return f"{delta // 86400} 天前"


def _today() -> str:
    import datetime as dt

    return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat()


def _send(prompt: str, forced: dict[str, Any] | None) -> None:
    st.session_state["quick_prompt"] = prompt
    if forced:
        st.session_state["quick_forced"] = forced
    st.rerun()
