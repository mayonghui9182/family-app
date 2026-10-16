"""prompt 模板测试（tasks.md 7.3）：关键约束字符串存在且模板可渲染。

关键约束对齐设计文档 §6/§8：
- 拆解：2 到 8 条、优先参考历史模式、高负载提醒、无历史参考标注
- 分类：只用既有分类、绝不创建新分类、兜底「未分类」
- 总结：≥2 次重复才更新、必带 reason、四种 action
"""

from __future__ import annotations

import pytest

from src.llm.prompts import render_prompt


def test_unknown_template_name_raises() -> None:
    """未知模板名：抛 ValueError。"""
    with pytest.raises(ValueError):
        render_prompt("not_exist")


def test_classification_prompt_constraints_and_render() -> None:
    """分类 prompt：禁创新分类、兜底未分类，且可渲染。"""
    text = render_prompt(
        "classification",
        message="准备Q4产品发布会",
        categories=[{"name": "市场推广", "description": "活动与推广"}],
    )
    assert "绝不创建新分类" in text
    assert "未分类" in text
    assert "市场推广" in text
    assert "准备Q4产品发布会" in text
    assert "confidence" in text


def test_classification_prompt_without_categories() -> None:
    """空分类列表也能渲染（冷系统兜底场景）。"""
    text = render_prompt("classification", message="随便一件事", categories=[])
    assert "未分类" in text


def test_template_match_prompt_constraints_and_render() -> None:
    """模板匹配 prompt：近似匹配标注调整、无匹配走冷启动。"""
    text = render_prompt(
        "template_match",
        message="准备产品发布会",
        templates=[
            {
                "id": "t-1",
                "name": "发布会准备",
                "common_steps": ["确定主题", "制作PPT", "彩排"],
                "estimated_subtask_count": 4,
            }
        ],
    )
    assert "参考该模板并有所调整" in text
    assert "冷启动" in text
    assert "不得使用默认兜底模板" in text
    assert "发布会准备" in text


def test_decomposition_prompt_constraints_and_render() -> None:
    """拆解 prompt：2-8 条、优先历史模式、高负载提醒、无历史参考。"""
    text = render_prompt(
        "decomposition",
        message="准备产品发布会",
        few_shot_tasks=[{"title": "Q3发布会", "subtasks": ["定主题", "做PPT"]}],
        matched_template={
            "name": "发布会准备",
            "common_steps": ["确定主题", "制作PPT"],
            "estimated_subtask_count": 7, "notes": "必须确认无障碍通道",
        },
        workload={
            "unfinished_count": 5,
            "priority_distribution": {"high": 3, "medium": 2},
            "due_soon_count": 1,
            "high_priority_count": 3,
        },
    )
    assert "2 到 8 条" in text
    assert "优先参考这些历史模式" in text
    assert "负载较高" in text
    assert "agent_reasoning" in text
    assert "Q3发布会" in text


def test_decomposition_prompt_cold_start_without_history() -> None:
    """无历史无模板：渲染出「无历史参考」与冷启动说明。"""
    text = render_prompt(
        "decomposition",
        message="新任务",
        few_shot_tasks=[],
        matched_template=None,
        workload=None,
    )
    assert "无历史参考" in text
    assert "冷启动" in text


def test_summary_prompt_constraints_and_render() -> None:
    """总结 prompt：≥2 次重复才更新、必带 reason、四种 action。"""
    text = render_prompt(
        "summary",
        category_name="市场推广",
        template={
            "name": "发布会准备",
            "common_steps": ["确定主题"],
            "estimated_subtask_count": 4,
            "notes": "",
        },
        tasks=[
            {"task_id": "a", "ai_steps": ["打印材料"], "current_steps": ["邀请媒体"], "title": "任务A", "deleted_steps": ["打印材料"], "added_steps": ["邀请媒体"]},
            {"task_id": "b", "ai_steps": [], "current_steps": ["邀请媒体"], "title": "任务B", "deleted_steps": [], "added_steps": ["邀请媒体"]},
        ],
    )
    assert "至少 2 个任务中重复出现" in text
    assert "单次偶发调整绝不更新模板" in text
    assert "必须带 reason" in text
    for action in ("add_step", "remove_step", "update_estimated_count", "update_notes"):
        assert action in text
    assert "邀请媒体" in text


def test_summary_prompt_without_template() -> None:
    """无模板分类：渲染出可建议新建的提示。"""
    text = render_prompt("summary", category_name="新分类", template=None, tasks=[])
    assert "尚无 decomposition 模板" in text
