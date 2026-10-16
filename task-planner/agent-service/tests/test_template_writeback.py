"""模板写回测试（tasks.md 9.3，spec `Learned updates are written back` 3 场景）。

场景一：学习结果立即写回既有模板，change_summary 列出动作与 reason（版本可追溯）；
场景二：无模板的分类在有 add_step 证据时自动新建（「每日总结自动创建」）；
场景三：单分类写回失败不阻塞其余分类的写回。
"""

from __future__ import annotations

from conftest import (
    RUN_DAY,
    SUMMARY_STEPS,
    summary_group,
    summary_payload,
    summary_template,
)

from src.services.mcp_gateway import McpToolError
from src.services.summary_learn import learn_all, learn_category, updated_categories


async def test_writeback_lists_actions_with_reasons(summary_env):
    """场景一：四种动作按序应用，change_summary 逐条列动作与 reason。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {
                            "action": "add_step",
                            "step": "邀请媒体",
                            "reason": "两条任务重复出现",
                        },
                        {
                            "action": "remove_step",
                            "step": "彩排",
                            "reason": "用户连续两次删除了该步骤",
                        },
                        {
                            "action": "update_estimated_count",
                            "value": 5,
                            "old_value": 4,
                            "reason": "实际子待办数持续多于预期", "evidence_task_ids": ["t-0", "t-1"],
                        },
                        {
                            "action": "update_notes",
                            "value": "提前一周预订场地",
                            "reason": "两条任务都因场地延误", "evidence_task_ids": ["t-0", "t-1"],
                        },
                    ],
                    new_notes="媒体名单需二次确认",
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(summary_template(), deleted=["彩排"], current=["确定主题", "制作PPT", "邀请媒体", "布置", "接待"]), RUN_DAY)

    calls = summary_env.fake.calls_of("update_rule_template")
    assert len(calls) == 1
    assert calls[0]["template_id"] == "tpl-mkt"
    content = calls[0]["content"]
    # add 追加末尾、remove 删除、count/notes 替换；new_notes 因已有 update_notes 不再叠加
    assert content["common_steps"] == ["确定主题", "制作PPT", "邀请媒体"]
    assert content["estimated_subtask_count"] == 5
    assert content["notes"] == "提前一周预订场地"

    change_summary = calls[0]["change_summary"]
    assert change_summary.startswith("每日总结自动学习：")
    assert "新增步骤「邀请媒体」（两条任务重复出现）" in change_summary
    assert "删除步骤「彩排」（用户连续两次删除了该步骤）" in change_summary
    assert "预期子待办数 4→5（实际子待办数持续多于预期）" in change_summary
    assert "更新注意事项（两条任务都因场地延误）" in change_summary
    assert outcome.changed is True and outcome.created is False
    assert updated_categories([outcome]) == ["市场推广"]


async def test_unverified_new_notes_never_written(summary_env):
    """new_notes 没有证据，不得绕过校验写回注意事项。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [{"action": "add_step", "step": "邀请媒体", "reason": "重复出现"}],
                    new_notes="媒体名单需二次确认",
                )
            ]
        }
    )
    outcome = await learn_category(
        summary_group(summary_template(notes="预留缓冲时间")), RUN_DAY
    )

    content = summary_env.fake.calls_of("update_rule_template")[0]["content"]
    assert content["notes"] == "预留缓冲时间"
    assert outcome.changed is True


async def test_template_created_when_category_has_none(summary_env):
    """场景二：无模板分类有 add_step 证据 → 自动创建（「每日总结自动创建」）。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {"action": "add_step", "step": "收集数据", "reason": "两条任务都有"},
                        {"action": "add_step", "step": "撰写初稿", "reason": "两条任务都有"},
                        # 重复步骤在创建时保序去重
                        {"action": "add_step", "step": "收集数据", "reason": "两条任务都有"},
                        {"action": "update_notes", "value": "数据口径先对齐", "reason": "用户备注", "evidence_task_ids": ["t-0", "t-1"]},
                    ]
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(None, added=["收集数据", "撰写初稿"]), RUN_DAY)

    calls = summary_env.fake.calls_of("create_rule_template")
    assert len(calls) == 1
    assert calls[0]["template_type"] == "decomposition"
    assert calls[0]["name"] == "市场推广拆解模板"
    assert calls[0]["category_id"] == "cat-mkt"
    assert calls[0]["common_steps"] == ["收集数据", "撰写初稿"]
    # 无 update_estimated_count 时取步骤数
    assert calls[0]["estimated_subtask_count"] == 2
    assert calls[0]["notes"] == "数据口径先对齐"
    assert calls[0]["change_summary"] == "每日总结自动创建"
    assert outcome.created is True and outcome.changed is True
    assert updated_categories([outcome]) == ["市场推广"]
    assert summary_env.fake.calls_of("update_rule_template") == []


async def test_no_template_and_no_evidence_creates_nothing(summary_env):
    """无模板且模型未给出 add_step 证据 → 不创建任何模板。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {
                            "action": "update_notes",
                            "value": "仅注意事项不足以建模板",
                            "reason": "无步骤证据",
                        }
                    ]
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(None), RUN_DAY)

    assert summary_env.fake.calls_of("create_rule_template") == []
    assert outcome.created is False and outcome.changed is False
    assert updated_categories([outcome]) == []


async def test_writeback_failure_does_not_block_other_categories(summary_env):
    """场景三：分类 A 写回失败被隔离为 outcome.error，分类 B 照常创建模板。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [{"action": "add_step", "step": "邀请媒体", "reason": "重复出现"}]
                ),
                summary_payload(
                    [{"action": "add_step", "step": "收集数据", "reason": "重复出现"}]
                ),
            ]
        }
    )
    summary_env.fake.set_error(
        "update_rule_template", McpToolError(500, "写回失败：数据库不可用")
    )
    groups = [
        summary_group(summary_template(), category_name="市场推广"),
        summary_group(None, category_id="cat-report", category_name="工作汇报", added=["收集数据"]),
    ]
    outcomes = await learn_all(groups, RUN_DAY)

    assert outcomes[0].error and outcomes[0].changed is False
    assert outcomes[1].error is None and outcomes[1].created is True
    assert updated_categories(outcomes) == ["工作汇报"]
    created = summary_env.fake.calls_of("create_rule_template")
    assert len(created) == 1 and created[0]["category_id"] == "cat-report"
