"""5000+ 学生规模压测（bench5000）。

在 e2e 容器内运行（e2e 容器挂载 /e2e 与 /out）：
  # 基线（造数前，向量库 ~1.1k 条）
  docker compose --env-file .env --profile test run --rm -T e2e \
      python /e2e/bench5000.py --mode baseline
  # 全量（造数后，向量库 ~6.1k 条 + 5000 学生）
  docker compose --env-file .env --profile test run --rm -T e2e \
      python /e2e/bench5000.py --mode full

场景（full = S1..S5，baseline 只跑 S1）：
  S1 签到全链路：CASIAT 班真实学生（C0..C11，face ACTIVE、向量真实）
     join → 挑战 → 8 帧 → finish，多场会话真并发（同一班可开多场，互不冲突）
  S2 登录并发：B5K 压测账号（5000 账号库规模）
  S3 学生读路径：attendance/history 与 sessions/active 并发
  S4 大班开场写路径：STRESS5K 班（5000 应到）开 session 的 API 耗时
  S5 教师大查询：attendance 分页列表 + export xlsx（5000 行）

口径：
  - S1 延迟 = 单条签到端到端（join+start+8 帧+finish），不是单次 HTTP
  - 只统计「链路走完」的样本算延迟；失败/被门禁拒的样本单独归因
  - framesMs = 每帧 POST /face-api/challenge/{cid}/frame 的服务端往返
    （含检测+活体特征+ArcFace 提取，即每帧的真实算力负载）
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time

sys.path.insert(0, "/e2e")

import flows  # noqa: E402
import synth  # noqa: E402
from e2e_client import ApiError, Client  # noqa: E402

BASE = os.environ.get("E2E_BASE", "https://nginx")
FACE = os.environ.get("E2E_FACE", "http://face:8000")
ADMIN = os.environ.get("E2E_ADMIN_USER", "admin")
PWD = os.environ.get("E2E_ADMIN_PWD", "")
OUT = os.environ.get("E2E_OUT", "/out")
STUDENT_PWD = flows.STUDENT_PWD
TEACHER_PWD = flows.TEACHER_PWD

CASIAT_CLASS_ID = 29          # seed_casia 造的可签到班（C0..C59）
CASIAT_COURSE_ID = 29         # teacher 38 (E2T841234) 已有 COURSE 29 授权
CASIA_TEACHER = "E2T841234"
# 压测签到池：串行筛选（bench_pool.py）通过的 14 人——合成素材活体分合格
CHECKIN_STUDENTS = ["C3", "C10", "C11", "C12", "C16", "C27", "C35",
                    "C60", "C61", "C71", "C86", "C88", "C91", "C98"]
STRESS_CLASS = "STRESS5K"     # 造数 SQL 产生的班/课/教师编码
STRESS_COURSE = "STRESS5K"
STRESS_TEACHER = "STRESS_T"


def pct(sorted_ms: list[float], p: float) -> float:
    if not sorted_ms:
        return 0.0
    k = min(len(sorted_ms) - 1, int(round(p / 100 * (len(sorted_ms) - 1))))
    return round(sorted_ms[k], 1)


def stat(ms_list: list[float]) -> dict:
    if not ms_list:
        return {"n": 0}
    s = sorted(ms_list)
    return {
        "n": len(s),
        "p50": pct(s, 50), "p95": pct(s, 95), "max": s[-1],
        "mean": round(statistics.mean(s), 1),
    }


def timed_get(client: Client, path: str) -> tuple[float, object]:
    t0 = time.perf_counter()
    r = client.get(path)
    ms = (time.perf_counter() - t0) * 1000
    return ms, r


# ---------------------------------------------------------------- 挑战（计帧耗时版）
def run_challenge_timed(face_base: Client, ticket: str, frames: list,
                        action: str, baseline) -> dict:
    base = synth.b64(baseline if baseline is not None else frames[0])
    body = {"baselineFrame": base, "deviceHash": "e2e", "action": action}
    t0 = time.perf_counter()
    start = face_base.post("/face-api/challenge/start", body, token=ticket)
    start_ms = (time.perf_counter() - t0) * 1000
    if start.status != 200:
        return {"error": f"start {start.status}: {start.code}", "timing": {}}
    cid = start.data["challengeId"]
    fms: list[float] = []
    last = None
    for i, f in enumerate(frames):
        t1 = time.perf_counter()
        last = face_base.request(
            "POST", f"/face-api/challenge/{cid}/frame", token=ticket,
            form={"seq": str(i)},
            files={"frame": ("f.jpg", synth.jpeg(f), "image/jpeg")})
        fms.append(round((time.perf_counter() - t1) * 1000, 1))
        if last.status != 200:
            break
    t2 = time.perf_counter()
    fin = face_base.post(f"/face-api/challenge/{cid}/finish", {}, token=ticket)
    fin_ms = (time.perf_counter() - t2) * 1000
    if fin.status != 200:
        return {"error": f"finish {fin.status}: {fin.code}",
                "timing": {"startMs": round(start_ms, 1), "framesMs": fms,
                           "finishMs": round(fin_ms, 1)}}
    out = dict(fin.data)
    out["_timing"] = {"startMs": round(start_ms, 1), "framesMs": fms,
                      "finishMs": round(fin_ms, 1)}
    return out


# ---------------------------------------------------------------- S1 签到
def one_checkin(face: Client, student: dict, sid: int) -> dict:
    t0 = time.perf_counter()
    try:
        j = flows.join(student["client"], sid)
        if j.status != 200:
            return {"ok": False, "ms": 0, "why": f"join {j.code}", "gate": None}
        base, frames, _ = flows.checkin_frames_scale(student["person"], closer=True)
        res = run_challenge_timed(face, j.data["checkinTicket"], frames,
                                  "MOVE_CLOSER", base)
        ms = (time.perf_counter() - t0) * 1000
        if res.get("error"):
            return {"ok": False, "ms": ms, "why": res["error"], "gate": None,
                    "timing": res.get("timing", {})}
        passed = res.get("passed") is True
        gate = res.get("rejectGate")
        return {"ok": passed and res.get("attendanceStatus") in ("PRESENT", "LATE"),
                "ms": ms, "why": None if passed else f"rejectGate={gate}",
                "gate": gate, "timing": res.get("_timing", {})}
    except Exception as exc:                                 # noqa: BLE001
        return {"ok": False, "ms": 0,
                "why": f"{type(exc).__name__}: {exc}"[:160], "gate": None}


def s1_checkin(admin: Client, rounds: int, students_no: list[str]) -> dict:
    # 教师：补 CLASS 授权（幂等：已存在的授权报 FA-2002/409，忽略）
    tid = 38
    try:
        flows.grant_scope(admin, tid, "CLASS", CASIAT_CLASS_ID)
    except ApiError as e:
        if "FA-2002" not in str(e):
            raise
    t_login = Client(BASE, flows.login(BASE, CASIA_TEACHER, TEACHER_PWD))

    # 学生登录（含身份素材目录映射：C0 → 000）
    students = []
    login_fail = []
    for no in students_no:
        try:
            token = flows.login(BASE, no, STUDENT_PWD)
        except AssertionError:
            login_fail.append(no)
            continue
        students.append({"no": no, "person": f"{int(no[1:]):03d}",
                         "client": Client(BASE, token)})
    if login_fail:
        return {"skipped": True, "loginFail": login_fail,
                "note": "部分 CASIA 学生登录失败，请先统一密码后重跑"}

    # 多场会话（同一班可开多场：每场 expected 只包含班上学生，签到互不冲突）
    sessions = [flows.start_session(t_login, CASIAT_CLASS_ID, CASIAT_COURSE_ID,
                                    f"BENCH{i:02d}", duration=30)
                for i in range(rounds)]
    tasks = [(st, s) for s in sessions for st in students]

    t0 = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        futs = [pool.submit(one_checkin, Client(FACE), st, s["id"])
                for st, s in tasks]
        results = [f.result() for f in futs]
    wall = time.perf_counter() - t0

    oks = [r for r in results if r["ok"]]
    done = [r for r in results if not (r.get("why") or "").startswith(("join", "start", "finish"))]
    lat = sorted(r["ms"] for r in done)
    frame_ms = [m for r in done for m in r.get("timing", {}).get("framesMs", [])]
    finish_ms = [r["timing"]["finishMs"] for r in done
                 if r.get("timing", {}).get("finishMs")]
    gates: dict[str, int] = {}
    for r in results:
        if r["gate"]:
            gates[r["gate"]] = gates.get(r["gate"], 0) + 1
    return {
        "tasks": len(tasks),
        "wallSeconds": round(wall, 1),
        "success": len(oks),
        "completed": len(done),
        "errorRate": round(1 - len(oks) / len(results), 4) if results else 1,
        "e2e": stat(lat),
        "perFrame": stat(frame_ms),
        "finishCall": stat(finish_ms),
        "throughputPerSec": round(len(done) / wall, 2) if wall else 0,
        "rejectGates": gates,
        "sampleFailures": [r["why"] for r in results if not r["ok"]][:5],
    }


# ---------------------------------------------------------------- S2 登录
def s2_login() -> dict:
    def one(i: int) -> float:
        t0 = time.perf_counter()
        flows.login(BASE, f"B5K{i:04d}", STUDENT_PWD)
        return (time.perf_counter() - t0) * 1000

    t0 = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        futs = [pool.submit(one, i) for i in range(1, 201)]
        ms = [f.result() for f in futs]
    wall = time.perf_counter() - t0
    return {"count": len(ms), "concurrency": 50, "wallSeconds": round(wall, 1),
            "latency": stat(ms),
            "throughputPerSec": round(len(ms) / wall, 2)}


# ---------------------------------------------------------------- S3 读路径
def s3_read() -> dict:
    tokens = []
    for i in range(1, 101):
        tokens.append(flows.login(BASE, f"B5K{i:04d}", STUDENT_PWD))
    hist_ms, active_ms = [], []

    def one(idx: int):
        c = Client(BASE, tokens[idx % len(tokens)])
        ms1, r1 = timed_get(c, "/api/v1/mobile/attendance/history?page=1&size=10")
        ms2, r2 = timed_get(c, "/api/v1/mobile/sessions/active")
        return ms1, r1.status, ms2, r2.status

    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        futs = [pool.submit(one, i) for i in range(200)]
        for f in futs:
            ms1, st1, ms2, st2 = f.result()
            if st1 == 200:
                hist_ms.append(ms1)
            if st2 == 200:
                active_ms.append(ms2)
    return {
        "history": stat(hist_ms), "sessionsActive": stat(active_ms),
        "non200": {"history": 200 - len(hist_ms), "sessionsActive": 200 - len(active_ms)},
    }


# ---------------------------------------------------------------- S4/S5 大班开场与大查询
def s4_open_session(admin: Client) -> dict:
    """开 STRESS5K 班的 session：Java 侧插 5000 行 session_expected。"""
    t_client = Client(BASE, flows.login(BASE, STRESS_TEACHER, TEACHER_PWD))
    # 班/课 id 通过 admin 列表查（分页结构 data.items）
    r = admin.get("/api/v1/admin/classes?page=1&size=200")
    cid = next(c["id"] for c in r.data["items"] if c["code"] == STRESS_CLASS)
    r = admin.get("/api/v1/admin/courses?page=1&size=200")
    kid = next(c["id"] for c in r.data["items"] if c["code"] == STRESS_COURSE)

    t0 = time.perf_counter()
    sess = flows.start_session(t_client, cid, kid, "BENCH-5000大班", duration=30)
    open_ms = (time.perf_counter() - t0) * 1000
    return {"sessionId": sess["id"], "expectedCount": sess.get("expectedCount"),
            "openMs": round(open_ms, 1)}


def s5_large_query(admin: Client, session_id: int) -> dict:
    page_ms = []
    for _ in range(3):
        ms, r = timed_get(admin, f"/api/v1/admin/attendance?sessionId={session_id}&page=1&size=50")
        if r.status == 200:
            page_ms.append(ms)
    page_all_ms = []
    for _ in range(3):
        ms, r = timed_get(admin, f"/api/v1/admin/attendance?sessionId={session_id}&page=1&size=500")
        if r.status == 200:
            page_all_ms.append(ms)

    t0 = time.perf_counter()
    r = admin.get(f"/api/v1/admin/attendance/export?sessionId={session_id}")
    export_ms = (time.perf_counter() - t0) * 1000
    export_bytes = 0
    if r.status == 200:
        try:
            path = os.path.join(OUT, "bench_export_5000.xlsx")
            os.makedirs(OUT, exist_ok=True)
            with open(path, "wb") as fh:
                fh.write(r.raw)
            export_bytes = len(r.raw)
        except OSError:
            pass
    return {
        "page50": stat(page_ms), "page500": stat(page_all_ms),
        "export": {"ms": round(export_ms, 1), "status": r.status,
                   "bytes": export_bytes},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["baseline", "full"], required=True)
    ap.add_argument("--concurrency-list", default="8,16,30")
    args = ap.parse_args()

    admin = Client(BASE, flows.login(BASE, ADMIN, PWD))
    report: dict = {"mode": args.mode, "timestamp": time.strftime("%F %T")}

    # S1 签到（baseline 与 full 都跑；full 跑两个并发档）
    conclist = [int(x) for x in args.concurrency_list.split(",")] \
        if args.mode == "full" else [30]
    s1 = {}
    for c in conclist:
        rounds = max(1, c // 10)
        stu = CHECKIN_STUDENTS[:c // rounds] if c // rounds <= 12 else CHECKIN_STUDENTS
        # 并发数 = rounds 场 × 每场 stu 人数，凑到接近 c
        s1[f"c{len(stu) * rounds}"] = s1_checkin(admin, rounds, stu)
    report["S1_checkin"] = s1

    if args.mode == "full":
        report["S2_login"] = s2_login()
        report["S3_read"] = s3_read()
        try:
            s4 = s4_open_session(admin)
            report["S4_openSession"] = s4
            report["S5_largeQuery"] = s5_large_query(admin, s4["sessionId"])
            # 关闭大班 session，避免长期占用
            admin.post(f"/api/v1/admin/sessions/{s4['sessionId']}/close", {})
        except Exception as exc:                             # noqa: BLE001
            report["S4_openSession"] = {"error": f"{type(exc).__name__}: {exc}"[:200]}

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"bench_{args.mode}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
