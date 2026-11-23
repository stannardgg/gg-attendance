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
    （隔帧 ArcFace 后 last_emb 最多滞后一帧 ~300ms，检测粒度 0.3s→0.6s。）
    """
    if not st.first_emb or not st.last_emb:
        return GateResult(False, None, settings.gates.identity_sim)
    sim = cosine_similarity(np.array(st.first_emb), np.array(st.last_emb))
    return GateResult(sim >= settings.gates.identity_sim, sim, settings.gates.identity_sim)


def gate_passive(st: ChallengeState) -> GateResult:
    """门禁4（被动活体，2026-11-22 接线，默认关）：屏幕重放物理痕迹。

    只否决不放行：真实人脸不触发，开启与否不影响误拒率基线。
    否决规则 = 非轴向孤立峰显著性达标 AND（高频占比达标 OR 过曝白斑达标）
    ——周期性证据必须有独立旁证，单证据不否决。样本取末 N 帧的中位数
    （与活体门禁同款聚合，抗离群帧）。

    **阈值是占位值**：三轮合成标定全部零区分度（见 passive.py 标定状态），
    PASSIVE_GATE=1 开启前必须用真机采集重标（≥10 场真实摄像头 bona fide 定
    上限、≥10 场真机屏幕重放定下限，阈值取几何中点）。门禁关闭时返回
    passed=True、value=None，finish 链路稳定，开启只是配置变化。
    """
    if not settings.gates.passive_gate_enabled or not st.passive_peak:
        return GateResult(True, None, None)
    n = max(1, settings.gates.passive_samples)
    prom = float(np.median(st.passive_peak[-n:]))
    hf = float(np.median(st.passive_hf[-n:])) if st.passive_hf else 0.0
    clip = float(np.median(st.passive_clip[-n:])) if st.passive_clip else 0.0
    periodic = prom >= settings.gates.passive_peak_max
    corroborated = (hf >= settings.gates.passive_hf_max
                    or clip >= settings.gates.passive_clip_max)
    passed = not (periodic and corroborated)
    if not passed:
        logger.warning("passive veto cid=%s prom=%.2f hf=%.3f clip=%.3f（疑似屏幕重放）",
                       st.challenge_id, prom, hf, clip)
    return GateResult(passed, prom, settings.gates.passive_peak_max)


def gate_disguise(st: ChallengeState) -> GateResult:
    """门禁5（伪装/遮挡，CR-025，默认开）：遮挡帧占比。

    frame 路径已把「连续 ≥3 帧遮挡」当场中止（FA-4009），finish 这道门禁
    兜的是「断续遮挡」——口罩反复露脸又戴上、总遮挡帧超过一半的挑战，
    即使动作凑够了也不放行。遮挡只在有脸帧上评估，分母用有脸帧数
    （len(st.ratios)），无脸帧不参与。开关关闭或无遮挡记录时直接放行。
    """
    if not settings.disguise.enabled:
        return GateResult(True, None, None)
    frames = len(st.ratios)
    if frames == 0:
        # 无有脸帧按不足处理（与其他门禁「样本不足判不过」一致；实际上
        # frames=0 会被 baseline_degraded 分支提前拦截，到不了这里）。
        return GateResult(False, None, settings.disguise.occl_frame_max)
    ratio = st.occl_total / frames
    return GateResult(ratio <= settings.disguise.occl_frame_max,
                      round(ratio, 4), settings.disguise.occl_frame_max)


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
        reverse = counter.reverse_count
        # 反向否决（2026-11-22）：hold=1 下单帧噪声可能碰巧在正向凑出一段
        # （滚转残余实测最大 0.108，距阈值 0.12 只差 1σ），但「提示左转却右转」
        # 会在反向留下达标段——反向段存在即说明学生没按指令做，直接判失败。
        # 正向做对时反向段物理上不会出现（基线|yaw|≤0.10 + 返回中性帧 Δ≈0），
        # 所以这条否决只堵误放行，不增加误拒。
        ok = detected >= st.need and reverse == 0
        if detected < st.need or reverse > 0:
            # 判失败才记逐帧偏航，供误拒归因；reverse>0 = 相反动作被检出
            logger.info("转头未计数 base=%.3f yaws=%s detected=%d reverse=%d",
                        st.baseline_yaw, [round(x, 3) for x in st.yaws],
                        detected, reverse)
        return (GateResult(ok, float(detected), float(st.need)),
                detected, st.need)

    if st.action in EYE_ACTIONS:
        if st.baseline_eye <= 0 or not st.eyes:
            # 眼区基线不可得 → 判不了。返回 0/1 而不是抛错：finish 要出可归因的结论。
            return GateResult(False, 0.0, float(st.need)), 0, st.need
        # 姿态一致性闸（2026-11-22）：眼框 ROI 在 start 锁定，头部大幅移动会把
        # 虹膜搬出 ROI，暗度下降被误读成「闭眼」。姿态相对基线超界的帧剔除；
        # 全部帧超界时直接判未完成（眼区统计整体不可信）。
        kept: list[float] = []
        excluded = 0
        if len(st.eye_yaws) == len(st.eyes) and len(st.eye_rolls) == len(st.eyes):
            for e, y, r in zip(st.eyes, st.eye_yaws, st.eye_rolls):
                if (abs(y - st.baseline_yaw) <= settings.action.eye_pose_yaw_max
                        and abs(r - st.baseline_roll) <= settings.action.eye_pose_roll_max):
                    kept.append(e)
                else:
                    excluded += 1
        else:
            # 部署前开的存量 challenge 没有逐帧姿态：跳过闸门，保持原判据
            kept = list(st.eyes)
        if not kept:
            logger.info("闭眼未计数 base=%.3f 姿态超界=%d/%d（头部移动中，眼区统计不可信）",
                        st.baseline_eye, excluded, len(st.eyes))
            return GateResult(False, 0.0, float(st.need)), 0, st.need
        counter = EyeCloseCounter(
            base_ratio=st.baseline_eye,
            abs_drop=settings.action.eye_dark_drop,
            drop_ratio=settings.action.eye_drop_ratio,
            hold_frames=settings.action.eye_hold_frames,
        )
        for e in kept:
            counter.observe(e)
        detected, _ = counter.result()
        if detected < st.need:
            # 只在判失败时打逐帧值：误拒申诉要能回答"差多少、哪一帧没跟上"，
            # 成功路径没必要刷屏（finish 那行已有 actionCount）。
            logger.info("闭眼未计数 base=%.3f ratios=%s excluded=%d",
                        st.baseline_eye, [round(x, 3) for x in kept], excluded)
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
        reverse = counter.reverse_count
        # 反向否决（与转头同构）：提示靠近却退远（或反之），反向达标段存在
        # 即判失败。正向做对时反向段物理上不会出现，不增加误拒。
        ok = detected >= st.need and reverse == 0
        if detected < st.need or reverse > 0:
            logger.info("尺度未计数 base=%.3f scales=%s detected=%d reverse=%d",
                        st.baseline_scale, [round(x, 3) for x in st.scales],
                        detected, reverse)
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
