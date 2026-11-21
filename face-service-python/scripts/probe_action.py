"""动作判定探针（WP-06 交付物）。

三种模式：

1. `--video <文件>`：真实视频。抽帧 4fps → 检测 → ROI → 计数，输出检出次数与分段。
   **Gate 的真人指标靠它**——需要现场录制的「张嘴一次 / 两次」视频，
   目前素材库里没有，所以 B3 交付时这条是"逻辑已验证、真人指标待补"。

2. `--synthetic`：自检模式。用 CASIA 人脸合成视频，
   把嘴部 ROI 区域按时间表涂暗来模拟张嘴，跑完整链路后核对计数。
   它验证的是**脚本与计数链路的正确性**，不证明真人准确率——两者不要混淆。

3. `--scale`：尺度类动作（靠近 / 离远）的分离度实测。用同一张脸按不同尺度
   重新合成，量两件事：
     (a) 检测框尺度是否**线性跟随**真实缩放（判据成立的前提）；
     (b) 「静止 + 抖动 + JPEG 压缩」下的尺度波动标准差。
   输出 `delta / std` 即分离度，§4.4.1 要求 ≥3。注意这仍是合成素材：
   它证明的是指标本身的保真度，真人动作的准确率要靠现场录制视频复核。

输出：每段视频的 expected vs detected，以及总体准确率。
"""
from __future__ import annotations

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.action import (  # noqa: E402
    MouthActionCounter, ScaleActionCounter, face_scale, mouth_dark_ratio,
)
from app.config import settings  # noqa: E402
from app.engine import detector, landmarker, load_all                  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))
FPS = 4.0


def composite(img112: np.ndarray, size=(720, 1280)) -> np.ndarray:
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = 320 / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)))
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2, (size[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def roi_polygon_in_image(frame: np.ndarray, bbox) -> np.ndarray:
    """把 192 空间的嘴部 ROI 矩形反算回原图坐标（四角点）。

    合成"张嘴"时需要涂暗原图上的嘴部区域，所以必须知道这块区域在原图里在哪。
    """
    pts, crop = landmarker().landmarks(frame, bbox)
    rect = landmarker().mouth_rect(pts)
    m = cv2.getRotationMatrix2D(
        ((bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2), 0,
        landmarker().size / (max(bbox[2] - bbox[0], bbox[3] - bbox[1]) * landmarker().margin))
    m[0, 2] += landmarker().size / 2 - (bbox[0] + bbox[2]) / 2
    m[1, 2] += landmarker().size / 2 - (bbox[1] + bbox[3]) / 2
    inv = cv2.invertAffineTransform(m)
    corners_192 = np.array([[rect[0], rect[1]], [rect[2], rect[1]],
                            [rect[2], rect[3]], [rect[0], rect[3]]], dtype=np.float32)
    ones = np.hstack([corners_192, np.ones((4, 1), dtype=np.float32)])
    return (ones @ inv.T).astype(np.int32)


def darken_mouth(frame: np.ndarray, poly: np.ndarray, strength=120) -> np.ndarray:
    """把嘴部区域压暗，模拟张嘴时口腔阴影扩大。"""
    out = frame.copy()
    mask = np.zeros(out.shape[:2], dtype=np.uint8)
    cv2.fillPoly(mask, [poly], 255)
    dark = (out.astype(np.int16) - strength).clip(0, 255).astype(np.uint8)
    out[mask > 0] = dark[mask > 0]
    return out


def measure_ratios(frames: list[np.ndarray]) -> tuple[list[float], float | None]:
    """逐帧算嘴部暗度。返回 (ratios, baseline)。"""
    ratios: list[float] = []
    baseline: float | None = None
    for fr in frames:
        face = detector().detect_largest(fr)
        if face is None:
            ratios.append(0.0)
            continue
        pts, crop = landmarker().landmarks(fr, face.bbox)
        rect = landmarker().mouth_rect(pts)
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        r = mouth_dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])
        if baseline is None:
            baseline = r
        ratios.append(r)
    return ratios, baseline


def count_from_ratios(ratios: list[float], baseline: float) -> tuple[int, list]:
    c = MouthActionCounter(base_ratio=baseline, abs_rise=0.05,
                           rise_ratio=1.45, hold_frames=2)
    for r in ratios:
        c.observe(r)
    return c.result()


def run_video(path: str) -> dict:
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        return {"video": path, "error": "无法打开视频"}
    frames = []
    step = max(1, int(cap.get(cv2.CAP_PROP_FPS) / FPS))
    idx = 0
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        if idx % step == 0:
            frames.append(fr)
        idx += 1
    cap.release()

    ratios, baseline = measure_ratios(frames)
    if baseline is None:
        return {"video": path, "error": "全程未检出人脸"}
    count, segs = count_from_ratios(ratios, baseline)
    return {"video": path, "frames": len(frames), "detected": count, "segments": segs}


def synthetic_cases(face_path: str) -> list[tuple[str, int, list[np.ndarray]]]:
    """生成三段合成视频：0 次 / 1 次 / 2 次张嘴。"""
    img = cv2.imread(face_path)
    assert img is not None
    base = composite(img)
    face = detector().detect_largest(base)
    poly = roi_polygon_in_image(base, face.bbox)
    dark = darken_mouth(base, poly)

    closed = [base] * 6
    one = closed + [dark, dark, dark] + [base] * 6
    two = closed + [dark, dark, dark] + [base] * 4 + [dark, dark, dark] + [base] * 6
    return [("synthetic_closed", 0, closed),
            ("synthetic_one_mouth", 1, one),
            ("synthetic_two_mouths", 2, two)]


def composite_at(img112: np.ndarray, size=(720, 1280), face_min_side: int = 320,
                 dx: int = 0, dy: int = 0, brightness: float = 1.0) -> np.ndarray:
    """按指定尺度把 CASIA 裁剪块贴到灰底上（`face_min_side` = 人脸短边像素）。"""
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = face_min_side / min(img112.shape[:2])
    face = cv2.resize(img112, (int(img112.shape[1] * scale), int(img112.shape[0] * scale)))
    if brightness != 1.0:
        face = np.clip(face.astype(np.float32) * brightness, 0, 255).astype(np.uint8)
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2 + dy, (size[1] - fw) // 2 + dx
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def measure_scales(frames: list[np.ndarray]) -> list[float]:
    """逐帧算人脸尺度（无量纲，按画面宽度归一）。检不出人脸记 0。"""
    out: list[float] = []
    for fr in frames:
        face = detector().detect_largest(fr)
        out.append(face_scale(face, fr.shape[1]) if face is not None else 0.0)
    return out


def count_scale(scales: list[float], base: float, closer: bool) -> tuple[int, list]:
    counter = ScaleActionCounter(
        base_scale=base, closer=closer,
        delta=settings.action.scale_delta,
        hold_frames=settings.action.scale_hold_frames,
    )
    for s in scales:
        counter.observe(s)
    return counter.result()


def scale_cases(face_path: str) -> list[tuple[str, str, int, list[np.ndarray]]]:
    """同一张脸按不同倍率重贴，生成带期望值的「靠近 / 离远」序列。

    期望值不是"动了就算"：0.90 / 1.10 只动了 10%，低于 delta=0.18，
    必须判 0（否则轻微挪动就能过，动作就失去意义）。
    """
    img = cv2.imread(face_path)
    assert img is not None
    base_side = 320
    base = composite_at(img, face_min_side=base_side)
    cases: list[tuple[str, str, int, list[np.ndarray]]] = [
        ("STATIC", "MOVE_CLOSER", 0, [base] * 10),
    ]
    for factor, expect in ((0.82, 1), (0.90, 0), (1.10, 0), (1.18, 1), (1.30, 1)):
        moved = composite_at(img, face_min_side=int(round(base_side * factor)))
        frames = [base] * 4 + [moved] * 6
        action = "MOVE_CLOSER" if factor > 1 else "MOVE_AWAY"
        cases.append((f"{action}@{factor:.2f}", action, expect, frames))
    return cases


def scale_noise(img: np.ndarray, n: int = 12) -> tuple[float, list[float]]:
    """静止画面的尺度波动：抖动 ±4px + 亮度 ±5% + JPEG q60。

    真实相机不会给你两张一模一样的帧，这组扰动比"同一帧重复 10 次"
    更接近手持状态（后者必然给出 0 方差，等于自欺欺人）。
    """
    rng = np.random.default_rng(20261121)
    base = measure_scales([composite_at(img)])[0]
    ratios = []
    for _ in range(n):
        fr = composite_at(img, dx=int(rng.integers(-4, 5)), dy=int(rng.integers(-4, 5)),
                          brightness=float(rng.uniform(0.95, 1.05)))
        ok, enc = cv2.imencode(".jpg", fr, [cv2.IMWRITE_JPEG_QUALITY, 60])
        if ok:
            fr = cv2.imdecode(enc, cv2.IMREAD_COLOR)
        s = measure_scales([fr])[0]
        if s > 0 and base > 0:
            ratios.append(s / base)
    return (float(np.std(ratios)) if ratios else 0.0), ratios


def run_scale(face_path: str) -> int:
    img = cv2.imread(face_path)
    if img is None:
        print(f"找不到人脸图：{face_path}")
        return 1

    print(f"人脸图：{face_path}")
    print(f"判据：delta={settings.action.scale_delta:.2f} "
          f"hold={settings.action.scale_hold_frames} 帧\n")
    print(f"{'序列':22s} {'期望':>4s} {'检出':>4s} {'实测倍率':>9s}  结果")

    failures = 0
    for name, action, expect, frames in scale_cases(face_path):
        scales = measure_scales(frames)
        base = scales[0]
        detected, _ = count_scale(scales, base, closer=action == "MOVE_CLOSER")
        # 反向动作必须为 0：同一个序列不能被另一种判据也算成"做到了"
        reverse, _ = count_scale(scales, base, closer=action != "MOVE_CLOSER")
        measured = (max(scales) / base) if action == "MOVE_CLOSER" and base else \
                   (min(scales) / base if base else 0.0)
        hit = detected == expect and reverse == 0
        failures += 0 if hit else 1
        print(f"{name:22s} {expect:>4d} {detected:>4d} {measured:>9.2f}  "
              f"{'✓' if hit else '✗'} 反向={reverse}")

    std, ratios = scale_noise(img)
    delta = settings.action.scale_delta
    sep = delta / std if std > 0 else float("inf")
    print(f"\n静止波动：std={std:.4f}（{len(ratios)} 帧，抖动±4px + JPEG q60 + 亮度±5%）")
    print(f"分离度：delta / std = {delta:.2f} / {std:.4f} = {sep:.1f}σ"
          f"（§4.4.1 要求 ≥3σ）{'✓' if sep >= 3 else '✗'}")

    ok = failures == 0 and sep >= 3
    print(f"\n结论：{'通过' if ok else '不通过'}"
          "（合成素材只证明判据保真度，真人准确率需现场录制视频复核）")
    return 0 if ok else 2


def main() -> int:
    parser = argparse.ArgumentParser(description="动作判定探针")
    parser.add_argument("--video", action="append", default=[],
                        help="真实视频文件，可多次传入")
    parser.add_argument("--expected", action="append", default=[],
                        help="与 --video 对应的期望次数，如 --expected 1")
    parser.add_argument("--synthetic", action="store_true",
                        help="用 CASIA 人脸合成序列自检脚本")
    parser.add_argument("--scale", action="store_true",
                        help="实测尺度类动作（靠近/离远）的分离度")
    parser.add_argument("--face", default=None, help="合成模式用的人脸图")
    args = parser.parse_args()

    load_all()

    if args.scale:
        return run_scale(args.face or os.path.join(CASIA, "000", "000_0.jpg"))

    results: list[dict] = []

    if args.synthetic:
        face_path = args.face or os.path.join(CASIA, "000", "000_0.jpg")
        for name, expected, frames in synthetic_cases(face_path):
            ratios, baseline = measure_ratios(frames)
            count, segs = count_from_ratios(ratios, baseline)
            results.append({"video": name, "expected": expected,
                            "detected": count, "frames": len(frames), "segments": segs})
    else:
        for i, v in enumerate(args.video):
            r = run_video(v)
            r["expected"] = int(args.expected[i]) if i < len(args.expected) else None
            results.append(r)

    print(f"{'视频':24s} {'期望':>4s} {'检出':>4s} {'帧数':>4s}  结果")
    ok = 0
    total = 0
    for r in results:
        if "error" in r:
            print(f"{r['video']:24s}  --   --    --   {r['error']}")
            continue
        exp = r.get("expected")
        det = r["detected"]
        hit = (exp is None) or (exp == det)
        total += 1
        ok += 1 if hit else 0
        mark = "✓" if hit else "✗"
        print(f"{r['video']:24s} {str(exp):>4s} {det:>4d} {r['frames']:>4d}  {mark} {r.get('segments', '')}")

    if total:
        print(f"\n准确率 {ok}/{total} ({100*ok/total:.0f}%，Gate 要求 ≥95%)")
    if not args.video and not args.synthetic:
        print("\n（未提供视频。真人指标需要现场录制的张嘴视频：--video a.mp4 --expected 1）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
