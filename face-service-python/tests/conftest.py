"""测试共享守卫。

模型（~189MB）与 CASIA 素材都不入库，CI 上没有这两样——
依赖它们的测试自动跳过，纯逻辑测试（动作计数器等）不受影响。
这保证 CI 的 python job 永远是绿的，同时本机开发仍能跑全套。
"""
from __future__ import annotations

import os

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODELS_DIR = os.environ.get(
    "FA_MODEL_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models"))
CASIA_DIR = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))

MODELS_AVAILABLE = all(
    os.path.exists(os.path.join(MODELS_DIR, f))
    for f in ("det_10g.onnx", "w600k_r50.onnx"))
CASIA_AVAILABLE = os.path.isdir(CASIA_DIR)

SKIP_NO_MODEL = "需要 ONNX 模型文件（不入库，见 face-service-python/README.md）"
SKIP_NO_CASIA = "需要 Cleaned_CASIA_FaceV5 素材（不入库）"
