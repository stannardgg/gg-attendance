"""对话区渲染。"""

from __future__ import annotations

import base64
import binascii
import hashlib

import streamlit as st

from app.ui.citations import render_citations


def render_history(messages: list[dict]) -> None:
    for idx, msg in enumerate(messages):
        with st.chat_message("user" if msg["role"] == "user" else "assistant"):
            st.markdown(msg["content"])
            _render_attachments(msg.get("attachments") or [], f"m{idx}")
            if msg.get("citations"):
                render_citations(msg["citations"])


def _render_attachments(attachments: list[dict], prefix: str) -> None:
    """把工具生成的文件渲染成下载按钮。

    文件体以 base64 存在消息里（见 app/main.py 的 _append_result）：
    Streamlit 每次交互都会重跑整个脚本，落盘再读反而要处理清理与并发，
    几十 KB 的表直接放会话状态最省事。
    """
    for att in attachments:
        raw = _decode(att.get("contentBase64"))
        if raw is None:
            st.caption(f"文件「{att.get('fileName') or '考勤记录.xlsx'}」内容已丢失，请重新导出。")
            continue
        st.download_button(
            label=f"下载 {att.get('fileName') or '考勤记录.xlsx'}",
            data=raw,
            file_name=str(att.get("fileName") or "考勤记录.xlsx"),
            mime=str(att.get("mimeType") or "application/octet-stream"),
            key=_key(att, prefix),
        )


def _decode(content: object) -> bytes | None:
    if not isinstance(content, str) or not content:
        return None
    try:
        return base64.b64decode(content, validate=True)
    except (binascii.Error, ValueError):
        return None


def _key(att: dict, prefix: str = "") -> str:
    """控件 key 必须稳定且唯一。

    内容哈希保证「同一份文件重绘不换 key」；前缀（消息下标）保证
    「导出两次同样范围、内容完全相同」时两条消息不会撞 key——
    Streamlit 撞 key 是直接抛异常，整页白屏。
    """
    digest = hashlib.sha1(str(att.get("contentBase64") or "").encode()).hexdigest()[:10]
    return f"dl-{prefix}-{digest}" if prefix else f"dl-{digest}"
