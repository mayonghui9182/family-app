"""MCP 分类与上下文工具测试（tasks.md 6.2）。

覆盖三个场景：
1. 分类/上下文 5 个工具全部可发现；
2. get_completed_tasks_by_category 返回 few-shot 结构
   （含分类名、已办任务的子待办明细）；
3. get_current_workload 返回未完成数、优先级分布、即将到期数三字段，
   且 completed/cancelled 不计入。

`mcp_session` 夹具 yield 连接工厂，用例体内 `async with` 打开
（TaskGroup 必须在同一任务内进入与退出，见 conftest 说明）。
"""

from datetime import UTC, datetime, timedelta

from src.models.category import Category
from src.models.subtask import Subtask
from src.models.task import Task

CONTEXT_TOOL_NAMES = {
    "get_categories",
    "create_category",
    "update_category",
    "get_completed_tasks_by_category",
    "get_current_workload",
}


def envelope(result):
    """取工具返回的封套 dict：协议层不得报错，业务结果看 code。"""
    assert result.isError is False, f"工具不应抛协议级错误：{result.content}"
    return result.structuredContent


async def test_context_tools_discoverable(mcp_session):
    """5 个分类/上下文工具均可被 agent 发现。"""
    async with mcp_session() as session:
        result = await session.list_tools()
    names = {tool.name for tool in result.tools}
    assert CONTEXT_TOOL_NAMES <= names


async def test_get_completed_tasks_by_category_few_shot(mcp_session, mcp_db):
    """few-shot 上下文：只含 completed 任务，子待办按结构返回。"""
    category = Category(name="家庭事务")
    mcp_db.add(category)
    await mcp_db.flush()

    task_done = Task(
        title="大扫除",
        description="春节前大扫除",
        status="completed",
        category_id=category.id,
    )
    task_open = Task(title="买菜", status="pending", category_id=category.id)
    mcp_db.add_all([task_done, task_open])
    await mcp_db.flush()
    mcp_db.add_all(
        [
            Subtask(
                parent_task_id=task_done.id,
                title="擦窗户",
                sort_order=1,
                status="completed",
            ),
            Subtask(
                parent_task_id=task_done.id,
                title="拖地板",
                sort_order=2,
                status="completed",
            ),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        # 按分类名查询（agent 通常只知道分类名）
        result = await session.call_tool(
            "get_completed_tasks_by_category", {"category_name": "家庭事务"}
        )
    body = envelope(result)
    assert body["code"] == 0
    data = body["data"]
    assert data["category"] == "家庭事务"
    assert [task["title"] for task in data["tasks"]] == ["大扫除"]
    assert data["tasks"][0]["description"] == "春节前大扫除"
    assert [sub["title"] for sub in data["tasks"][0]["subtasks"]] == [
        "擦窗户",
        "拖地板",
    ]
    assert data["tasks"][0]["subtasks"][0]["sort_order"] == 1


async def test_get_current_workload_counts(mcp_session, mcp_db):
    """workload：completed/cancelled 不计；优先级分布与 7 天内到期数正确。"""
    now = datetime.now(UTC)
    mcp_db.add_all(
        [
            # 计入：高优先级、3 天后到期（落在 7 天窗口内）
            Task(
                title="交水电费",
                status="pending",
                priority="high",
                due_date=now + timedelta(days=3),
            ),
            # 计入：中优先级、无截止时间
            Task(title="整理相册", status="in_progress", priority="medium"),
            # 不计入：已完成 / 已取消
            Task(title="旧任务", status="completed", priority="high"),
            Task(title="放弃的任务", status="cancelled", priority="low"),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool("get_current_workload", {})
    body = envelope(result)
    assert body["code"] == 0
    data = body["data"]
    assert data["unfinished_count"] == 2
    assert data["priority_distribution"] == {"high": 1, "medium": 1, "low": 0}
    assert data["due_soon_count"] == 1
