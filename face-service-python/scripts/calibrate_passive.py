"""被动活体线索标定工具。

★ 2026-11-22 结论：合成标定不可行（三轮实验全部零区分度）——
  a) 对齐空间（192 上采样）：bona fide prom p95=112 > 重放 p95=60，上采样
     插值本身在 bona fide 里注入了与重放同族的谱峰，特征失效；
  b) 整帧非轴向峰（整数倍重采样模拟）：分布完全重合；
  c) 人脸原生裁剪非轴向峰（非整数比 + 微透视 + 镜头模糊模拟）：bona fide
     prom max=10.2 / hf max=0.60，重放 max=9.6 / 0.60——零区分度。
  原因：bicubic 重采样链复现不了屏幕摩尔纹的真实物理（亚像素采样相位漂移、
  RGB 子像素结构、背光不均、传感器噪声）。

因此阈值的权威来源只能是**真机采集**。本脚本保留两种模式：

  1. --synth：复跑合成实验（只用于回归验证特征代码本身没有引入偏置，
     不再用于定阈值——它测不出攻击）；
  2. --real <目录>：真机采集标定（权威模式）。
     bona fide 语料：≥10 人 × 每人 3 场真实摄像头签到帧（原 JPEG 帧落盘或
     从 passive features 日志回收浮点数均可）；
     攻击语料：≥10 场真机屏幕重放（手机播放预录视频对准摄像头）。
     输出 bona fide 上限（p99.9）与攻击下限（p10），阈值取几何中点并留边距，
     写回 config.py 的 PASSIVE_* 并同步注释。

生产语料的免费来源：finish 日志的「passive features」行（每场 4 个中位数/
最大值浮点数），PASSIVE_GATE=0 时也在记。
"""
from __future__ import annotations

import argparse
import glob
import os
import sys

import cv2
import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.challenge.passive import clip_ratio, spectral_prominence  # noqa: E402

CASIA = os.getenv("CASIA_DIR", os.path.join(REPO, "Cleaned_CASIA_FaceV5"))


def synth_replay(frame: np.ndarray, seed: int) -> np.ndarray:
    """合成重放（已被证明测不出差异，仅供特征代码回归）。"""
    rng = np.random.default_rng(seed)
    up = cv2.resize(frame, (933, 700), interpolation=cv2.INTER_CUBIC)
    m = cv2.getRotationMatrix2D((466, 350), 1.5 + rng.normal(0, 0.5),
                                1.0 + rng.normal(0, 0.01))
    m[0, 2] += rng.normal(0, 3)
    m[1, 2] += rng.normal(0, 3)
    up = cv2.warpAffine(up, m, (933, 700))
    up = cv2.GaussianBlur(up, (0, 0), 0.8)
    dn = cv2.resize(up, (frame.shape[1], frame.shape[0]), interpolation=cv2.INTER_CUBIC)
    dn = np.clip(dn.astype(np.float32) + rng.normal(0, 2, dn.shape), 0, 255).astype(np.uint8)
    ok, buf = cv2.imencode(".jpg", dn, [cv2.IMWRITE_JPEG_QUALITY, 90])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def stats(values: list[float]) -> dict:
    a = np.asarray(values)
    return {p: round(float(np.percentile(a, p)), 4) for p in (10, 50, 95, 99, 99.9)} | {
        "max": round(float(a.max()), 4)}


def collect(frames, kind: str):
    out = {"prom": [], "hf": [], "clip": []}
    for i, frame in enumerate(frames):
        if kind == "replay":
            frame = synth_replay(frame, i)
        if frame is None or frame.size == 0:
            continue
        prom, hf = spectral_prominence(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY))
        out["prom"].append(prom)
        out["hf"].append(hf)
        out["clip"].append(clip_ratio(frame))
    return out


def print_stats(label: str, vals: dict) -> None:
    print(f"== {label} ==")
    for k, v in vals.items():
        print(f"  {k}: {stats(v)}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--synth", action="store_true", help="复跑合成实验（不做阈值依据）")
    ap.add_argument("--real", nargs=2, metavar=("BONA_DIR", "ATTACK_DIR"),
                    help="真机采集标定（权威模式）")
    args = ap.parse_args()

    if args.real:
        bona, attack = [], []
        for d, sink in ((args.real[0], bona), (args.real[1], attack)):
            for p in sorted(glob.glob(os.path.join(d, "**", "*.jpg"), recursive=True)):
                img = cv2.imread(p)
                if img is None:
                    continue
                prom, hf = spectral_prominence(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
                sink.append((prom, hf, clip_ratio(img)))
        if not bona or not attack:
            raise SystemExit("两个目录都必须有 .jpg 帧")
        b = np.asarray(bona)
        a = np.asarray(attack)
        print("== bona fide（真机）==")
        for j, k in enumerate(("prom", "hf", "clip")):
            print(f"  {k}: {stats(b[:, j].tolist())}  → 上限建议 p99.9={np.percentile(b[:, j], 99.9):.4f}")
        print("== attack（真机重放）==")
        for j, k in enumerate(("prom", "hf", "clip")):
            print(f"  {k}: {stats(a[:, j].tolist())}  → 下限参考 p10={np.percentile(a[:, j], 10):.4f}")
        print("阈值 = 几何中点（各项独立取），写回 config.PASSIVE_* 并同步注释。")
        return

    # 默认 / --synth：CASIA 合成回归（结论：区分度=0，只验证特征代码无偏置漂移）
    paths = sorted(glob.glob(os.path.join(CASIA, "*", "*.jpg")))[:2000]
    frames = []
    for p in paths:
        img = cv2.imread(p)
        if img is None:
            continue
        fh, fw = img.shape[:2]
        frame = np.full((240, 320, 3), 60, np.uint8)
        scale = min(120 / fh, 120 / fw)
        small = cv2.resize(img, (max(1, int(fw * scale)), max(1, int(fh * scale))))
        frame[60:60 + small.shape[0], 100:100 + small.shape[1]] = small
        frames.append(frame)
    for kind in ("bona", "replay"):
        print_stats(kind, collect(frames, kind))


if __name__ == "__main__":
    main()
