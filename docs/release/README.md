# 发布记录

存放每次发版的发布说明与验收快照。**版本号规则见 `项目文档.md` §19.5。**

## 命名

```
docs/release/v1.0.0.md
docs/release/v0.3.0.md
```

## 发布前检查单

- [ ] `main` 上 CI 全绿
- [ ] 全部 18 个系统级验收场景通过（§16.3）
- [ ] `CHANGELOG.md` 对应版本段落已写，分组为 Added / Changed / Fixed / Removed / Security
- [ ] 数据库迁移脚本可在空库与上一版库上各跑一遍
- [ ] `.env.example` 与代码里读取的环境变量一致（无遗漏、无多余）
- [ ] 备份与恢复演练做过，记录耗时
- [ ] 已知问题已写入发布说明

## 发布动作

```bash
git tag -a v1.0.0 -m "首个可交付版本"
git push origin v1.0.0
gh release create v1.0.0 --notes-file docs/release/v1.0.0.md
```

## 标签约定

| 标签 | 含义 |
|---|---|
| `batch-0-done` ~ `batch-5-done` | 批次完成标记（用于回滚与对比） |
| `contract-v1` | 契约冻结 |
| `v0.x.y` / `v1.0.0` | 可交付版本 |

## 回滚

```bash
# 回到某个批次完成时的状态（仅本地排查用，不要直接强推 main）
git checkout batch-4-done
```

生产回滚优先用上一版镜像 + 数据库向下迁移脚本，**不要靠 `git reset`**。
