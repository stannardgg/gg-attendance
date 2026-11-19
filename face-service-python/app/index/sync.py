"""向量快照同步（CR-002，2026-11-19 批准）。

设计：
  - 每 60 秒向 Java 拉一次**全量快照**，然后整体替换内存索引；
  - 学生被删除/归档后，下一次拉取自然消失（Java 侧只返回 ACTIVE 学生的向量）；
  - 拉取失败不打断服务：保留旧索引继续比对，下个周期重试——
    课堂签到不能因为 Java 一次网络抖动就全停。

为什么全量而不是增量游标：删除传播靠全量替换天然覆盖，
快照还有自愈性（Python 侧状态漂移自动修正）。1MB/60s 的流量可接受，
这个取舍写在了 CR-002 的实现说明里。
"""
from __future__ import annotations

import base64
import logging
import threading

import numpy as np

logger = logging.getLogger("face-service.vector-sync")


class VectorSync:
    def __init__(self, vector_index, base_url: str, token: str,
                 fetch=None, dim: int = 512):
        self.index = vector_index
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.dim = dim
        self._fetch = fetch or self._http_get
        self._stop = threading.Event()

    # ---------------- 拉取 ----------------
    def pull_once(self) -> int:
        """拉一次快照并整体替换索引。返回向量条数。"""
        data = self._fetch(self._url())
        vectors = data.get("vectors", [])
        pairs = []
        skipped = 0
        for item in vectors:
            try:
                raw = base64.b64decode(item["vector"], validate=True)
                vec = np.frombuffer(raw, dtype="<f4")
            except Exception:                                       # noqa: BLE001
                skipped += 1
                continue
            if vec.size != int(item.get("dim", self.dim)) or vec.size != self.dim:
                # 维度不符说明模型换代了（如换了 256 维模型），静默收下会把
                # 不同空间的向量混进一个索引里比对——必须跳过并告警
                logger.error("vector dim mismatch studentId=%s dim=%s",
                             item.get("studentId"), vec.size)
                skipped += 1
                continue
            pairs.append((int(item["studentId"]), vec))
        n = self.index.replace_all(pairs)
        if skipped:
            logger.warning("snapshot skipped %d malformed vectors", skipped)
        logger.info("vector snapshot loaded %d entries (serverTime=%s)",
                    n, data.get("serverTime"))
        return n

    def _url(self) -> str:
        return self.base_url + "/internal/face/vectors"

    def _http_get(self, url: str) -> dict:
        import json as _json
        import urllib.request
        req = urllib.request.Request(
            url, headers={"X-Internal-Token": self.token}, method="GET")
        with urllib.request.urlopen(req, timeout=10) as resp:
            return _json.loads(resp.read().decode())

    # ---------------- 后台轮询 ----------------
    def start_background(self, interval: float = 60.0) -> threading.Thread:
        def loop():
            # 先立即拉一次（失败不致命：索引为空时比对会全部 FA-4005，
            # 所以首次拉取失败要重试几次再进入正常节奏）
            for attempt in range(3):
                try:
                    self.pull_once()
                    break
                except Exception as exc:                        # noqa: BLE001
                    logger.error("initial vector pull failed (%d/3): %s",
                                 attempt + 1, exc)
                    if self._stop.wait(5.0):
                        return
            while not self._stop.wait(interval):
                try:
                    self.pull_once()
                except Exception as exc:                        # noqa: BLE001
                    logger.error("vector pull failed, keep stale index: %s", exc)

        t = threading.Thread(target=loop, name="vector-sync", daemon=True)
        t.start()
        return t

    def stop(self) -> None:
        self._stop.set()
