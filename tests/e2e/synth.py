"""合成帧工厂：用 CASIA 静图造出「签到画面」。

为什么不放真人视频：16.2 明确要求 E2E 不依赖真人。这里的每个函数对应
16.3 里的一个负例/正例，参数都是在本机实测过的（见 docs/handover/B6-验收报告.md）。

⚠️ 天花板：CASIA 是 112×112 的对齐人脸，放大到画面尺寸后活体模型打分仅 0.02~0.10，
必然过不了 `liveness` 门禁。所以「必须通过」的场景在验收栈里把
`FA_LIVENESS_THRESHOLD` 降到 0 跑（见 run.sh 的两阶段），
**活体识别能力不以本套件为证据**——那是 WP-06 攻击测试报告的职责。
"""
from __future__ import annotations

import base64
import os

import cv2
import numpy as np

DATASET = os.getenv("E2E_DATASET", "/dataset")
FRAME_SIZE = (720, 1280)
# 背景灰度与传感器噪声：采集质检的清晰度指标（Laplacian 方差）在"纯色背景的大图"
# 上必然很低 —— 整幅图 97% 是平坦区域，再清晰的人脸也拉不起来（实测 lap≈2）。
# 真机画面永远有墙纹、噪点，所以这里补一层 σ=4 的高斯噪声 + 非纯黑背景，
# 让样本过的是"清晰度门禁"而不是"纯色背景门禁"。实测 JPEG q90 后 lap≈224（阈值 80）。
BG_LEVEL = 90
_rng = np.random.default_rng(20261119)


def blank(size: tuple[int, int] = FRAME_SIZE) -> np.ndarray:
    """一块"教室墙面"：中灰底。"""
    return np.full((size[0], size[1], 3), BG_LEVEL, dtype=np.uint8)


def sensor_noise(frame: np.ndarray, sigma: float = 6.0) -> np.ndarray:
    """加一层相机噪点。

    ★ 只用于**采集**（enroll/sample 的清晰度门禁），不用于签到帧：
      - 采集门禁算的是整幅图的 Laplacian 方差，纯色背景的大图天然是"模糊"的
        （实测 lap≈2，阈值 80），加 σ=6 噪点后 JPEG q90 仍有 lap≈224；
      - 但是同一层噪点会把活体模型骗到 0.95（MiniFASNet 把传感器噪点当"真机"），
        场景 07「翻拍照片必须被拒」就永远不成立。
    真机上这是两件不同的事：采集用的是"拿稳手机"的高质量帧，签到用的是实时流。
    """
    return np.clip(frame.astype(np.float32) + _rng.normal(0, sigma, frame.shape),
                   0, 255).astype(np.uint8)


def enroll_frame(img112: np.ndarray) -> np.ndarray:
    """采集样本帧：合成画面 + 相机噪点（过清晰度门禁）。"""
    return sensor_noise(composite(img112))


def _engine():
    """延迟加载模型（首次调用约 2~4 秒）。"""
    from app import engine
    engine.load_all()
    return engine


def person_images(person: str) -> list[np.ndarray]:
    """读取某人的 5 张清晰样本（000_0..000_4）。"""
    imgs = []
    for i in range(5):
        p = os.path.join(DATASET, person, f"{person}_{i}.jpg")
        img = cv2.imread(p)
        if img is None:
            raise FileNotFoundError(p)
        imgs.append(img)
    return imgs


def composite(img112: np.ndarray, size: tuple[int, int] = FRAME_SIZE,
              side: int = 320) -> np.ndarray:
    """把人脸居中放到 720×1280 的灰底画面上，短边放大到 `side` px。

    `side` 用来造尺度类动作（CR-016/CR-018）：同一张脸按不同 side 重贴，
    检测框会随真实尺寸一起变，正是「靠近 / 离远」判据要的信号。
    """
    frame = blank(size)
    scale = side / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)),
                      interpolation=cv2.INTER_CUBIC)
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2, (size[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def largest_face(frame: np.ndarray):
    """返回 (bbox, 全部检测结果)。"""
    eng = _engine()
    faces = eng.detector().detect(frame)
    if not faces:
        return None, faces
    return max(faces, key=lambda f: f.area), faces


def largest_bbox(frame: np.ndarray):
    """最大人脸的 bbox 数组（landmarker 需要的是数组，不是 Face 对象）。"""
    face, _ = largest_face(frame)
    return None if face is None else face.bbox


def mouth_polygon(frame: np.ndarray, bbox) -> np.ndarray:
    """把 192 对齐空间的嘴部矩形反算回图像坐标（四角点）。

    与 probe_action.py 的 roi_polygon_in_image 同一套矩阵运算：
    每帧判定用的是 align_space 后的 ROI，所以合成时也必须按同一条变换回去。
    """
    eng = _engine()
    lm = eng.landmarker()
    pts, _ = lm.landmarks(frame, bbox)
    rect = lm.mouth_rect(pts)
    m = cv2.getRotationMatrix2D(
        ((bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2), 0,
        lm.size / (max(bbox[2] - bbox[0], bbox[3] - bbox[1]) * lm.margin))
    m[0, 2] += lm.size / 2 - (bbox[0] + bbox[2]) / 2
    m[1, 2] += lm.size / 2 - (bbox[1] + bbox[3]) / 2
    inv = cv2.invertAffineTransform(m)
    corners = np.array([[rect[0], rect[1]], [rect[2], rect[1]],
                        [rect[2], rect[3]], [rect[0], rect[3]]], dtype=np.float32)
    ones = np.hstack([corners, np.ones((4, 1), dtype=np.float32)])
    mapped = ones @ inv.T
    return mapped.astype(np.float32)


def mouth_ratio(frame: np.ndarray, bbox) -> float:
    """当前画面在服务端口径下的嘴区暗度比（用于断言合成有效）。"""
    from app.challenge.action import mouth_dark_ratio
    eng = _engine()
    lm = eng.landmarker()
    pts, crop = lm.landmarks(frame, bbox)
    rect = lm.mouth_rect(pts)
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    return mouth_dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])


def open_mouth(frame: np.ndarray, bbox) -> np.ndarray:
    """把嘴区涂成暗色椭圆，模拟「张嘴」。

    实测参数（person=000）：基线 0.236 → 0.391，涨幅 +0.155、倍数 1.66，
    同时满足 abs_rise(0.05) 与 rise_ratio(1.45)；再小就过不了门禁，再大就不像嘴。
    """
    poly = mouth_polygon(frame, bbox)
    cx, cy = float(poly[:, 0].mean()), float(poly[:, 1].mean())
    w = float(poly[:, 0].max() - poly[:, 0].min())
    h = float(poly[:, 1].max() - poly[:, 1].min())
    out = frame.copy()
    cv2.ellipse(out, (int(cx), int(cy)), (int(w * 0.8 / 2), int(h * 0.5 / 2)),
                0, 0, 360, (10, 10, 10), -1)
    return out


def eye_rects_image(frame: np.ndarray, face):
    """把 192 空间的眼框反算回图像坐标，返回 ([(x1,y1,x2,y2)...], 192 空间的 rois)。

    与 mouth_polygon 同一套逆变换：服务端每帧都在 192 对齐空间里取眼框，
    合成闭眼时必须涂在**同一块**区域上，否则测的就不是判据本身。
    眼框用 106 点的眼球轮廓（与服务端一致），所以这里要跑一次关键点。
    """
    lm = _engine().landmarker()
    pts, _ = lm.landmarks(frame, face.bbox)
    rois = lm.eye_rects(pts)
    inv = cv2.invertAffineTransform(lm.align_matrix(face.bbox))
    boxes = []
    for i in range(0, len(rois), 4):
        x1, y1, x2, y2 = rois[i:i + 4]
        corners = np.array([[x1, y1], [x2, y1], [x2, y2], [x1, y2]], dtype=np.float64)
        mapped = np.hstack([corners, np.ones((4, 1))]) @ inv.T
        boxes.append((int(mapped[:, 0].min()), int(mapped[:, 1].min()),
                      int(mapped[:, 0].max()), int(mapped[:, 1].max())))
    return boxes, rois


def eye_ratio(frame: np.ndarray, face) -> float:
    """当前画面在服务端口径下的眼框暗度（用于断言合成有效）。"""
    from app.challenge.action import eye_dark_ratio
    lm = _engine().landmarker()
    pts, crop = lm.landmarks(frame, face.bbox)
    rois = lm.eye_rects(pts)
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    return eye_dark_ratio(gray, rois)


def close_eyes(frame: np.ndarray) -> np.ndarray:
    """闭眼：眼框填成周围肤色，再压一条 2px 睫毛线。

    为什么不直接涂黑：闭眼是**眼皮盖住虹膜**，暗像素减少而不是增多；
    涂黑会得到相反方向的信号（v1.x 的 block_eyes 素材就是黑条，只能用于打码测试）。
    睫毛线保留是因为真实闭眼仍有一条暗的睑缘，不留会把降幅估得过于乐观。
    """
    face, _ = largest_face(frame)
    if face is None:
        raise RuntimeError("合成闭眼前未检出人脸")
    boxes, _ = eye_rects_image(frame, face)
    out = frame.copy()
    # 肤色取整张脸的**中位色**，不取眼下的"脸颊小块"：后者在脸小/头偏时会落到
    # 鼻孔或嘴唇上，填出一块深色，把闭眼信号做成反向（实测 010 就是这样）。
    fx1, fy1, fx2, fy2 = [int(v) for v in face.bbox]
    fw, fh = max(1, fx2 - fx1), max(1, fy2 - fy1)
    face_patch = out[fy1 + fh // 10:fy2 - fh // 10, fx1 + fw // 10:fx2 - fw // 10]
    skin = (np.median(face_patch.reshape(-1, 3), axis=0) if face_patch.size
            else np.array([170, 170, 170], dtype=np.float64))
    for (x1, y1, x2, y2) in boxes:
        out[y1:y2, x1:x2] = skin.astype(np.uint8)
        cy = (y1 + y2) // 2
        out[max(cy - 1, 0):cy + 1, x1:x2] = (skin * 0.30).astype(np.uint8)
    return out


def two_faces(frame_a: np.ndarray, frame_b: np.ndarray) -> np.ndarray:
    """A、B 两人同框（各占半幅，都远超 90px）。"""
    h, w = FRAME_SIZE
    out = blank((h, w))
    for frame, x0 in ((frame_a, 0), (frame_b, w // 2)):
        face, _ = largest_face(frame)
        x1, y1, x2, y2 = [int(v) for v in face.bbox]
        crop = frame[y1:y2, x1:x2]
        target = min(w // 2 - 20, h // 2)
        crop = cv2.resize(crop, (target, target), interpolation=cv2.INTER_CUBIC)
        cx = x0 + (w // 2 - target) // 2
        cy = (h - target) // 2
        out[cy:cy + target, cx:cx + target] = crop
    return out


def with_background_face(frame: np.ndarray, frame_b: np.ndarray, px: int = 40) -> np.ndarray:
    """主脸之外，背景墙上贴一张 px 像素的小脸（用来验证尺寸下限不误拦）。"""
    out = frame.copy()
    face, _ = largest_face(frame_b)
    x1, y1, x2, y2 = [int(v) for v in face.bbox]
    crop = cv2.resize(frame_b[y1:y2, x1:x2], (px, px), interpolation=cv2.INTER_AREA)
    out[40:40 + px, 40:40 + px] = crop
    return out


def jpeg(frame: np.ndarray, quality: int = 90) -> bytes:
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
    if not ok:
        raise RuntimeError("JPEG 编码失败")
    return buf.tobytes()


def b64(frame: np.ndarray) -> str:
    return base64.b64encode(jpeg(frame)).decode()


def describe(frame: np.ndarray) -> str:
    """给出「这张画面里检出几张脸、各多宽」，写进报告当证据。"""
    eng = _engine()
    faces = eng.detector().detect(frame)
    if not faces:
        return "检出 0 张脸"
    return "检出 %d 张脸，宽度=%s" % (
        len(faces), [int(round(f.width)) for f in sorted(faces, key=lambda f: -f.area)])
