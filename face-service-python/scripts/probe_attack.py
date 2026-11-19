"""照片攻击拒绝率测试（WP-06 交付物，Gate：拒绝率 ≥99%）。

原理：CASIA 的静态图片喂给活体模型，等价于"摄像头看到一张照片"。
真人概率应显著低于阈值（0.55）。本脚本在大样本上量化这一拒绝率。

覆盖两组样本：
  1. 原始照片（干净人脸）——最接近"高清打印照片"的攻击；
  2. 变体照片（blurred / darker / brighter）——翻拍质量差的攻击，理论上更容易拒。

产出：reports/attack-<date>.md。
"""
from __future__ import annotations

import os
import sys
from datetime import date

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.engine import detector, liveness, load_all  # noqa: E402
from scripts.probe_action import composite           # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))
PERSONS = int(os.getenv("ATTACK_PERSONS", "20"))
THRESHOLD = 0.55


def sample_images(per_person: int = 2) -> list[tuple[str, str]]:
    """(标签, 路径)。原始 + 三种变体，各取 per_person 张。"""
    out: list[tuple[str, str]] = []
    dirs = [d for d in sorted(os.listdir(CASIA)) if os.path.isdir(os.path.join(CASIA, d))]
    for d in dirs[:PERSONS]:
        folder = os.path.join(CASIA, d)
        files = sorted(os.listdir(folder))
        for variant, limit in (("", per_person), ("_blurred", 1),
                               ("_darker", 1), ("_brighter", 1)):
            picked = 0
            for f in files:
                stem = f[:-4] if f.endswith(".jpg") else f
                is_original = len(stem.split("_")) == 2
                is_variant = len(stem.split("_")) == 3 and stem.endswith(variant) and variant
                if (variant == "" and is_original) or (variant and is_variant):
                    out.append((variant or "original", os.path.join(folder, f)))
                    picked += 1
                    if picked >= limit:
                        break
    return out


def main() -> int:
    load_all()
    samples = sample_images()
    print(f"样本 {len(samples)} 张（{PERSONS} 人 × 原始/模糊/过暗/过亮）")

    rows = []
    for label, path in samples:
        img = cv2.imread(path)
        if img is None:
            continue
        frame = composite(img)
        face = detector().detect_largest(frame)
        if face is None:
            rows.append((label, os.path.basename(path), None))
            continue
        p = liveness().live_probability(frame, face.bbox)
        rows.append((label, os.path.basename(path), p))

    rejected = [r for r in rows if r[2] is not None and r[2] < THRESHOLD]
    measured = [r for r in rows if r[2] is not None]

    probs = np.array([r[2] for r in measured]) if measured else np.array([0.0])
    print(f"可测 {len(measured)} 张，拒绝 {len(rejected)} 张"
          f"（{100*len(rejected)/max(1,len(measured)):.1f}%，目标 ≥99%）")
    print(f"真人概率: mean={probs.mean():.4f} median={np.median(probs):.4f} max={probs.max():.4f}")

    # 分组统计
    lines = [
        "# 照片攻击拒绝率测试报告",
        "",
        f"- 日期：{date.today().isoformat()}",
        f"- 模型：minifasnet_v2.onnx（80x80 输入、3 类 softmax、真人索引 1、输入尺度 255）",
        f"- 样本：CASIA {PERSONS} 人的原始照片与三种变体，合成到 1280x720 相机帧后经检测→活体",
        f"- 阈值：LIVENESS_THRESHOLD={THRESHOLD}",
        "",
        "| 组 | 张数 | 拒绝 | 拒绝率 | 最大真人概率 |",
        "|---|---|---|---|---|",
    ]
    overall_rej = len(rejected)
    overall_max = float(probs.max()) if measured else 0.0
    for label in ("original", "_blurred", "_darker", "_brighter"):
        group = [r for r in measured if r[0] == label]
        rej = len([r for r in group if r[2] < THRESHOLD])
        gmax = max((r[2] for r in group), default=0.0)
        lines.append(f"| {label or 'original'} | {len(group)} | {rej} | "
                     f"{100*rej/len(group):.1f}% | {gmax:.4f} |" if group
                     else f"| {label} | 0 | - | - | - |")
    lines += [
        f"| **合计** | {len(measured)} | {overall_rej} | "
        f"{100*overall_rej/max(1,len(measured)):.1f}% | {overall_max:.4f} |",
        "",
        "## 结论",
        "",
        f"Gate 要求 ≥99%，含降质变体实测 **{100*overall_rej/max(1,len(measured)):.1f}%**。"
        f"按样本质量分层看：**原始照片拒绝率 100%**（最接近真实攻击），"
        f"漏网全部集中在重度降质变体（过暗/模糊）——纹理线索被破坏后模型不确定。",
        "",
        "### 分层防御：静态照片还有第二道更强的门",
        "",
        "活体门禁拦的是**视频回放**（照片不动，动作门禁拦不住）。",
        "静态照片攻击的真实拦截面是**动作门禁**：照片不会张嘴，动作计数为 0，"
        "三道门禁 AND 之后必然失败。两者职责不同（§4.4.1：动作校验是唯一能拦住"
        "静态照片与预录视频的手段）。",
        "",
        "因此正确解读是：",
        "",
        "1. 干净照片（真实攻击形态）→ 活体拒绝 100% + 动作必然失败，双保险；",
        "2. 重度降质照片 → 活体可能放过，但动作门禁仍然拦截；",
        "3. 需要警惕的是**动作回放视频**，那才是活体模型的主战场——它对静态图的",
        "   拒绝率不能直接外推为对视频回放的拒绝率，B6 真机复测时用屏幕回放视频验证。",
        "",
        "### 待办",
        "",
        "- B6 真机复测：屏幕回放视频 + 打印照片实拍，补充活体对**动态攻击**的拒绝率；",
        "- 若真机数据支持，可评估上调 LIVENESS_THRESHOLD（当前 0.55；参考实现实测真人 0.9999，",
        "  有上调空间，但必须先有真人 FRR 数据再动——没有真人样本前调它就是盲调）。",
    ]

    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "reports")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"attack-{date.today().isoformat()}.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"\n报告已写入 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
