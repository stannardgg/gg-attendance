"""引擎门面：四个模型的装配点。

之所以要个门面而不是让上层直接 new 四个类：
  1. 模型的加载状态（哪些可用、哪些降级）要集中暴露给 /health；
  2. 上层只需要「检测→特征→活体→ROI」这条链，不应该关心会话怎么开。
"""
from __future__ import annotations

import cv2
import numpy as np

from app.config import settings
from app.engine.detector import Detector, Face
from app.engine.landmark import Embedder, Landmarker
from app.engine.liveness_model import LivenessModel
from app.engine.models import ModelRegistry, ModelUnavailable

_registry: ModelRegistry | None = None
_detector: Detector | None = None
_embedder: Embedder | None = None
_landmarker: Landmarker | None = None
_liveness: LivenessModel | None = None


def load_all() -> dict[str, bool]:
    """加载全部模型。必选模型缺失时抛 ModelUnavailable。

    返回可选模型的状态位，供 /health 展示三模型就绪位。
    """
    global _registry, _detector, _embedder, _landmarker, _liveness
    if _registry is None:
        _registry = ModelRegistry(settings.models)
        _registry.load_required()
        _detector = Detector(_registry, settings.models.det_size,
                             settings.models.det_thresh, settings.models.nms_thresh)
        _embedder = Embedder(_registry)
        _landmarker = Landmarker(_registry, settings.models.landmark_size,
                                 settings.models.landmark_margin)
        _liveness = LivenessModel(_registry, settings.models.liveness_live_index,
                                  settings.models.liveness_input_scale,
                                  settings.models.liveness_crop_scale)
    return _registry.load_optional()


def optional_status() -> dict[str, bool]:
    if _registry is None:
        return {"landmark": False, "liveness": False}
    return {"landmark": _registry.lmd is not None, "liveness": _registry.live is not None}


def detector() -> Detector:
    return _detector


def embedder() -> Embedder:
    return _embedder


def landmarker() -> Landmarker:
    return _landmarker


def liveness() -> LivenessModel:
    return _liveness


def decode_b64_jpeg(data_b64: str) -> np.ndarray:
    """Base64 JPEG → BGR ndarray。失败抛 ValueError（→ 400）。"""
    import base64
    try:
        raw = base64.b64decode(data_b64, validate=True)
    except Exception as exc:
        raise ValueError(f"Base64 解码失败：{exc}") from exc
    arr = np.frombuffer(raw, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("JPEG 解码失败，请检查图像数据")
    return img


def read_jpeg_bytes(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("JPEG 解码失败，请检查图像数据")
    return img


__all__ = [
    "Face", "ModelUnavailable", "load_all", "optional_status",
    "detector", "embedder", "landmarker", "liveness",
    "decode_b64_jpeg", "read_jpeg_bytes",
]
