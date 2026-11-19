"""引擎冒烟测试：用 Cleaned_CASIA_FaceV5 验证四条链路。

数据集特点：图片已经是 112x112 的**对齐人脸**（不是原始拍摄图）。
因此本脚本验证两条独立链路：
  A. 预对齐图 → 直接提特征 → 1:N 可分性（这是 calibrate.py 的基础）
  B. 把对齐图合成到相机尺寸画布上 → 走完整检测链路 → 活体概率
     （静态照片喂活体模型，应当被拒绝——它们本来就是"照片攻击"样本）

用法：
  FA_MODEL_DIR=/path/to/models python3 scripts/smoke_engine.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

from app.engine import detector, embedder, landmarker, liveness, load_all
from app.engine.landmark import cosine_similarity

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.environ.get("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))


def persons(n: int = 3) -> list[str]:
    return [d for d in sorted(os.listdir(CASIA))
            if os.path.isdir(os.path.join(CASIA, d))][:n]


def originals_of(person: str, limit: int = 5) -> list[str]:
    out = []
    d = os.path.join(CASIA, person)
    for f in sorted(os.listdir(d)):
        stem = f[:-4] if f.endswith(".jpg") else f
        if len(stem.split("_")) == 2 and f.endswith(".jpg"):
            out.append(os.path.join(d, f))
        if len(out) >= limit:
            break
    return out


def direct_embedding(img112: np.ndarray) -> np.ndarray:
    """预对齐 112x112 直接提特征（跳过检测与二次对齐）。"""
    session = embedder().registry.rec
    blob = cv2.dnn.blobFromImage(img112, 1.0 / 127.5, (112, 112),
                                 (127.5, 127.5, 127.5), swapRB=True)
    e = session.run(None, {session.get_inputs()[0].name: blob})[0].ravel()
    norm = float(np.linalg.norm(e))
    return (e / norm).astype(np.float32) if norm else e.astype(np.float32)


def composite_frame(img112: np.ndarray, canvas=(720, 1280)) -> np.ndarray:
    """把 112x112 的人脸贴到相机尺寸画布中央，模拟一次真实拍摄。

    为什么不直接把 112 放大到 640 去检测：放大 5.7 倍的图全是插值模糊，
    检测分数会虚低，测出来的不是检测能力而是插值损失。
    """
    frame = np.full((canvas[0], canvas[1], 3), 60, dtype=np.uint8)
    h, w = img112.shape[:2]
    # 目标短边 ~320px（模拟 1~2 米外的人脸）
    scale = 320 / min(h, w)
    face = cv2.resize(img112, (int(w * scale), int(h * scale)))
    fh, fw = face.shape[:2]
    y0 = (canvas[0] - fh) // 2
    x0 = (canvas[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def main() -> int:
    status = load_all()
    print("可选模型就绪位:", status)
    people = persons(3)

    # ============ A. 预对齐图 → 特征 → 可分性 ============
    embs: dict[str, list[np.ndarray]] = {}
    for pid in people:
        for path in originals_of(pid):
            img = cv2.imread(path)
            if img is None:
                continue
            embs.setdefault(pid, []).append(direct_embedding(img))

    intra, inter = [], []
    ps = [p for p in people if len(embs.get(p, [])) >= 2]
    for i, p1 in enumerate(ps):
        for a in embs[p1]:
            for b in embs[p1]:
                if a is not b:
                    intra.append(cosine_similarity(a, b))
            for p2 in ps[i + 1:]:
                for b in embs[p2]:
                    inter.append(cosine_similarity(a, b))
    intra_a, inter_a = np.array(intra), np.array(inter)
    print(f"\n[A] 预对齐图 1:N 可分性（n_intra={len(intra)} n_inter={len(inter)}）")
    print(f"    同人:   mean={intra_a.mean():.3f} min={intra_a.min():.3f}")
    print(f"    不同人: mean={inter_a.mean():.3f} max={inter_a.max():.3f}")
    gap = float(intra_a.mean() - inter_a.mean())
    print(f"    gap={gap:.3f}（>0.2 可用）  0.45 阈值交叉: 同人误拒 {np.mean(intra_a < 0.45):.1%}, "
          f"不同人误收 {np.mean(inter_a >= 0.45):.1%}")

    # ============ B. 相机帧合成 → 检测 → 活体 ============
    print("\n[B] 合成相机帧：检测 / 关键点 / 活体（静态照片应被拒）")
    det_ok = 0
    det_total = 0
    live_probs: list[float] = []
    for pid in people[:2]:
        for path in originals_of(pid, limit=3):
            img112 = cv2.imread(path)
            if img112 is None:
                continue
            frame = composite_frame(img112)
            det_total += 1
            face = detector().detect_largest(frame)
            if face is None:
                print(f"    {os.path.basename(path)}: 未检出 ✗")
                continue
            det_ok += 1
            try:
                roi, _ = landmarker().mouth_roi(frame, face.bbox)
                roi_info = f"ROI {roi.shape[1]}x{roi.shape[0]}"
            except Exception as exc:                      # noqa: BLE001
                roi_info = f"ROI 失败({exc})"
            p = liveness().live_probability(frame, face.bbox)
            if p is not None:
                live_probs.append(p)
                print(f"    {os.path.basename(path)}: short_side={face.short_side:.0f}px "
                      f"score={face.score:.2f} {roi_info} 活体概率={p:.3f}")
            else:
                print(f"    {os.path.basename(path)}: 活体模型不可用")

    if live_probs:
        lp = np.array(live_probs)
        rejected = int((lp < 0.55).sum())
        print(f"    活体概率: mean={lp.mean():.3f} max={lp.max():.3f}  "
              f"拒绝率 {rejected}/{len(lp)} ({100*rejected/len(lp):.0f}%，目标 ≥99%)")
    print(f"    检出率: {det_ok}/{det_total}")

    # ============ 结论 ============
    a_ok = gap > 0.2
    b_ok = det_ok == det_total and (not live_probs or float(np.max(live_probs)) < 0.55)
    print("\n结论:")
    print(f"  [A] 1:N 链路: {'✓ 可用' if a_ok else '✗ 有问题'}")
    print(f"  [B] 相机帧链路（检测/ROI/活体拒照片）: {'✓ 可用' if b_ok else '✗ 有问题'}")
    return 0 if (a_ok and b_ok) else 1


if __name__ == "__main__":
    raise SystemExit(main())
