"""串行对照：单线程签到，验证「liveness 拒」是并发排队时序问题还是素材问题。

用法（e2e 容器）：
  python /e2e/bench_solo.py            # C0/C3/C8/C9 各跑 1 条（新开 1 场）
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, "/e2e")

import flows  # noqa: E402
from bench5000 import CASIAT_CLASS_ID, CASIAT_COURSE_ID, CASIA_TEACHER  # noqa: E402
from e2e_client import Client  # noqa: E402

BASE = os.environ.get("E2E_BASE", "https://nginx")
FACE = os.environ.get("E2E_FACE", "http://face:8000")
ADMIN = os.environ.get("E2E_ADMIN_USER", "admin")
PWD = os.environ.get("E2E_ADMIN_PWD", "")


def one_checkin(face: Client, client: Client, person: str, sid: int) -> dict:
    j = flows.join(client, sid)
    if j.status != 200:
        return {"ok": False, "why": f"join {j.code}"}
    base, frames, _ = flows.checkin_frames_scale(person, closer=True)
    res = flows.run_challenge(face, j.data["checkinTicket"], frames,
                              action="MOVE_CLOSER", baseline=base)
    return {"ok": res.get("passed") is True,
            "rejectGate": res.get("rejectGate"),
            "gates": {k: v for k, v in (res.get("gates") or {}).items()
                      if k in ("liveness", "match")},
            "status": res.get("attendanceStatus")}


def main() -> int:
    admin = Client(BASE, flows.login(BASE, ADMIN, PWD))
    teacher = Client(BASE, flows.login(BASE, CASIA_TEACHER, flows.TEACHER_PWD))
    sess = flows.start_session(teacher, CASIAT_CLASS_ID, CASIAT_COURSE_ID,
                               "SOLO验证", duration=15)
    sid = sess["id"]
    print(f"session={sid} expected={sess.get('expectedCount')}")
    out = []
    for no in ("C0", "C3", "C8", "C9"):
        token = flows.login(BASE, no, flows.STUDENT_PWD)
        r = one_checkin(Client(FACE), Client(BASE, token), f"{int(no[1:]):03d}", sid)
        out.append({"student": no, **r})
        print(no, "OK" if r["ok"] else f"REJECT {r.get('rejectGate')}",
              r.get("gates"))
    import json
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
