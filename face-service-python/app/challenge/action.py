"""动作判定：固定 ROI + 亮度统计 + 连续段计数；尺度类动作用检测框尺寸。

四类判据，全部避开 `2d106det` 的形状归一化：

  | 动作族 | 信号 | 来源 |
  |---|---|---|
  | 张嘴（回滚用） | 嘴区暗像素占比 | 192 对齐空间里的固定 ROI |
  | 靠近 / 离远 | 检测框几何平均尺度 / 画面宽 | SCRFD bbox，原图坐标 |
  | 闭眼 | 眼框暗像素占比 | 192 对齐空间里的固定眼框 |
  | 左转 / 右转 | 鼻尖相对双眼中点的横向偏移 / 瞳距 | SCRFD **5 点**，原图坐标 |

最后一行是 CR-021 的新增项，也是唯一用到关键点的判据。它与 v2.0 禁用的
「关键点角度」不是一回事：禁的是 `2d106det` 那个带强形状先验的回归器
（偏 20° 只变 0.03~0.05，信号被归一化吃掉）；SCRFD 的 5 点检测头直接回归
原图坐标，保留了姿态。实测（`probe_action.py --pose`）：静止抖动
std=0.0104，整帧滚转 ±15/±30° 的漂移 mean|Δ|=0.030 / max=0.108，阈值 0.12
对前者是 11.5σ、对后者刚好留出余量。

眼框也是关键点，但只用来**定位**：眼框取自 106 点的眼球轮廓（33~42 / 87~96），
判定仍用框内暗度统计——这与「关键点只用于定位 ROI」的全局约束一致。

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


def dark_ratio(gray_roi: np.ndarray) -> float:
    """ROI 内暗像素占比（0~1）。嘴、眼、任意小区域通用。

    阈值取 ROI 中位数的 0.82 倍并下移 8 个灰度级，自适应不同光照与肤色：
      - 固定灰度阈值（如 <80）在暗光教室会把整张脸都算"暗"；
      - 固定比例阈值在逆光下又会把背景算进去。
    中位数自适应 + 绝对下移，两者结合才稳。

    ⚠️ 整块 ROI 亮度均匀（例如被黑条遮挡）时中位数会塌到 0，阈值变成负数，
    统计结果反而归 0——这不是"没有暗像素"，是判据失效。质检/打码素材上不要用它。
    """
    if gray_roi.size == 0:
        return 0.0
    med = float(np.median(gray_roi))
    thresh = med * 0.82 - 8.0
    if thresh <= 0:
        # 极亮画面（如过曝）时阈值可能为负，此时用绝对下限兜底
        thresh = med * 0.7
    return float((gray_roi < thresh).sum() / gray_roi.size)


# 兼容旧名：v1.x 只有嘴部动作时用的就是这个名字，外部脚本与测试还在引用。
mouth_dark_ratio = dark_ratio


def interocular(face) -> float:
    """瞳距（原图像素）。眼框尺寸、偏航代理都以它为尺度基准。"""
    if face is None:
        return 0.0
    kps = np.asarray(face.kps, dtype=np.float64).reshape(5, 2)
    return float(np.linalg.norm(kps[1] - kps[0]))


def yaw_proxy(face) -> float:
    """偏航代理：鼻尖相对双眼中点、沿**眼轴方向**的偏移，除以瞳距。

    为什么沿眼轴投影而不是直接取 x 差：歪头（滚转）会把 x 差整体带偏。
    沿「左眼 → 右眼」的单位向量投影后，滚转在数学上被消掉；实测残余来自
    检测器自身在侧倾下的关键点偏差（整帧 ±30° 漂移 mean 0.029 / max 0.11）。

    符号：`kps[0]` 是图像左侧的眼睛（实测 60/60 身份成立），代理 > 0 表示鼻尖
    偏向画面右侧 —— 对着镜头的人把头转向**他自己的左边**时就是这样。
    因此 TURN_LEFT 对应代理增大。注意这依赖"上传帧未镜像"：前端只在
    `<video>` 上做 CSS 镜像，`drawImage` 取的是原始流（useCamera.captureSmall）。
    """
    if face is None:
        return 0.0
    kps = np.asarray(face.kps, dtype=np.float64).reshape(5, 2)
    le, re, nose = kps[0], kps[1], kps[2]
    v = re - le
    iod = float(np.linalg.norm(v))
    if iod <= 1e-6:
        return 0.0
    return float(np.dot(nose - (le + re) / 2.0, v / iod) / iod)


def roll_proxy(face) -> float:
    """滚转代理：眼轴相对图像水平线的夹角（弧度，有符号）。

    与 yaw_proxy 同源（SCRFD 5 点、原图坐标），但用途不是判动作，而是
    **姿态一致性闸**：固定眼框 ROI 建立在基线帧上，闭眼判据窗内头部一旦
    偏航/滚转，虹膜会被搬出 ROI，暗度统计跟着失真——「提示闭眼却转头」
    被误判成闭眼就是这条通道。yaw_proxy 用眼轴投影把滚转消掉了，所以
    姿态闸需要这里单独给出原始角度。

    符号：正值 = 右眼低于左眼（图像上顺时针歪头）。静止抖动 std≈0.01 rad，
    ±15° 滚转 = 0.26 rad——probe_action --pose 的标定范围即以此为界。
    """
    if face is None:
        return 0.0
    kps = np.asarray(face.kps, dtype=np.float64).reshape(5, 2)
    le, re = kps[0], kps[1]
    v = re - le
    if float(np.linalg.norm(v)) <= 1e-6:
        return 0.0
    return float(np.arctan2(v[1], v[0]))


def eye_dark_ratio(gray: np.ndarray, rois: list[int]) -> float:
    """两只眼框的暗像素占比均值。`rois` 是扁平的 8 个整数（192 空间两组矩形）。"""
    vals = []
    for i in range(0, len(rois) - 3, 4):
        x1, y1, x2, y2 = rois[i:i + 4]
        patch = gray[y1:y2, x1:x2]
        if patch.size:
            vals.append(dark_ratio(patch))
    return float(np.mean(vals)) if vals else 0.0


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
        # 反向段追踪（与 TurnActionCounter 同构）：正向计数达标但反向出现过
        # 达标段 = 学生没按指令做（例如提示靠近却退远，靠单帧噪声凑出正向段），
        # gates.gate_action 据此否决。
        self._rev_run: list[float] = []
        self._in_reverse = False
        self._reverse_count = 0

    def observe(self, scale: float) -> None:
        """喂入一帧的人脸尺度。顺序敏感，乱序帧不应调用。

        无效帧（没检出人脸，scale<=0）记为「动作不成立」而不是 0：
        当成 0 会让 MOVE_AWAY 白捡一次（0 必然小于 base*(1-delta)），
        等于把「没检出」算成「学生做到了」。反向同理——不送检即不计数。
        """
        active = False
        reverse = False
        if scale > 0:
            ratio = float(scale) / self.base
            self._min_ratio = min(self._min_ratio, ratio)
            self._max_ratio = max(self._max_ratio, ratio)
            active = (ratio >= 1.0 + self.delta) if self.closer \
                else (ratio <= 1.0 - self.delta)
            reverse = (ratio <= 1.0 - self.delta) if self.closer \
                else (ratio >= 1.0 + self.delta)

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
        if reverse:
            if not self._in_reverse:
                self._in_reverse = True
            self._rev_run.append(scale)
        else:
            if self._in_reverse:
                if len(self._rev_run) >= self.hold_frames:
                    self._reverse_count += 1
                self._in_reverse = False
                self._rev_run = []
        self._seq += 1

    def result(self) -> tuple[int, list[tuple[int, int]]]:
        """返回 (次数, 段列表)。结尾仍在动作中按已积累长度判定（同张嘴）。"""
        if self._in_action and len(self._run) >= self.hold_frames:
            return self._count + 1, self._segments + [(self._seg_start, self._seq - 1)]
        return self._count, list(self._segments)

    @property
    def reverse_count(self) -> int:
        """反向达标段数（含结尾未闭合的段）。> 0 = 出现过与指令相反的动作。"""
        if self._in_reverse and len(self._rev_run) >= self.hold_frames:
            return self._reverse_count + 1
        return self._reverse_count

    @property
    def observed_range(self) -> tuple[float, float]:
        """(最小, 最大) 尺度比，用于日志与申诉归因。"""
        return self._min_ratio, self._max_ratio


class EyeCloseCounter:
    """按连续段统计「闭眼」次数（CR-021）。

    判据与张嘴同构，方向相反：眼睑盖住虹膜 → 眼框里的暗像素**减少**。

      base_ratio  基线眼框暗度（指令渲染之前的中性帧）
      abs_drop    相对基线的绝对降幅（0.06 = 6% 的像素由暗转亮）
      drop_ratio  同时要求降到基线的 0.60 倍以下，两个条件 AND

    为什么是这两个阈值：80 个身份「肤色覆盖眼睑 + 保留睫毛线」实测，
    睁眼 0.257 → 闭眼 0.053，逐人降幅 0.204 ± 0.055，最小 0.043；
    静止抖动（±4px / 亮度 ±5% / JPEG q60）std=0.0096，0.06 相当于 6σ。
    两个降幅不足 0.06 的个例，基线本身只有 0.093 / 0.098 —— 由
    `eye_base_min=0.10` 的可做性门槛挡在抽签之外（不是判失败）。

    为什么不用关键点开合比：整只眼睛被肤色覆盖时开合比只跌 19%（v2.0 实测），
    而同一实验里暗度从 0.018 涨到 0.309。梯度能量也试过，相对降幅仅 4.8%，
    且 20% 的身份是反号——已否决。
    """

    def __init__(self, base_ratio: float, abs_drop: float = 0.06,
                 drop_ratio: float = 0.60, hold_frames: int = 2):
        self.base = max(base_ratio, 1e-4)
        self.abs_drop = abs_drop
        self.drop_ratio = drop_ratio
        self.hold_frames = max(1, hold_frames)
        self._run: list[float] = []
        self._in_close = False
        self._count = 0
        self._segments: list[tuple[int, int]] = []
        self._seg_start = 0
        self._seq = 0
        self._max_ratio = base_ratio
        self._ratios: list[float] = []
        self._rescored = False

    def observe(self, ratio: float) -> None:
        """喂入一帧的眼框暗度。顺序敏感，乱序帧不应调用。"""
        self._ratios.append(ratio)
        self._max_ratio = max(self._max_ratio, ratio)
        active = (ratio <= self.base - self.abs_drop) and (ratio <= self.base * self.drop_ratio)
        if active:
            if not self._in_close:
                self._in_close = True
                self._seg_start = self._seq
            self._run.append(ratio)
        else:
            if self._in_close:
                if len(self._run) >= self.hold_frames:
                    self._count += 1
                    self._segments.append((self._seg_start, self._seq - 1))
                self._in_close = False
                self._run = []
        self._seq += 1

    def result(self) -> tuple[int, list[tuple[int, int]]]:
        """返回 (次数, 段列表)。结尾仍在闭眼中按已积累长度判定。

        ★ 基线自愈（与张嘴同构，方向相反）：基线帧若正好拍在"已经闭眼"上，
        基线被压到闭眼水平，之后任何闭眼都跌不破它，次数恒为 0。判据是
        序列里出现过明显**更亮**（睁眼）的帧——那就用观测到的最高值重算。
        正常场景 max ≈ baseline，不触发，行为不变。
        """
        if not self._rescored and self._max_ratio > self.base * 1.6:
            self._rescored = True
            healed = EyeCloseCounter(self._max_ratio, self.abs_drop,
                                     self.drop_ratio, self.hold_frames)
            healed._rescored = True
            for r in self._ratios:
                healed.observe(r)
            return healed.result()
        if self._in_close and len(self._run) >= self.hold_frames:
            return self._count + 1, self._segments + [(self._seg_start, self._seq - 1)]
        return self._count, list(self._segments)

    @property
    def observed_max(self) -> float:
        return self._max_ratio


class TurnActionCounter:
    """按连续段统计「朝指定方向转头」的次数（CR-021）。

    与 ScaleActionCounter 同构，把「尺度离基线多远」换成「偏航代理离基线多远」：

      base_yaw   基线帧的偏航代理
      direction  +1 = 向左（代理增大）；-1 = 向右（代理减小）
      delta      相对基线的变化量（0.12 IOD，约 20°）
      hold_frames 一次动作至少连续几帧（2 帧 @4fps = 0.5s）

    方向必须相对基线判定：用「序列最大/最小值」的话，「先左后右」会同时满足
    两个方向，指令就退化成「随便动一下头」。
    """

    def __init__(self, base_yaw: float, direction: int, delta: float = 0.12,
                 hold_frames: int = 2):
        self.base = float(base_yaw)
        self.direction = 1 if direction >= 0 else -1
        self.delta = float(delta)
        self.hold_frames = max(1, hold_frames)
        self._run: list[float] = []
        self._in_action = False
        self._count = 0
        self._segments: list[tuple[int, int]] = []
        self._seg_start = 0
        self._seq = 0
        self._min_delta = 0.0
        self._max_delta = 0.0
        # 反向段追踪（2026-11-22）：不只数正向，还要记录反向是否出现过达标段。
        # 「提示左转却右转」本身不会产生正向计数（方向相对基线带符号），但
        # hold=1 下单帧噪声可能碰巧在正向凑出一段——转头滚转残余实测最大
        # 0.108，距阈值 0.12 只差 1σ。反向出现过达标段就说明学生没按指令做，
        # 正向计数再够也判不过（见 gates.gate_action 的否决逻辑）。
        self._rev_run: list[float] = []
        self._in_reverse = False
        self._reverse_count = 0

    def observe(self, yaw: float) -> None:
        """喂入一帧的偏航代理。顺序敏感，乱序帧不应调用。"""
        d = (float(yaw) - self.base) * self.direction
        self._min_delta = min(self._min_delta, d)
        self._max_delta = max(self._max_delta, d)
        active = d >= self.delta
        reverse = d <= -self.delta
        if active:
            if not self._in_action:
                self._in_action = True
                self._seg_start = self._seq
            self._run.append(yaw)
        else:
            if self._in_action:
                if len(self._run) >= self.hold_frames:
                    self._count += 1
                    self._segments.append((self._seg_start, self._seq - 1))
                self._in_action = False
                self._run = []
        # 反向状态机与正向同构。刻意展开成平级字段而不是递归一个反实例：
        # 递归会让反向计数器自己也带一个反向，语义混乱且浪费帧循环。
        if reverse:
            if not self._in_reverse:
                self._in_reverse = True
            self._rev_run.append(yaw)
        else:
            if self._in_reverse:
                if len(self._rev_run) >= self.hold_frames:
                    self._reverse_count += 1
                self._in_reverse = False
                self._rev_run = []
        self._seq += 1

    def result(self) -> tuple[int, list[tuple[int, int]]]:
        if self._in_action and len(self._run) >= self.hold_frames:
            return self._count + 1, self._segments + [(self._seg_start, self._seq - 1)]
        return self._count, list(self._segments)

    @property
    def reverse_count(self) -> int:
        """反向达标段数（含结尾未闭合的段）。> 0 = 出现过与指令相反的动作。"""
        if self._in_reverse and len(self._rev_run) >= self.hold_frames:
            return self._reverse_count + 1
        return self._reverse_count

    @property
    def observed_delta(self) -> tuple[float, float]:
        """(最小, 最大) 带方向的偏移量，用于日志与申诉归因。"""
        return self._min_delta, self._max_delta
