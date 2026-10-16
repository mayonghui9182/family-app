"""模板匹配与上下文装配测试（tasks.md 8.3）。

覆盖 spec `Template matching` 与 `Decomposition context` 的场景：
命中模板、近似匹配标注调整、冷启动标注、停用模板不作候选、
历史 few-shot 进 prompt、高负载提醒、冷系统仍可完成拆解。
"""

from __future__ import annotations

import copy

from conftest import DEFAULT_SCRIPTS, parse_sse, post_chat

# 启用中的 decomposition 模板
ACTIVE_TEMPLATE = {
    "id": "tpl-1",
    "name": "发布会准备",
    "content": {
        "common_steps": ["确定主题", "制作PPT", "彩排", "物料准备"],
        "estimated_subtask_count": 4,
        "notes": "",
    },
    "is_active": True,
    "version": 2,
}


def _full_reasoning() -> str:
    return "参考模板「发布会准备」的步骤模式，并结合本次任务调整了顺序。"


def _scripts_with_template(matched: bool, reasoning: str) -> dict:
    """构造带模板匹配环节的脚本。"""
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["template_match"] = [
        {"matched": matched, "template_id": "tpl-1" if matched else None, "reason": "步骤吻合"}
    ]
    scripts["decomposition"][0]["agent_reasoning"] = reasoning
    return scripts


async def test_matching_template_guides_decomposition(env) -> None:
    """命中模板：reasoning 提及模板，拆解按模板步骤模式产出。"""
    env.fake.set_data("get_rule_templates_active", [ACTIVE_TEMPLATE])
    env.install(_scripts_with_template(True, _full_reasoning()))

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)

    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "发布会准备" in reasoning
    proposal = next(data for name, data in events if name == "proposal")
    assert proposal["subtasks"], "提案应包含子待办"

    # 模板查询只取启用的 decomposition 模板
    query = env.fake.calls_of("get_rule_templates")[0]
    assert query["template_type"] == "decomposition"
    assert query["is_active"] is True


async def test_approximate_match_annotates_adjustment(env) -> None:
    """近似匹配：LLM 未提模板名时确定性标注「参考模板X但有所调整」。"""
    env.fake.set_data("get_rule_templates_active", [ACTIVE_TEMPLATE])
    scripts = _scripts_with_template(True, "按发布会的一般流程拆解。")
    env.install(scripts)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "参考模板「发布会准备」但有所调整" in reasoning


async def test_cold_start_annotates_missing_history(env) -> None:
    """分类下无模板：冷启动且 reasoning 含「无历史参考」。"""
    stub = env.install(DEFAULT_SCRIPTS)  # 默认无启用模板

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "无历史参考" in reasoning
    # 冷启动路径不产生模板匹配调用
    assert "template_match" not in stub.calls


async def test_deactivated_template_is_not_candidate(env) -> None:
    """停用模板不作候选：走冷启动，reasoning 不提名该模板。"""
    deactivated = {**ACTIVE_TEMPLATE, "is_active": False}
    env.fake.set_data("get_rule_templates", [deactivated])  # 未过滤时可见
    env.fake.set_data("get_rule_templates_active", [])  # is_active=true 查询为空
    env.install(DEFAULT_SCRIPTS)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "无历史参考" in reasoning
    assert "发布会准备" not in reasoning


async def test_history_is_used_as_few_shot_reference(env) -> None:
    """同分类已完成任务作为 few-shot 进入拆解 prompt。"""
    env.fake.set_data(
        "get_completed_tasks_by_category",
        {
            "category": "市场推广",
            "tasks": [
                {"title": "Q3发布会", "description": "", "subtasks": [
                    {"title": "定主题", "status": "completed", "sort_order": 1},
                    {"title": "做PPT", "status": "completed", "sort_order": 2},
                ]},
                {"title": "Q2路演", "description": "", "subtasks": [
                    {"title": "场地确认", "status": "completed", "sort_order": 1},
                ]},
            ],
        },
    )
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["decomposition"][0]["agent_reasoning"] = (
        "基于历史2次发布会的拆解习惯，沿用相同的准备顺序。"
    )
    stub = env.install(scripts)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)

    # few-shot 任务标题出现在拆解 prompt 中
    decomposition_prompt = next(
        system for purpose, system in stub.prompts if purpose == "decomposition"
    )
    assert "Q3发布会" in decomposition_prompt
    assert "Q2路演" in decomposition_prompt

    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "基于历史2次" in reasoning


async def test_high_workload_produces_reminder(env) -> None:
    """≥3 高优先级未完成：reasoning 含负载提醒。"""
    env.fake.set_data(
        "get_current_workload",
        {
            "unfinished_count": 5,
            "priority_distribution": {"high": 3, "medium": 1, "low": 1},
            "due_soon_count": 2,
        },
    )
    env.install(DEFAULT_SCRIPTS)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    reasoning = "".join(
        data["chunk"] for name, data in events if name == "reasoning"
    )
    assert "高优先级" in reasoning
    assert "提醒" in reasoning


async def test_cold_system_without_history_still_decomposes(env) -> None:
    """冷系统（无历史、无模板）：仍完成拆解，无 error 事件。"""
    env.fake.set_data("get_completed_tasks_by_category", {"category": "市场推广", "tasks": []})
    env.install(DEFAULT_SCRIPTS)

    response = await post_chat("准备产品发布会")
    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert "error" not in names
    assert names[-1] == "done"
    assert "proposal" in names
