"""Java 内部接口客户端：commit 签到结果。

只在三道门禁全部通过后调用。失败的处理原则：
  - FA-3003（已签到）视为成功——重复提交是正常的网络重试；
  - 其它失败**不能静默**：签到没落库却告诉学生"成功"，是最坏的结果。
    返回 passed=False + rejectGate=commit，让学生重来一次挑战（60 秒的代价）。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from app.config import settings


class JavaError(RuntimeError):
    def __init__(self, code: str, message: str, status: int):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.status = status


def _call(path: str, body: dict, timeout: float = 8.0) -> dict:
    url = settings.java.base_url.rstrip("/") + path
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-Internal-Token": settings.java.internal_token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data.get("code") not in (None, "FA-0000"):
                raise JavaError(data.get("code", "FA-9001"),
                                data.get("message", ""), resp.status)
            return data.get("data") or {}
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8"))
        except Exception:                                       # noqa: BLE001
            payload = {}
        raise JavaError(payload.get("code", "FA-9001"),
                        payload.get("message", str(e)), e.code) from e


def commit_checkin(session_id: int, student_id: int, similarity: float,
                   liveness_median: float | None, identity_sim: float | None,
                   action_count: int, action_seq: str) -> dict:
    """提交签到结果。返回 {attendanceId, status, checkinAt}。"""
    return _call("/internal/checkin/commit", {
        "sessionId": session_id,
        "studentId": student_id,
        "similarity": round(float(similarity), 4),
        "livenessMedian": None if liveness_median is None else round(float(liveness_median), 4),
        "identitySim": None if identity_sim is None else round(float(identity_sim), 4),
        "actionCount": int(action_count),
        "actionSeq": action_seq,
    })
