"""json_guard 2 层解析断言（Task 1 Step 1）。"""
import pytest

from app.harness.json_guard import AIJsonGuardError, parse


def test_parse_valid_json_returns_model() -> None:
    result = parse('{"title":"大扫除","subtasks":[{"title":"擦窗","sort_order":1}]}')
    assert result.title == "大扫除"
    assert result.subtasks[0].title == "擦窗"
    assert result.subtasks[0].sort_order == 1


def test_parse_malformed_raises_56002() -> None:
    with pytest.raises(AIJsonGuardError) as exc_info:
        parse("not json at all")
    assert exc_info.value.code == 56002
    assert "解析失败" in exc_info.value.message


def test_parse_wrong_shape_raises_56002() -> None:
    # 缺 title 必填字段 → Pydantic 校验失败 → 56002
    with pytest.raises(AIJsonGuardError) as exc_info:
        parse('{"foo": 1}')
    assert exc_info.value.code == 56002
