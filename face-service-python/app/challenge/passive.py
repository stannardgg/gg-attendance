"""被动活体线索：屏幕重放/翻拍的物理痕迹，无模型、纯图像处理。

定位：**只否决，不放行**（veto-only）。三道硬门禁（活体模型/身份/动作）仍是
放行的必要条件，本模块只增加一道"攻击证据"门禁——真实人脸不会触发，
所以它的加入不改变误拒率（BPCER），只降低屏幕重放通过率（APCER）。

三族信号（对应摩尔纹 / 屏幕反光 / 频域峰值）：

1. **非轴向频域孤立峰** `prom`：屏幕重放要经过「帧缓冲 → 屏幕像素网格 →
   摄像头传感器」二次采样，采样相位在面板上逐像素漂移，插值/混叠在频谱
   留下**孤立强峰**；真实人脸纹理是宽带的。打印件半色调网点同样产生周期峰。
2. **高频能量占比** `hf`：重放相关噪声把能量推向上半频段。
3. **过曝白斑占比** `clip`：屏幕自发光形成大面积低饱和过曝区；真实人脸
   只有鼻尖/额头小块镜面高光（clip 因此要求低饱和，见 clip_ratio）。

★ 标定状态（2026-11-22，为什么门禁默认关）：
本机做了三轮合成标定（`scripts/calibrate_passive.py` 可复跑）：
  a) 对齐空间（192 上采样）：bona fide prom p95=112 **高于**重放 p95=60——
     上采样插值本身就在 bona fide 里注入了与重放同族的谱峰，特征失效；
  b) 整帧非轴向峰：合成重放（整数倍重采样）与 bona fide 完全重合；
  c) 人脸原生裁剪非轴向峰 + 高频占比：bona fide prom max=10.2 / hf max=0.60，
     合成重放 max=9.6 / 0.60——**零区分度**。
结论：bicubic 重采样链复现不了屏幕摩尔纹的真实物理（亚像素采样相位漂移、
RGB 子像素结构、背光不均、传感器噪声），这三条线索**无法合成标定**。
按「阈值必须实测得出、勿凭感觉调」的项目铁律：
  - 上线先**逐帧记录特征**（finish 日志，每帧 3 个浮点数，不落原图，隐私安全），
    生产流量免费积累真实标定语料；
  - 门禁 `PASSIVE_GATE=1` 在真机采集标定后开启（协议见 calibrate_passive.py
    模块注释：≥10 场真实摄像头 bona fide 定上限，≥10 场真机屏幕重放定下限，
    阈值取几何中点并留安全边距）。
config 里的阈值是占位值（合成 bona fide max × 1.2），开启前必须重标。
"""
from __future__ import annotations

import cv2
import numpy as np

# 轴向排除角（度）：JPEG 8×8 块效应产生严格水平/垂直的谱峰（f=1/8 c/px 及其
# 谐波），屏幕摩尔纹出现在任意角度。只搜偏离水平/垂直轴 ≥15° 的扇区，
# 把 bona fide 最大的混淆源（上传帧的 JPEG 压缩）从搜索空间里剔除——
# 这是实验 c) 相对 b) 的关键修正。
_AXIS_EXCLUDE_DEG = 15.0
# 最低搜索半径（频谱环）：最低频几个环含 Hann 窗泄漏与 DC 残留，不算峰。
_MIN_RADIUS = 6


def spectral_prominence(gray: np.ndarray) -> tuple[float, float]:
    """(非轴向孤立峰显著性 prom, 高频能量占比 hf)。

    输入必须是**原生分辨率**的人脸区域裁剪（检测框内、无缩放）：
    任何插值缩放都会伪造重采样痕迹（实验 a) 的教训）。帧距内人脸 ~90-150px，
    裁剪直接进 FFT，无预处理开销。

    为什么先去低频：自然图像能量集中在低频（1/f 谱），不去掉的话径向剖面的
    中位数被低频背景抬高，孤立峰的显著性会被淹没。
    """
    if gray is None or gray.size == 0:
        return 0.0, 0.0
    img = gray.astype(np.float32)
    hp = img - cv2.GaussianBlur(img, (0, 0), 2.0)

    h, w = hp.shape[:2]
    win = np.outer(np.hanning(h), np.hanning(w)).astype(np.float32)
    spec = np.abs(np.fft.fftshift(np.fft.fft2(hp * win)))

    cy, cx = h // 2, w // 2
    yy, xx = np.mgrid[0:h, 0:w]
    dx = (xx - cx).astype(np.float32)
    dy = (yy - cy).astype(np.float32)
    r = np.sqrt(dx ** 2 + dy ** 2).astype(np.int32)
    ang = np.abs(np.degrees(np.arctan2(dy, dx)))
    off_axis = ((ang >= _AXIS_EXCLUDE_DEG) & (ang <= 180.0 - _AXIS_EXCLUDE_DEG)
                & (np.abs(ang - 90.0) >= _AXIS_EXCLUDE_DEG))

    max_r = min(cy, cx) - 1
    total = float(spec.sum()) + 1e-9
    hf = float(spec[(r >= max_r * 0.5) & off_axis].sum()) / (total + 1e-9)

    prom = 1.0
    for rad in range(_MIN_RADIUS, max_r):
        m = spec[(r == rad) & off_axis]
        if m.size < 10:
            continue
        med = float(np.median(m))
        if med <= 1e-9:
            continue
        prom = max(prom, float(m.max()) / med)
    return prom, hf


def clip_ratio(bgr: np.ndarray) -> float:
    """过曝白斑占比：高亮度（V≥250）且低饱和（S≤30）的像素比例。

    输入用**整帧**而不是人脸裁剪：屏幕自发光不只打在脸上——拍摄手机屏幕时
    边框/留黑区域同样被背光照亮，整帧统计对"录屏重放"更敏感，对真实教室
    场景（灯光下的脸 + 暗背景）保持低位。

    为什么要求低饱和：屏幕发光是"白"的（RGB 同步拉满），彩色灯光反射、
    深色皮肤的正常高光不满足低饱和——把「强光下真人鼻尖反光」的误报压下去。
    """
    if bgr is None or bgr.size == 0:
        return 0.0
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    v = hsv[:, :, 2]
    s = hsv[:, :, 1]
    return float(((v >= 250) & (s <= 30)).mean())
