"""FastAPI 入口。路由对齐 contracts/openapi/face-api.yaml。

错误码映射（face 域）：
  ModelUnavailable        → 503 FA-9002
  TicketError / PermissionError → 401 FA-1002
  LookupError（挑战不存在/已过期）→ 400 FA-3002（语义「凭据失效请重来」）
  TooManyFaces            → 400 FA-4002
  NoFace                  → 400 FA-4001
  ValueError（图像解析失败）→ 400 FA-9001（码不理想，见 handover「契约软点」）

注意：判定未通过（finish 返回 rejectGate）不是错误，走 200——
客户端需要读 gates 里的逐项数据给用户看具体原因。
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, Header, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app import engine
from app.challenge.service import ChallengeService, NoFace, RetryExceeded, TooManyFaces
from app.challenge.store import ChallengeStore
from app.config import settings
from app.engine import ModelUnavailable
from app.engine.landmark import cosine_similarity
from app.index.vectors import index

logger = logging.getLogger("face-service")
# uvicorn 只配置自己的 logger，这个业务 logger 没人配 handler 时
# INFO 会被 root 的默认 WARNING 级别吃掉——判定明细等于没记。
if not logger.handlers:
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s"))
    logger.addHandler(_h)
    logger.setLevel(logging.INFO)
    logger.propagate = False

app = FastAPI(title="人脸签到 · 人脸服务", version="0.1.0")
_store = None
_service: ChallengeService | None = None
# 采集样本的向量暂存（enroll/sample → Java 落库）。见 CR-009：
# 契约 FaceEnrollReq 只传 sampleKeys，不含向量；Java 没有模型，算不出 embedding，
# 所以由 face 把「sampleKey → 向量」写进 Redis，Java 提交时按 key 取走并落 face_vector。
# 用 DB 0（Java 默认库）而不是挑战用的 DB 1，键前缀隔离。
_enroll_redis = None
ENROLL_KEY_PREFIX = "fa:enroll:sample:"
ENROLL_TTL_SEC = int(os.getenv("FA_ENROLL_SAMPLE_TTL", "900"))


def _trace(request: Request) -> str:
    return request.headers.get("X-Trace-Id", "")


def _resp(request: Request, code: str, message: str, data=None, status=200):
    return JSONResponse(
        status_code=status,
        content={"code": code, "message": message, "data": data,
                 "traceId": _trace(request)},
    )


@app.on_event("startup")
def _startup():
    global _store, _service, _enroll_redis
    try:
        engine.load_all()
    except ModelUnavailable as exc:
        logger.error("模型不可用：%s", exc)
    import redis as redis_lib
    client = redis_lib.Redis(
        host=settings.redis.host, port=settings.redis.port,
        db=settings.redis.db,
        password=settings.redis.password or None,
        decode_responses=True,
    )
    _store = ChallengeStore(client)
    _service = ChallengeService(_store)
    _enroll_redis = redis_lib.Redis(
        host=settings.redis.host, port=settings.redis.port, db=0,
        password=settings.redis.password or None,
        decode_responses=True,
    )
    idx_path = os.getenv("FA_FACE_INDEX_FILE")
    if idx_path:
        n = index().load_json(idx_path)
        logger.info("向量库加载 %d 条（%s）", n, idx_path)

    # 向量快照同步（CR-002）：每 60 秒全量拉取并替换索引。
    # FA_SYNC_ENABLED=0 可关闭（如本机没有 Java 在跑时做算法实验）。
    if os.getenv("FA_SYNC_ENABLED", "1") != "0":
        from app.index.sync import VectorSync
        sync = VectorSync(index(), settings.java.base_url, settings.java.internal_token)
        sync.start_background(interval=float(os.getenv("FA_SYNC_INTERVAL", "60")))
        logger.info("向量同步已启动（Java=%s，间隔 %ss）",
                    settings.java.base_url, os.getenv("FA_SYNC_INTERVAL", "60"))


@app.exception_handler(ModelUnavailable)
async def _model_unavailable(request: Request, exc: ModelUnavailable):
    return _resp(request, "FA-9002", "人脸服务暂不可用：模型缺失或损坏", None, 503)


@app.exception_handler(PermissionError)
async def _permission(request: Request, exc: PermissionError):
    return _resp(request, "FA-1002", str(exc) or "登录已过期，请重新登录", None, 401)


@app.exception_handler(LookupError)
async def _lookup(request: Request, exc: LookupError):
    return _resp(request, "FA-3002", str(exc) or "挑战已过期", None, 400)


@app.exception_handler(TooManyFaces)
async def _multi_face(request: Request, exc: TooManyFaces):
    return _resp(request, "FA-4002", "只能留下一个人进行签到", None, 400)


@app.exception_handler(NoFace)
async def _no_face(request: Request, exc: NoFace):
    return _resp(request, "FA-4001", "未检测到人脸", None, 400)


@app.exception_handler(RetryExceeded)
async def _retry_exceeded(request: Request, exc: RetryExceeded):
    # CR-023：失败次数达上限。与 join 的 FA-3005 同码——学生无论从哪个门进，
    # 看到的都是同一句「次数已达上限」。
    return _resp(request, "FA-3005", str(exc) or "重试次数过多，请稍后再试或提交申诉", None, 429)


@app.exception_handler(ValueError)
async def _bad_input(request: Request, exc: ValueError):
    return _resp(request, "FA-9001", str(exc), None, 400)


# ---------------- health ----------------
@app.get("/face-api/health")
def health(request: Request):
    try:
        optional = engine.optional_status()
        up = engine.detector() is not None and engine.embedder() is not None
        data = {
            "status": "UP" if up else "DEGRADED",
            "modelName": "buffalo_l + minifasnet_v2",
            "modelVersion": "det_10g/w600k_r50/2d106det",
            "vectorCount": index().size(),
            "studentCount": index().size(),
            "loadMs": 0,
            "lastRefreshAt": None,
            "models": {
                "detection": engine.detector() is not None,
                "recognition": engine.embedder() is not None,
                "landmark": optional["landmark"],
                "liveness": optional["liveness"],
            },
            "livenessBackend": "model" if optional["liveness"] else "basic",
        }
        return _resp(request, "FA-0000", "ok", data)
    except ModelUnavailable as exc:
        return _resp(request, "FA-9002", str(exc), {"status": "DEGRADED"}, 200)


# ---------------- challenge ----------------
class ChallengeStartReq(BaseModel):
    baselineFrame: str
    deviceHash: str | None = None
    # CR-010：仅当 FA_ALLOW_ACTION_HINT=1 时生效，验收环境用来稳定复现负例
    action: str | None = None


@app.post("/face-api/challenge/start")
def challenge_start(request: Request, body: ChallengeStartReq,
                    authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    img = engine.decode_b64_jpeg(body.baselineFrame)
    return _resp(request, "FA-0000", "ok", _service.start(token, img, body.action))


@app.post("/face-api/challenge/{cid}/frame")
async def challenge_frame(request: Request, cid: str,
                          frame: UploadFile = File(...), seq: int = Form(...),
                          authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        return _resp(request, "FA-1002", "缺少票据", None, 401)
    raw = await frame.read()
    if len(raw) > settings.max_upload_bytes:
        return _resp(request, "FA-9001", "图像超过大小限制", None, 400)
    img = engine.read_jpeg_bytes(raw)
    return _resp(request, "FA-0000", "ok", _service.frame(cid, img, seq))


@app.post("/face-api/challenge/{cid}/finish")
def challenge_finish(request: Request, cid: str,
                     authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        return _resp(request, "FA-1002", "缺少票据", None, 401)
    result = _service.finish(cid)
    # 判定未通过不是 HTTP 错误：客户端要读 gates 给用户看原因
    return _resp(request, "FA-0000", "ok", result)


# ---------------- enroll ----------------
@app.post("/face-api/enroll/sample")
async def enroll_sample(request: Request, frame: UploadFile = File(...),
                        authorization: str = Header(default="")):
    """采集单张样本：质检 + 特征。

    质检不通过**不是 4xx**，而是 passed=false + reasons —— 客户端要拿
    这些原因告诉学生"往亮处站一点"，而不是一句"采集失败"。
    """
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        return _resp(request, "FA-1002", "缺少登录态", None, 401)
    raw = await frame.read()
    img = engine.read_jpeg_bytes(raw)

    reasons: list[str] = []
    quality = {}

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    lap = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    bright = float(gray.mean())
    quality["laplacian"] = round(lap, 1)
    quality["brightness"] = round(bright, 1)
    if lap < settings.quality.laplacian_min:
        reasons.append("BLURRY")
    if bright < settings.quality.brightness_min:
        reasons.append("TOO_DARK")
    if bright > settings.quality.brightness_max:
        reasons.append("TOO_BRIGHT")

    faces = engine.detector().detect(img)
    if not faces:
        reasons.append("NO_FACE")
        return _resp(request, "FA-0000", "ok", {
            "passed": False, "reasons": reasons, "advice": "请正对摄像头",
            "sampleKey": None, "quality": quality})

    if len(faces) > 1:
        reasons.append("MULTI_FACE")
    face = max(faces, key=lambda f: f.area)
    quality["faceWidth"] = int(face.width)
    quality["faceHeight"] = int(face.height)
    if face.short_side < settings.quality.min_short_side:
        reasons.append("FACE_TOO_SMALL")

    emb = engine.embedder().embedding(img, face.kps)
    sid, sim, _ = index().search_top2(emb)
    if sid is not None and sim is not None and sim >= settings.quality.dup_sim:
        reasons.append("DUPLICATE")

    sample_key = None
    advice = None
    if not reasons:
        sample_key = hashlib.sha256(raw).hexdigest()[:16]
        # 向量暂存，供 Java 提交时落库（CR-009）。质量分口径与响应里的 qualityScore 一致。
        if _enroll_redis is not None:
            payload = json.dumps({
                "embedding": base64.b64encode(np.asarray(emb, dtype="<f4").tobytes()).decode(),
                "dim": int(np.asarray(emb).size),
                "model": os.getenv("FA_MODEL_NAME", "buffalo_l"),
                "qualityScore": round(min(1.0, lap / 400.0), 3),
                "brightness": quality.get("brightness"),
            })
            _enroll_redis.setex(ENROLL_KEY_PREFIX + sample_key, ENROLL_TTL_SEC, payload)
    else:
        advice = {
            "BLURRY": "请拿稳手机，别晃动",
            "TOO_DARK": "往亮处站一点，别背光",
            "TOO_BRIGHT": "光线太强了，请换个角度",
            "FACE_TOO_SMALL": "请离摄像头近一点",
            "MULTI_FACE": "画面里只能有一个人",
            "DUPLICATE": "这张和已采集的样本太像，换个角度",
        }.get(reasons[0], "请调整姿势后重试")

    return _resp(request, "FA-0000", "ok", {
        "passed": not reasons,
        "reasons": reasons,
        "advice": advice,
        "sampleKey": sample_key,
        "quality": {**quality, "qualityScore": round(min(1.0, lap / 400.0), 2)},
    })
