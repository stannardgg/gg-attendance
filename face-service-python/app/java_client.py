"""Java 内部接口客户端：commit 签到结果、失败计数读写（CR-023）。

只在三道门禁全部通过后调用 commit。失败的处理原则：
  - FA-3003（已签到）视为成功——重复提交是正常的网络重试；
  - 其它失败**不能静默**：签到没落库却告诉学生"成功"，是最坏的结果。
    返回 passed=False + rejectGate=commit，让学生重来一次挑战（60 秒的代价）。

失败计数（CR-023）：finish 判失败后上报 Java 落库（单一写入口原则——
Python 只提交判定结果，权威计数在 Java/MySQL）。start 前先查计数，
把「三次失败后本场锁死」拦在采集开始之前，而不是让第 4 次采集白跑。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

from app.config import settings


class JavaError(RuntimeError):
    def __init__(self, code: str, message: str, status: int):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.status = status


def _request(method: str, path: str, body: dict | None = None,
             params: dict | None = None, timeout: float = 8.0) -> dict:
    url = settings.java.base_url.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = None
    headers = {"X-Internal-Token": settings.java.internal_token}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            if payload.get("code") not in (None, "FA-0000"):
                raise JavaError(payload.get("code", "FA-9001"),
                                payload.get("message", ""), resp.status)
            return payload.get("data") or {}
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8"))
        except Exception:                                       # noqa: BLE001
            payload = {}
        raise JavaError(payload.get("code", "FA-9001"),
                        payload.get("message", str(e)), e.code) from e


def _call(path: str, body: dict, timeout: float = 8.0) -> dict:
    return _request("POST", path, body=body, timeout=timeout)


def commit_checkin(session_id: int, student_id: int, similarity: float,
                   liveness_median: float | None, identity_sim: float | None,
                   action_count: int, action_seq: str) -> dict:
    """提交签到结果。返回 {attendanceId, status, checkinAt, studentName}。

    checkinAt 由 Java 侧写入（attendance.checkin_at），finish 结果原样透传给前端；
    没有它，成功页只能显示设备本地时间或 "—"。
    """
    return _call("/internal/checkin/commit", {
        "sessionId": session_id,
        "studentId": student_id,
        "similarity": round(float(similarity), 4),
        "livenessMedian": None if liveness_median is None else round(float(liveness_median), 4),
        "identitySim": None if identity_sim is None else round(float(identity_sim), 4),
        "actionCount": int(action_count),
        "actionSeq": action_seq,
    })


def checkin_attempt_status(session_id: int, student_id: int) -> dict:
    """查刷脸失败计数（CR-023）。返回 {failCount, retryLeft, blocked}。"""
    return _request("GET", "/internal/checkin/attempt",
                    params={"sessionId": session_id, "studentId": student_id},
                    timeout=5.0)


def record_checkin_failure(session_id: int, student_id: int, reject_gate: str) -> dict:
    """上报一次识别失败。返回计数状态（含最新 retryLeft，供结果页渲染）。"""
    return _call("/internal/checkin/attempt/fail", {
        "sessionId": session_id,
        "studentId": student_id,
        "rejectGate": reject_gate,
    })
