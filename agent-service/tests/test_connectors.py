"""connectors HTTP 行为（httpx.MockTransport 全离线）。"""
from typing import Any

import httpx
import pytest

from app.connectors.backend_client import BackendClient
from app.connectors.weather_client import WeatherClient


def wire_backend(handler: Any) -> BackendClient:
    client = BackendClient.__new__(BackendClient)
    client._secret = "itest-secret"  # noqa: SLF001
    client._client = httpx.AsyncClient(  # noqa: SLF001
        transport=httpx.MockTransport(handler), base_url="http://backend.test", timeout=30.0
    )
    return client


async def test_backend_create_task_sends_query_and_secret() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = request.url.path
        seen["family_id"] = request.url.params.get("family_id")
        seen["secret"] = request.headers.get("X-Internal-Secret")
        return httpx.Response(200, json={"code": 0, "data": {"id": 77}})

    client = wire_backend(handler)
    resp = await client.create_task(7, {"title": "t", "source": "AGENT"})
    await client.aclose()
    assert resp["data"]["id"] == 77
    assert seen["method"] == "POST" and seen["path"] == "/api/internal/agent/tasks"
    assert seen["family_id"] == "7" and seen["secret"] == "itest-secret"


async def test_backend_interview_writes_send_family_scope() -> None:
    """B-5/B-7 必须把家庭作用域放进 query，避免仅凭会话 ID 写入。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"code": 0, "data": None})

    client = wire_backend(handler)
    await client.save_extracted_patch(91, {"profile_id": 12}, family_id=7)
    await client.append_session_message(91, "user", "你好", family_id=7)
    await client.aclose()

    assert [request.url.path for request in seen] == [
        "/api/internal/agent/sessions/91/extract-patch",
        "/api/internal/agent/sessions/91/messages",
    ]
    assert all(request.url.params["family_id"] == "7" for request in seen)
    assert all(request.headers["X-Internal-Secret"] == "itest-secret" for request in seen)


async def test_backend_batch_note_cancel_categories() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(f"{request.method} {request.url.path}")
        if request.url.path.endswith("/batch"):
            return httpx.Response(200, json={"code": 0, "data": {"created": 2}})
        if request.url.path.endswith("/notes"):
            calls.append(request.content.decode())  # 捕获真实报文，断言 target_type 已归一
            return httpx.Response(200, json={"code": 0})
        if request.url.path.endswith("/tasks/501"):
            return httpx.Response(200, json={"code": 0, "data": {"id": 501}})
        if request.url.path.endswith("/categories"):
            return httpx.Response(200, json={"code": 0, "data": [{"id": 3, "name": "家务"}]})
        return httpx.Response(500)

    client = wire_backend(handler)
    await client.create_subtasks_batch(7, 501, [{"title": "a", "sort_order": 1}])
    await client.add_note(7, "TASK", 501, "n")
    await client.cancel_task(7, 501)
    cats = await client.get_categories(7)
    await client.aclose()
    assert "PUT /api/internal/agent/tasks/501" in calls  # 回滚软取消走 C-5
    assert '"target_type":"task"' in "".join(calls), "后端 C-8 只认小写 task（大写会被 400 拒）"
    assert cats and cats[0]["name"] == "家务"


async def test_note_target_type_rejects_unknown_value() -> None:
    """非法 target_type 在发出请求前就拒（防契约再次漂移，400 才在生产暴露）。"""
    client = wire_backend(lambda request: httpx.Response(200, json={"code": 0}))

    with pytest.raises(ValueError):
        await client.add_note(7, "bogus", 501, "n")
    with pytest.raises(ValueError):
        await client.add_note(7, "TASKS", 501, "n")
    await client.aclose()


def wire_weather(handler: Any) -> WeatherClient:
    wc = WeatherClient.__new__(WeatherClient)
    wc._client = httpx.AsyncClient(  # noqa: SLF001
        transport=httpx.MockTransport(handler), base_url="http://tool.test", timeout=5.0
    )
    return wc


async def test_weather_ok_and_degrade_to_unknown() -> None:
    def ok(request: httpx.Request) -> httpx.Response:
        assert request.url.params["city"] == "杭州"
        return httpx.Response(200, json={"weather_type": "RAIN"})

    wc = wire_weather(ok)
    assert await wc.fetch_weather_type("杭州", "2026-08-26") == "RAIN"
    await wc.aclose()

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    wc2 = wire_weather(boom)
    assert await wc2.fetch_weather_type("杭州", "2026-08-26") == "unknown"  # D5：降级不抛
    await wc2.aclose()


def test_secret_constant_time_helper() -> None:
    from app.utils.secrets import constant_time_equals

    assert constant_time_equals("a", "a") is True
    assert constant_time_equals("a", "b") is False


async def test_summary_lock_and_learning_endpoints_envelopes() -> None:
    """Change 5 新增 C-19/C-20/C-21/C-23/C-11：Result 信封解包与 query 参数口径。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        path = request.url.path
        if path.endswith("/summary-log/claim"):
            return httpx.Response(200, json={"code": 0, "data": {"acquired": False, "log_id": None}})
        if path.endswith("/summary-log/901"):
            return httpx.Response(200, json={"code": 0, "data": None})
        if path.endswith("/change-summary"):
            return httpx.Response(200, json={"code": 0, "data": {"new_version": 3}})
        if path.endswith("/active-families"):
            return httpx.Response(200, json={"code": 0, "data": [1, 5, 9]})
        if path == "/api/internal/agent/templates":
            return httpx.Response(
                200, json={"code": 0, "data": [{"id": 77, "category_id": 3, "type": "decomposition"}]}
            )
        return httpx.Response(500)

    client = wire_backend(handler)
    claim = await client.claim_summary_lock(7, "manual", force=False)
    finish = await client.finish_summary_log(7, 901, "success", [3], [])
    append = await client.append_change_summary(7, 77, "⚠️ 中文警告")
    families = await client.get_active_family_ids()
    templates = await client.get_decomposition_templates(7)
    await client.aclose()

    assert claim == {"acquired": False, "log_id": None}
    assert finish == {"code": 0, "data": None}
    assert append["data"]["new_version"] == 3
    assert families == [1, 5, 9]
    assert templates and templates[0]["id"] == 77

    put_req = next(r for r in seen if r.method == "PUT")
    assert put_req.url.path == "/api/internal/agent/summary-log/901"
    assert put_req.url.params["family_id"] == "7"
    claim_req = next(r for r in seen if r.url.path.endswith("/summary-log/claim"))
    assert claim_req.url.params["force"] == "false"
    tmpl_req = next(r for r in seen if r.url.path == "/api/internal/agent/templates")
    assert tmpl_req.url.params["type"] == "decomposition" and tmpl_req.url.params["is_active"] == "true"
