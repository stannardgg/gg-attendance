"""名称归一化与模糊匹配（Agent 侧的名称解析）。

老师不会照着库里的名字念。实测踩过的三种说法：

- 「大数据一班」——库里叫「大数据1班」（中文数字 ↔ 阿拉伯数字）
- 「高数」——库里叫「高等数学」（简称）
- 「MATH101」——这是课程**编码**，不是名字

匹配分四档，越靠前越可信：

| 档 | 例子 | 分数 |
|---|---|---|
| 归一化后完全相同（含编码） | 大数据1班 / 大数据1班 | 100 |
| 去掉「班 / 课程」后缀后相同 | 高等数学课程 / 高等数学 | 95 |
| 一方包含另一方 | 高等数学上 / 高等数学 | 70~90 |
| 汉字子序列 | 高数 / 高等数学 | 50~90 |

只有最后一档是"猜"，所以加了三道闸：

1. **数字必须整段命中**：「计算机2班」不会被配到「计算机2024级1班」——
   `2` 不是 `2024` 里的一个数字段，直接判不匹配；
2. **首字必须相同**：「高数」→「高等数学」成立，「数学」→「英语」不会瞎配；
3. **同分即歧义**：两个不同对象拿到同一分数就返回候选交回上层反问，
   绝不替老师选——选错就是给别的班发了签到。
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence

# 类别后缀：库里叫「班级」/「课程」，口语里叫「班」/「课」
_SUFFIX: dict[str, tuple[str, ...]] = {
    "class": ("班级", "班"),
    "course": ("课程", "课"),
}

_CN_DIGITS = {
    "零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
    "五": 5, "六": 6, "七": 7, "八": 8, "九": 9,
}
_CN_NUM = re.compile(r"[零〇一二两三四五六七八九十]+")
_CN_GROUP = re.compile(r"[一二两三四五六七八九]?十[一二三四五六七八九]?")
_PUNCT = "，。、；：！？,.;:!?~～…—-·（）()《》【】\"'“”‘’ \t\u3000"
_SPACE_PUNCT = re.compile(r"[\s，。、；：！？,.;:!?~～…·\-—_（）()【】《》\"'“”‘’]+")

# 子序列匹配的最低分（低于此分视为不匹配）
MIN_SCORE = 50


def normalize(text: str | None) -> str:
    """比对用的规范形：全角转半角、小写、去空白标点、中文数字转阿拉伯数字。

    两侧（用户输入与库里的名字）都过这一层，所以「一班」→「1班」是对称的，
    不会出现"只改用户不改库"的单边归一化。
    """
    if not text:
        return ""
    s = unicodedata.normalize("NFKC", str(text)).lower()
    s = _CN_NUM.sub(lambda m: _cn_num(m.group()), s)
    s = _SPACE_PUNCT.sub("", s)
    return s


def compact(text: str | None) -> str:
    """展示与切词用：只做全半角与空白处理，保留原始字符。

    `normalize` 会把「（上）」的括号与「一」都换掉，拿去回显给老师会很怪；
    切分名称时用这一层，报错时说的名字还是老师打出来的样子。
    """
    if not text:
        return ""
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", str(text)))


def _cn_num(raw: str) -> str:
    """「一」→1、「十一」→11、「二十」→20；解析不了保持原样，不外抛。"""
    if raw == "十":
        return "10"
    if "十" in raw:
        head, _, tail = raw.partition("十")
        if not head and not tail:
            return raw
        tens = 1 if not head else _CN_DIGITS.get(head)
        ones = 0 if not tail else _CN_DIGITS.get(tail)
        if tens is None or ones is None:
            return raw
        return str(tens * 10 + ones)
    if len(raw) == 1:
        return str(_CN_DIGITS[raw])
    return raw


def core(text: str | None, kind: str) -> str:
    """去掉类别后缀：「大数据1班」→「大数据1」，「高等数学课程」→「高等数学」。"""
    s = normalize(text)
    for suffix in _SUFFIX.get(kind, ()):
        suffix = normalize(suffix)
        if s.endswith(suffix) and len(s) > len(suffix):
            return s[: -len(suffix)]
    return s


def variants(row: dict[str, Any], kind: str) -> list[str]:
    """一个对象在文本里可能的写法：名字、去后缀的名字、编码。"""
    out: list[str] = []
    for raw in (row.get("name"), row.get("code")):
        if not raw:
            continue
        for value in (normalize(raw), core(raw, kind)):
            if len(value) >= 2 and value not in out:
                out.append(value)
    return out


def score(query: str, row: dict[str, Any], kind: str) -> float:
    """查询串与一个候选对象的匹配分（0 表示不匹配）。"""
    q = normalize(query)
    if not q:
        return 0.0
    name = normalize(row.get("name"))
    code = normalize(row.get("code"))

    if q == name or (code and q == code):
        return 100.0

    qc = _strip_suffix(q, kind)
    nc = _strip_suffix(name, kind)
    if not qc or not nc:
        return 0.0

    if qc == nc:
        return 95.0

    # 包含关系：先过数字闸，再比长度比例（「高数」包含在「高等数学」里只是子串特例）
    if _digit_compatible(qc, nc) and (qc in nc or nc in qc):
        shorter, longer = sorted((len(qc), len(nc)))
        return 70.0 + 20.0 * shorter / longer

    # 子序列：简称的主要来源。「高数」的 高、数 都在「高等数学」里且顺序一致
    if (_digit_compatible(qc, nc) and qc[0] == nc[0]
            and _is_subsequence(qc, nc) and len(qc) >= 2):
        return MIN_SCORE + 40.0 * len(qc) / len(nc)

    return 0.0


def pick(rows: Sequence[dict[str, Any]], query: str, kind: str) -> "NameMatch":
    """在一批候选里挑唯一匹配。

    返回空 `row` 有两种含义，上层必须分开处理：
    - `candidates` 为空 → 库里没有这个东西（对用户说「未存在」）
    - `candidates` 非空 → 有多个同分对象（让用户补编码，不替他选）
    """
    if not normalize(query):
        return NameMatch()

    scored = [(score(query, row, kind), row) for row in rows]
    scored = [(s, row) for s, row in scored if s >= MIN_SCORE]
    if not scored:
        return NameMatch()

    best = max(s for s, _ in scored)
    top = [row for s, row in scored if abs(s - best) < 0.01]
    if len(top) == 1:
        return NameMatch(row=top[0], score=best)

    # 同名多条：编码不同就是不同对象，必须让老师指定
    uniq = {_identity(row) for row in top}
    if len(uniq) == 1:
        return NameMatch(row=top[0], score=best)
    return NameMatch(score=best, candidates=tuple(top[:8]))


def find_all_in_text(text: str, rows: Sequence[dict[str, Any]], kind: str) -> list[dict[str, Any]]:
    """句子里被原样念到的所有对象（按 id 去重，顺序同 rows）。

    这一步不猜：只有库里的名字/编码**原样**出现在句子里才算。
    班级要求至少 3 个字符，否则「1班」这种短名会命中任何一句话。
    返回多个时由调用方反问——「1班和2班」不能取最长那个就开干。
    """
    body = normalize(text)
    if not body:
        return []
    min_len = 3 if kind == "class" else 2
    found: dict[Any, dict[str, Any]] = {}
    for row in rows:
        if any(len(v) >= min_len and v in body for v in variants(row, kind)):
            found[_identity(row)[0]] = row
    return list(found.values())


def describe(rows: Iterable[dict[str, Any]], limit: int = 5) -> str:
    """候选清单文案：同名时带上编码，否则老师无从区分。"""
    rows = list(rows)
    if not rows:
        return "（无）"
    same_name = len({normalize(r.get("name")) for r in rows}) < len(rows)
    parts = []
    for row in rows[:limit]:
        name = str(row.get("name") or "?")
        code = row.get("code")
        parts.append(f"{name}（{code}）" if same_name and code else name)
    text = "、".join(parts)
    if len(rows) > limit:
        text += f" 等 {len(rows)} 个"
    return text


def _identity(row: dict[str, Any]) -> tuple[Any, Any]:
    return (row.get("id"), normalize(row.get("name")))


def _strip_suffix(value: str, kind: str) -> str:
    for suffix in _SUFFIX.get(kind, ()):
        suffix = normalize(suffix)
        if value.endswith(suffix) and len(value) > len(suffix):
            return value[: -len(suffix)]
    return value


def _digit_compatible(query: str, target: str) -> bool:
    """查询里的数字段必须对上目标**末尾**的数字段。

    班名的数字是从左往右叠的：「计算机 2023 级 2 班」里 2023 是年级、2 是班号，
    老师说「计算机2班」指的一定是最后那个 2。所以比的是数字串的后缀，不是"出现过"。

    实测：按"出现过"比，「计算机2班」会同时配到「计算机 2023 级 2 班」（班号 2，对）
    和「计算机 2024 级 1 班」（只是年份里有个 2，错）——后者就是发错班的直接原因。
    """
    qd = re.findall(r"\d+", query)
    if not qd:
        return True
    td = re.findall(r"\d+", target)
    if len(qd) > len(td):
        return False
    return qd == td[-len(qd):]


def _is_subsequence(query: str, target: str) -> bool:
    i = 0
    for ch in target:
        if i < len(query) and ch == query[i]:
            i += 1
    return i == len(query)


@dataclass(frozen=True)
class NameMatch:
    """一次名称解析的结果。`row=None` 时看 `candidates` 区分"没有"与"重名"。"""

    row: dict[str, Any] | None = None
    score: float = 0.0
    candidates: tuple[dict[str, Any], ...] = field(default_factory=tuple)

    @property
    def ambiguous(self) -> bool:
        return self.row is None and len(self.candidates) > 1
