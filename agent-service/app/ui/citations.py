"""引用展示：「数据来源：工具名 / 条件 / 命中 N 条」（§11.7）。

为什么一定要展示这个：教师看到"出勤率 87%"时，必须能知道这是按哪个班、哪段时间算的。
没有引用，模型给什么数字就只能信什么数字。
"""

from __future__ import annotations

from typing import Any

import streamlit as st


def render_citations(citations: list[dict[str, Any]]) -> None:
    if not citations:
        return
    lines = []
    for c in citations:
        params = c.get("params") or {}
        cond = "、".join(f"{k}={v}" for k, v in params.items()) or "无条件"
        lines.append(f"- `{c.get('tool')}` / {cond} / 命中 {c.get('count')} 条")
    st.caption("数据来源：\n" + "\n".join(lines))
