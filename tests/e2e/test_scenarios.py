"""16.3 系统级验收场景（18 条）。

约定：
  - 用例名 = 场景号，顺序与项目文档 16.3 表格一致，报告直接按这个顺序贴。
  - 每个用例自己开一场签到，互不干扰；组织骨架在 session 级的 world 里建一次。
  - 人脸相关的「必须通过」场景要求服务端活体阈值置 0（CASIA 静图放大后活体分只有
    0.02~0.10，这是数据集的天花板，不是系统缺陷）。run.sh 会分两阶段跑：
    阶段 A（LIVENESS_THRESHOLD=0）跑除场景 7 以外的全部；阶段 B（0.55）只跑场景 7。
"""
from __future__ import annotations

import datetime
import time

import pytest

import flows
import synth
from conftest import INTERNAL_TOKEN
from e2e_client import Client


# ---------------------------------------------------------------- 工具
def do_checkin(world, key: str, sid: int, *, action: str | None = None,
               frames=None, baseline=None):
    """进入签到 → 拿票据 → 开始挑战 → 逐帧 → finish。返回 finish 的 data。

    造帧按 action 自动匹配：MOVE_CLOSER / MOVE_AWAY 用尺度序列，转头与
    **未指定 action**（交给服务端随机）都用静止帧—— 合成素材造不出真实偏航，
    而随机动作下的"没做动作"就是静止不动。EYE_CLOSE 分支保留供白名单回滚。
    """
    st = world.students[key]
    j = flows.join(st["client"], sid)
    assert j.status == 200, f"join 失败：{j.status} {j.body}"
    assert j.data["challengeRequired"] is True
    if frames is None:
        if action == "MOVE_AWAY":
            base, frames, _ = flows.checkin_frames_scale(st["person"], closer=False)
        elif action == "MOVE_CLOSER":
            base, frames, _ = flows.checkin_frames_scale(st["person"], closer=True)
        elif action == "EYE_CLOSE":
            base, frames, _ = flows.checkin_frames_eye(st["person"])
        else:
            base, frames, _ = flows.checkin_frames_static(st["person"])
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
    """教师发起 → 学生从首页进入 → 人脸通过 → PRESENT；大屏 1 秒内 +1。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景01")
    facts["s01"] = s["id"]
    assert s["expectedCount"] == 3, "应到名单应在创建时冻结为 3 人"
    assert s["classId"] == world.class_id and s["courseId"] == world.course_id

    # 先连大屏通道，再签到：推送必须是"发生的那一刻"收到的
    live = events(s["id"], world.teacher.token)
    live.wait_for("EVT_CONNECTED", 2.0)

    res = do_checkin(world, "s1", s["id"], action="MOVE_CLOSER")
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
    res = do_checkin(world, "s2", s["id"], action="MOVE_CLOSER")
    assert res.get("passed") is True, res
    assert res["attendanceStatus"] == "LATE"


def test_03_会话结束后不可签到(world):
    """学生从首页直接进入已结束的场次 → FA-3001；该场次也不再出现在进行中列表。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景03")
    world.teacher.ok(f"/api/v1/admin/sessions/{s['id']}/close", {})
    r = flows.join(world.students["s3"]["client"], s["id"])
    assert r.status == 409 and r.code == "FA-3001", r.body

    active = world.students["s3"]["client"].ok("/api/v1/mobile/sessions/active", method="GET").data
    assert s["id"] not in [int(x["sessionId"]) for x in active]


def test_04_重复签到(world, facts):
    """同一学生第二次进入并提交 → FA-3003，且只留 1 条考勤。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景04")
    facts["s04"] = s["id"]
    res = do_checkin(world, "s1", s["id"], action="MOVE_CLOSER")
    assert res.get("passed") is True, res
    again = flows.join(world.students["s1"]["client"], s["id"])
    # FA-3003 = 409 Conflict（已经签过，不是参数错）
    assert again.status == 409 and again.code == "FA-3003", again.body
    assert flows.attendance_of(world.students["s1"]["client"], s["id"]).status == 200


def test_05_异班学生(world):
    """别的班的学生进入本场 → FA-3004。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景05")
    r = flows.join(world.outsider["client"], s["id"])
    # 契约 error-codes.json：FA-3004 http=403（不是 400 —— 是"无权"，不是"参数错"）
    assert r.status == 403 and r.code == "FA-3004", r.body
    assert "班级" in r.message


def test_06_画面两人(world):
    """两人同框（都 ≥90px）→ FA-4002，且不产生考勤。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景06")
    st = world.students["s2"]
    j = flows.join(st["client"], s["id"])
    assert j.status == 200, j.body

    face_a = synth.composite(world.students["s2"]["images"][0])
    face_b = synth.composite(world.students["s1"]["images"][0])
    two = synth.two_faces(face_a, face_b)
    assert "检出 2 张脸" in synth.describe(two), synth.describe(two)

    res = flows.run_challenge(world.face, j.data["checkinTicket"], [two] * 4,
                              baseline=face_a, until_error=True)
    assert res.get("code") == "FA-4002", res
    assert "只能留下一个人" in res["message"]
    flows.assert_no_attendance(st["client"], s["id"])


def test_08_要求靠近但没动(world):
    """要求 MOVE_CLOSER 但画面完全静止 → FA-4003，数值回传 0/1。

    CR-018 停用嘴部动作后动作库没有 need=2 的动作，本场景由「要求两次只做一次」
    改为「要求靠近但完全没动」：测的仍是 actionCount 门禁的数值归因，
    只是不再出现 1/2 这种形态。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景08")
    base = synth.composite(synth.person_images(world.students["s3"]["person"])[0])
    res = do_checkin(world, "s3", s["id"], action="MOVE_CLOSER",
                     frames=[base] * 8, baseline=base)
    assert res["action"] == "MOVE_CLOSER"
    assert res["passed"] is False and res["rejectGate"] == "actionCount", res
    gate = res["gates"]["actionCount"]
    assert gate["value"] == 0 and gate["threshold"] == 1, gate


def test_09_失败后申诉推送(world, events, facts):
    """签到失败 → 学生申诉 → 教师端 1 秒内收到提醒，申诉进列表。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景09")
    facts["s09"] = s["id"]
    base = synth.composite(synth.person_images(world.students["s3"]["person"])[0])
    res = do_checkin(world, "s3", s["id"], action="MOVE_CLOSER",
                     frames=[base] * 8, baseline=base)
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
    """学生看到指令就先靠近、基线帧已经贴近，仍必须判通过。

    判据是「相对基线的变化」：基线偏高（已是大脸）不能让真实动作失效——
    这是 CR-016 §6 已知限制 4 在尺度动作上的等价保护（原用例是「基线已经张嘴」）。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景13")
    images = synth.person_images(world.students["s2"]["person"])
    base = synth.composite(images[0], side=380)     # 基线已经贴近
    moved = synth.composite(images[0], side=470)    # 再靠近约 24%
    frames = [base] * 4 + [moved] * 4
    res = do_checkin(world, "s2", s["id"], action="MOVE_CLOSER",
                     frames=frames, baseline=base)
    assert res.get("passed") is True, res
    assert res["detectedAction"] >= 1, res


def test_14_背景小脸不误拦(world):
    """身后墙上 40px 的小脸：低于 90px 下限，不该触发 FA-4002。"""
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景14")
    images = synth.person_images(world.students["s3"]["person"])
    other = synth.composite(world.students["s1"]["images"][0])
    base = synth.composite(images[0])
    near = synth.composite(images[0], side=392)     # 靠近约 22%
    frame = synth.with_background_face(base, other, px=40)
    moved = synth.with_background_face(near, other, px=40)
    frames = [frame] * 4 + [moved] * 4
    res = do_checkin(world, "s3", s["id"], action="MOVE_CLOSER",
                     frames=frames, baseline=frame)
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
    几个身份），活体这道门禁不一定拦得住；但动作门禁必然拦住——翻拍拍的是静止画面，
    三类判据（尺度变化 / 眼睑开合 / 偏航）都是"相对基线的位移"，静止画面一帧都
    凑不出来（CR-018 前是"翻拍不张嘴"），三道 AND 的语义下攻击仍然进不去。活体单门禁
    的拒绝率证据在 WP-06 的照片攻击探针（原始照片 100% / 全部 96%），那才是对活体
    模型本身的专项。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景07")
    res = do_checkin(world, "s1", s["id"])
    assert res.get("passed") is False, res
    assert res["rejectGate"], res          # 误拒必须可归因（§6.5），写清是哪道门禁拦的
    assert res["similarity"] is not None
    flows.assert_no_attendance(world.students["s1"]["client"], s["id"])


# ---------------------------------------------------------------- CR-021 新增场景

def test_19_已停用动作hint被拒(world):
    """要求已停用的 EYE_CLOSE（CR-029）→ start 直接拒绝（FA-9001），挑战不开。

    CR-021 时这里是「闭眼保持判通过」的正例；CR-029 产品决定停用闭眼动作后，
    联调/验收脚本若仍按旧契约 hint 该码，必须拿到明确的服务端拒绝——
    消息说「已停用」，而不是模糊的"未知动作码"或更糟的静默随机动作。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景19")
    res = do_checkin(world, "s2", s["id"], action="EYE_CLOSE")
    assert res.get("startError") == 400, res
    assert res["code"] == "FA-9001", res
    assert "已停用" in res["message"], res
    flows.assert_no_attendance(world.students["s2"]["client"], s["id"])

# 场景 20（要求闭眼但一直睁眼 → FA-4003）随 CR-029 停用闭眼移除：闭眼动作
# 不再下发，这个负例失去了可复现的入口。FA-4003 语义由场景 21（转头不动）继续覆盖。


def test_21_要求转头但没动(world):
    """要求 TURN_LEFT 但画面静止 → FA-4003。

    转头在 E2E 只验负例：正面静图做不出真实偏航（鼻尖是平面贴图，投影变换
    不会让它相对双眼中点位移）。正例由计数器单测与 probe_action.py --pose 覆盖。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景21")
    base, frames, _ = flows.checkin_frames_static(world.students["s3"]["person"])
    res = do_checkin(world, "s3", s["id"], action="TURN_LEFT",
                     frames=frames, baseline=base)
    assert res["action"] == "TURN_LEFT", res
    assert res["passed"] is False and res["rejectGate"] == "actionCount", res
    assert res["gates"]["actionCount"]["value"] == 0, res


# ---------------------------------------------------------------- CR-023 新增场景

def test_22_三次失败后禁止再进(world):
    """CR-023：识别失败 3 次后，join 以 FA-3005 拒绝，首页 retryLeft=0。

    旧实现里「试过 3 次」只是采集页的本地计数（ResultPanel.attempts），
    关掉页面回首页再点进去次数就清零，可以无限重试。现在每次判定失败由
    Python 上报 Java 落库（checkin_attempt），join / 首页列表 / start 三处都拦。
    """
    s = flows.start_session(world.teacher, world.class_id, world.course_id, "场景22")
    st = world.students["s1"]

    j = flows.join(st["client"], s["id"])
    assert j.status == 200, j.body
    assert j.data["retryLeft"] == 3, j.data          # JoinData 不再硬编码 3
    ticket = j.data["checkinTicket"]

    for i in range(3):
        base, frames, _ = flows.checkin_frames_static(st["person"], n=10)
        res = flows.run_challenge(world.face, ticket, frames, baseline=base)
        assert res["passed"] is False, (i, res)
        # finish 现在必须带权威 retryLeft（CR-023 契约早声明了该字段）
        assert res.get("retryLeft") == 3 - (i + 1), (i, res)

    # 第 4 次领票：后端拒绝（权威判定，不是前端隐藏按钮）
    j4 = flows.join(st["client"], s["id"])
    assert j4.status == 429, j4.body
    assert j4.code == "FA-3005", j4.body

    # 首页列表：retryLeft=0，前端据此显示「识别失败」并拒绝点击
    active = st["client"].get("/api/v1/mobile/sessions/active").data
    mine = [x for x in active if x["sessionId"] == s["id"]]
    assert mine and mine[0]["retryLeft"] == 0, active

    flows.assert_no_attendance(st["client"], s["id"])
