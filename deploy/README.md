# deploy — 部署与运维

一套 Compose 起全栈，Nginx 做统一入口，TLS 终止在这里。

## 目录计划

```
deploy/
├─ docker-compose.yml
├─ .env.example
├─ nginx/
│  ├─ nginx.conf
│  └─ conf.d/app.conf
├─ scripts/
│  ├─ healthcheck.sh
│  └─ backup.sh
└─ tls/                 # 证书挂载点（不入库）
```

## 七个服务

`nginx` / `java` / `face` / `agent` / `mysql` / `redis` / `minio`

## 路由要点

| 路径 | 转发 | 注意事项 |
|---|---|---|
| `/admin/`、`/m/`、`/checkin` | 静态文件 | history 模式需回退到 index.html |
| `/api/` | `java:8080` | 透传 `Authorization`、`X-Real-IP` |
| `/face-api/` | `face:8000` | `client_max_body_size 8m`、关闭请求缓冲降延迟 |
| `/agent/` | `agent:8501` | 需 WebSocket 头；**移除 `X-Frame-Options`** 以便 iframe |

## 边界

- 只放部署产物与脚本，**不放业务代码**。
- 生产密钥只进服务器上的 `.env`，`deploy/.env.example` 里全部留空或写 `***`。
- 数据库备份走离线介质，不把 dump 放回仓库或云同步盘。

## 对应文档

§12.1 服务编排、§12.2 Nginx 职责、§12.3 环境变量、§12.4 联调顺序。

## 当前状态

**B0：仅目录占位。** 由 B6（WP-12）实现。

## 验收

全新机器 `docker compose up -d` 后 10 分钟内全部 healthy；`nginx -t` 通过；备份可恢复。
