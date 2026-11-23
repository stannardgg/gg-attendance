"""伪装/遮挡识别（CR-025）：口罩、墨镜、帽子的无模型统计判据。

定位：拦「本人带着伪装物糊弄」——现有门禁只回答"是不是这个人"，
戴口罩时学生拿到的是 FA-4001（检不出）或 FA-4005（不匹配），报错与
原因无关，学生只会反复重试。本模块在 192 对齐空间上判"脸上有没有
遮挡物"，给出明确指令（「请摘下口罩」），并让失败可归因
（rejectGate="disguise"，FA-4009）。

化妆边界：普通化妆（眼影/口红/粉底）**不在拦截范围**——化妆不影响
考勤真实性（识别门禁兜底身份），拦它必然误拒；且无判据能在不误拒的
前提下区分「浓妆」与「深肤色/阴影」。只有整脸涂装/面具这类让活体与
识别同时失效的极端遮挡才属于拦截范围（由口罩分支兜住）。

★ 阈值全部来自 2026-11-22 五轮 CASIA 标定实验（40 身份，JPEG q90 传输
链路），勿凭感觉调。标定结论摘要（详见 docs/change-request/CR-025）：

  口罩：下半脸必盖区肤色占比——干净脸 min 0.113，浅色织物 90% ≤ 0.10；
        嘴区灰度 std——干净脸 min 30.0，均质色块 ≤ 22。棕色织物口罩
        是已知盲区（棕=暗橙=肤色同色度，Cr/Cb 落在肤色区间且织物有
        纹理），由匹配门禁兜底。
  墨镜：眼框 std ≤ 15 且 相对亮度 ≤ 0.50——干净眼框 std ≥ 27.2、
        rel ≥ 0.72；纹理镜面 ≤ 13.7 / ≤ 0.21。「均匀 + 暗」双条件
        缺一不可：闭眼时眼睑 std 也低（代理最高 19.4）但它是皮肤色
        （rel ≥ 0.97），亮度条件把闭眼排除——闭眼动作与墨镜判据
        不冲突。透明眼镜不触发（镜片透明，std/亮度不变）。
  帽子：额头带 std ≤ 16——干净额头 min 36.3，平布代理 ≤ 24.6。
        只拦「布料级平坦」：有纹理的物体（头发、针织帽）大概率逃逸，
        宁可放过不可错拦。齐刘海与布料同分布，是已知盲区（误拒逃生门：
        FA_DISGUISE_HAT=0 单独关闭帽子分支）。

区域定位：坐标在 start 用 106 点关键点锁定（eye_roi 8 数 / 嘴区 roi 4 数，
均已在 ChallengeState），每帧直接在 aligned crop 上复用——与动作 ROI
锁定同构。为什么不在每帧重跑关键点：帧管线不跑 106 点模型（省一次
调用）；且第三轮实验实测「贴遮挡物会让关键点漂移」，漂移的坐标会把
统计区带偏——锁定 start 的干净脸坐标反而更稳。
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from app.config import settings

# 伪装类别码（进 st.occl_kinds / rejectGate 归因日志 / 前端文案映射）。
KIND_TEXT = {
    "mask": "口罩",
    "glasses": "墨镜",
    "hat": "帽子",
}
KIND_ADVICE = {
    "mask": "请摘下口罩",
    "glasses": "请摘下墨镜",
    "hat": "请摘下帽子",
}


@dataclass
class Criterion:
    """单类判据的评估结果。hit=True 表示「检测到该类遮挡」。"""

    kind: str
    value: float | None
    threshold: float
    hit: bool

    def to_dict(self) -> dict:
        return {"kind": self.kind, "value": self.value,
                "threshold": self.threshold, "hit": self.hit}


def mask_region(eye_rect: list[int], mouth_rect: tuple[int, int, int, int]
                ) -> tuple[int, int, int, int]:
    """口罩必盖区：眼下 +1.6 眼高（鼻下）→ 嘴底 + 0.45×(嘴底-眼顶)，x = 嘴宽 ±60%。

    外扩是刻意的：口罩上缘到颧骨、下缘到下巴都算「必须被布料占据」的区域，
    判据只在这个必盖区上数肤色，非必盖区（脸颊两侧）不参与——避免宽脸/瘦脸
    的个体差异把统计区挪到背景上。
    """
    ey_top, ey_bot = min(eye_rect[1], eye_rect[5]), max(eye_rect[3], eye_rect[7])
    eh = max(1, ey_bot - ey_top)
    mx1, my1, mx2, my2 = mouth_rect
    mw = max(1, mx2 - mx1)
    return (int(mx1 - mw * 0.6), int(ey_bot + eh * 1.6),
            int(mx2 + mw * 0.6), int(my2 + (my2 - ey_top) * 0.45))


def _skin_ratio(ycc_roi: np.ndarray) -> float:
    """YCrCb 肤色占比。区间是文献常用值，标定实验里干净下半脸全部落在
    区间内（占比 ≥ 0.113）、织物口罩全部落在区间外——区间本身不敏化，
    判据的区分力来自「必盖区被布料占据」这个物理事实。"""
    skin = ((ycc_roi[:, :, 0] > 50) & (ycc_roi[:, :, 1] >= 70)
            & (ycc_roi[:, :, 1] <= 135) & (ycc_roi[:, :, 2] >= 125)
            & (ycc_roi[:, :, 2] <= 180))
    return float(skin.sum() / max(1, skin.size))


def evaluate(aligned_bgr: np.ndarray, eye_rect: list[int],
             mouth_rect: tuple[int, int, int, int]) -> list[Criterion]:
    """对 192 对齐空间裁剪评估三类伪装判据，返回逐类结果（含未命中的）。

    区域不可得（eye_rect 空 → 墨镜/帽子跳过；mouth_rect 越界 → 口罩跳过）
    时对应判据按「未命中」处理——宁可放过不可错拦，start/frame 都依赖
    基线锁定，锁不上说明连基线统计都不可信，此时交给其他门禁。
    """
    g = cv2.cvtColor(aligned_bgr, cv2.COLOR_BGR2GRAY)
    results: list[Criterion] = []
    d = settings.disguise

    # ---- 口罩：下半脸必盖区肤色占比（单通道）----
    # ★ 原设计有第二个分支「嘴区 std ≤24 = 均质布料」，实测废除：探针实验
    #   的「干净嘴区 std ≥30」是在**错误嘴框**（MOUTH_LM_IDS 混入右眼，
    #   见 landmark.py 修正记录）上测出的假象；正确唇区上**闭唇天然平坦**
    #   （CASIA 实测 23.95 ≤ 24，15/15 身份几何验证），std 通道是误拒机器。
    #   肤色通道不受此影响（它是区域占比，不依赖嘴框内纹理）。
    # 区域合理性闸：嘴框顶必须低于眼框底（2px 容差）。106 点在极端遮挡下会
    # 漂移（实测 CASIA 026 干净脸嘴框漂到眉毛高度 → mask_region 落到眉眼间
    # → skin 边缘误命中）；区域不可信时跳过——宁可放过，由匹配门禁兜底。
    if mouth_rect and eye_rect:
        er_bot = max(eye_rect[3], eye_rect[7])
        region_ok = mouth_rect[1] >= er_bot - 2
        if region_ok:
            x1, y1, x2, y2 = mask_region(eye_rect, mouth_rect)
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(192, x2), min(192, y2)
            if x2 - x1 >= 8 and y2 - y1 >= 8:
                ycc = cv2.cvtColor(aligned_bgr, cv2.COLOR_BGR2YCrCb)
                skin = _skin_ratio(ycc[y1:y2, x1:x2])
                results.append(Criterion(
                    "mask", round(skin, 4), d.mask_skin_max,
                    skin <= d.mask_skin_max))
    if not any(r.kind == "mask" for r in results):
        results.append(Criterion("mask", None, d.mask_skin_max, False))

    # ---- 墨镜：眼框「均匀 + 暗」双条件 ----
    if eye_rect and len(eye_rect) >= 8:
        face_mean = float(g[int(192 * 0.25):int(192 * 0.75),
                            int(192 * 0.25):int(192 * 0.75)].mean())
        stds, rels = [], []
        for i in range(0, 8, 4):
            ex1, ey1, ex2, ey2 = (int(v) for v in eye_rect[i:i + 4])
            patch = g[max(0, ey1):min(192, ey2), max(0, ex1):min(192, ex2)]
            if patch.size:
                stds.append(float(patch.std()))
                rels.append(float(patch.mean()) / max(face_mean, 1e-6))
        if stds:
            eye_std = float(np.mean(stds))
            eye_rel = float(np.mean(rels))
            results.append(Criterion(
                "glasses", round(eye_std, 3), d.eye_std_max,
                eye_std <= d.eye_std_max and eye_rel <= d.eye_rel_max))
    if not any(r.kind == "glasses" for r in results):
        results.append(Criterion("glasses", None, d.eye_std_max, False))

    # ---- 帽子：额头带「布料级平坦」（独立开关）----
    if d.hat_enabled and eye_rect and len(eye_rect) >= 8:
        ey_top = int(min(eye_rect[1], eye_rect[5]))
        band = g[int(192 * 0.10):max(int(192 * 0.10) + 4, ey_top),
                 int(192 * 0.22):int(192 * 0.78)]
        if band.size:
            results.append(Criterion(
                "hat", round(float(band.std()), 3), d.forehead_std_max,
                float(band.std()) <= d.forehead_std_max))
    if not any(r.kind == "hat" for r in results):
        results.append(Criterion("hat", None, d.forehead_std_max, False))

    return results


def hits(criterions: list[Criterion]) -> list[str]:
    """命中的类别码列表（按 KIND_TEXT 顺序，保证文案稳定）。"""
    found = {c.kind for c in criterions if c.hit}
    return [k for k in KIND_TEXT if k in found]


def kind_text(kinds: list[str]) -> str:
    """类别码 → 中文并列（「口罩、帽子」），日志与报错文案用。"""
    texts = [KIND_TEXT[k] for k in kinds if k in KIND_TEXT]
    return "、".join(texts) if texts else "面部遮挡物"


def advice(kinds: list[str]) -> str:
    """摘除指令：单类直接说，多类并列。"""
    texts = [KIND_ADVICE[k] for k in kinds if k in KIND_ADVICE]
    if not texts:
        return "请摘下面部遮挡物"
    return "，".join(texts)
