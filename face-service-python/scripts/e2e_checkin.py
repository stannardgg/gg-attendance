"""B4 端到端：扫码 → 基线帧 → 动作帧 → 三道门禁 → commit → 考勤。

★ 活体阈值被刻意放宽为 0（FA_LIVENESS_THRESHOLD=0）：CASIA 是静态照片，
真实活体会拒绝——这里要验证的是**链路是否打通**，不是活体能力。
活体指标已在 B3 用 100 张样本单独测过（原始照片拒绝率 100%）。
"""
import base64, json, os, sys, time, urllib.error, urllib.request, uuid

import cv2
import numpy as np

sys.path.insert(0, ".")
sys.path.insert(0, "scripts")
from probe_action import composite, darken_mouth, roi_polygon_in_image  # noqa: E402
from app.engine import detector, load_all  # noqa: E402

JAVA = "http://127.0.0.1:8080"
FACE = "http://127.0.0.1:8000"
CASIA = "../Cleaned_CASIA_FaceV5/000/000_0.jpg"
ok = 0; fail = []


def call(method, url, body=None, token=None, headers=None, raw=None, ctype="application/json"):
    h = {}
    if headers:
        h.update(headers)
    if token:
        h["Authorization"] = "Bearer " + token
    if raw is not None:
        data = raw
        h["Content-Type"] = ctype
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    else:
        data = None
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, cond, detail=""):
    global ok
    if cond:
        ok += 1; print(f"[OK]   {name}")
    else:
        fail.append(name); print(f"[FAIL] {name}  {detail}")


S = uuid.uuid4().hex[:6]
load_all()

# ---------- 1. 准备组织数据 ----------
admin = call("POST", JAVA + "/api/v1/auth/login", {"username": "admin", "password": "Admin@12345"})[1]["data"]["accessToken"]
cid = call("POST", JAVA + "/api/v1/admin/classes", {"code": "E2C" + S, "name": "E2E班" + S}, admin)[1]["data"]["id"]
kid = call("POST", JAVA + "/api/v1/admin/courses", {"code": "E2K" + S.upper(), "name": "E2E课"}, admin)[1]["data"]["id"]
tid = call("POST", JAVA + "/api/v1/admin/teachers", {"teacherNo": "ET" + S, "name": "E2E教师", "password": "Teacher12345"}, admin)[1]["data"]["id"]
for typ, sid in (("CLASS", cid), ("COURSE", kid)):
    call("POST", JAVA + "/api/v1/admin/teacher-scopes", {"teacherId": tid, "scopeType": typ, "scopeId": sid}, admin)

# 第一个学生 → id 必为 1，对应 vectors.json 里 person 000
st, r = call("POST", JAVA + "/api/v1/admin/students", {"studentNo": "E2S" + S, "name": "E2E学生", "classId": cid}, admin)
student_id = r["data"]["id"]
print(f"student id = {student_id}（需为 1 才能对上 vectors.json 的 person 000）")

# 人脸档案置 ACTIVE（真机流程里由审核接口写，这里直接造）
print("（人脸档案状态由 B3 审核流程维护，E2E 不依赖它）")

teacher = call("POST", JAVA + "/api/v1/auth/login", {"username": "ET" + S, "password": "Teacher12345"})[1]["data"]["accessToken"]
st, r = call("POST", JAVA + "/api/v1/admin/sessions", {"classId": cid, "courseId": kid, "title": "E2E 签到", "durationMinutes": 30}, teacher)
sid = r["data"]["id"]
check("教师发起会话", st == 200, r)

# ---------- 2. 学生 join 拿票 ----------
st, r = call("POST", JAVA + "/api/v1/auth/student/activate",
             {"studentNo": "E2S" + S, "name": "E2E学生", "classCode": "E2C" + S, "password": "Student12345"})
check("学生激活", st == 200, r.get("code"))
student = r["data"]["accessToken"]
st, r = call("POST", JAVA + f"/api/v1/mobile/sessions/{sid}/join", {}, student)
check("join 拿到票据", st == 200 and r["data"]["checkinTicket"], r.get("code"))
ticket = r["data"]["checkinTicket"]
check("challengeRequired 正确", "challengeRequired" in r["data"], r["data"])

# ---------- 3. 人脸挑战 ----------
img = cv2.imread(CASIA)
base = composite(img)
face = detector().detect_largest(base)
poly = roi_polygon_in_image(base, face.bbox)
dark = darken_mouth(base, poly)

okj, buf = cv2.imencode(".jpg", base, [cv2.IMWRITE_JPEG_QUALITY, 90])
baseline_b64 = base64.b64encode(buf.tobytes()).decode()
st, r = call("POST", FACE + "/face-api/challenge/start",
             {"baselineFrame": baseline_b64, "deviceHash": "e2e"}, ticket)
check("challenge/start", st == 200, r)
cid_f = r["data"]["challengeId"]

frames = [base] * 4 + [dark, dark, dark] + [base] * 4
for i, f in enumerate(frames):
    _, b = cv2.imencode(".jpg", f, [cv2.IMWRITE_JPEG_QUALITY, 90])
    boundary = "----e2e" + uuid.uuid4().hex[:8]
    body = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="frame"; filename="f.jpg"\r\n'
            f"Content-Type: image/jpeg\r\n\r\n").encode() + b.tobytes() + \
           (f"\r\n--{boundary}\r\n"
            f'Content-Disposition: form-data; name="seq"\r\n\r\n{i}\r\n'
            f"--{boundary}--\r\n").encode()
    st, r = call("POST", FACE + f"/face-api/challenge/{cid_f}/frame", raw=body,
                 token=ticket, ctype=f"multipart/form-data; boundary={boundary}")
    if st != 200:
        check(f"frame {i}", False, r); break
check(f"上传 {len(frames)} 帧", st == 200, r.get("code"))

st, r = call("POST", FACE + f"/face-api/challenge/{cid_f}/finish", {}, token=ticket)
res = r.get("data") or {}
check("finish 返回判定", st == 200, r)
check("动作检测到 1 次", res.get("detectedAction") == 1, res.get("detectedAction"))
check("匹配到本人", res.get("matchedStudentId") == 1, res.get("matchedStudentId"))
check("三道门禁全过（活体阈值已放宽为 0）", res.get("passed") is True,
      json.dumps(res.get("gates"), ensure_ascii=False))
check("commit 成功并拿到考勤状态", res.get("attendanceStatus") in ("PRESENT", "LATE"), res)

# ---------- 4. 考勤侧确认 ----------
st, r = call("GET", JAVA + f"/api/v1/mobile/attendance/{sid}", token=student)
check("移动端看到考勤记录", st == 200 and r["data"]["status"] in ("PRESENT", "LATE"), r)
st, r = call("GET", JAVA + f"/api/v1/admin/sessions/{sid}", token=teacher)
check("大屏计数 presentCount=1", r["data"]["presentCount"] == 1, r["data"])

print(f"\n通过 {ok} 项，失败 {len(fail)} 项")
if fail:
    print("失败：", ", ".join(fail)); raise SystemExit(1)
