# Check List: tool-service-weather

| # | 验证项 | 结果 |
|---|---|---|
| 1 | WEATHER_CACHE_TTL 默认=21600 ❌7 生效 | ✅ test_settings.py：未设→默认 21600；设为 7200→parsed=7200 |
| 2 | 第三方失败 100% 降级（weather_type=unknown + 56501 中文） | ✅ test_third_party_failure_degrade_56501（ConnectError→56501 中文 msg）；test_third_party_http_error_degrades（HTTP 422→56501）；均返回 200 不抛 500 |
| 3 | LRU 过期/淘汰正确（test_lru_cache 4 例） | ✅ 4 例全绿：maxsize=2 写 3 淘汰最早 / freeze_time 7h 过期 / TTL=0 立即过期 / 100 线程 500 次读写无锁死 |
| 4 | ruff 0 错 / mypy strict 0 错 | ✅ ruff 0 错（120 line，routes B008 per-file-ignore FastAPI 惯用法）/ mypy strict 0 错（10 源文件） |
| 5 | 13 tests 全绿 / 覆盖率 ≥ 80% | ✅ 13/13 pass / 覆盖率 94.17%（门禁 80%） |
| 6 | WEATHER_API_KEY 日志不落明文 | ✅ Pydantic SecretStr 强类型；.get_secret_value() 仅用于 httpx 请求传参；loguru 未配置时 pydantic 默认 repr 脱敏（Change 4 同款机制） |
| 7 | Docker build 成功 + /healthz 200 | ✅ docker build BUILD_EXIT=0；容器 /healthz 返回 {"status":"ok","cache_size":0}（Docker 29.7.2，python:3.12-slim，appuser，HEALTHCHECK） |

验证时间：2026-08-26。Java 后端基线保持 152/0/0。
