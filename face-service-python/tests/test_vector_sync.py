"""向量同步测试：不连真 Java，注入假的 fetch 函数。

覆盖：全量替换、维度不符跳过、学生删除后从快照消失（下一次拉取自然生效）、
base64 解码。
"""
from __future__ import annotations

import base64
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.index.sync import VectorSync   # noqa: E402
from app.index.vectors import VectorIndex  # noqa: E402


def vec512(seed=1) -> str:
    rng = np.random.default_rng(seed)
    v = rng.normal(size=512).astype("<f4")
    v = (v / np.linalg.norm(v)).astype("<f4")
    return base64.b64encode(v.tobytes()).decode()


def snapshot(items):
    return {"count": len(items), "serverTime": "2026-11-19T03:00:00Z",
            "vectors": items}


def test_pull_replaces_index():
    idx = VectorIndex()
    fetch = lambda url: snapshot([  # noqa: E731
        {"studentId": 1, "vector": vec512(1), "dim": 512, "qualityScore": 0.9},
        {"studentId": 2, "vector": vec512(2), "dim": 512, "qualityScore": 0.8},
    ])
    n = VectorSync(idx, "http://java", "tok", fetch=fetch).pull_once()
    assert n == 2
    assert idx.size() == 2
    sid, sim, _ = idx.search_top2(np.frombuffer(base64.b64decode(vec512(1)), dtype="<f4"))
    assert sid == 1 and sim > 0.999


def test_dim_mismatch_skipped():
    idx = VectorIndex()
    # 维度不符的向量必须跳过，不能混进索引
    raw = base64.b64encode(np.zeros(256, dtype="<f4").tobytes()).decode()
    fetch = lambda url: snapshot([  # noqa: E731
        {"studentId": 1, "vector": raw, "dim": 256, "qualityScore": 0.9},
        {"studentId": 2, "vector": vec512(3), "dim": 512, "qualityScore": 0.9},
    ])
    n = VectorSync(idx, "http://java", "tok", fetch=fetch).pull_once()
    assert n == 1
    sid, _, _ = idx.search_top2(np.frombuffer(base64.b64decode(vec512(3)), dtype="<f4"))
    assert sid == 2


def test_student_removal_propagates():
    """学生删除后，Java 快照不再含其向量 → 下一次拉取后索引里消失。"""
    idx = VectorIndex()
    svc = VectorSync(idx, "http://java", "tok",
                     fetch=lambda url: snapshot([
                         {"studentId": 1, "vector": vec512(1), "dim": 512, "qualityScore": 0.9},
                         {"studentId": 2, "vector": vec512(2), "dim": 512, "qualityScore": 0.9},
                     ]))
    svc.pull_once()
    assert idx.size() == 2

    # 学生 1 被删除 → Java 快照只剩学生 2
    svc._fetch = lambda url: snapshot([
        {"studentId": 2, "vector": vec512(2), "dim": 512, "qualityScore": 0.9},
    ])
    svc.pull_once()
    assert idx.size() == 1
    sid, _, _ = idx.search_top2(np.frombuffer(base64.b64decode(vec512(2)), dtype="<f4"))
    assert sid == 2


def test_fetch_failure_keeps_stale_index():
    """拉取失败保留旧索引：课堂签到不能因 Java 抖动全停。"""
    idx = VectorIndex()
    svc = VectorSync(idx, "http://java", "tok",
                     fetch=lambda url: snapshot([
                         {"studentId": 1, "vector": vec512(1), "dim": 512, "qualityScore": 0.9},
                     ]))
    svc.pull_once()
    assert idx.size() == 1

    def boom(url):
        raise ConnectionError("java down")

    svc._fetch = boom           # 切换到会失败的 fetch
    with pytest.raises(ConnectionError):
        svc.pull_once()
    assert idx.size() == 1      # 旧索引仍在
