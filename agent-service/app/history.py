"""按用户持久化历史会话（§8.2 补充）。

Redis 结构（与 checkpoint 同实例、独立前缀，互不干扰）：
- ``hist:{accountId}:convs``           ZSET：conv_id → 最后活跃时间戳
- ``hist:{accountId}:conv:{id}:title`` 会话标题（首条用户消息截断）
- ``hist:{accountId}:conv:{id}:msgs``  JSON 消息列表（role/content/citations）

TTL 与列表容量上限防止无限增长；单用户 Streamlit 场景下整存整取即可，
不需要逐条 append 的原子性。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import redis

log = logging.getLogger(__name__)

_TITLE_MAX = 24


class HistoryStore:
    def __init__(
        self,
        redis_url: str,
        ttl_sec: int = 604800,
        max_convs: int = 20,
        max_msgs: int = 100,
    ) -> None:
        self._redis = redis.Redis.from_url(redis_url, decode_responses=True)
        self._ttl = ttl_sec
        self._max_convs = max_convs
        self._max_msgs = max_msgs

    # ---------------- 写 ----------------

    def save(self, account_id: int | str, conv_id: str, messages: list[dict]) -> None:
        """整存当前会话消息，并登记到会话列表。空会话不落库。"""
        if not conv_id or not messages:
            return
        conv_key = self._conv_key(account_id, conv_id)
        pipe = self._redis.pipeline()
        pipe.set(conv_key + ":msgs", json.dumps(messages[-self._max_msgs:]), ex=self._ttl)
        first_user = next((m["content"] for m in messages if m.get("role") == "user"), "")
        if first_user:
            pipe.set(conv_key + ":title", _title(first_user), ex=self._ttl)
        pipe.zadd(self._index_key(account_id), {conv_id: time.time()})
        pipe.expire(self._index_key(account_id), self._ttl)
        pipe.execute()
        self._trim(account_id)

    def delete(self, account_id: int | str, conv_id: str) -> None:
        conv_key = self._conv_key(account_id, conv_id)
        self._redis.delete(conv_key + ":msgs", conv_key + ":title")
        self._redis.zrem(self._index_key(account_id), conv_id)

    # ---------------- 读 ----------------

    def list_convs(self, account_id: int | str) -> list[dict[str, Any]]:
        """按最后活跃倒序返回 [{id, title, ts}]。"""
        items = self._redis.zrevrange(self._index_key(account_id), 0, self._max_convs - 1,
                                      withscores=True)
        out: list[dict[str, Any]] = []
        for conv_id, ts in items:
            title = self._redis.get(self._conv_key(account_id, conv_id) + ":title") or "（无标题）"
            out.append({"id": conv_id, "title": title, "ts": int(ts)})
        return out

    def load(self, account_id: int | str, conv_id: str) -> list[dict]:
        raw = self._redis.get(self._conv_key(account_id, conv_id) + ":msgs")
        if not raw:
            return []
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            log.warning("history-load-corrupt account=%s conv=%s", account_id, conv_id)
            return []

    # ---------------- 内部 ----------------

    def _index_key(self, account_id: int | str) -> str:
        return f"hist:{account_id}:convs"

    def _conv_key(self, account_id: int | str, conv_id: str) -> str:
        return f"hist:{account_id}:conv:{conv_id}"

    def _trim(self, account_id: int | str) -> None:
        """超过容量上限时淘汰最旧的会话。"""
        overflow = self._redis.zrevrange(self._index_key(account_id), self._max_convs, -1)
        for conv_id in overflow:
            self.delete(account_id, conv_id)


def _title(content: str) -> str:
    text = " ".join(str(content).split())
    return text[:_TITLE_MAX] + ("…" if len(text) > _TITLE_MAX else "")
