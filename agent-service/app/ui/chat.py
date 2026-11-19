"""对话区渲染。"""

from __future__ import annotations

import streamlit as st

from app.ui.citations import render_citations


def render_history(messages: list[dict]) -> None:
    for msg in messages:
        with st.chat_message("user" if msg["role"] == "user" else "assistant"):
            st.markdown(msg["content"])
            if msg.get("citations"):
                render_citations(msg["citations"])
