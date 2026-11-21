"""验收套件的公共装置。

运行环境是 compose 网络里的 `fa/face` 镜像（有 cv2 / numpy / onnxruntime / 模型）：
  - 业务请求走 https://nginx（与真机一致：过 nginx、过 TLS、过限流）
  - 内部接口走 http://java:8080（/internal/** 不对宿主机暴露，套件必须在网络内）
  - 人脸服务走 http://face:8000（跳过 nginx 的 8MB/限流，避免测人脸时被限流干扰）

跑法见 tests/e2e/run.sh；单独调试：
  docker compose --env-file .env --profile test run --rm e2e \
      python -m pytest /e2e -k "01" -q --no-header
"""
from __future__ import annotations

import json
import os
import uuid

import pytest

import flows
import synth
from e2e_client import Client

BASE = os.environ.get("E2E_BASE", "https://nginx")
JAVA = os.environ.get("E2E_JAVA", "http://java:8080")
FACE = os.environ.get("E2E_FACE", "http://face:8000")
ADMIN_USER = os.environ.get("E2E_ADMIN_USER", "admin")
ADMIN_PWD = os.environ.get("E2E_ADMIN_PWD", "")
INTERNAL_TOKEN = os.environ.get("E2E_INTERNAL_TOKEN", "")
OUT_DIR = os.environ.get("E2E_OUT", "/out")

# 每位「有脸」的学生用一个人：CASIA 目录名。
# 不能固定用 000/001/002：face 的采集质检有 DUPLICATE 判定（同一张脸不能重复建档），
# 同一个栈跑第二遍就会因为"这个人已经入过库"而失败。所以每次从 500 个身份里
# 挑没被用过的 3 个，并把用过的记进 out/used_persons.json。
DEFAULT_PERSONS = ["000", "001", "002"]


def candidates(limit: int = 40):
    """按「未用过 + 动作可测」筛出候选身份，逐个 yield。

    为什么是生成器而不是一次取 3 个：采集质检有 DUPLICATE 判定，谁也没法在不试的
    情况下保证某个身份一定没入过库（历史运行、手工联调都会留下向量）。所以让调用方
    拿到候选序列，失败一个换下一个，而不是整批重来。
    """
    try:
        pool = sorted(d for d in os.listdir(synth.DATASET)
                      if os.path.isdir(os.path.join(synth.DATASET, d))
                      and os.path.exists(os.path.join(synth.DATASET, d, f"{d}_0.jpg")))
    except OSError:
        pool = []
    if len(pool) < 3:
        yield from DEFAULT_PERSONS
        return

    used_file = os.path.join(OUT_DIR, "used_persons.json")
    used: list[str] = []
    try:
        with open(used_file, encoding="utf-8") as fh:
            used = json.load(fh)
    except (OSError, ValueError):
        pass
    avail = [d for d in pool if d not in used]
    if len(avail) < 3:                 # 全用过了（理论上要跑 160 次以上）：从头再来
        avail, used = pool, []
    import random
    random.SystemRandom().shuffle(avail)

    given = 0
    for cand in avail:
        if given >= limit:
            break
        if not _mouth_detectable(cand):
            continue
        given += 1
        used.append(cand)
        _dump("used_persons.json", used)
        yield cand


def _mouth_detectable(person: str) -> bool:
    """预检：这张脸的「合成张嘴」能不能被动作门禁判出来。

    20 个身份里有 1 个（唇色偏暗，基线暗度 0.30）涨幅只有 1.36 倍，达不到
    rise_ratio=1.45 —— 那是人脸本身的属性，不是系统行为，先筛掉。

    ★ 必须用**服务端同一套口径**：ROI 在基线帧上锁定，之后每帧只做刚性对齐再取那块。
      用"每帧重新算关键点"去估，涂黑的嘴会把形状回归带偏、ROI 跟着嘴动，
      算出来的涨幅虚高 —— 预检就会放过实际判不出来的身份。
    """
    try:
        import cv2
        from app.engine import detector, landmarker
        from app.challenge.action import MouthActionCounter, mouth_dark_ratio

        synth._engine()            # 先把模型加载完，detector()/landmarker() 才不是 None
        img = cv2.imread(os.path.join(synth.DATASET, person, f"{person}_0.jpg"))
        if img is None:
            return False
        base = synth.composite(img)
        face = detector().detect_largest(base)
        if face is None:
            return False
        pts, crop = landmarker().landmarks(base, face.bbox)
        rect = landmarker().mouth_rect(pts)
        base_ratio = mouth_dark_ratio(cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)[
            rect[1]:rect[3], rect[0]:rect[2]])
        opened = synth.open_mouth(base, face.bbox)

        def svc_ratio(frame):
            f = detector().detect_largest(frame)
            if f is None:
                return None
            aligned = landmarker().align_space(frame, f.bbox)
            gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
            return mouth_dark_ratio(gray[rect[1]:rect[3], rect[0]:rect[2]])

        open_ratio = svc_ratio(opened)
        if open_ratio is None:
            return False
        counter = MouthActionCounter(base_ratio)
        for x in (base_ratio, base_ratio, open_ratio, open_ratio,
                  base_ratio, base_ratio, open_ratio, open_ratio):
            counter.observe(x)
        return counter.result()[0] >= 2
    except Exception:                                        # noqa: BLE001
        return True            # 预检本身出错时不要卡住验收


class World:
    """一次验收跑用的组织骨架。"""

    def __init__(self, base, java, face, admin: Client, sfx: str):
        self.base = base
        self.java = java
        self.face = face
        self.admin = admin
        self.sfx = sfx
        self.class_id = 0
        self.class_code = ""
        self.course_id = 0
        self.teacher_no = ""
        self.teacher: Client | None = None
        # key -> dict(id, no, person, client, images)
        self.students: dict[str, dict] = {}
        self.other_class_id = 0
        self.outsider: dict = {}

    def student(self, key: str) -> dict:
        return self.students[key]


@pytest.fixture(scope="session")
def admin() -> Client:
    token = flows.login(BASE, ADMIN_USER, ADMIN_PWD)
    return Client(BASE, token)


@pytest.fixture(scope="session")
def world(admin: Client) -> World:
    sfx = uuid.uuid4().hex[:6]
    w = World(BASE, Client(JAVA), Client(FACE), admin, sfx)

    w.class_code = f"E2C{sfx}"
    w.class_id = flows.create_class(admin, w.class_code, f"验收班{sfx}")
    w.course_id = flows.create_course(admin, f"E2K{sfx.upper()}")
    w.teacher_no = f"E2T{sfx}"
    tid = flows.create_teacher(admin, w.teacher_no)
    flows.grant_scope(admin, tid, "CLASS", w.class_id)
    flows.grant_scope(admin, tid, "COURSE", w.course_id)
    w.teacher = Client(BASE, flows.login(BASE, w.teacher_no, flows.TEACHER_PWD))

    # 本班 3 名学生，各用一个人脸。身份采集失败（DUPLICATE / 质量不过）就换下一个：
    # 把失败的那个学生归档（status=ARCHIVED），它不会进 session_expected，
    # 所以「应到 3 人」的断言仍然成立。
    pool = candidates()
    for i in range(1, 4):
        name = f"验收学生{i}"
        last = None
        for _ in range(6):
            try:
                person = next(pool)
            except StopIteration:
                break
            # 学号带身份后缀：重试时上一个学生已归档，但学号仍占着唯一索引
            no = f"{w.class_code}S{i}{person}"
            sid = flows.create_student(admin, no, name, w.class_id)
            flows.activate(BASE, no, name, w.class_code)
            client = Client(BASE, flows.login(BASE, no, flows.STUDENT_PWD))
            try:
                flows.enroll_face(w.face, client, person, synth.person_images(person))
                flows.approve_face(admin, sid)
            except AssertionError as exc:
                last = exc
                admin.delete(f"/api/v1/admin/students/{sid}")
                continue
            w.students[f"s{i}"] = {"id": sid, "no": no, "person": person,
                                   "client": client, "images": synth.person_images(person)}
            break
        else:
            raise AssertionError(f"第 {i} 名学生连续 6 个身份都采集失败：{last}")

    # 异班学生：只要存在，不需要人脸（join 阶段就会被 FA-3004 拦下）
    other_code = f"E2O{sfx}"
    w.other_class_id = flows.create_class(admin, other_code, f"异班{sfx}")
    out_no = f"{other_code}S9"
    out_id = flows.create_student(admin, out_no, "异班学生", w.other_class_id)
    flows.activate(BASE, out_no, "异班学生", other_code)
    w.outsider = {"id": out_id, "no": out_no,
                  "client": Client(BASE, flows.login(BASE, out_no, flows.STUDENT_PWD))}

    flows.wait_vector_sync()
    _dump("world.json", {"classId": w.class_id, "classCode": w.class_code,
                         "courseId": w.course_id, "teacherNo": w.teacher_no,
                         "students": {k: {"id": v["id"], "no": v["no"]}
                                      for k, v in w.students.items()},
                         "otherClassId": w.other_class_id})
    return w


_FACTS: dict = {}
_RESULTS: list[dict] = []


@pytest.fixture(scope="session")
def facts() -> dict:
    """用例把关键主键塞进来，run.sh 拿去做 SQL 侧证据。"""
    return _FACTS


@pytest.fixture(scope="session")
def events():
    """按会话开实时通道：events(sid) -> LiveChannel（用完自己 close）。"""
    from ws import LiveChannel

    opened: list = []

    def open_channel(sid: int, token: str):
        ch = LiveChannel(BASE, sid, token, timeout=8.0)
        opened.append(ch)
        return ch

    yield open_channel
    for ch in opened:
        ch.close()


def _dump(name: str, obj) -> None:
    try:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
            json.dump(obj, fh, ensure_ascii=False, indent=2, default=str)
    except OSError:
        pass          # 只读挂载等情况下不写证据文件，但不能让用例失败


def pytest_runtest_logreport(report):
    """把每个用例的结果摊平成一份 JSON，run.sh 用它拼报告表格。"""
    if report.when == "call":
        _RESULTS.append({"name": report.nodeid.split("::")[-1],
                         "outcome": report.outcome,
                         "duration": round(report.duration, 2)})
    elif report.when == "setup" and report.outcome != "passed":
        _RESULTS.append({"name": report.nodeid.split("::")[-1],
                         "outcome": "error", "duration": 0})


def pytest_sessionfinish(session, exitstatus):
    # 阶段 B 只跑场景 07，facts 是空的 —— 不能拿空文件覆盖阶段 A 记下的主键
    if _FACTS:
        _dump("facts.json", _FACTS)
    _dump("results.json", _RESULTS)
