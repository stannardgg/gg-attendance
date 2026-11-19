"""确认卡片（§11.5：少一项即不合格）。

卡片上必须有：中文动作描述、影响的资源、影响条数、字段级 diff、可否撤销与截止时间、令牌倒计时。
倒计时归零后按钮必须禁用——这不是装饰，是防止用户以为"点了就行"却收到 FA-6002。
"""

from __future__ import annotations

import datetime as dt
from typing import Any

import streamlit as st

SHANGHAI = dt.timezone(dt.timedelta(hours=8))


def _remaining_seconds(expires_at: str | None) -> int:
    if not expires_at:
        return 0
    try:
        target = dt.datetime.fromisoformat(expires_at)
    except ValueError:
        return 0
    if target.tzinfo is None:
        target = target.replace(tzinfo=SHANGHAI)
    return max(0, int((target - dt.datetime.now(SHANGHAI)).total_seconds()))


def render_confirm(pending: dict[str, Any]) -> bool | None:
    """返回 True(确认) / False(取消) / None(还没决定)。"""
    decision = st.session_state.pop("_confirm_decision", None)
    if decision is not None:
        return decision

    preview = pending.get("preview") or {}
    expires_at = pending.get("expiresAt")
    remaining = _remaining_seconds(expires_at)

    with st.container(border=True):
        st.subheader("需要你确认")
        st.markdown(f"**{preview.get('actionLabel') or pending.get('tool')}**")
        if preview.get("resourceLabel"):
            st.markdown(f"影响对象：`{preview['resourceLabel']}`")
        if preview.get("affectedSummary"):
            st.markdown(preview["affectedSummary"])
        st.markdown(f"影响条数：**{preview.get('affectedCount', 0)}**")

        diffs = preview.get("fieldDiffs") or []
        if diffs:
            st.table([
                {"字段": d.get("label") or d.get("field"),
                 "变更前": d.get("before"),
                 "变更后": d.get("after")}
                for d in diffs
            ])

        if preview.get("undoable"):
            until = preview.get("undoableUntil") or "30 分钟内"
            st.caption(f"可撤销：是（截止 {until}）")
        else:
            st.caption("可撤销：否 —— " + (preview.get("irreversibleHint") or "该操作不可逆"))

        if remaining > 0:
            st.caption(f"确认令牌剩余 {remaining} 秒")
        else:
            st.error("确认令牌已过期，请重新发起这次操作。")

        col1, col2 = st.columns(2)
        with col1:
            if st.button("确认执行", type="primary", disabled=remaining <= 0,
                         use_container_width=True):
                st.session_state["_confirm_decision"] = True
                st.rerun()
        with col2:
            if st.button("取消", use_container_width=True):
                st.session_state["_confirm_decision"] = False
                st.rerun()
    return None
