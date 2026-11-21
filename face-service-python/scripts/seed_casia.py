#!/usr/bin/env python3
"""批量把 CASIA 人脸数据集灌入线上库（CR-012 后的合法链路）。

走完整的业务链路，不绕过任何一道门禁：
  ADMIN 建班/建学生 → 学生激活 → 逐张上传 /face-api/enroll/sample
  （face 服务质检+算向量，暂存 Redis，TTL 15min）→ 学生提交
  /api/v1/mobile/face/enroll（落库，向量经 VectorCrypto 加密）→
  ADMIN 审核 face/review → ACTIVE。

为什么不用 SQL 直插：绕过单一写入口（face_vector 只有 Java enroll 落库一个写入口），
也绕过 CR-012 的字段级加密写路径（直插即明文行）；且入库的是特征向量，不是原图——
原图不落盘是上线时定死的设计（§13.3，CR-012 记录）。

幂等：学号已存在则复用；faceStatus=ACTIVE 跳过；PENDING_REVIEW 补审核；
REJECTED 重新采集。可安全重跑。

用法：
  python3 seed_casia.py [--count 100] [--start 0]
                        [--base https://127.0.0.1]
                        [--dataset /path/to/Cleaned_CASIA_FaceV5]

只吃干净原图 {XXX}/{XXX}_{N}.jpg；*_block_eyes.jpg 等活体攻击变体不进向量库。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

REPO = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = REPO / "Cleaned_CASIA_FaceV5"

CLASS_CODE = "CASIAT"
CLASS_NAME = "人脸数据集测试班"
STUDENT_PASSWORD = "Casia#Seed2026"
CONSENT_VERSION = "v1"
SHOTS = 5  # 每人干净原图数：{XXX}_{0..4}.jpg


def load_env() -> dict:
    env = {}
    p = REPO / "deploy" / ".env"
    if not p.exists():
        sys.exit("找不到 deploy/.env（需要 BOOTSTRAP_ADMIN_USERNAME/PASSWORD）")
    for line in p.read_text().splitlines():
        m = re.match(r"^([A-Z][A-Z0-9_]+)=(.*)$", line.strip())
        if m:
            env[m.group(1)] = m.group(2)
    return env


class Api:
    """极薄封装：统一 BASE/verify=False/重试，返回 (ok, data_or_message)。"""

    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.s = requests.Session()
        self.s.verify = False

    def _call(self, method: str, path: str, **kw):
        last = None
        for attempt in range(3):
            try:
                r = self.s.request(method, self.base + path, timeout=120, **kw)
                j = r.json()
                if j.get("code") == "FA-0000":
                    return True, j.get("data")
                return False, f'{j.get("code")}: {j.get("message")}'
            except (requests.ConnectionError, requests.Timeout) as e:
                last = e
                time.sleep(1.5 * (attempt + 1))
        return False, f"网络重试耗尽: {last}"

    def get(self, path, token=None, **kw):
        return self._call("GET", path, headers=self._h(token), **kw)

    def post(self, path, token=None, **kw):
        return self._call("POST", path, headers=self._h(token), **kw)

    @staticmethod
    def _h(token):
        return {"Authorization": f"Bearer {token}"} if token else {}


def framed(jpg: Path) -> bytes:
    """预裁剪小图适配：CASIA Cleaned 版是 112×112 已裁剪人脸块（ArcFace
    标准输入尺寸），脸占满画面落在 SCRFD 训练分布外——实测检出 0 张；
    放大到脸占画面约 6 成后稳定检出。质检阈值（laplacian 80 / 短边 120 /
    亮度 60~190）按 112→192 贴 320×240 画布实测全绿：
    lap 102 / short 137 / bright 105（2026-11-20 容器内实测）。
    背景纯灰只影响整图模糊度统计，embedding 只用对齐后的人脸区域。
    真实摄像头尺寸的图（>320×240）原样上传，不做任何处理。"""
    img = cv2.imdecode(np.fromfile(str(jpg), dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError(f"图片解码失败: {jpg.name}")
    h, w = img.shape[:2]
    if w <= 320 and h <= 240:
        # 统一锐化：低纹理样本放大稀释后 laplacian 会跌破 80（000_1 实测 99→86），
        # 所有图走同一链路保证同人 5 张向量处理一致
        blur = cv2.GaussianBlur(img, (0, 0), 1.0)
        img = cv2.addWeighted(img, 1.5, blur, -0.5, 0)
        face = cv2.resize(img, (192, 192), interpolation=cv2.INTER_CUBIC)
        canvas = np.full((240, 320, 3), (128, 124, 120), np.uint8)
        canvas[24:216, 64:256] = face
        img = canvas
    ok, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
    if not ok:
        raise ValueError(f"JPEG 重编码失败: {jpg.name}")
    return buf.tobytes()


def sample_upload(api: Api, token: str, jpg: Path):
    """上传单张采集图；返回 (sampleKey | None, 原因)。"""
    ok, data = api.post(
        "/face-api/enroll/sample",
        token=token,
        files={"frame": (jpg.name, framed(jpg), "image/jpeg")},
    )
    if not ok:
        return None, data
    if not data.get("passed"):
        return None, ",".join(data.get("reasons", [])) or "质检未过"
    return data.get("sampleKey"), None


def seed_identity(api: Api, admin: str, ident: str, class_id: int) -> tuple[str, str]:
    """灌单个身份。返回 (状态, 说明)。状态：done / skip / fail。"""
    no = f"C{int(ident)}"
    name = f"数据集-{ident}"

    # 1) 学生档案（幂等：keyword 精确查学号）
    ok, data = api.get("/api/v1/admin/students", token=admin,
                       params={"keyword": no, "page": 1, "size": 1})
    if not ok:
        return "fail", f"查学生失败 {data}"
    hit = next((it for it in data["items"] if it["studentNo"] == no), None)
    if hit:
        sid, face_status = hit["id"], hit["faceStatus"]
    else:
        ok, data = api.post("/api/v1/admin/students", token=admin,
                            json={"studentNo": no, "name": name, "classId": class_id})
        if not ok:
            return "fail", f"建学生失败 {data}"
        sid, face_status = data["id"], data.get("faceStatus", "NONE")

    if face_status == "ACTIVE":
        return "skip", "已 ACTIVE"

    # 2) 学生令牌（激活成功直接返回 token；已激活则登录）
    ok, data = api.post("/api/v1/auth/student/activate",
                        json={"studentNo": no, "name": name,
                              "classCode": CLASS_CODE, "password": STUDENT_PASSWORD})
    if ok:
        stu_tok = data["accessToken"]
    else:
        ok, data = api.post("/api/v1/auth/login",
                            json={"username": no, "password": STUDENT_PASSWORD})
        if not ok:
            return "fail", f"学生令牌获取失败 {data}"
        stu_tok = data["accessToken"]

    # 3) PENDING_REVIEW 只欠审核
    if face_status == "PENDING_REVIEW":
        ok, data = api.post(f"/api/v1/admin/students/{sid}/face/review",
                            token=admin, json={"approved": True})
        return ("done", "补审核→ACTIVE") if ok else ("fail", f"审核失败 {data}")

    # 4) 采集 5 张（REJECTED 允许重采；face 服务质检+算向量+暂存 Redis）
    keys, errors = [], []
    for n in range(SHOTS):
        jpg = DEFAULT_DATASET / ident / f"{ident}_{n}.jpg"
        if not jpg.exists():
            errors.append(f"缺图{jpg.name}")
            continue
        key, why = sample_upload(api, stu_tok, jpg)
        (keys.append(key) if key else errors.append(f"{jpg.name}:{why}"))
        if len(keys) >= SHOTS:
            break
    if len(keys) < SHOTS:
        return "fail", f"样本不足({len(keys)}/{SHOTS}) {'; '.join(errors)}"

    # 5) 提交落库（Java 单一写入口，向量在此被 VectorCrypto 加密）
    ok, data = api.post("/api/v1/mobile/face/enroll", token=stu_tok,
                        json={"sampleKeys": keys, "consentVersion": CONSENT_VERSION})
    if not ok:
        return "fail", f"提交失败 {data}"

    # 6) ADMIN 审核 → ACTIVE
    ok, data = api.post(f"/api/v1/admin/students/{sid}/face/review",
                        token=admin, json={"approved": True})
    if not ok:
        return "fail", f"审核失败 {data}"
    return "done", "全流程完成"


def main() -> int:
    global DEFAULT_DATASET
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--base", default="https://127.0.0.1")
    ap.add_argument("--dataset", default=str(DEFAULT_DATASET))
    args = ap.parse_args()
    DEFAULT_DATASET = Path(args.dataset)

    env = load_env()
    api = Api(args.base)

    ok, data = api.post("/api/v1/auth/login", json={
        "username": env.get("BOOTSTRAP_ADMIN_USERNAME", "admin"),
        "password": env.get("BOOTSTRAP_ADMIN_PASSWORD", ""),
    })
    if not ok:
        sys.exit(f"管理员登录失败：{data}")
    admin = data["accessToken"]
    print(f"[seed] 管理员登录 OK；目标 {args.count} 个身份（{args.start:03d} 起）")

    # 班级幂等
    ok, data = api.get("/api/v1/admin/classes", token=admin,
                       params={"keyword": CLASS_CODE, "page": 1, "size": 10})
    cls = next((it for it in (data or {}).get("items", [])
                if it["code"] == CLASS_CODE), None)
    if cls:
        class_id = cls["id"]
        print(f"[seed] 复用已有班级 id={class_id}")
    else:
        ok, data = api.post("/api/v1/admin/classes", token=admin,
                            json={"code": CLASS_CODE, "name": CLASS_NAME})
        if not ok:
            sys.exit(f"建班失败：{data}")
        class_id = data["id"]
        print(f"[seed] 新建班级 id={class_id}")

    stats = {"done": 0, "skip": 0, "fail": 0}
    t0 = time.time()
    for idx in range(args.start, args.start + args.count):
        ident = f"{idx:03d}"
        t1 = time.time()
        try:
            state, msg = seed_identity(api, admin, ident, class_id)
        except Exception as e:  # 单人异常不中断批次
            state, msg = "fail", f"异常 {type(e).__name__}: {e}"
        stats[state] += 1
        mark = {"done": "+", "skip": "=", "fail": "x"}[state]
        print(f"[{mark}] {ident} {msg}（{time.time()-t1:.1f}s，"
              f"完成 {idx-args.start+1}/{args.count}）", flush=True)

    dt = time.time() - t0
    print(f"\n[seed] 完成：成功 {stats['done']}，跳过 {stats['skip']}，"
          f"失败 {stats['fail']}，耗时 {dt:.0f}s")
    print(f"[seed] 预期向量增量 +{stats['done'] * SHOTS} 条（每人 {SHOTS} 张）；"
          f"失败身份可用 --start {args.start} 重跑（幂等）")
    return 0 if stats["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
