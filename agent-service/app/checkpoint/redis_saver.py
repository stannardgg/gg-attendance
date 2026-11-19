"""Redis 检查点。

**为什么不用 `langgraph-checkpoint-redis` 的 RedisSaver**（这是本包唯一的"偏离文档"）：
它基于 RedisVL 建 RediSearch 索引，而部署清单（§12.1）里是 `redis:7-alpine` —— 没有 search 模块。
在本机与生产都不装 Redis Stack 的前提下，那个 RedisSaver 的 `setup()` 直接抛错。
所以这里实现一个只用 KV + ZSET 的 `BaseCheckpointSaver`：
同样的外置存储、同样的 24h TTL、同样支持 `interrupt()` 之后从断点恢复。
已登记 CR-004，建议 v2.3 把「检查点后端」写成"任一 BaseCheckpointSaver 实现"，不要锁死具体库。
"""

from __future__ import annotations

import base64
import json
import logging
import time
from datetime import datetime
from typing import Any, Iterator, Sequence

import redis
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)

log = logging.getLogger(__name__)

PREFIX = "lg:"


def _score(ts: Any) -> float:
    """把 checkpoint 的 ts 转成 ZSET 分数。

    LangGraph 写进来的 `checkpoint["ts"]` 是 ISO 8601 字符串
    （例如 `2026-11-19T08:03:31.910540+00:00`），不是数字——
    直接 float() 会 TypeError/ValueError，整条会话都存不进检查点。
    解析失败退回当前时间：顺序略微失真，也好过整轮对话丢失。
    """
    if isinstance(ts, bool):  # bool 是 int 的子类，别让它混进分数
        return time.time()
    if isinstance(ts, (int, float)):
        return float(ts)
    if isinstance(ts, str) and ts.strip():
        try:
            return datetime.fromisoformat(ts.strip().replace("Z", "+00:00")).timestamp()
        except ValueError:
            log.warning("checkpoint-ts-unparsable ts=%r，改用当前时间", ts)
    return time.time()


class RedisCheckpointSaver(BaseCheckpointSaver):
    """把 checkpoint / metadata / pending writes 以 JSON+BLOB 形式存 Redis。"""

    def __init__(self, redis_url: str, ttl_sec: int = 86400) -> None:
        super().__init__()
        self._redis = redis.Redis.from_url(redis_url, decode_responses=False)
        self._ttl = ttl_sec

    # ---------------- 内部序列化 ----------------

    def _dump(self, value: Any) -> list[str]:
        kind, payload = self.serde.dumps_typed(value)
        return [kind, base64.b64encode(payload).decode("ascii")]

    def _load(self, blob: list[str]) -> Any:
        kind, payload = blob[0], base64.b64decode(blob[1])
        return self.serde.loads_typed((kind, payload))

    @staticmethod
    def _ns(config: RunnableConfig) -> tuple[str, str]:
        configurable = config.get("configurable") or {}
        return str(configurable.get("thread_id", "")), str(configurable.get("checkpoint_ns", ""))

    def _key(self, thread: str, ns: str, checkpoint_id: str) -> str:
        return f"{PREFIX}ckpt:{thread}:{ns}:{checkpoint_id}"

    def _latest_key(self, thread: str, ns: str) -> str:
        return f"{PREFIX}latest:{thread}:{ns}"

    def _writes_key(self, thread: str, ns: str, checkpoint_id: str, task_id: str) -> str:
        return f"{PREFIX}writes:{thread}:{ns}:{checkpoint_id}:{task_id}"

    def _index_key(self, thread: str) -> str:
        return f"{PREFIX}index:{thread}"

    # ---------------- 读 ----------------

    def get_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        thread, ns = self._ns(config)
        if not thread:
            return None
        checkpoint_id = (config.get("configurable") or {}).get("checkpoint_id")
        if not checkpoint_id:
            raw = self._redis.get(self._latest_key(thread, ns))
            if raw is None:
                return None
            checkpoint_id = raw.decode("utf-8")

        raw = self._redis.get(self._key(thread, ns, checkpoint_id))
        if raw is None:
            return None
        record = json.loads(raw)
        checkpoint: Checkpoint = self._load(record["checkpoint"])
        metadata: CheckpointMetadata = self._load(record["metadata"])

        parent_config = None
        if record.get("parent"):
            parent_config = {
                "configurable": {
                    "thread_id": thread,
                    "checkpoint_ns": ns,
                    "checkpoint_id": record["parent"],
                }
            }
        return CheckpointTuple(
            config={
                "configurable": {
                    "thread_id": thread,
                    "checkpoint_ns": ns,
                    "checkpoint_id": checkpoint_id,
                }
            },
            checkpoint=checkpoint,
            metadata=metadata,
            parent_config=parent_config,
            pending_writes=self._read_writes(thread, ns, checkpoint_id),
        )

    def _read_writes(self, thread: str, ns: str, checkpoint_id: str) -> list[tuple[str, str, Any]]:
        out: list[tuple[str, str, Any]] = []
        pattern = f"{PREFIX}writes:{thread}:{ns}:{checkpoint_id}:*"
        for key in self._redis.scan_iter(match=pattern):
            task_id = key.decode("utf-8").rsplit(":", 1)[-1]
            for item in json.loads(self._redis.get(key)):
                out.append((task_id, item[0], self._load(item[1])))
        return out

    def list(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> Iterator[CheckpointTuple]:
        thread = str(((config or {}).get("configurable") or {}).get("thread_id", ""))
        if not thread:
            return iter(())
        raw_ids = self._redis.zrevrange(self._index_key(thread), 0, -1)
        out: list[CheckpointTuple] = []
        for raw in raw_ids:
            checkpoint_id = raw.decode("utf-8")
            item = self.get_tuple({"configurable": {"thread_id": thread, "checkpoint_id": checkpoint_id}})
            if item is None:
                continue
            if before and (before.get("configurable") or {}).get("checkpoint_id") == checkpoint_id:
                continue
            if filter and any(item.metadata.get(k) != v for k, v in filter.items()):
                continue
            out.append(item)
            if limit is not None and len(out) >= limit:
                break
        return iter(out)

    # ---------------- 写 ----------------

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        thread, ns = self._ns(config)
        checkpoint_id = checkpoint["id"]
        parent_id = (config.get("configurable") or {}).get("checkpoint_id")
        record = {
            "checkpoint": self._dump(checkpoint),
            "metadata": self._dump(metadata),
            "parent": parent_id,
        }
        with self._redis.pipeline() as pipe:
            pipe.set(self._key(thread, ns, checkpoint_id), json.dumps(record), ex=self._ttl)
            pipe.set(self._latest_key(thread, ns), checkpoint_id, ex=self._ttl)
            # 索引分数用 checkpoint 自带的时间戳，保证 list() 的顺序与产生顺序一致
            ts = checkpoint.get("ts") or time.time()
            pipe.zadd(self._index_key(thread), {checkpoint_id: _score(ts)})
            pipe.expire(self._index_key(thread), self._ttl)
            pipe.execute()
        return {
            "configurable": {
                "thread_id": thread,
                "checkpoint_ns": ns,
                "checkpoint_id": checkpoint_id,
            }
        }

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        thread, ns = self._ns(config)
        checkpoint_id = str((config.get("configurable") or {}).get("checkpoint_id", ""))
        key = self._writes_key(thread, ns, checkpoint_id, task_id)
        payload = [[channel, self._dump(value)] for channel, value in writes]
        self._redis.set(key, json.dumps(payload), ex=self._ttl)

    def delete_thread(self, thread_id: str) -> None:
        for pattern in (
            f"{PREFIX}ckpt:{thread_id}:*",
            f"{PREFIX}latest:{thread_id}:*",
            f"{PREFIX}writes:{thread_id}:*",
            self._index_key(thread_id),
        ):
            keys = list(self._redis.scan_iter(match=pattern))
            if keys:
                self._redis.delete(*keys)
        log.info("checkpoint-thread-deleted thread=%s", thread_id)

    # ---------------- 异步薄封装 ----------------
    # Streamlit 全程同步调用；保留异步接口是为了以后接 FastAPI 时不用改实现。

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        return self.get_tuple(config)

    async def aput(self, config, checkpoint, metadata, new_versions):
        return self.put(config, checkpoint, metadata, new_versions)

    async def aput_writes(self, config, writes, task_id, task_path=""):
        return self.put_writes(config, writes, task_id, task_path)

    async def adelete_thread(self, thread_id: str) -> None:
        return self.delete_thread(thread_id)
