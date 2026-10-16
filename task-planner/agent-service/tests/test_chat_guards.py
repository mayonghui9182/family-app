"""数量与超时约束测试（tasks.md 8.5）。

覆盖 spec 的 5 个场景：>8 截断、<2 重试后报错、30s 超时、
MCP 不可达、落库中途失败（draft 不回滚）。
"""

from __future__ import annotations

import copy

from conftest import DEFAULT_SCRIPTS, parse_sse, post_chat
from src.services.mcp_gateway import McpToolError


def _many_subtasks(count: int) -> list[dict]:
    return [
        {"title": f"步骤{i}", "description": f"说明{i}", "notes": ""}
        for i in range(1, count + 1)
    ]


async def test_excessive_subtasks_truncated_to_eight(env) -> None:
    """>8 条：按顺序截前 8 条，reasoning 注明截断。"""
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["decomposition"][0]["subtasks"] = _many_subtasks(11)
    env.install(scripts)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)

    subtask_args = env.fake.calls_of("create_subtasks")[0]
    assert len(subtask_args["subtasks"]) == 8
    assert [s["title"] for s in subtask_args["subtasks"]] == [
        f"步骤{i}" for i in range(1, 9)
    ]

    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "截取前 8 条" in reasoning

    proposal = next(data for name, data in events if name == "proposal")
    assert len(proposal["subtasks"]) == 8


async def test_too_few_subtasks_retry_then_error(env) -> None:
    """<2 条：重试一次仍不足 → 单个 error，不落提案。"""
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["decomposition"] = [
        {"task_title": "t", "task_description": "d", "subtasks": _many_subtasks(1), "agent_reasoning": ""},
        {"task_title": "t", "task_description": "d", "subtasks": _many_subtasks(1), "agent_reasoning": ""},
    ]
    stub = env.install(scripts)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)

    assert [name for name, _ in events] == ["classification", "error"]
    assert events[-1][1] == {"message": "拆解失败，请重试"}
    # 拆解确实重试了一次，且未落库
    assert stub.calls.count("decomposition") == 2
    assert env.fake.calls_of("create_task") == []
    assert env.fake.calls_of("create_subtasks") == []


async def test_total_timeout_emits_error(env, monkeypatch) -> None:
    """整体 30s 超时（测试中调小预算）：只发 error。"""
    from src.routes import chat as chat_route

    monkeypatch.setattr(
        chat_route.settings, "decomposition_total_timeout_seconds", 0.05
    )
    env.install(DEFAULT_SCRIPTS, delays={"classification": 0.2})

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["error"]
    assert events[0][1] == {"message": "拆解失败，请重试"}
    assert env.fake.calls_of("create_task") == []


async def test_mcp_unreachable_emits_error(env) -> None:
    """MCP 不可达（get_categories 传输失败）：单个 error，不开后续事件。"""
    env.fake.set_error("get_categories", ConnectionError("connection refused"))
    env.install(DEFAULT_SCRIPTS)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["error"]
    assert events[0][1] == {"message": "拆解失败，请重试"}


async def test_mid_persistence_failure_keeps_draft_without_rollback(env) -> None:
    """落库中途失败（create_subtasks 业务失败）：发 error，已建 draft 不回滚。"""
    env.fake.set_error(
        "create_subtasks", McpToolError(40001, "子待办不能为空")
    )
    env.install(DEFAULT_SCRIPTS)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)

    names = [name for name, _ in events]
    assert names[-1] == "error"
    assert "proposal" not in names and "done" not in names
    # draft 已创建且不回滚：没有删除/取消调用（交给 24h 过期收敛）
    assert len(env.fake.calls_of("create_task")) == 1
    assert env.fake.calls_of("update_task") == []
    assert env.fake.calls_of("delete_task") == []
