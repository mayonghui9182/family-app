"""POST /api/chat SSE 契约测试（tasks.md 8.1）。

覆盖：成功事件顺序、reasoning 分块拼接、空 message 400 不开流、
失败只发单个 error 且其后无 proposal/done。
"""

from __future__ import annotations

from conftest import DEFAULT_SCRIPTS, MISSING_MESSAGE, parse_sse, post_chat


async def test_successful_run_emits_documented_event_order(env) -> None:
    """成功运行：classification → reasoning×N → proposal → done。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("下周三准备产品发布会，需要做20分钟演示")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")

    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "classification"
    assert names[-2] == "proposal"
    assert names[-1] == "done"
    assert names[1:-2] and all(name == "reasoning" for name in names[1:-2])

    # done 载荷固定为 {"task_id", "status": "draft_created"}
    done_data = events[-1][1]
    assert done_data == {"task_id": "task-0001", "status": "draft_created"}


async def test_reasoning_is_streamed_in_chunks(env) -> None:
    """reasoning 多块拼接为完整推理文本。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("准备产品发布会")

    events = parse_sse(response.text)
    reasoning_events = [data for name, data in events if name == "reasoning"]
    assert len(reasoning_events) >= 2
    full = "".join(item["chunk"] for item in reasoning_events)
    # 完整推理应包含拆解说明与冷启动标注
    assert "拆解" in full
    assert "无历史参考" in full


async def test_blank_message_rejected_without_stream(env) -> None:
    """空白 message：400 且响应不是 SSE。"""
    response = await post_chat("   ")
    assert response.status_code == 400
    assert not response.headers["content-type"].startswith("text/event-stream")


async def test_missing_message_rejected_without_stream(env) -> None:
    """缺失 message 字段：同样 400 不开流。"""
    response = await post_chat(MISSING_MESSAGE)
    assert response.status_code == 400
    assert not response.headers["content-type"].startswith("text/event-stream")


async def test_llm_failure_emits_single_error_event(env) -> None:
    """LLM 传输失败：只发一个 error，其后无 proposal/done。"""
    env.install(
        {"classification": ["这不是 JSON，也提取不到花括号块，彻底不可解析"]}
    )
    response = await post_chat("准备产品发布会")

    assert response.status_code == 200
    events = parse_sse(response.text)
    assert len(events) == 1
    name, data = events[0]
    assert name == "error"
    assert data == {"message": "拆解失败，请重试"}
    # 未落库：没有创建任务调用
    assert env.fake.calls_of("create_task") == []
