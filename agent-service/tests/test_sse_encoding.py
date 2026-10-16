"""SSE 事件格式化断言（Task 1 Step 1）。"""
from app.harness.sse_stream import EVENT_NAMES, format_sse


def test_format_sse_proposal_compact_json() -> None:
    assert format_sse("proposal", {"task_id": 123}) == 'event: proposal\ndata: {"task_id":123}\n\n'


def test_format_sse_error_chinese_unescaped() -> None:
    assert (
        format_sse("error", {"code": 56002, "msg": "AI 返回格式解析失败，请重试"})
        == 'event: error\ndata: {"code":56002,"msg":"AI 返回格式解析失败，请重试"}\n\n'
    )


def test_event_names_are_five() -> None:
    assert EVENT_NAMES == ("classification", "reasoning", "proposal", "done", "error")
