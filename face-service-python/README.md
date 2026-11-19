# face-service-python — 人脸服务

只做三件事：**认得出人、拦得住假、动作判得对**。不写业务库。

## 实际结构（B3 实现中）

| 目录 | 内容 |
|---|---|
| `app/engine/` | 四模型封装：SCRFD 检测、ArcFace 特征、2d106det 关键点、MiniFASNet 活体 |
| `app/index/` | 向量库（内存索引 + JSON 数据源；Java 下发端点见 CR-002） |
| `app/challenge/` | 动作计数（固定 ROI + 连续段）、Redis 状态机、三道门禁、编排 |
| `app/common/tickets.py` | `checkinTicket` 签发与校验（B4 的 Java 侧用同一密钥） |
| `app/main.py` | FastAPI，路由对齐 contracts/openapi/face-api.yaml |
| `scripts/` | 引擎冒烟、（待）calibrate.py / probe_action.py |
| `tests/` | 动作计数判据 + 挑战全流程（fakeredis，不需要真 Redis） |

## 边界（不做什么）

- 不写 `attendance` 等业务表 —— 只调 Java 的 `/internal/checkin/commit`（B4 接线）。
- 不决定"某学生算不算出勤"，只返回判定结果与 `rejectGate`。
- 不做鉴权体系，只校验 Java 签发的 `checkinTicket`。

## 三条不可违反的技术约束

1. **关键点只用于定位 ROI，判动作用 ROI 内的亮度统计。** 关键点开合比量不出局部形变（实测张嘴时只变 1%）。
2. **ROI 在 192 对齐空间锁定一次，之后跨帧复用坐标。** 这个空间与离镜头远近无关，
   等价于"用轮廓点做刚性搬运"；直接按检测框裁剪的话，张嘴本身会把框撑大。
3. **动作只看连续段，不看达标帧总数。** 真动作是连续一段，噪声是零散单帧尖刺。

## 模型文件

四个 ONNX（~189MB）不入库，通过 `FA_MODEL_DIR` 指定目录。
下载源与输入输出规格见隔壁项目 `cangqiong-attendance-main/models/README.md`。
缺失必选模型时 `/face-api/health` 返回 DEGRADED，人脸接口返回 `FA-9002`。

## 快速验证

```bash
pip3 install -r requirements.txt

# 引擎冒烟（Cleaned_CASIA_FaceV5 真实图片）
FA_MODEL_DIR=/path/to/models python3 scripts/smoke_engine.py

# 判据与状态机测试（fakeredis，不需要真 Redis）
FA_MODEL_DIR=/path/to/models python3 -m pytest tests/ -q
```

本机实测：1:N 可分性 gap=0.705（同人 min 0.578 / 不同人 max 0.197，0.45 阈值零交叉）；
合成相机帧检出率 6/6；静态照片活体拒绝率 6/6（max=0.045 << 0.55）。

## 对应文档

§4.4（动作与活体）、§5.4（向量与比对）、§10（整个 Python 服务）、§13.2（精度指标）。
