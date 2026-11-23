"""挑战全流程测试：合成相机帧 + fakeredis，验证状态机与门禁编排。

不连真实 Redis：用 fakeredis（纯 Python 实现，接口一致）。
这条链路的部署正确性（真 Redis、4 worker）由 CI 集成测试验证。

关键用例：
  1. 静态照片序列 → 动作不足 + 活体拒绝 → passed=False（这正是"照片攻击被拦"的完整链路）
  2. 乱序帧被丢弃
  3. 连续无脸 → FA-4001 中止
  4. 票据过期 → 401
  5. finish 二次调用 → 拒绝（一次性）
"""
from __future__ import annotations

import os
import sys

import cv2
import numpy as np
import pytest

from conftest import CASIA_AVAILABLE, MODELS_AVAILABLE, SKIP_NO_CASIA, SKIP_NO_MODEL

pytestmark = pytest.mark.skipif(
    not (CASIA_AVAILABLE and MODELS_AVAILABLE), reason=SKIP_NO_CASIA if not CASIA_AVAILABLE else SKIP_NO_MODEL)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.service import ChallengeService, NoFace, TooManyFaces
from app.challenge.store import ChallengeStore
from app.common import tickets
from app.engine import load_all

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))


def composite(img112, size=(720, 1280), side=320):
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = side / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)))
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2, (size[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


@pytest.fixture(scope="module")
def service():
    load_all()
    import fakeredis
    return ChallengeService(ChallengeStore(fakeredis.FakeRedis(decode_responses=True)))


@pytest.fixture(scope="module")
def face_img():
    path = os.path.join(CASIA, "000", "000_0.jpg")
    img = cv2.imread(path)
    assert img is not None, f"找不到测试图 {path}"
    return img


def test_static_sequence_fails(service, face_img):
    """照片攻击的完整链路：帧全是静态的 → 活体拒绝 + 动作不足。"""
    ticket = tickets.issue(1, 101, "jti-static")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]

    for i in range(10):
        service.frame(cid, composite(face_img), seq=i)

    result = service.finish(cid)
    assert result["passed"] is False
    # 静态照片必然命中的第一道门禁是活体（max 0.045 << 0.55）
    assert result["rejectGate"] in ("liveness", "actionCount", "match")
    assert result["gates"]["liveness"]["passed"] is False
    assert result["frames"] == 10
    # match 门禁有结论（向量库为空时也会给出明确的失败，而不是异常）
    assert "match" in result["gates"]


def test_stale_frame_dropped_gap_tolerated(service, face_img):
    """序号倒退丢弃、序号跳跃容忍——v1.3 起两个方向必须同时成立。

    旧版把两者一律当作"乱序"永久拒收，`st.seq` 会卡在缺口处，整场剩下的
    窗口全废（前端只看到"识别失败"，网关日志一片 200）。这条用例钉住两个
    方向，防止以后再把缺口当乱序处理。
    """
    ticket = tickets.issue(1, 102, "jti-ooo")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]

    service.frame(cid, composite(face_img), seq=0)            # st.seq -> 1
    stale = service.frame(cid, composite(face_img), seq=0)    # 重传的旧帧
    assert stale["accepted"] is False

    gap = service.frame(cid, composite(face_img), seq=5)      # 缺口，按降采样
    assert gap["accepted"] is True
    # seq 字段返回的是"下一帧应传的序号"（本帧已被消费，计数器 +1）
    assert gap["seq"] == 6


def test_no_face_abort(service, face_img):
    ticket = tickets.issue(1, 103, "jti-noface")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]

    blank = np.full((720, 1280, 3), 90, dtype=np.uint8)
    with pytest.raises(NoFace):
        for i in range(3):
            service.frame(cid, blank, seq=i)


def test_multi_face_abort(service, face_img):
    ticket = tickets.issue(1, 104, "jti-multi")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]

    # 两张脸并排
    two = np.full((720, 1280, 3), 90, dtype=np.uint8)
    small = cv2.resize(face_img, (320, 320))
    two[200:520, 200:520] = small
    two[200:520, 700:1020] = small
    with pytest.raises(TooManyFaces):
        for i in range(3):
            service.frame(cid, two, seq=i)


def test_expired_ticket_rejected(service):
    with pytest.raises(PermissionError):
        service.start("bad-token", np.zeros((10, 10, 3), dtype=np.uint8))


def test_finish_is_one_shot(service, face_img):
    ticket = tickets.issue(1, 105, "jti-once")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]
    for i in range(9):
        service.frame(cid, composite(face_img), seq=i)
    first = service.finish(cid)
    assert "passed" in first
    with pytest.raises(LookupError):
        service.finish(cid)          # 状态已消费


def test_baseline_pollution_multi_face_rejected(service, face_img):
    """基线污染专项：基线帧有多张脸 → start 直接 400，不创建会话。"""
    ticket = tickets.issue(1, 106, "jti-pollute")
    two = np.full((720, 1280, 3), 90, dtype=np.uint8)
    small = cv2.resize(face_img, (320, 320))
    two[200:520, 200:520] = small
    two[200:520, 700:1020] = small
    with pytest.raises(TooManyFaces):
        service.start(ticket, two)


def test_start_rejects_blank_baseline(service):
    """基线无脸 → FA-4001（NoFace），不抽一个注定失败的动作。

    CR-018 后白名单只剩尺度动作，判据是「相对基线的变化」：没有基线时
    抽到哪个都必然判失败，让学生跑完一轮再收「动作次数不足」是把系统侧
    问题转嫁给他。回滚嘴部动作后本用例应恢复为「降级为首帧」。
    """
    blank = np.full((720, 1280, 3), 90, dtype=np.uint8)
    with pytest.raises(NoFace):
        service.start(tickets.issue(1, 107, "jti-degrade"), blank)


# ---------------- 尺度类动作：靠近 / 离远 ----------------


def test_random_pick_only_whitelisted_actions(service, face_img):
    """CR-018/021：抽样只出白名单里的动作，已停用的嘴部动作不得再被抽到。"""
    base = composite(face_img)
    picked = set()
    for i in range(12):
        info = service.start(tickets.issue(1, 110 + i, f"jti-pick-{i}"), base)
        picked.add(info["action"])
    assert picked <= {"MOVE_CLOSER", "MOVE_AWAY", "EYE_CLOSE",
                      "TURN_LEFT", "TURN_RIGHT"}, picked
    assert picked, "12 次抽样至少应抽出一个动作"


def test_move_closer_passes_action_gate(service, face_img, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)

    base = composite(face_img)
    # det 320 后检测框略紧（380/320 实测 ratio=1.178，贴着 0.18 阈值过不去）。
    # 素材放大 25% 留 6% 余量，测试意图不变：足够大的正向变化必须达标。
    near = composite(face_img, side=400)          # 放大 ~25%
    info = service.start(tickets.issue(1, 120, "jti-closer"), base,
                         action_hint="MOVE_CLOSER")
    assert info["action"] == "MOVE_CLOSER"
    assert info["needCount"] == 1
    assert info["windowMs"] == settings.action.window_ms_scale
    assert "靠近" in info["instruction"]

    cid = info["challengeId"]
    for seq in range(4):
        service.frame(cid, base, seq=seq)
    for seq in range(4, 10):
        service.frame(cid, near, seq=seq)

    result = service.finish(cid)
    assert result["gates"]["actionCount"]["passed"] is True
    assert result["detectedAction"] == 1


def test_move_away_rejects_closer_sequence(service, face_img, monkeypatch):
    """方向必须对：学生越靠越近，不能算「离远」做到了。"""
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)

    base = composite(face_img)
    near = composite(face_img, side=380)
    info = service.start(tickets.issue(1, 121, "jti-away"), base,
                         action_hint="MOVE_AWAY")
    cid = info["challengeId"]
    for seq in range(4):
        service.frame(cid, base, seq=seq)
    for seq in range(4, 10):
        service.frame(cid, near, seq=seq)

    result = service.finish(cid)
    assert result["gates"]["actionCount"]["passed"] is False
    assert result["detectedAction"] == 0


def test_move_away_passes_when_face_shrinks(service, face_img, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)

    base = composite(face_img)
    far = composite(face_img, side=260)           # 缩小 ~19%
    info = service.start(tickets.issue(1, 122, "jti-away-ok"), base,
                         action_hint="MOVE_AWAY")
    cid = info["challengeId"]
    for seq in range(4):
        service.frame(cid, base, seq=seq)
    for seq in range(4, 10):
        service.frame(cid, far, seq=seq)

    result = service.finish(cid)
    assert result["gates"]["actionCount"]["passed"] is True
    assert result["detectedAction"] == 1


def test_unknown_action_hint_rejected(service, face_img, monkeypatch):
    """契约外的动作码不许进 attendance.action_seq。"""
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)
    with pytest.raises(ValueError):
        service.start(tickets.issue(1, 123, "jti-bad"), composite(face_img),
                      action_hint="NOD")


@pytest.mark.parametrize("retired", ["MOUTH_ONCE", "MOUTH_TWICE", "BLINK"])
def test_retired_action_hint_rejected(service, face_img, monkeypatch, retired):
    """CR-018 停用的嘴部动作码、CR-021 被闭眼取代的 BLINK 都要给明确原因。

    验收脚本最容易踩这个坑：以为 hint 写错，去翻白名单和环境变量。
    """
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)
    with pytest.raises(ValueError, match="已停用"):
        service.start(tickets.issue(1, 124, "jti-retired"), composite(face_img),
                      action_hint=retired)


# ---------------- CR-021：闭眼 / 左右转头 ----------------

def test_eye_close_counter_counts_segment():
    from app.challenge.action import EyeCloseCounter
    c = EyeCloseCounter(base_ratio=0.25, abs_drop=0.06, drop_ratio=0.60, hold_frames=2)
    for r in (0.25, 0.26, 0.05, 0.05, 0.24, 0.25):
        c.observe(r)
    assert c.result()[0] == 1


def test_eye_close_needs_two_frames():
    """单帧闭眼 = 眨眼，不算「闭眼保持」；这正是 4fps 下眨眼判不过的原因。"""
    from app.challenge.action import EyeCloseCounter
    c = EyeCloseCounter(base_ratio=0.25, hold_frames=2)
    for r in (0.25, 0.05, 0.25):
        c.observe(r)
    assert c.result()[0] == 0


def test_eye_close_baseline_self_heals():
    """基线帧正好拍在闭眼上 → 用序列最高值重算（与张嘴的自愈同构）。"""
    from app.challenge.action import EyeCloseCounter
    c = EyeCloseCounter(base_ratio=0.05, hold_frames=2)
    for r in (0.05, 0.26, 0.27, 0.05, 0.05):
        c.observe(r)
    assert c.result()[0] == 1


def test_turn_counter_is_directional():
    from app.challenge.action import TurnActionCounter
    left = TurnActionCounter(base_yaw=-0.03, direction=1, delta=0.12, hold_frames=2)
    right = TurnActionCounter(base_yaw=-0.03, direction=-1, delta=0.12, hold_frames=2)
    for y in (-0.03, -0.02, 0.10, 0.11, -0.03):
        left.observe(y)
        right.observe(y)
    assert left.result()[0] == 1
    assert right.result()[0] == 0, "同一个序列不能被相反方向也算成做到了"


def _state(action: str, **kw) -> "object":
    from app.challenge.store import ChallengeState
    base = dict(challenge_id="c-test", session_id=1, student_id=1, ticket_jti="jti",
                action=action, need=1, window_ms=2500, min_frames=8, created_ms=0,
                baseline_ratio=0.1, baseline_degraded=False)
    base.update(kw)
    return ChallengeState(**base)


def test_turn_gate_counts_left_turn():
    from app.challenge import gates
    st = _state("TURN_LEFT", baseline_yaw=-0.03,
                yaws=[-0.03, -0.03, 0.11, 0.12, -0.02])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


def test_turn_gate_rejects_static_sequence():
    from app.challenge import gates
    st = _state("TURN_RIGHT", baseline_yaw=-0.03, yaws=[-0.03, -0.04, -0.02, -0.03])
    g, detected, _ = gates.gate_action(st)
    assert detected == 0 and not g.passed


def test_turn_reverse_segment_vetoes_pass():
    """反向动作否决：「提示左转却右转」即使碰上单帧噪声也不许过。

    复现验收发现的误放行：反向段本身不产生正向计数，但 hold=1 下滚转残余
    （实测最大 0.108）+ 噪声可能在正向凑出一段。只要反向出现过达标段，
    正向计数再够也判不过。
    """
    from app.challenge import gates
    st = _state("TURN_LEFT", baseline_yaw=-0.03,
                yaws=[-0.03, 0.11, 0.12, -0.20, -0.19, -0.03])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and not g.passed


def test_turn_reverse_veto_does_not_reject_correct_turn():
    """正向做对不误伤：左转-保持-回正的序列没有反向达标段（回正 Δ≈0）。"""
    from app.challenge import gates
    st = _state("TURN_LEFT", baseline_yaw=-0.03,
                yaws=[-0.03, 0.11, 0.12, 0.10, -0.02, -0.03])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


def test_scale_reverse_segment_vetoes_pass():
    """尺度反向否决：提示靠近却退远，靠噪声凑出的正向段不能过。"""
    from app.challenge import gates
    st = _state("MOVE_CLOSER", baseline_scale=0.30,
                scales=[0.30, 0.36, 0.37, 0.30, 0.24, 0.23, 0.30])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and not g.passed


def test_scale_reverse_veto_does_not_reject_correct_closer():
    from app.challenge import gates
    st = _state("MOVE_CLOSER", baseline_scale=0.30,
                scales=[0.30, 0.36, 0.37, 0.34, 0.30, 0.31])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


def test_eye_gate_counts_closed_segment():
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, eyes=[0.25, 0.26, 0.05, 0.05, 0.25])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


def test_eye_pose_gate_excludes_displaced_frames():
    """姿态一致性闸：提示闭眼却转头 → 虹膜被搬出固定 ROI，暗度下降是
    「移动」不是「闭眼」。偏航超界的帧不计入判据，全部超界判未完成。"""
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, baseline_yaw=0.0, baseline_roll=0.0,
                eyes=[0.25, 0.05, 0.05, 0.05],
                eye_yaws=[0.0, 0.30, 0.30, 0.30], eye_rolls=[0.0, 0.0, 0.0, 0.0])
    g, detected, _ = gates.gate_action(st)
    assert detected == 0 and not g.passed


def test_eye_pose_gate_excludes_rolled_frames():
    """滚转同理：歪头也会把眼框搬离 ROI。"""
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, baseline_yaw=0.0, baseline_roll=0.0,
                eyes=[0.25, 0.05, 0.05, 0.05],
                eye_yaws=[0.0, 0.02, 0.02, 0.02], eye_rolls=[0.0, 0.40, 0.40, 0.40])
    g, detected, _ = gates.gate_action(st)
    assert detected == 0 and not g.passed


def test_eye_pose_gate_keeps_still_frames():
    """正常闭眼（头部不动）不受闸门影响；hold=2 下连续 2 帧达标计 1 次。"""
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, baseline_yaw=0.0, baseline_roll=0.0,
                eyes=[0.25, 0.05, 0.05, 0.05],
                eye_yaws=[0.0, 0.02, 0.02, 0.02], eye_rolls=[0.0, 0.05, 0.05, 0.05])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


def test_eye_hold2_needs_two_consecutive_frames():
    """hold=2 恢复后的门禁语义：单帧达标不再计数（挡单帧噪声）。"""
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, eyes=[0.25, 0.05, 0.25, 0.26])
    g, detected, _ = gates.gate_action(st)
    assert detected == 0 and not g.passed


def test_eye_gate_legacy_state_without_pose_still_counts():
    """部署前开的存量 challenge 没有逐帧姿态：闸门跳过，保持原判据。"""
    from app.challenge import gates
    st = _state("EYE_CLOSE", baseline_eye=0.25, eyes=[0.25, 0.05, 0.05, 0.05])
    g, detected, _ = gates.gate_action(st)
    assert detected == 1 and g.passed


# ---------------- 被动活体线索（默认关，只否决） ----------------

def test_passive_gate_disabled_by_default():
    """PASSIVE_GATE=0（默认）：不否决、value/threshold 为 null，链路稳定。"""
    from app.challenge import gates
    st = _state("MOVE_CLOSER", baseline_scale=0.3,
                passive_peak=[99.0], passive_hf=[0.99], passive_clip=[0.99])
    g = gates.gate_passive(st)
    assert g.passed and g.value is None and g.threshold is None


def test_passive_gate_veto_needs_corroboration():
    """周期性证据 + 独立旁证才否决：单峰不否决（防误拒）。"""
    from app.challenge import gates
    from app.config import settings
    st = _state("MOVE_CLOSER", baseline_scale=0.3,
                passive_peak=[99.0, 99.0], passive_hf=[0.1, 0.1],
                passive_clip=[0.01, 0.01])
    # 阈值是占位值，直接改小以触发判定路径
    old = (settings.gates.passive_gate_enabled, settings.gates.passive_peak_max,
           settings.gates.passive_hf_max, settings.gates.passive_clip_max)
    settings.gates.passive_gate_enabled = True
    settings.gates.passive_peak_max = 50.0
    settings.gates.passive_hf_max = 0.60
    settings.gates.passive_clip_max = 0.30
    try:
        solo = gates.gate_passive(st)
        assert solo.passed, "只有周期性证据、无旁证，不许否决"
        st2 = _state("MOVE_CLOSER", baseline_scale=0.3,
                     passive_peak=[99.0, 99.0], passive_hf=[0.9, 0.9],
                     passive_clip=[0.01, 0.01])
        vetoed = gates.gate_passive(st2)
        assert not vetoed.passed, "周期性 + 高频旁证 → 否决"
    finally:
        (settings.gates.passive_gate_enabled, settings.gates.passive_peak_max,
         settings.gates.passive_hf_max, settings.gates.passive_clip_max) = old


def test_passive_veto_blocks_finish():
    """被动否决进 AND 链：其余门禁全过也不放行，rejectGate=passive。"""
    from app.challenge import gates
    st = _state("MOVE_CLOSER", baseline_scale=0.30,
                scales=[0.30, 0.36, 0.37, 0.36, 0.30, 0.31],
                passive_peak=[99.0, 99.0, 99.0], passive_hf=[0.9, 0.9, 0.9],
                passive_clip=[0.5, 0.5, 0.5])
    from app.config import settings
    old = (settings.gates.passive_gate_enabled, settings.gates.passive_peak_max,
           settings.gates.passive_hf_max, settings.gates.passive_clip_max)
    settings.gates.passive_gate_enabled = True
    settings.gates.passive_peak_max = 50.0
    settings.gates.passive_hf_max = 0.60
    settings.gates.passive_clip_max = 0.30
    try:
        a_g, detected, _ = gates.gate_action(st)
        p_g = gates.gate_passive(st)
        assert detected >= 1 and a_g.passed, "动作门禁本身应通过"
        assert not p_g.passed, "被动门禁否决"
    finally:
        (settings.gates.passive_gate_enabled, settings.gates.passive_peak_max,
         settings.gates.passive_hf_max, settings.gates.passive_clip_max) = old


def test_identity_gate_survives_skipped_embeddings(service, face_img, monkeypatch):
    """隔帧 ArcFace 后「中途换人」仍被抓到：换人帧落在偶数序号即可检出。"""
    from app.config import settings
    import cv2 as _cv2
    import os as _os
    monkeypatch.setattr(settings.action, "allow_action_hint", True)
    other_path = _os.path.join(CASIA, "001", "001_0.jpg")
    other = _cv2.imread(other_path)
    assert other is not None, "需要第二个身份的素材"
    a = composite(face_img)
    b = composite(other)
    info = service.start(tickets.issue(1, 140, "jti-swap"), a, action_hint="MOVE_CLOSER")
    cid = info["challengeId"]
    # seq0=A（嵌入），seq1=A（跳过），seq2..=B（偶数序号全部嵌入）
    for seq, frame in ((0, a), (1, a), (2, b), (3, b), (4, b), (5, b)):
        service.frame(cid, frame, seq=seq)
    result = service.finish(cid)
    assert result["gates"]["identity"]["passed"] is False


def test_eye_close_not_eligible_without_headroom(face_img):
    """基线眼区太暗就没有下降空间：该动作退出抽签，而不是判学生失败。"""
    from app.challenge.service import ChallengeService
    from app.engine import detector
    frame = composite(face_img)
    face = detector().detect_largest(frame)
    assert face is not None
    assert not ChallengeService._action_eligible("EYE_CLOSE", face, frame, 0.1, 0.05, 0.0)
    assert ChallengeService._action_eligible("EYE_CLOSE", face, frame, 0.1, 0.25, 0.0)


def test_new_action_instructions_and_windows(service, face_img, monkeypatch):
    """文案与窗口是契约的一部分：闭眼不是眨眼、转头要报方向。"""
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)
    base = composite(face_img)
    eye = service.start(tickets.issue(1, 130, "jti-eye"), base, action_hint="EYE_CLOSE")
    assert "闭" in eye["instruction"] and eye["windowMs"] == 3000
    right = service.start(tickets.issue(1, 131, "jti-turn"), base, action_hint="TURN_RIGHT")
    # 2026-11-22 真机采样修正：转头窗口与尺度类对齐 3s，文案改「保持约 1 秒」
    # （0.5s < 服务端 ~0.8s 帧距，按旧文案做会一帧都不落窗）
    assert "右边" in right["instruction"] and right["windowMs"] == 3000
    assert "保持约 1 秒" in right["instruction"]
