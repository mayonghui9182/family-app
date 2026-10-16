"""审查回归：提案证据原子保存、过期拦截与模板并发版本保护。"""

import asyncio
from copy import deepcopy
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker
from src.mcp import server
from src.models.rule_template import RuleTemplateVersion
from src.models.task import Task
from src.schemas.task import TaskUpdate
from src.schemas.template import TemplateCreate, TemplateUpdate
from src.services import task_service, template_service
from src.services.envelope import Conflict, NotFound


async def test_snapshot_notes_are_atomic_and_survive_user_changes(mcp_session):
    task = (await server.create_task(title="提案", status="draft"))["data"]
    import uuid

    task_id = uuid.UUID(task["id"])
    result = await server.create_subtasks(
        task_id,
        [
            {"title": "打印材料", "notes": "双面打印"},
            {"title": "彩排"},
        ],
    )
    assert result["code"] == 0
    first, second = result["data"]
    detail = (await server.get_task_detail(task_id))["data"]
    original = deepcopy(detail["ai_snapshot"])
    assert original["subtasks"][0]["notes"] == "双面打印"
    assert detail["subtasks"][0]["notes"][0]["content"] == "双面打印"
    assert original["subtasks"][0]["id"] == first["id"]
    assert (await server.update_subtask(uuid.UUID(first["id"]), title="电子材料"))[
        "code"
    ] == 0
    assert (await server.delete_subtask(uuid.UUID(second["id"])))["code"] == 0
    detail = (await server.get_task_detail(task_id))["data"]
    assert detail["ai_snapshot"] == original
    assert [s["title"] for s in detail["subtasks"]] == ["电子材料"]
    assert (await server.create_subtasks(task_id, [{"title": "再次生成"}]))[
        "code"
    ] == 40900


async def test_invalid_batch_leaves_no_snapshot_or_partial_notes(mcp_session):
    import uuid

    task = (await server.create_task(title="提案", status="draft"))["data"]
    task_id = uuid.UUID(task["id"])
    result = await server.create_subtasks(
        task_id,
        [
            {"title": "有效", "notes": "不得半提交"},
            {"title": ""},
        ],
    )
    assert result["code"] != 0
    detail = (await server.get_task_detail(task_id))["data"]
    assert detail["subtasks"] == [] and detail["ai_snapshot"] is None


async def test_expired_draft_detail_and_confirmation_rejected(session):
    task = Task(
        title="过期",
        status="draft",
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    session.add(task)
    await session.commit()
    with pytest.raises(NotFound):
        await task_service.get_task_detail(session, task.id)
    with pytest.raises(NotFound):
        await task_service.update_task(session, task.id, TaskUpdate(status="pending"))
    assert await task_service.list_tasks(session) == []
    await task_service.cancel_expired_drafts(session)
    assert (await task_service.get_task_detail(session, task.id)).status == "cancelled"


async def test_stale_learning_write_never_overwrites_manual_content(session):
    template = await template_service.create_template(
        session, TemplateCreate(name="模板", common_steps=["原步骤"], notes="原备注")
    )
    template_id = template.id
    await template_service.update_template(
        session,
        template_id,
        TemplateUpdate(common_steps=["用户新步骤"], notes="用户新备注"),
    )
    with pytest.raises(Conflict):
        await template_service.update_template(
            session,
            template_id,
            TemplateUpdate(common_steps=["原步骤", "AI新增"], notes="原备注"),
            expected_version=1,
            change_summary="每日总结自动学习",
        )
    await session.refresh(template)
    assert template.content["common_steps"] == ["用户新步骤"]
    assert template.content["notes"] == "用户新备注"
    assert template.version == 2 and template.last_manual_edit_at is not None


async def test_two_writers_cannot_create_duplicate_template_versions(session):
    template = await template_service.create_template(
        session, TemplateCreate(name="并发模板")
    )
    maker = async_sessionmaker(session.bind, expire_on_commit=False)

    async def write(step):
        async with maker() as connection:
            try:
                await template_service.update_template(
                    connection,
                    template.id,
                    TemplateUpdate(common_steps=[step]),
                    expected_version=1,
                )
                return "success"
            except Conflict:
                return "conflict"

    outcomes = await asyncio.gather(write("甲"), write("乙"))
    assert sorted(outcomes) == ["conflict", "success"]
    versions = (
        (
            await session.execute(
                select(RuleTemplateVersion.version).where(
                    RuleTemplateVersion.template_id == template.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert sorted(versions) == [1, 2]


async def test_snapshot_records_exact_template_and_version(mcp_session):
    import uuid

    category = (await server.create_category("发布会"))["data"]
    category_id = uuid.UUID(category["id"])
    template = (
        await server.create_rule_template(
            "decomposition", "模板", category_id, ["准备"], 2
        )
    )["data"]
    task = (
        await server.create_task(title="提案", status="draft", category_id=category_id)
    )["data"]
    task_id = uuid.UUID(task["id"])
    await server.create_subtasks(
        task_id,
        [{"title": "准备"}, {"title": "总结"}],
        template_id=uuid.UUID(template["id"]),
        template_version=1,
    )
    snapshot = (await server.get_task_detail(task_id))["data"]["ai_snapshot"]
    assert snapshot["template_id"] == template["id"]
    assert snapshot["template_version"] == 1
    assert snapshot["category_id"] == category["id"]
