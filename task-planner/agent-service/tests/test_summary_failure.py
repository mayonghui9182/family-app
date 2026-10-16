"""失败重试与数据窗口测试（tasks.md 9.6，桩时钟）。

覆盖 4 个场景：首次失败后退避重试成功、三次重试耗尽记 failed、连续三天失败后
窗口前移合并积压数据、每次尝试顺带清理过期 draft。

时钟与退避均注入：`now` 固定运行时刻，`sleep` 收集退避秒数而不真正等待。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from src.services.mcp_gateway import McpToolError
from src.services.summary_run import run_summary

# 桩时钟：2026-09-20 22:00（Asia/Shanghai），与 settings.summary_hour 一致
_NOW = datetime(2026, 9, 20, 22, 0, tzinfo=ZoneInfo("Asia/Shanghai"))
# 正常窗口起点（当天 0:00）与连续失败三天后的窗口起点
_SINCE_TODAY = "2026-09-20T00:00:00+08:00"
_SINCE_WIDENED = "2026-09-17T00:00:00+08:00"

# 总结链路中会调用 MCP 的三个模块（包装 call_tool 时都要覆盖）
_CALL_TOOL_MODULES = (
    "src.services.summary_run",
    "src.services.summary_collect",
    "src.services.summary_learn",
)


def _sleep_recorder() -> tuple[list[float], Any]:
    """零延迟退避桩：返回 (已记录的秒数列表, 可注入的 sleep 协程)。"""
    recorded: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        recorded.append(seconds)

    return recorded, fake_sleep


def _fail_once(
    monkeypatch,
    fake,
    tool_name: str,
    exc: Exception,
) -> None:
    """让某工具第一次调用抛错、之后恢复正常。

    FakeMcp 的 errors 是静态的（预置后每次都抛），重试成功场景需要状态化包装；
    失败的调用同样记进 fake.calls，保持「网关确实被调用过」的语义。
    """
    remaining = {"count": 1}

    async def wrapper(name: str, arguments: dict[str, Any] | None = None) -> Any:
        if name == tool_name and remaining["count"]:
            remaining["count"] -= 1
            fake.calls.append((name, arguments or {}))
            raise exc
        return await fake(name, arguments)

    for module_name in _CALL_TOOL_MODULES:
        monkeypatch.setattr(f"{module_name}.call_tool", wrapper)


def _failed_row(day: int) -> dict[str, Any]:
    """某天运行失败的执行日志行（run_at 倒序传入）。"""
    return {
        "id": f"log-{day}",
        "run_at": f"2026-09-{day}T22:00:00+08:00",
        "status": "failed",
        "error_message": "MCP 工具失败 code=500 msg=task-service 不可用",
        "categories_updated": [],
        "trigger_source": "auto",
    }


async def test_retry_succeeds_after_backoff(summary_env, monkeypatch):
    """运行级失败退避 1 分钟后重试成功：日志仍为 success，attempts=2。"""
    fake = summary_env.fake
    _fail_once(
        monkeypatch,
        fake,
        "get_tasks_changed_since",
        McpToolError(500, "数据库连接中断"),
    )
    recorded, fake_sleep = _sleep_recorder()

    result = await run_summary("auto", now=_NOW, sleep=fake_sleep)

    assert recorded == [60.0]
    assert result.status == "success"
    assert result.attempts == 2
    assert result.error_message is None
    assert len(fake.calls_of("get_tasks_changed_since")) == 2
    logs = fake.calls_of("log_summary_execution")
    assert len(logs) == 1
    assert logs[0]["status"] == "success" and logs[0]["error_message"] is None


async def test_retries_exhausted_records_failure(summary_env):
    """三次重试全失败：当天跳过、记 failed 与原因，退避 1min→5min→30min。"""
    fake = summary_env.fake
    fake.set_error("get_tasks_changed_since", McpToolError(500, "task-service 不可用"))
    recorded, fake_sleep = _sleep_recorder()

    result = await run_summary("auto", now=_NOW, sleep=fake_sleep)

    assert recorded == [60.0, 300.0, 1800.0]
    assert result.status == "failed"
    assert result.attempts == 4
    assert result.categories_updated == []
    # 每次尝试都顺带清理过期 draft（幂等，重复调用无副作用）
    assert len(fake.calls_of("cancel_expired_drafts")) == 4
    logs = fake.calls_of("log_summary_execution")
    assert len(logs) == 1
    assert logs[0]["status"] == "failed"
    assert logs[0]["categories_updated"] == []
    assert "task-service 不可用" in logs[0]["error_message"]


async def test_window_widens_after_three_failed_days(summary_env):
    """连续三天失败：本次窗口起点前移 3 天，合并失败期间积压的数据。"""
    fake = summary_env.fake
    fake.set_data("get_summary_executions", [_failed_row(19), _failed_row(18), _failed_row(17)])
    _, fake_sleep = _sleep_recorder()

    result = await run_summary("auto", now=_NOW, sleep=fake_sleep)

    assert result.window_since.isoformat() == _SINCE_WIDENED
    assert fake.calls_of("get_tasks_changed_since")[0]["since"] == _SINCE_WIDENED


async def test_window_defaults_to_today(summary_env):
    """未连续失败（仅两条 failed）：窗口起点仍是当天 0:00。"""
    fake = summary_env.fake
    fake.set_data("get_summary_executions", [_failed_row(19), _failed_row(18)])
    _, fake_sleep = _sleep_recorder()

    result = await run_summary("auto", now=_NOW, sleep=fake_sleep)

    assert result.window_since.isoformat() == _SINCE_TODAY
    assert fake.calls_of("get_tasks_changed_since")[0]["since"] == _SINCE_TODAY


async def test_expired_drafts_cancelled_with_run_moment(summary_env):
    """每次运行顺带清理过期 draft：带上执行时刻，清理数进结果。"""
    fake = summary_env.fake
    fake.set_data(
        "cancel_expired_drafts",
        {"cancelled_count": 2, "task_ids": ["task-a", "task-b"]},
    )
    _, fake_sleep = _sleep_recorder()

    result = await run_summary("auto", now=_NOW, sleep=fake_sleep)

    assert result.cancelled_drafts == 2
    calls = fake.calls_of("cancel_expired_drafts")
    assert len(calls) == 1
    assert calls[0]["now"] == _NOW.isoformat()
    # 清理发生在采集之前（同一次尝试内）
    names = [name for name, _ in fake.calls]
    assert names.index("cancel_expired_drafts") < names.index("get_tasks_changed_since")


async def test_execution_log_read_failure_is_controlled(summary_env):
    """读不到执行日志属运行级失败：退避重试后记 failed，不上抛、不静默降级。"""
    fake = summary_env.fake
    fake.set_error("get_summary_executions", McpToolError(500, "task-service 不可用"))
    recorded, fake_sleep = _sleep_recorder()

    # manual 且无法确认当天是否已成功：不能抛 409，也不能返回 500
    result = await run_summary("manual", now=_NOW, sleep=fake_sleep)

    assert recorded == [60.0, 300.0, 1800.0]
    assert result.status == "failed" and result.attempts == 4
    # 窗口无法判定：故障在读取执行日志阶段就暴露，清理与采集都未发生
    assert result.window_since is None
    assert fake.calls_of("cancel_expired_drafts") == []
    assert fake.calls_of("get_tasks_changed_since") == []
    logs = fake.calls_of("log_summary_execution")
    assert len(logs) == 1
    assert logs[0]["status"] == "failed" and logs[0]["trigger_source"] == "manual"
    assert "task-service 不可用" in logs[0]["error_message"]


async def test_trigger_returns_200_when_task_service_unreachable(summary_env, monkeypatch):
    """task-service 不可达时手动触发返回 200/failed（而非 500），body 带原因。"""
    from httpx import ASGITransport, AsyncClient

    from src.main import app
    from src.services import summary_run

    summary_env.fake.set_error(
        "get_summary_executions", McpToolError(500, "task-service 不可用")
    )
    # 退避压成零延迟：真实退避 36 分钟不适合在测试里等
    monkeypatch.setattr(summary_run, "RETRY_DELAYS_SECONDS", (0.0, 0.0, 0.0))

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/api/summary/trigger", json={})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "failed" and body["attempts"] == 4
    assert "task-service 不可用" in body["error_message"]
