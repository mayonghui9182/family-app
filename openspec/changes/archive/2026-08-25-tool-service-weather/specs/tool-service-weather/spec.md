# Spec: tool-service 天气服务

## ADDED Requirements

### D-1 GET /api/weather
- 请求: ?city=北京&date=2026-08-26
- 响应 200: {weather_type, cached, source, code:0}
- 降级 200: {weather_type:"unknown", code:56501, msg:"天气服务暂时不可用，日常模板的天气条件暂时跳过"}
- 校验 city 非空、date YYYY-MM-DD → 422

### ❌7 WEATHER_CACHE_TTL
- 环境变量秒单位，默认 21600（=6 小时）
- LRU maxsize=1024

### /healthz
- GET → 200 {"status":"ok","cache_size":N}
- 不依赖第三方
