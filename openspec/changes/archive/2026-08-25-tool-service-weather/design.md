# Design: tool-service 天气服务

## D1：进程内 LRU 缓存（OrderedDict + threading.Lock）
- maxsize=1024，(city,date) 元组 key
- TTL 秒级（默认 21600=6h，❌7补项）
- get 时检查过期自动清除；淘汰 LRU 末尾（最老）
- 并发安全：Lock 保护 get/set

## D2：第三方 API 降级 (56501)
- httpx.AsyncClient 10s 超时
- ConnectError/TimeoutException/http非200/JSON解析失败 → weather_type=unknown, code=56501
- 绝不抛 500 给调用方

## D3：weather_type 枚举
- sunny / rain / snow / cloudy / unknown
- 第三方原始值映射到枚举

## D4：/healthz 不依赖第三方
- 返回 {status:ok, cache_size:N}，第三方挂了也 200

## D5：WEATHER_API_KEY 脱敏
- Pydantic SecretStr，日志不落明文
