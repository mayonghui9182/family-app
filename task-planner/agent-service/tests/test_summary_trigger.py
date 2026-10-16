"""调度与手动触发测试（tasks.md 9.5）。

覆盖：auto/manual 两种 trigger_source 的执行日志、同日重复触发 409（body 说明
当天已总结并请求确认）、force=true 绕过拦截、调度器注册每日 22:00 任务。
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
from httpx import ASGITransport, AsyncClient

from src.config import settings
from src.main import app
from src.scheduler import JOB_ID, build_scheduler
from src.services.summary_run import run_summary


async def _post_trigger(body: dict | None = None):
    """向 /api/summary/trigger 发起请求（body=None 表示不带请求体）。"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post("/api/summary/trigger", json=body)


def _success_row_now() -> dict:
    """当天已成功的执行日志行（run_at 取真实当前时刻）。"""
    return {
        "id": "log-1",
        "run_at": datetime.now(timezone.utc).isoformat(),
        "status": "success",
        "error_message": None,
        "categories_updated": ["市场推广"],
        "trigger_source": "auto",
    }


async def test_manual_trigger_logs_manual_source(summary_env):
    """手动触发：数据范围与定时一致，日志 trigger_source=manual。"""
    response = await _post_trigger()

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success" and body["trigger_source"] == "manual"

    logs = summary_env.fake.calls_of("log_summary_execution")
    assert len(logs) == 1
    assert logs[0]["trigger_source"] == "manual" and logs[0]["status"] == "success"
    # 每次运行顺带清理过期 draft（9.6 契约，此处验证调用发生）
    assert len(summary_env.fake.calls_of("cancel_expired_drafts")) == 1


async def test_scheduled_job_logs_auto_source(summary_env, monkeypatch):
    """定时任务体（调度器 22:00 触发的入口）：日志 trigger_source=auto。"""
    from src.scheduler import run_scheduled_summary
    from src.scheduler import daily_summary
    from src.work_auth import WorkIdentity

    class Response:
        def raise_for_status(self): pass
        def json(self): return {"userIds": ["1"]}

    class Client:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def get(self, *args, **kwargs): return Response()

    async def verified(*args, **kwargs): return WorkIdentity("1", {})
    monkeypatch.setattr(daily_summary.httpx, "AsyncClient", Client)
    monkeypatch.setattr(daily_summary, "verify_identity", verified)
    monkeypatch.setattr(settings, "internal_api_secret", "test-secret")

    await run_scheduled_summary()

    logs = summary_env.fake.calls_of("log_summary_execution")
    assert len(logs) == 1
    assert logs[0]["trigger_source"] == "auto" and logs[0]["status"] == "success"


async def test_same_day_rerun_without_force_conflicts(summary_env):
    """当天已成功且未带 force → 409，body 说明当天已总结并请求确认。"""
    summary_env.fake.set_data("get_summary_executions", [_success_row_now()])

    response = await _post_trigger()

    assert response.status_code == 409
    detail = response.json()["detail"]
    assert "已成功执行" in detail and "force" in detail
    # 被拦截的运行不写日志、不清理 draft
    assert summary_env.fake.calls_of("log_summary_execution") == []
    assert summary_env.fake.calls_of("cancel_expired_drafts") == []


async def test_force_flag_bypasses_conflict(summary_env):
    """force=true：同日重跑放行，正常执行并再写一条 manual 日志。"""
    summary_env.fake.set_data("get_summary_executions", [_success_row_now()])

    response = await _post_trigger({"force": True})

    assert response.status_code == 200
    assert response.json()["status"] == "success"
    logs = summary_env.fake.calls_of("log_summary_execution")
    assert len(logs) == 1 and logs[0]["trigger_source"] == "manual"


async def test_invalid_trigger_source_rejected(summary_env):
    """trigger_source 只接受 auto/manual（编程错误尽早暴露）。"""
    with pytest.raises(ValueError):
        await run_summary("cron")


def test_scheduler_registers_daily_22_job():
    """调度器注册每日 22:00（settings.tz）的总结任务，且不在测试中启动。"""
    scheduler = build_scheduler()
    job = scheduler.get_job(JOB_ID)
    assert job is not None
    trigger = str(job.trigger)
    assert "hour='22'" in trigger and "minute='0'" in trigger
    # CronTrigger 的字符串形式不含时区，时区要单独查属性
    assert str(job.trigger.timezone) == settings.tz
    # 只构建不启动：启停由 main.py 的 lifespan 负责
    assert scheduler.running is False
