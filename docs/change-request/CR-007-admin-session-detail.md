# CR-007 · 管理端缺「按 id 读单个会话」的接口

- 提出人：WP-10（管理端前端）
- 提出日期：2026-11-19
- 状态：**已用列表翻页兜底，建议补端点**
- 阻塞范围：WP-10 二维码大屏（刷新后要展示场次头信息与状态）
- 严重度：低（已可用，但实现不优雅）

## 1. 问题

大屏页 `/admin/sessions/{id}/live` 需要四样东西：

1. 班级名 / 课程名（**必须并列常驻**，§8.2）；
2. 会话状态（决定「结束签到」「换码」按钮是否可点）；
3. 应到人数；
4. 二维码。

其中 4 有 `GET /sessions/{id}/qrcode`，1 与 3 可以从
`GET /admin/attendance?sessionId=` 的行里拿到（应到名单在建会话时已落 `attendance` 行）。

但 **2 拿不到** —— `sessions/{id}` 下只有 `close` / `rotate-qr` / `qrcode` / `live` 四个子路径，
没有 GET 详情。前端只能：

```ts
for (let p = 1; p <= 3; p++) {
  const data = await api.get('/api/v1/admin/sessions', { page: p, size: 100 })
  const hit = data.items.find(s => s.id === sessionId)
  if (hit) return hit
}
```

即「倒序翻最多 3 页找自己」。300 场以外的历史场次打开大屏会拿不到状态（按钮全部隐藏）。

## 2. 影响

- 正常使用（当场发起 → 立刻看大屏）**没有影响**，因为刚建的会话一定在第一页；
- 影响的是「翻旧场次看录屏式回放」和「刷新后立刻判断状态」；
- 多打 1~3 次列表请求（每次 ≤100 行），在教室内网可忽略。

## 3. 建议

补一个只读端点，与其它模块风格一致：

```yaml
/api/v1/admin/sessions/{id}:
  get:
    summary: 会话详情（含班级名、课程名、状态、各状态计数）
    responses:
      '200': { data: Session }
      '403': 非授权范围
      '404': 不存在
```

前端拿到后可以把 `loadSession()` 的翻页逻辑换成一次请求，
并在 404 时明确提示「场次不存在或不在你的授权范围内」，而不是静默隐藏按钮。
