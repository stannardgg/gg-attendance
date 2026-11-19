# CR-006 · 一次性 code 只定义了「签发」，没有「消费」端点

- 提出人：WP-09（Agent 服务）
- 提出日期：2026-11-19
- 状态：**已补 `/internal/agent/sso/exchange`，待追认**
- 阻塞范围：WP-09（iframe 嵌入链路）
- 严重度：高（不补则 D6 的嵌入方案物理上无法闭环）

## 1. 问题

D6 与 §11.1 规定：管理端不把 JWT 放进 iframe URL，而是先换 **60 秒单次 code**。

`java-api.yaml` 有签发端：

```yaml
POST /api/v1/agent/session   → { code, expiresAt, agentUrl }
```

`internal.yaml` 有三个 Agent 端点，但**没有 `code → token` 的兑换端点**。
契约里写着「Agent 服务用 `POST /agent/sso/exchange` 换成会话 token」（§1125 行附近的描述），
可这个端点在任何 yaml 里都不存在 —— 属于**描述先于契约**的缺口。

结果：Streamlit 拿到 `?code=` 之后无处兑换，只能直接拒绝。

## 2. 方案

| 方案 | 说明 | 问题 |
|---|---|---|
| A 让 Streamlit 拿 code 去调 `/auth/refresh` | 复用现有端点 | code 不是 refreshToken，语义错位；且会把 code 逻辑塞进 auth 模块 |
| B 换成把 JWT 放进 URL | 最简单 | **D6 明确禁止**：JWT 会进 nginx access log 与浏览器历史 |
| **C 新增 `/internal/agent/sso/exchange`（已采用）** | 只服务内部、只能由 agent 容器访问、无日志 | 需在 internal.yaml 补一个端点 |

## 3. 已落地实现

`fa-agent/.../api/InternalAgentSsoController.java`

```
POST /internal/agent/sso/exchange   { code }
  → { token, expiresAt, profile: { accountId, name, role, levels, classIds, courseIds } }
```

- code 用 Redis `GETDEL` 取出即删（**单次消费**，重放必失败）；
- 换取后签发的是 Agent 专用短期 token，TTL 与 code 的剩余有效期解耦（默认 2h）；
- **整个方法不打任何日志**（刻意）：这是唯一一处凭据明文过手的地方，宁可没有可观测性；
- 路由挂在 `/internal/**`，nginx 不对外暴露（§12.3 已只反代 `/agent/`）。

## 4. 待办

请在 `contracts/openapi/internal.yaml` 补该端点（当前以代码为准，契约校验会漏掉它）。
建议同时补 `security: []`（内部端点靠网络隔离，不叠加 JWT）。
