"""D-1 GET /api/weather 端点：城市+日期查询天气，6h LRU 缓存。"""
from datetime import date

from fastapi import APIRouter, Query

from ..models.weather_schemas import WeatherResponse
from ..services.weather_service import weather_service

router = APIRouter(prefix="/api", tags=["weather"])


@router.get("/weather", response_model=WeatherResponse, summary="查询天气")
async def get_weather(
    city: str = Query(..., min_length=1, description="城市名，如北京"),
    date_: date = Query(..., alias="date", description="YYYY-MM-DD 日期"),
) -> WeatherResponse:
    return await weather_service.fetch(city, date_.isoformat())
