"""每日总结学习测试（tasks.md 9.2）。

覆盖 4 个契约场景：≥2 次重复才更新（每分类恰好一次 LLM 调用）、单次偶发不更新、
非法 action/缺字段条目跳过而不中断、单分类失败隔离；另附每分类超时被隔离与
`categories_updated` 只含确实变化的分类两个断言。
"""

from __future__ import annotations

import pytest
from conftest import (
    RUN_DAY,
    SUMMARY_STEPS,
    summary_group,
    summary_payload,
    summary_template,
)

from src.config import settings
from src.services.summary_learn import learn_all, learn_category, updated_categories


async def test_repeated_pattern_updates_template_with_one_llm_call(summary_env):
    """≥2 次重复的调整被应用：每分类恰好一次 LLM 调用，prompt 含重复约束。"""
    stub = summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {
                            "action": "add_step",
                            "step": "邀请媒体",
                            "reason": "两条任务都由用户手动补充了该步骤",
                        }
                    ]
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(summary_template()), RUN_DAY)

    assert stub.calls == ["summary"]
    system = stub.prompts[0][1]
    assert "至少 2 个任务中重复出现" in system
    assert "市场推广" in system

    assert outcome.error is None and outcome.changed and not outcome.created
    calls = summary_env.fake.calls_of("update_rule_template")
    assert len(calls) == 1
    assert calls[0]["content"]["common_steps"] == [*SUMMARY_STEPS, "邀请媒体"]
    assert "新增步骤「邀请媒体」" in calls[0]["change_summary"]
    assert "两条任务都由用户手动补充了该步骤" in calls[0]["change_summary"]
    assert updated_categories([outcome]) == ["市场推广"]


async def test_single_incidental_change_leaves_template_untouched(summary_env):
    """单次偶发调整（模型判定证据不足，返回空数组）不动模板、不写库。"""
    stub = summary_env.install({"summary": [summary_payload([])]})
    outcome = await learn_category(
        summary_group(summary_template(), task_count=1), RUN_DAY
    )

    assert stub.calls == ["summary"]
    assert outcome.changed is False and outcome.error is None
    assert summary_env.fake.calls_of("update_rule_template") == []
    assert updated_categories([outcome]) == []


async def test_unknown_action_and_missing_fields_are_skipped(summary_env):
    """未知 action、缺 reason、reason 空白的条目跳过，合法条目照常应用。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [
                        {"action": "rewrite_template", "step": "重写", "reason": "非法动作"},
                        {"action": "add_step", "step": "预订场地"},
                        {"action": "remove_step", "step": "彩排", "reason": "   "},
                        {
                            "action": "add_step",
                            "step": "邀请媒体",
                            "reason": "两条任务重复出现",
                        },
                    ]
                )
            ]
        }
    )
    outcome = await learn_category(summary_group(summary_template()), RUN_DAY)

    assert outcome.error is None
    assert len(outcome.skipped) == 3
    content = summary_env.fake.calls_of("update_rule_template")[0]["content"]
    assert content["common_steps"] == [*SUMMARY_STEPS, "邀请媒体"]


async def test_unparsable_category_does_not_block_the_next_one(summary_env):
    """单分类失败隔离：A 输出不可解析，B 仍完成学习并写回。"""
    stub = summary_env.install(
        {
            "summary": [
                "模型这次没有输出 JSON",
                summary_payload(
                    [{"action": "add_step", "step": "邀请媒体", "reason": "两条任务重复"}]
                ),
            ]
        }
    )
    groups = [
        summary_group(summary_template(), category_name="市场推广"),
        summary_group(
            summary_template(["确定主题"], template_id="tpl-report", count=2),
            category_id="cat-report",
            category_name="工作汇报",
        ),
    ]
    outcomes = await learn_all(groups, RUN_DAY)

    assert stub.calls == ["summary", "summary"]
    assert [item.category_name for item in outcomes] == ["市场推广", "工作汇报"]
    assert outcomes[0].error and outcomes[0].changed is False
    assert outcomes[1].error is None and outcomes[1].changed is True
    assert updated_categories(outcomes) == ["工作汇报"]
    assert summary_env.fake.calls_of("update_rule_template")[0]["template_id"] == "tpl-report"


async def test_per_category_timeout_is_isolated(summary_env, monkeypatch: pytest.MonkeyPatch):
    """单分类超时（配置值）只让该分类失败，不抛出到调用方。"""
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [{"action": "add_step", "step": "邀请媒体", "reason": "重复"}]
                )
            ]
        },
        delays={"summary": 0.05},
    )
    monkeypatch.setattr(settings, "summary_llm_timeout_seconds", 0.01)

    outcomes = await learn_all([summary_group(summary_template())], RUN_DAY)

    assert outcomes[0].error and outcomes[0].changed is False
    assert summary_env.fake.calls_of("update_rule_template") == []
