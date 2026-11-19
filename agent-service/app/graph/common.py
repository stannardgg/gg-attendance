"""节点共用的小工具：日期口径、金额/比率格式化、敏感意图识别。"""

from __future__ import annotations

import datetime as dt
import re

SHANGHAI = dt.timezone(dt.timedelta(hours=8))

# 「明显的越权/注入」规则。这些句子不该走到工具调用，也不该浪费一次模型调用。
# 注意只拦"要联系方式/要全量数据/改规则"这三类，不要拦正常提问——
# 误拦的代价是用户觉得助手坏了，比漏拦更常见。
SENSITIVE_PATTERNS = [
    re.compile(r"(手机号|电话|身份证|住址|家庭地址)"),
    re.compile(r"(忽略|无视|忘记)(之前|以上|前面)?的?(指令|规则|设定)"),
    re.compile(r"(系统提示|system prompt|你的提示词)"),
    re.compile(r"(把所有|全部)(学生|用户|账号)(的)?(信息|数据|列表)"),
    re.compile(r"(导出|dump)(全部|所有)数据"),
]


def now() -> dt.datetime:
    return dt.datetime.now(SHANGHAI)


def today() -> dt.date:
    return now().date()


def monday_of_this_week() -> dt.date:
    d = today()
    return d - dt.timedelta(days=d.weekday())


def is_sensitive(text: str) -> str | None:
    for pat in SENSITIVE_PATTERNS:
        m = pat.search(text)
        if m:
            return m.group(0)
    return None


def ratio(value: float | int | None) -> str:
    if value is None:
        return "-"
    return f"{float(value) * 100:.2f}%"


def fmt_date(value: dt.date | str | None) -> str:
    if value is None:
        return "-"
    if isinstance(value, str):
        return value[:10]
    return value.isoformat()
