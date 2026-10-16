"""MCP 每日总结数据通道工具测试（组 9 前置：tasks.md 9.1/9.3/9.4/9.5/9.6）。

agent-service 只能经 MCP 取数，故总结链路依赖的五个通道工具在此单独验收：
1. `get_tasks_changed_since`：当天窗口查询（含子待办增删改经父任务 updated_at 捕获、
   过期 draft 隐藏）；
2. `create_rule_template`：分类无模板时自动创建（version=1 + 版本行 change_summary）；
3. `get_template_versions`：暴露「用户手动编辑」标记与时间，供冲突策略判定；
4. `get_summary_executions`：同日重复触发 409 判定与退避/连续失败统计的读侧；
5. `cancel_expired_drafts`：过期 draft → cancelled，未过期与其他状态不动。

`mcp_session` 夹具 yield 连接工厂，用例体内 `async with` 打开
（TaskGroup 必须在同一任务内进入与退出，见 conftest 说明）。
"""

from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from src.models.category import Category
from src.models.rule_template import RuleTemplate, RuleTemplateVersion
from src.models.subtask import Subtask
from src.models.summary_log import SummaryExecutionLog
from src.models.task import Task


def envelope(result):
    """取工具返回的封套 dict：协议层不得报错，业务结果看 code。"""
    assert result.isError is False, f"工具不应抛协议级错误：{result.content}"
    return result.structuredContent


def _steps(*items: str) -> dict:
    """构造模板 content 三字段（版本行与模板行共用）。"""
    return {
        "common_steps": list(items),
        "estimated_subtask_count": len(items),
        "notes": "",
    }


# --------------------------------------------------------------------------
# 9.1 窗口查询
# --------------------------------------------------------------------------


async def test_changed_since_window_boundary(mcp_session, mcp_db):
    """窗口按 created_at/updated_at 双谓词圈定：窗外任务不返回。"""
    now = datetime.now(UTC)
    old = now - timedelta(hours=5)
    recent = now - timedelta(minutes=10)
    mcp_db.add_all(
        [
            Task(title="窗口内修改", status="pending", created_at=old, updated_at=recent),
            Task(title="窗口外任务", status="pending", created_at=old, updated_at=old),
            Task(title="窗口内新建", status="pending", created_at=recent, updated_at=recent),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "get_tasks_changed_since",
            {"since": (now - timedelta(hours=1)).isoformat()},
        )
    body = envelope(result)
    assert body["code"] == 0
    assert {row["title"] for row in body["data"]} == {"窗口内修改", "窗口内新建"}


async def test_changed_since_captures_subtask_add_and_delete(mcp_session, mcp_db):
    """子待办新增与物理删除都要把父任务带进窗口（删除无痕迹，只能靠父任务时间戳）。"""
    now = datetime.now(UTC)
    old = now - timedelta(hours=6)
    task_added = Task(title="新增子待办", status="pending", created_at=old, updated_at=old)
    task_deleted = Task(title="删除子待办", status="pending", created_at=old, updated_at=old)
    mcp_db.add_all([task_added, task_deleted])
    await mcp_db.flush()
    doomed = Subtask(
        parent_task_id=task_deleted.id,
        title="被用户删掉的一步",
        sort_order=1,
        is_ai_generated=True,
        created_at=old,
        updated_at=old,
    )
    mcp_db.add(doomed)
    await mcp_db.commit()
    added_id, deleted_id, doomed_id = task_added.id, task_deleted.id, doomed.id

    since = (now - timedelta(hours=1)).isoformat()
    async with mcp_session() as session:
        before = envelope(
            await session.call_tool("get_tasks_changed_since", {"since": since})
        )
        assert before["code"] == 0
        assert before["data"] == []

        await session.call_tool(
            "create_subtasks",
            {"task_id": str(added_id), "subtasks": [{"title": "用户补的一步"}]},
        )
        await session.call_tool("delete_subtask", {"subtask_id": str(doomed_id)})

        after = envelope(
            await session.call_tool("get_tasks_changed_since", {"since": since})
        )

    assert after["code"] == 0
    assert {row["title"] for row in after["data"]} == {"新增子待办", "删除子待办"}
    # 父任务时间戳确实被刷新（agent 侧据此判定「当天有修改行为」）
    await mcp_db.refresh(task_deleted)
    assert task_deleted.updated_at > old


async def test_changed_since_hides_expired_draft(mcp_session, mcp_db):
    """过期未确认的 draft 与列表接口口径一致地隐藏，未过期的 draft 仍在窗口内。"""
    now = datetime.now(UTC)
    recent = now - timedelta(minutes=5)
    mcp_db.add_all(
        [
            Task(
                title="过期提案",
                status="draft",
                expires_at=now - timedelta(hours=1),
                created_at=recent,
                updated_at=recent,
            ),
            Task(
                title="有效提案",
                status="draft",
                expires_at=now + timedelta(hours=23),
                created_at=recent,
                updated_at=recent,
            ),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "get_tasks_changed_since", {"since": (now - timedelta(hours=2)).isoformat()}
        )
    body = envelope(result)
    assert body["code"] == 0
    assert [row["title"] for row in body["data"]] == ["有效提案"]


# --------------------------------------------------------------------------
# 9.3 模板自动创建 / 9.4 手动编辑判定
# --------------------------------------------------------------------------


async def test_create_rule_template_writes_first_version(mcp_session, mcp_db):
    """自动建模板：version=1、is_active=true，版本行记下「每日总结自动创建」。"""
    category = Category(name="市场推广")
    mcp_db.add(category)
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "create_rule_template",
            {
                "template_type": "decomposition",
                "name": "市场推广拆解模板",
                "category_id": str(category.id),
                "common_steps": ["确定推广渠道", "准备物料清单"],
                "estimated_subtask_count": 2,
                "notes": "预算需提前确认",
                "change_summary": "每日总结自动创建",
            },
        )
    body = envelope(result)
    assert body["code"] == 0
    data = body["data"]
    assert data["version"] == 1
    assert data["is_active"] is True
    assert data["category_name"] == "市场推广"
    assert data["content"]["common_steps"] == ["确定推广渠道", "准备物料清单"]
    assert data["content"]["notes"] == "预算需提前确认"

    rows = (await mcp_db.execute(select(RuleTemplateVersion))).scalars().all()
    assert len(rows) == 1
    assert rows[0].version == 1
    assert rows[0].change_summary == "每日总结自动创建"


async def test_create_rule_template_rejects_bad_input(mcp_session, mcp_db):
    """非法 type 与空 change_summary 一律拒绝，且不留下半成品模板。"""
    async with mcp_session() as session:
        bad_type = envelope(
            await session.call_tool(
                "create_rule_template",
                {"template_type": "unknown", "name": "坏类型模板"},
            )
        )
        blank_summary = envelope(
            await session.call_tool(
                "create_rule_template",
                {
                    "template_type": "decomposition",
                    "name": "空摘要模板",
                    "change_summary": "   ",
                },
            )
        )

    assert bad_type["code"] != 0
    assert blank_summary["code"] != 0
    assert (await mcp_db.execute(select(RuleTemplate))).scalars().all() == []


async def test_get_template_versions_exposes_manual_edit(mcp_session, mcp_db):
    """版本历史按 version 倒序，含 change_summary 与 created_at（手动编辑判定依据）。"""
    now = datetime.now(UTC)
    template = RuleTemplate(
        type="decomposition", name="市场推广拆解模板", content=_steps("确定推广渠道"), version=3
    )
    mcp_db.add(template)
    await mcp_db.flush()
    mcp_db.add_all(
        [
            RuleTemplateVersion(
                template_id=template.id,
                version=1,
                content=_steps("确定推广渠道"),
                change_summary="创建模板",
                created_at=now - timedelta(days=2),
            ),
            RuleTemplateVersion(
                template_id=template.id,
                version=2,
                content=_steps("确定推广渠道", "准备物料清单"),
                change_summary="每日总结自动补充：新增步骤「准备物料清单」",
                created_at=now - timedelta(days=1),
            ),
            RuleTemplateVersion(
                template_id=template.id,
                version=3,
                content=_steps("确定推广渠道", "准备物料清单", "确认预算"),
                change_summary="用户手动编辑",
                created_at=now - timedelta(hours=3),
            ),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "get_template_versions", {"template_id": str(template.id)}
        )
        missing = await session.call_tool(
            "get_template_versions", {"template_id": "00000000-0000-0000-0000-000000000000"}
        )

    body = envelope(result)
    assert body["code"] == 0
    versions = body["data"]
    assert [row["version"] for row in versions] == [3, 2, 1]
    manual = [row for row in versions if row["change_summary"] == "用户手动编辑"]
    assert len(manual) == 1
    assert manual[0]["created_at"] is not None
    assert manual[0]["content"]["common_steps"][-1] == "确认预算"

    assert envelope(missing)["code"] != 0


# --------------------------------------------------------------------------
# 9.5 执行日志读侧 / 9.6 过期 draft 清理
# --------------------------------------------------------------------------


async def test_get_summary_executions_filters_since_and_status(mcp_session, mcp_db):
    """since/status 过滤生效，run_at 倒序：同日重复触发与退避判定都依赖该读取。"""
    now = datetime.now(UTC)
    mcp_db.add_all(
        [
            SummaryExecutionLog(
                status="failed",
                trigger_source="auto",
                error_message="LLM 调用超时",
                run_at=now - timedelta(days=2),
            ),
            SummaryExecutionLog(
                status="success",
                trigger_source="auto",
                categories_updated=["市场推广"],
                run_at=now - timedelta(hours=20),
            ),
            SummaryExecutionLog(
                status="success", trigger_source="manual", run_at=now - timedelta(minutes=5)
            ),
        ]
    )
    await mcp_db.commit()

    async with mcp_session() as session:
        today = envelope(
            await session.call_tool(
                "get_summary_executions",
                {"since": (now - timedelta(hours=23)).isoformat()},
            )
        )
        failed = envelope(
            await session.call_tool("get_summary_executions", {"status": "failed"})
        )
        bad = envelope(
            await session.call_tool("get_summary_executions", {"status": "unknown"})
        )

    assert today["code"] == 0
    assert [row["trigger_source"] for row in today["data"]] == ["manual", "auto"]
    assert today["data"][0]["status"] == "success"

    assert failed["code"] == 0
    assert len(failed["data"]) == 1
    assert failed["data"][0]["error_message"] == "LLM 调用超时"
    assert failed["data"][0]["categories_updated"] is None

    assert bad["code"] != 0


async def test_cancel_expired_drafts_only_touches_expired(mcp_session, mcp_db):
    """过期 draft → cancelled 且清空 expires_at；未过期 draft 与其他状态不动。"""
    now = datetime.now(UTC)
    expired = Task(
        title="过期提案",
        status="draft",
        expires_at=now - timedelta(minutes=30),
        created_at=now - timedelta(hours=25),
        updated_at=now - timedelta(hours=25),
    )
    alive = Task(
        title="新鲜提案",
        status="draft",
        expires_at=now + timedelta(hours=23),
        created_at=now - timedelta(hours=1),
        updated_at=now - timedelta(hours=1),
    )
    confirmed = Task(
        title="已确认任务",
        status="pending",
        created_at=now - timedelta(hours=2),
        updated_at=now - timedelta(hours=2),
    )
    mcp_db.add_all([expired, alive, confirmed])
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool("cancel_expired_drafts", {})
    body = envelope(result)
    assert body["code"] == 0
    assert body["data"]["cancelled_count"] == 1
    assert body["data"]["task_ids"] == [str(expired.id)]

    await mcp_db.refresh(expired)
    await mcp_db.refresh(alive)
    await mcp_db.refresh(confirmed)
    assert expired.status == "cancelled"
    assert expired.expires_at is None
    assert alive.status == "draft"
    assert confirmed.status == "pending"


async def test_cancel_expired_drafts_honours_injected_clock(mcp_session, mcp_db):
    """传入 `now` 时以该时刻判定过期（退避重试与桩时钟测试依赖）。"""
    now = datetime.now(UTC)
    expired = Task(
        title="过期提案",
        status="draft",
        expires_at=now - timedelta(minutes=30),
        created_at=now - timedelta(hours=25),
        updated_at=now - timedelta(hours=25),
    )
    mcp_db.add(expired)
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "cancel_expired_drafts", {"now": (now - timedelta(hours=26)).isoformat()}
        )
    body = envelope(result)
    assert body["code"] == 0
    assert body["data"]["cancelled_count"] == 0

    await mcp_db.refresh(expired)
    assert expired.status == "draft"
