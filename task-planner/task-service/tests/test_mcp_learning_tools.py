"""MCP 学习闭环工具测试（tasks.md 6.3）。

覆盖两个场景：
1. update_rule_template 写回 content：version+1，且版本快照行记录
   新 content 与 change_summary（学习闭环的可追溯依据）；
2. log_summary_execution 落库：summary_execution_log 各字段与入参一致。

`mcp_session` 夹具 yield 连接工厂，用例体内 `async with` 打开
（TaskGroup 必须在同一任务内进入与退出，见 conftest 说明）。
"""

from sqlalchemy import select

from src.models.rule_template import RuleTemplate, RuleTemplateVersion
from src.models.summary_log import SummaryExecutionLog


def envelope(result):
    """取工具返回的封套 dict：协议层不得报错，业务结果看 code。"""
    assert result.isError is False, f"工具不应抛协议级错误：{result.content}"
    return result.structuredContent


async def test_update_rule_template_creates_new_version(mcp_session, mcp_db):
    """content 变更 → version+1 + 版本行（含 change_summary）。"""
    template = RuleTemplate(
        type="decomposition",
        name="市场推广拆解模板",
        content={
            "common_steps": ["确定推广渠道"],
            "estimated_subtask_count": 1,
            "notes": "",
        },
        version=1,
    )
    mcp_db.add(template)
    await mcp_db.commit()

    async with mcp_session() as session:
        result = await session.call_tool(
            "update_rule_template",
            {
                "template_id": str(template.id),
                "content": {
                    "common_steps": ["确定推广渠道", "准备物料清单"],
                    "estimated_subtask_count": 2,
                },
                "change_summary": "每日总结自动补充：新增步骤「准备物料清单」",
            },
        )
    body = envelope(result)
    assert body["code"] == 0
    data = body["data"]
    assert data["version"] == 2
    assert data["content"]["common_steps"] == ["确定推广渠道", "准备物料清单"]
    assert data["content"]["estimated_subtask_count"] == 2

    # 版本快照行：version=2，change_summary 与 content 与写回一致
    rows = (
        await mcp_db.execute(
            select(RuleTemplateVersion)
            .where(RuleTemplateVersion.template_id == template.id)
            .order_by(RuleTemplateVersion.version.desc())
        )
    ).scalars().all()
    assert len(rows) == 1
    assert rows[0].version == 2
    assert rows[0].change_summary == "每日总结自动补充：新增步骤「准备物料清单」"
    assert rows[0].content["common_steps"] == ["确定推广渠道", "准备物料清单"]


async def test_log_summary_execution_persists(mcp_session, mcp_db):
    """执行日志落库：状态、更新分类清单、触发来源与入参一致。"""
    async with mcp_session() as session:
        result = await session.call_tool(
            "log_summary_execution",
            {
                "status": "success",
                "categories_updated": ["市场推广"],
                "trigger_source": "manual",
            },
        )
    body = envelope(result)
    assert body["code"] == 0
    assert body["data"]["status"] == "success"
    assert body["data"]["categories_updated"] == ["市场推广"]
    assert body["data"]["trigger_source"] == "manual"

    rows = (await mcp_db.execute(select(SummaryExecutionLog))).scalars().all()
    assert len(rows) == 1
    row = rows[0]
    assert row.status == "success"
    assert row.categories_updated == ["市场推广"]
    assert row.trigger_source == "manual"
    assert row.error_message is None
    assert row.run_at is not None
