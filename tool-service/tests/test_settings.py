"""settings.py 环境变量绑定与默认值（Task 1 Step 1）。"""
import pytest

from app.settings import get_settings


@pytest.fixture(autouse=True)
def _clean() -> None:
    """每用例后复位 lru_cache。"""
    yield
    get_settings.cache_clear()


def test_missing_api_key_raises_validation_error(monkeypatch) -> None:
    """WEATHER_API_KEY 未设置 → ValidationError。"""
    monkeypatch.delenv("WEATHER_API_KEY", raising=False)
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        get_settings()


def test_wttl_default_21600_and_custom(monkeypatch) -> None:
    """❌7：未设 WEATHER_CACHE_TTL → 默认 21600；设为 7200 → 解析正确。"""
    monkeypatch.setenv("WEATHER_API_KEY", "dummy-key")
    monkeypatch.delenv("WEATHER_CACHE_TTL", raising=False)
    s = get_settings()
    assert s.weather_cache_ttl == 21600
    monkeypatch.setenv("WEATHER_CACHE_TTL", "7200")
    get_settings.cache_clear()
    s2 = get_settings()
    assert s2.weather_cache_ttl == 7200
