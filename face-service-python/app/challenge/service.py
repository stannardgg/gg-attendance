"""挑战服务：start / frame / finish 的业务编排。

三条设计决定（都来自 §4.4.3 的实测教训）：
  1. **基线必须可判**：白名单里的动作全部是「相对基线的变化」（CR-021 起包括
     闭眼与转头），没有基线就无从判起，`start` 直接返回 FA-4001 让学生重来，
     而不是抽一个注定失败的动作再判他不过。只有回滚嘴部动作后才允许
     "基线缺失降级为首帧"（嘴部判据不依赖基线）。
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
from app.challenge.action import (
    dark_ratio, eye_dark_ratio, face_scale, interocular, roll_proxy, yaw_proxy,
)
from app.challenge.store import (
    BASELINE_FREE_ACTIONS, EYE_ACTIONS, SCALE_ACTIONS, TURN_ACTIONS,
    TURN_DIRECTIONS, ChallengeState, ChallengeStore,
)
from app.common.tickets import TicketError, parse
from app.config import settings
from app.engine import detector, embedder, landmarker, liveness
from app.index.vectors import index

logger = logging.getLogger("face-service")

# 指令文案：尺度类动作与嘴部动作的措辞不同（「靠近」没有「几次」的概念）。
# CR-018 起嘴部动作已停用，这两行保留是为了让回滚不必重写文案。
INSTRUCTIONS = {
    "MOUTH_ONCE": "请张一次嘴，约半秒",
    "MOUTH_TWICE": "请张两次嘴，每次约半秒",
    "MOVE_CLOSER": "请把脸靠近摄像头一点，保持半秒",
    "MOVE_AWAY": "请把脸离摄像头远一点，保持半秒",
    # CR-021：闭眼而不是眨眼。4fps 采样下眨眼的闭合期（100~300ms）多数落在
    # 两帧之间，要求连续 2 帧达标时真人做对也判不过——改成"闭住约 1 秒"，
    # 同一套眼区判据，但不再赌采样运气。
    # 2026-11-22 hold 修正：真机有效采样 ~1.25fps（服务端单帧 ~800ms），1 秒
    # 恰好保证至少 1 帧落窗（hold=1）；转头若仍说"保持半秒"，0.5s < 0.8s 帧距
    # 有概率一帧都不落，必须说"保持约 1 秒"。
    "EYE_CLOSE": "请闭上眼睛约 1 秒，再睁开",
    "TURN_LEFT": "请把头慢慢转向你的左边，约 25 度，保持约 1 秒",
    "TURN_RIGHT": "请把头慢慢转向你的右边，约 25 度，保持约 1 秒",
}

# 动作未达成时的实时提示（进度提示由 _hint 统一拼）
HINTS = {
    "MOVE_CLOSER": "请再靠近一点",
    "MOVE_AWAY": "请再离远一点",
    "EYE_CLOSE": "请把眼睛闭上，保持住",
    "TURN_LEFT": "请把头再往左转一点",
    "TURN_RIGHT": "请把头再往右转一点",
}

# 已停用的动作码。判据与窗口配置都还在（张嘴族回滚用），但不再参与抽签、
# 也不接受 action_hint；单独列出来是为了把「已停用」与「拼错了」区分开。
# BLINK 从来没有实现过（4fps 采样定理），列在这里是因为契约里明确写了它被
# EYE_CLOSE 取代——联调试 BLINK 时应该看到原因，而不是"未知动作码"。
RETIRED_ACTIONS = ("MOUTH_ONCE", "MOUTH_TWICE", "BLINK")


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

        # CR-023：join 拦的是「领新票」，但失败后票据还有最长 60 秒寿命，
        # 旧票据能直接开新挑战——上限必须在 start 也查一次，否则第 4 次采集照跑。
        # Java 不可达时降级放行：commit 反正也要过 Java，这里死拦只会把「Java 抖了一下」
        # 放大成「全班都签不了」。
        self._ensure_not_blocked(session_id, student_id)

        baseline_ratio: float | None = None
        baseline_scale = 0.0
        baseline_eye = 0.0
        baseline_yaw = 0.0
        baseline_roll = 0.0
        degraded = True
        roi: tuple[int, int, int, int] | None = None
        eye_roi: list[int] | None = None

        # 基线污染专项（§4.4.3）：基线帧有多张够大的人脸 → 直接拒绝。
        # 此时无法确定"基线"属于谁，继续做只会把 A 的基线配到 B 的脸上。
        _faces = detector().detect(baseline_bgr)
        _big = [f for f in _faces if f.width >= settings.challenge.multi_face_min_px]
        if len(_big) >= 2:
            raise TooManyFaces("基线画面中检测到多张人脸")

        face = detector().detect_largest(baseline_bgr)
        # 基线无脸时的取舍：白名单里只要还有**不依赖基线**的动作（只有已停用的
        # 嘴部动作属于这一类，回滚时才会出现），就允许降级为首帧继续；
        # 否则抽到任何动作都必然判失败，不如现在就 FA-4001 让他把脸放进取景框。
        if face is None and not any(
                a in BASELINE_FREE_ACTIONS for a in settings.action.action_whitelist):
            raise NoFace("基线画面中没有检测到人脸")

        if face is not None:
            baseline_scale = face_scale(face, baseline_bgr.shape[1])
            baseline_yaw = yaw_proxy(face)
            baseline_roll = roll_proxy(face)
            crop = None
            pts = None
            try:
                pts, crop = landmarker().landmarks(baseline_bgr, face.bbox)
                rect = landmarker().mouth_rect(pts)
                gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                baseline_ratio = dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])
                roi = rect
                degraded = False
            except ValueError:
                degraded = True
            # 眼框优先用 106 点的眼球轮廓（定位更准），关键点模型不可用时退回 5 点。
            try:
                boxes = (landmarker().eye_rects(pts) if pts is not None
                         else landmarker().eye_rects_from_kps(face.kps, face.bbox))
                if crop is None:
                    crop = landmarker().align_space(baseline_bgr, face.bbox)
                baseline_eye = eye_dark_ratio(
                    cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY), boxes)
                eye_roi = boxes
            except ValueError:
                eye_roi = None

        # 动作必须在基线之后选：所有动作的判据都是"相对基线的变化"，
        # 而且还要看这张脸此刻有没有做这个动作的余地（见 _action_eligible）。
        # 动作从白名单里**随机**选（§4.4.3）：固定动作等于给预录视频一个稳定的靶子。
        action = self._pick_action(action_hint, face, baseline_bgr,
                                   baseline_scale, baseline_eye, baseline_yaw)

        st = self.store.create(
            session_id=session_id,
            student_id=student_id,
            ticket_jti=str(claims.get("jti", "")),
            action=action,
            baseline_ratio=baseline_ratio if baseline_ratio is not None else 0.02,
            baseline_degraded=degraded,
            baseline_scale=baseline_scale,
            baseline_eye=baseline_eye,
            baseline_yaw=baseline_yaw,
            baseline_roll=baseline_roll,
        )
        if roi is not None:
            st.roi_x1, st.roi_y1, st.roi_x2, st.roi_y2 = roi
        if eye_roi:
            st.eye_roi = eye_roi
        if roi is not None or eye_roi:
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

    def _pick_action(self, hint: str | None, face, frame_bgr: np.ndarray,
                     baseline_scale: float = 0.0, baseline_eye: float = 0.0,
                     baseline_yaw: float = 0.0) -> str:
        """随机取动作；仅当显式允许时采纳客户端 hint（CR-010）。

        随机前先剔除此刻**物理上做不到**的动作：
          - 脸已经快占满画面 → 不能要求"再靠近"；脸太小 → 再离远就检不出人了；
          - 眼区太暗 / 瞳距太小 → 闭眼的降幅没有可测空间；
          - 基线已经偏头 → 再往同侧转会滑出检测范围。
        剔除只针对单个动作，不合格的那个退出抽签，其余照旧。
        """
        whitelist = settings.action.action_whitelist
        if hint and hint in RETIRED_ACTIONS and hint not in whitelist:
            # 已停用的动作码给明确原因：联调最容易把它当成拼写错误去查白名单
            raise ValueError(f"动作已停用：{hint}")
        if hint and settings.action.allow_action_hint and hint in whitelist:
            # 验收/联调要能强制指定动作，包括故意指定"做不到"的组合
            return hint
        if hint and hint not in whitelist:
            # 契约外的动作码直接拒绝，避免把未知值写进 attendance.action_seq
            raise ValueError(f"未知动作码：{hint}")

        candidates = [a for a in whitelist
                      if self._action_eligible(a, face, frame_bgr,
                                               baseline_scale, baseline_eye, baseline_yaw)]
        if not candidates:
            # 极端构图（脸太小 / 已偏头 / 已怼满画面）下可能一个都不合格。
            # 此时不猜"哪个最可能成"，退回全量白名单并留日志：与其在这里拒绝
            # 签到，不如让学生试一次——失败还能走申诉，拒绝就是死路。
            logger.warning(
                "动作可做性：无候选，退回全量白名单 face=%s scale=%.3f eye=%.3f yaw=%.3f",
                face is not None, baseline_scale, baseline_eye, baseline_yaw)
            candidates = list(whitelist)
        return random.choice(candidates)

    @staticmethod
    def _action_eligible(action: str, face, frame_bgr: np.ndarray,
                         baseline_scale: float = 0.0, baseline_eye: float = 0.0,
                         baseline_yaw: float = 0.0) -> bool:
        """该动作在当前画面上是否做得出来。四类判据各有自己的门槛。"""
        if action in TURN_ACTIONS:
            if face is None or abs(baseline_yaw) > settings.action.turn_base_max:
                return False
            # 转头会把脸推向画面边缘：左右各留 4% 才要求转，否则一转就出框。
            frame_w = float(frame_bgr.shape[1])
            return face.bbox[0] >= 0.04 * frame_w and face.bbox[2] <= 0.96 * frame_w
        if action in EYE_ACTIONS:
            if face is None or baseline_eye < settings.action.eye_base_min:
                return False
            # 瞳距太小 = 人太远：眼框只有十几像素，暗度统计不可信。
            return interocular(face) >= settings.action.eye_min_iod_px
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
                # 中止也算一次失败（CR-023）：采集页把它当 ERROR 计数，
                # 服务端不记的话，「三次」就只约束门禁判定，不约束画面失控。
                self._record_failure(st, "multi_face")
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
                # 同 multi_face：中止也计一次失败（CR-023）
                self._record_failure(st, "no_face")
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
                st.baseline_ratio = dark_ratio(
                    gray[rect[1]:rect[3], rect[0]:rect[2]])
            # 尺度基线缺失才补：只在 hint 强制指定尺度动作、而基线帧又没检到脸时走到这里
            if st.baseline_scale <= 0:
                st.baseline_scale = face_scale(face, frame_bgr.shape[1])

        aligned = landmarker().align_space(frame_bgr, face.bbox)
        gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
        roi = gray[st.roi_y1:st.roi_y2, st.roi_x1:st.roi_x2]
        ratio = dark_ratio(roi)

        # 眼框没锁上（基线帧瞳距太小/关键点异常）→ 拿当前帧补锁，并把这一帧当基线。
        # 这一帧可能已经闭着眼（基线被污染），EyeCloseCounter 的 max 自愈会兜住。
        if st.action in EYE_ACTIONS and not st.eye_roi:
            try:
                # 这里只补锁一次，且不再跑关键点模型（每帧都跑不划算），用 5 点兜底
                st.eye_roi = landmarker().eye_rects_from_kps(face.kps, face.bbox)
                st.baseline_eye = eye_dark_ratio(gray, st.eye_roi)
            except ValueError:
                st.eye_roi = []

        emb = embedder().embedding(frame_bgr, face.kps)
        if not st.first_emb:
            st.first_emb = emb.tolist()
        st.last_emb = emb.tolist()

        p = liveness().live_probability(frame_bgr, face.bbox)
        if p is not None:
            st.live_probs.append(p)

        st.ratios.append(ratio)
        st.scales.append(face_scale(face, frame_bgr.shape[1]))
        if st.action in EYE_ACTIONS and st.eye_roi:
            st.eyes.append(eye_dark_ratio(gray, st.eye_roi))
            # 姿态代理与 eyes 严格同长（同一分支追加）：闭眼判据窗内头部移动
            # 会把虹膜搬出固定 ROI，暗度下降是「移动」不是「闭眼」——
            # gates.gate_action 用这两个序列把超界帧剔除。
            st.eye_yaws.append(yaw_proxy(face))
            st.eye_rolls.append(roll_proxy(face))
        if st.action in TURN_ACTIONS:
            yaw = yaw_proxy(face)
            st.yaws.append(yaw)
            # 记下"离基线偏航最近"的那一帧特征：末帧可能是侧脸，拿它做 1:N 会自伤。
            dev = abs(yaw - st.baseline_yaw)
            if dev < st.frontal_dev:
                st.frontal_dev = dev
                st.frontal_emb = emb.tolist()
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

        if st.action in TURN_ACTIONS and st.frontal_emb:
            # 转头动作的末帧可能是侧脸。身份一致性与 1:N 都改用"最正脸"那一帧，
            # 否则 similarity 掉下去会冒出与动作无关的 FA-4002（判据在惩罚正确行为）。
            st.last_emb = st.frontal_emb

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
            # 失败时由 Java 回填（CR-023）；通过/系统故障时保持 None，前端不读
            "retryLeft": None,
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

        # 判定失败才计失败次数（CR-023）。rejectGate=commit 是系统故障，学生没有
        # 做错任何事，把 Java 宕机算到他头上、三次后锁死，是把基础设施问题转嫁给学生。
        # 契约 FinishData.retryLeft 早就声明了该字段，这里第一次有真值——
        # 结果面板据此隐藏重试按钮，权威值不在客户端。
        if not result["passed"] and result["rejectGate"] not in (None, "commit"):
            attempt = self._record_failure(st, result["rejectGate"])
            if attempt:
                result["retryLeft"] = attempt.get("retryLeft")

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
    def _ensure_not_blocked(self, session_id: int, student_id: int) -> None:
        """失败次数达到上限就拒绝开新挑战（CR-023）。

        上报/查询失败一律降级放行并留日志：计数缺失的最坏结果是多几次重试
        （每次仍要过三道门禁），而误拦的最坏结果是全班签不了。两害相权取其轻。
        """
        from app import java_client
        try:
            attempt = java_client.checkin_attempt_status(session_id, student_id)
        except java_client.JavaError as exc:
            logger.warning("attempt 查询被拒 sid=%s student=%s code=%s",
                           session_id, student_id, exc.code)
            return
        except Exception as exc:                                # noqa: BLE001
            logger.warning("attempt 查询不可达 sid=%s student=%s err=%s",
                           session_id, student_id, exc)
            return
        if attempt.get("blocked"):
            raise RetryExceeded(
                f"识别失败 {attempt.get('failCount', '?')} 次，本场已锁定，请提交申诉让老师核对")

    def _record_failure(self, st: ChallengeState, gate: str) -> dict | None:
        """把一次识别失败上报 Java 落库（CR-023）。返回计数状态，失败返回 None。

        上报不成功不改变本次挑战的判定结果：学生已经失败了，这一帧的反馈
        不能因为计数系统抖动而变成「系统错误」。
        """
        from app import java_client
        try:
            return java_client.record_checkin_failure(st.session_id, st.student_id, gate)
        except Exception as exc:                                # noqa: BLE001
            logger.warning("失败计数上报失败 cid=%s sid=%s student=%s gate=%s err=%s",
                           st.challenge_id, st.session_id, st.student_id, gate, exc)
            return None

    def _count(self, st: ChallengeState) -> int:
        return gates.gate_action(st)[1]

    def _progress(self, st: ChallengeState) -> float:
        return min(1.0, self._count(st) / max(1, st.need))

    def _hint(self, st: ChallengeState) -> str | None:
        detected = self._count(st)
        if detected >= st.need:
            return None
        if detected == 0:
            return HINTS.get(st.action, "请按提示完成动作")
        return f"已检测到 {detected} 次，还需 {st.need - detected} 次"

    def _frame_resp(self, st: ChallengeState, accepted: bool, hint: str | None) -> dict:
        return {"accepted": accepted, "seq": st.seq, "hint": hint,
                "progress": self._progress(st), "detectedCount": self._count(st),
                "requiredCount": st.need}


class TooManyFaces(RuntimeError):
    """连续多帧多脸 → 中止（FA-4002）。"""


class NoFace(RuntimeError):
    """连续多帧无脸 → 中止（FA-4001）。"""


class RetryExceeded(RuntimeError):
    """识别失败次数已达上限（CR-023）→ 429 FA-3005。

    与 TooManyFaces/NoFace 不同，这不是单场挑战的判定结果，而是「这场签到
    不再放行」的门禁：计数在 Java/MySQL（checkin_attempt），start 前查询。
    """
