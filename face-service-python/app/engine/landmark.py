"""人脸特征（ArcFace）与关键点（2d106det）。

两个模型放在一个文件里，因为它们共享「对齐人脸空间」这个概念：
  - ArcFace 的 112x112 对齐用于提特征；
  - 2d106det 的 192x192 对齐空间用于动作 ROI。

两个空间都「与离镜头远近无关」，这是动作校验能用固定 ROI 的前提——
换一种对齐方式（比如直接按检测框裁剪），同一块嘴部区域的坐标就会随人脸缩放，
固定 ROI 就失效了。
"""
from __future__ import annotations

import cv2
import numpy as np

from app.engine.models import ARC_SRC, ModelRegistry

# 106 点里与嘴部相关的下标（InsightFace 2d106det 的固定编号）。
# 68~96 是嘴唇外轮廓、97~104 是嘴唇内轮廓。刻意只取这些点而不是整个轮廓：
# 动作判定只关心嘴的开合，多余的点只会增加误判面。
# 106 点里的嘴部（唇+唇周）：CR-025 实测修正。旧值 68~104 混入了右眼眼球
# （87~96）与眉毛（97~104），嘴框顶部因此漂到眉毛高度（15/15 身份复现），
# 嘴区暗度统计被污染——MOUTH_* 动作停用（CR-018）后该 rect 只剩
# baseline_ratio 兜底与 disguise 口罩判据（CR-025）两个消费者，错误长期
# 未暴露。几何实测（CASIA 15 身份）：52~61 y≈128~140、62~70 y≈123~129，
# 两段并集的 bbox 全部低于眼框底（15/15），才是真正的嘴部。
MOUTH_LM_IDS = list(range(52, 71))

# 106 点里的两只眼框（InsightFace 2d106det 的固定编号，本机 20 个身份实测反推）：
# 33~42 是图像左眼的眼球轮廓，87~96 是右眼。中间 43~51 / 97~105 是眉毛
# （y 比眼位高 10~20px），52~86 是鼻梁与鼻翼。
EYE_LM_LEFT = list(range(33, 43))
EYE_LM_RIGHT = list(range(87, 97))


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    a = np.asarray(a, dtype=np.float32).ravel()
    b = np.asarray(b, dtype=np.float32).ravel()
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if a.size == 0 or b.size == 0 or denom == 0:
        return -1.0
    return float(np.dot(a, b) / denom)


class Embedder:
    """ArcFace 512 维特征。输出已 L2 归一化，可直接用余弦/点积比对。"""

    def __init__(self, registry: ModelRegistry):
        self.registry = registry

    def align_112(self, img_bgr: np.ndarray, kps: np.ndarray) -> np.ndarray:
        """按 5 点模板对齐到 112x112。"""
        kps = np.asarray(kps, dtype=np.float32).reshape(5, 2)
        m, _ = cv2.estimateAffinePartial2D(kps, ARC_SRC)
        if m is None:
            raise ValueError("人脸对齐失败：关键点无效")
        return cv2.warpAffine(img_bgr, m, (112, 112), borderValue=0.0)

    def embedding(self, img_bgr: np.ndarray, kps: np.ndarray) -> np.ndarray:
        session = self.registry.rec
        aimg = self.align_112(img_bgr, kps)
        blob = cv2.dnn.blobFromImage(
            aimg, 1.0 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True)
        emb = session.run(None, {session.get_inputs()[0].name: blob})[0].ravel()
        norm = float(np.linalg.norm(emb))
        if norm == 0:
            return emb.astype(np.float32)
        return (emb / norm).astype(np.float32)


class Landmarker:
    """106 点关键点，输出 192x192 对齐人脸空间的坐标。"""

    def __init__(self, registry: ModelRegistry, size: int = 192, margin: float = 1.5):
        self.registry = registry
        self.size = size
        self.margin = margin

    def align_matrix(self, bbox) -> np.ndarray:
        """bbox → 192 对齐空间的仿射矩阵（只含平移 + 缩放）。

        单独暴露是因为眼框要在这块空间里定位（CR-021），而 `align_space`
        只返回图像、不返回矩阵。
        """
        x1, y1, x2, y2 = [float(v) for v in bbox]
        w, h = x2 - x1, y2 - y1
        if w <= 1 or h <= 1:
            raise ValueError("人脸框无效")
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        scale = self.size / (max(w, h) * self.margin)
        m = cv2.getRotationMatrix2D((cx, cy), 0, scale)
        m[0, 2] += self.size / 2 - cx
        m[1, 2] += self.size / 2 - cy
        return m

    def align_space(self, img_bgr: np.ndarray, bbox) -> np.ndarray:
        """把人脸按框中心裁剪/缩放到 192x192。

        只依赖 bbox，不跑模型，开销 ~0.1ms。同一张脸在不同帧里，
        这块空间的坐标天然可复用——固定 ROI 的基础。
        """
        return cv2.warpAffine(img_bgr, self.align_matrix(bbox), (self.size, self.size),
                              borderValue=0.0)

    def eye_rects(self, pts: np.ndarray, pad: int = 2) -> list[int]:
        """两只眼框（192 空间，扁平 8 个整数：x1,y1,x2,y2 各一只）。

        用 106 点里的**眼球轮廓**（`EYE_LM_LEFT` / `EYE_LM_RIGHT`）定位，与
        `mouth_rect` 同一套做法。为什么不用 5 点眼位配固定倍率框：5 点在头部
        侧倾时的偏差会把框带偏（实测 80 个身份里有 4 例框到了眉毛上，闭眼降幅
        直接归零；换成轮廓之后 78/80 达标）。

        关键点在这里只做**定位**，不做判定——判定仍用框内暗度（§4.4.1 全局约束）。
        """
        rects: list[int] = []
        for ids in (EYE_LM_LEFT, EYE_LM_RIGHT):
            q = pts[ids]
            x1 = max(0, int(np.floor(q[:, 0].min())) - pad)
            y1 = max(0, int(np.floor(q[:, 1].min())) - pad)
            x2 = min(self.size, int(np.ceil(q[:, 0].max())) + pad)
            y2 = min(self.size, int(np.ceil(q[:, 1].max())) + pad)
            if x2 - x1 < 4 or y2 - y1 < 4:
                raise ValueError("眼框过小，无法测量")
            rects.extend([x1, y1, x2, y2])
        return rects

    def eye_rects_from_kps(self, kps: np.ndarray, bbox, w_ratio: float = 0.52,
                           h_ratio: float = 0.34) -> list[int]:
        """5 点兜底版眼框：关键点模型不可用（或首帧锁不上）时才用。

        精度略差（侧倾时框会偏），但只依赖检测头的 5 点，能在关键点模型挂掉时
        仍然产出「闭眼」这个动作。尺寸按瞳距定，中心取 5 点眼位。
        """
        m = self.align_matrix(bbox)
        pts = cv2.transform(np.asarray(kps, dtype=np.float64).reshape(-1, 1, 2), m)
        pts = pts.reshape(-1, 2)
        iod = float(np.linalg.norm(pts[1] - pts[0]))
        if iod <= 1e-6:
            raise ValueError("瞳距无效，无法定位眼框")
        w, h = w_ratio * iod, h_ratio * iod
        rects: list[int] = []
        for cx, cy in (pts[0], pts[1]):
            x1 = int(round(max(0.0, cx - w / 2)))
            y1 = int(round(max(0.0, cy - h / 2)))
            x2 = int(round(min(float(self.size), cx + w / 2)))
            y2 = int(round(min(float(self.size), cy + h / 2)))
            if x2 - x1 < 4 or y2 - y1 < 4:
                raise ValueError("眼框过小，无法测量")
            rects.extend([x1, y1, x2, y2])
        return rects

    def landmarks(self, img_bgr: np.ndarray, bbox) -> tuple[np.ndarray, np.ndarray]:
        """返回 (points[106,2], crop)。

        坐标是 192 空间像素坐标，不是原图坐标——上层拼 ROI 时务必注意。
        """
        session = self.registry.lmd
        if session is None:
            raise RuntimeError("缺少关键点模型 2d106det.onnx")
        crop = self.align_space(img_bgr, bbox)
        blob = crop.astype(np.float32).transpose(2, 0, 1)[np.newaxis, ...]
        out = session.run(None, {session.get_inputs()[0].name: blob})[0]
        pts = (np.asarray(out, dtype=np.float32).reshape(-1, 2) + 1.0) * (self.size // 2)
        return pts, crop

    def mouth_rect(self, pts: np.ndarray) -> tuple[int, int, int, int]:
        """由关键点算嘴部 ROI 矩形（192 空间坐标），供挑战服务锁定后跨帧复用。"""
        mouth = pts[MOUTH_LM_IDS]
        x1 = int(np.floor(mouth[:, 0].min())) - 2
        y1 = int(np.floor(mouth[:, 1].min())) - 2
        x2 = int(np.ceil(mouth[:, 0].max())) + 2
        y2 = int(np.ceil(mouth[:, 1].max())) + 2
        x1, y1 = max(x1, 0), max(y1, 0)
        x2, y2 = min(x2, self.size), min(y2, self.size)
        if x2 - x1 < 4 or y2 - y1 < 4:
            raise ValueError("嘴部 ROI 过小，无法测量")
        return x1, y1, x2, y2

    def mouth_roi(self, img_bgr: np.ndarray, bbox) -> tuple[np.ndarray, np.ndarray]:
        """从关键点定位嘴部 ROI，返回 (roi 灰度图, 192 空间 crop)。

        ROI 取嘴唇外轮廓的凸外接矩形，四周各留 2 像素。
        刻意不把 ROI 做成正方形或居中——嘴在脸的下半部分，外接矩形更贴合，
        周围的鼻子/下巴像素越少，暗度统计越纯粹。
        """
        pts, crop = self.landmarks(img_bgr, bbox)
        mouth = pts[MOUTH_LM_IDS]
        x1 = int(np.floor(mouth[:, 0].min())) - 2
        y1 = int(np.floor(mouth[:, 1].min())) - 2
        x2 = int(np.ceil(mouth[:, 0].max())) + 2
        y2 = int(np.ceil(mouth[:, 1].max())) + 2
        x1, y1 = max(x1, 0), max(y1, 0)
        x2, y2 = min(x2, self.size), min(y2, self.size)
        if x2 - x1 < 4 or y2 - y1 < 4:
            raise ValueError("嘴部 ROI 过小，无法测量")
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        return gray[y1:y2, x1:x2], crop
