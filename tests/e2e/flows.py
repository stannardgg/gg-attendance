"""业务动作封装：把「建班/建课/开签到/进入签到/挑战」这些流程写成函数。

测试用例只写断言，不重复拼 URL——URL 一改，改一处就够。
所有路径以契约 contracts/openapi/*.yaml 为准。
"""
from __future__ import annotations

import time

import synth
from e2e_client import Client

STUDENT_PWD = "Student12345"
TEACHER_PWD = "Teacher12345"


# ---------------- 组织数据 ----------------
def create_class(admin: Client, code: str, name: str) -> int:
    return int(admin.ok("/api/v1/admin/classes", {"code": code, "name": name}).data["id"])


def create_course(admin: Client, code: str, name: str = "高等数学") -> int:
    return int(admin.ok("/api/v1/admin/courses", {"code": code, "name": name}).data["id"])


def create_teacher(admin: Client, no: str, name: str = "验收教师") -> int:
    return int(admin.ok("/api/v1/admin/teachers",
                        {"teacherNo": no, "name": name, "password": TEACHER_PWD}).data["id"])


def grant_scope(admin: Client, teacher_id: int, scope_type: str, scope_id: int) -> None:
    admin.ok("/api/v1/admin/teacher-scopes",
             {"teacherId": teacher_id, "scopeType": scope_type, "scopeId": scope_id})


def create_student(admin: Client, no: str, name: str, class_id: int) -> int:
    return int(admin.ok("/api/v1/admin/students",
                        {"studentNo": no, "name": name, "classId": class_id}).data["id"])


def activate(base: str, no: str, name: str, class_code: str) -> None:
    r = Client(base).post("/api/v1/auth/student/activate",
                          {"studentNo": no, "name": name, "classCode": class_code,
                           "password": STUDENT_PWD})
    if r.status != 200:
        raise AssertionError(f"激活 {no} 失败：{r.status} {r.body}")


def login(base: str, username: str, password: str) -> str:
    r = Client(base).post("/api/v1/auth/login", {"username": username, "password": password})
    if r.status != 200:
        raise AssertionError(f"登录 {username} 失败：{r.status} {r.body}")
    return r.data["accessToken"]


# ---------------- 人脸采集 ----------------
def enroll_face(face_base: Client, java: Client, person: str, images) -> list[str]:
    """逐张 enroll/sample 拿 sampleKey，再一次性提交给 Java。返回 sampleKeys。"""
    keys: list[str] = []
    for img in images:
        frame = synth.enroll_frame(img)
        r = face_base.request(
            "POST", "/face-api/enroll/sample",
            files={"frame": ("sample.jpg", synth.jpeg(frame), "image/jpeg")},
            token=java.token)
        if r.status != 200:
            raise AssertionError(f"采集样本上传失败：{r.status} {r.body}")
        if not r.data.get("passed"):
            raise AssertionError(f"采集样本质检未通过：{r.data.get('reasons')}")
        keys.append(r.data["sampleKey"])
    r = java.ok("/api/v1/mobile/face/enroll",
                {"sampleKeys": keys, "consentVersion": "v1-2026"})
    assert r.data["status"] == "PENDING_REVIEW", r.body
    return keys


def approve_face(admin: Client, student_id: int) -> None:
    admin.ok(f"/api/v1/admin/students/{student_id}/face/review", {"approved": True})


# ---------------- 签到 ----------------
def start_session(teacher: Client, class_id: int, course_id: int, title: str = "验收签到",
                  duration: int = 30, late_after_sec: int | None = None) -> dict:
    body = {"classId": class_id, "courseId": course_id, "title": title,
            "durationMinutes": duration}
    if late_after_sec is not None:
        body["lateAfterSec"] = late_after_sec
    r = teacher.ok("/api/v1/admin/sessions", body)
    return {"id": int(r.data["id"]), "expectedCount": r.data.get("expectedCount"),
            "classId": r.data.get("classId"), "courseId": r.data.get("courseId")}


def join(student: Client, sid: int):
    """无请求体：身份来自 JWT，会话来自路径。"""
    return student.post(f"/api/v1/mobile/sessions/{sid}/join")


def attendance_of(student: Client, sid: int):
    return student.get(f"/api/v1/mobile/attendance/{sid}")


# ---------------- 人脸挑战 ----------------
def _frame_bytes(face_base: Client, cid: str, frame, seq: int, token: str):
    return face_base.request(
        "POST", f"/face-api/challenge/{cid}/frame", token=token,
        form={"seq": str(seq)},
        files={"frame": ("f.jpg", synth.jpeg(frame), "image/jpeg")})


def run_challenge(face_base: Client, ticket: str, frames: list, action: str | None = None,
                  baseline=None, until_error: bool = False) -> dict:
    """跑一次完整挑战。

    frames   投给 /frame 的画面序列（不含基线语义，按 seq 顺序发）
    baseline 挑战开始的基线帧；缺省用 frames[0]
    """
    base = synth.b64(baseline if baseline is not None else frames[0])
    body = {"baselineFrame": base, "deviceHash": "e2e"}
    if action:
        body["action"] = action
    start = face_base.post("/face-api/challenge/start", body, token=ticket)
    if start.status != 200:
        return {"startError": start.status, "code": start.code, "message": start.message}
    cid = start.data["challengeId"]
    last = None
    for i, f in enumerate(frames):
        last = _frame_bytes(face_base, cid, f, i, ticket)
        if last.status != 200:
            if until_error:
                return {"frameError": last.status, "code": last.code,
                        "message": last.message, "start": start.data}
            break
    fin = face_base.post(f"/face-api/challenge/{cid}/finish", {}, token=ticket)
    if fin.status != 200:
        return {"finishError": fin.status, "code": fin.code, "message": fin.message}
    out = fin.data
    out["action"] = start.data.get("action")
    out["needCount"] = start.data.get("needCount")
    return out


def frames_with_opens(base_frame, bbox, opens: tuple[int, ...], n: int = 8) -> list:
    """n 帧里，让第 opens 帧张嘴（其余是闭嘴帧）。"""
    opened = synth.open_mouth(base_frame, bbox)
    return [opened if i in opens else base_frame for i in range(n)]


def checkin_frames(person: str, opens: tuple[int, ...] = (1, 2, 4, 5), n: int = 8):
    """取该人的第一张清晰样本造一套**嘴部**签到帧，返回 (baseline, frames, bbox)。

    ★ CR-018 起嘴部动作已停用，生产白名单不再随机它，这套帧只用于回滚验证或
    历史判据的专项测试；正常验收改用 `checkin_frames_scale` / `checkin_frames_eye`
    （CR-021 后当前场景里没有调用方）。

    默认张两次（两段各 2 帧）：一次张嘴必须连续 ≥ hold_frames(2) 帧才计数，
    造两段可以同时满足 `MOUTH_ONCE` 与 `MOUTH_TWICE`。调用方必须显式传 action。
    """
    images = synth.person_images(person)
    base = synth.composite(images[0])
    bbox = synth.largest_bbox(base)
    frames = frames_with_opens(base, bbox, opens, n)
    return base, frames, bbox


def checkin_frames_scale(person: str, closer: bool = True, n: int = 8,
                         base_side: int = 320, delta: float = 0.22):
    """造一套满足 `MOVE_CLOSER` / `MOVE_AWAY` 的签到帧，返回 (baseline, frames, bbox)。

    前 4 帧保持基线尺度（让动作从基线开始），后 4 帧按 `delta` 放大/缩小。

    幅度取 0.22 而不是阈值 0.18：同一张脸整图重贴时检测框的响应略大于名义倍率
    （CR-016 实测 1.18 → 1.21），留一点余量，避免合成素材卡在阈值边界上。
    """
    images = synth.person_images(person)
    base = synth.composite(images[0], side=base_side)
    moved_side = int(round(base_side * (1 + delta if closer else 1 - delta)))
    moved = synth.composite(images[0], side=moved_side)
    return base, [base] * 4 + [moved] * max(2, n - 4), synth.largest_bbox(base)


def checkin_frames_eye(person: str, closed: int = 4, n: int = 10):
    """造一套「闭眼保持」的签到帧，返回 (baseline, frames, bbox)。

    前 3 帧睁眼（动作从基线之后开始），中间 `closed` 帧闭眼（≥2 帧才计入
    `EYE_HOLD_FRAMES`），末尾回到睁眼——真实学生也是「闭一下再睁开」。
    """
    images = synth.person_images(person)
    base = synth.composite(images[0])
    shut = synth.close_eyes(base)
    tail = max(1, n - 3 - closed)
    return base, [base] * 3 + [shut] * closed + [base] * tail, synth.largest_bbox(base)


def checkin_frames_static(person: str, n: int = 8):
    """同一帧重复 n 次：给「要求转头但完全没动」这类负例用。

    素材是正面静图，合成不出真实的 3D 偏航（鼻尖是平面贴图，投影变换不会让它
    相对双眼中点发生位移），所以转头动作在 E2E 里只能验负例；
    正例由 `test_challenge_flow.py` 的计数器单测与 `probe_action.py --pose` 覆盖。
    """
    base = synth.composite(synth.person_images(person)[0])
    return base, [base] * n, synth.largest_bbox(base)


def wait_vector_sync(seconds: float = 12.0) -> None:
    """等 face 服务的全量快照轮询把新向量拉过去（FA_SYNC_INTERVAL=10）。"""
    time.sleep(seconds)
