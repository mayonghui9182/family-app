"""每日总结数据采集测试（tasks.md 9.1）。

覆盖 4 个契约场景：窗口边界（右边界闭区间 + since 传当天 0:00）、
跨分类改挂（按当前 category_id 分组）、每分类 10 条上限（修改幅度大者优先，
无修改的不占名额）、diff 计算（用户新增精确 / 用户删除以不可变原始快照为基准）。
另附两个降级场景：单任务详情失败跳过、模板读取失败明确报错。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from typing import Any

from src.services.mcp_gateway import McpToolError
from src.services.summary_collect import (
    MAX_TASKS_PER_CATEGORY,
    collect_summary_input,
    compute_diff,
    flatten_template,
)

TZ = timezone(timedelta(hours=8))
# 窗口：当天 0:00 至 22:00 执行时刻
SINCE = datetime(2026, 9, 20, 0, 0, tzinfo=TZ)
UNTIL = datetime(2026, 9, 20, 22, 0, tzinfo=TZ)

MARKETING_STEPS = ["确定主题", "制作PPT", "彩排"]


def _task(
    task_id: str,
    title: str,
    category_id: str | None = "cat-mkt",
    category_name: str = "市场推广",
    updated_at: datetime = UNTIL - timedelta(minutes=1),
) -> dict[str, Any]:
    """构造一条 TaskOut 形状的窗口任务。"""
    return {
        "id": task_id,
        "title": title,
        "category_id": category_id,
        "category_name": category_name,
        "status": "in_progress",
        "created_at": (SINCE + timedelta(hours=9)).isoformat(),
        "updated_at": updated_at.isoformat(),
    }


def _template_row(
    steps: list[str], template_id: str = "tpl-mkt", version: int = 3
) -> dict[str, Any]:
    """构造一条 MCP 模板行（内容嵌在 content 键内）。"""
    return {
        "id": template_id,
        "name": "发布会准备",
        "version": version,
        "category_id": "cat-mkt",
        "type": "decomposition",
        "is_active": True,
        "content": {
            "common_steps": steps,
            "estimated_subtask_count": len(steps),
            "notes": "旧注意事项",
        },
    }


def _detail(*subtasks: tuple[str, bool], original: list[str] | None = None, template_id: str | None = None) -> dict[str, Any]:
    """构造任务详情：入参为 (标题, is_ai_generated)。"""
    return {
        "id": "ignored",
        "ai_snapshot": {"template_id": template_id, "category_id": "cat-mkt",
                        "subtasks": [{"title": title} for title in (original if original is not None else [t for t, ai in subtasks if ai])]},
        "subtasks": [
            {
                "id": f"st-{index}",
                "title": title,
                "sort_order": index + 1,
                "status": "pending",
                "is_ai_generated": is_ai,
            }
            for index, (title, is_ai) in enumerate(subtasks)
        ],
        "notes": [],
    }


def _prepare(fake: Any, tasks: list[dict[str, Any]], details: dict[str, Any], steps: list[str] | None = None) -> None:
    """预置窗口任务、任务详情与分类模板。"""
    fake.set_data("get_tasks_changed_since", tasks)
    fake.set_data("task_details", details)
    fake.set_data(
        "templates_by_category",
        {"cat-mkt": [_template_row(steps)]} if steps is not None else {},
    )


async def test_window_boundary_excludes_changes_after_run(summary_env) -> None:
    """窗口边界：since 传当天 0:00，右边界闭区间，执行时刻之后的变动不进本次。"""
    fake = summary_env.fake
    tasks = [
        _task("t-before", "边界内任务", updated_at=UNTIL - timedelta(minutes=1)),
        _task("t-edge", "边界任务", updated_at=UNTIL),
        _task("t-after", "执行后任务", updated_at=UNTIL + timedelta(minutes=1)),
    ]
    _prepare(
        fake,
        tasks,
        {
            "t-before": _detail(("确定主题", True), ("联系场地方", False)),
            "t-edge": _detail(("确定主题", True), ("打印物料", False)),
            "t-after": _detail(("确定主题", True), ("执行后才加的步骤", False)),
        },
        MARKETING_STEPS,
    )

    groups = await collect_summary_input(SINCE, UNTIL)

    since_arg = fake.calls_of("get_tasks_changed_since")[0]["since"]
    assert since_arg == SINCE.isoformat()
    detailed = [args["task_id"] for args in fake.calls_of("get_task_detail")]
    assert detailed == ["t-before", "t-edge"]
    assert len(groups) == 1
    assert [task.task_id for task in groups[0].tasks] == ["t-edge", "t-before"]


async def test_recategorised_task_groups_under_current_category(summary_env) -> None:
    """跨分类改挂：按任务当前 category_id 分组，模板也按新分类查。"""
    fake = summary_env.fake
    _prepare(
        fake,
        [_task("t-moved", "改挂到研发的任务", category_id="cat-dev", category_name="产品研发")],
        {"t-moved": _detail(("需求确认", True), ("补充压测", False))},
        MARKETING_STEPS,
    )

    groups = await collect_summary_input(SINCE, UNTIL)

    assert len(groups) == 1
    assert groups[0].category_id == "cat-dev"
    assert groups[0].category_name == "产品研发"
    # 新分类没有 decomposition 模板 → 按无模板处理（后续可自动创建）
    assert groups[0].template is None
    template_query = fake.calls_of("get_rule_templates")[0]
    assert template_query["category_id"] == "cat-dev"
    assert template_query["template_type"] == "decomposition"


async def test_category_caps_at_ten_tasks_by_change_size(summary_env) -> None:
    """10 条上限：修改幅度大者优先，无修改行为的任务不占名额。"""
    fake = summary_env.fake
    tasks: list[dict[str, Any]] = []
    details: dict[str, Any] = {}
    # 12 条有修改的任务：第 n 条被用户新增 n 个步骤（幅度 1..12）
    for number in range(1, 13):
        task_id = f"t-{number:02d}"
        tasks.append(_task(task_id, f"任务{number}"))
        added = [("确定主题", True)] + [(f"用户步骤{index}", False) for index in range(number)]
        details[task_id] = _detail(*added)
    # 一条无任何修改的任务：AI 步骤与当前集合一致，且模板步骤全部保留
    tasks.append(_task("t-clean", "没有修改的任务"))
    details["t-clean"] = _detail(*[(step, True) for step in MARKETING_STEPS])
    _prepare(fake, tasks, details, MARKETING_STEPS)

    groups = await collect_summary_input(SINCE, UNTIL)

    assert len(groups) == 1
    selected = groups[0].tasks
    assert len(selected) == MAX_TASKS_PER_CATEGORY
    # 第 n 条幅度 = n 个用户新增；AI 未采用的模板步骤不算删除
    assert [task.change_size for task in selected] == list(range(12, 2, -1))
    assert [selected[0].task_id, selected[-1].task_id] == ["t-12", "t-03"]
    assert "t-clean" not in [task.task_id for task in selected]


async def test_diff_computes_user_additions_and_deletions(summary_env) -> None:
    """diff 计算：新增与删除都比较原始快照与当前集合。"""
    fake = summary_env.fake
    _prepare(
        fake,
        [_task("t-diff", "准备发布会")],
        {"t-diff": _detail(("确定主题", True), ("制作PPT", True), ("现场布置", False), original=MARKETING_STEPS, template_id="tpl-mkt")},
        MARKETING_STEPS,
    )

    groups = await collect_summary_input(SINCE, UNTIL)

    diff = groups[0].tasks[0]
    assert diff.ai_steps == MARKETING_STEPS
    assert diff.current_steps == ["确定主题", "制作PPT", "现场布置"]
    assert diff.added_steps == ["现场布置"]
    # 「彩排」在原始提案里、当前集合不存在 → 判定为用户删除
    assert diff.deleted_steps == ["彩排"]
    assert diff.change_size == 2
    assert groups[0].template == flatten_template(_template_row(MARKETING_STEPS))
    assert diff.to_prompt() == {
        "task_id": "t-diff", "ai_steps": MARKETING_STEPS,
        "current_steps": ["确定主题", "制作PPT", "现场布置"],
        "title": "准备发布会",
        "deleted_steps": ["彩排"],
        "added_steps": ["现场布置"],
    }


def test_diff_without_template_reports_no_deletion() -> None:
    """冷启动仍按原始快照计算；此例原始步骤未删除，只报告新增。"""
    diff = compute_diff(
        _task("t-x", "无模板任务"),
        _detail(("确定主题", True), ("补充步骤", False)),
        None,
    )
    assert diff.added_steps == ["补充步骤"]
    assert diff.deleted_steps == []


def test_diff_ignores_whitespace_only_differences() -> None:
    """纯排版差异（空白折叠）不算修改，避免污染学习信号。"""
    diff = compute_diff(
        _task("t-y", "排版任务"),
        _detail(("确定主题", True), ("制作 PPT", True)),
        flatten_template(_template_row(["确定主题", "制作  PPT"])),
    )
    assert diff.added_steps == []
    assert diff.deleted_steps == []
    assert diff.change_size == 0


async def test_task_detail_failure_skips_only_that_task(summary_env) -> None:
    """单任务详情失败：跳过该任务，不抛异常中断整轮采集。"""
    fake = summary_env.fake
    _prepare(fake, [_task("t-1", "任务一")], {}, MARKETING_STEPS)
    fake.set_error("get_task_detail", McpToolError(40404, "任务不存在"))

    assert await collect_summary_input(SINCE, UNTIL) == []


async def test_template_failure_does_not_create_replacement(summary_env) -> None:
    """模板查询失败必须报错，不能当作无模板新建替身。"""
    fake = summary_env.fake
    _prepare(fake, [_task("t-1", "任务一")], {"t-1": _detail(("确定主题", True), ("补充步骤", False))})
    fake.set_error("get_rule_templates", McpToolError(50001, "服务内部错误"))

    with pytest.raises(McpToolError):
        await collect_summary_input(SINCE, UNTIL)
