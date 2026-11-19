"""静默活体（MiniFASNetV2）。

★ 三个容易翻车的点，都已踩过并在常量处注明：
  1. 输入必须喂 [0,255] 原始像素——这套 ONNX 导出有 255 倍标定偏移，
     按 ToTensor 喂 [0,1] 会输出与输入无关的常数（真人帧与噪声同结果）；
  2. 「真人」在 3 类输出的索引 1（[打印, 真人, 翻拍]），取错索引会把真人全判假；
  3. 裁剪放大倍数 2.7 是训练时固定参数，改了分布就变了。
换模型时这三项必须重新核对——对应 sys_config 里 LIVENESS_* 三个 editable=0 的键。
"""
from __future__ import annotations

import cv2
import numpy as np

from app.engine.models import ModelRegistry


class LivenessModel:
    def __init__(self, registry: ModelRegistry, live_index=1, input_scale=255.0, crop_scale=2.7):
        self.registry = registry
        self.live_index = live_index
        self.input_scale = input_scale
        self.crop_scale = crop_scale

    def live_probability(self, img_bgr: np.ndarray, bbox) -> float | None:
        """返回真人概率（0~1）。模型不可用返回 None；框非法返回 0.0（不可信）。"""
        session = self.registry.live
        if session is None:
            return None

        x1, y1, x2, y2 = [float(v) for v in bbox]
        h, w = img_bgr.shape[:2]
        box_w, box_h = x2 - x1, y2 - y1
        if box_w <= 4 or box_h <= 4:
            return 0.0

        # 2.7 倍放大裁剪（受图像边界限制）
        scale = min((w - 1) / box_w, (h - 1) / box_h, self.crop_scale)
        new_w, new_h = box_w * scale, box_h * scale
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        nx1 = max(0, int(cx - new_w / 2))
        ny1 = max(0, int(cy - new_h / 2))
        nx2 = min(w, int(cx + new_w / 2))
        ny2 = min(h, int(cy + new_h / 2))
        if nx2 - nx1 < 8 or ny2 - ny1 < 8:
            return 0.0

        crop = cv2.resize(img_bgr[ny1:ny2, nx1:nx2], (80, 80))
        norm = crop.astype(np.float32) / 255.0 * self.input_scale
        blob = norm.transpose(2, 0, 1)[np.newaxis, ...]
        logits = session.run(None, {session.get_inputs()[0].name: blob})[0][0]
        # softmax
        e = np.exp(logits - logits.max())
        probs = e / e.sum()
        return float(probs[self.live_index])
