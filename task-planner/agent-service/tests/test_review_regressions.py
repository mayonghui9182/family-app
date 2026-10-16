"""审查回归：真实证据、模板归属、并发、错误路径与提示词闭环。"""

import asyncio
from datetime import date
from unittest.mock import AsyncMock

import pytest
from conftest import (
    RUN_DAY,
    parse_sse,
    post_chat,
    summary_group,
    summary_payload,
    summary_template,
)
from src.llm.prompts import render_prompt
from src.services import summary_run as runs
from src.services.decomposition import _template_view
from src.services.summary_collect import collect_summary_input, compute_diff
from src.services.summary_learn import CategoryOutcome, learn_all, learn_category
from test_summary_collect import SINCE, UNTIL, _detail, _prepare, _task, _template_row


def test_original_snapshot_prevents_false_deletion_and_detects_rename():
    """未采用模板步骤不算删除；改名在快照中保留原值。"""
    task = _task("t", "任务")
    detail = _detail(("制作PPT", True), original=["制作PPT"])
    assert (
        compute_diff(
            task, detail, {"common_steps": ["制作PPT", "打印材料"]}
        ).change_size
        == 0
    )
    renamed = _detail(("线上发布", True), original=["现场发布"])
    diff = compute_diff(task, renamed)
    assert diff.deleted_steps == ["现场发布"]
    assert diff.added_steps == ["线上发布"]
    assert renamed["ai_snapshot"]["subtasks"] == [{"title": "现场发布"}]


def test_legacy_task_without_snapshot_is_not_inferred():
    detail = {"subtasks": [{"title": "手动步骤", "is_ai_generated": False}]}
    assert (
        compute_diff(
            _task("old", "旧任务"), detail, {"common_steps": ["材料"]}
        ).change_size
        == 0
    )


async def test_two_templates_receive_only_their_own_task_evidence(summary_env):
    fake = summary_env.fake
    tasks = [_task("a", "发布会"), _task("b", "汇报")]
    _prepare(
        fake,
        tasks,
        {
            "a": _detail(
                ("邀请媒体", False), original=["现场彩排"], template_id="tpl-a"
            ),
            "b": _detail(
                ("审阅数据", False), original=["采集数据"], template_id="tpl-b"
            ),
        },
    )
    fake.set_data(
        "templates_by_category",
        {
            "cat-mkt": [
                _template_row(["现场彩排"], "tpl-a"),
                _template_row(["采集数据"], "tpl-b"),
            ]
        },
    )
    groups = await collect_summary_input(SINCE, UNTIL)
    grouped = {g.template["id"]: g for g in groups}
    assert grouped["tpl-a"].tasks[0].task_id == "a"
    assert grouped["tpl-b"].tasks[0].deleted_steps == ["采集数据"]


async def test_summary_reads_second_page_before_sampling(summary_env):
    tasks = [_task(str(i), str(i)) for i in range(201)]
    details = {str(i): _detail(("原始", True)) for i in range(200)}
    details["200"] = _detail(("新增", False), original=["原始"])
    _prepare(summary_env.fake, tasks, details)
    groups = await collect_summary_input(SINCE, UNTIL)
    assert [
        a["offset"] for a in summary_env.fake.calls_of("get_tasks_changed_since")
    ] == [0, 200]
    assert groups[0].tasks[0].task_id == "200"


@pytest.mark.parametrize("task_count,step", [(1, "邀请媒体"), (2, "凭空步骤")])
async def test_model_cannot_bypass_repeated_evidence(summary_env, task_count, step):
    summary_env.install(
        {
            "summary": [
                summary_payload(
                    [{"action": "add_step", "step": step, "reason": "声称重复"}],
                    new_notes="没有证据的备注",
                )
            ]
        }
    )
    result = await learn_category(
        summary_group(summary_template(), task_count=task_count), RUN_DAY
    )
    assert not result.changed
    assert result.skipped
    assert not summary_env.fake.calls_of("update_rule_template")


async def test_same_category_multi_template_uses_one_llm_call(summary_env):
    stub = summary_env.install(
        {
            "summary": [
                {
                    "template_results": [
                        {
                            "target_key": "one",
                            "template_updates": [
                                {
                                    "action": "add_step",
                                    "step": "邀请媒体",
                                    "reason": "两个任务",
                                }
                            ],
                        },
                        {
                            "target_key": "two",
                            "template_updates": [
                                {
                                    "action": "add_step",
                                    "step": "核对数据",
                                    "reason": "两个任务",
                                }
                            ],
                        },
                    ]
                }
            ]
        }
    )
    outcomes = await learn_all(
        [
            summary_group(summary_template(template_id="one")),
            summary_group(summary_template(template_id="two"), added=["核对数据"]),
        ],
        RUN_DAY,
    )
    assert stub.calls == ["summary"]
    assert all(o.changed for o in outcomes)
    calls = summary_env.fake.calls_of("update_rule_template")
    assert [c["template_id"] for c in calls] == ["one", "two"]
    assert calls[0]["content"]["common_steps"][-1] == "邀请媒体"
    assert calls[1]["content"]["common_steps"][-1] == "核对数据"
    assert all(c["expected_version"] == 3 for c in calls)


async def test_all_category_failures_are_failed_and_retried(summary_env, monkeypatch):
    group = summary_group(summary_template())
    monkeypatch.setattr(runs, "collect_summary_input", AsyncMock(return_value=[group]))
    learner = AsyncMock(
        return_value=[CategoryOutcome("cat-mkt", "市场推广", error="LLM不可用")]
    )
    monkeypatch.setattr(runs, "learn_all", learner)
    result = await runs.run_summary("manual", now=UNTIL, sleep=AsyncMock())
    assert result.status == "failed" and result.attempts == 4
    assert "LLM不可用" in result.error_message
    assert learner.await_count == 4
    assert summary_env.fake.calls_of("log_summary_execution")[0]["status"] == "failed"


async def test_partial_failure_retries_only_failed_groups(summary_env, monkeypatch):
    groups = [
        summary_group(summary_template(template_id="a")),
        summary_group(summary_template(template_id="b")),
    ]
    monkeypatch.setattr(runs, "collect_summary_input", AsyncMock(return_value=groups))
    learner = AsyncMock(
        side_effect=[
            [
                CategoryOutcome("cat-mkt", "市场推广", changed=True),
                CategoryOutcome("cat-mkt", "市场推广", error="暂时失败"),
            ],
            [CategoryOutcome("cat-mkt", "市场推广", changed=True)],
        ]
    )
    monkeypatch.setattr(runs, "learn_all", learner)
    result = await runs.run_summary("manual", now=UNTIL, sleep=AsyncMock())
    assert result.status == "success" and result.attempts == 2
    assert learner.call_args_list[1].args[0] == [groups[1]]
    assert result.categories_updated == ["市场推广"]


async def test_concurrent_force_cannot_bypass_running_mutex(summary_env, monkeypatch):
    entered, release = asyncio.Event(), asyncio.Event()

    async def collect(*args):
        entered.set()
        await release.wait()
        return []

    monkeypatch.setattr(runs, "collect_summary_input", collect)
    first = asyncio.create_task(runs.run_summary("auto", now=UNTIL))
    await entered.wait()
    try:
        with pytest.raises(runs.SummaryAlreadyRun, match="正在运行"):
            await runs.run_summary("manual", force=True, now=UNTIL)
    finally:
        release.set()
        await first
    assert len(summary_env.fake.calls_of("log_summary_execution")) == 1


def test_failure_window_uses_dates_and_full_failure_streak():
    today = date(2026, 9, 21)
    same_day = [{"status": "failed", "run_at": "2026-09-21T12:00:00+08:00"}] * 3
    assert runs.window_start_day(same_day, today) == today
    failed_days = [
        {"status": "failed", "run_at": f"2026-09-{d}T22:00:00+08:00"}
        for d in range(16, 21)
    ]
    assert runs.window_start_day(failed_days, today) == date(2026, 9, 16)
    failed_days.append({"status": "success", "run_at": "2026-09-19T23:00:00+08:00"})
    assert runs.window_start_day(failed_days, today) == today


async def test_missing_api_key_is_an_sse_error(monkeypatch):
    from src.config import Settings
    from src.llm.client import LLMClient
    from src.routes import chat

    monkeypatch.setattr(chat, "_build_llm", lambda: LLMClient(Settings(llm_api_key="")))
    response = await post_chat("准备发布会")
    assert parse_sse(response.text) == [("error", {"message": "拆解失败，请重试"})]


def test_learned_notes_and_count_reach_decomposition_prompt():
    template = _template_view(
        {
            "id": "t",
            "name": "发布会",
            "content": {
                "common_steps": ["准备"],
                "notes": "必须确认无障碍通道",
                "estimated_subtask_count": 7,
            },
        }
    )
    prompt = render_prompt(
        "decomposition",
        message="发布会",
        few_shot_tasks=[],
        matched_template=template,
        workload=None,
    )
    assert "必须确认无障碍通道" in prompt and "预期步骤数：7" in prompt


async def test_log_failure_cannot_report_success(summary_env):
    from src.services.mcp_gateway import McpToolError

    summary_env.fake.set_error("log_summary_execution", McpToolError(500, "写日志失败"))
    result = await runs.run_summary("manual", now=UNTIL)
    assert result.status == "failed" and "日志未保存" in result.error_message


@pytest.mark.parametrize("active", [True, False])
async def test_cold_start_reuses_template_and_respects_deactivation(
    summary_env, active
):
    _prepare(
        summary_env.fake,
        [_task("t", "冷启动任务")],
        {
            "t": _detail(("新增", False), original=["原始"]),
        },
    )
    row = _template_row(["原始"], "automatic")
    row.update(learning_key="cold_start:cat-mkt", is_active=active)
    summary_env.fake.set_data("templates_by_category", {"cat-mkt": [row]})
    groups = await collect_summary_input(SINCE, UNTIL)
    if active:
        assert groups[0].template["id"] == "automatic"
    else:
        assert groups == []


async def test_retry_rechecks_already_successful_day(summary_env, monkeypatch):
    from src.services.mcp_gateway import McpToolError

    fetch = AsyncMock(
        side_effect=[
            McpToolError(500, "暂时失败"),
            [
                {"status": "success", "run_at": UNTIL.isoformat()},
            ],
        ]
    )
    monkeypatch.setattr(runs, "fetch_executions", fetch)
    with pytest.raises(runs.SummaryAlreadyRun):
        await runs.run_summary("manual", now=UNTIL, sleep=AsyncMock())
    assert not summary_env.fake.calls_of("get_tasks_changed_since")
