# 主动活体（动作挑战）APCER/BPCER 评测报告

- 日期：2026-11-23 · 模式：**proxy** · 协议：docs/verification/active-liveness-protocol.md
- 阈值：{'scale_delta': 0.18, 'scale_hold_frames': 2, 'eye_dark_drop': 0.06, 'eye_hold_frames': 2, 'turn_delta': 0.12, 'turn_hold_frames': 2, 'min_frames': 8}
- 模型就绪：{'landmark': True, 'liveness': True}（liveness=False 时系统级 APCER 恒 0，只看组件级）

> **口径声明（协议 §8）**：代理素材不过翻拍通道，检出质量按干净帧计 →
> 组件级 APCER 是**上限估计**。EYE_CLOSE 无信号通道（只有静态负例）、
> replay-video 与全部 BPCER 必须真机采集后用 `--real` 复测。本报告不替代真机验收。

## 攻击侧（APCER）

| 攻击类型 | 动作 | 场数 | 组件级放行 | 组件级 APCER | 系统级放行 | 预期 |
|---|---|---|---|---|---|---|
| print-move | MOVE_AWAY | 12 | 12 | 1.00 | 0 | PASS（12/12 场） |
| print-move | MOVE_CLOSER | 12 | 12 | 1.00 | 0 | PASS（12/12 场） |
| print-move-weak | MOVE_AWAY | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-move-weak | MOVE_CLOSER | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-roll | TURN_LEFT | 36 | 0 | 0.00 | 0 | PASS（36/36 场） |
| print-roll | TURN_RIGHT | 36 | 0 | 0.00 | 0 | PASS（36/36 场） |
| print-static | EYE_CLOSE | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-static | MOVE_AWAY | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-static | MOVE_CLOSER | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-static | TURN_LEFT | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| print-static | TURN_RIGHT | 12 | 0 | 0.00 | 0 | REJECT（0/12 场） |
| screen-tilt | TURN_LEFT | 7 | 7 | 1.00 | 0 | PASS（7/7 场） |
| screen-tilt | TURN_RIGHT | 1 | 1 | 1.00 | 0 | PASS（1/1 场） |

## 明细（逐场）

字段：expect=PASS/REJECT 为代理预期；finish 为生产 finish 原始结果。

```json
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1001, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1002, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1003, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1004, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1005, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1006, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1007, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1008, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1009, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1010, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1011, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1012, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1013, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1014, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1015, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1016, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1017, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1018, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1019, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1020, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1021, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1022, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1023, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1024, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1025, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1026, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1027, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1028, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1029, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1030, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1031, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1032, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1033, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1034, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1035, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1036, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1037, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1038, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1039, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1040, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1041, "abort": null, "rejectGate": "actionCount", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1042, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1043, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1044, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1045, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1046, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1047, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1048, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1049, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1050, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1051, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1052, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1053, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1054, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1055, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1056, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1057, "abort": null, "rejectGate": "actionCount", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1058, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1059, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1060, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1061, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1062, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1063, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1064, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1065, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1066, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1067, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1068, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1069, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1070, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1071, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1072, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1073, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1074, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1075, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1076, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1077, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1078, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1079, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1080, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1081, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1082, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1083, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1084, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1085, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1086, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1087, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1088, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1089, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1090, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1091, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1092, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1093, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1094, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1095, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1096, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1097, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1098, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1099, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1100, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1101, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1102, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1103, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1104, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1105, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1106, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1107, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1108, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1109, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1110, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1111, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1112, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1113, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1114, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1115, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1116, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1117, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1118, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1119, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1120, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1121, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1122, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1123, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1124, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1125, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1126, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1127, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1128, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1129, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1130, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1131, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1132, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1133, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1134, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1135, "abort": null, "rejectGate": "actionCount", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1136, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1137, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1138, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1139, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1140, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1141, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1142, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1143, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1144, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1145, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1146, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1147, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1148, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1149, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1150, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1151, "abort": null, "rejectGate": "actionCount", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1152, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1153, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1154, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1155, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1156, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1157, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1158, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1159, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1160, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1161, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "screen-tilt", "action": "TURN_LEFT", "expect": "PASS", "sid": 1162, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1163, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1164, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1165, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1166, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1167, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1168, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1169, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1170, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1171, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1172, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1173, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_CLOSER", "expect": "PASS", "sid": 1174, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move", "action": "MOVE_AWAY", "expect": "PASS", "sid": 1175, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": true, "value": 1.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1176, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-move-weak", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1177, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1178, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1179, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1180, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1181, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_LEFT", "expect": "PASS", "sid": 1182, "abort": null, "rejectGate": "actionCount", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-roll", "action": "TURN_RIGHT", "expect": "PASS", "sid": 1183, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_CLOSER", "expect": "REJECT", "sid": 1184, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "MOVE_AWAY", "expect": "REJECT", "sid": 1185, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "EYE_CLOSE", "expect": "REJECT", "sid": 1186, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_LEFT", "expect": "REJECT", "sid": 1187, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
{"attack": "print-static", "action": "TURN_RIGHT", "expect": "REJECT", "sid": 1188, "abort": null, "rejectGate": "liveness", "actionCount": {"passed": false, "value": 0.0, "threshold": 1.0}, "passed": false}
```
