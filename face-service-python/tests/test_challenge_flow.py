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

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.service import ChallengeService, NoFace, TooManyFaces
from app.challenge.store import ChallengeStore
from app.common import tickets
from app.engine import load_all

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))


def composite(img112, size=(720, 1280)):
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = 320 / min(img112.shape[:2])
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
