"""三道硬门禁与判定编排（§4.4.4）。

AND 关系，逐项回传 value/threshold——这是「误拒率 ≤5% 可归因」的前提：
学生申诉时，教师要看的是"哪道门禁、差多少"，而不是一句"没通过"。

match 门禁（1:N）依赖向量库，向量来源见 index/vectors.py 的说明。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

import numpy as np

from app.challenge.action import EyeCloseCounter, MouthActionCounter, ScaleActionCounter, TurnActionCounter
from app.challenge.store import (
    EYE_ACTIONS, SCALE_ACTIONS, TURN_ACTIONS, TURN_DIRECTIONS, ChallengeState,
)
from app.config import settings
from app.engine.landmark import cosine_similarity

# 用业务 logger（main.py 给它配了 handler 并设成 INFO）。用 __name__ 会被 root
# 的默认 WARNING 吃掉——判定明细等于没记。
logger = logging.getLogger("face-service")


@dataclass
class GateResult:
    passed: bool
    value: float | None
    threshold: float | None

    def to_dict(self) -> dict:
        return {"passed": self.passed, "value": self.value, "threshold": self.threshold}


def gate_liveness(st: ChallengeState) -> GateResult:
    """门禁1：单帧活体概率取中位数（比均值抗离群）。样本不足按不足处理。"""
    samples = st.live_probs[-settings.gates.liveness_samples:]
    if not samples:
        return GateResult(False, None, settings.gates.liveness_threshold)
    median = float(np.median(samples))
    return GateResult(median >= settings.gates.liveness_threshold,
                      median, settings.gates.liveness_threshold)


def gate_identity(st: ChallengeState) -> GateResult:
    """门禁2：首末帧身份一致性，拦「中途换人」。

    为什么用首末而不是每帧两两比：换人发生在序列中间，
    末帧不是开头那个人——首末对比就能发现，而且只要 1 次比对。
    """
    if not st.first_emb or not st.last_emb:
        return GateResult(False, None, settings.gates.identity_sim)
    sim = cosine_similarity(np.array(st.first_emb), np.array(st.last_emb))
    return GateResult(sim >= settings.gates.identity_sim, sim, settings.gates.identity_sim)


def gate_action(st: ChallengeState) -> tuple[GateResult, int, int]:
    """门禁3：动作次数。

    返回 (result, detected, required)。FA-4003 的 hint 要给出具体数值
    （「只检测到 1 次，要求 2 次」），所以 detected/required 必须单独返回。

    四类判据：嘴部看 ROI 暗度、尺度看检测框尺寸、闭眼看眼框暗度、
    转头看 5 点偏航代理（§4.4.1）。除嘴部外都**强依赖基线**——不做「序列首帧
    顶上」，那正是 4.4.2 明令禁止的基线污染（学生已经在做动作，首帧就是污染值）。
    """
    if st.action in TURN_ACTIONS:
        if not st.yaws:
            return GateResult(False, 0.0, float(st.need)), 0, st.need
        counter = TurnActionCounter(
            base_yaw=st.baseline_yaw,
            direction=TURN_DIRECTIONS.get(st.action, 1),
            delta=settings.action.turn_delta,
            hold_frames=settings.action.turn_hold_frames,
        )
        for y in st.yaws:
            counter.observe(y)
        detected, _ = counter.result()
        if detected < st.need:
            # 同闭眼：判失败才记逐帧偏航，供误拒归因
            logger.info("转头未计数 base=%.3f yaws=%s",
                        st.baseline_yaw, [round(x, 3) for x in st.yaws])
        return (GateResult(detected >= st.need, float(detected), float(st.need)),
                detected, st.need)

    if st.action in EYE_ACTIONS:
        if st.baseline_eye <= 0 or not st.eyes:
            # 眼区基线不可得 → 判不了。返回 0/1 而不是抛错：finish 要出可归因的结论。
            return GateResult(False, 0.0, float(st.need)), 0, st.need
        counter = EyeCloseCounter(
            base_ratio=st.baseline_eye,
            abs_drop=settings.action.eye_dark_drop,
            drop_ratio=settings.action.eye_drop_ratio,
            hold_frames=settings.action.eye_hold_frames,
        )
        for e in st.eyes:
            counter.observe(e)
        detected, _ = counter.result()
        if detected < st.need:
            # 只在判失败时打逐帧值：误拒申诉要能回答"差多少、哪一帧没跟上"，
            # 成功路径没必要刷屏（finish 那行已有 actionCount）。
            logger.info("闭眼未计数 base=%.3f ratios=%s",
                        st.baseline_eye, [round(x, 3) for x in st.eyes])
        return (GateResult(detected >= st.need, float(detected), float(st.need)),
                detected, st.need)

    if st.action in SCALE_ACTIONS:
        if st.baseline_scale <= 0:
            return GateResult(False, 0.0, float(st.need)), 0, st.need
        counter = ScaleActionCounter(
            base_scale=st.baseline_scale,
            closer=st.action == "MOVE_CLOSER",
            delta=settings.action.scale_delta,
            hold_frames=settings.action.scale_hold_frames,
        )
        for s in st.scales:
            counter.observe(s)
        detected, _ = counter.result()
        ok = detected >= st.need
        return GateResult(ok, float(detected), float(st.need)), detected, st.need

    counter = MouthActionCounter(
        base_ratio=st.baseline_ratio,
        abs_rise=settings.action.mouth_dark_abs,
        rise_ratio=settings.action.mouth_rise_ratio,
        hold_frames=settings.action.mouth_hold_frames,
    )
    for r in st.ratios:
        counter.observe(r)
    detected, _ = counter.result()
    ok = detected >= st.need
    return GateResult(ok, float(detected), float(st.need)), detected, st.need


@dataclass
class FinishVerdict:
    """finish 的完整判定。passed = 三道门禁 AND。"""

    passed: bool
    reject_gate: str | None            # 失败时指向第一道未过的门禁
    gates: dict
    similarity: float | None           # 1:N 的 top1 相似度
    matched_student_id: int | None
    detected_action: int
    frames: int
    baseline_degraded: bool

    def to_dict(self) -> dict:
        return {
            "passed": self.passed,
            "rejectGate": self.reject_gate,
            "gates": {k: v.to_dict() for k, v in self.gates.items()},
            "similarity": self.similarity,
            "matchedStudentId": self.matched_student_id,
            "detectedAction": self.detected_action,
            "frames": self.frames,
            "baselineDegraded": self.baseline_degraded,
        }
