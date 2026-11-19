"""Agent 服务配置。全部走环境变量（§11.8），不读本地文件。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    raw = os.getenv(name)
    try:
        return int(raw) if raw not in (None, "") else default
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    # 依赖
    java_base_url: str
    internal_token: str
    redis_url: str

    # LLM（可为空 → 优雅降级）
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_temperature: float
    llm_max_tokens: int

    # LangGraph
    checkpoint_url: str
    checkpoint_ttl_sec: int
    recursion_limit: int
    tool_timeout_sec: int
    tool_max_retry: int

    # 能力级别默认值（实际以 Java 下发的 catalog 为准）
    level_l0: bool
    level_l1: bool
    level_l2: bool
    level_l3: bool
    confirm_ttl_sec: int
    undo_window_sec: int

    @property
    def llm_enabled(self) -> bool:
        """没有 Key 就是没配模型。空字符串也算没配——避免"配了个空值还去请求"的行为。"""
        return bool(self.llm_api_key and self.llm_model and self.llm_base_url)

    @property
    def max_steps(self) -> int:
        """plan 最多 4 步（§11.2）。比 recursion_limit 更早触发，给出可读的提示。"""
        return 4


def load_settings() -> Settings:
    return Settings(
        java_base_url=os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
        internal_token=os.getenv("INTERNAL_TOKEN", "dev-internal-token"),
        redis_url=os.getenv("REDIS_URL", "redis://127.0.0.1:6379/0"),
        llm_base_url=os.getenv("LLM_BASE_URL", "").rstrip("/"),
        llm_api_key=os.getenv("LLM_API_KEY", ""),
        llm_model=os.getenv("LLM_MODEL", ""),
        llm_temperature=float(os.getenv("LLM_TEMPERATURE", "0.2")),
        llm_max_tokens=_int("LLM_MAX_TOKENS", 1024),
        checkpoint_url=os.getenv("REDIS_CHECKPOINT_URL", "redis://127.0.0.1:6379/2"),
        checkpoint_ttl_sec=_int("CHECKPOINT_TTL_SEC", 86400),
        recursion_limit=_int("GRAPH_RECURSION_LIMIT", 12),
        tool_timeout_sec=_int("TOOL_TIMEOUT_SEC", 8),
        tool_max_retry=_int("TOOL_MAX_RETRY", 1),
        level_l0=_bool("AGENT_LEVEL_L0", True),
        level_l1=_bool("AGENT_LEVEL_L1", True),
        level_l2=_bool("AGENT_LEVEL_L2", True),
        level_l3=_bool("AGENT_LEVEL_L3", False),
        confirm_ttl_sec=_int("CONFIRM_TTL_SEC", 60),
        undo_window_sec=_int("UNDO_WINDOW_SEC", 1800),
    )


settings = load_settings()
