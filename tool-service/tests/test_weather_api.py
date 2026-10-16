"""D-1 天气端点 7 用例（respx mock 第三方）。"""
from typing import Any

import httpx
from fastapi import FastAPI
from starlette.testclient import TestClient

SUNNY_RESP = {
    "forecast": {"forecastday": [{"day": {"condition": {"text": "Sunny"}}}]}
}


def _async_client(app: FastAPI) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


async def test_first_call_third_party_returns_sunny(
    app_fixture: FastAPI, respx_mock: Any
) -> None:
    """未命中缓存 → 第三方返回 sunny → cached=false。"""
    route = respx_mock.get("https://api.weatherapi.com/v1/forecast.json").respond(
        json=SUNNY_RESP
    )
    async with _async_client(app_fixture) as client:
        resp = await client.get("/api/weather?city=beijing&date=2026-08-26")
    data = resp.json()
    assert resp.status_code == 200
    assert data["weather_type"] == "sunny"
    assert data["cached"] is False and data["source"] == "3rd-party"
    assert route.called


async def test_second_call_hits_cache_one_third_party_call(
    app_fixture: FastAPI, respx_mock: Any
) -> None:
    """相同 (city,date) 二次请求 → cached=true，第三方仅调一次。"""
    route = respx_mock.get("https://api.weatherapi.com/v1/forecast.json").respond(
        json=SUNNY_RESP
    )
    async with _async_client(app_fixture) as client:
        r1 = await client.get("/api/weather?city=beijing&date=2026-08-26")
        r2 = await client.get("/api/weather?city=beijing&date=2026-08-26")
    assert r1.json()["cached"] is False
    assert r2.json()["cached"] is True
    assert route.call_count == 1


async def test_third_party_failure_degrade_56501(
    app_fixture: FastAPI, respx_mock: Any
) -> None:
    """第三方 ConnectError → weather_type=unknown, code=56501, 不抛 500。"""
    respx_mock.get("https://api.weatherapi.com/v1/forecast.json").mock(
        side_effect=httpx.ConnectError("down")
    )
    async with _async_client(app_fixture) as client:
        resp = await client.get("/api/weather?city=beijing&date=2026-08-26")
    data = resp.json()
    assert resp.status_code == 200 and data["code"] == 56501
    assert data["weather_type"] == "unknown"
    assert "暂时不可用" in data["msg"]


def test_empty_city_422(app_fixture: FastAPI) -> None:
    """city 空字符串 → 422。"""
    resp = TestClient(app_fixture).get("/api/weather?city=&date=2026-08-26")
    assert resp.status_code == 422


def test_invalid_date_422(app_fixture: FastAPI) -> None:
    """date 非 YYYY-MM-DD → 422。"""
    resp = TestClient(app_fixture).get("/api/weather?city=beijing&date=not-a-date")
    assert resp.status_code == 422


async def test_healthz_returns_cache_size(app_fixture: FastAPI) -> None:
    """GET /healthz → 200 {status:ok, cache_size:N}。"""
    async with _async_client(app_fixture) as client:
        resp = await client.get("/healthz")
    data = resp.json()
    assert resp.status_code == 200 and data["status"] == "ok"
    assert isinstance(data["cache_size"], int)


async def test_third_party_http_error_degrades(
    app_fixture: FastAPI, respx_mock: Any
) -> None:
    """第三方返回 422（如超出预测范围）→ 56501 降级。"""
    respx_mock.get("https://api.weatherapi.com/v1/forecast.json").respond(422)
    async with _async_client(app_fixture) as client:
        resp = await client.get("/api/weather?city=beijing&date=2027-08-27")
    data = resp.json()
    assert data["code"] == 56501 and data["weather_type"] == "unknown"
