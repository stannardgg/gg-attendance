"""动作计数的判据测试（Gate：动作次数准确率 ≥95%）。

★ 必须如实说明：这里用的是**合成暗度序列**，验证的是计数器逻辑本身——
  一段"暗度升高 2 帧"必须数成 1 次，抖动不得虚报。逻辑对了，
  真实视频的准确率只取决于判据参数（那要等真人素材标定）。
  静态照片无法张嘴，真人视频素材目前不存在，这一条 Gate 的"真人验证"
  在 B3 交付时是**未完成**状态，见 handover。

尺度类动作（靠近 / 离远）同样是合成尺度序列：验证"朝指定方向变化 ≥delta
且持续 ≥hold 帧算 1 次、静止与轻微挪动不算、反向不误判"。
"""
from __future__ import annotations

import numpy as np

from app.challenge.action import MouthActionCounter, ScaleActionCounter, face_scale


def make_counter(base=0.02):
    return MouthActionCounter(base_ratio=base, abs_rise=0.05,
                              rise_ratio=1.45, hold_frames=2)


def seq_one_mouth():
    """基线 6 帧 + 张嘴 3 帧 + 闭合 5 帧。期望 1 次。"""
    base = [0.02] * 6
    open_ = [0.12, 0.15, 0.11]
    close = [0.02] * 5
    return base + open_ + close


def seq_two_mouths():
    """两次张嘴，间隔 3 帧。期望 2 次。"""
    return ([0.02] * 4 + [0.12, 0.14, 0.11] + [0.02] * 3
            + [0.13, 0.15, 0.12] + [0.02] * 4)


def seq_jitter():
    """轻微抖动（0.03、0.04），不构成动作。期望 0 次。"""
    return [0.02, 0.03, 0.02, 0.04, 0.02, 0.03, 0.02, 0.02]


def seq_too_short():
    """单帧 0.15：时长不足 hold_frames，不算一次。期望 0 次。"""
    return [0.02, 0.02, 0.15, 0.02, 0.02]


def seq_sustained_open():
    """张嘴后保持张开 8 帧：应当只算 1 次（连续段），不是 8 次。"""
    return [0.02] * 4 + [0.13] * 8 + [0.02] * 3


def test_one_mouth():
    c = make_counter()
    for r in seq_one_mouth():
        c.observe(r)
    count, segs = c.result()
    assert count == 1, (count, segs)
    assert len(segs) == 1
    assert segs[0][1] - segs[0][0] + 1 >= 2


def test_two_mouths():
    c = make_counter()
    for r in seq_two_mouths():
        c.observe(r)
    count, segs = c.result()
    assert count == 2, (count, segs)


def test_jitter_ignored():
    c = make_counter()
    for r in seq_jitter():
        c.observe(r)
    count, _ = c.result()
    assert count == 0


def test_too_short_ignored():
    c = make_counter()
    for r in seq_too_short():
        c.observe(r)
    count, _ = c.result()
    assert count == 0


def test_sustained_is_one():
    c = make_counter()
    for r in seq_sustained_open():
        c.observe(r)
    count, _ = c.result()
    assert count == 1


def test_action_at_end_counts():
    """序列结束时仍在动作中（客户端提前 finish），应按已积累长度判定。"""
    c = make_counter()
    for r in [0.02] * 5 + [0.13, 0.14, 0.12]:
        c.observe(r)
    count, _ = c.result()
    assert count == 1


# ---------------- 尺度类动作：靠近 / 离远 ----------------

BASE = 0.26          # 人脸 sqrt(宽×高) / 画面宽，约等于"占画面四分之一"
DELTA = 0.18


def make_scale(closer: bool, base: float = BASE):
    return ScaleActionCounter(base_scale=base, closer=closer,
                              delta=DELTA, hold_frames=2)


def count_scale(seq: list[float], closer: bool) -> int:
    c = make_scale(closer)
    for s in seq:
        c.observe(s)
    return c.result()[0]


def test_move_closer_detected():
    seq = [BASE] * 4 + [BASE * 1.25] * 6
    assert count_scale(seq, closer=True) == 1


def test_move_away_detected():
    seq = [BASE] * 4 + [BASE * 0.80] * 6
    assert count_scale(seq, closer=False) == 1


def test_direction_is_not_interchangeable():
    """靠近的序列不能被「离远」判据算成做到了，反之亦然。

    否则两个动作退化成一个「随便动一下」，指令就白给了。
    """
    closer_seq = [BASE] * 4 + [BASE * 1.25] * 6
    away_seq = [BASE] * 4 + [BASE * 0.80] * 6
    assert count_scale(closer_seq, closer=False) == 0
    assert count_scale(away_seq, closer=True) == 0


def test_scale_jitter_ignored():
    """手持抖动 / 检测框波动（±2%）不得算成动作。"""
    rng = np.random.default_rng(1)
    seq = [BASE * float(1 + rng.uniform(-0.02, 0.02)) for _ in range(10)]
    assert count_scale(seq, closer=True) == 0
    assert count_scale(seq, closer=False) == 0


def test_scale_below_threshold_ignored():
    """只挪动 10%，低于 delta=0.18，不算动作（阈值边界）。"""
    assert count_scale([BASE] * 4 + [BASE * 1.10] * 6, closer=True) == 0
    assert count_scale([BASE] * 4 + [BASE * 0.90] * 6, closer=False) == 0


def test_scale_single_frame_spike_ignored():
    """单帧跳变时长不足 hold_frames，不算一次（对齐误差/误检的典型形态）。"""
    assert count_scale([BASE, BASE, BASE * 1.4, BASE, BASE], closer=True) == 0


def test_scale_missing_face_is_not_action():
    """没检出人脸的帧（尺度记 0）不能被 MOVE_AWAY 白捡成「学生做对了」。"""
    seq = [BASE] * 4 + [0.0] * 6
    assert count_scale(seq, closer=False) == 0


def test_scale_sustained_is_one():
    """靠近后一直保持：只算 1 次，不是每帧算一次。"""
    seq = [BASE] * 3 + [BASE * 1.3] * 8
    c = make_scale(True)
    for s in seq:
        c.observe(s)
    count, segs = c.result()
    assert count == 1
    assert len(segs) == 1


def test_face_scale_is_resolution_invariant():
    """同一构图换分辨率（像素翻倍）后尺度不变——基线不能因为客户端换分辨率失效。"""

    class Stub:
        def __init__(self, w, h):
            self.width, self.height = w, h

    small = face_scale(Stub(120, 150), 640)
    large = face_scale(Stub(240, 300), 1280)
    assert abs(small - large) < 1e-9
    assert face_scale(None, 640) == 0.0
