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
MOUTH_LM_IDS = list(range(68, 105))


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

    def align_space(self, img_bgr: np.ndarray, bbox) -> np.ndarray:
        """把人脸按框中心裁剪/缩放到 192x192。

        只依赖 bbox，不跑模型，开销 ~0.1ms。同一张脸在不同帧里，
        这块空间的坐标天然可复用——固定 ROI 的基础。
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
        return cv2.warpAffine(img_bgr, m, (self.size, self.size), borderValue=0.0)

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
