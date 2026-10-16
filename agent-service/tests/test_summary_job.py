"""Change 5 Task 1：❌2 取消学习信号 + 分布式锁编排 + A-2 手动触发权限。

本文件的假件（FakeSummaryBackend / FakeSummaryDb / make_summarizer）同时被
tests/test_summarizer.py（Task 3 分类环）复用。

假件策略（design.md D1 锁归属后端）：并发互斥的真实证据在 Java/PG 侧
（InternalAgentSummaryLockTest + uk_sel_family_run_date 表达式唯一索引）；
本文件锁定 agent-service 侧契约——「未抢到锁必须 skip 且绝不跑总结主体」。
"""
import asyncio
from typing import Any

import httpx
import pytest

from app.main import create_app
from app.models.errors import RoleDeniedError
from app.routes import summary_routes
from app.services.auth_verifier import CurrentUser
from app.services.summarizer import Summarizer

_TEMPLATE_3: dict[str, Any] = {"id": 77, "name": "周末大扫除", "type": "decomposition", "category_id": 3}


class FakeSummaryBackend:
    """C-10/C-11/C-18/C-19/C-20/C-21/C-22/C-24 假件；claim 只放行首次（模拟同日唯一锁）。"""

    def __init__(self, templates: list[dict[str, Any]] | None = None) -> None:
        self.templates = templates if templates is not None else [dict(_TEMPLATE_3)]
        self.categories: list[dict[str, Any]] = []
        self.claim_calls: list[tuple[int, str, bool]] = []
        self.finish_calls: list[dict[str, Any]] = []
        self.append_calls: list[tuple[int, int, str]] = []
        self.version_calls: list[dict[str, Any]] = []
        self.cleanup_calls: list[int] = []
        self.family_ids: list[int] = []
        self.versions: list[dict[str, Any]] = []
        self.cleanup_deleted = 0
        self._acquired = False
        self._log_seq = 900

    # ---- C-10 / C-11 / C-23 / C-24 读 ----

    async def get_categories(self, family_id: int) -> list[dict[str, Any]]:
        assert isinstance(family_id, int), "family_id 必须 int"
        return self.categories

    async def get_decomposition_templates(self, family_id: int) -> list[dict[str, Any]]:
        assert isinstance(family_id, int), "family_id 必须 int"
        return self.templates

    async def get_template_versions(
        self, family_id: int, template_id: int, limit: int = 10
    ) -> list[dict[str, Any]]:
        assert isinstance(family_id, int), "family_id 必须 int"
        assert limit == 1, "❌2 幂等判定只读最新版"
        return self.versions

    async def get_active_family_ids(self) -> list[int]:
        return self.family_ids

    # ---- C-18 / C-19 / C-20 / C-21 / C-22 写 ----

    async def claim_summary_lock(
        self, family_id: int, trigger_source: str = "auto", force: bool = False
    ) -> dict[str, Any]:
        assert isinstance(family_id, int), "family_id 必须 int"
        assert trigger_source in ("auto", "manual"), "V6 CHECK 约束"
        self.claim_calls.append((family_id, trigger_source, force))
        if force:
            self._acquired = False  # 后端 force 语义：先删同日行再重插
        if self._acquired:
            return {"acquired": False, "log_id": self._log_seq + 1}
        self._acquired = True
        self._log_seq += 1
        return {"acquired": True, "log_id": self._log_seq}

    async def finish_summary_log(
        self,
        family_id: int,
        log_id: int,
        status: str,
        categories_updated: list[int],
        failed_categories: list[dict[str, Any]],
    ) -> dict[str, Any]:
        assert status in ("success", "failed"), "V7 CHECK 约束"
        self.finish_calls.append(
            {
                "family_id": family_id,
                "log_id": log_id,
                "status": status,
                "categories_updated": categories_updated,
                "failed_categories": failed_categories,
            }
        )
        return {"code": 0}

    async def append_change_summary(self, family_id: int, template_id: int, append: str) -> dict[str, Any]:
        self.append_calls.append((family_id, template_id, append))
        return {"code": 0, "data": {"new_version": 1}}

    async def create_new_template_version(
        self,
        family_id: int,
        template_id: int,
        content: dict[str, Any],
        change_summary: str,
        is_important: bool = False,
    ) -> dict[str, Any]:
        self.version_calls.append(
            {
                "family_id": family_id,
                "template_id": template_id,
                "content": content,
                "change_summary": change_summary,
                "is_important": is_important,
            }
        )
        return {"code": 0, "data": {"new_version": 2}}

    async def cleanup_template_versions(self, family_id: int) -> int:
        self.cleanup_calls.append(family_id)
        return self.cleanup_deleted


class FakeSummaryDb:
    """只读假件：❌2 统计口径（HAVING 阈值）与实际样本（json_agg 已归一）。"""

    def __init__(self, signal: dict[int, int] | None = None) -> None:
        self.signal = signal or {}
        self.completed_by_category: dict[int, list[dict[str, Any]]] = {}
        self.completed_calls: list[tuple[int, int, int, int]] = []
        self.seen: list[int] = []

    async def get_cancelled_agent_signal(
        self, family_id: int, window_days: int = 7, threshold: int = 5
    ) -> dict[int, int]:
        assert isinstance(family_id, int), "family_id 必须 int"
        self.seen.append(family_id)
        assert (window_days, threshold) == (7, 5), "❌2 口径固定：近 7 天 ≥5 次"
        return self.signal

    async def get_recent_completed(
        self, family_id: int, category_id: int, window_days: int = 30, limit: int = 10
    ) -> list[dict[str, Any]]:
        assert isinstance(family_id, int) and isinstance(category_id, int), "隔离守卫：两个 id 都必须 int"
        self.completed_calls.append((family_id, category_id, window_days, limit))
        return self.completed_by_category.get(category_id, [])


def make_summarizer(
    signal: dict[int, int] | None = None,
    templates: list[dict[str, Any]] | None = None,
    llm: Any = None,
    timeout: float | None = None,
) -> tuple[Summarizer, FakeSummaryBackend, FakeSummaryDb]:
    """组装全假件总结器（llm/timeout 缺省时 Task 1 用例不触分类环）。"""
    be = FakeSummaryBackend(templates)
    db = FakeSummaryDb(signal)
    kwargs: dict[str, Any] = {"backend": be, "db": db}
    if llm is not None:
        kwargs["llm"] = llm
    if timeout is not None:
        kwargs["per_category_timeout"] = timeout
    return Summarizer(**kwargs), be, db  # type: ignore[arg-type]


class FakeA2Auth:
    """A-2 身份假件：可注入错误（CHILD → RoleDeniedError）。"""

    def __init__(self, error: Exception | None = None) -> None:
        self.error = error

    async def verify(self, family_id: int, access_token: str) -> CurrentUser:
        if self.error is not None:
            raise self.error
        return CurrentUser(nickname="妈妈", family_id=family_id, role="OWNER")


async def _client(app: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


# ==================== 用例 1/2：❌2 取消信号 ====================


async def test_cancelled_signal_appends_chinese_warning() -> None:
    """命中阈值（近 7 天 ≥5 次取消）→ C-21 向该分类模板最新版追加中文警告。"""
    summarizer, be, db = make_summarizer(signal={3: 7})

    result = await summarizer.run_family(1, trigger_source="auto")

    assert db.seen == [1]
    assert len(be.append_calls) == 1
    family_id, template_id, warning = be.append_calls[0]
    assert (family_id, template_id) == (1, 77)
    assert "⚠️" in warning and "近 7 天" in warning and "取消 AI 提案 7 次" in warning
    assert "模板粒度" in warning and "家务规则" in warning and "提示词" in warning
    assert result is not None and result["status"] == "success"
    assert result["cancelled_signal"] == {"3": 7}
    assert be.finish_calls == [
        {
            "family_id": 1, "log_id": 901, "status": "success",
            "categories_updated": [], "failed_categories": [],
        }
    ]


async def test_cancelled_signal_below_threshold_appends_nothing() -> None:
    """未达阈值（HAVING 未命中 → SQL 返回空）→ 绝不追加警告（4 条取消 + 1 条 PENDING 场景）。"""
    summarizer, be, _db = make_summarizer(signal={})

    result = await summarizer.run_family(1)

    assert be.append_calls == []
    assert result is not None and result["cancelled_signal"] == {}


async def test_cancelled_signal_is_idempotent_within_same_day() -> None:
    """❌2 幂等（D10）：同日重跑（force）不重复追加同一条中文警告；跨日标记仍会追加。"""
    from datetime import date

    today = date.today().isoformat()
    summarizer, be, _db = make_summarizer(signal={3: 6})
    be.versions = [
        {"version": 3, "change_summary": f"首版说明\n⚠️ 【系统自动警告·{today}】此分类近 7 天家人连续取消 AI 提案 6 次"}
    ]

    await summarizer.run_family(1, trigger_source="manual", force=True)
    assert be.append_calls == []

    be.versions = [{"version": 4, "change_summary": "【系统自动警告·2020-01-01】旧日警告"}]
    await summarizer.run_family(1, trigger_source="manual", force=True)
    assert len(be.append_calls) == 1
    assert f"【系统自动警告·{today}】" in be.append_calls[0][2]


async def test_cancelled_signal_without_template_skips_append() -> None:
    """命中分类但无启用的 decomposition 模板 → 跳过追加但不报错、总结仍 success。"""
    summarizer, be, _db = make_summarizer(signal={9: 6}, templates=[])

    result = await summarizer.run_family(1)

    assert be.append_calls == []
    assert result is not None and result["status"] == "success"
    assert be.finish_calls[0]["status"] == "success"


# ==================== 用例 3：分布式锁互斥 ====================


async def test_lock_contention_hundred_concurrent_only_one_runs() -> None:
    """100 并发同家庭同日 → 仅 1 个真正执行总结，其余 99 个 skip（不重复写日志）。"""
    summarizer, be, _db = make_summarizer(signal={})

    results = await asyncio.gather(*(summarizer.run_family(1) for _ in range(100)))

    assert len(be.claim_calls) == 100
    assert sum(1 for r in results if r is not None) == 1
    assert sum(1 for r in results if r is None) == 99
    assert len(be.finish_calls) == 1  # 只有持锁者收尾


async def test_claim_rejects_unknown_trigger_source() -> None:
    """trigger_source 受 V6 CHECK 约束，非法值在发出请求前就拒（不污染锁表）。"""
    summarizer, be, _db = make_summarizer(signal={})

    with pytest.raises(ValueError):
        await summarizer.claim(1, "MANUAL")  # type: ignore[arg-type]

    assert be.claim_calls == []


# ==================== 用例 4/5：A-2 手动触发 ====================


async def test_a2_same_day_conflict_and_force() -> None:
    """同日已跑 → 46502 中文；force=true → 后端清同日锁后重跑并异步执行主体。"""
    summarizer, be, _db = make_summarizer(signal={})
    app = create_app()
    app.dependency_overrides[summary_routes.get_summarizer] = lambda: summarizer
    app.dependency_overrides[summary_routes.get_auth_verifier] = lambda: FakeA2Auth()

    async with await _client(app) as client:
        first = await client.post("/api/v1/summary/trigger", json={},
                                  headers={"X-Family-Id": "1", "X-Access-Token": "t"})
        conflict = await client.post("/api/v1/summary/trigger", json={},
                                     headers={"X-Family-Id": "1", "X-Access-Token": "t"})
        forced = await client.post("/api/v1/summary/trigger", json={"force": True},
                                   headers={"X-Family-Id": "1", "X-Access-Token": "t"})
        assert first.status_code == 200
        assert forced.status_code == 200
        assert set(first.json()) == {"trigger_id", "estimated_finish_at", "message"}
        assert first.json()["estimated_finish_at"].endswith("+00:00")
        while summary_routes._background_tasks:
            await asyncio.sleep(0.01)

    assert conflict.status_code == 400
    assert conflict.json()["code"] == 46502
    assert "今日已自动总结过" in conflict.json()["msg"]
    assert be.claim_calls == [(1, "manual", False), (1, "manual", False), (1, "manual", True)]
    # 两次抢锁成功（首次 + force）→ 两个异步主体各收尾一次
    assert [c["log_id"] for c in be.finish_calls] == [901, 902]
    assert [c["status"] for c in be.finish_calls] == ["success", "success"]


async def test_a2_child_role_rejected_40302() -> None:
    """CHILD 角色 → 40302 中文提示，且绝不触碰抢锁与总结主体。"""
    summarizer, be, _db = make_summarizer(signal={3: 9})
    app = create_app()
    app.dependency_overrides[summary_routes.get_summarizer] = lambda: summarizer
    app.dependency_overrides[summary_routes.get_auth_verifier] = lambda: FakeA2Auth(RoleDeniedError())

    async with await _client(app) as client:
        resp = await client.post("/api/v1/summary/trigger", json={},
                                 headers={"X-Family-Id": "1", "X-Access-Token": "t"})

    assert resp.status_code == 403
    assert resp.json()["code"] == 40302
    assert "孩子成员" in resp.json()["msg"]
    assert be.claim_calls == [] and be.append_calls == []


async def test_a2_requires_token_and_family() -> None:
    """缺 X-Access-Token → 40103；X-Family-Id 非法 → 40099（沿用 A-1 校验口径）。"""
    summarizer, _be, _db = make_summarizer(signal={})
    app = create_app()
    app.dependency_overrides[summary_routes.get_summarizer] = lambda: summarizer
    app.dependency_overrides[summary_routes.get_auth_verifier] = lambda: FakeA2Auth()

    async with await _client(app) as client:
        no_token = await client.post("/api/v1/summary/trigger", json={}, headers={"X-Family-Id": "1"})
        bad_family = await client.post("/api/v1/summary/trigger", json={},
                                       headers={"X-Family-Id": "abc", "X-Access-Token": "t"})
        zero_family = await client.post("/api/v1/summary/trigger", json={},
                                        headers={"X-Family-Id": "0", "X-Access-Token": "t"})

    assert no_token.json()["code"] == 40103
    assert bad_family.json()["code"] == 40099
    assert zero_family.json()["code"] == 40099


# ==================== 22:00 调度环 ====================


async def test_daily_summary_batch_isolates_family_failure() -> None:
    """单家庭抛错不阻断其它家庭；未抢到锁记 skip。"""
    from app.schedulers import summary_job as summary_job_mod

    summarizer, be, _db = make_summarizer(signal={})
    be.family_ids = [1, 2, 3]
    calls: list[int] = []

    async def fake_run_family(family_id: int, trigger_source: str = "auto", force: bool = False) -> Any:
        calls.append(family_id)
        if family_id == 2:
            raise RuntimeError("模拟单家庭炸裂")
        return None if family_id == 3 else {"status": "success"}

    summarizer.run_family = fake_run_family  # type: ignore[method-assign]
    previous = summary_job_mod._summarizer  # noqa: SLF001
    summary_job_mod.configure(summarizer)
    try:
        await summary_job_mod.run_daily_summary()
    finally:
        summary_job_mod.configure(previous)  # type: ignore[arg-type]  # 复位全局，防污染其它用例

    assert calls == [1, 2, 3]


async def test_daily_summary_without_configurator_is_noop() -> None:
    """未 configure（如单实例只跑 API）→ 记警告直接返回，绝不炸调度线程。"""
    from app.schedulers import summary_job as summary_job_mod

    assert summary_job_mod._summarizer is None  # noqa: SLF001
    await summary_job_mod.run_daily_summary()  # 不抛即通过


async def test_run_claimed_finishes_failed_on_unexpected_error() -> None:
    """总结主体抛错 → 先收尾 failed（锁不悬挂）再上抛。"""
    summarizer, be, db = make_summarizer(signal={})

    async def boom(family_id: int, window_days: int = 7, threshold: int = 5) -> Any:
        raise ValueError("分析失败")

    db.get_cancelled_agent_signal = boom  # type: ignore[method-assign]

    with pytest.raises(ValueError):
        await summarizer.run_family(1)

    assert be.finish_calls[0]["status"] == "failed"
    assert "未预期错误" in be.finish_calls[0]["failed_categories"][0]["reason"]


def test_register_summary_job_registers_22_cron() -> None:
    """Change 5 后 job 真实注册（22:00 Asia/Shanghai，id=daily_summary）。"""
    from apscheduler.schedulers.asyncio import AsyncIOScheduler

    from app.schedulers.summary_job import register_summary_job

    scheduler = AsyncIOScheduler(timezone="Asia/Shanghai")
    assert register_summary_job(scheduler) is True
    job = scheduler.get_job("daily_summary")
    assert job is not None
    trigger = str(job.trigger)
    assert "hour='22'" in trigger and "minute='0'" in trigger
