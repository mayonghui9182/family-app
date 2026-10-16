"""MCP 任务域工具测试（tasks.md 6.1）。

覆盖四个场景：
1. 21 个工具全部可发现（含 6.2/6.3 与组 9 补充的总结数据通道，一次性断言全集）；
2. get_tasks 组合过滤 + 过期 draft 隐藏；
3. create_task draft 提案：source=agent 且 expires_at 约为 24 小时后；
4. create_subtasks 批量：全部 is_ai_generated=true 且 sort_order 连续。

通道为内存 client：`mcp_session` 夹具 yield 一个连接工厂，用例体内
`async with mcp_session() as session:` 打开（TaskGroup 必须在同一任务内
进入与退出，见 conftest 说明）。工具返回统一封套 dict，code 非 0 视为失败。
"""

from datetime import UTC, datetime, timedelta

from src.models.category import Category
from src.models.task import Task

#: 6.1-6.3 注册的工具全集（组 9 每日总结追加 5 个数据通道工具）
EXPECTED_TOOL_NAMES = {
    # 6.1 任务域（9）
    "get_tasks",
    "get_tasks_changed_since",
    "get_task_detail",
    "create_task",
    "create_subtasks",
    "update_task",
    "update_subtask",
    "delete_subtask",
    "add_note",
    # 6.2 分类与上下文（5）
    "get_categories",
    "create_category",
    "update_category",
    "get_completed_tasks_by_category",
    "get_current_workload",
    # 6.3 学习闭环与每日总结（7）
    "get_rule_templates",
    "create_rule_template",
    "get_template_versions",
    "update_rule_template",
    "log_summary_execution",
    "get_summary_executions",
    "cancel_expired_drafts",
}


def envelope(result):
    """取工具返回的封套 dict：协议层不得报错，业务结果看 code。"""
    assert result.isError is False, f"工具不应抛协议级错误：{result.content}"
    return result.structuredContent


async def test_all_21_tools_discoverable(mcp_session):
    """list_tools 必须恰好暴露 21 个工具（agent 依此编排）。"""
    async with mcp_session() as session:
        result = await session.list_tools()
    names = {tool.name for tool in result.tools}
    assert names == EXPECTED_TOOL_NAMES


async def test_get_tasks_filters_and_hides_expired_draft(mcp_session, mcp_db):
    """组合过滤（状态+分类+关键词）生效，且过期 draft 不出现在任何列表。"""
    category = Category(name="工作")
    mcp_db.add(category)
    await mcp_db.flush()
    task_visible = Task(
        title="准备周报", status="pending", category_id=category.id, source="manual"
    )
    task_completed = Task(
        title="项目复盘", status="completed", category_id=category.id
    )
    task_expired_draft = Task(
        title="过期的提案",
        status="draft",
        expires_at=datetime.now(UTC) - timedelta(hours=1),
    )
    mcp_db.add_all([task_visible, task_completed, task_expired_draft])
    await mcp_db.commit()

    async with mcp_session() as session:
        # 状态 + 分类 + 关键词三重过滤：只剩「准备周报」
        result = await session.call_tool(
            "get_tasks",
            {
                "status": "pending",
                "category_id": str(category.id),
                "keyword": "周报",
            },
        )
        body = envelope(result)
        assert body["code"] == 0
        assert [task["title"] for task in body["data"]] == ["准备周报"]

        # 过期 draft 即便按 draft 状态查询也必须被隐藏
        result = await session.call_tool("get_tasks", {"status": "draft"})
        body = envelope(result)
        assert body["code"] == 0
        assert all(
            task["id"] != str(task_expired_draft.id) for task in body["data"]
        )


async def test_create_task_draft_with_agent_source(mcp_session):
    """draft 提案：source 按调用方写 agent，expires_at 自动约为 24 小时后。"""
    async with mcp_session() as session:
        result = await session.call_tool(
            "create_task",
            {"title": "旅行计划", "status": "draft", "source": "agent"},
        )
    body = envelope(result)
    assert body["code"] == 0
    data = body["data"]
    assert data["status"] == "draft"
    assert data["source"] == "agent"

    expires_at = datetime.fromisoformat(data["expires_at"])
    delta = expires_at - datetime.now(UTC)
    assert timedelta(hours=23, minutes=50) < delta <= timedelta(hours=24, minutes=5)


async def test_create_subtasks_marks_ai_generated(mcp_session):
    """批量子待办：全部 is_ai_generated=true，sort_order 缺省按序 1..N。"""
    async with mcp_session() as session:
        created = envelope(
            await session.call_tool("create_task", {"title": "整理厨房"})
        )["data"]

        result = await session.call_tool(
            "create_subtasks",
            {
                "task_id": created["id"],
                "subtasks": [
                    {"title": "清点餐具"},
                    {"title": "清洁橱柜", "description": "重点去油污"},
                ],
            },
        )
    body = envelope(result)
    assert body["code"] == 0
    rows = body["data"]
    assert [row["title"] for row in rows] == ["清点餐具", "清洁橱柜"]
    assert [row["sort_order"] for row in rows] == [1, 2]
    assert all(row["is_ai_generated"] for row in rows)
