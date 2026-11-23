"""伪装/遮挡识别测试（CR-025）。

三层用例：
  1. 纯函数判据（合成对齐 crop，**不依赖模型**，CI 可跑）——
     钉住判据方向：干净不命中、口罩/墨镜/帽子命中、闭眼不命中；
  2. gate_disguise 占比门禁（纯状态构造，不依赖模型）；
  3. 端到端（真实模型 + CASIA，本机跑）——
     40 身份干净脸 0 误拒（BPCER 声明）、口罩 e2e 从 start/frame 拦住。
"""
from __future__ import annotations

import os
import sys

import cv2
import numpy as np
import pytest

from conftest import CASIA_AVAILABLE, MODELS_AVAILABLE, SKIP_NO_CASIA, SKIP_NO_MODEL

# 第 1、2 层不依赖模型：单独标记，CI 上只 skip 第 3 层
_needs_model = pytest.mark.skipif(
    not (CASIA_AVAILABLE and MODELS_AVAILABLE),
    reason=SKIP_NO_CASIA if not CASIA_AVAILABLE else SKIP_NO_MODEL)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge import disguise
from app.challenge.gates import gate_disguise
from app.challenge.store import ChallengeState

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))


@pytest.fixture(scope="module")
def service():
    from app.challenge.service import ChallengeService
    from app.challenge.store import ChallengeStore
    from app.engine import load_all
    load_all()
    import fakeredis
    return ChallengeService(ChallengeStore(fakeredis.FakeRedis(decode_responses=True)))


# ---- 合成对齐空间的"皮肤/布料"素材 ----
# 判据的肤色区间（disguise._skin_ratio）：Y>50, Cr∈[70,135], Cb∈[125,180]。
# 构造时直接在 YCrCb 上取值再转 BGR，保证 evaluate 转回去后落点一致。
SKIN_YCC = (160, 100, 150)          # 肤色：Cr=100 ∈[70,135]，Cb=150 ∈[125,180]
FABRIC_YCC = (150, 40, 200)         # 布料：Cr=40（区间外）——白色口罩的低色度物理特征


def _bgr(ycc_tuple: tuple, shape: tuple, noise: float = 0.0,
         seed: int = 7) -> np.ndarray:
    img = np.full(shape + (3,), ycc_tuple, dtype=np.float32)
    if noise > 0:
        img += np.random.default_rng(seed).normal(0, noise, img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    return cv2.cvtColor(img, cv2.COLOR_YCrCb2BGR)


def _fake_rects() -> tuple[list[int], tuple[int, int, int, int]]:
    """192 空间的两眼框（8 数）与嘴区（4 数）——贴近真实 106 点输出量级。"""
    eye_rect = [30, 70, 70, 92,       # 左眼框
                122, 70, 162, 92]     # 右眼框
    mouth_rect = (70, 130, 122, 158)  # 嘴区
    return eye_rect, mouth_rect


def _clean_crop() -> np.ndarray:
    """干净的合成脸：有纹理的额头/眼框（真实脸五官 std 高），下半脸肤色。"""
    img = _bgr(SKIN_YCC, (192, 192))
    rng = np.random.default_rng(11)
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    # 额头带加纹理（真实额头有眉毛/发际/光影，std ~36+）
    band = g[19:70, 42:150]
    img[19:70, 42:150] = cv2.cvtColor(
        np.clip(band.astype(np.float32) + rng.normal(0, 45, band.shape),
                0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    # 眼框加内容（瞳孔+眼睑，std ~27+）
    for (ex1, ey1, ex2, ey2) in ((30, 70, 70, 92), (122, 70, 162, 92)):
        patch = g[ey1:ey2, ex1:ex2].astype(np.float32)
        patch[8:14, 14:26] = 40          # 瞳孔暗块
        patch[:4, :] = 60                # 眉/睑缘
        img[ey1:ey2, ex1:ex2] = cv2.cvtColor(
            np.clip(patch, 0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    # 嘴区加暗唇与唇线（嘴区 std ~30+）
    mx1, my1, mx2, my2 = _fake_rects()[1]
    patch = g[my1:my2, mx1:mx2].astype(np.float32)
    patch[8:16, 10:40] = 70
    img[my1:my2, mx1:mx2] = cv2.cvtColor(
        np.clip(patch, 0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    return img


def _paste(aligned: np.ndarray, y1: int, y2: int, x1: int, x2: int,
           ycc_tuple: tuple, noise: float = 5.0, seed: int = 3) -> np.ndarray:
    """在对齐 crop 的矩形上贴"布料"（YCrCb 值 + 织物噪声）。"""
    out = aligned.copy()
    h, w = y2 - y1, x2 - x1
    patch = _bgr(ycc_tuple, (h, w), noise=noise, seed=seed)
    out[y1:y2, x1:x2] = patch
    return out


# ---------------- 第 1 层：判据方向（无模型，CI 可跑） ----------------

def test_clean_face_no_hit():
    """干净合成脸三类全不命中（判据方向的第一根钉子）。"""
    crits = disguise.evaluate(_clean_crop(), *_fake_rects())
    assert disguise.hits(crits) == []
    by_kind = {c.kind: c for c in crits}
    # 干净脸的统计量必须离阈值足够远（标定余量的回归钉）
    assert by_kind["mask"].value > 0.3          # 肤色占比（阈值 0.10）
    assert by_kind["glasses"].value > 25        # 眼框 std（阈值 15）
    assert by_kind["hat"].value > 30            # 额头带 std（阈值 16）


def test_mask_hit_by_skin():
    """下半脸被布料占据 → mask 命中（肤色占比单通道；std 分支已废除）。"""
    eye_rect, mouth_rect = _fake_rects()
    img = _clean_crop()
    # 口罩必盖区（disguise.mask_region 的外扩范围）整体贴布料
    x1, y1, x2, y2 = disguise.mask_region(eye_rect, mouth_rect)
    masked = _paste(img, max(0, y1), min(192, y2), max(0, x1), min(192, x2),
                    FABRIC_YCC, noise=0.5)
    crits = disguise.evaluate(masked, eye_rect, mouth_rect)
    hits = disguise.hits(crits)
    assert "mask" in hits
    by_kind = {c.kind: c for c in crits}
    assert by_kind["mask"].value is not None
    assert by_kind["mask"].value < 0.10


def test_mask_soft_fabric_hit():
    """带织物噪声的浅色布料（更接近真实口罩）仍命中。"""
    eye_rect, mouth_rect = _fake_rects()
    img = _clean_crop()
    x1, y1, x2, y2 = disguise.mask_region(eye_rect, mouth_rect)
    masked = _paste(img, max(0, y1), min(192, y2), max(0, x1), min(192, x2),
                    FABRIC_YCC, noise=6.0)
    crits = disguise.evaluate(masked, eye_rect, mouth_rect)
    assert "mask" in disguise.hits(crits)


def test_glasses_hit_but_closed_eye_not():
    """暗色平光镜面命中；闭眼（皮肤色低 std 眼睑）不命中——双条件的意义。"""
    eye_rect, mouth_rect = _fake_rects()
    img = _clean_crop()
    # 墨镜：两眼框贴暗色均质块（亮度 ~0.18×脸中心）
    dark = img.copy()
    for (ex1, ey1, ex2, ey2) in ((30, 70, 70, 92), (122, 70, 162, 92)):
        dark = _paste(dark, ey1, ey2, ex1, ex2, (40, 128, 128), noise=1.5)
    crits = disguise.evaluate(dark, eye_rect, mouth_rect)
    assert "glasses" in disguise.hits(crits)

    # 闭眼：眼睑=皮肤色，std 低但 rel 高（≥0.97）→ 亮度条件排除
    closed = img.copy()
    for (ex1, ey1, ex2, ey2) in ((30, 70, 70, 92), (122, 70, 162, 92)):
        closed = _paste(closed, ey1, ey2, ex1, ex2, SKIN_YCC, noise=2.0)
    crits2 = disguise.evaluate(closed, eye_rect, mouth_rect)
    assert "glasses" not in disguise.hits(crits2)


def test_hat_hit_but_bangs_like_texture_not():
    """平坦布料帽子命中；有纹理的刘海/发际代理不命中（std 余量）。"""
    eye_rect, mouth_rect = _fake_rects()
    img = _clean_crop()
    # 平坦帽檐：额头带（192*0.10 ~ 眼框上缘）贴均质布料
    ey_top = min(eye_rect[1], eye_rect[5])
    flat = _paste(img, int(192 * 0.10), ey_top, int(192 * 0.22), int(192 * 0.78),
                  (120, 130, 140), noise=0.5)
    crits = disguise.evaluate(flat, eye_rect, mouth_rect)
    assert "hat" in disguise.hits(crits)

    # 刘海代理：同区域贴高纹理深色块（std ~40）
    textured = _paste(img, int(192 * 0.10), ey_top, int(192 * 0.22), int(192 * 0.78),
                      (80, 80, 80), noise=40.0)
    crits2 = disguise.evaluate(textured, eye_rect, mouth_rect)
    assert "hat" not in disguise.hits(crits2)


def test_missing_regions_degrade_to_no_hit():
    """区域缺失（基线降级/关键点失败）→ 按未命中处理，宁可放过不可错拦。"""
    crits = disguise.evaluate(_clean_crop(), [], (0, 0, 0, 0))
    assert disguise.hits(crits) == []
    assert all(c.value is None for c in crits)


def test_disabled_hat_branch():
    """FA_DISGUISE_HAT=0：帽子分支关闭，平坦帽檐不再命中（误拒逃生门）。"""
    from app.config import settings
    eye_rect, mouth_rect = _fake_rects()
    img = _clean_crop()
    ey_top = min(eye_rect[1], eye_rect[5])
    flat = _paste(img, int(192 * 0.10), ey_top, int(192 * 0.22), int(192 * 0.78),
                  (120, 130, 140), noise=0.5)
    old = settings.disguise.hat_enabled
    settings.disguise.hat_enabled = False
    try:
        crits = disguise.evaluate(flat, eye_rect, mouth_rect)
        assert "hat" not in disguise.hits(crits)
    finally:
        settings.disguise.hat_enabled = old


def test_advice_and_kind_text():
    assert disguise.advice(["mask"]) == "请摘下口罩"
    assert disguise.advice(["mask", "hat"]) == "请摘下口罩，请摘下帽子"
    assert disguise.kind_text(["glasses", "mask"]) == "墨镜、口罩"
    assert disguise.kind_text([]) == "面部遮挡物"


# ---------------- 第 2 层：finish 占比门禁（无模型） ----------------

def _state(occl_total: int, frames: int) -> ChallengeState:
    st = ChallengeState(
        challenge_id="c_test", session_id=1, student_id=1, ticket_jti="t",
        action="EYE_CLOSE", need=1, window_ms=3000, min_frames=8,
        created_ms=0, baseline_ratio=0.5, baseline_degraded=False)
    st.ratios = [0.5] * frames
    st.occl_total = occl_total
    return st


def test_gate_disguise_ratio():
    assert gate_disguise(_state(0, 10)).passed is True
    assert gate_disguise(_state(2, 10)).passed is True      # 0.20 ≤ 0.50
    assert gate_disguise(_state(5, 10)).passed is True      # 0.50 恰在边界：≤ 放行
    assert gate_disguise(_state(6, 10)).passed is False     # 0.60 > 0.50
    g = gate_disguise(_state(6, 10))
    assert g.value == 0.6 and g.threshold == 0.50


def test_gate_disguise_switch_and_empty():
    from app.config import settings
    old = settings.disguise.enabled
    settings.disguise.enabled = False
    try:
        assert gate_disguise(_state(10, 10)).passed is True
    finally:
        settings.disguise.enabled = old
    # 无有脸帧按不足处理
    assert gate_disguise(_state(0, 0)).passed is False


# ---------------- 第 3 层：端到端（模型 + CASIA） ----------------

@_needs_model
def test_e2e_clean_bpcer(service):
    """CASIA 干净脸全链路：伪装门禁不得成为拒绝原因（BPCER 声明）。"""
    from app.common import tickets
    dirs = sorted(d for d in os.listdir(CASIA) if os.path.isdir(os.path.join(CASIA, d)))[:40]
    rejected = []
    for i, d in enumerate(dirs):
        img_path = os.path.join(CASIA, d, f"{d}_0.jpg")
        img = cv2.imread(img_path)
        if img is None:
            continue
        ticket = tickets.issue(1, 500 + i, f"jti-bpcer-{i}")
        info = service.start(ticket, _composite(img))
        cid = info["challengeId"]
        for f in range(10):
            service.frame(cid, _composite(img), seq=f)
        result = service.finish(cid)
        if not result["passed"] and result["rejectGate"] == "disguise":
            rejected.append(d)
    assert rejected == [], f"干净脸被伪装门禁误拒：{rejected}"


@_needs_model
def test_e2e_mask_start_rejected(service):
    """软口罩 start：检出 → 判据命中 → DisguiseError（FA-4009 语义）。"""
    from app.common import tickets
    img = cv2.imread(os.path.join(CASIA, "000", "000_0.jpg"))
    masked = _mask_frame(img, alpha=0.85)
    ticket = tickets.issue(1, 601, "jti-mask-start")
    with pytest.raises(Exception) as exc:
        service.start(ticket, masked)
    assert "遮挡" in str(exc.value) and "口罩" in str(exc.value)


@_needs_model
def test_e2e_hard_mask_rejected_anyway(service):
    """完整硬口罩 start：SCRFD 检不出 → NoFace（FA-4001）。

    极端遮挡的预期行为（第一轮实验结论）：检测层先挡住。钉住这条防止
    以后有人把「口罩走 FA-4001」当判据 bug 处理。
    """
    from app.challenge.service import DisguiseError, NoFace
    from app.common import tickets
    img = cv2.imread(os.path.join(CASIA, "000", "000_0.jpg"))
    hard = _mask_frame(img, alpha=1.0)
    ticket = tickets.issue(1, 603, "jti-mask-hard")
    with pytest.raises((NoFace, DisguiseError)):
        service.start(ticket, hard)


@_needs_model
def test_e2e_mask_frame_abort(service):
    """干净基线开挑战后戴上软口罩：连续 3 帧 → DisguiseError 中止。

    这是口罩拦截的主防线：start 用干净帧锁定正常坐标，口罩帧在锁定的
    对齐空间上评估——统计区与皮肤分布都可信，mask 通道命中。
    """
    from app.common import tickets
    img = cv2.imread(os.path.join(CASIA, "000", "000_0.jpg"))
    masked = _mask_frame(img, alpha=0.85)
    ticket = tickets.issue(1, 602, "jti-mask-frame")
    info = service.start(ticket, _composite(img))
    cid = info["challengeId"]
    with pytest.raises(Exception) as exc:
        for f in range(3):
            service.frame(cid, masked, seq=f)
    assert "口罩" in str(exc.value)


def _composite(img112, size=(720, 1280), side=320):
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = side / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)))
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2, (size[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def _mask_frame(img112, alpha: float = 0.85):
    """在原图坐标系给下半脸贴织物口罩（探针脚本的 192↔frame 映射法）。

    alpha<1 是**软口罩**（与原脸混合）：完整硬贴会把 SCRFD 的检出分数压到
    阈值之下（实测 detect=0，挑战走 FA-4001 无脸路径——极端遮挡的预期行为，
    见 test_e2e_hard_mask_rejected_anyway）；软口罩模拟「脸可检出但带着
    遮挡」的场景，判据链路（锁定坐标 → aligned 统计 → skin 占比）只有在
    这个场景下才被端到端验证。alpha=0.85 时布料区统计仍是布料（skin≈0.01）。
    """
    from app.engine import detector, landmarker
    frame = _composite(img112)
    face = detector().detect_largest(frame)
    assert face is not None
    pts, _ = landmarker().landmarks(frame, face.bbox)
    m = landmarker().align_matrix(face.bbox)
    er = landmarker().eye_rects(pts)
    mr = landmarker().mouth_rect(pts)
    x1, y1, x2, y2 = disguise.mask_region(er, mr)
    inv = cv2.invertAffineTransform(m)
    corners = np.array([[x1, y1], [x2, y1], [x2, y2], [x1, y2]], dtype=np.float32)
    ones = np.hstack([corners, np.ones((4, 1), dtype=np.float32)])
    poly = (ones @ inv.T).astype(np.int32)
    out = frame.copy()
    h, w = out.shape[:2]
    fabric = np.full((h, w, 3), FABRIC_YCC, dtype=np.float32)
    fabric += np.random.default_rng(5).normal(0, 5.0, fabric.shape)
    fabric = cv2.cvtColor(np.clip(fabric, 0, 255).astype(np.uint8),
                          cv2.COLOR_YCrCb2BGR)
    mask = np.zeros((h, w), dtype=np.uint8)
    cv2.fillPoly(mask, [poly], 255)
    if alpha >= 1.0:
        out[mask > 0] = fabric[mask > 0]
    else:
        blended = (frame.astype(np.float32) * (1 - alpha)
                   + fabric.astype(np.float32) * alpha)
        out = np.where(mask[..., None] > 0, blended.astype(np.uint8), out)
    return out
