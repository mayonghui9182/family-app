"""AI 拆解智能字段生成测试：验证 LLM 生成的 category_id/due_date/assignee_profile_id
正确透传到 proposed_tasks（A-1）与 subtasks batch（B-1）；天气真实查询注入 prompt。

覆盖：
1. A-1 模式 proposed_tasks 带三字段（_flatten_to_proposed_tasks）
2. B-1 模式 subtasks batch 带三字段（_persist）
3. 城市匹配时 WeatherClient 被调用，prompt 含 weather + profiles + categories + today
4. 无城市匹配时降级 unknown，不阻断主流程
5. json_guard 解析含三字段的 subtask JSON 不报错
"""
import json

import pytest

from app.harness.json_guard import parse
from app.models.llm_schemas import SubtaskProposal
from tests.conftest import (
    CTX_COLD,
    FakeLLM,
    FakeWeather,
    collect,
    make_decomposer,
)

# 含三字段的合法拆解 JSON（子任务带 category_id/due_date/assignee_profile_id）
VALID_DECOMPOSE_JSON_WITH_FIELDS = json.dumps(
    {
        "title": "北京周末户外活动",
        "description": "全家去公园玩",
        "priority": "MEDIUM",
        "category_id": 3,
        "subtasks": [
            {
                "title": "查天气",
                "description": "确认今天晴天",
                "sort_order": 1,
                "category_id": 3,
                "due_date": "2026-09-12",
                "assignee_profile_id": 11,
            },
            {
                "title": "准备野餐",
                "description": "妈妈做三明治",
                "sort_order": 2,
                "category_id": 3,
                "due_date": "2026-09-12",
                "assignee_profile_id": 11,
            },
            {
                "title": "开车去公园",
                "description": "爸爸开车",
                "sort_order": 3,
                "category_id": None,
                "due_date": None,
                "assignee_profile_id": 12,
            },
        ],
        "notes": ["记得带防晒霜"],
    },
    ensure_ascii=False,
)


@pytest.mark.asyncio
async def test_a1_proposed_tasks_carry_three_fields() -> None:
    """A-1 模式：LLM 生成的 category_id/due_date/assignee_profile_id 正确落 proposed_tasks。"""
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},  # 分类步
        {"kind": "json", "tokens": ["拆", "解"], "text": VALID_DECOMPOSE_JSON_WITH_FIELDS},
    ])
    dec, be = make_decomposer(llm, CTX_COLD)
    be.categories = [{"id": 3, "name": "家务"}]
    frames = await collect(dec.stream(family_id=1, title="北京周末户外活动", session_id=99))
    assert frames[-1]["event"] == "done"
    # B-6 save_proposal 被调用一次
    assert be.saved_proposal is not None
    assert len(be.saved_proposal) == 3
    # 第一条：三字段齐全
    row0 = be.saved_proposal[0]
    assert row0["category_id"] == 3
    assert row0["due_date"] == "2026-09-12"
    assert row0["assignee_profile_id"] == 11
    # 第三条：category_id 为 null → 回退父 proposal.category_id=3
    row2 = be.saved_proposal[2]
    assert row2["category_id"] == 3  # 回退父 proposal
    assert "due_date" not in row2  # s.due_date 为 None → 不写入
    assert row2["assignee_profile_id"] == 12


@pytest.mark.asyncio
async def test_b1_subtasks_batch_carry_three_fields() -> None:
    """B-1 模式：_persist 调 create_subtasks_batch 时 subtasks 带三字段。"""
    llm = FakeLLM([{"kind": "raw", "text": VALID_DECOMPOSE_JSON_WITH_FIELDS}])
    dec, be = make_decomposer(llm, CTX_COLD)
    be.categories = []  # 无分类 → 单次 LLM 调用
    ok, task_id, msg = await dec.run_buffered(family_id=9, title="北京周末户外活动")
    assert ok is True and task_id == 501
    # 查 create_subtasks_batch 调用 payload
    batch_call = next(c for c in be.calls if c[0] == "create_subtasks_batch")
    subtasks = batch_call[2]["subtasks"]
    assert len(subtasks) == 3
    assert subtasks[0]["category_id"] == 3
    assert subtasks[0]["due_date"] == "2026-09-12"
    assert subtasks[0]["assignee_profile_id"] == 11
    # 第三条 category_id 为 None（B-1 不回退，保留 LLM 原值）
    assert subtasks[2]["category_id"] is None
    assert subtasks[2]["due_date"] is None
    assert subtasks[2]["assignee_profile_id"] == 12


@pytest.mark.asyncio
async def test_weather_injected_when_city_matched() -> None:
    """任务标题含城市关键词时 WeatherClient 被调用，prompt 含 weather + profiles + categories + today。"""
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "raw", "text": VALID_DECOMPOSE_JSON_WITH_FIELDS},
    ])
    weather = FakeWeather({"北京": "sunny"})
    dec, be = make_decomposer(llm, CTX_COLD, weather=weather)
    be.categories = [{"id": 3, "name": "户外"}]
    frames = await collect(dec.stream(family_id=1, title="北京周末户外活动", session_id=88))
    assert frames[-1]["event"] == "done"
    # WeatherClient 被调用（城市=北京）
    assert len(weather.calls) == 1
    assert weather.calls[0][0] == "北京"
    # 第二次 LLM 调用（拆解步）的 user prompt 含 weather=分类 + profiles + categories + today
    decompose_call = llm.calls[1]
    user_content = decompose_call["messages"][1]["content"]
    assert "今日天气：sunny" in user_content
    assert "北京" in user_content or "妈妈" in user_content  # profiles 注入
    assert "户外" in user_content  # categories 注入
    assert "2026-" in user_content  # today 注入


@pytest.mark.asyncio
async def test_weather_unknown_when_no_city_matched() -> None:
    """任务标题无城市关键词时降级 unknown，不阻断主流程，prompt 不含天气行。"""
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "raw", "text": VALID_DECOMPOSE_JSON_WITH_FIELDS},
    ])
    weather = FakeWeather({"北京": "sunny"})
    dec, be = make_decomposer(llm, CTX_COLD, weather=weather)
    be.categories = [{"id": 3, "name": "家务"}]
    frames = await collect(dec.stream(family_id=1, title="周末大扫除", session_id=77))
    assert frames[-1]["event"] == "done"
    # 无城市匹配 → WeatherClient 未被调用
    assert weather.calls == []
    # prompt 不含天气行（weather=unknown 时 system.j2 + decompose.j2 都不渲染天气）
    decompose_call = llm.calls[1]
    user_content = decompose_call["messages"][1]["content"]
    assert "今日天气" not in user_content


def test_json_guard_parses_subtasks_with_three_fields() -> None:
    """json_guard 解析含 category_id/due_date/assignee_profile_id 的子任务 JSON 不报错。"""
    raw = VALID_DECOMPOSE_JSON_WITH_FIELDS
    proposal = parse(raw)
    assert len(proposal.subtasks) == 3
    s0: SubtaskProposal = proposal.subtasks[0]
    assert s0.category_id == 3
    assert s0.due_date == "2026-09-12"
    assert s0.assignee_profile_id == 11
    # 第三条：category_id/due_date 为 None，assignee_profile_id=12
    s2: SubtaskProposal = proposal.subtasks[2]
    assert s2.category_id is None
    assert s2.due_date is None
    assert s2.assignee_profile_id == 12


def test_subtask_proposal_defaults_none() -> None:
    """SubtaskProposal 三字段默认 None（向后兼容旧 JSON 不带三字段）。"""
    s = SubtaskProposal(title="测试", description="", sort_order=1)
    assert s.category_id is None
    assert s.due_date is None
    assert s.assignee_profile_id is None
