"""落库与提案输出测试（tasks.md 8.4）。

断言：create_task 用 draft/agent 且服务端写 expires_at；
create_subtasks 全部 is_ai_generated=true 且 sort_order 连续；
proposal 事件载荷字段齐全。
"""

from __future__ import annotations

from conftest import DEFAULT_SCRIPTS, parse_sse, post_chat


async def test_draft_task_created_before_proposal(env) -> None:
    """落库参数：status=draft、source=agent、挂在命中分类下。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("准备产品发布会")

    events = parse_sse(response.text)
    proposal = next(data for name, data in events if name == "proposal")

    create_args = env.fake.calls_of("create_task")[0]
    assert create_args["status"] == "draft"
    assert create_args["source"] == "agent"
    assert create_args["category_id"] == "cat-mkt"
    # 服务端写 24h 过期：proposal 事件透传非空 expires_at
    assert proposal["expires_at"] == "2026-09-21T09:00:00+00:00"
    assert proposal["task_id"] == "task-0001"


async def test_subtasks_stored_as_ai_generated_with_sequential_order(env) -> None:
    """子待办落库：is_ai_generated=true（MCP 侧标记）且 sort_order 从 1 连续。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("准备产品发布会")

    subtask_args = env.fake.calls_of("create_subtasks")[0]
    assert subtask_args["task_id"] == "task-0001"
    orders = [item["sort_order"] for item in subtask_args["subtasks"]]
    assert orders == list(range(1, len(orders) + 1))
    assert len(orders) == 3


async def test_proposal_payload_fields_complete(env) -> None:
    """proposal 事件载荷字段齐全且与落库一致。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("准备产品发布会")

    events = parse_sse(response.text)
    proposal = next(data for name, data in events if name == "proposal")

    for field in (
        "task_id",
        "task_title",
        "task_description",
        "category",
        "category_confidence",
        "subtasks",
        "agent_reasoning",
        "expires_at",
    ):
        assert field in proposal, f"proposal 缺少字段 {field}"

    assert proposal["category"] == "市场推广"
    assert proposal["category_confidence"] == 0.9
    assert len(proposal["subtasks"]) == 3
    # 每条子待办含 title/description/sort_order/notes
    for index, item in enumerate(proposal["subtasks"], start=1):
        assert item["sort_order"] == index
        assert "title" in item and "description" in item and "notes" in item

    # done 事件与 proposal 的 task_id 一致
    done = next(data for name, data in events if name == "done")
    assert done["task_id"] == proposal["task_id"]
