"""天气查询服务：第三方 API 调用 + TTL LRU 缓存 + 56501 降级。"""
import httpx

from ..models.weather_schemas import WeatherResponse, WeatherType
from ..settings import get_settings
from .lru_cache import TTLLRUCache


class WeatherService:
    """第三方天气查询服务；进程内实例单例。"""

    def __init__(self) -> None:
        settings = get_settings()
        self._cache: TTLLRUCache[str, WeatherResponse] = TTLLRUCache(ttl_seconds=settings.weather_cache_ttl)
        self._settings = settings

    async def fetch(self, city: str, date_str: str) -> WeatherResponse:
        """查询天气：命中缓存返回 cached=True，否则调第三方，失败降级 unknown。"""
        key = f"{city.lower()}:{date_str}"
        cached = self._cache.get(key)
        if cached is not None:
            return cached.model_copy(update={"cached": True})
        result = await self._call_third_party(city, date_str)
        if result.code == 0:
            self._cache.set(key, result)
        return result

    async def _call_third_party(self, city: str, date_str: str) -> WeatherResponse:
        try:
            async with httpx.AsyncClient(timeout=self._settings.weather_timeout_sec) as client:
                resp = await client.get(
                    f"{self._settings.weather_base_url}/forecast.json",
                    params={
                        "key": self._settings.weather_api_key.get_secret_value(),
                        "q": city,
                        "dt": date_str,
                        "days": "1",
                    },
                )
            resp.raise_for_status()
            data = resp.json()
            condition = (
                data.get("forecast", {})
                .get("forecastday", [{}])[0]
                .get("day", {})
                .get("condition", {})
                .get("text", "")
            )
            return WeatherResponse(weather_type=self._map_type(condition), source="3rd-party")
        except Exception:  # noqa: BLE001
            return WeatherResponse(
                weather_type=WeatherType.UNKNOWN,
                cached=False,
                source="degraded",
                code=56501,
                msg="天气服务暂时不可用，日常模板的天气条件暂时跳过",
            )

    @staticmethod
    def _map_type(text: str) -> WeatherType:
        t = text.lower()
        if any(w in t for w in ("晴", "sunny", "clear")):
            return WeatherType.SUNNY
        if any(w in t for w in ("雨", "rain", "shower", "drizzle")):
            return WeatherType.RAIN
        if any(w in t for w in ("雪", "snow", "blizzard")):
            return WeatherType.SNOW
        if any(w in t for w in ("雾", "fog", "mist", "haze", "sand")):
            return WeatherType.CLOUDY
        return WeatherType.CLOUDY


weather_service = WeatherService()
