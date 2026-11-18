# face-service-python — 人脸服务

只做三件事：**认得出人、拦得住假、动作判得对**。不写业务库。

## 职责

| 目录 | 内容 |
|---|---|
| `domain/detect` | SCRFD 检测、五点对齐、质量评分 |
| `domain/embed` | ArcFace 512 维特征、L2 归一化 |
| `domain/match` | 内存索引、1:N 比对、阈值分档 |
| `domain/liveness` | ROI 亮度判据、连续段计数、三道硬门禁、身份一致性 |
| `domain/challenge` | 挑战状态机（Redis） |
| `api/` | `challenge/start`（收基线帧）、`frame`、`finish`、`enroll/sample` |

## 边界（不做什么）

- 不写 `attendance` 等业务表 —— 只调 Java 的 `/internal/checkin/commit`。
- 不决定"某学生算不算出勤"，只返回判定结果与 `rejectGate`。
- 不做鉴权体系，只校验 Java 签发的 `checkinTicket`。

## 三条不可违反的技术约束

1. **关键点只用于定位 ROI，判动作用 ROI 内的亮度统计。** 关键点开合比量不出局部形变（实测张嘴时只变 1%）。
2. **ROI 尺寸在基线帧定一次，之后用轮廓点做刚性搬运。** 否则张嘴本身会把框撑大，等于用会变的尺子量变化。
3. **动作只看连续段，不看达标帧总数。** 真动作是连续一段，噪声是零散单帧尖刺。

## 对应文档

§4.4（动作与活体）、§5.4（向量与比对）、§10（整个 Python 服务）、§13.2（精度指标）。

## 当前状态

**B0：仅目录占位。** 由 B3（WP-05 / WP-06 / WP-07）实现。

## 模型文件

`models/*.onnx` 不入库（体积 + 来源许可原因），需按 §10.1 下载后放入 `models/`。
缺失时服务应**照常启动**，人脸相关接口返回 `FA-9002` 并说明缺哪个文件。

## 自检

```bash
python -c "from app.recognizer import recognizer; print(recognizer.has_liveness(), recognizer.has_landmark())"
curl -s localhost:8000/face-api/health | jq
```
