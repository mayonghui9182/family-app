"""routine_matcher stub 行为锚定（diff_engine 已在 Change 5 Task 2 实装，见 test_diff_engine.py）。"""
from app.services.routine_matcher import RoutineMatcher


def test_routine_matcher_hit_and_miss() -> None:
    tpls = [{"name": "大扫除"}, {"name": "采买"}]
    assert RoutineMatcher.match("帮我安排大扫除", tpls) == {"name": "大扫除"}
    assert RoutineMatcher.match("修水管", tpls) is None
    assert RoutineMatcher.match("", tpls) is None
