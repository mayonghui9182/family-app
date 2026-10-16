"""D-1 天气请求/响应模型 + weather_type 枚举。"""
from enum import StrEnum

from pydantic import BaseModel


class WeatherType(StrEnum):
    """天气类型枚举（英文值保留，为业务判断用）。"""
    SUNNY = "sunny"
    RAIN = "rain"
    SNOW = "snow"
    CLOUDY = "cloudy"
    UNKNOWN = "unknown"


class WeatherResponse(BaseModel):
    """D-1 GET /api/weather 统一响应结构。"""
    weather_type: WeatherType
    cached: bool = False
    source: str = "3rd-party"
    code: int = 0
    msg: str | None = None
