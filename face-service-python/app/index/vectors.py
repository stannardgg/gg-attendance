"""向量库：1:N 比对的索引。

★ 这里有一个**契约缺口**（已提 CR-002）：
  按单一写入口原则，向量应该由 Java 写 face_vector 表，Python 只读。
  但 B1 冻结的 internal.yaml 里没有「向量下发」端点，Python 拿不到数据
  又不允许直连 MySQL。所以 B3 先用 JSON 文件作为数据源（calibrate.py 的产出
  就能直接喂进来），等 B4 定义好下发端点后把 `source` 换掉即可——
  本类的接口（load/refresh/search）不会变。

1:N 搜索不引入 FAISS 之类的库：
  - 一个班的应到人数 ≤ 几百，512 维 × 500 = 1MB 内存，暴力点积毫秒级；
  - 少一个重依赖，少一类「索引文件损坏」的故障模式。
  等到万级向量再考虑 ANN，那时也要先用数据说话。
"""
from __future__ import annotations

import json
import os
import threading

import numpy as np


class VectorIndex:
    """studentId → 512 维向量的内存索引。线程安全（模型服务是多 worker 的）。"""

    def __init__(self) -> None:
        self._ids: list[int] = []
        self._matrix: np.ndarray | None = None
        self._lock = threading.Lock()

    def replace_all(self, pairs: list[tuple[int, list[float] | np.ndarray]]) -> int:
        """整体替换（加载/刷新都走这里，避免半新半旧的状态）。"""
        if not pairs:
            with self._lock:
                self._ids, self._matrix = [], None
            return 0
        ids = [int(p[0]) for p in pairs]
        mat = np.asarray([p[1] for p in pairs], dtype=np.float32)
        norms = np.linalg.norm(mat, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        mat = mat / norms
        with self._lock:
            self._ids, self._matrix = ids, mat
        return len(ids)

    def load_json(self, path: str) -> int:
        """从 JSON 文件加载，格式：[{"studentId": 1, "vector": [...]}, ...]。

        这是 calibrate.py / 标定数据的落点，也是 B4 前唯一的真实数据源。
        """
        if not os.path.exists(path):
            return 0
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return self.replace_all([(int(d["studentId"]), d["vector"]) for d in data])

    def size(self) -> int:
        with self._lock:
            return len(self._ids)

    def search_top2(self, emb: np.ndarray) -> tuple[int | None, float, float | None]:
        """返回 (top1_student_id, top1_sim, top2_sim)。

        top2 用于 MARGIN_THRESHOLD：top1 与 top2 差距太小说明"像两个人"，
        即使 top1 过阈值也要送人工复核（§10.2）。
        """
        with self._lock:
            if self._matrix is None or not self._ids:
                return None, -1.0, None
            mat = self._matrix
            ids = list(self._ids)
        v = np.asarray(emb, dtype=np.float32).ravel()
        sims = mat @ v
        order = np.argsort(sims)[::-1]
        top1 = int(order[0])
        top2 = float(sims[order[1]]) if len(order) > 1 else None
        return ids[top1], float(sims[top1]), top2


_index = VectorIndex()


def index() -> VectorIndex:
    return _index
