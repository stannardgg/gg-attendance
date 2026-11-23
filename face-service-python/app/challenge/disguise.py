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

★ 阈值与通道语义：
  口罩：下半脸必盖区肤色占比（_skin_ratio，Cr∈[125,180] + Cb∈[70,135]，
        区间挂接已按 cv2 [Y,Cr,Cb] 顺序修正，见该函数 docstring 的事故记录）。
        修正前的「干净脸 min 0.113 / 织物 90% ≤ 0.10」是低饱和 CASIA 数据与
        装反区间的巧合产物，已作废；修正后阈值 0.10 保留——物理含义重新
        成立：布料占满必盖区 → 区间外像素占比 ≈1 → 得分 ≈0。
        中性灰白织物（Cr≈Cb≈128）是盲区，由匹配门禁兜底。
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
    """YCrCb 肤色占比。区间是文献常用值。

    ★★ 通道语义（2026-11-23 真机误报事故后钉死，勿再改回）：
    cv2.COLOR_BGR2YCrCb 输出顺序是 **[Y, Cr, Cb]**（纯红像素 idx1=255/Cr 饱和、
    纯蓝像素 idx2=255/Cb 饱和，实测钉死）。肤色区间必须挂：
      Cr（idx1）∈ [125, 180]  ← 红色度，真机肤色 ≈156
      Cb（idx2）∈ [70, 135]   ← 蓝色度，真机肤色 ≈105
    原实现把区间挂反了（idx1 套了 Cb 区间、idx2 套了 Cr 区间）：正常肤色
    Cr≈156 落在 [70,135] 之外、Cb≈105 落在 [125,180] 之外 → 占比≈0 →
    所有真机干净脸都被误判为「口罩」（FA-4009）。CASIA 标定没炸是因为
    该数据集人脸低饱和（实测 Cr 126~132 / Cb 122~132，双双贴着 128 徘徊），
    装反的区间恰好放进来一部分——「干净脸 min 0.113」是低饱和数据与双反
    判据的脆弱巧合，真机上必炸（2026-11-23 sid=236 实测占比 0.006~0.015）。

    已知盲区（修正前后同样存在）：中性灰白织物（真实口罩 Cr≈Cb≈128）双区间
    全落内，判据只拦**彩色**织物（蓝绿口罩 Cb>135、红粉织物 Cr>180）。
    白口罩由匹配门禁兜底，此判据不做白色检测。"""
    skin = ((ycc_roi[:, :, 0] > 50) & (ycc_roi[:, :, 1] >= 125)
            & (ycc_roi[:, :, 1] <= 180) & (ycc_roi[:, :, 2] >= 70)
            & (ycc_roi[:, :, 2] <= 135))
    return float(skin.sum() / max(1, skin.size))


def diagnose(aligned_bgr: np.ndarray, eye_rect: list[int],
             mouth_rect: tuple[int, int, int, int]) -> dict | None:
    """误报取证（仅在判据命中时调用）：一次误报日志要能同时回答三类假设。

      ① 光照全局偏移（暗光/色温把肤色挤出 YCrCb 区间）→ meanY/Cb/Cr 与
         skinRelaxed（放宽区间后占比是否回升）；
      ② 局部遮挡/阴影（胡须、下巴阴影只压低必盖区中心）→ centerSkin 与
         sideSkin 分带对比 + foreheadSkin 全脸参照；
      ③ 真遮挡（口罩/围巾占满必盖区）→ strict 与 relaxed 同低。

    与 evaluate 同一套区域闸（region_ok / 尺寸下限），区域不可得返回 None。
    只读统计，不影响判定路径。标定纪律：本函数只采集证据，不改阈值。
    """
    if not (mouth_rect and eye_rect):
        return None
    er_bot = max(eye_rect[3], eye_rect[7])
    if mouth_rect[1] < er_bot - 2:
        return None
    x1, y1, x2, y2 = mask_region(eye_rect, mouth_rect)
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(192, x2), min(192, y2)
    if x2 - x1 < 8 or y2 - y1 < 8:
        return None
    ycc = cv2.cvtColor(aligned_bgr, cv2.COLOR_BGR2YCrCb)
    roi = ycc[y1:y2, x1:x2]
    y_ch, cr, cb = roi[..., 0], roi[..., 1], roi[..., 2]

    def ratio(mask: np.ndarray) -> float:
        return round(float(mask.sum()) / max(1, mask.size), 4)

    # cv2 YCrCb 顺序 = [Y, Cr, Cb]（见 _skin_ratio 的事故记录）；区间同源。
    strict = ((y_ch > 50) & (cr >= 125) & (cr <= 180) & (cb >= 70) & (cb <= 135))
    relaxed = ((y_ch > 35) & (cr >= 115) & (cr <= 190) & (cb >= 60) & (cb <= 145))
    # 分带：嘴框内（中心） vs 两侧脸颊条（必盖区 x 范围减去嘴框）。
    mx1, my1, mx2, my2 = mouth_rect
    mx1, mx2 = max(x1, mx1), min(x2, mx2)
    center = roi[:, mx1 - x1:mx2 - x1]
    left = roi[:, :max(0, mx1 - x1)]
    right = roi[:, max(0, mx2 - x1):]
    def band_skin(seg: np.ndarray) -> float:
        if seg.size == 0:
            return 0.0
        return ratio(((seg[..., 0] > 50) & (seg[..., 1] >= 125) & (seg[..., 1] <= 180)
                      & (seg[..., 2] >= 70) & (seg[..., 2] <= 135)))
    ey_top = int(min(eye_rect[1], eye_rect[5]))
    fore = ycc[int(192 * 0.10):max(int(192 * 0.10) + 4, ey_top),
               int(192 * 0.22):int(192 * 0.78)]
    return {
        "region": [x1, y1, x2, y2],
        "meanY": round(float(y_ch.mean()), 1),
        "meanCr": round(float(cr.mean()), 1),
        "meanCb": round(float(cb.mean()), 1),
        "skinStrict": ratio(strict),
        "skinRelaxed": ratio(relaxed),
        "pctNearBlack": ratio(y_ch < 20),
        "pctYle50": ratio(y_ch <= 50), "pctCblt70": ratio(cb < 70),
        "pctCbgt135": ratio(cb > 135), "pctCrlt125": ratio(cr < 125),
        "pctCrgt180": ratio(cr > 180),
        "centerSkin": band_skin(center),
        "sideSkin": band_skin(left) if right.size == 0 else round(
            (band_skin(left) * left.size + band_skin(right) * right.size)
            / max(1, left.size + right.size), 4),
        "foreheadSkin": band_skin(fore) if fore.size else None,
    }


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
