"""模型加载与会话管理。四个 ONNX 全部懒加载、CPU 推理。

为什么自己封装而不直接用 insightface 包：
  1. 包会拖进 Cython/onnxruntime 固定版本等一串依赖，Python 3.13 下编译风险高；
  2. 我们需要精确控制预处理（尤其活体模型的 255 倍标定偏移），第三方封装反而要反着改；
  3. 这套实现参考 InsightFace 官方 SCRFD / ArcFace 推理流程，行为一致、依赖更少。
"""
from __future__ import annotations

import os

import numpy as np
import onnxruntime as ort

from app.config import ModelConfig


class ModelUnavailable(RuntimeError):
    """模型文件缺失或损坏。

    以前直接抛 FileNotFoundError 会被 FastAPI 包成 500，运维只看到
    「Internal Server Error」却查不到是哪个模型没放——所以这里带上文件名与处置建议。
    """


# ArcFace 112x112 的 5 点标准模板（左眼、右眼、鼻尖、左嘴角、右嘴角）。
# 这是 InsightFace 官方对齐模板，所有训练样本都按它对齐——换掉它识别精度会掉一个档次。
ARC_SRC = np.array(
    [[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
     [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)

DET_STRIDES = (8, 16, 32)
DET_NUM_ANCHORS = 2


def _distance2bbox(points: np.ndarray, distance: np.ndarray) -> np.ndarray:
    """SCRFD 框解码：anchor 中心 + 偏移 → [x1,y1,x2,y2]。"""
    x1 = points[:, 0] - distance[:, 0]
    y1 = points[:, 1] - distance[:, 1]
    x2 = points[:, 0] + distance[:, 2]
    y2 = points[:, 1] + distance[:, 3]
    return np.stack([x1, y1, x2, y2], axis=-1)


def _distance2kps(points: np.ndarray, distance: np.ndarray) -> np.ndarray:
    """SCRFD 关键点解码 → [N,5,2]。"""
    preds = []
    for i in range(0, distance.shape[1], 2):
        px = points[:, i % 2] + distance[:, i]
        py = points[:, i % 2 + 1] + distance[:, i + 1]
        preds.append(px)
        preds.append(py)
    return np.stack(preds, axis=-1).reshape((-1, 5, 2))


def _nms(boxes: np.ndarray, scores: np.ndarray, thresh: float) -> list[int]:
    """标准 NMS。手写而不是用 cv2.dnn，是为了与 InsightFace 行为完全一致。"""
    if len(boxes) == 0:
        return []
    x1, y1 = boxes[:, 0], boxes[:, 1]
    x2, y2 = boxes[:, 2], boxes[:, 3]
    areas = (x2 - x1 + 1) * (y2 - y1 + 1)
    order = scores.argsort()[::-1]
    keep: list[int] = []
    while order.size > 0:
        i = order[0]
        keep.append(int(i))
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        w = np.maximum(0.0, xx2 - xx1 + 1)
        h = np.maximum(0.0, yy2 - yy1 + 1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter)
        order = order[np.where(ovr <= thresh)[0] + 1]
    return keep


class ModelRegistry:
    """四个模型的容器。探测/识别必须的模型缺失会拒绝启动，其余可选。"""

    def __init__(self, cfg: ModelConfig):
        self.cfg = cfg
        self._det = None
        self._rec = None
        self._lmd = None
        self._live = None

    def _path(self, name: str) -> str:
        return os.path.join(self.cfg.model_dir, name)

    def _open(self, path: str) -> ort.InferenceSession:
        try:
            return ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        except Exception as exc:
            raise ModelUnavailable(
                f"模型 {os.path.basename(path)} 无法加载：{exc}"
            ) from exc

    def load_required(self) -> None:
        """检测与识别是硬依赖，缺失时抛 ModelUnavailable（→ /health 报 DEGRADED）。"""
        for name in (self.cfg.det, self.cfg.rec):
            if not os.path.exists(self._path(name)):
                raise ModelUnavailable(
                    f"缺少模型 {name}，请放入 {self.cfg.model_dir}"
                )
        self._det = self._open(self._path(self.cfg.det))
        self._rec = self._open(self._path(self.cfg.rec))

    def load_optional(self) -> dict[str, bool]:
        """关键点与活体按需加载。缺了不阻塞，但动作校验/活体判定会明确降级。"""
        status = {}
        if self._lmd is None and os.path.exists(self._path(self.cfg.landmark)):
            self._lmd = self._open(self._path(self.cfg.landmark))
        status["landmark"] = self._lmd is not None
        if self._live is None and os.path.exists(self._path(self.cfg.liveness)):
            self._live = self._open(self._path(self.cfg.liveness))
        status["liveness"] = self._live is not None
        return status

    @property
    def det(self) -> ort.InferenceSession:
        return self._det

    @property
    def rec(self) -> ort.InferenceSession:
        return self._rec

    @property
    def lmd(self) -> ort.InferenceSession | None:
        return self._lmd

    @property
    def live(self) -> ort.InferenceSession | None:
        return self._live
