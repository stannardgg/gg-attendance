"""主动活体（动作挑战）真实-world APCER/BPCER 评测。

协议：docs/verification/active-liveness-protocol.md（指标定义、素材矩阵、
落盘格式、决策映射都在那里，本脚本只负责执行）。

两种模式：

1. `--proxy`：几何同构代理评测（本机可跑，先于真机）。
   用合成素材驱动**完整生产判定链**（同一 ChallengeService + 门禁 + 计数器）：

     - print-move 代理：同一张脸按 1.30x / 0.72x 重贴 —— 与"照片物理靠近/远离"
       **几何同构**（bbox 尺度变化与真人完全同构，判据不读纹理）；
     - 不足 delta 的轻微挪动（1.10x / 0.90x）：攻击者没做够动作的负例；
     - screen-tilt 代理：CASIA 同身份另一张原图（**真实 3D 姿态**）—— 与
       "照片 3D 倾转"几何同构（刚体旋转的 5 点投影相同）。CASIA 平面贴图的
       投影变换造不出这个信号，所以必须用数据集里的真实侧脸；
     - 照片歪着拿（整帧 ±10/15/20° 滚转）：量化平面滚转对 TURN_* 的残余风险
       （CR-021 已知限制：±15° 漂移 mean 0.030 / max 0.108，距阈值 0.12 仅 1σ）；
     - print-static 负例：静态照片（恒定帧）—— EYE_CLOSE/TURN_*/MOVE_* 应全部拒。

   ⚠️ 边界（报告内固定声明）：代理不过翻拍通道，检出质量按干净帧计 → 组件级
   APCER 是**上限估计**；EYE_CLOSE 无信号通道只有静态负例；replay-video 与
   全部 BPCER 必须真机采集后走 `--real`。

2. `--real <攻击根目录> <真人根目录>`：真机采集落盘评测（权威口径）。
   目录格式见协议 §5：`attacks/<attack-type>/<ACTION>/<case>/baseline.jpg +
   seq_*.jpg`，`bonafide/<ACTION>/<case>/...`。

共同口径：
  - 一次呈现 = 完整 start → frame×N → finish（与生产同链路、同阈值）；
  - 每场把基线帧特征注册进向量库（冒充目标身份），保证 match 门禁可判；
  - 组件级 APCER/BPCER 只看 actionCount 门禁，系统级看最终 passed；
  - java_client 打桩（评测机无 Java），CR-023 计数不落库。

输出：reports/active-liveness-<date>.md 与同名 .json。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import uuid
from datetime import date

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CASIA = os.getenv("CASIA_DIR", os.path.join(REPO_ROOT, "Cleaned_CASIA_FaceV5"))

from app import java_client                               # noqa: E402
from app.common import tickets                            # noqa: E402
from app.challenge.service import (                       # noqa: E402
    ChallengeService, DisguiseError, NoFace, TooManyFaces,
)
from app.challenge.store import ChallengeState            # noqa: E402
from app.config import settings                           # noqa: E402
from app.engine import (detector, embedder, load_all,     # noqa: E402
                        liveness, landmarker, optional_status)
from app.index.vectors import index                       # noqa: E402

ACTIONS = ("MOVE_CLOSER", "MOVE_AWAY", "EYE_CLOSE", "TURN_LEFT", "TURN_RIGHT")
FPS_NOTE = "帧序列按 ~3fps 服务端消化率设计：静止 3 帧 + 动作保持 5 帧（≥1s，hold=2 可达）"

# ---------------------------------------------------------------- 打桩与存储


class MemoryStore:
    """ChallengeStore 的进程内替身。接口与 ChallengeStore 完全一致，
    但直接持有活对象引用（不序列化），create 返回值即后续 get/save/consume 的同一实例。"""

    def __init__(self) -> None:
        self._live: dict[str, ChallengeState] = {}

    def create(self, session_id: int, student_id: int, ticket_jti: str,
               action: str, baseline_ratio: float, baseline_degraded: bool,
               baseline_scale: float = 0.0, baseline_eye: float = 0.0,
               baseline_yaw: float = 0.0, baseline_roll: float = 0.0) -> ChallengeState:
        st = ChallengeState(
            challenge_id=f"c_{uuid.uuid4().hex[:10]}",
            session_id=session_id, student_id=student_id, ticket_jti=ticket_jti,
            action=action, need=1, window_ms=3000,
            min_frames=settings.action.min_frames,
            created_ms=0,   # 内存 store 无 TTL，恒 0
            baseline_ratio=baseline_ratio, baseline_degraded=baseline_degraded,
            baseline_scale=baseline_scale, baseline_eye=baseline_eye,
            baseline_yaw=baseline_yaw, baseline_roll=baseline_roll,
        )
        self._live[st.challenge_id] = st
        return st

    def get(self, cid: str) -> ChallengeState:
        return self._live[cid]

    def save(self, st: ChallengeState) -> None:
        self._live[st.challenge_id] = st

    def consume(self, cid: str) -> ChallengeState:
        return self._live.pop(cid)

    def remaining_ms(self, st: ChallengeState) -> int:
        return st.window_ms


def stub_java() -> None:
    """评测机没有 Java。CR-023 计数不落库，commit 恒成功（match 通过的场）。"""
    java_client.checkin_attempt_status = lambda *a, **kw: {
        "failCount": 0, "retryLeft": 3, "blocked": False}
    java_client.record_checkin_failure = lambda *a, **kw: {
        "failCount": 1, "retryLeft": 2, "blocked": False}
    java_client.commit_checkin = lambda *a, **kw: {
        "attendanceId": 0, "status": "PRESENT", "checkinAt": None}


# ---------------------------------------------------------------- 完整链驱动


def run_challenge(baseline: np.ndarray, frames: list[np.ndarray],
                  action: str, student_id: int) -> dict:
    """一次完整呈现：注册基线特征 → start(hint 强制动作) → frame×N → finish。

    返回 {"finish": finish结果或None, "startError"/"abort": 原因, "frames": 实际喂入数}。
    """
    # 基线特征注册进向量库：冒充目标身份，match 门禁才有判可言（协议 §6）
    face = detector().detect_largest(baseline)
    if face is not None:
        emb = embedder().embedding(baseline, face.kps)
        if emb is not None:
            index().replace_all([(student_id, emb)])

    store = MemoryStore()
    service = ChallengeService(store)
    ticket = tickets.issue(session_id=1, student_id=student_id, jti="eval")

    try:
        info = service.start(ticket, baseline, action_hint=action)
    except (NoFace, TooManyFaces, DisguiseError) as exc:
        return {"finish": None, "startError": type(exc).__name__}
    except ValueError as exc:                       # hint 拼错等，评测脚本自己的错
        raise SystemExit(f"start 失败：{exc}") from exc

    sent = 0
    abort = None
    for i, fr in enumerate(frames):
        try:
            service.frame(info["challengeId"], fr, seq=i + 1)
            sent += 1
        except (NoFace, TooManyFaces, DisguiseError) as exc:
            abort = type(exc).__name__
            sent += 1
            break
    try:
        result = service.finish(info["challengeId"])
    except LookupError:
        result = None
    return {"finish": result, "abort": abort, "frames": sent,
            "action": info.get("action", action)}


# ---------------------------------------------------------------- 代理素材


def _jpeg90(frame: np.ndarray) -> np.ndarray:
    """过一遍上传编码（q90）再解码——服务端量的是解码后的这份（E2E 同口径）。"""
    ok, enc = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 90])
    return cv2.imdecode(enc, cv2.IMREAD_COLOR) if ok else frame


def _composite(img: np.ndarray, face_min_side: int, size=(480, 640),
               brightness: float = 1.0) -> np.ndarray:
    """把 CASIA 裁剪块按指定短边贴到灰底（近真机构图：脸占画面高 ~37%）。"""
    frame = np.full((size[0], size[1], 3), 60, dtype=np.uint8)
    scale = face_min_side / min(img.shape[:2])
    face = cv2.resize(img, (max(1, int(img.shape[1] * scale)),
                            max(1, int(img.shape[0] * scale))))
    if brightness != 1.0:
        face = np.clip(face.astype(np.float32) * brightness, 0, 255).astype(np.uint8)
    fh, fw = face.shape[:2]
    y0, x0 = (size[0] - fh) // 2, (size[1] - fw) // 2
    frame[y0:y0 + fh, x0:x0 + fw] = face
    return frame


def _rotate_frame(frame: np.ndarray, deg: float) -> np.ndarray:
    """整帧绕画面中心旋转，画布放大不裁边（裁脸角的旋转会污染关键点）。"""
    h, w = frame.shape[:2]
    diag = int(np.ceil(np.hypot(h, w)))
    canvas = cv2.copyMakeBorder(frame, (diag - h) // 2, (diag - h + 1) // 2,
                                (diag - w) // 2, (diag - w + 1) // 2,
                                cv2.BORDER_REPLICATE)
    m = cv2.getRotationMatrix2D((diag / 2, diag / 2), deg, 1.0)
    return cv2.warpAffine(canvas, m, (diag, diag), borderMode=cv2.BORDER_REPLICATE)


def _subjects(n: int) -> list[str]:
    return sorted(s for s in os.listdir(CASIA)
                  if os.path.isdir(os.path.join(CASIA, s)) and s.isdigit())[:n]


def _orig_images(subject: str) -> list[np.ndarray]:
    """该身份的原始图（排除变体），按文件名序。"""
    out = []
    folder = os.path.join(CASIA, subject)
    for f in sorted(os.listdir(folder)):
        stem = f[:-4]
        if f.endswith(".jpg") and len(stem.split("_")) == 2:
            img = cv2.imread(os.path.join(folder, f))
            if img is not None:
                out.append(img)
    return out


def pick_pose_pairs(subjects: list[str]) -> dict[str, dict[str, tuple[int, np.ndarray, np.ndarray]]]:
    """筛选姿态对：基线正面（|yaw|≤0.08 且左右留白足够）→ 目标侧脸（|Δyaw|≥0.20）。

    目标帧用的是 CASIA **真实 3D 侧脸**——照片 3D 倾转的几何同构代理
    （刚体旋转的 5 点投影与真人转头相同，平面贴图的投影变换造不出它）。
    返回 {subject: {"TURN_LEFT": (Δyaw, baseline_img, target_img), "TURN_RIGHT": ...}}。
    """
    from app.challenge.action import yaw_proxy
    pairs: dict[str, dict] = {}
    for s in subjects:
        imgs = _orig_images(s)
        if len(imgs) < 2:
            continue
        base_img = None
        base_yaw = None
        for img in imgs[:2]:
            fr = _composite(img, 180)
            fc = detector().detect_largest(fr)
            if fc is None:
                continue
            fw = float(fr.shape[1])
            if fc.bbox[0] < 0.04 * fw or fc.bbox[2] > 0.96 * fw:
                continue                      # 基线贴边不筛（turn 基线质量条件）
            y = yaw_proxy(fc)
            if abs(y) <= 0.08:
                base_img, base_yaw = img, y
                break
        if base_img is None:
            continue
        for img in imgs[1:]:
            fr = _composite(img, 180)
            fc = detector().detect_largest(fr)
            if fc is None:
                continue
            d = yaw_proxy(fc) - base_yaw
            slot = "TURN_LEFT" if d >= 0.20 else ("TURN_RIGHT" if d <= -0.20 else None)
            if slot and slot not in pairs.setdefault(s, {}):
                pairs[s][slot] = (round(d, 3), base_img, img)
    return pairs


def proxy_cases(subjects: list[str], roll_degrees: list[float]) -> list[dict]:
    """生成全部代理攻击场。每场 = {attack, action, expect, student_id, baseline, frames}。

    expect: "PASS" 预期动作门禁放行（几何同构通道可达性），"REJECT" 预期拒。
    """
    cases: list[dict] = []
    sid = 1000

    def add(attack, action, expect, base_img, moved_imgs):
        nonlocal sid
        sid += 1
        base = _jpeg90(_composite(base_img, 180))
        frames = [_jpeg90(m) for m in moved_imgs]
        cases.append({"attack": attack, "action": action, "expect": expect,
                      "student_id": sid, "baseline": base, "frames": frames})

    pose_pairs = pick_pose_pairs(subjects)

    for s in subjects:
        imgs = _orig_images(s)
        if not imgs:
            continue
        base_img = imgs[0]

        # --- print-move：照片物理靠近/远离的几何同构代理 ---
        closer = _composite(base_img, int(180 * 1.30))       # ≥18%+余量，"照片怼近"
        away = _composite(base_img, int(180 * 0.72))
        closer_weak = _composite(base_img, int(180 * 1.10))  # 挪了但不足 delta
        away_weak = _composite(base_img, int(180 * 0.90))
        still = [_composite(base_img, 180)] * 3
        add("print-move", "MOVE_CLOSER", "PASS", base_img, still + [closer] * 5)
        add("print-move", "MOVE_AWAY", "PASS", base_img, still + [away] * 5)
        add("print-move-weak", "MOVE_CLOSER", "REJECT", base_img,
            still + [closer_weak] * 5)
        add("print-move-weak", "MOVE_AWAY", "REJECT", base_img,
            still + [away_weak] * 5)

        # --- screen-tilt：照片 3D 倾转的几何同构代理（真实侧脸） ---
        for action in ("TURN_LEFT", "TURN_RIGHT"):
            pair = pose_pairs.get(s, {}).get(action)
            if pair:
                _, b_img, t_img = pair
                b = _jpeg90(_composite(b_img, 180))
                add("screen-tilt", action, "PASS", b_img,
                    [_composite(b_img, 180)] * 3 + [_composite(t_img, 180)] * 5)

        # --- print-roll：照片歪着拿（整帧滚转） ---
        for deg in roll_degrees:
            add("print-roll", "TURN_LEFT", "PASS", base_img,
                still + [_rotate_frame(_composite(base_img, 180), deg)] * 5)
            add("print-roll", "TURN_RIGHT", "PASS", base_img,
                still + [_rotate_frame(_composite(base_img, 180), -deg)] * 5)

        # --- print-static：无信号通道，全部动作应拒 ---
        for action in ACTIONS:
            add("print-static", action, "REJECT", base_img,
                [_composite(base_img, 180)] * 8)

    return cases


# ---------------------------------------------------------------- 真机落盘评测


def _load_case(case_dir: str) -> tuple[np.ndarray, list[np.ndarray]] | None:
    base_p = os.path.join(case_dir, "baseline.jpg")
    if not os.path.exists(base_p):
        return None
    base = cv2.imread(base_p)
    if base is None:
        return None
    frames = []
    for p in sorted(glob.glob(os.path.join(case_dir, "seq_*.jpg"))):
        img = cv2.imread(p)
        if img is not None:
            frames.append(img)
    return base, frames


def scan_real(root: str) -> list[dict]:
    """按协议 §5 扫描落盘目录。attacks/<type>/<ACTION>/<case> 与 bonafide/<ACTION>/<case>。"""
    cases: list[dict] = []
    sid = 2000
    for kind_dir, kind in ((os.path.join(root, "attacks"), "attack"),
                           (os.path.join(root, "bonafide"), "bonafide")):
        if not os.path.isdir(kind_dir):
            continue
        for type_p in sorted(glob.glob(os.path.join(kind_dir, "*"))):
            attack_type = "print-real" if kind == "bonafide" \
                else os.path.basename(type_p)
            for action_dir in sorted(glob.glob(os.path.join(type_p, "*"))):
                action = os.path.basename(action_dir)
                if action not in ACTIONS:
                    print(f"⚠️ 跳过未知动作目录：{action_dir}")
                    continue
                for case_dir in sorted(glob.glob(os.path.join(action_dir, "*"))):
                    loaded = _load_case(case_dir)
                    if loaded is None:
                        print(f"⚠️ 跳过缺 baseline.jpg/空帧的 case：{case_dir}")
                        continue
                    sid += 1
                    cases.append({"kind": kind, "attack": attack_type,
                                  "action": action, "student_id": sid,
                                  "baseline": loaded[0], "frames": loaded[1],
                                  "case": os.path.basename(case_dir)})
    return cases


# ---------------------------------------------------------------- 统计与报告


def summarize(runs: list[dict], label: str, kind: str) -> list[dict]:
    """按 (attack, action) 聚合。kind=attack → APCER；bonafide → BPCER。"""
    rows: dict[tuple[str, str], list[dict]] = {}
    for r in runs:
        rows.setdefault((r["attack"], r["action"]), []).append(r)
    out = []
    for (attack, action), items in sorted(rows.items()):
        total = len(items)
        comp_pass = sum(1 for r in items
                        if r["finish"] and r["finish"]["gates"].get("actionCount", {})
                        .get("passed"))
        sys_pass = sum(1 for r in items if r["finish"] and r["finish"]["passed"])
        if kind == "attack":
            rate_c, rate_s = comp_pass / total, sys_pass / total
        else:
            rate_c, rate_s = (total - comp_pass) / total, (total - sys_pass) / total
        out.append({"attack": attack, "action": action, "total": total,
                    "component_pass": comp_pass, "system_pass": sys_pass,
                    "component_rate": round(rate_c, 4),
                    "system_rate": round(rate_s, 4),
                    "expect_note": (f"{items[0].get('expect', '-')}"
                                    f"（{sum(1 for r in items if r.get('expect') == 'PASS')}"
                                    f"/{total} 场）") if kind == "attack"
                    and items[0].get("expect") else None})
    return out


def write_report(mode: str, meta: dict, runs: list[dict], out_dir: str) -> str:
    """md + json。口径声明按协议 §8 固定写入，防止数字被脱离边界引用。"""
    os.makedirs(out_dir, exist_ok=True)
    today = date.today().isoformat()
    attacks = summarize([r for r in runs if r["kind"] == "attack"], "attacks", "attack")
    bonafide = summarize([r for r in runs if r["kind"] == "bonafide"], "bonafide", "bonafide")

    threshold_note = {
        "scale_delta": settings.action.scale_delta,
        "scale_hold_frames": settings.action.scale_hold_frames,
        "eye_dark_drop": settings.action.eye_dark_drop,
        "eye_hold_frames": settings.action.eye_hold_frames,
        "turn_delta": settings.action.turn_delta,
        "turn_hold_frames": settings.action.turn_hold_frames,
        "min_frames": settings.action.min_frames,
    }

    payload = {"mode": mode, "date": today, "meta": meta,
               "thresholds": threshold_note,
               "model_status": optional_status(),
               "attacks": attacks, "bonafide": bonafide,
               "runs": [{k: v for k, v in r.items() if k in
                         ("kind", "attack", "action", "expect", "student_id",
                          "frames", "abort", "startError", "case")}
                        | {"finish": r["finish"]} for r in runs]}

    json_path = os.path.join(out_dir, f"active-liveness-{today}.json")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1, default=str)

    L = ["# 主动活体（动作挑战）APCER/BPCER 评测报告",
         "",
         f"- 日期：{today} · 模式：**{mode}** · 协议：docs/verification/active-liveness-protocol.md",
         f"- 阈值：{threshold_note}",
         f"- 模型就绪：{optional_status()}（liveness=False 时系统级 APCER 恒 0，只看组件级）",
         ""]
    if mode == "proxy":
        L += [
            "> **口径声明（协议 §8）**：代理素材不过翻拍通道，检出质量按干净帧计 →",
            "> 组件级 APCER 是**上限估计**。EYE_CLOSE 无信号通道（只有静态负例）、",
            "> replay-video 与全部 BPCER 必须真机采集后用 `--real` 复测。本报告不替代真机验收。",
            ""]
    if attacks:
        L += ["## 攻击侧（APCER）", "",
              "| 攻击类型 | 动作 | 场数 | 组件级放行 | 组件级 APCER | 系统级放行 | 预期 |",
              "|---|---|---|---|---|---|---|"]
        for r in attacks:
            L.append(f"| {r['attack']} | {r['action']} | {r['total']} | "
                     f"{r['component_pass']} | {r['component_rate']:.2f} | "
                     f"{r['system_pass']} | {r.get('expect_note', '-')} |")
        L.append("")
    if bonafide:
        L += ["## 真人侧（BPCER）", "",
              "| 动作 | 场数 | 组件级误拒 | 组件级 BPCER | 系统级误拒 |",
              "|---|---|---|---|---|"]
        for r in bonafide:
            L.append(f"| {r['action']} | {r['total']} | {r['component_pass']} | "
                     f"{r['component_rate']:.2f} | {r['system_pass']} |")
        L.append("")
    L += ["## 明细（逐场）", "",
          "字段：expect=PASS/REJECT 为代理预期；finish 为生产 finish 原始结果。", "",
          "```json"]
    for r in runs:
        fin = r["finish"]
        L.append(json.dumps({"attack": r["attack"], "action": r["action"],
                             "expect": r.get("expect"), "sid": r["student_id"],
                             "abort": r.get("abort"),
                             "rejectGate": fin and fin.get("rejectGate"),
                             "actionCount": fin and fin["gates"].get("actionCount"),
                             "passed": fin and fin.get("passed")},
                            ensure_ascii=False))
    L += ["```", ""]
    md_path = os.path.join(out_dir, f"active-liveness-{today}.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L))
    print(f"报告已写入 {md_path}")
    return md_path


# ---------------------------------------------------------------- 入口


def run_proxy(persons: int, roll_degrees: list[float], out_dir: str) -> int:
    load_all()
    subjects = _subjects(persons)
    print(f"代理评测：{len(subjects)} 身份；滚转档位 {roll_degrees}°；{FPS_NOTE}")
    cases = proxy_cases(subjects, roll_degrees)
    print(f"共 {len(cases)} 场呈现，开始逐场执行……")
    runs: list[dict] = []
    for i, c in enumerate(cases, 1):
        res = run_challenge(c["baseline"], c["frames"], c["action"], c["student_id"])
        runs.append({"kind": "attack", "attack": c["attack"], "action": c["action"],
                     "expect": c["expect"], "student_id": c["student_id"], **res})
        fin = res["finish"]
        mark = "?" if fin is None else ("✓" if fin["passed"] else "✗")
        gate = fin and fin["gates"].get("actionCount", {}).get("passed")
        print(f"  [{i}/{len(cases)}] {c['attack']:16s} {c['action']:11s} "
              f"expect={c['expect']:6s} actionGate={'PASS' if gate else 'REJ'} "
              f"system={mark} abort={res.get('abort')}")
    write_report("proxy", {"persons": len(subjects), "roll_degrees": roll_degrees},
                 runs, out_dir)
    return 0


def run_real(attack_root: str, bona_root: str, out_dir: str) -> int:
    load_all()
    cases = scan_real(attack_root) + scan_real(bona_root)
    if not cases:
        print("没有可用素材。目录格式见协议 §5。")
        return 2
    print(f"真机评测：共 {len(cases)} 场呈现，开始逐场执行……")
    runs: list[dict] = []
    for i, c in enumerate(cases, 1):
        res = run_challenge(c["baseline"], c["frames"], c["action"], c["student_id"])
        runs.append({"kind": c["kind"], "attack": c["attack"], "action": c["action"],
                     "student_id": c["student_id"], "case": c["case"], **res})
        fin = res["finish"]
        print(f"  [{i}/{len(cases)}] {c['kind']:8s} {c['attack']:12s} "
              f"{c['action']:11s} rejectGate={fin and fin.get('rejectGate')}")
    write_report("real", {"attack_root": attack_root, "bona_root": bona_root},
                 runs, out_dir)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="主动活体 APCER/BPCER 评测")
    ap.add_argument("--proxy", action="store_true", help="几何同构代理评测（本机可跑）")
    ap.add_argument("--real", nargs=2, metavar=("ATTACK_ROOT", "BONA_ROOT"),
                    help="真机落盘评测（权威口径，目录格式见协议 §5）")
    ap.add_argument("--persons", type=int, default=12, help="代理评测身份数")
    ap.add_argument("--roll-degrees", default="10,15,20",
                    help="照片歪着拿的滚转档位，逗号分隔")
    ap.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "reports"))
    args = ap.parse_args()

    # 评测需要强制指定动作（绕过随机抽签与可做性剔除）——CR-010 的验收开关
    settings.action.allow_action_hint = True
    stub_java()
    rolls = [float(x) for x in args.roll_degrees.split(",") if x.strip()]

    if args.real:
        return run_real(args.real[0], args.real[1], args.out)
    if args.proxy:
        return run_proxy(args.persons, rolls, args.out)
    ap.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
