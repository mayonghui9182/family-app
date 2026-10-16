# Change 3: tool-service 天气服务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付独立 Python FastAPI 天气服务：D-1 GET /api/weather?city=&date= 端点 + 进程内 6 小时 LRU 缓存（❌7 补项 WEATHER_CACHE_TTL=21600）+ 第三方 API 失败降级（返回 weather_type=unknown + 56501 中文 msg）+ /healthz 健康检查 + Dockerfile（python:3.12-slim 非 root）。

**Architecture:** 单服务 2 层：routes/ 处理 HTTP + 入参校验；services/ 处理第三方调用 + LRU 缓存。缓存用 collections.OrderedDict + threading.Lock（进程内 6h TTL，单实例），避免引入 Redis 等外部依赖。第三方 API 调用 httpx.AsyncClient 10 秒超时。降级触发时不抛异常，给业务方留 decision 空间（weather_type=unknown 时跳过 weather 条件匹配）。

**Tech Stack:** Python 3.12 / FastAPI 0.110+ / Pydantic v2 / httpx 0.27+ / uvicorn[standard] / pytest-asyncio / pytest-cov 80% / ruff 120 / mypy strict / Docker python:3.12-slim

## Global Constraints
- ❌7 补项严格：WEATHER_CACHE_TTL 环境变量秒单位，默认值=21600（6 小时）；不接受其它单位
- 第三方调用失败 100% 降级：永远不抛 500 给调用方；总是返回 {weather_type, source, cached, msg?} 结构
- 缓存 TTL 精确：(now - stored_at) > TTL 秒，LRU 淘汰 maxsize=1024（(city, date) 元组 key）
- Python 工程质量：ruff check 0 错；mypy strict 0 错；pytest --cov-fail-under=80
- 所有消息/注释/Prompt 全中文（weather_type 英文枚举值不变，为业务判断用）
- 敏感：WEATHER_API_KEY 仅从环境变量读，不得写入 stdout/loguru INFO 级日志（loguru DEBUG 级也不得输出）；HTTP 请求头 Auth 脱敏打码
- /healthz 不依赖第三方：纯返回 {"status":"ok","cache_size":N}，第三方挂也 200

---

## File Structure
| 文件 | 操作 | 职责 |
|---|---|---|
| tool-service/pyproject.toml | Create | ruff 120 / mypy strict / pytest-cov 80 / 依赖版本（fastapi, httpx, pydantic-settings, uvicorn, pytest-asyncio） |
| tool-service/.env.example | Create | WEATHER_API_KEY / WEATHER_CACHE_TTL=21600 注释说明 ❌7 |
| tool-service/Dockerfile | Create | python:3.12-slim → USER appuser → EXPOSE 8800 → HEALTHCHECK CMD |
| tool-service/app/main.py | Create | FastAPI 挂载 + exception_handler 兜底 + /healthz |
| tool-service/app/settings.py | Create | PydanticSettings 强类型（WEATHER_API_KEY 必填；WEATHER_CACHE_TTL: int=21600；其它） |
| tool-service/app/routes/weather_routes.py | Create | D-1 GET /api/weather + 入参校验 + 56501 降级 |
| tool-service/app/services/weather_service.py | Create | 第三方 httpx 调用 + LRU 缓存 get/set |
| tool-service/app/services/lru_cache.py | Create | OrderedDict + Lock + TTL 过期 封装类 |
| tool-service/app/models/weather_schemas.py | Create | WeatherRequest / WeatherResponse Pydantic |
| tool-service/tests/conftest.py | Create | respx mock 第三方 API / mock 时间 fixtures |
| tool-service/tests/test_lru_cache.py | Create | LRU 容量/TLL/并发 4 用例 |
| tool-service/tests/test_weather_api.py | Create | 命中缓存/未命中/第三方失败/未知城市/date 非法 7 用例 |
| tool-service/tests/test_settings.py | Create | 环境变量缺失抛 ValidationError / TTL 默认值 2 用例 |

---

### Task 1: 工程脚手架（pyproject + settings + Dockerfile）

- [ ] **Step 1: 写失败测试 test_settings.py（2 用例）**
用例1：WEATHER_API_KEY 未设置 → Pydantic ValidationError
用例2：WEATHER_CACHE_TTL 未设置 → 默认值=21600；设置="7200" → parsed=7200
Run: `cd tool-service ; python -m pytest tests/test_settings.py -v` Expected: FAIL（settings.py 不存在）

- [ ] **Step 2: pyproject.toml 工程标准**
依赖：fastapi>=0.110, httpx>=0.27, pydantic-settings>=2.2, uvicorn[standard]>=0.30
dev依赖：pytest>=8, pytest-asyncio>=0.23, pytest-cov>=5, respx>=0.21, ruff>=0.4, mypy>=1.10
[tool.ruff] line-length=120 target-version="py312"
[tool.mypy] strict=true
[pytest] addopts="--cov=app --cov-report=term-missing --cov-fail-under=80 -p no:cacheprovider" asyncio_mode=auto

- [ ] **Step 3: settings.py PydanticSettings 强类型**
class Settings(BaseSettings):
    WEATHER_API_KEY: SecretStr  # SecretStr → 序列化默认脱敏
    WEATHER_CACHE_TTL: int = 21600  # ❌7 补项 6h
    WEATHER_BASE_URL: HttpUrl = HttpUrl("https://api.weatherapi.com/v1")  # 可替换任意第三方
    WEATHER_TIMEOUT_SEC: int = 10
    PORT: int = 8800
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
.settings = Settings()  # 单例

- [ ] **Step 4: .env.example + Dockerfile 非 root**
.env.example 三行（注释 ❌7）：
# 第三方天气 API Key（必填；如和风天气/OpenWeatherMap；默认使用 weatherapi.com 格式）
WEATHER_API_KEY=your_weather_api_key_here
# ❌7 补项：天气缓存 TTL 秒数，默认 21600 秒 = 6 小时
WEATHER_CACHE_TTL=21600
Dockerfile:
FROM python:3.12-slim
WORKDIR /app
RUN useradd -m -u 1000 appuser
COPY --chown=appuser:appuser pyproject.toml ./
RUN pip install --no-cache-dir . && pip install uvicorn[standard]
COPY --chown=appuser:appuser app ./app
USER appuser
EXPOSE 8800
HEALTHCHECK CMD python -c "import urllib.request,sys; r=urllib.request.urlopen('http://localhost:8800/healthz',timeout=2); sys.exit(0 if r.status==200 else 1)" --interval=10s --timeout=3s --retries=5
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]

- [ ] **Step 5: Run test_settings.py Expected: PASS 2/2**
- [ ] **Step 6: Commit feat(change3): 工程脚手架 + PydanticSettings + Dockerfile**

---

### Task 2: LRU 缓存服务 + 天气 Service（第三方调用 + 降级）

- [ ] **Step 1: 写失败测试 test_lru_cache.py（4 用例）**
用例1：maxsize=2，写入3个 → 最早被淘汰（LRU 顺序）
用例2：写入后 freeze_time 7 小时（>TTL 6h）→ get 返回 None（过期）
用例3：并发 100 线程读写 → 无 KeyError/死锁（threading.Lock 生效）
用例4：TTL 0 → 每次都过期（边界）
Run: `python -m pytest tests/test_lru_cache.py -v` Expected: FAIL（LRU 类不存在）

- [ ] **Step 2: lru_cache.py 实现（OrderedDict + Lock + TTL）**
class TTLLRUCache[K, V]:
    def __init__(self, maxsize: int = 1024, ttl_seconds: int = 21600):
        self._data: OrderedDict[K, tuple[V, float]] = OrderedDict()  # (value, stored_at_timestamp)
        self._lock = threading.Lock()
        self.maxsize = maxsize
        self.ttl = ttl_seconds
    def get(self, key: K) -> V | None:
        with self._lock:
            item = self._data.get(key)
            if not item: return None
            value, stored_at = item
            if time.time() - stored_at > self.ttl:
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return value
    def set(self, key: K, value: V) -> None:
        with self._lock:
            if key in self._data:
                self._data.move_to_end(key)
            elif len(self._data) >= self.maxsize:
                self._data.popitem(last=False)
            self._data[key] = (value, time.time())

- [ ] **Step 3: 写失败测试 test_weather_api.py（7 用例，respx mock）**
用例1：未命中缓存 + 第三方返回 sunny → weather_type=sunny, cached=false, source="3rd-party"
用例2：相同 (city, date) 二次请求 → cached=true, 第三方未被调用（respx 断言 call_count=1）
用例3：第三方 ConnectError/5xx → weather_type=unknown, code=56501, msg="天气服务暂时不可用，日常模板的天气条件暂时跳过"（降级不抛 500）
用例4：城市空字符串 → 422 ValidationError
用例5：date="not-a-date" → 422 中文 msg "日期格式错误，请使用 YYYY-MM-DD"
用例6：date=today + 365 天（超出第三方预测）→ weather_type=unknown, msg="超出预测范围，最多支持未来 14 天"
用例7：/healthz → 200 {"status":"ok","cache_size":0}
Run: `python -m pytest tests/test_weather_api.py -v` Expected: FAIL（routes/services 不存在）

- [ ] **Step 4: weather_service.py 业务逻辑**
第三方请求：httpx.AsyncClient.get(f"{settings.WEATHER_BASE_URL}/forecast.json", params={"key": settings.WEATHER_API_KEY.get_secret_value(), "q": city, "dt": date_str, "days":1}, timeout=settings.WEATHER_TIMEOUT_SEC)
第三方响应 → weather_type 映射（晴天=sunny / 雨=rain / 雪=snow / 多云=cloudy / 雾雾=cloudy / 其它=cloudy；极端天气 thunderstorm → rainy + severity=high）
缓存 key：(city.lower(), date.isoformat()) 元组
降级分支（任一条触发走 unknown）：
  except httpx.ConnectError → code=56501
  except httpx.TimeoutException → code=56501
  response.status_code != 200 → code=56501
  JSONDecodeError → code=56501
永远 return WeatherResponse(weather_type=unknown, cached=False, code=56501, msg=中文) 不抛 HTTPException 给上层

- [ ] **Step 5: weather_routes.py D-1 GET /api/weather**
@app.get("/api/weather", response_model=WeatherResponse, summary="查询天气", description="按城市+日期查天气，6h LRU缓存 ❌7")
async def get_weather(city: str = Query(..., min_length=1, description="城市名，如北京"),
                      date_: date = Query(..., alias="date", description="YYYY-MM-DD 日期")):
    key = (city.lower(), date_.isoformat())
    cached = weather_cache.get(key)
    if cached: return cached.model_copy(update={"cached": True})
    result = await weather_service.fetch_third_party(city, date_)  # 内部降级绝不会抛异常
    weather_cache.set(key, result)
    return result

- [ ] **Step 6: main.py FastAPI 挂载 + /healthz**
@app.get("/healthz", tags=["系统"], summary="健康检查")
def healthz() -> dict:
    return {"status": "ok", "cache_size": len(weather_cache._data)}  # 不依赖第三方

- [ ] **Step 7: Run pytest 全部 13 用例 Expected: PASS**
  （4 LRU + 2 settings + 7 weather_api）
- [ ] **Step 8: ruff/mypy 验证**
  Run: `ruff check app tests --fix` → 0 errors
  Run: `mypy app tests` → Success: no issues found
- [ ] **Step 9: 覆盖率检查 Expected: ≥ 80%**
- [ ] **Step 10: Commit feat(change3): LRU缓存TTl=21600 ❌7 + 天气D-1端点 + 56501降级**

---

### Task 3: 端到端本地验证 + Docker build

- [ ] **Step 1: 本地 dev server 启动 + curl 验证**
  Run: `cd tool-service ; WEATHER_API_KEY=dummy uvicorn app.main:app --reload --port 8800`
  curl "http://localhost:8800/healthz" → {"status":"ok","cache_size":0}
  curl "http://localhost:8800/api/weather?city=北京&date=$(date +%F)" → 因 dummy key 走降级，返回 weather_type=unknown, code=56501, msg=中文（符合预期，不抛 500）

- [ ] **Step 2: Docker build 成功**
  Run: `cd tool-service ; docker build -t family-app/tool-service:local .`
  Expected: Successfully built ...
  Run: `docker run -e WEATHER_API_KEY=dummy -p 8800:8800 --rm family-app/tool-service:local` → curl healthz 200

- [ ] **Step 3: 人工 check_list 7 项**
  [x] WEATHER_CACHE_TTL 默认=21600 生效 ❌7
  [x] 第三方失败永远不抛 500（weather_type=unknown + 56501 中文）
  [x] LRU 过期 + 淘汰正确（4 测试全绿）
  [x] ruff 0 错 + mypy strict 0 错
  [x] pytest 覆盖率 ≥ 80%
  [x] WEATHER_API_KEY 未输出到日志（respx mock 断言 Authorization 打码）
  [x] Docker build 成功 + /healthz 200

- [ ] **Step 4: Commit feat(change3): 端到端验证通过 + Dockerfile build**

---

## Self-Review
- [x] Spec 覆盖：§3.5 D-1 端点 + ❌7 WEATHER_CACHE_TTL=21600 + 56501 降级
- [x] 无占位符：TTL 值/cache 容量/超时时间/错误码值 全具体
- [x] 类型一致：weather_cache 名称 Task 全程统一；WeatherResponse 字段 cached/code/weather_type 全部匹配
- [x] 测试：13 用例覆盖正/反/并发/边界/过期/降级

---

## 下一步
启动 OpenSpec：创建 openspec/changes/2026-08-25-tool-service-weather/ 按 Task 1→2→3 顺序实施。注意：与 Change 1 并行，不互相依赖。

（Change 3 Plan 结束）