"""1:N 比对阈值标定（WP-05 交付物）。

数据源：Cleaned_CASIA_FaceV5 的**原始照片**（100 人 × 5 张，共 500 张）。
变体图（blurred/darker/...）不参与标定——它们是质量门限的测试样本，
混进来会把"图像质量差造成的相似度下降"算成"同人不相似"，标出的阈值就偏了。

产出：
  1. reports/calibration-<date>.md —— 同人/不同人分布、各阈值下的 FAR/FRR、推荐阈值
  2. vectors.json —— 全部向量（studentId = 人物编号+1），可直接喂 FA_FACE_INDEX_FILE

跑一次约 1~2 分钟（CPU），全程可复现（固定随机种子）。
"""
from __future__ import annotations

import json
import os
import random
import sys
from datetime import date

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.engine import embedder, load_all          # noqa: E402
from app.engine.landmark import cosine_similarity  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "reports")
VEC_OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vectors.json")

SEED = 20260101
INTER_SAMPLES = 20000          # 不同人对采样数（全量约 12 万对，抽样足够估分布）
THRESHOLDS = [0.30, 0.35, 0.38, 0.40, 0.42, 0.45, 0.48, 0.50, 0.55, 0.60]


def collect_originals() -> dict[str, list[str]]:
    """人物 → 原始照片列表。只收 `000_0.jpg` 这种两段式命名。"""
    out: dict[str, list[str]] = {}
    for d in sorted(os.listdir(CASIA)):
        folder = os.path.join(CASIA, d)
        if not os.path.isdir(folder):
            continue
        files = []
        for f in sorted(os.listdir(folder)):
            stem = f[:-4] if f.endswith(".jpg") else f
            if f.endswith(".jpg") and len(stem.split("_")) == 2:
                files.append(os.path.join(folder, f))
        if files:
            out[d] = files
    return out


def compute_embeddings(data: dict[str, list[str]]) -> dict[str, list[np.ndarray]]:
    load_all()
    result: dict[str, list[np.ndarray]] = {}
    total = sum(len(v) for v in data.values())
    done = 0
    for person, paths in data.items():
        embs = []
        for path in paths:
            img = cv2.imread(path)
            if img is None:
                continue
            session = embedder().registry.rec
            blob = cv2.dnn.blobFromImage(img, 1.0 / 127.5, (112, 112),
                                         (127.5, 127.5, 127.5), swapRB=True)
            e = session.run(None, {session.get_inputs()[0].name: blob})[0].ravel()
            norm = float(np.linalg.norm(e))
            embs.append((e / norm).astype(np.float32) if norm else e.astype(np.float32))
            done += 1
            if done % 100 == 0:
                print(f"  特征提取 {done}/{total}")
        if len(embs) >= 2:
            result[person] = embs
    return result


def pair_stats(embs: dict[str, list[np.ndarray]]) -> tuple[np.ndarray, np.ndarray]:
    """同人全量配对；不同人按固定种子抽样。"""
    rng = random.Random(SEED)
    persons = sorted(embs)
    intra = []
    for p in persons:
        lst = embs[p]
        for i in range(len(lst)):
            for j in range(i + 1, len(lst)):
                intra.append(cosine_similarity(lst[i], lst[j]))

    inter = []
    # 生成所有跨人物对的人对索引，再抽样
    cross = [(a, b) for i, a in enumerate(persons) for b in persons[i + 1:]]
    rng.shuffle(cross)
    target_pairs = INTER_SAMPLES // max(1, len(cross)) + 1
    for a, b in cross[:len(cross)]:
        ea, eb = embs[a], embs[b]
        for _ in range(target_pairs):
            i, j = rng.randrange(len(ea)), rng.randrange(len(eb))
            inter.append(cosine_similarity(ea[i], eb[j]))
            if len(inter) >= INTER_SAMPLES:
                break
        if len(inter) >= INTER_SAMPLES:
            break
    return np.array(intra), np.array(inter)


def far_frr(intra: np.ndarray, inter: np.ndarray, t: float) -> tuple[float, float]:
    frr = float((intra < t).mean()) if intra.size else 0.0
    far = float((inter >= t).mean()) if inter.size else 0.0
    return far, frr


def main() -> int:
    data = collect_originals()
    total = sum(len(v) for v in data.values())
    print(f"人物 {len(data)} 个，原始照片 {total} 张")

    embs = compute_embeddings(data)
    print("特征提取完成，开始配对……")

    intra, inter = pair_stats(embs)
    print(f"同人对 {len(intra)}，不同人对 {len(inter)}")

    os.makedirs(OUT_DIR, exist_ok=True)
    lines = [
        "# 1:N 比对阈值标定报告",
        "",
        f"- 日期：{date.today().isoformat()}",
        f"- 数据：Cleaned_CASIA_FaceV5 原始照片，{len(data)} 人 / {sum(len(v) for v in data.values())} 张",
        f"- 同人配对：全量 {len(intra)}；不同人配对：固定种子抽样 {len(inter)}（seed={SEED}）",
        "- 模型：w600k_r50.onnx（ArcFace 512 维，L2 归一化，余弦相似度）",
        "",
    ]

    i_mean, i_std = float(intra.mean()), float(intra.std())
    x_mean, x_std = float(inter.mean()), float(inter.std())
    lines += [
        "## 分布",
        "",
        "| 组 | mean | std | min | max |",
        "|---|---|---|---|---|",
        f"| 同人 | {i_mean:.4f} | {i_std:.4f} | {intra.min():.4f} | {intra.max():.4f} |",
        f"| 不同人 | {x_mean:.4f} | {x_std:.4f} | {inter.min():.4f} | {inter.max():.4f} |",
        "",
        f"可分性 gap = **{i_mean - x_mean:.4f}**",
        "",
    ]

    lines += ["## 各阈值下的 FAR / FRR", "",
              "| 阈值 | FAR（误收） | FRR（误拒） |", "|---|---|---|"]
    best = None
    for t in THRESHOLDS:
        far, frr = far_frr(intra, inter, t)
        lines.append(f"| {t:.2f} | {far*100:.2f}% | {frr*100:.2f}% |")
        # 运行点：FRR ≤ 5% 的前提下 FAR 最小
        if frr <= 0.05:
            if best is None or far < best[1]:
                best = (t, far, frr)
    lines.append("")

    if best:
        t, far, frr = best
        lines += [
            "## 推荐",
            "",
            f"在 FRR ≤ 5%（§13.2 的误拒率上限）约束下，FAR 最小的阈值是 **{t:.2f}**"
            f"（FAR {far*100:.3f}%，FRR {frr*100:.2f}%）。",
            "",
            f"当前契约默认 PASS_THRESHOLD={0.45}、REVIEW_THRESHOLD={0.38}。"
            f"若推荐阈值与 0.45 偏离超过 0.05，应更新 sys_config（走 CR，"
            f"因为阈值已经在 B1 冻结的默认值表里）。",
            "",
        ]

    # 与契约默认值的对照
    far45, frr45 = far_frr(intra, inter, 0.45)
    far38, frr38 = far_frr(intra, inter, 0.38)
    lines += [
        "## 契约默认值实测",
        "",
        f"- PASS_THRESHOLD=0.45 → FAR {far45*100:.3f}% / FRR {frr45*100:.2f}%",
        f"- REVIEW_THRESHOLD=0.38 → FAR {far38*100:.3f}% / FRR {frr38*100:.2f}%",
        "",
        "> 注：CASIA 是公开研究数据集，本报告标出的是**这套模型在这套数据上的行为**，",
        "> 不是线上真实误拒率。真实教室场景（光照/角度/分辨率不同）需要现场复标，",
        "> 也就是 §16.1 里『实测阈值下 FRR ≤5%』那条 Gate 在 B6 的做法。",
        "",
    ]

    report_path = os.path.join(OUT_DIR, f"calibration-{date.today().isoformat()}.md")
    with open(report_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"报告已写入 {report_path}")

    # 向量导出（studentId = 人物编号+1，供向量库与流程测试使用）
    vectors = []
    for person, lst in sorted(embs.items()):
        try:
            sid = int(person) + 1
        except ValueError:
            sid = abs(hash(person)) % 10000
        for e in lst:
            vectors.append({"studentId": sid, "vector": [round(float(x), 5) for x in e]})
    with open(VEC_OUT, "w", encoding="utf-8") as fh:
        json.dump(vectors, fh)
    print(f"向量已导出 {len(vectors)} 条 → {VEC_OUT}")

    print("\n".join(lines[lines.index("## 各阈值下的 FAR / FRR"):]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
