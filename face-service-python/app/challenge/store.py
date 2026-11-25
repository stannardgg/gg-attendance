"""挑战会话状态机（Redis）。

四条硬约束（§9.4）：
  1. 一次性：challengeId 用后即焚（finish 成功或失败都销毁），防重放；
  2. 绑定：state 里存 sessionId/studentId/ticketJti，finish 时核对票据；
  3. TTL：60 秒，Redis 原生过期，不自己实现时钟；
  4. 多 worker 安全：**所有判定依据都存在 state 里，而不是进程内存**——
     这是「4 worker 跨进程状态正确」的关键。若把帧序列放在进程内 dict，
     worker1 收的帧 worker2 看不见，动作就数不齐。

帧序列的存储取舍：
  - 只存每帧的暗度标量（float），不存图像——单次挑战 ≤12 帧，每帧一个数，
    Redis 里几百字节；存图像既慢又有隐私问题（§13.4 不落原图）。
"""
from __future__ import annotations

import json
import time
import uuid
from dataclasses import asdict, dataclass, field

from app.config import settings

PREFIX = "fa:challenge:"

# 尺度类动作：判据是检测框尺度，不是嘴区暗度（见 challenge/action.py）
SCALE_ACTIONS = ("MOVE_CLOSER", "MOVE_AWAY")
# 转头（CR-021）：判据是 SCRFD 5 点算出的偏航代理
TURN_ACTIONS = ("TURN_LEFT", "TURN_RIGHT")
# 转向：+1 = 向左（偏航代理增大）。符号来源见 action.yaw_proxy 的说明。
TURN_DIRECTIONS = {"TURN_LEFT": 1, "TURN_RIGHT": -1}
# 闭眼（CR-021）：判据是眼框暗像素占比下降。CR-029 起停用（不再抽签、不收
# hint），判据保留供回滚与存量 challenge 判定。
EYE_ACTIONS = ("EYE_CLOSE",)
# 不依赖任何基线的动作（只有已停用的嘴部动作属于这一类，回滚时才会出现在白名单里）。
# start 用它决定「基线无脸」到底该拒绝还是降级。
BASELINE_FREE_ACTIONS = ("MOUTH_ONCE", "MOUTH_TWICE")

# CR-018：MOUTH_* 已停用（不再抽签、不接受 hint）。CR-029：EYE_CLOSE 同样停用。
# 这些表保留全部键，一是回滚只需改 FA_ACTION_WHITELIST，二是 Redis 里未过期的
# 存量 challenge 仍能反序列化、跑完剩余窗口。
ACTION_WINDOW = {
    "MOUTH_ONCE": settings.action.window_ms_once,
    "MOUTH_TWICE": settings.action.window_ms_twice,
    "MOVE_CLOSER": settings.action.window_ms_scale,
    "MOVE_AWAY": settings.action.window_ms_scale,
    "EYE_CLOSE": settings.action.window_ms_eye,
    "TURN_LEFT": settings.action.window_ms_turn,
    "TURN_RIGHT": settings.action.window_ms_turn,
}

# 每个动作要完成几次。靠近/离远/闭眼/转头做一次就够——「连续靠近两次」没有物理意义。
# （闭眼已停用，仅回滚时生效。）
ACTION_NEED = {
    "MOUTH_ONCE": 1,
    "MOUTH_TWICE": 2,
    "MOVE_CLOSER": 1,
    "MOVE_AWAY": 1,
    "EYE_CLOSE": 1,
    "TURN_LEFT": 1,
    "TURN_RIGHT": 1,
}


@dataclass
class ChallengeState:
    """一次挑战的全部状态。序列化后整个存进 Redis 一个 key。"""

    challenge_id: str
    session_id: int
    student_id: int
    ticket_jti: str
    action: str                      # MOVE_* / EYE_CLOSE / TURN_LEFT / TURN_RIGHT
    need: int                        # 需要完成的动作次数
    window_ms: int
    min_frames: int
    created_ms: int
    baseline_ratio: float            # 基线帧嘴区暗度
    baseline_degraded: bool          # 基线不可得、退回序列首帧
    seq: int = 0                     # 已接收帧数（也用作下一帧的 seq）
    ratios: list[float] = field(default_factory=list)
    # 尺度类动作（靠近/离远）的每帧人脸尺度；默认 0 表示旧状态没有这个字段
    baseline_scale: float = 0.0
    scales: list[float] = field(default_factory=list)
    # 闭眼（CR-021）：基线眼框暗度 + 每帧值 + 两只眼框（192 空间，8 个整数）
    baseline_eye: float = 0.0
    eyes: list[float] = field(default_factory=list)
    eye_roi: list[int] = field(default_factory=list)
    # 姿态一致性闸（2026-11-22，闭眼用）：基线滚转 + 每帧偏航/滚转代理。
    # 与 eyes 严格同长（同一分支里追加），长度不一致视为部署前的旧状态，
    # 闸门跳过（保持原判据，见 gates.gate_action）。
    baseline_roll: float = 0.0
    eye_yaws: list[float] = field(default_factory=list)
    eye_rolls: list[float] = field(default_factory=list)
    # 转头（CR-021）：基线偏航代理 + 每帧代理 + 序列里最正脸那一帧的特征。
    # 末帧可能是侧脸，拿它做 1:N 是自伤（similarity 掉下去 → 误判 FA-4002），
    # 所以额外记一帧"离基线偏航最近"的特征，finish 时优先用它。
    baseline_yaw: float = 0.0
    yaws: list[float] = field(default_factory=list)
    frontal_emb: list[float] = field(default_factory=list)
    frontal_dev: float = 9.9
    closed: bool = False             # 已 finish（无论成败），防止重复提交
    no_face_run: int = 0             # 连续无脸帧数
    multi_face_run: int = 0          # 连续多脸帧数
    # ROI 在 start 时锁定（192 对齐空间里的矩形），后续帧直接复用坐标。
    # -1 表示尚未锁定（首帧才锁定）。
    roi_x1: int = -1
    roi_y1: int = -1
    roi_x2: int = -1
    roi_y2: int = -1
    # 每帧的活体概率与首/末帧特征——三道门禁的原始数据，finish 时统一判
    live_probs: list[float] = field(default_factory=list)
    first_emb: list[float] = field(default_factory=list)
    last_emb: list[float] = field(default_factory=list)
    # 被动活体线索（2026-11-22）：非轴向频域峰显著性 / 高频占比 / 整帧过曝白斑。
    # 逐帧记录、finish 全量落日志——即使 PASSIVE_GATE=0（默认关）也要积累
    # 真实标定语料；每帧 3 个浮点数，不落原图（§13.4）。
    passive_peak: list[float] = field(default_factory=list)
    passive_hf: list[float] = field(default_factory=list)
    passive_clip: list[float] = field(default_factory=list)
    # 伪装/遮挡（CR-025）：连续遮挡帧（frame 中止用）、遮挡总帧（finish 门禁
    # 占比用）、出现过的类别码（文案归因）。部署前的存量 challenge 缺这三个
    # 字段时反序列化取默认值 0/[]，语义 = 无遮挡记录，与旧状态一致。
    occl_run: int = 0
    occl_total: int = 0
    occl_kinds: list[str] = field(default_factory=list)


class ChallengeStore:
    """Redis 状态存取。所有方法都是原子的（GET→改→SET 有竞态，
    但同一挑战的帧按 seq 串行上传，实际不会并发写同一个 key；
    finish 用 DELETE 返回值保证只提交一次）。"""

    def __init__(self, redis_client):
        self.redis = redis_client

    def _key(self, cid: str) -> str:
        return PREFIX + cid

    def create(self, session_id: int, student_id: int, ticket_jti: str,
               action: str, baseline_ratio: float, baseline_degraded: bool,
               baseline_scale: float = 0.0, baseline_eye: float = 0.0,
               baseline_yaw: float = 0.0, baseline_roll: float = 0.0) -> ChallengeState:
        cid = f"c_{uuid.uuid4().hex[:10]}"
        st = ChallengeState(
            challenge_id=cid,
            session_id=session_id,
            student_id=student_id,
            ticket_jti=ticket_jti,
            action=action,
            need=ACTION_NEED.get(action, 1),
            window_ms=ACTION_WINDOW.get(action, settings.action.window_ms_once),
            min_frames=settings.action.min_frames,
            created_ms=int(time.time() * 1000),
            baseline_ratio=baseline_ratio,
            baseline_degraded=baseline_degraded,
            baseline_scale=baseline_scale,
            baseline_eye=baseline_eye,
            baseline_yaw=baseline_yaw,
            baseline_roll=baseline_roll,
        )
        self.redis.set(self._key(cid), json.dumps(asdict(st)),
                       ex=settings.challenge.ttl_sec)
        return st

    def get(self, cid: str) -> ChallengeState | None:
        raw = self.redis.get(self._key(cid))
        if raw is None:
            return None
        data = json.loads(raw)
        return ChallengeState(**data)

    def save(self, st: ChallengeState) -> None:
        """剩余 TTL 跟随创建时间，避免刷新把过期时间顺延。"""
        elapsed = int(time.time() * 1000) - st.created_ms
        remain = max(1, settings.challenge.ttl_sec - elapsed // 1000)
        self.redis.set(self._key(st.challenge_id), json.dumps(asdict(st)), ex=remain)

    def consume(self, cid: str) -> ChallengeState | None:
        """原子地取走并删除。finish 只允许成功一次，靠它实现。"""
        pipe = self.redis.pipeline()
        pipe.get(self._key(cid))
        pipe.delete(self._key(cid))
        raw, _ = pipe.execute()
        if raw is None:
            return None
        return ChallengeState(**json.loads(raw))

    def remaining_ms(self, st: ChallengeState) -> int:
        return max(0, st.window_ms - (int(time.time() * 1000) - st.created_ms))
