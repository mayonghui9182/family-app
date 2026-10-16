"""共享 fixtures：模块级 env + 单例复位。"""
import os

os.environ.setdefault("WEATHER_API_KEY", "dummy-key-for-tests")

import pytest
from fastapi import FastAPI

from app.main import create_app
from app.services.weather_service import weather_service
from app.settings import get_settings


@pytest.fixture(autouse=True)
def _reset_singletons() -> None:
    """每个测试前清理 settings 缓存 和 天气 LRU 缓存。"""
    get_settings.cache_clear()
    from app.models.weather_schemas import WeatherResponse
    from app.services.lru_cache import TTLLRUCache
    weather_service._cache = TTLLRUCache[str, WeatherResponse](maxsize=1024, ttl_seconds=21600)  # noqa: SLF001


@pytest.fixture
def app_fixture(monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setenv("WEATHER_API_KEY", "dummy-key")
    get_settings.cache_clear()
    return create_app()
