# Proposal: tool-service 天气服务

## 目标
- 交付独立 Python FastAPI 天气查询服务（端口 8800），供 agent-service 调用
- D-1 GET /api/weather?city=&date= 端点 + 56501 降级 + /healthz
- ❌7 WEATHER_CACHE_TTL=21600（6h LRU进程内缓存）

## 非目标
- 不接入 Redis 等外部缓存
- 不对接特定的单一天气 API（可替换第三方）
- 不做天气可视化/推送（归前端/Change 6）

## 规约
见 specs/tool-service-weather/spec.md
