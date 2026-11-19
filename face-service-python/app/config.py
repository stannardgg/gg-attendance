"""配置中心。所有阈值都来自项目文档 §10.5，与 B1 冻结的 sys_config 默认值一一对应。

为什么用环境变量而不是配置文件：阈值在 §10.5 是「可被标定脚本改写」的运行时数据，
真正的权威在数据库 sys_config 表里。这里只提供**默认值与覆盖入口**：
  - Java 侧下发配置后，服务通过 /admin/config 变更会热更新（B4 接线）
  - 环境变量用于本地实验与 CI

命名：FA_ 前缀 + 大写下划线，避免与系统环境变量撞名。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _get(name: str, default: str) -> str:
    return os.environ.get(f"FA_{name}", default)


def _get_float(name: str, default: float) -> float:
    return float(_get(name, str(default)))


def _get_int(name: str, default: int) -> int:
    return int(_get(name, str(default)))


@dataclass
class ModelConfig:
    """模型路径与加载参数。"""

    model_dir: str = field(default_factory=lambda: _get(
        "MODEL_DIR",
        # 默认用项目内 models/ 目录（四个 ONNX 不入 git，获取方式见 README）
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")))
    det: str = "det_10g.onnx"
    rec: str = "w600k_r50.onnx"
    landmark: str = "2d106det.onnx"
    liveness: str = "minifasnet_v2.onnx"

    # SCRFD
    det_size: int = 640
    det_thresh: float = 0.5
    nms_thresh: float = 0.4

    # 2d106det 对齐空间
    landmark_size: int = 192
    landmark_margin: float = 1.5

    # minifasnet_v2
    liveness_crop_scale: float = 2.7
    liveness_live_index: int = 1          # ★ 3 类输出 [打印, 真人, 翻拍]，真人固定索引 1
    liveness_input_scale: float = 255.0   # ★ 该 ONNX 导出存在 255 倍标定偏移，必须喂 [0,255]


@dataclass
class FaceQualityConfig:
    """采集门限（§10.5 FACE 组）。"""

    min_short_side: int = 120
    laplacian_min: int = 80
    brightness_min: int = 60
    brightness_max: int = 190
    dup_sim: float = 0.95
    max_yaw: float = 30.0
    max_pitch: float = 20.0


@dataclass
class MatchConfig:
    """1:N 比对阈值。0.38~0.45 判 REVIEW（识别不够确定）是 §10.2 的分界。"""

    pass_threshold: float = 0.45
    margin_threshold: float = 0.06
    review_threshold: float = 0.38


@dataclass
class GateConfig:
    """三道硬门禁（§4.4.4）。AND 关系，逐项回传便于归因。

    阈值允许环境变量覆盖：联调/端到端演练时常常需要单独放宽某道门，
    但线上必须由 sys_config 下发（B6 接配置中心后这里的默认值只作兜底）。
    """

    liveness_threshold: float = field(default_factory=lambda: _get_float("LIVENESS_THRESHOLD", 0.55))
    liveness_samples: int = field(default_factory=lambda: _get_int("LIVENESS_SAMPLES", 5))
    identity_sim: float = field(default_factory=lambda: _get_float("IDENTITY_SIM", 0.55))


@dataclass
class ActionConfig:
    """动作可测性判据（§4.4.2）。全部由 probe_action.py 实测得出，勿凭感觉调。"""

    mouth_dark_abs: float = 0.05
    mouth_rise_ratio: float = 1.45
    mouth_hold_frames: int = 2
    window_ms_once: int = 2000
    window_ms_twice: int = 3000
    min_frames: int = 8
    action_whitelist: tuple = ("MOUTH_ONCE", "MOUTH_TWICE")
    # CR-010：挑战动作允许由请求指定。**默认关闭**——动作随机是防预录视频的一道
    # 保险，让客户端能自选动作等于把这道保险交回给攻击者。只有验收/联调环境
    # （需要稳定复现"要求 2 次只做 1 次"这类负例）才开。
    allow_action_hint: bool = field(
        default_factory=lambda: _get("ALLOW_ACTION_HINT", "0") == "1")


@dataclass
class ChallengeConfig:
    """挑战会话状态机（§9.4）。

    ttl_sec 可用 CHALLENGE_TTL_SEC 覆盖（默认 60 = 契约值）。仅压测/验收环境放宽：
    单机 CPU 推理在 30 路并发下 finish 会排队，排队超过 TTL 的挑战会被 Redis 删掉
    （R3 已登记的容量问题，不是业务缺陷）。
    """

    ttl_sec: int = field(default_factory=lambda: int(_get("CHALLENGE_TTL_SEC", "60")))
    max_retry_per_session: int = 3
    multi_face_frames: int = 3
    multi_face_min_px: int = 90
    no_face_frames: int = 3


@dataclass
class RedisConfig:
    host: str = field(default_factory=lambda: _get("REDIS_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: _get_int("REDIS_PORT", 6379))
    db: int = field(default_factory=lambda: _get_int("REDIS_DB", 1))
    password: str = field(default_factory=lambda: _get("REDIS_PASSWORD", ""))


@dataclass
class JavaConfig:
    """Java 内部接口（§6.4 internal）。B3 只读会话上下文与提交结果。"""

    base_url: str = field(default_factory=lambda: _get(
        "JAVA_INTERNAL_URL", "http://127.0.0.1:8080"))
    internal_token: str = field(default_factory=lambda: _get("INTERNAL_TOKEN", "dev-internal-token"))


@dataclass
class Settings:
    models: ModelConfig = field(default_factory=ModelConfig)
    quality: FaceQualityConfig = field(default_factory=FaceQualityConfig)
    match: MatchConfig = field(default_factory=MatchConfig)
    gates: GateConfig = field(default_factory=GateConfig)
    action: ActionConfig = field(default_factory=ActionConfig)
    challenge: ChallengeConfig = field(default_factory=ChallengeConfig)
    redis: RedisConfig = field(default_factory=RedisConfig)
    java: JavaConfig = field(default_factory=JavaConfig)
    # 服务自身
    max_upload_bytes: int = field(default_factory=lambda: _get_int("MAX_UPLOAD_BYTES", 4 * 1024 * 1024))


settings = Settings()
