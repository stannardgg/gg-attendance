"""串行筛选压测签到池：找出「合成 MOVE_CLOSER 素材活体分合格」的 CASIA 学生。

为什么需要：活体门禁（0.55）对合成素材的响应因源图而异（串行实测 C3 过、
C0/C8/C9 拒，match 全部 0.95+ 通过）。压测要压吞吐，得先把素材关过了，
池子筛出来给 bench5000.py 用。

一场 session 串行跑完全部 ACTIVE 学生，每人 1 条（失败计数不会触顶）。
输出 /out/bench_pool.json：{"pass": [...], "reject": {student: gate}}

用法（e2e 容器）：python /e2e/bench_pool.py
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, "/e2e")

import flows  # noqa: E402
from bench5000 import CASIAT_CLASS_ID, CASIAT_COURSE_ID, CASIA_TEACHER, OUT  # noqa: E402
from e2e_client import Client  # noqa: E402

BASE = os.environ.get("E2E_BASE", "https://nginx")
FACE = os.environ.get("E2E_FACE", "http://face:8000")
ADMIN = os.environ.get("E2E_ADMIN_USER", "admin")
PWD = os.environ.get("E2E_ADMIN_PWD", "")

# CASIAT 班 face ACTIVE 学生（2026-11-23 查库所得，60 人）
POOL = [
    "C0", "C3", "C8", "C9", "C10", "C11", "C12", "C13", "C14", "C15",
    "C16", "C19", "C20", "C22", "C24", "C25", "C26", "C27", "C31", "C33",
    "C34", "C35", "C36", "C37", "C43", "C44", "C46", "C48", "C49", "C51",
    "C55", "C56", "C58", "C59", "C60", "C61", "C63", "C64", "C70", "C71",
    "C73", "C76", "C77", "C78", "C79", "C80", "C81", "C82", "C83", "C84",
    "C86", "C88", "C89", "C91", "C92", "C93", "C94", "C96", "C98", "C99",
]


def main() -> int:
    admin = Client(BASE, flows.login(BASE, ADMIN, PWD))
    teacher = Client(BASE, flows.login(BASE, CASIA_TEACHER, flows.TEACHER_PWD))
    # 每场最多 30 人（duration 30 分钟），分两场跑完 60 人
    sessions = [flows.start_session(teacher, CASIAT_CLASS_ID, CASIAT_COURSE_ID,
                                    f"POOL{i}", duration=30)["id"]
                for i in range(2)]
    tokens = {}
    passed, reject = [], {}
    t0 = time.perf_counter()
    for i, no in enumerate(POOL):
        sid = sessions[i // 30]
        if no not in tokens:
            try:
                tokens[no] = flows.login(BASE, no, flows.STUDENT_PWD)
            except AssertionError as e:
                reject[no] = f"login {e}"
                continue
        client = Client(BASE, tokens[no])
        j = flows.join(client, sid)
        if j.status != 200:
            reject[no] = f"join {j.code}"
            continue
        base, frames, _ = flows.checkin_frames_scale(f"{int(no[1:]):03d}", closer=True)
        res = flows.run_challenge(Client(FACE), j.data["checkinTicket"], frames,
                                  action="MOVE_CLOSER", baseline=base)
        if res.get("passed") is True and res.get("attendanceStatus") in ("PRESENT", "LATE"):
            passed.append(no)
            print(f"{no} PASS")
        else:
            reject[no] = res.get("rejectGate") or str(res)[:80]
            print(f"{no} REJECT {reject[no]}")
    wall = time.perf_counter() - t0
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "bench_pool.json"), "w", encoding="utf-8") as fh:
        json.dump({"pass": passed, "reject": reject,
                   "wallSeconds": round(wall, 1)}, fh, ensure_ascii=False, indent=2)
    print(f"\npool={len(passed)}/{len(POOL)}  wall={wall:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
