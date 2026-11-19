"""挑战服务：start / frame / finish 的业务编排。

三条设计决定（都来自 §4.4.3 的实测教训）：
  1. **基线帧缺失不拒绝**：降级为序列首帧并打 baselineDegraded 标记。
     基线不可得是系统侧问题，让学生白跑一次采集是拿用户体验换工程师的省事。
  2. **ROI 在第一帧锁定**（192 对齐空间坐标），后续帧只跑检测不跑关键点——
     每帧省一次模型调用，也避免关键点漂移把 ROI 带跑。
  3. **所有判定数据存 Redis state**，进程内存只留模型。4 worker 部署时
     worker1 收的帧 worker2 照样算得出来。
"""
from __future__ import annotations

import cv2
import numpy as np

from app.challenge import gates
from app.challenge.action import mouth_dark_ratio
from app.challenge.store import ChallengeState, ChallengeStore
from app.common.tickets import TicketError, parse
from app.config import settings
from app.engine import detector, embedder, landmarker, liveness
from app.index.vectors import index


class ChallengeService:
    def __init__(self, store: ChallengeStore):
        self.store = store

    # ---------------- start ----------------
    def start(self, ticket: str, baseline_bgr: np.ndarray) -> dict:
        try:
            claims = parse(ticket)
        except TicketError as exc:
            raise PermissionError(str(exc)) from exc

        session_id = int(claims["sid"])
        student_id = int(claims["studentId"])

        # 动作从白名单里选。B3 阶段固定 MOUTH_ONCE（B4 由会话上下文下发）
        action = "MOUTH_ONCE"

        baseline_ratio: float | None = None
        degraded = True
        roi: tuple[int, int, int, int] | None = None

        face = detector().detect_largest(baseline_bgr)
        if face is not None:
            try:
                pts, crop = landmarker().landmarks(baseline_bgr, face.bbox)
                rect = landmarker().mouth_rect(pts)
                gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                baseline_ratio = mouth_dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])
                roi = rect
                degraded = False
            except ValueError:
                degraded = True

        st = self.store.create(
            session_id=session_id,
            student_id=student_id,
            ticket_jti=str(claims.get("jti", "")),
            action=action,
            baseline_ratio=baseline_ratio if baseline_ratio is not None else 0.02,
            baseline_degraded=degraded,
        )
        if roi is not None:
            st.roi_x1, st.roi_y1, st.roi_x2, st.roi_y2 = roi
            self.store.save(st)

        return {
            "challengeId": st.challenge_id,
            "action": st.action,
            "instruction": "请张一次嘴，约半秒",
            "windowMs": st.window_ms,
            "minFrames": st.min_frames,
            "baselineDegraded": st.baseline_degraded,
            "needCount": st.need,
            "expiresIn": settings.challenge.ttl_sec,
        }

    # ---------------- frame ----------------
    def frame(self, challenge_id: str, frame_bgr: np.ndarray, seq: int) -> dict:
        st = self.store.get(challenge_id)
        if st is None:
            raise LookupError("挑战不存在或已过期")
        if st.closed:
            raise PermissionError("该挑战已提交")
        if seq != st.seq:
            # 乱序帧直接丢弃：按 seq 顺序计数是动作统计正确性的前提
            return {"accepted": False, "seq": seq, "hint": None,
                    "progress": self._progress(st), "detectedCount": self._count(st),
                    "requiredCount": st.need}

        faces = detector().detect(frame_bgr)
        big = [f for f in faces if f.width >= settings.challenge.multi_face_min_px]

        if len(big) >= 2:
            st.multi_face_run += 1
            st.seq += 1            # 帧已消费，必须推进 seq，否则后续帧全被当乱序
            if st.multi_face_run >= settings.challenge.multi_face_frames:
                st.closed = True
                self.store.save(st)
                raise TooManyFaces("检测到多张人脸")
            self.store.save(st)
            return self._frame_resp(st, accepted=True, hint="请让其他人离开画面")
        st.multi_face_run = 0

        if not big:
            st.no_face_run += 1
            st.seq += 1            # 同上：无脸帧也算一帧
            if st.no_face_run >= settings.challenge.no_face_frames:
                st.closed = True
                self.store.save(st)
                raise NoFace("未检测到人脸")
            self.store.save(st)
            return self._frame_resp(st, accepted=True, hint="请正对摄像头")

        face = max(big, key=lambda f: f.area)
        st.no_face_run = 0

        # ROI 未锁定（基线降级场景）→ 用当前帧的关键点锁定
        if st.roi_x1 < 0:
            pts, crop = landmarker().landmarks(frame_bgr, face.bbox)
            rect = landmarker().mouth_rect(pts)
            st.roi_x1, st.roi_y1, st.roi_x2, st.roi_y2 = rect
            if st.baseline_degraded:
                gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                st.baseline_ratio = mouth_dark_ratio(
                    gray[rect[1]:rect[3], rect[0]:rect[2]])

        aligned = landmarker().align_space(frame_bgr, face.bbox)
        gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
        roi = gray[st.roi_y1:st.roi_y2, st.roi_x1:st.roi_x2]
        ratio = mouth_dark_ratio(roi)

        emb = embedder().embedding(frame_bgr, face.kps)
        if not st.first_emb:
            st.first_emb = emb.tolist()
        st.last_emb = emb.tolist()

        p = liveness().live_probability(frame_bgr, face.bbox)
        if p is not None:
            st.live_probs.append(p)

        st.ratios.append(ratio)
        st.seq += 1
        self.store.save(st)

        return self._frame_resp(st, accepted=True, hint=self._hint(st))

    # ---------------- finish ----------------
    def finish(self, challenge_id: str) -> dict:
        st = self.store.consume(challenge_id)
        if st is None:
            raise LookupError("挑战不存在或已过期")

        if st.baseline_degraded and st.roi_x1 < 0:
            return {"passed": False, "rejectGate": "no_face",
                    "gates": {}, "similarity": None, "matchedStudentId": None,
                    "detectedAction": 0, "frames": 0, "baselineDegraded": True}

        l_g = gates.gate_liveness(st)
        i_g = gates.gate_identity(st)
        a_g, detected, required = gates.gate_action(st)
        all_gates = {"liveness": l_g, "identity": i_g, "actionCount": a_g}

        sid, sim, top2 = index().search_top2(np.array(st.last_emb))
        margin = settings.match.margin_threshold
        match_pass = (
            sid is not None
            and sim is not None
            and sim >= settings.match.pass_threshold
            and (top2 is None or (sim - top2) >= margin)
        )
        m_g = gates.GateResult(match_pass, sim, settings.match.pass_threshold)
        all_gates["match"] = m_g

        passed = l_g.passed and i_g.passed and a_g.passed and m_g.passed
        reject = None
        if not passed:
            for name, g in (("liveness", l_g), ("identity", i_g),
                            ("actionCount", a_g), ("match", m_g)):
                if not g.passed:
                    reject = name
                    break

        return {
            "passed": passed,
            "rejectGate": reject,
            "gates": {k: v.to_dict() for k, v in all_gates.items()},
            "similarity": sim,
            "matchedStudentId": sid,
            "detectedAction": detected,
            "frames": len(st.ratios),
            "baselineDegraded": st.baseline_degraded,
        }

    # ---------------- helpers ----------------
    def _count(self, st: ChallengeState) -> int:
        return gates.gate_action(st)[1]

    def _progress(self, st: ChallengeState) -> float:
        return min(1.0, self._count(st) / max(1, st.need))

    def _hint(self, st: ChallengeState) -> str | None:
        detected = self._count(st)
        if detected >= st.need:
            return None
        if detected == 0:
            return "请张嘴"
        return f"已检测到 {detected} 次，还需 {st.need - detected} 次"

    def _frame_resp(self, st: ChallengeState, accepted: bool, hint: str | None) -> dict:
        return {"accepted": accepted, "seq": st.seq, "hint": hint,
                "progress": self._progress(st), "detectedCount": self._count(st),
                "requiredCount": st.need}


class TooManyFaces(RuntimeError):
    """连续多帧多脸 → 中止（FA-4002）。"""


class NoFace(RuntimeError):
    """连续多帧无脸 → 中止（FA-4001）。"""
