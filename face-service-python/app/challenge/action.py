"""动作判定：固定 ROI + 亮度统计 + 连续段计数；尺度类动作用检测框尺寸。

实现的是项目文档 §4.4.2 的核心设计，三条铁律在这里固化：

1. **关键点只用于定位 ROI，判动作用 ROI 内的亮度统计**。
   2d106det 是形状回归器，会把偏转归一化掉——用它算嘴部开合度，
   偏 20° 指标只变 0.03~0.05，动作就"看不见"了。亮度不受这个影响。

2. **ROI 在 start 时锁定，后续帧用刚性变换搬运**。
   同一帧序列里人脸位置基本不变，每帧重算 ROI 会让"张嘴"变成"ROI 跟着嘴动"，
   测出来永远是常数。锁定 ROI 后，张嘴 → 嘴部露出的暗色（口腔/阴影）增多 → 亮度下降。

3. **按连续段计数**。一次张嘴 = 暗度连续低于基线 ≥ hold_frames 帧的一段。
   不做连续段判断的话，抖动会被数成十几次。

4. **尺度类动作（靠近 / 离远）看检测框尺寸，不看关键点**。
   bbox 由 SCRFD 在原图像素坐标给出，没有经过 2d106det 的形状归一化，
   「人脸在画面里变大 / 变小」就是它直接测量到的量。尺度取
   `sqrt(宽 × 高)`（几何平均）而不是宽：侧脸 30° 会让 bbox 宽度缩 13%，
   几何平均只缩 7%，避免把「转了下头」误判成「离远了」。
"""
from __future__ import annotations

import numpy as np


def mouth_dark_ratio(gray_roi: np.ndarray) -> float:
    """嘴区暗像素占比（0~1）。

    阈值取 ROI 中位数的 0.82 倍并下移 8 个灰度级，自适应不同光照与肤色：
      - 固定灰度阈值（如 <80）在暗光教室会把整张脸都算"暗"；
      - 固定比例阈值在逆光下又会把背景算进去。
    中位数自适应 + 绝对下移，两者结合才稳。
    """
    if gray_roi.size == 0:
        return 0.0
    med = float(np.median(gray_roi))
    thresh = med * 0.82 - 8.0
    if thresh <= 0:
        # 极亮画面（如过曝）时阈值可能为负，此时用绝对下限兜底
        thresh = med * 0.7
    return float((gray_roi < thresh).sum() / gray_roi.size)


class MouthActionCounter:
    """对一帧序列统计「张嘴次数」。

    用法：
        counter = MouthActionCounter(...)
        counter.observe(ratio)      # 每帧调一次，按 seq 顺序
        counter.result()            # (count, segments)

    参数来自 §10.5（sys_config，均可热调）：
      base_ratio   基线暗度（start 时的 ROI 暗度，或序列最低值）
      abs_rise     相对基线的绝对增幅（0.05 = 5% 像素变暗）
      rise_ratio   峰值/基线 的倍数（1.45 = 增加 45%）
      hold_frames  一次张嘴至少连续几帧（2 帧 @4fps = 0.5s，正好是"张嘴半秒"）
    """

    def __init__(self, base_ratio: float, abs_rise: float = 0.05,
                 rise_ratio: float = 1.45, hold_frames: int = 2):
        self.base = max(base_ratio, 1e-4)
        self.abs_rise = abs_rise
        self.rise_ratio = rise_ratio
        self.hold_frames = max(1, hold_frames)
        self._run: list[float] = []
        self._in_mouth = False
        self._count = 0
        self._segments: list[tuple[int, int]] = []
        self._seg_start = 0
        self._seq = 0
        # 观测到的最低暗度：基线不可得时的兜底（baselineDegraded 场景）
        self._min_ratio = base_ratio
        self._ratios: list[float] = []
        self._rescored = False

    def observe(self, ratio: float) -> None:
        """喂入一帧的暗度。顺序敏感，乱序帧不应调用。"""
        self._ratios.append(ratio)
        self._min_ratio = min(self._min_ratio, ratio)
        active = (ratio >= self.base + self.abs_rise) and (ratio >= self.base * self.rise_ratio)
        # 注：ratio 越大 = 越暗（暗像素占比升高）
        if active:
            if not self._in_mouth:
                self._in_mouth = True
                self._seg_start = self._seq
            self._run.append(ratio)
        else:
            if self._in_mouth:
                # 一段结束：判定长度
                if len(self._run) >= self.hold_frames:
                    self._count += 1
                    self._segments.append((self._seg_start, self._seq - 1))
                self._in_mouth = False
                self._run = []
        self._seq += 1

    def result(self) -> tuple[int, list[tuple[int, int]]]:
        """返回 (次数, 段列表)。结尾若仍在动作中，按已积累长度判定。

        ★ 基线自愈（16.3 场景 13「基线污染」）：start 那一帧如果正好赶上学生
        已经张嘴，baseline_ratio 会被抬到"张嘴水平"，之后所有张嘴都涨不过它，
        动作次数恒为 0 —— 学生明明照做了却被判 FA-4003。

        判据很直接：既然序列里出现过比基线**更亮**（暗度更低）的帧，说明嘴至少
        闭合过一次，真实的闭口基线就是那个最低值。此时用观测到的最低暗度重算一遍
        计数。正常场景下 min ≈ baseline（甚至略高），不会触发重算，行为不变。
        """
        if not self._rescored and 1e-4 < self._min_ratio < self.base * 0.9:
            self._rescored = True
            healed = MouthActionCounter(self._min_ratio, self.abs_rise,
                                        self.rise_ratio, self.hold_frames)
            healed._rescored = True          # 只自愈一次，避免极小基线来回递归
            for r in self._ratios:
                healed.observe(r)
            return healed.result()
        if self._in_mouth and len(self._run) >= self.hold_frames:
            return self._count + 1, self._segments + [(self._seg_start, self._seq - 1)]
        return self._count, list(self._segments)

    @property
    def observed_min(self) -> float:
        return self._min_ratio


def face_scale(face, frame_width: int) -> float:
    """人脸在画面中的线性尺度（无量纲，按画面宽度归一）。

    为什么不直接用像素：客户端上传分辨率可能不同（480p / 720p），用像素做基线
    换个分辨率就失效；除以画面宽度后，尺度只反映「人脸占画面多大」。
    为什么用几何平均而不是宽：见模块 docstring 第 4 条（侧脸会缩宽）。
    """
    if frame_width <= 0 or face is None:
        return 0.0
    w = max(float(face.width), 1.0)
    h = max(float(face.height), 1.0)
    return float(np.sqrt(w * h) / frame_width)


class ScaleActionCounter:
    """按连续段统计「尺度朝指定方向变化」的次数（靠近 / 离远）。

    与 MouthActionCounter 同构，只把判据从「暗度升高」换成「尺度远离基线」：

      base_scale   基线帧的人脸尺度（指令下发之前的画面）
      closer       True = 找「变大」的段；False = 找「变小」的段
      delta        相对基线的变化比例（0.18 = 尺度变化 18%）
      hold_frames  一次动作至少连续几帧（2 帧 @4fps = 0.5s）

    方向必须相对基线判定，不能用「序列最大/最小之比」代替：那样「前后晃一下」
    一个动作同时满足靠近和离远，指令就退化成「随便动一下」。
    """

    def __init__(self, base_scale: float, closer: bool, delta: float = 0.18,
                 hold_frames: int = 2):
        self.base = max(float(base_scale), 1e-6)
        self.closer = closer
        self.delta = float(delta)
        self.hold_frames = max(1, hold_frames)
        self._run: list[float] = []
        self._in_action = False
        self._count = 0
        self._segments: list[tuple[int, int]] = []
        self._seg_start = 0
        self._seq = 0
        self._min_ratio = 1.0
        self._max_ratio = 1.0

    def observe(self, scale: float) -> None:
        """喂入一帧的人脸尺度。顺序敏感，乱序帧不应调用。

        无效帧（没检出人脸，scale<=0）记为「动作不成立」而不是 0：
        当成 0 会让 MOVE_AWAY 白捡一次（0 必然小于 base*(1-delta)），
        等于把「没检出」算成「学生做到了」。
        """
        active = False
        if scale > 0:
            ratio = float(scale) / self.base
            self._min_ratio = min(self._min_ratio, ratio)
            self._max_ratio = max(self._max_ratio, ratio)
            active = (ratio >= 1.0 + self.delta) if self.closer \
                else (ratio <= 1.0 - self.delta)

        if active:
            if not self._in_action:
                self._in_action = True
                self._seg_start = self._seq
            self._run.append(scale)
        else:
            if self._in_action:
                if len(self._run) >= self.hold_frames:
                    self._count += 1
                    self._segments.append((self._seg_start, self._seq - 1))
                self._in_action = False
                self._run = []
        self._seq += 1

    def result(self) -> tuple[int, list[tuple[int, int]]]:
        """返回 (次数, 段列表)。结尾仍在动作中按已积累长度判定（同张嘴）。"""
        if self._in_action and len(self._run) >= self.hold_frames:
            return self._count + 1, self._segments + [(self._seg_start, self._seq - 1)]
        return self._count, list(self._segments)

    @property
    def observed_range(self) -> tuple[float, float]:
        """(最小, 最大) 尺度比，用于日志与申诉归因。"""
        return self._min_ratio, self._max_ratio
