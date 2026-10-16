"""tool-service 天气客户端（Change 3 对端，5s 超时降级 unknown）。"""
import httpx

from ..settings import get_settings


class WeatherClient:
    """查询城市天气类型；失败/超时返回 unknown，不抛（拆解推理参考，非主链路）。"""

    def __init__(self) -> None:
        self._client = httpx.AsyncClient(base_url=get_settings().tool_service_url, timeout=5.0)

    async def fetch_weather_type(self, city: str, date: str) -> str:
        """GET /api/weather?city=&date= → weather_type；异常降级 unknown。"""
        try:
            resp = await self._client.get("/api/weather", params={"city": city, "date": date})
            resp.raise_for_status()
            return str(resp.json().get("weather_type", "unknown"))
        except Exception:  # noqa: BLE001
            return "unknown"

    async def aclose(self) -> None:
        await self._client.aclose()
