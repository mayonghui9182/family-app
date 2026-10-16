"""统一响应封套与异常处理器测试（tasks.md 2.4）。

覆盖任务要求的三种响应形态（成功 / 校验失败 / 不存在），并补齐非法状态流转、
409 冲突、框架层 HTTP 异常、未捕获异常四条兜底路径，以及真实 app 的接线验证。
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from pydantic import BaseModel

from src.main import app as main_app
from src.services.envelope import (
    BadRequest,
    Conflict,
    InvalidStatusTransition,
    NotFound,
    ResultCode,
    ok,
    register_exception_handlers,
)


class ProbeBody(BaseModel):
    """探针请求体：title 必填，用于触发 FastAPI 的参数校验异常。"""

    title: str


def build_probe_app() -> FastAPI:
    """构造只含封套探针路由的最小应用，不触库、不依赖任何业务模块。"""
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/probe/ok")
    async def probe_ok() -> dict:
        """成功形态：空列表也应逐字等于 spec 断言的封套。"""
        return ok([])

    @app.post("/probe/model")
    async def probe_model(body: ProbeBody) -> dict:
        """参数校验通过时正常回显。"""
        return ok({"title": body.title})

    @app.get("/probe/bad-title")
    async def probe_bad_title() -> dict:
        """业务级 400：标题为空。"""
        raise BadRequest(ResultCode.BAD_REQUEST_TITLE_EMPTY)

    @app.get("/probe/not-found")
    async def probe_not_found() -> dict:
        """业务级 404：任务不存在。"""
        raise NotFound(ResultCode.TASK_NOT_FOUND)

    @app.get("/probe/transition")
    async def probe_transition() -> dict:
        """业务级 400：非法状态流转，自定义中文提示。"""
        raise InvalidStatusTransition("draft 不能直接切到 completed")

    @app.get("/probe/conflict")
    async def probe_conflict() -> dict:
        """业务级 409：当天已总结。"""
        raise Conflict(ResultCode.SUMMARY_ALREADY_RAN)

    @app.get("/probe/http-404")
    async def probe_http_404() -> dict:
        """框架层 HTTPException 也要被封套包装。"""
        raise HTTPException(status_code=404)

    @app.get("/probe/boom")
    async def probe_boom() -> dict:
        """未捕获异常：对外只暴露 500 封套，不泄漏内部信息。"""
        raise RuntimeError("内部炸了")

    return app


def test_ok_envelope_matches_spec_body() -> None:
    """成功封套：code=0、msg=success、data 原样返回（spec task-mgmt 逐字断言）。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/ok")

    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": []}


def test_validation_failure_returns_400_envelope() -> None:
    """校验失败形态：缺 title → 400 + 非 0 码 + 提示点名 title + 字段明细。"""
    with TestClient(build_probe_app()) as client:
        resp = client.post("/probe/model", json={"description": "没有标题"})

    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == ResultCode.BAD_REQUEST_VALIDATION.code
    assert body["code"] != 0
    assert "title" in body["msg"]
    assert isinstance(body["data"], list) and body["data"]
    assert body["data"][0]["loc"][-1] == "title"


def test_validation_passes_through_when_payload_valid() -> None:
    """同一探针路由在校验通过时返回成功封套，证明处理器没有误伤正常路径。"""
    with TestClient(build_probe_app()) as client:
        resp = client.post("/probe/model", json={"title": "写季度总结"})

    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "msg": "success", "data": {"title": "写季度总结"}}


def test_business_bad_request_uses_chinese_msg() -> None:
    """业务 400：中文提示 + data 为 None（对齐 family-app Result.error 约定）。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/bad-title")

    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == 40001
    assert body["msg"] == "任务标题不能为空"
    assert body["data"] is None


def test_not_found_returns_404_with_nonzero_code() -> None:
    """不存在形态：404 + 非 0 码 + 资源级中文提示（spec「Unknown task identifier」）。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/not-found")

    assert resp.status_code == 404
    body = resp.json()
    assert body["code"] == ResultCode.TASK_NOT_FOUND.code
    assert body["code"] != 0
    assert body["msg"] == "任务不存在"


def test_invalid_transition_returns_400_with_transition_code() -> None:
    """非法流转：400 + 40014，自定义 msg 覆盖默认文案。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/transition")

    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == ResultCode.TASK_INVALID_TRANSITION.code
    assert body["msg"] == "draft 不能直接切到 completed"


def test_conflict_returns_409_and_asks_for_force() -> None:
    """409 冲突：当天已总结，提示需带 force（spec template-learning「Re-running」）。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/conflict")

    assert resp.status_code == 409
    body = resp.json()
    assert body["code"] == ResultCode.SUMMARY_ALREADY_RAN.code
    assert "force" in body["msg"]


def test_starlette_http_exception_is_wrapped() -> None:
    """框架层 HTTPException(404) 也走封套，兜底码为 40400。"""
    with TestClient(build_probe_app()) as client:
        resp = client.get("/probe/http-404")

    assert resp.status_code == 404
    assert resp.json()["code"] == ResultCode.NOT_FOUND_GENERIC.code


def test_unhandled_exception_returns_500_envelope_without_leak() -> None:
    """未捕获异常：500 封套，异常文案不得出现在响应体中。"""
    with TestClient(build_probe_app(), raise_server_exceptions=False) as client:
        resp = client.get("/probe/boom")

    assert resp.status_code == 500
    body = resp.json()
    assert body["code"] == ResultCode.INTERNAL_ERROR.code
    assert body["msg"] == "服务器内部错误"
    assert "内部炸了" not in resp.text


def test_unknown_route_on_real_app_returns_envelope() -> None:
    """真实 src.main.app 已接线：未匹配路由返回 404 封套而非 FastAPI 默认裸 JSON。"""
    with TestClient(main_app) as client:
        resp = client.get("/api/definitely-not-exists")

    assert resp.status_code == 404
    body = resp.json()
    assert set(body) == {"code", "msg", "data"}
    assert body["code"] != 0
