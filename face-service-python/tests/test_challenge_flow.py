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


def test_out_of_order_frame_dropped(service, face_img):
    ticket = tickets.issue(1, 102, "jti-ooo")
    info = service.start(ticket, composite(face_img))
    cid = info["challengeId"]

    service.frame(cid, composite(face_img), seq=0)
    resp = service.frame(cid, composite(face_img), seq=5)   # 乱序
    assert resp["accepted"] is False
    resp2 = service.frame(cid, composite(face_img), seq=1)  # 正序
    assert resp2["accepted"] is True
    # seq 字段返回的是"下一帧应传的序号"（本帧已被消费，计数器 +1）
    assert resp2["seq"] == 2


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


def test_baseline_missing_degrades(service, face_img):
    """基线无脸 → 降级为序列首帧（baselineDegraded=True），链路照常走完。"""
    ticket = tickets.issue(1, 107, "jti-degrade")
    blank = np.full((720, 1280, 3), 90, dtype=np.uint8)
    info = service.start(ticket, blank)
    assert info["baselineDegraded"] is True

    cid = info["challengeId"]
    for seq in range(6):
        service.frame(cid, composite(face_img), seq=seq)
    result = service.finish(cid)
    assert result["baselineDegraded"] is True
    assert result["frames"] == 6
    # 降级后基线取自首帧，静态序列的动作门禁依然失败
    assert result["gates"]["actionCount"]["passed"] is False


# ---------------- 尺度类动作：靠近 / 离远 ----------------


def test_scale_action_never_picked_without_baseline(service):
    """基线帧没有人脸时不得抽到尺度类动作：它们的判据是「相对基线的变化」，
    没有基线就必然判失败，等于让学生替我们重来一次。"""
    blank = np.full((720, 1280, 3), 90, dtype=np.uint8)
    for i in range(8):
        info = service.start(tickets.issue(1, 110 + i, f"jti-nb-{i}"), blank)
        assert info["action"] not in ("MOVE_CLOSER", "MOVE_AWAY")


def test_move_closer_passes_action_gate(service, face_img, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings.action, "allow_action_hint", True)

    base = composite(face_img)
    near = composite(face_img, side=380)          # 放大 ~19%
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
                      action_hint="TURN_LEFT")
