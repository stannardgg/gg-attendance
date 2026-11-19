"""动作判定探针（WP-06 交付物）。

两种模式：

1. `--video <文件>`：真实视频。抽帧 4fps → 检测 → ROI → 计数，输出检出次数与分段。
   **Gate 的真人指标靠它**——需要现场录制的「张嘴一次 / 两次」视频，
   目前素材库里没有，所以 B3 交付时这条是"逻辑已验证、真人指标待补"。

2. `--synthetic`：自检模式。用 CASIA 人脸合成视频，
   把嘴部 ROI 区域按时间表涂暗来模拟张嘴，跑完整链路后核对计数。
   它验证的是**脚本与计数链路的正确性**，不证明真人准确率——两者不要混淆。

输出：每段视频的 expected vs detected，以及总体准确率。
"""
from __future__ import annotations

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.action import MouthActionCounter, mouth_dark_ratio  # noqa: E402
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


def main() -> int:
    parser = argparse.ArgumentParser(description="动作判定探针")
    parser.add_argument("--video", action="append", default=[],
                        help="真实视频文件，可多次传入")
    parser.add_argument("--expected", action="append", default=[],
                        help="与 --video 对应的期望次数，如 --expected 1")
    parser.add_argument("--synthetic", action="store_true",
                        help="用 CASIA 人脸合成序列自检脚本")
    parser.add_argument("--face", default=None, help="合成模式用的人脸图")
    args = parser.parse_args()

    load_all()
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
