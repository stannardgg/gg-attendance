"""API 层集成测试 + 多 worker 验证。

多 worker 的验证方式：两个 ChallengeService 实例共享同一个 Redis，
start 用 A、frame 用 B、finish 再换回 A——只要状态真在 Redis 里，
这种「换着用」就必须成功。真实部署里 4 个 uvicorn worker 等价于此。
"""
from __future__ import annotations

import base64
import os
import sys

import cv2
import numpy as np
import pytest

from conftest import CASIA_AVAILABLE, MODELS_AVAILABLE, SKIP_NO_CASIA, SKIP_NO_MODEL

pytestmark = pytest.mark.skipif(
    not (CASIA_AVAILABLE and MODELS_AVAILABLE), reason=SKIP_NO_CASIA if not CASIA_AVAILABLE else SKIP_NO_MODEL)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.service import ChallengeService
from app.challenge.store import ChallengeStore
from app.common import tickets
from app.config import settings
from app.engine import load_all
from app.main import app

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))


def composite(img112, size=(720, 1280)):
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = 320 / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)))
    fh, fw = face.shape[:2]
    frame[(size[0] - fh) // 2:(size[0] - fh) // 2 + fh,
          (size[1] - fw) // 2:(size[1] - fw) // 2 + fw] = face
    return frame


def b64(img):
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
    assert ok
    return base64.b64encode(buf.tobytes()).decode()


@pytest.fixture(scope="module")
def client():
    load_all()
    import fakeredis
    shared = fakeredis.FakeRedis(decode_responses=True)
    fake = ChallengeService(ChallengeStore(shared))
    from fastapi.testclient import TestClient
    with TestClient(app) as c:
        # 用 fakeredis 后端替换启动时创建的真实 Redis 连接
        app.state._test_service = fake
        import app.main as m
        m._service = fake
        yield c


@pytest.fixture(scope="module")
def face_img():
    img = cv2.imread(os.path.join(CASIA, "000", "000_0.jpg"))
    assert img is not None
    return img


def test_health(client):
    r = client.get("/face-api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["code"] == "FA-0000"
    assert body["data"]["status"] == "UP"
    assert body["data"]["models"]["detection"] is True
    assert body["data"]["models"]["recognition"] is True


def test_start_and_frame_and_finish(client, face_img):
    ticket = tickets.issue(1, 201, "jti-api")
    r = client.post("/face-api/challenge/start",
                    json={"baselineFrame": b64(composite(face_img))},
                    headers={"Authorization": f"Bearer {ticket}"})
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    # 动作是随机的（§4.4.3 防预录），不能断言具体值——只断言它来自白名单，
    # 且给出了对应指令。早先这里写死 MOUTH_ONCE，白名单一扩容就假失败。
    assert data["action"] in settings.action.action_whitelist
    assert data["instruction"]
    assert data["challengeId"]

    cid = data["challengeId"]
    for seq in range(6):
        ok, buf = cv2.imencode(".jpg", composite(face_img))
        r = client.post(f"/face-api/challenge/{cid}/frame",
                        files={"frame": ("f.jpg", buf.tobytes(), "image/jpeg")},
                        data={"seq": str(seq)},
                        headers={"Authorization": f"Bearer {ticket}"})
        assert r.status_code == 200, r.text
        assert r.json()["data"]["accepted"] is True

    r = client.post(f"/face-api/challenge/{cid}/finish",
                    headers={"Authorization": f"Bearer {ticket}"})
    assert r.status_code == 200
    result = r.json()["data"]
    assert result["frames"] == 6
    # 被动活体（2026-11-22）与伪装遮挡（CR-025）加入 gates：关闭的门禁也返回
    assert set(result["gates"]) == {"liveness", "identity", "actionCount", "match", "passive", "disguise"}
    # 静态照片：活体必拒
    assert result["gates"]["liveness"]["passed"] is False


def test_start_without_ticket(client, face_img):
    r = client.post("/face-api/challenge/start",
                    json={"baselineFrame": b64(composite(face_img))})
    assert r.status_code == 401
    assert r.json()["code"] == "FA-1002"


def test_multi_worker_shared_state(client, face_img):
    """跨进程语义：换一个 service 实例（等价于换 worker），状态不丢。"""
    import fakeredis
    shared = fakeredis.FakeRedis(decode_responses=True)
    worker_a = ChallengeService(ChallengeStore(shared))
    worker_b = ChallengeService(ChallengeStore(shared))

    ticket = tickets.issue(1, 202, "jti-worker")
    info = worker_a.start(ticket, composite(face_img))
    cid = info["challengeId"]

    # frame 走 worker_b
    for seq in range(5):
        worker_b.frame(cid, composite(face_img), seq=seq)

    # finish 走 worker_a
    result = worker_a.finish(cid)
    assert result["frames"] == 5
    assert result["gates"]["liveness"]["passed"] is False
