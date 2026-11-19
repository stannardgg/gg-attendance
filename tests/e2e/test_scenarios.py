"""16.3 系统级验收场景（18 条）。

约定：
  - 用例名 = 场景号，顺序与项目文档 16.3 表格一致，报告直接按这个顺序贴。
  - 每个用例自己开一场签到，互不干扰；组织骨架在 session 级的 world 里建一次。
  - 人脸相关的「必须通过」场景要求服务端活体阈值置 0（CASIA 静图放大后活体分只有
    0.02~0.10，这是数据集的天花板，不是系统缺陷）。run.sh 会分两阶段跑：
    阶段 A（LIVENESS_THRESHOLD=0）跑除场景 7 以外的全部；阶段 B（0.55）只跑场景 7。
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import hmac
import time

import pytest

import flows
import synth
from conftest import INTERNAL_TOKEN, QR_SECRET
from e2e_client import Client


# ---------------------------------------------------------------- 工具
def qr_token(secret: str, sid: int, epoch: int, ago_sec: int = 0) -> str:
    """按 Java QrTokenService 的算法本地算一个时间片 token。

    用它而不是"拿当前码再等 20 秒"，是因为服务端允许「当前 + 上一时间片」共 30 秒，
    20 秒前的码不一定失效；这里直接算一个 45 秒前的片，确定性更强。
    """
    slice_no = int(time.time()) - ago_sec
    slice_no //= 15
    msg = f"{sid}|{epoch}|{slice_no}".encode()
    digest = hmac.new(secret.encode(), msg, hashlib.sha256).digest()
    return base64.b32encode(digest).decode()[:16]


def do_checkin(world, key: str, sid: int, qr: str, *, action: str | None = None,
               opens: tuple[int, ...] = (1, 2, 4, 5), frames=None, baseline=None):
    """扫码 → 拿票据 → 开始挑战 → 逐帧 → finish。返回 finish 的 data。"""
    st = world.students[key]
    j = flows.join(st["client"], sid, qr)
    assert j.status == 200, f"join 失败：{j.status} {j.body}"
    assert j.data["challengeRequired"] is True
    if frames is None:
        base, frames, _ = flows.checkin_frames(st["person"], opens)
        baseline = base if baseline is None else baseline
    return flows.run_challenge(world.face, j.data["checkinTicket"], frames,
                               action=action, baseline=baseline)


def agent_tool(world, teacher: Client, tool: str, params: dict,
               confirm: str | None = None, call_id: str | None = None):
    body = {"params": params}
    if confirm:
        body["confirmToken"] = confirm
    if call_id:
        body["toolCallId"] = call_id
    return world.java.post(f"/internal/agent/tools/{tool}", body,
                           headers={"X-Internal-Token": INTERNAL_TOKEN,
                                    "X-User-Token": teacher.token})


def session_status(teacher: Client, sid: int) -> str:
    return teacher.ok(f"/api/v1/admin/sessions/{sid}", method="GET").data["status"]


# ---------------------------------------------------------------- 场景
def test_01_正常签到(world, events, facts):
    """教师发起 → 学生扫码 → 人脸通过 → PRESENT；大屏 1 秒内 +1。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景01")
    facts["s01"] = s["id"]
    assert s["expectedCount"] == 3, "应到名单应在创建时冻结为 3 人"
    assert s["classId"] == world.class_id and s["courseId"] == world.course_id

    # 先连大屏通道，再签到：推送必须是"发生的那一刻"收到的
    live = events(s["id"], world.teacher.token)
    live.wait_for("EVT_CONNECTED", 2.0)

    res = do_checkin(world, "s1", s["id"], s["qrToken"])
    assert res.get("passed") is True, res
    assert res["attendanceStatus"] == "PRESENT", res

    evt = live.wait_for("EVT_CHECKIN_SUCCESS", timeout=1.0)
    assert evt is not None, "签到成功后 1 秒内必须收到大屏推送"
    assert evt["payload"]["status"] == "PRESENT"
    assert evt["payload"]["studentName"] == "验收学生1", "推送要带姓名，大屏才能就地改那一行"

    att = flows.attendance_of(world.students["s1"]["client"], s["id"]).data
    assert att["status"] == "PRESENT"
    assert att["className"] and att["courseName"]


def test_02_迟到签到(world, facts):
    """迟到阈值 1 秒，2 秒后签到 → LATE。

    16.3 写的是「迟到 6 分钟（late_after_sec=300）」。等价性说明：判定式是
    now > startAt + lateAfterSec，与具体数值无关；把阈值压到 1 秒只是把等待
    从 6 分钟压到 2 秒，判的是同一条分支。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景02",
                            late_after_sec=1)
    facts["s02"] = s["id"]
    time.sleep(2.2)
    res = do_checkin(world, "s2", s["id"], s["qrToken"])
    assert res.get("passed") is True, res
    assert res["attendanceStatus"] == "LATE"


def test_03_过期二维码(world):
    """过期码 → FA-3002；换码后的旧码同样失效。"""
    assert QR_SECRET, "E2E_QR_SECRET 未注入，无法构造过期码"
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景03")
    stale = qr_token(QR_SECRET, s["id"], s["epoch"], ago_sec=45)
    r = flows.join(world.students["s3"]["client"], s["id"], stale)
    assert r.status == 400 and r.code == "FA-3002", r.body

    # 教师换码：换码**之前**拿到的 token 才是"旧码"。
    # 注意 rotate-qr 的响应里已经带的是新码，拿它当旧码测等于测了个寂寞。
    old = flows.refresh_qr(world.teacher, s["id"])
    rotated = flows.rotate_qr(world.teacher, s["id"])
    assert rotated["epoch"] == old["epoch"] + 1
    r2 = flows.join(world.students["s3"]["client"], s["id"], old["qrToken"])
    assert r2.status == 400 and r2.code == "FA-3002", r2.body
    # 新码可用
    assert flows.join(world.students["s3"]["client"], s["id"],
                      rotated["qrToken"]).status == 200


def test_04_重复扫码(world, facts):
    """同一学生第二次扫码 → FA-3003，且只留 1 条考勤。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景04")
    facts["s04"] = s["id"]
    res = do_checkin(world, "s1", s["id"], s["qrToken"])
    assert res.get("passed") is True, res
    again = flows.join(world.students["s1"]["client"], s["id"], s["qrToken"])
    # FA-3003 = 409 Conflict（已经签过，不是参数错）
    assert again.status == 409 and again.code == "FA-3003", again.body
    assert flows.attendance_of(world.students["s1"]["client"], s["id"]).status == 200


def test_05_异班学生(world):
    """别的班的学生扫码 → FA-3004。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景05")
    r = flows.join(world.outsider["client"], s["id"], s["qrToken"])
    # 契约 error-codes.json：FA-3004 http=403（不是 400 —— 是"无权"，不是"参数错"）
    assert r.status == 403 and r.code == "FA-3004", r.body
    assert "班级" in r.message


def test_06_画面两人(world):
    """两人同框（都 ≥90px）→ FA-4002，且不产生考勤。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景06")
    st = world.students["s2"]
    j = flows.join(st["client"], s["id"], s["qrToken"])
    assert j.status == 200, j.body

    face_a = synth.composite(world.students["s2"]["images"][0])
    face_b = synth.composite(world.students["s1"]["images"][0])
    two = synth.two_faces(face_a, face_b)
    assert "检出 2 张脸" in synth.describe(two), synth.describe(two)

    res = flows.run_challenge(world.face, j.data["checkinTicket"], [two] * 4,
                              baseline=face_a, until_error=True)
    assert res.get("code") == "FA-4002", res
    assert "只能留下一个人" in res["message"]
    assert flows.attendance_of(st["client"], s["id"]).status == 404


def test_08_要求两次只做一次(world):
    """MOUTH_TWICE 只张一次 → FA-4003，且回落「只检测到 1 次，要求 2 次」的数值。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景08")
    # 连张 2 帧才算「一次」（hold_frames=2）；只发 1 帧暗画面等于没张嘴
    res = do_checkin(world, "s3", s["id"], s["qrToken"],
                     action="MOUTH_TWICE", opens=(2, 3))
    assert res["action"] == "MOUTH_TWICE"
    assert res["passed"] is False and res["rejectGate"] == "actionCount", res
    gate = res["gates"]["actionCount"]
    assert gate["value"] == 1 and gate["threshold"] == 2, gate


def test_09_失败后申诉推送(world, events, facts):
    """签到失败 → 学生申诉 → 教师端 1 秒内收到提醒，申诉进列表。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景09")
    facts["s09"] = s["id"]
    res = do_checkin(world, "s3", s["id"], s["qrToken"],
                     action="MOUTH_TWICE", opens=(2, 3))
    assert res["passed"] is False

    live = events(s["id"], world.teacher.token)
    live.wait_for("EVT_CONNECTED", 2.0)

    r = world.students["s3"]["client"].ok("/api/v1/mobile/appeals",
                                          {"sessionId": s["id"],
                                           "reason": "人脸识别未通过，本人在现场"})
    appeal_id = r.data["id"]
    facts["appeal"] = appeal_id

    evt = live.wait_for("EVT_APPEAL_CREATED", timeout=1.0)
    assert evt is not None, "申诉提交后 1 秒内必须收到大屏提醒"
    assert evt["payload"]["appealId"] == appeal_id

    lst = world.teacher.ok("/api/v1/admin/appeals?status=PENDING&size=20",
                           method="GET").data
    assert any(x["id"] == appeal_id for x in lst["items"]), lst


def test_10_教师通过申诉(world, facts):
    """通过申诉 → 考勤补为 PRESENT，审计留前后状态，学生端同步。"""
    sid = facts["s09"]
    appeal_id = facts["appeal"]
    r = world.teacher.ok(f"/api/v1/admin/appeals/{appeal_id}/handle",
                         {"approved": True, "reply": "已核对现场，通过"})
    assert r.data["status"] == "APPROVED"

    att = flows.attendance_of(world.students["s3"]["client"], sid).data
    assert att["status"] == "PRESENT"

    logs = world.admin.ok(
        f"/api/v1/admin/audit-logs?action=APPEAL_HANDLE&targetType=APPEAL&size=50",
        method="GET").data
    mine = [x for x in logs["items"] if x["targetId"] == appeal_id]
    assert mine, logs
    before, after = mine[0]["before"], mine[0]["after"]
    assert before and after, "审计必须同时记变更前与变更后"
    assert before.get("appealStatus") != "APPROVED"
    assert after.get("appealStatus") == "APPROVED"


def test_11_教师越权删教师(world):
    """TEACHER 直接 DELETE /admin/teachers/{id} → 403 FA-1003，数据不变。"""
    # 教师连列表都不该看到（FA-1003），所以只能由管理员先查到这个 id
    me = world.admin.ok(f"/api/v1/admin/teachers?keyword={world.teacher_no}",
                        method="GET").data["items"][0]
    r = world.teacher.delete(f"/api/v1/admin/teachers/{me['id']}")
    assert r.status == 403 and r.code == "FA-1003", r.body
    still = world.admin.ok(f"/api/v1/admin/teachers?keyword={world.teacher_no}",
                           method="GET").data["items"]
    assert still and still[0]["status"] == "ACTIVE", "越权请求不得改动任何数据"


def test_12_Agent查询班级统计(world):
    """应到/实到/迟到/缺勤 + 命中条数；越权班级直接拒绝。"""
    today = datetime.date.today().isoformat()
    # 先查会话列表（带命中条数）
    ls = agent_tool(world, world.teacher, "list_sessions",
                    {"dateFrom": today, "dateTo": today, "classId": world.class_id})
    assert ls.status == 200, ls.body
    sessions = ls.data["result"]["items"]
    # 「命中 N 条」：ToolData.result 直接就是工具数据，条数由 agent-service 的
    # _citations() 按 items 长度渲染（见 agent-service/app/graph/nodes/answer.py）
    assert len(sessions) >= 1, ls.data

    # QueryTools 会把列名转 camelCase：session_id → sessionId
    sid = sessions[0]["sessionId"]
    sm = agent_tool(world, world.teacher, "get_session_summary", {"sessionId": sid})
    assert sm.status == 200, sm.body
    d = sm.data["result"]
    for key in ("total", "present", "late", "absent", "rate"):
        assert key in d, f"汇总缺字段 {key}: {d}"

    # 未授权班级：读都不许读
    deny = agent_tool(world, world.teacher, "list_sessions",
                      {"dateFrom": today, "dateTo": today,
                       "classId": world.other_class_id})
    assert deny.status == 403 and deny.code == "FA-6004", deny.body
    # get_class_stats 的 dateFrom/dateTo 是必填，先给全参数再验证越权
    deny2 = agent_tool(world, world.teacher, "get_class_stats",
                       {"classId": world.other_class_id,
                        "dateFrom": today, "dateTo": today})
    assert deny2.status == 403 and deny2.code == "FA-6004", deny2.body


def test_13_基线污染不误拒(world):
    """学生一看到指令就先张嘴、基线帧已经是张嘴状态，仍必须判通过。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景13")
    images = synth.person_images(world.students["s2"]["person"])
    base = synth.composite(images[0])
    bbox = synth.largest_bbox(base)
    opened = synth.open_mouth(base, bbox)
    # baseline = 张嘴（污染）；序列里两次张嘴各保持 2 帧（hold_frames=2）
    frames = [base, base, opened, opened, base, base, opened, opened]
    res = do_checkin(world, "s2", s["id"], s["qrToken"], action="MOUTH_TWICE",
                     frames=frames, baseline=opened)
    assert res.get("passed") is True, res
    assert res["detectedAction"] >= 2, res


def test_14_背景小脸不误拦(world):
    """身后墙上 40px 的小脸：低于 90px 下限，不该触发 FA-4002。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景14")
    base = synth.composite(world.students["s3"]["images"][0])
    other = synth.composite(world.students["s1"]["images"][0])
    frame = synth.with_background_face(base, other, px=40)
    bbox = synth.largest_bbox(frame)
    frames = flows.frames_with_opens(frame, bbox, (1, 2, 4, 5), 8)
    res = do_checkin(world, "s3", s["id"], s["qrToken"], frames=frames, baseline=frame)
    assert res.get("code") != "FA-4002", res
    assert res.get("passed") is True, res


def test_15_班级非空拒删(world):
    """仍有在读学生的班级不能删；学生转出后可以删。"""
    code = f"E2D{world.sfx}"
    cid = flows.create_class(world.admin, code, f"待删班{world.sfx}")
    no = f"{code}S1"
    stu = flows.create_student(world.admin, no, "待转学生", cid)
    r = world.admin.delete(f"/api/v1/admin/classes/{cid}")
    assert r.status == 409 and r.code == "FA-2005", r.body
    assert r.body["data"]["studentCount"] == 1, r.body

    world.admin.ok(f"/api/v1/admin/students/{stu}", {"classId": world.class_id},
                   method="PUT")
    r2 = world.admin.delete(f"/api/v1/admin/classes/{cid}")
    assert r2.status == 200, r2.body
    assert r2.data.get("archived") is False, f"班内已无学生，应物理删除：{r2.data}"


def test_16_课程不接受班级字段(world):
    """课程 DTO 带 classId → 400；契约校验脚本另行断言 course 表无 class_id。"""
    r = world.admin.post("/api/v1/admin/courses",
                         {"code": f"E2X{world.sfx}", "name": "越界课程",
                          "classId": world.class_id})
    assert r.status == 400, r.body
    assert "classId" in r.message or "不合法" in r.message, r.message


def test_17_Agent发起签到并撤销(world):
    """预览（库里没有）→ 确认（生成）→ 撤销（作废），action_log 两条。"""
    params = {"classId": world.class_id, "courseId": world.course_id,
              "title": "Agent 发起", "durationMinutes": 20, "lateAfterSec": 300}
    before = world.teacher.ok(
        f"/api/v1/admin/sessions?classId={world.class_id}&size=100",
        method="GET").data["items"]

    pv = agent_tool(world, world.teacher, "create_session", params)
    assert pv.status == 200, pv.body
    assert pv.data["requiresConfirm"] is True
    token = pv.data["confirmToken"]
    assert token
    mid = world.teacher.ok(
        f"/api/v1/admin/sessions?classId={world.class_id}&size=100",
        method="GET").data["items"]
    assert len(mid) == len(before), "预览阶段不得落库"

    ex = agent_tool(world, world.teacher, "create_session", params, confirm=token)
    assert ex.status == 200, ex.body
    sid = ex.data["result"]["sessionId"]
    assert session_status(world.teacher, sid) == "ACTIVE"
    assert ex.data["actionLogId"]

    un = world.java.post(f"/internal/agent/actions/{ex.data['actionLogId']}/undo", {},
                         headers={"X-Internal-Token": INTERNAL_TOKEN,
                                  "X-User-Token": world.teacher.token})
    assert un.status == 200, un.body
    assert un.data["undone"] is True and un.data["undoneLogId"] != un.data["originalLogId"]
    assert session_status(world.teacher, sid) == "CANCELLED"


def test_18_确认令牌过期与参数篡改(world):
    """过期 → FA-6002；篡改 → FA-6003；两者都不执行。"""
    params = {"classId": world.class_id, "courseId": world.course_id,
              "title": "过期用例", "durationMinutes": 10}
    pv = agent_tool(world, world.teacher, "create_session", params)
    token = pv.data["confirmToken"]
    time.sleep(61)          # 契约写死 60 秒（§11.5）
    expired = agent_tool(world, world.teacher, "create_session", params, confirm=token)
    assert expired.status == 400 and expired.code == "FA-6002", expired.body

    pv2 = agent_tool(world, world.teacher, "create_session", params)
    changed = dict(params, durationMinutes=59)
    tampered = agent_tool(world, world.teacher, "create_session", changed,
                          confirm=pv2.data["confirmToken"])
    assert tampered.status == 409 and tampered.code == "FA-6003", tampered.body

    titles = [x["title"] for x in world.teacher.ok(
        f"/api/v1/admin/sessions?classId={world.class_id}&size=100",
        method="GET").data["items"]]
    assert "过期用例" not in titles, "令牌失效/篡改的请求都不得创建会话"


@pytest.mark.high_liveness
def test_07_照片翻拍被拒(world):
    """静图（翻拍照片）→ 三道门禁至少一道拦住，无考勤记录。

    只在阶段 B（LIVENESS_THRESHOLD=0.55）跑：阶段 A 把阈值置 0 是为了让"必须通过"
    的场景不被数据集天花板挡住，若还在这里测活体就等于测了个恒真命题。

    断言是 passed=False 而不是指定 rejectGate：翻拍帧的活体分**因身份而异**
    （实测同一套合成参数下 0.02~0.75 都有——旧结论"0.02~0.10"只是当初抽到的
    几个身份），活体这道门禁不一定拦得住；但动作门禁（翻拍不张嘴）必然拦住，
    三道 AND 的语义下攻击仍然进不去。活体单门禁的拒绝率证据在 WP-06 的
    照片攻击探针（原始照片 100% / 全部 96%），那才是对活体模型本身的专项。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景07")
    res = do_checkin(world, "s1", s["id"], s["qrToken"], opens=())
    assert res.get("passed") is False, res
    assert res["rejectGate"], res          # 误拒必须可归因（§6.5），写清是哪道门禁拦的
    assert res["similarity"] is not None
    assert flows.attendance_of(world.students["s1"]["client"], s["id"]).status == 404
