"""30 并发签到压测（WP-13）。

为什么可以真并发签到：一场会话里每个学生只能签一次，但**同一班可以开多场**。
开 10 场会话 × 3 名学生 = 30 条互不冲突的签到流水，每一条都要走完整的
进入签到 → 挑战 → 8 帧上传 → finish → 落库，这才压到了真实链路的每一段。

口径：
  - 延迟 = 单条签到的端到端耗时（join + 挑战 + 8 帧 + finish），不是单次 HTTP
  - 失败率 = 未拿到 PRESENT/LATE 的比例（含 HTTP 错、门禁拒、落库失败）
  - 只统计「全链路成功」的延迟分布，避免把快速失败的样本混进来美化 P95
  - 帧直接打 http://face:8000（与验收套件一致），业务请求走 nginx

用法（在 deploy/ 下）：
  docker compose --env-file .env --profile test run --rm -T e2e \
      python /e2e/loadtest.py --concurrency 30
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time
import uuid

sys.path.insert(0, "/e2e")

import flows  # noqa: E402
import synth  # noqa: E402
from e2e_client import Client  # noqa: E402

BASE = os.environ.get("E2E_BASE", "https://nginx")
JAVA = os.environ.get("E2E_JAVA", "http://java:8080")
FACE = os.environ.get("E2E_FACE", "http://face:8000")
ADMIN = os.environ.get("E2E_ADMIN_USER", "admin")
PWD = os.environ.get("E2E_ADMIN_PWD", "")
OUT = os.environ.get("E2E_OUT", "/out")
STUDENT_PWD = flows.STUDENT_PWD


def one_checkin(face: Client, student: dict, sid: int) -> dict:
    t0 = time.perf_counter()
    try:
        j = flows.join(student["client"], sid)
        if j.status != 200:
            return {"ok": False, "ms": 0, "why": f"join {j.code}"}
        # 造一套「靠近」序列并显式指定 MOVE_CLOSER（CR-018 后动作库只剩尺度类）：
        # 压测量的是吞吐与并发，不能让「随机抽到另一个动作」被记成「系统失败」。
        base, frames, _ = flows.checkin_frames_scale(student["person"], closer=True)
        res = flows.run_challenge(face, j.data["checkinTicket"], frames,
                                  action="MOVE_CLOSER", baseline=base)
        ms = (time.perf_counter() - t0) * 1000
        ok = res.get("passed") is True and res.get("attendanceStatus") in ("PRESENT", "LATE")
        return {"ok": ok, "ms": ms, "why": None if ok else str(res)[:160]}
    except Exception as exc:                                 # noqa: BLE001
        return {"ok": False, "ms": 0, "why": f"{type(exc).__name__}: {exc}"[:160]}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--concurrency", type=int, default=30)
    ap.add_argument("--per-session", type=int, default=3)
    args = ap.parse_args()

    sfx = uuid.uuid4().hex[:6]
    admin = Client(BASE, flows.login(BASE, ADMIN, PWD))
    cid = flows.create_class(admin, f"LD{sfx}", f"压测班{sfx}")
    course = flows.create_course(admin, f"LDK{sfx.upper()}")
    tid = flows.create_teacher(admin, f"LDT{sfx}")
    flows.grant_scope(admin, tid, "CLASS", cid)
    flows.grant_scope(admin, tid, "COURSE", course)
    teacher = Client(BASE, flows.login(BASE, f"LDT{sfx}", flows.TEACHER_PWD))

    # N 名学生（复用验收的身份轮换 candidates()，避免 DUPLICATE）
    sys.path.insert(0, "/e2e")
    import conftest  # noqa: E402
    import itertools
    persons = list(itertools.islice(conftest.candidates(args.per_session), args.per_session))
    if len(persons) < args.per_session:
        print(f"可用身份不足：需要 {args.per_session} 个，候选只有 {len(persons)} 个")
        return 1
    students = []
    for i, person in enumerate(persons, start=1):
        no = f"LD{sfx}S{i}"
        sid = flows.create_student(admin, no, f"压测学生{i}", cid)
        flows.activate(BASE, no, f"压测学生{i}", f"LD{sfx}")
        client = Client(BASE, flows.login(BASE, no, STUDENT_PWD))
        flows.enroll_face(Client(FACE), client, person, synth.person_images(person))
        flows.approve_face(admin, sid)
        students.append({"id": sid, "no": no, "person": person, "client": client})
    flows.wait_vector_sync()

    rounds = max(1, args.concurrency // args.per_session)
    sessions = [flows.start_session(teacher, cid, course, f"压测{i:02d}", duration=30)
                for i in range(rounds)]

    tasks = [(st, s) for s in sessions for st in students][:args.concurrency]
    print(f"并发 {len(tasks)} 条（{len(sessions)} 场 × {len(students)} 人），"
          f"face worker={'?'} ...")

    t0 = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        futs = [pool.submit(one_checkin, Client(FACE), st, s["id"])
                for st, s in tasks]
        results = [f.result() for f in futs]
    total = time.perf_counter() - t0

    oks = [r for r in results if r["ok"]]
    lat = sorted(r["ms"] for r in oks)
    def pct(p: float) -> float:
        if not lat:
            return 0.0
        k = min(len(lat) - 1, int(round(p / 100 * (len(lat) - 1))))
        return round(lat[k], 1)

    summary = {
        "concurrency": len(tasks),
        "wallSeconds": round(total, 1),
        "success": len(oks),
        "failed": len(results) - len(oks),
        "errorRate": round((len(results) - len(oks)) / len(results), 4),
        "p50ms": pct(50), "p95ms": pct(95), "maxMs": pct(100),
        "meanMs": round(statistics.mean(lat), 1) if lat else 0,
        "throughputPerSec": round(len(oks) / total, 2) if total else 0,
        "sampleFailures": [r["why"] for r in results if not r["ok"]][:5],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    try:
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, "loadtest.json"), "w", encoding="utf-8") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
    except OSError:
        pass
    return 0 if summary["errorRate"] <= 0.01 else 1


if __name__ == "__main__":
    raise SystemExit(main())
