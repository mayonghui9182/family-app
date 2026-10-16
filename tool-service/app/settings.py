"""PydanticSettings 强类型配置（WEATHER_API_KEY 必填 SecretStr；❌7 WEATHER_CACHE_TTL=21600）。"""
from functools import lru_cache

from pydantic import HttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """tool-service 运行配置。敏感字段 SecretStr 自动脱敏。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 第三方天气 API
    weather_api_key: SecretStr
    weather_cache_ttl: int = 21600  # ❌7 补项 6 小时
    weather_base_url: HttpUrl = HttpUrl("https://api.weatherapi.com/v1")
    weather_timeout_sec: int = 10
    port: int = 8800


@lru_cache
def get_settings() -> Settings:
    """进程级单例。"""
    return Settings()  # type: ignore[call-arg]  # 运行时从 env 读取
