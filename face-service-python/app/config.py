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
    # det_size=320（2026-11-22 恢复 hold=2 的前置优化）：客户端上传帧就是 320×240，
    # 旧值 640 等于把小帧放大一倍再检测——纯浪费（det 460ms → ~130ms），检测
    # 分数与关键点精度在本机 60 身份回归里无可测差异。可用 FA_DET_SIZE 覆盖，
    # 若某些设备检出质量异常可回 640 排查（恢复 hold=2 后单帧 ~300ms，回 640
    # 会退回 ~560ms，hold=2 的时序余量消失）。
    det_size: int = field(default_factory=lambda: _get_int("DET_SIZE", 320))
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

    # ---- 被动活体线索（2026-11-22，摩尔纹/屏幕反光/频域峰值）----
    # 只否决不放行：真实人脸不触发，加入不改变误拒率，只降低屏幕重放通过率。
    # **门禁默认关闭**：三族特征在本机做了三轮合成标定（对齐空间 / 整帧 /
    # 人脸原生裁剪），合成重放与 bona fide 分布全部重合（prom p50 4.9 vs 4.9，
    # hf p50 0.34 vs 0.37）——bicubic 重采样链复现不了屏幕摩尔纹的真实物理
    # （亚像素采样相位、像素结构、背光、传感器噪声）。按「阈值必须实测得出」
    # 的项目铁律，上线先只记特征（finish 日志，隐私安全：每帧 3 个浮点数），
    # 真机采集标定后用 PASSIVE_GATE=1 开启。
    # 下列阈值为**占位值**（合成 bona fide max × 1.2），开启前必须重标。
    passive_gate_enabled: bool = field(
        default_factory=lambda: _get("PASSIVE_GATE", "0") == "1")
    passive_peak_max: float = field(
        default_factory=lambda: _get_float("PASSIVE_PEAK_MAX", 12.5))
    passive_hf_max: float = field(
        default_factory=lambda: _get_float("PASSIVE_HF_MAX", 0.72))
    passive_clip_max: float = field(
        default_factory=lambda: _get_float("PASSIVE_CLIP_MAX", 0.42))
    passive_samples: int = field(default_factory=lambda: _get_int("PASSIVE_SAMPLES", 5))


@dataclass
class DisguiseConfig:
    """伪装/遮挡识别（CR-025，§10.5 FACE 组）。阈值来自五轮 CASIA 标定，勿凭感觉调。

    与被动活体门禁（passive）不同：遮挡对干净脸**不触发**（干净脸分布与
    阈值之间隔着 2~3 倍余量），所以默认开启不改变误拒率基线；已知盲区
    （棕色织物口罩、齐刘海、针织帽）向「不拦」侧退化，由匹配门禁兜底。
    """

    enabled: bool = field(default_factory=lambda: _get("DISGUISE_ENABLED", "1") == "1")
    # 帽子分支独立开关：齐刘海/针织帽与「布料级平坦」同分布，误拒时先关它。
    hat_enabled: bool = field(default_factory=lambda: _get("DISGUISE_HAT", "1") == "1")
    # 下半脸必盖区肤色占比上限（干净脸实测 min 0.21，取其下探针值 0.10）。
    # ★ 原设计的「嘴区 std ≤24」分支已废除：正确唇区上闭唇天然平坦
    #   （实测 23.95），与布料不可分——判据只留肤色单通道。
    mask_skin_max: float = field(default_factory=lambda: _get_float("DISGUISE_MASK_SKIN_MAX", 0.10))
    # 眼框灰度 std 上限（纹理镜面 ≤ 13.7；闭眼代理最高 19.4，但被亮度条件排除）。
    eye_std_max: float = field(default_factory=lambda: _get_float("DISGUISE_EYE_STD_MAX", 15))
    # 眼框相对亮度上限（干净/闭眼 ≥ 0.72；镜面 ≤ 0.21）。与 std 双条件 AND。
    eye_rel_max: float = field(default_factory=lambda: _get_float("DISGUISE_EYE_REL_MAX", 0.50))
    # 额头带灰度 std 上限（干净额头 min 36.3，平布代理 ≤ 24.6）。
    forehead_std_max: float = field(default_factory=lambda: _get_float("DISGUISE_FOREHEAD_STD_MAX", 16))
    # CR-025 运营取证：基线判据命中时把原始帧 JPEG 落盘到该目录（默认关）。
    # 与 disguise baseline 日志的 diag 通道统计配套，用于离线复核误报真因。
    dump_dir: str = field(default_factory=lambda: _get("DISGUISE_DUMP_DIR", ""))
    # 连续遮挡中止帧数（frame 路径，与 no_face/multi_face 同构）。
    occl_frames: int = field(default_factory=lambda: _get_int("DISGUISE_OCCL_FRAMES", 3))
    # finish 门禁：遮挡帧占比上限（occl_total / 有脸帧数）。
    occl_frame_max: float = field(default_factory=lambda: _get_float("DISGUISE_OCCL_FRAME_MAX", 0.50))


@dataclass
class ActionConfig:
    """动作可测性判据（§4.4.2）。全部由 probe_action.py 实测得出，勿凭感觉调。"""

    # CR-018：以下嘴部阈值目前不参与生产判定（白名单已无 MOUTH_*），保留仅供回滚。
    mouth_dark_abs: float = 0.05
    mouth_rise_ratio: float = 1.45
    # 连续多少帧达到阈值才算「一次」张嘴。契约值 2 的前提是 4fps 采集+
    # 250ms 级推理，动作跨度内必然覆盖两个采样点；单机 CPU 844ms/帧时
    # 稳态采样间隔≈844ms，500ms 的张口动作只能命中一帧，hold=2 等于必判失败。
    # 允许 FA_MOUTH_HOLD_FRAMES 覆盖（开发环境置 1）。降为 1 会削弱单帧防伪，
    # 但身份门禁与活体仍是主防线，生产必须留默认 2。
    mouth_hold_frames: int = field(default_factory=lambda: _get_int("MOUTH_HOLD_FRAMES", 2))
    # 采集窗口。契约值 2000/3000/2500 面向「推理跟得上 4fps 采集」的部署；
    # 单机纯 CPU 实测 844ms/帧，2 秒窗口稳态只送进 2~3 帧，动作判据靠运气。
    # 这三个值允许环境变量覆盖，只是**运维开关**，不改变门禁语义：
    #   FA_WINDOW_MS_ONCE / FA_WINDOW_MS_TWICE / FA_WINDOW_MS_SCALE
    # 延长窗口会略微削弱「短窗口防预录」的强度，活体与随机动作仍在，故可接受；
    # 生产如需严格对齐契约，留空即回落到默认值。
    window_ms_once: int = field(default_factory=lambda: _get_int("WINDOW_MS_ONCE", 2000))
    window_ms_twice: int = field(default_factory=lambda: _get_int("WINDOW_MS_TWICE", 3000))
    min_frames: int = 8
    # 尺度类动作（MOVE_CLOSER / MOVE_AWAY，§4.4.1）：判据是检测框尺度相对基线的
    # 变化比例。0.18 = 人脸占画面的大小变化 18%（40cm ↔ 47cm 量级），
    # 检测框抖动实测约 1%（见 probe_action.py --scale），留足余量。
    scale_delta: float = 0.18
    scale_hold_frames: int = 2
    window_ms_scale: int = field(default_factory=lambda: _get_int("WINDOW_MS_SCALE", 2500))
    # 可做性门槛：基线已经把脸怼满画面时不能要求「再靠近」，脸太小时不能要求「再离远」。
    # 命中门槛的动作不参与随机（否则学生物理上做不到，判失败是我们的错）。
    scale_closer_max_frac: float = 0.80      # 人脸长边 / 画面高，超过则不做「靠近」
    scale_away_min_px: float = 70.0          # 缩 18% 后仍 ≥70px 才允许「离远」
    # 闭眼（CR-021，**已随 CR-029 停用**）：眼框暗像素占比相对基线下降。0.06 来自
    # 80 个身份的实测
    # （`probe_action.py --eye`：闭眼降幅 0.178±0.065，静止抖动 std=0.0152 → 3.9σ；
    # 75/80 达标，未达标的 5 例里 1 例被下面的基线门槛挡在抽签之外）；
    # drop_ratio 再要求落到基线的 60% 以下，避免「本来就很暗的眼睛」靠绝对量过关。
    eye_dark_drop: float = field(default_factory=lambda: _get_float("EYE_DARK_DROP", 0.06))
    eye_drop_ratio: float = field(default_factory=lambda: _get_float("EYE_DROP_RATIO", 0.60))
    # hold=2（2026-11-22 晚恢复，此前一天因 1.25fps 采样降到 1）：恢复的前提是
    # 单帧耗时从 ~800ms 压到 ~300ms（det 640→320 + 隔帧 ArcFace，见 ModelConfig
    # 与 ChallengeService.frame）。服务端消化率 ~3fps 后，"保持约 1 秒"的动作
    # 落 2~3 帧，hold=2 的时序可达性成立——3.9σ 的判据灵敏度终于和采样率对齐。
    # hold=1 期间的单帧噪声风险（转头滚转残余 0.108 距阈值 0.12 仅 1σ）由
    # 反向段否决 + 闭眼姿态闸兜底，两层互补。
    eye_hold_frames: int = field(default_factory=lambda: _get_int("EYE_HOLD_FRAMES", 2))
    # 可做性门槛：基线眼区太暗（浓妆/逆光）就没有下降空间；瞳距太小（人太远）
    # 眼框只有十几个像素，统计量不可信。命中门槛的动作不参与随机。
    eye_base_min: float = 0.10
    eye_min_iod_px: float = 45.0
    # 姿态一致性闸（2026-11-22）：固定眼框 ROI 在 start 锁定，闭眼判据窗内
    # 头部一旦偏航/滚转，虹膜会被搬出 ROI，暗度下降被误读成「闭眼」——
    # 验收发现的「提示闭眼却转头/歪头也签到成功」就是这条通道。姿态相对
    # 基线超界的帧不计入闭眼判据；全部帧超界时直接判未完成（眼区统计整体
    # 不可信）。阈值与 probe_action --pose 的标定范围对齐（±15° = 0.26 rad），
    # 勿凭感觉调。
    eye_pose_yaw_max: float = 0.10
    eye_pose_roll_max: float = 0.26
    # 转头（CR-021）：偏航代理 = 鼻尖相对双眼中点的横向偏移 / 瞳距。
    # 0.12 对应约 24°（按鼻尖前凸 0.30×瞳距估算）；静止抖动 std=0.0104 → 11.5σ。
    # 整帧滚转 ±15/±30° 的漂移 mean 0.030 / max 0.108 —— 极端歪头仍可能被算成
    # 转头，这是**单向**的误放行（不会误拒），代价可接受，见 CR-021「已知限制」。
    turn_delta: float = field(default_factory=lambda: _get_float("TURN_DELTA", 0.12))
    # hold=2（2026-11-22 晚恢复）：与 eye 同步，前提是单帧 ~300ms（det 320 +
    # 隔帧 ArcFace），"保持约 1 秒"落 2~3 帧。转头动作逐帧跑 ArcFace（frontal_emb
    # 要在每帧上找最正脸），单帧 ~430ms，3s 窗口仍落 6~7 帧，余量足够。
    turn_hold_frames: int = field(default_factory=lambda: _get_int("TURN_HOLD_FRAMES", 2))
    # 基线已经明显偏头（|代理| > 0.10）时，不再要求「继续往同侧转」——
    # 再转 20° 会滑出检测范围，学生照做反而被 FA-4001 中止。
    turn_base_max: float = 0.10
    window_ms_eye: int = field(default_factory=lambda: _get_int("WINDOW_MS_EYE", 3000))
    # 转头窗口与尺度类对齐到 3s：1.25fps 下 2.5s 只有 3 帧，扣掉反应时间后
    # 转头那一帧经常落在交卷之后（真机日志：达标帧恰好是末帧）。
    window_ms_turn: int = field(default_factory=lambda: _get_int("WINDOW_MS_TURN", 3000))
    # 白名单可用 FA_ACTION_WHITELIST 覆盖（逗号分隔）：
    # 新动作在真机标定完成前，运维可以不重建镜像就把它们摘下来。
    #
    # CR-018：嘴部动作（MOUTH_ONCE / MOUTH_TWICE）已整体停用（产品决定：签到不再
    # 要求学生张嘴）。CR-021 增加闭眼与左右转头；CR-029 产品决定停用闭眼
    # （EYE_CLOSE），白名单回到 4 个。
    # 回滚嘴部/闭眼动作只需把对应码加回本变量并重建 face 容器；判据代码与
    # 阈值都还在（见上面 MOUTH_* 与 eye_* 各字段）。
    action_whitelist: tuple = field(default_factory=lambda: tuple(
        s.strip() for s in _get(
            "ACTION_WHITELIST",
            "MOVE_CLOSER,MOVE_AWAY,TURN_LEFT,TURN_RIGHT"
        ).split(",") if s.strip()))
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
    # 「离远」会把人脸缩小，如果仍按 90px 判「有没有脸」，学生照做反而被判 FA-4001。
    # 尺度类动作的最小人脸尺寸单独放宽（多脸计数仍用 multi_face_min_px，
    # 免得背景里的小脸把一次正常签到打断）。
    subject_min_px: int = 60


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
    disguise: DisguiseConfig = field(default_factory=DisguiseConfig)
    action: ActionConfig = field(default_factory=ActionConfig)
    challenge: ChallengeConfig = field(default_factory=ChallengeConfig)
    redis: RedisConfig = field(default_factory=RedisConfig)
    java: JavaConfig = field(default_factory=JavaConfig)
    # 服务自身
    max_upload_bytes: int = field(default_factory=lambda: _get_int("MAX_UPLOAD_BYTES", 4 * 1024 * 1024))


settings = Settings()
