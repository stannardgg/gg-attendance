"""动作计数的判据测试（Gate：动作次数准确率 ≥95%）。

★ 必须如实说明：这里用的是**合成暗度序列**，验证的是计数器逻辑本身——
  一段"暗度升高 2 帧"必须数成 1 次，抖动不得虚报。逻辑对了，
  真实视频的准确率只取决于判据参数（那要等真人素材标定）。
  静态照片无法张嘴，真人视频素材目前不存在，这一条 Gate 的"真人验证"
  在 B3 交付时是**未完成**状态，见 handover。
"""
from __future__ import annotations

from app.challenge.action import MouthActionCounter


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
