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

import logging
import random

import cv2
import numpy as np

from app.challenge import gates
from app.challenge.action import face_scale, mouth_dark_ratio
from app.challenge.store import SCALE_ACTIONS, ChallengeState, ChallengeStore
from app.common.tickets import TicketError, parse
from app.config import settings
from app.engine import detector, embedder, landmarker, liveness
from app.index.vectors import index

logger = logging.getLogger("face-service")

# 指令文案：尺度类动作与嘴部动作的措辞不同（「靠近」没有「几次」的概念）
INSTRUCTIONS = {
    "MOUTH_ONCE": "请张一次嘴，约半秒",
    "MOUTH_TWICE": "请张两次嘴，每次约半秒",
    "MOVE_CLOSER": "请把脸靠近摄像头一点，保持半秒",
    "MOVE_AWAY": "请把脸离摄像头远一点，保持半秒",
}

# 动作未达成时的实时提示（进度提示由 _hint 统一拼）
HINTS = {
    "MOVE_CLOSER": "请再靠近一点",
    "MOVE_AWAY": "请再离远一点",
}


class ChallengeService:
    def __init__(self, store: ChallengeStore):
        self.store = store

    # ---------------- start ----------------
    def start(self, ticket: str, baseline_bgr: np.ndarray,
              action_hint: str | None = None) -> dict:
        try:
            claims = parse(ticket)
        except TicketError as exc:
            raise PermissionError(str(exc)) from exc

        session_id = int(claims["sid"])
        student_id = int(claims["studentId"])

        baseline_ratio: float | None = None
        baseline_scale = 0.0
        degraded = True
        roi: tuple[int, int, int, int] | None = None

        # 基线污染专项（§4.4.3）：基线帧有多张够大的人脸 → 直接拒绝。
        # 此时无法确定"基线"属于谁，继续做只会把 A 的基线配到 B 的脸上。
        _faces = detector().detect(baseline_bgr)
        _big = [f for f in _faces if f.width >= settings.challenge.multi_face_min_px]
        if len(_big) >= 2:
            raise TooManyFaces("基线画面中检测到多张人脸")

        face = detector().detect_largest(baseline_bgr)
        if face is not None:
            baseline_scale = face_scale(face, baseline_bgr.shape[1])
            try:
                pts, crop = landmarker().landmarks(baseline_bgr, face.bbox)
                rect = landmarker().mouth_rect(pts)
                gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                baseline_ratio = mouth_dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])
                roi = rect
                degraded = False
            except ValueError:
                degraded = True

        # 动作必须在基线之后选：尺度类动作的判据就是"相对基线的变化"，
        # 而且还要看这张脸此刻有没有"靠近 / 离远"的余地（见 _action_eligible）。
        # 动作从白名单里**随机**选（§4.4.3）：固定动作等于给预录视频一个稳定的靶子。
        action = self._pick_action(action_hint, face, baseline_bgr)

        st = self.store.create(
            session_id=session_id,
            student_id=student_id,
            ticket_jti=str(claims.get("jti", "")),
            action=action,
            baseline_ratio=baseline_ratio if baseline_ratio is not None else 0.02,
            baseline_degraded=degraded,
            baseline_scale=baseline_scale,
        )
        if roi is not None:
            st.roi_x1, st.roi_y1, st.roi_x2, st.roi_y2 = roi
            self.store.save(st)

        return {
            "challengeId": st.challenge_id,
            "action": st.action,
            "instruction": INSTRUCTIONS.get(st.action, "请按提示完成动作"),
            "windowMs": st.window_ms,
            "minFrames": st.min_frames,
            "baselineDegraded": st.baseline_degraded,
            "needCount": st.need,
            "expiresIn": settings.challenge.ttl_sec,
        }

    def _pick_action(self, hint: str | None, face, frame_bgr: np.ndarray) -> str:
        """随机取动作；仅当显式允许时采纳客户端 hint（CR-010）。

        随机前先剔除此刻**物理上做不到**的动作：
          - 基线帧没有人脸 → 尺度类动作没有基线，判据无从谈起；
          - 脸已经快占满画面 → 不能要求"再靠近"，学生做不到会被判失败；
          - 脸太小 → 再离远就检不出人了，等于让他自己触发 FA-4001。
        剔除只针对单个动作，不合格的那个退出抽签，其余照旧。
        """
        whitelist = settings.action.action_whitelist
        if hint and settings.action.allow_action_hint and hint in whitelist:
            # 验收/联调要能强制指定动作，包括故意指定"做不到"的组合
            return hint
        if hint and hint not in whitelist:
            # 契约外的动作码直接拒绝，避免把未知值写进 attendance.action_seq
            raise ValueError(f"未知动作码：{hint}")

        candidates = [a for a in whitelist if self._action_eligible(a, face, frame_bgr)]
        if not candidates:
            # 兜底回到嘴部动作：它们不依赖人脸尺度，任何距离都能判
            candidates = [a for a in whitelist if a not in SCALE_ACTIONS] or list(whitelist)
        return random.choice(candidates)

    @staticmethod
    def _action_eligible(action: str, face, frame_bgr: np.ndarray) -> bool:
        """该动作在当前画面上是否做得出来。只对尺度类动作有约束。"""
        if action not in SCALE_ACTIONS:
            return True
        if face is None:
            return False
        frame_h = float(frame_bgr.shape[0])
        delta = settings.action.scale_delta
        long_side = max(float(face.width), float(face.height))
        if action == "MOVE_CLOSER":
            # 再放大 delta 之后仍要留在画面里：框被画面边缘截断时尺度就不再增长了，
            # 学生越靠越近、比例却卡住，会被判失败。
            return long_side * (1 + delta) <= frame_h * settings.action.scale_closer_max_frac
        short_side = min(float(face.width), float(face.height))
        return short_side * (1 - delta) >= settings.action.scale_away_min_px

    # ---------------- frame ----------------
    def frame(self, challenge_id: str, frame_bgr: np.ndarray, seq: int) -> dict:
        st = self.store.get(challenge_id)
        if st is None:
            raise LookupError("挑战不存在或已过期")
        if st.closed:
            raise PermissionError("该挑战已提交")
        if seq < st.seq:
            # 过期/重复帧（网络重传、客户端重排）直接丢弃，不动已有计数
            return {"accepted": False, "seq": seq, "hint": None,
                    "progress": self._progress(st), "detectedCount": self._count(st),
                    "requiredCount": st.need}
        if seq > st.seq:
            # 序号缺口：客户端队列丢过帧。不能按「乱序」永久拒收——
            # st.seq 会卡在缺口处，之后每一帧都不相等，整场挑战剩下的窗口全废，
            # 用户只看到「识别失败」而日志里一片 200。缺口按降采样处理，序号对齐到当前帧。
            st.seq = seq

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

        face = max(big, key=lambda f: f.area) if big else None
        if face is None and st.action in SCALE_ACTIONS:
            # 「离远」会把人脸缩小。仍按 90px 判「没有脸」的话，学生照做反而
            # 被 FA-4001 中止——这是判据在惩罚正确的行为。尺度类动作放宽到
            # subject_min_px；多脸计数仍用 90px，免得背景小脸打断正常签到。
            small = [f for f in faces if f.width >= settings.challenge.subject_min_px]
            if small:
                face = max(small, key=lambda f: f.area)

        if face is None:
            st.no_face_run += 1
            st.seq += 1            # 同上：无脸帧也算一帧
            if st.no_face_run >= settings.challenge.no_face_frames:
                st.closed = True
                self.store.save(st)
                raise NoFace("未检测到人脸")
            self.store.save(st)
            return self._frame_resp(st, accepted=True, hint="请正对摄像头")

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
            # 尺度基线缺失才补：只在 hint 强制指定尺度动作、而基线帧又没检到脸时走到这里
            if st.baseline_scale <= 0:
                st.baseline_scale = face_scale(face, frame_bgr.shape[1])

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
        st.scales.append(face_scale(face, frame_bgr.shape[1]))
        st.seq += 1
        self.store.save(st)

        return self._frame_resp(st, accepted=True, hint=self._hint(st))

    # ---------------- finish ----------------
    def finish(self, challenge_id: str) -> dict:
        st = self.store.consume(challenge_id)
        if st is None:
            raise LookupError("挑战不存在或已过期")

        if st.baseline_degraded and st.roi_x1 < 0:
            logger.warning("challenge finish cid=%s sid=%s student=%s reject=no_face "
                           "frames=0 baselineDegraded=true", challenge_id,
                           st.session_id, st.student_id)
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

        result = {
            "passed": passed,
            "rejectGate": reject,
            "gates": {k: v.to_dict() for k, v in all_gates.items()},
            "similarity": sim,
            "matchedStudentId": sid,
            "detectedAction": detected,
            "frames": len(st.ratios),
            "baselineDegraded": st.baseline_degraded,
            "attendanceStatus": None,
            "attendanceId": None,
            "checkinAt": None,
        }

        # 门禁全过才落库。落库失败必须让学生看到，不能"假成功"。
        if passed:
            try:
                from app import java_client
                committed = java_client.commit_checkin(
                    session_id=st.session_id,
                    student_id=st.student_id,
                    similarity=float(sim),
                    liveness_median=float(np.median(st.live_probs)) if st.live_probs else None,
                    identity_sim=float(i_g.value) if i_g.value is not None else None,
                    action_count=int(detected),
                    action_seq=st.action,
                )
                result["attendanceStatus"] = committed.get("status")
                result["attendanceId"] = committed.get("attendanceId")
                # 成功页要显示签到时间：契约 FinishData.checkinAt 早就声明了该字段，
                # 以前只挑出 status/id，前端只能显示 "—"，只能退化成设备本地时间。
                # 姓名不在这里透传：学生看自己的结果，前端用登录态里的 name 即可，
                # 少一个 PII 字段过网络。
                result["checkinAt"] = committed.get("checkinAt")
            except java_client.JavaError as exc:
                if exc.code == "FA-3003":
                    # 已签到：网络重试导致的重复提交，按成功处理
                    result["attendanceStatus"] = "ALREADY"
                else:
                    result["passed"] = False
                    result["rejectGate"] = "commit"
                    result["commitError"] = exc.message
            except Exception as exc:                            # noqa: BLE001
                result["passed"] = False
                result["rejectGate"] = "commit"
                result["commitError"] = f"提交签到结果失败：{exc}"

        # 判定明细必须落日志：finish 是 200 + 业务结果，网关只看到 200，
        # 不记这一行的话「签到失败」在现场完全无法归因（只能靠帧耗时反推）。
        logger.info(
            "challenge finish cid=%s sid=%s student=%s action=%s passed=%s reject=%s "
            "frames=%d detected=%d/%d gates=%s",
            challenge_id, st.session_id, st.student_id, st.action, result["passed"],
            result["rejectGate"], result["frames"], detected, st.need,
            {k: (None if v.value is None else round(float(v.value), 3))
             for k, v in all_gates.items()},
        )

        return result

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
            return HINTS.get(st.action, "请张嘴")
        return f"已检测到 {detected} 次，还需 {st.need - detected} 次"

    def _frame_resp(self, st: ChallengeState, accepted: bool, hint: str | None) -> dict:
        return {"accepted": accepted, "seq": st.seq, "hint": hint,
                "progress": self._progress(st), "detectedCount": self._count(st),
                "requiredCount": st.need}


class TooManyFaces(RuntimeError):
    """连续多帧多脸 → 中止（FA-4002）。"""


class NoFace(RuntimeError):
    """连续多帧无脸 → 中止（FA-4001）。"""
