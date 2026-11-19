"""检查点的分数转换。

这里只测 `_score`：它是纯函数，而真正写 Redis 的部分靠 `test_checkpoint_redis.py`
在本地/CI 有 Redis 时跑。分开是因为前者是**曾经真出过 bug**的地方
（LangGraph 给的是 ISO 字符串，直接 float() 会让整个会话存不进检查点），
必须钉死在无依赖的单测里。
"""

from __future__ import annotations

from datetime import datetime, timezone

from app.checkpoint.redis_saver import _score


def test_iso_string_is_parsed() -> None:
    # LangGraph 实际写入的格式
    got = _score("2026-11-19T08:03:31.910540+00:00")
    want = datetime(2026, 11, 19, 8, 3, 31, 910540, tzinfo=timezone.utc).timestamp()
    assert got == want


def test_z_suffix_is_accepted() -> None:
    """前端/JS 习惯用 Z 结尾，后端也会遇到，不能只认 +00:00。"""
    assert _score("2026-11-19T08:03:31Z") == _score("2026-11-19T08:03:31+00:00")


def test_numbers_pass_through() -> None:
    assert _score(1700000000.5) == 1700000000.5
    assert _score(1700000000) == 1700000000.0


def test_bad_input_falls_back_to_now() -> None:
    """解析不了也不能抛：宁可排序失真，也不能让整轮对话丢检查点。"""
    before = datetime.now().timestamp()
    for bad in ("", "   ", "not-a-date", None, True):
        got = _score(bad)
        assert got >= before - 1, f"{bad!r} 应退回当前时间，实际 {got}"
