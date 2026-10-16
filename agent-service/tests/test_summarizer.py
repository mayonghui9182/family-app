"""Change 5 Task 3：分类循环（单分类 60s 超时）+ LLM 中文 tips + C-18/C-22 落库 + B-2。

复用 test_summary_job 的假件（跨测试模块 import 沿用 Change 4 既有模式）。
超时用例把 per_category_timeout 压到 0.2s（等价语义，避免测试真等 60s）；
生产值仍由 settings.summary_timeout_per_category=60 提供（test_timeout_defaults_to_settings）。
"""
import json
from typing import Any

import httpx

from app.main import create_app
from app.routes import internal_routes, summary_routes
from app.services.summarizer import Summarizer
from tests.conftest import FakeLLM
from tests.test_diff_engine import BASE_STEPS as BASELINE_STEPS
from tests.test_summary_job import (
    FakeSummaryBackend,
    FakeSummaryDb,
    make_summarizer,
)

SUMMARY_JSON = json.dumps(
    {
        "additional_tips_cn": "大扫除建议分两天做，先公区后卧室，老人中途多休息。",
        "mark_important": True,
        "suggested_common_steps_add": ["清理阳台"],
        "frequency_removals": ["消毒门把手"],
    },
    ensure_ascii=False,
)


def _template(category_id: int, template_id: int) -> dict[str, Any]:
    return {
        "id": template_id,
        "name": "周末大扫除",
        "type": "decomposition",
        "category_id": category_id,
        "content": {
            "common_steps": list(BASELINE_STEPS),
            "count": len(BASELINE_STEPS),
            "notes": ["大扫除建议周六上午开始"],
        },
    }


def _sample(task_id: int, extra_step: str, notes: tuple[str, ...] = ()) -> dict[str, Any]:
    """一条已完成任务：AI 原始步骤（命中基线，不产差异）+ 家人补的 extra_step。"""
    subtasks = [
        {"title": t, "description": None, "sort_order": i + 1, "is_ai_generated": True}
        for i, t in enumerate(BASELINE_STEPS)
    ]
    subtasks.append(
        {"title": extra_step, "description": None, "sort_order": len(BASELINE_STEPS) + 1, "is_ai_generated": False}
    )
    return {"task_id": task_id, "subtasks": subtasks, "notes": list(notes)}


def _make_loop_setup(
    llm_outcomes: list[dict[str, Any]], timeout: float = 0.2
) -> tuple[Summarizer, FakeSummaryBackend, FakeSummaryDb, FakeLLM]:
    llm = FakeLLM(llm_outcomes)
    summarizer, be, db = make_summarizer(signal={}, llm=llm, timeout=timeout)
    be.categories = [{"id": 3, "name": "家务"}, {"id": 4, "name": "育儿"}]
    be.templates = [_template(3, 77), _template(4, 88)]
    db.completed_by_category = {
        3: [_sample(9001 + i, "擦窗台", ("记得戴橡胶手套",)) for i in range(3)],
        4: [_sample(9101 + i, "陪读绘本") for i in range(3)],
    }
    return summarizer, be, db, llm


async def test_category_loop_emits_learned_version_with_tips() -> None:
    """三样本复现家人新增步骤 → 达 ADD 阈值 → C-18 新版本（含中文 tips 与 ⭐ 标记）。"""
    summarizer, be, _db, llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}] * 2)

    updated, failed, created = await summarizer.summarize_categories(1)

    assert updated == [3, 4] and failed == [] and created == 2
    assert len(be.version_calls) == 2
    first = be.version_calls[0]
    assert (first["family_id"], first["template_id"]) == (1, 77)
    assert "擦窗台" in first["content"]["common_steps"]
    assert first["content"]["count"] == len(first["content"]["common_steps"])
    assert first["content"]["notes"]  # 原模板注意事项保留
    assert first["is_important"] is True  # LLM mark_important 透传
    assert "自动总结·家人行为学习" in first["change_summary"]
    assert "擦窗台" in first["change_summary"] and "阈值" in first["change_summary"]
    assert "AI 辅助建议：大扫除建议分两天做" in first["change_summary"]
    assert "AI 建议补充（未自动执行）：清理阳台" in first["change_summary"]
    assert "AI 建议减少（未自动执行）：消毒门把手" in first["change_summary"]
    assert len(llm.calls) == 2
    assert llm.calls[0]["response_format"] == {"type": "json_object"}


async def test_prompt_is_chinese_and_carries_family_scoped_samples_only() -> None:
    """提示词全中文 + 只含本分类样本步骤（家庭隔离 L3 的提示词侧证据）。"""
    summarizer, _be, _db, llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}] * 2)

    await summarizer.summarize_categories(1)

    prompt = llm.calls[0]["messages"][1]["content"]
    assert "家务" in prompt and "擦窗台" in prompt and "记得戴橡胶手套" in prompt
    assert "AI原始" in prompt and "additional_tips_cn" in prompt
    assert "【系统已识别的行为差异】" in prompt
    assert "9001" in prompt  # 样本任务号（供模型定位复现）
    assert "9101" not in prompt  # 另一分类样本不得串台


async def test_single_category_timeout_does_not_block_others() -> None:
    """Q79 容错降级：家务分类 LLM 卡死（超 0.2s 预算）→ 只记该分类失败，育儿分类照常落版本。"""
    summarizer, be, _db, _llm = _make_loop_setup(
        [{"kind": "slow", "seconds": 30}, {"kind": "json", "text": SUMMARY_JSON}]
    )

    updated, failed, created = await summarizer.summarize_categories(1)

    assert updated == [4] and created == 1
    assert len(failed) == 1
    assert failed[0]["category_id"] == 3
    assert "单分类总结超时" in failed[0]["reason"] and "不阻断其它分类" in failed[0]["reason"]
    assert [c["template_id"] for c in be.version_calls] == [88]


async def test_llm_bad_json_degrades_to_structured_learning_only() -> None:
    """LLM 吐坏 JSON / 抛错 → 结构化学习结论照样落库（tips 降级为空，版本不丢）。"""
    summarizer, be, _db, _llm = _make_loop_setup([{"kind": "json", "text": "抱歉，我说不清"}] +
                                                 [{"kind": "json", "text": SUMMARY_JSON}])

    updated, failed, created = await summarizer.summarize_categories(1)

    assert 3 in updated and created == 2
    degraded = be.version_calls[0]
    assert degraded["template_id"] == 77
    assert "AI 辅助建议" not in degraded["change_summary"]
    assert "擦窗台" in degraded["change_summary"]  # 结构结论仍在
    assert degraded["is_important"] is False  # 坏 JSON → 默认不标重要


async def test_no_diff_and_too_few_samples_emit_no_version() -> None:
    """无差异（样本步骤 == 模板）与样本不足（<2）都不调 LLM、不落版本（成本纪律）。"""
    summarizer, be, db, llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}])
    db.completed_by_category = {
        # 家务：完全等于模板 → 无差异
        3: [_baseline_sample(9001), _baseline_sample(9002)],
        # 育儿：仅 1 条样本 → 跳过
        4: [_sample(9101, "陪读绘本")],
    }

    updated, failed, created = await summarizer.summarize_categories(1)

    assert updated == [] and failed == [] and created == 0
    assert llm.calls == [] and be.version_calls == []


def _baseline_sample(task_id: int) -> dict[str, Any]:
    return {
        "task_id": task_id,
        "subtasks": [
            {"title": t, "description": None, "sort_order": i + 1, "is_ai_generated": True}
            for i, t in enumerate(BASELINE_STEPS)
        ],
        "notes": [],
    }


async def test_run_family_cleans_versions_and_finishes_log() -> None:
    """完整编排：抢锁 → ❌2 → 分类环 → C-22 清理 → C-20 收尾（success + 版本计数）。"""
    summarizer, be, _db, _llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}] * 2)
    be.cleanup_deleted = 4

    result = await summarizer.run_family(1, trigger_source="auto")

    assert result is not None
    assert result["status"] == "success"
    assert result["versions_created"] == 2
    assert result["versions_cleaned"] == 4
    assert be.cleanup_calls == [1]
    assert be.finish_calls[0]["status"] == "success"
    assert be.finish_calls[0]["categories_updated"] == [3, 4]


async def test_partial_category_failure_is_partial_success_but_logged_success() -> None:
    """D8 口径：部分分类失败但确有产出 → 对调用方 partial_success，锁日志仍记 success（分类级失败另表）。"""
    summarizer, be, _db, _llm = _make_loop_setup(
        [{"kind": "json", "text": SUMMARY_JSON}, {"kind": "slow", "seconds": 30}]
    )

    result = await summarizer.run_family(1)

    assert result is not None
    assert result["status"] == "partial_success"
    assert result["categories_updated"] == [3]
    assert [f["category_id"] for f in result["failed_categories"]] == [4]
    assert be.finish_calls[0]["status"] == "success"  # 有产出即 success，失败明细进 failed_categories
    assert be.finish_calls[0]["failed_categories"][0]["category_id"] == 4


async def test_only_category_ids_scopes_the_loop() -> None:
    """B-2 传 category_ids → 只处理指定分类。"""
    summarizer, be, _db, _llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}])

    updated, _failed, _created = await summarizer.summarize_categories(1, only_category_ids=[4])

    assert updated == [4]
    assert [c["template_id"] for c in be.version_calls] == [88]


def test_timeout_defaults_to_settings() -> None:
    """缺省超时取 settings.summary_timeout_per_category（=60 秒，plan §全局约束）。"""
    from app.settings import get_settings

    summarizer = Summarizer(backend=None, db=None, llm=object(), engine=object())  # type: ignore[arg-type]
    assert summarizer.per_category_timeout == float(get_settings().summary_timeout_per_category) == 60.0


async def test_b2_one_family_real_run_and_second_call_skipped(monkeypatch: Any) -> None:
    """B-2 实装：带内部秘钥跑完整编排；同日二次调用抢不到锁 → completed=false/skipped=true。"""
    monkeypatch.setenv("INTERNAL_API_SECRET", "itest-secret")
    from app.settings import get_settings

    get_settings.cache_clear()
    summarizer, be, _db, _llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}] * 2)
    app = create_app()
    app.dependency_overrides[summary_routes.get_summarizer] = lambda: summarizer

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
        first = await client.post(
            "/api/internal/summary/one-family",
            json={"family_id": 7, "category_ids": [], "trigger_source": "MANUAL"},
            headers={"X-Internal-Secret": "itest-secret"},
        )
        second = await client.post(
            "/api/internal/summary/one-family",
            json={"family_id": 7, "category_ids": [3], "trigger_source": "AUTO"},
            headers={"X-Internal-Secret": "itest-secret"},
        )
    get_settings.cache_clear()

    assert first.status_code == 200
    body = first.json()
    assert body["completed"] is True and body["skipped"] is False
    assert body["categories_updated"] == [3, 4] and body["versions_created"] == 2
    assert "本家庭总结已完成" in body["message"]
    assert second.json()["completed"] is False and second.json()["skipped"] is True
    assert be.claim_calls == [(7, "manual", False), (7, "auto", False)]  # MANUAL 归一化为 manual
    assert internal_routes.router is not None  # 路由可导入（防重构漂移）


async def test_category_read_error_is_isolated_from_others() -> None:
    """单分类样本读取抛错（真机 42501 / 网络抖动）→ 只记该分类失败，其它分类照常落版本。"""
    summarizer, be, db, llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}])
    original = db.get_recent_completed

    async def selective(family_id: int, category_id: int, window_days: int = 30, limit: int = 10) -> Any:
        if category_id == 3:
            raise RuntimeError("permission denied for table tasks")
        return await original(family_id, category_id, window_days=window_days, limit=limit)

    db.get_recent_completed = selective  # type: ignore[method-assign]

    updated, failed, created = await summarizer.summarize_categories(1)

    assert updated == [4] and created == 1
    assert failed[0]["category_id"] == 3 and "未预期错误" in failed[0]["reason"]
    assert len(llm.calls) == 1  # 失败的分类绝不消耗 LLM 预算


async def test_categories_without_template_or_with_bad_shape_are_skipped() -> None:
    """无启用模板 / 脏数据行（id 非 int、缺 id）→ 静默跳过，不算失败也不调 LLM。"""
    summarizer, be, _db, llm = _make_loop_setup([{"kind": "json", "text": SUMMARY_JSON}])
    be.categories = [
        {"id": 3, "name": "家务"},
        {"id": 99, "name": "无模板分类"},
        {"id": "x", "name": "脏数据"},
        {"name": "缺 id"},
    ]

    updated, failed, created = await summarizer.summarize_categories(1)

    assert updated == [3] and failed == [] and created == 1
    assert len(llm.calls) == 1


async def test_version_history_read_failure_still_appends_warning() -> None:
    """❌2「宁重不漏」：C-24 读历史失败时不吞掉学习信号，当日警告照旧追加。"""
    summarizer, be, _db = make_summarizer(signal={3: 6})

    async def explode(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("backend 不可达")

    be.get_template_versions = explode  # type: ignore[method-assign]

    result = await summarizer.run_family(1)

    assert len(be.append_calls) == 1
    assert "取消 AI 提案 6 次" in be.append_calls[0][2]
    assert result is not None and result["status"] == "success"
