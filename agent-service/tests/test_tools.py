"""规则层与文案层（不依赖网络，跑得比图测试还快）。"""

from __future__ import annotations

import datetime as dt

from app.graph.nodes.answer import _citations, _describe
from app.graph.nodes.planner import _clean, _looks_like_create, parse_create_target
from app.graph.nodes.router import _classify
from app.tools.naming import normalize, pick, score
from app.ui.confirm import _remaining_seconds

CLASSES = [
    {"id": 28, "code": "1234", "name": "大数据1班"},
    {"id": 29, "code": "CASIAT", "name": "人脸数据集测试班"},
]
COURSES = [
    {"id": 16, "code": "E2K600C45", "name": "高等数学"},
    {"id": 17, "code": "ENG201", "name": "大学英语"},
]


def test_intent_classification():
    assert _classify("张三今天签到了吗") == "query"
    assert _classify("计算机 2 班这周出勤率") == "query"
    assert _classify("有哪些待处理申诉") == "query"
    assert _classify("帮我给计算机 2 班发起高数签到") == "action"
    assert _classify("结束这场签到") == "action"
    assert _classify("生成一下缺勤名单") == "draft"
    assert _classify("你好") == "chitchat"


def test_intent_rename_is_action_not_query():
    """「把 X 改名为 Y」曾经落到 query，被 _NAME_STATUS 当成「查 X 的考勤」。"""
    assert _classify("把计算机 2 班改名为计算机 3 班") == "action"
    assert _classify("将 E2E课 重命名为 高等数学") == "action"
    # 「改成请假」仍应被更早的补签/请假规则吃掉，不能被改名规则抢走
    assert _classify("把张三改成请假") == "action"


def test_question_words_are_not_names():
    """「本周有多少场签到」问的是场次，不是叫「有多少场」的学生。"""
    from app.graph.nodes.planner import _NAME_STATUS, _QUESTION_WORDS, _SESSION_COUNT

    text = "本周有多少场签到"
    assert _SESSION_COUNT.search(text), "应先命中场次规则"
    m = _NAME_STATUS.search(text)
    assert m and any(w in m.group("name") for w in _QUESTION_WORDS), \
        "护栏要能识别出「有多少场」不是人名"


def test_clean_strips_colloquial_prefix():
    assert _clean("帮我 计算机 2 班") == "计算机 2 班"
    assert _clean("计算机 2 班 的") == "计算机 2 班"


# ---------------------------------------------------------------- 名称解析


def test_normalize_handles_fullwidth_digits_and_spacing():
    """「大数据一班」和「大数据 1 班」必须归一成同一个串，否则永远匹配不上。"""
    assert normalize("大数据一班") == normalize("大数据 1 班") == "大数据1班"
    assert normalize("ＭＡＴＨ１０１") == "math101"
    assert normalize("计算机（2）班") == "计算机2班"


def test_alias_course_name_is_matched():
    """「高数」库里叫「高等数学」——子序列匹配要认，这是最常被说的一句。"""
    assert score("高数", COURSES[0], "course") >= 50
    assert score("高等数学", COURSES[0], "course") == 100
    assert pick(COURSES, "高数", "course").row["name"] == "高等数学"


def test_wrong_class_number_is_not_a_match():
    """「计算机2班」不能配到「计算机 2024 级 1 班」——年份里有个 2 不算。"""
    rows = [{"id": 1, "code": "A", "name": "计算机 2024 级 1 班"},
            {"id": 2, "code": "B", "name": "计算机 2023 级 2 班"}]
    assert pick(rows, "计算机2班", "class").row["id"] == 2
    assert pick(rows[:1], "计算机2班", "class").row is None


def test_same_name_courses_are_ambiguous_not_guessed():
    """同名课程两份（E2E 数据里很常见）必须反问，不能挑第一条发出去。"""
    rows = [{"id": 1, "code": "E2K9FDFC4", "name": "高等数学"},
            {"id": 2, "code": "E2KEAD602", "name": "高等数学"}]
    hit = pick(rows, "高数", "course")
    assert hit.row is None and hit.ambiguous
    answer = parse_create_target("给大数据1班发起高数签到", CLASSES, rows).answer
    assert "同名课程" in answer and "E2K9FDFC4" in answer


def test_create_target_accepts_any_word_order():
    """课程在动词前、夹着时间词、带「课程」后缀，都要认出来。"""
    cases = [
        "给大数据一班高数课程发起签到",
        "帮我给大数据 1 班发起高数签到",
        "高数，大数据一班，开个签到",
        "大数据1班今天发起高数签到",
        "发起大数据1班的高等数学签到",
    ]
    for text in cases:
        target = parse_create_target(text, CLASSES, COURSES)
        assert target.clazz and target.clazz["id"] == 28, text
        assert target.course and target.course["id"] == 16, text


def test_course_omitted_falls_back_only_when_unique():
    """「给大数据一班发起签到」——名下只有一门课时直接用它，有多门就反问。"""
    single = parse_create_target("给大数据一班发起签到", CLASSES, COURSES[:1])
    assert single.course["id"] == 16
    assert parse_create_target("麻烦给大数据1班搞个签到", CLASSES, COURSES[:1]).course["id"] == 16

    many = parse_create_target("给大数据一班发起签到", CLASSES, COURSES)
    assert many.course is None and "请说明是哪门课程" in many.answer


def test_missing_objects_say_weicunzai():
    """库里没有就叫「未存在」，并把可用对象列出来，而不是"没有唯一匹配到"。"""
    class_answer = parse_create_target("给大数据三班发起高数签到", CLASSES, COURSES).answer
    assert "未存在" in class_answer and "大数据1班" in class_answer

    course_answer = parse_create_target("给大数据1班发起物理签到", CLASSES, COURSES).answer
    assert "课程「物理」未存在" in course_answer and "高等数学" in course_answer

    empty = parse_create_target("给大数据1班发起高数签到", CLASSES, [])
    assert "没有可用的课程" in empty.answer


def test_create_intent_widened_but_not_stealing_other_writes():
    assert _looks_like_create("给大数据1班搞个签到")
    assert _looks_like_create("大数据1班开一场签到")
    assert not _looks_like_create("结束这场签到")
    assert not _looks_like_create("把张三补签成出勤")
    assert not _looks_like_create("有哪些待处理申诉")


def test_two_classes_in_one_sentence_are_refused():
    """「1班和2班」不能按最长命中就开干——一次只能一个班。"""
    classes = CLASSES + [{"id": 30, "code": "5678", "name": "大数据2班"}]
    answer = parse_create_target("给大数据1班和大数据2班发起高数签到", classes, COURSES).answer
    assert "多个班级" in answer and "一次只能给一个班" in answer


def test_relative_day_range_is_not_today_only():
    """「近两天」必须解析成昨天~今天。

    旧实现把任何「近…」都当成本周一至今，「近两天」于是退化成「只查今天」，
    再叠上「查无此人」，看起来就像识别错了人。
    """
    from app.graph.common import today
    from app.graph.nodes.planner import _range_from_text

    d0, d1 = _range_from_text("导出近两天考勤记录")
    assert (d1 - d0).days == 1
    assert d1 == today()


def test_relative_week_range_and_chinese_numbers():
    from app.graph.nodes.planner import _range_from_text

    d0, d1 = _range_from_text("近两周考勤")
    assert (d1 - d0).days == 13
    two_day = _range_from_text("最近 2 天")
    assert (two_day[1] - two_day[0]).days == 1


def test_explicit_iso_date_range():
    """从表格里复制的日期区间不能被静默当成「今天」。"""
    from app.graph.nodes.planner import _range_from_text

    d0, d1 = _range_from_text("导出 2026-11-20 到 2026-11-22 的考勤")
    assert (d0.isoformat(), d1.isoformat()) == ("2026-11-20", "2026-11-22")


def test_export_class_name_strips_action_words():
    """「导出计算机 2 班」里的「导出计算机」不是班名。"""
    from app.graph.nodes.planner import _class_in_text

    assert _class_in_text("导出计算机 2 班近两天考勤记录") == "计算机 2 班"
    assert _class_in_text("导出近两天考勤记录") is None


def test_name_guard_covers_export_phrase():
    """正则仍会截出「出近两天」，护栏必须识别它不是人名。"""
    from app.graph.nodes.planner import _NAME_STATUS, _NOT_NAME_WORDS

    m = _NAME_STATUS.search("导出近两天考勤记录")
    assert m is not None
    assert any(w in m.group("name") for w in _NOT_NAME_WORDS)


def test_found_false_citation_is_zero():
    """「未查询到该学生」后面不能再跟「命中 1 条」。"""
    text = _citations([{"tool": "get_attendance_status",
                        "params": {"studentName": "张三"},
                        "result": {"found": False}}])
    assert "命中 0 条" in text


def test_export_citation_counts_rows():
    """导出的「命中条数」是数据行数，不是 1 个文件。"""
    text = _citations([{"tool": "export_attendance",
                        "params": {"dateFrom": "2026-11-21", "dateTo": "2026-11-22"},
                        "result": {"fileName": "考勤记录.xlsx", "rowCount": 12}}])
    assert "命中 12 条" in text


def test_attachment_key_unique_per_message():
    """同样范围导出两次会出现在两条消息里，下载按钮 key 不能撞。

    Streamlit 撞 key 不是"警告"，是直接抛异常把整页打白。
    """
    from app.ui.chat import _key

    att = {"contentBase64": "YWJj"}
    assert _key(att, "m1") == _key(att, "m1")
    assert _key(att, "m1") != _key(att, "m3")


def test_citations_format():
    text = _citations([{"tool": "list_sessions", "params": {"dateFrom": "2026-11-19"}, "result": {"items": [1, 2]}}])
    assert "list_sessions" in text
    assert "命中 2 条" in text


def test_describe_session_summary():
    text = _describe("get_session_summary", {
        "className": "计算机 1 班", "courseName": "高数",
        "total": 10, "present": 8, "late": 1, "absent": 1, "rate": 0.9,
    })
    assert "出勤率 90.00%" in text


def test_countdown_expired_and_future():
    past = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) - dt.timedelta(seconds=5)).isoformat()
    future = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(seconds=30)).isoformat()
    assert _remaining_seconds(past) == 0
    assert 25 <= _remaining_seconds(future) <= 30
