#!/usr/bin/env python3
# =====================================================================
# 契约断言：Agent 工具定义的结构性校验
# =====================================================================
# 归属工作包：WP-01（B1）
# 事实来源  ：项目文档.md §11.4「设计原则」、§11.5「写操作五道闸」、§13.4「隐私合规」
#
# 为什么是脚本而不是约定：
#   §11.4 的五条原则里，有三条是「不该存在的东西」——
#   不该有裸 SQL 工具、不该有复合工具、返回里不该有手机号和向量。
#   这些在代码评审里最容易漏，因为它们"没写出来"反而是对的。
#
# 用法：python3 contracts/scripts/check-agent-tools.py [文件]
# =====================================================================
from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("需要 PyYAML：pip install pyyaml")

# 返回结构里绝对不允许出现的字段（§13.4：Agent 只查文本类考勤数据）
FORBIDDEN_RETURN_FIELDS = {
    "imageurl", "image_url", "snapshotref", "snapshot_ref",
    "embedding", "facevector", "face_vector", "vector",
    "phone", "mobile", "idcard", "id_card", "idnumber",
    "password", "passwordhash", "password_hash", "token", "secret",
}

# 工具名里不允许出现的动词（只允许归档，不允许物理删除；不提供裸 SQL）
FORBIDDEN_TOOL_TOKENS = ("delete", "drop", "truncate", "sql", "query_raw", "exec")

VALID_LEVELS = {"L0", "L1", "L2", "L3"}


def collect_keys(node, out: set[str]) -> None:
    """递归收集所有对象键名（含 properties 下的字段名）。"""
    if isinstance(node, dict):
        for k, v in node.items():
            out.add(str(k).lower())
            collect_keys(v, out)
    elif isinstance(node, list):
        for item in node:
            collect_keys(item, out)


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1
                else Path(__file__).resolve().parents[1] / "openapi" / "agent-tools.yaml")
    if not path.exists():
        print(f"找不到文件：{path}")
        return 2

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    tools = doc.get("tools") or []
    errors: list[str] = []
    warnings: list[str] = []

    if not tools:
        errors.append("tools 为空")

    seen: set[str] = set()
    counts: dict[str, int] = {}

    for t in tools:
        name = t.get("name", "<未命名>")
        level = t.get("level")

        # 1. 名称唯一
        if name in seen:
            errors.append(f"工具名重复：{name}")
        seen.add(name)

        # 2. 级别合法
        if level not in VALID_LEVELS:
            errors.append(f"{name}: level={level!r} 不在 {sorted(VALID_LEVELS)} 内")
        else:
            counts[level] = counts.get(level, 0) + 1

        # 3. 工具名不得含物理删除 / 裸 SQL 语义
        low = name.lower()
        for token in FORBIDDEN_TOOL_TOKENS:
            if token in low:
                errors.append(f"{name}: 工具名含禁用词 {token!r}（只允许归档，不提供裸 SQL）")

        # 4. L2/L3 必须要求确认
        if level in {"L2", "L3"} and t.get("requiresConfirm") is not True:
            errors.append(f"{name}: {level} 工具必须 requiresConfirm=true（§11.5 第 3 道闸）")

        # 5. L0/L1 不得要求确认以外的写语义（防误标级别）
        if level in {"L0", "L1"} and t.get("requiresConfirm") is True:
            warnings.append(f"{name}: {level} 工具却标了 requiresConfirm=true，请复核级别")

        # 6. 返回结构不得含敏感/生物特征字段
        if "returns" in t:
            keys: set[str] = set()
            collect_keys(t["returns"], keys)
            bad = keys & FORBIDDEN_RETURN_FIELDS
            if bad:
                errors.append(f"{name}: 返回结构含禁用字段 {sorted(bad)}（§13.4 结构性隔离）")

        # 7. 参数必须是合法 JSON Schema 骨架
        params = t.get("params")
        if not isinstance(params, dict) or params.get("type") != "object":
            errors.append(f"{name}: params 必须是 type=object 的 JSON Schema")

        # 8. 必须有 description 或 title，否则模型选不准工具
        if not (t.get("description") or t.get("title")):
            warnings.append(f"{name}: 缺 description/title，模型难以正确选工具")

    # 9. 文档自带的 invariants 必须与实现一致（防止改了工具忘了改说明）
    declared = {i.get("rule", "") for i in (doc.get("invariants") or [])}
    if not declared:
        warnings.append("文档缺 invariants 段")

    # 输出
    print("=== 契约断言：Agent 工具定义 ===")
    print(f"文件: {path}")
    print(f"工具数: {len(tools)}  分布: " + "  ".join(f"{k}={counts[k]}" for k in sorted(counts)))
    print()

    for w in warnings:
        print(f"[WARN]      {w}")
    for e in errors:
        print(f"[VIOLATION] {e}")

    print()
    if errors:
        print(f"契约断言失败：{len(errors)} 项违规。改工具定义或提 CR，不要绕过。")
        return 1
    print("契约断言通过：工具分级、确认标记与字段白名单均符合 §11.4。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
