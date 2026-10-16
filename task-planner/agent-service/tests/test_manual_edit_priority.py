"""手动编辑优先测试（tasks.md 9.4，spec `Manual edits take priority`）。

模板当天存在 change_summary「用户手动编辑」的版本行时：remove_step /
update_estimated_count / update_notes 全部被抑制，只允许追加步骤，
change_summary 固定「自动补充（用户已手动编辑，未覆盖）」。
另覆盖：手动编辑发生在前一天不触发抑制、取版本历史失败时保守按已编辑处理。
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
from src.services.summary_learn import learn_category

# 全量动作脚本：抑制生效时只有 add_step 存活
_FULL_UPDATES = [
    {"action": "add_step", "step": "邀请媒体", "reason": "两条任务重复出现"},
    {"action": "remove_step", "step": "彩排", "reason": "用户删除过"},
    {
        "action": "update_estimated_count",
        "value": 9,
        "old_value": 4,
        "reason": "模型想改预期数", "evidence_task_ids": ["t-0", "t-1"],
    },
    {"action": "update_notes", "value": "模型想改注意事项", "reason": "不应生效", "evidence_task_ids": ["t-0", "t-1"]},
]


def _version_row(change_summary: str, created_at: str) -> dict:
    """TemplateVersionOut 形状的版本行。"""
    return {
        "id": "ver-1",
        "version": 4,
        "content": {},
        "change_summary": change_summary,
        "is_important": False,
        "parent_version_id": None,
        "created_at": created_at,
    }


async def test_remove_step_suppressed_after_same_day_manual_edit(summary_env):
    """当天 14:00 用户手动编辑过 → 只追加，删除/改数/改注意事项全被抑制。"""
    summary_env.fake.set_data(
        "get_template_versions",
        [_version_row("用户手动编辑", "2026-09-20T14:00:00+08:00")],
    )
    summary_env.install(
        {"summary": [summary_payload(_FULL_UPDATES, new_notes="模型补充的注意事项")]}
    )
    outcome = await learn_category(summary_group(summary_template(), deleted=["彩排"], current=[str(i) for i in range(9)]), RUN_DAY)

    assert outcome.manual_edit is True and outcome.error is None
    calls = summary_env.fake.calls_of("update_rule_template")
    assert len(calls) == 1
    content = calls[0]["content"]
    # 只追加末尾：既有步骤一个不少、顺序不变
    assert content["common_steps"] == [*SUMMARY_STEPS, "邀请媒体"]
    # 用户编辑过的 count 与 notes 绝不被覆盖
    assert content["estimated_subtask_count"] == 4
    assert content["notes"] == ""
    assert calls[0]["change_summary"] == "自动补充（用户已手动编辑，未覆盖）"


async def test_add_only_run_after_manual_edit_still_writes_nothing_when_idempotent(
    summary_env,
):
    """当天已手动编辑且模型只给出模板里已有的步骤 → 无实际变化，不写库。"""
    summary_env.fake.set_data(
        "get_template_versions",
        [_version_row("用户手动编辑", "2026-09-20T09:30:00+08:00")],
    )
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {"action": "add_step", "step": "彩排", "reason": "已存在的步骤"},
                        {"action": "remove_step", "step": "确定主题", "reason": "被抑制"},
                    ]
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(summary_template(), deleted=["彩排"], current=[str(i) for i in range(9)]), RUN_DAY)

    assert outcome.manual_edit is True and outcome.changed is False
    assert summary_env.fake.calls_of("update_rule_template") == []


async def test_previous_day_manual_edit_does_not_suppress(summary_env):
    """手动编辑发生在前一天（本地时区换算后）→ 正常全量应用。"""
    summary_env.fake.set_data(
        "get_template_versions",
        [
            _version_row("用户手动编辑", "2026-09-19T14:00:00+08:00"),
            # 当天只有自动学习产生的版本行，不算手动编辑
            _version_row("每日总结自动学习：新增步骤「预订场地」", "2026-09-20T22:05:00+08:00"),
        ],
    )
    summary_env.install({"summary": [summary_payload(_FULL_UPDATES)]})
    outcome = await learn_category(summary_group(summary_template(), deleted=["彩排"], current=[str(i) for i in range(9)]), RUN_DAY)

    assert outcome.manual_edit is False
    calls = summary_env.fake.calls_of("update_rule_template")
    content = calls[0]["content"]
    assert content["common_steps"] == ["确定主题", "制作PPT", "邀请媒体"]
    assert content["estimated_subtask_count"] == 9
    assert content["notes"] == "模型想改注意事项"
    assert calls[0]["change_summary"].startswith("每日总结自动学习：")


async def test_version_fetch_failure_conservatively_suppresses(summary_env):
    """取版本历史失败 → 保守按「当天已手动编辑」处理，绝不覆盖。"""
    summary_env.fake.set_error(
        "get_template_versions", McpToolError(500, "查询版本失败")
    )
    summary_env.install({"summary": [summary_payload(_FULL_UPDATES)]})
    outcome = await learn_category(summary_group(summary_template(), deleted=["彩排"], current=[str(i) for i in range(9)]), RUN_DAY)

    assert outcome.manual_edit is True and outcome.error is None
    calls = summary_env.fake.calls_of("update_rule_template")
    assert len(calls) == 1
    assert calls[0]["content"]["common_steps"] == [*SUMMARY_STEPS, "邀请媒体"]
    assert calls[0]["change_summary"] == "自动补充（用户已手动编辑，未覆盖）"
