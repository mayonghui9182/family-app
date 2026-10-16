"""分类环节测试（tasks.md 8.2）：只用既有分类，无匹配归「未分类」且不建分类。"""

from __future__ import annotations

import copy

from conftest import DEFAULT_SCRIPTS, parse_sse, post_chat


async def test_matching_category_reported_with_confidence(env) -> None:
    """命中既有分类：classification 事件带 category 与 [0,1] 的 confidence。"""
    env.install(DEFAULT_SCRIPTS)
    response = await post_chat("准备Q4产品发布会")

    events = parse_sse(response.text)
    classification = next(data for name, data in events if name == "classification")
    assert classification["category"] == "市场推广"
    assert 0.0 <= classification["confidence"] <= 1.0

    # 落库任务挂在命中分类下
    create_args = env.fake.calls_of("create_task")[0]
    assert create_args["category_id"] == "cat-mkt"


async def test_unmatched_falls_back_to_reserved_category(env) -> None:
    """无匹配归「未分类」：分类集合不变（绝不创建新分类）。"""
    before = copy.deepcopy(env.fake.data["get_categories"])
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["classification"] = [{"category": "不存在的分类", "confidence": 0.95}]
    env.install(scripts)

    response = await post_chat("一件和所有分类都不沾边的事")

    events = parse_sse(response.text)
    classification = next(data for name, data in events if name == "classification")
    assert classification["category"] == "未分类"

    # 分类表数量与内容未变：没有任何创建分类的 MCP 调用
    assert env.fake.data["get_categories"] == before
    assert env.fake.calls_of("create_category") == []

    # 落库任务挂在「未分类」下
    create_args = env.fake.calls_of("create_task")[0]
    assert create_args["category_id"] == "cat-uncat"


async def test_confidence_clamped_to_unit_interval(env) -> None:
    """置信度越界时收敛到 [0,1]。"""
    scripts = copy.deepcopy(DEFAULT_SCRIPTS)
    scripts["classification"] = [{"category": "市场推广", "confidence": 1.7}]
    env.install(scripts)

    response = await post_chat("准备发布会")
    events = parse_sse(response.text)
    classification = next(data for name, data in events if name == "classification")
    assert classification["confidence"] == 1.0
