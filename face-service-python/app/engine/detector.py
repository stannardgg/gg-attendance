"""人脸检测（SCRFD）。

输出约定：bbox / kps 都是**原图像素坐标**。上层（对齐、裁剪、动作 ROI）
全部基于原图坐标工作，所以「检测输入 640」这个实现细节绝不能漏出这层。
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from app.engine.models import (
    DET_NUM_ANCHORS,
    DET_STRIDES,
    ModelRegistry,
    _distance2bbox,
    _distance2kps,
    _nms,
)


@dataclass
class Face:
    """单张人脸的检测结果。

    bbox: [x1, y1, x2, y2]，原图坐标
    kps:  5 个关键点 [[x,y]x5]，顺序为左眼、右眼、鼻尖、左嘴角、右嘴角
    """

    bbox: tuple[float, float, float, float]
    kps: np.ndarray
    score: float

    @property
    def width(self) -> float:
        return self.bbox[2] - self.bbox[0]

    @property
    def height(self) -> float:
        return self.bbox[3] - self.bbox[1]

    @property
    def area(self) -> float:
        return self.width * self.height

    @property
    def short_side(self) -> float:
        return min(self.width, self.height)


class Detector:
    """SCRFD 检测器。'''

    注意 blob 的均值/尺度必须与训练一致：1/128、mean 127.5、BGR→RGB。
    换任何一个都会让检测分数整体偏移，最常见的症状是「检测得到但分数只有 0.3」。
    """

    def __init__(self, registry: ModelRegistry, det_size=640, det_thresh=0.5, nms_thresh=0.4):
        self.registry = registry
        self.det_size = det_size
        self.det_thresh = det_thresh
        self.nms_thresh = nms_thresh

    def detect(self, img_bgr: np.ndarray) -> list[Face]:
        session = self.registry.det
        h, w = img_bgr.shape[:2]
        blob = cv2.dnn.blobFromImage(
            img_bgr, 1.0 / 128.0, (self.det_size, self.det_size),
            (127.5, 127.5, 127.5), swapRB=True,
        )
        net_outs = session.run(None, {session.get_inputs()[0].name: blob})

        fmc = len(DET_STRIDES)
        scores_list, bboxes_list, kpss_list = [], [], []
        ih, iw = blob.shape[2], blob.shape[3]
        for idx, stride in enumerate(DET_STRIDES):
            scores = net_outs[idx].ravel()
            bbox_preds = net_outs[idx + fmc] * stride
            kps_preds = net_outs[idx + fmc * 2] * stride
            fh, fw = ih // stride, iw // stride
            centers = np.stack(np.mgrid[:fh, :fw][::-1], axis=-1).astype(np.float32)
            centers = (centers * stride).reshape((-1, 2))
            if DET_NUM_ANCHORS > 1:
                centers = np.stack([centers] * DET_NUM_ANCHORS, axis=1).reshape((-1, 2))
            bboxes = _distance2bbox(centers, bbox_preds)
            kpss = _distance2kps(centers, kps_preds)
            pos = np.where(scores >= self.det_thresh)[0]
            scores_list.append(scores[pos])
            bboxes_list.append(bboxes[pos])
            kpss_list.append(kpss[pos])

        if not any(s.size for s in scores_list):
            return []

        scores = np.concatenate(scores_list)
        bboxes = np.concatenate(bboxes_list)
        kpss = np.concatenate(kpss_list)
        keep = _nms(bboxes, scores, self.nms_thresh)
        bboxes, scores, kpss = bboxes[keep], scores[keep], kpss[keep]

        # 640 空间 → 原图坐标。这个换算漏掉的话，所有下游坐标都会错
        sx, sy = w / self.det_size, h / self.det_size
        bboxes[:, [0, 2]] *= sx
        bboxes[:, [1, 3]] *= sy
        kpss[:, :, 0] *= sx
        kpss[:, :, 1] *= sy
        bboxes[:, [0, 2]] = np.clip(bboxes[:, [0, 2]], 0, w)
        bboxes[:, [1, 3]] = np.clip(bboxes[:, [1, 3]], 0, h)

        return [
            Face(
                bbox=(float(bboxes[i][0]), float(bboxes[i][1]),
                      float(bboxes[i][2]), float(bboxes[i][3])),
                kps=kpss[i],
                score=float(scores[i]),
            )
            for i in range(len(scores))
        ]

    def detect_largest(self, img_bgr: np.ndarray) -> Face | None:
        """取面积最大的人脸。签到场景「站最近的人」就是目标，
        不做任何关于身份的假设——那是比对层的事。"""
        faces = self.detect(img_bgr)
        if not faces:
            return None
        return max(faces, key=lambda f: f.area)
