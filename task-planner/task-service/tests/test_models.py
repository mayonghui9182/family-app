"""数据模型测试（任务 2.1）：建表、默认值、枚举约束、外键级联删除。

依赖开发库 tp-dev-pg（127.0.0.1:55432/task_db_test），连接串见 conftest.py。
"""

import pytest
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError

from src.models import (
    Category,
    Note,
    RuleTemplate,
    RuleTemplateVersion,
    Subtask,
    SummaryExecutionLog,
    Task,
    is_valid_transition,
)


async def test_create_task_with_design_defaults(session):
    """建表与默认值：新任务 user_id=default、status=pending、priority=medium、source=manual。"""
    session.add(Task(title="准备产品发布会"))
    await session.commit()

    row = (await session.execute(select(Task))).scalar_one()
    assert row.user_id == "1"
    assert row.status == "pending"
    assert row.priority == "medium"
    assert row.source == "manual"
    assert row.expires_at is None
    assert row.created_at is not None
    assert row.updated_at is not None


async def test_invalid_enum_value_rejected(session):
    """枚举约束：非法 status 取值被 CheckConstraint 拒绝。"""
    with pytest.raises(IntegrityError):
        async with session.begin_nested():
            session.add(Task(title="非法状态", status="unknown_state"))
            await session.flush()


async def test_subtask_cascade_deleted_with_task(session):
    """外键级联删除：删除任务后其子待办在数据库级一并消失。"""
    task = Task(title="父任务")
    session.add(task)
    await session.flush()
    session.add(Subtask(parent_task_id=task.id, title="子待办一", sort_order=0))
    session.add(Subtask(parent_task_id=task.id, title="子待办二", sort_order=1))
    await session.commit()

    await session.execute(delete(Task).where(Task.id == task.id))
    await session.commit()

    assert (await session.execute(select(Subtask))).scalars().all() == []


async def test_all_seven_tables_roundtrip(session):
    """七张表全量落库：分类/任务/子待办/注意事项/模板/版本快照/总结日志（含循环外键）。"""
    category = Category(name="未分类", description="保留分类")
    session.add(category)
    await session.flush()

    task = Task(title="季度汇报", category_id=category.id)
    session.add(task)
    await session.flush()

    subtask = Subtask(parent_task_id=task.id, title="收集数据", is_ai_generated=True)
    session.add(subtask)
    session.add(Note(target_type="task", target_id=task.id, content="提前一天打印材料"))
    await session.flush()
    session.add(Note(target_type="subtask", target_id=subtask.id, content="数据口径找财务确认"))

    template = RuleTemplate(
        type="decomposition",
        name="季度汇报拆解规则",
        category_id=category.id,
        content={"common_steps": ["收集数据", "撰写初稿"], "estimated_subtask_count": 4, "notes": "预留审阅时间"},
    )
    session.add(template)
    await session.flush()

    version = RuleTemplateVersion(
        template_id=template.id, version=1, content=template.content, change_summary="初始版本"
    )
    session.add(version)
    await session.flush()
    # 回填循环外键：模板当前版本指向版本快照
    template.parent_version_id = version.id

    session.add(SummaryExecutionLog(
        status="success", categories_updated=["未分类"], trigger_source="auto"
    ))
    await session.commit()

    # 逐表回读验证
    assert (await session.execute(select(Category))).scalar_one().name == "未分类"
    assert (await session.execute(select(Task))).scalar_one().title == "季度汇报"
    loaded_sub = (await session.execute(select(Subtask))).scalar_one()
    assert loaded_sub.is_ai_generated is True
    assert len((await session.execute(select(Note))).scalars().all()) == 2
    loaded_tpl = (await session.execute(select(RuleTemplate))).scalar_one()
    assert loaded_tpl.version == 1
    assert loaded_tpl.content["estimated_subtask_count"] == 4
    assert loaded_tpl.parent_version_id == version.id
    assert (await session.execute(select(RuleTemplateVersion))).scalar_one().version == 1
    log = (await session.execute(select(SummaryExecutionLog))).scalar_one()
    assert log.categories_updated == ["未分类"]


def test_status_machine_whitelist():
    """状态机白名单：合法流转通过，跳变与终态复活被拒。"""
    assert is_valid_transition("draft", "pending")
    assert is_valid_transition("draft", "cancelled")
    assert is_valid_transition("pending", "in_progress")
    assert is_valid_transition("pending", "completed")
    assert is_valid_transition("pending", "cancelled")
    assert is_valid_transition("in_progress", "completed")
    assert is_valid_transition("in_progress", "cancelled")
    # 非法：跳变、终态复活、原状态
    assert not is_valid_transition("draft", "completed")
    assert not is_valid_transition("completed", "pending")
    assert not is_valid_transition("cancelled", "pending")
    assert not is_valid_transition("pending", "pending")
    assert not is_valid_transition("unknown", "pending")


async def test_template_version_cascade_deleted_with_template(session):
    """模板删除时版本历史级联删除（ondelete CASCADE）。"""
    template = RuleTemplate(type="decomposition", name="临时模板", content={"common_steps": []})
    session.add(template)
    await session.flush()
    session.add(RuleTemplateVersion(template_id=template.id, version=1, content=template.content))
    await session.commit()

    await session.execute(delete(RuleTemplate).where(RuleTemplate.id == template.id))
    await session.commit()

    assert (await session.execute(select(RuleTemplateVersion))).scalars().all() == []
