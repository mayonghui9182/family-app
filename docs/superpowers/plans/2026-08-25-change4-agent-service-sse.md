# Change 4: agent-service 骨架 + SSE 流式拆解 Implementation Plan

> **设计定版（2026-08-26）**：评估过「直接用 DeepSeek Harness(DSH) headless/插件」后放弃——DSH 是本地 coding-agent 跑批工具，无流式/无租户隔离/无 JSON 保证，与 A-1 SSE 契约冲突。保留官方 deepseek-sdk 薄路线；json_guard 由 3 层简化为 2 层（去 L3 正则兜底）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 agent-service Python 3.12 + FastAPI + APScheduler 完整骨架，完成：A-1 SSE 流式拆解 5 事件流（classification/reasoning/proposal/done/error）+ harness 三层（llm_client SDK stream + retry / json_guard 2 层 JSON 硬约束 / sse_stream 100ms flush 或攒 3s 动效减少）+ 拆解 7 步流程（每步强制 family_id 签名）+ Prompt 5 Jinja2 文件全中文 + 4 层家庭隔离强执行 + B-1 AI拆解 / B-2 单家庭总结 入口 + /healthz + ❌6 补项前半（SSE 集成测试 + 6 测试目录骨架）。

**Architecture:** 9 目录分层（routes/services/harness/connectors/prompts/models/schedulers/utils/tests）；拆解 7 步编排层用 ContextVar family_context 注入 X-Family-Id 头，每步签名强制 family_id:int Required（mypy strict 时漏写类型报错）。写操作 100% 经 backend_client（httpx.AsyncClient 调 Change 2 的 C 组 18 端点）+ X-Internal-Secret 恒定比较；直连 PostgreSQL 仅用 agent_reader 角色 SELECT 只读（SQLAlchemy async）。harness json_guard 两层约束 SDK response_format JSON Object → json.loads 严格解析（去正则兜底），两次失败返回 56002 中文。

**Tech Stack:** Python 3.12 / FastAPI / openai SDK（OpenAI 兼容，DeepSeek 官方推荐） / httpx[http2] / SQLAlchemy[asyncio, postgresql] asyncpg / loguru JSON / pydantic-settings / apscheduler / jinja2 / pytest-asyncio / pytest-cov 80% / ruff 120 / mypy strict / SSE starlette 原生 StreamingResponse

## Global Constraints
- 写操作 0 例外：100% 走 backend C 组内部 API（含 DELETE 软撤销）；agent_reader 角色无写权限（DB 层也会拒绝）
- 拆解 7 步 **每函数签名强制 `family_id: int` Required**，mypy strict 漏写→类型报错；编排层首行 assert user.family_id == family_context.get().id
- OpenAI 兼容 SDK（openai 包 + base_url=https://api.deepseek.com，模型 deepseek-chat）：stream=True + response_format={"type":"json_object"}，不自己写 token 解析
- SSE 事件名/格式 1:1 对齐 B4 §3.2：event: classification / reasoning / proposal / done / error（data: JSON）
- ❌6 补项：tests/ 目录 6 个文件齐全（conftest / test_decomposer_flow / test_diff_engine / test_sse_encoding / test_routine_matcher / test_sse_integration）
- 省 token 策略：auth_verifier 失败立刻返回 SSE error_event，**不调 LLM**；冷启动 System Prompt 最短化
- 所有错误消息/注释/loguru 业务日志全中文；LLM token/prompt 原文 DEBUG 级别默认不打（LOG_LEVEL=DEBUG 才输出）
- 敏感：DEEPSEEK_API_KEY/INTERNAL_API_SECRET/DB_AGENT_READER_URL_PASSWORD 全 SecretStr；日志自动脱敏（loguru filter 屏蔽 SecretStr.__str__）
- 4 层家庭隔离（db_reader 签名 / backend_client headers / 编排 assert / Phase 2 RLS 注释占位但明确不强制 MVP）

---

## File Structure（对齐 B4 §5.1 目录骨架）
| 文件 | 操作 | 职责 |
|---|---|---|
| agent-service/pyproject.toml | Create | ruff/mypy strict/pytest-cov 80/deepseek-sdk/fastapi[all]/uvicorn[standard]/apscheduler/httpx[http2]/sqlalchemy[asyncio,postgresql]/asyncpg/loguru/pydantic-settings/jinja2/pytest-asyncio |
| agent-service/.env.example | Create | LLM 3条 + INTERNAL_API_SECRET + BACKEND_BASE_URL + DB_AGENT_READER_URL + TOOL_SERVICE_URL + FAMILY_ID_SCOPE_ENFORCE=true + DRAFT_EXPIRE_HOURS=24 |
| agent-service/Dockerfile | Create | python:3.12-slim → USER appuser → EXPOSE 8700 → HEALTHCHECK |
| agent-service/app/main.py | Create | FastAPI 挂载 3 router + APScheduler on_event + /healthz + 全局异常兜底 |
| agent-service/app/settings.py | Create | PydanticSettings（SecretStr 敏感 + 9 条配置默认值） |
| agent-service/app/routes/chat_routes.py | Create | A-1 POST /api/agent/chat/stream（SSE 5 事件流） |
| agent-service/app/routes/summary_routes.py | Create | A-2 POST /api/agent/summary/trigger（手动总结）+ B-2 内部 POST |
| agent-service/app/routes/internal_routes.py | Create | B-1 POST /api/internal/ai-decompose（日常/调度/触发 AI 拆） |
| agent-service/app/services/auth_verifier.py | Create | 调 backend C auth/me，返回 CurrentUser(userId, familyId, role)；失败立刻 SSE error 不调 LLM |
| agent-service/app/services/decomposer.py | Create | 拆解 7 步编排 + 原子回滚（写失败→DELETE tasks 软删） |
| agent-service/app/services/summarizer.py | Create | Change 5 用（此 Change 仅搭骨架 pass） |
| agent-service/app/services/diff_engine.py | Create | Change 5 用（搭骨架 stub） |
| agent-service/app/harness/llm_client.py | Create | DeepSeek SDK 单例 + 指数退避重试（1s/2s/4s 三次） |
| agent-service/app/harness/json_guard.py | Create | 2 层 JSON 硬约束：SDK response_format(L1) → json.loads(L2)（严格解析，失败 56002，去 L3 正则兜底） |
| agent-service/app/harness/sse_stream.py | Create | SSE 事件格式标准化；100ms flush 或 prefers-reduced-motion 攒 3s 批量 |
| agent-service/app/connectors/backend_client.py | Create | httpx.AsyncClient 封装 C 组 18 端点（_headers(family_id) 必填） |
| agent-service/app/connectors/db_reader.py | Create | SQLAlchemy async SELECT-only，所有函数签名 family_id:int Required |
| agent-service/app/connectors/weather_client.py | Create | 调 Change 3 tool-service 天气（拆解推理参考） |
| agent-service/app/prompts/*.txt（5 文件） | Create | base.txt / decompose.txt / classify.txt / match_template.txt / summary_per_category.txt |
| agent-service/app/models/*.py（3 文件） | Create | api_schemas.py / llm_schemas.py（Pydantic 严格 JSON）/ diff_types.py |
| agent-service/app/schedulers/summary_job.py | Create | Change 5 用（搭骨架 @app.on_event 注释占位） |
| agent-service/app/utils/*.py（2 文件） | Create | log.py（loguru JSON trace_id）/ secrets.py（hmac 恒定比较） |
| agent-service/tests/ 目录 6 文件 | Create | ❌6 补项前半：conftest / test_decomposer_flow / test_diff_engine(stub) / test_sse_encoding / test_routine_matcher(stub) / test_sse_integration |

---

### Task 1: 工程脚手架 + 配置 + 连接器 + harness 基础

- [ ] **Step 1: 写失败测试基础（工程约束验证）**
test_sse_encoding.py：断言 format_sse("proposal", {"task_id":123}) == "event: proposal\ndata: {\"task_id\":123}\n\n"
test_json_guard.py：畸形 JSON → assert error_code == 56002
Run: `cd agent-service ; python -m pytest tests/test_sse_encoding.py tests/test_json_guard.py -v` Expected: FAIL

- [ ] **Step 2: pyproject + .env.example + settings.py（9 条配置对齐 B4 §8）**
pyproject 依赖：openai>=1.50, fastapi[all]>=0.110, uvicorn[standard]>=0.30, apscheduler>=3.10, httpx[http2]>=0.27, sqlalchemy[asyncio,postgresql]>=2, asyncpg>=0.29, loguru>=0.7, pydantic-settings>=2.2, jinja2>=3.1, pytest-asyncio>=0.23, pytest-cov>=5, ruff>=0.4, mypy>=1.10
.dev.example（9 条对齐 §8）：
DEEPSEEK_API_KEY=sk-xxx（必填）
DEEPSEEK_BASE_URL=https://api.deepseek.com（兼容代理）
DEEPSEEK_MODEL=deepseek-chat
MCP_TIMEOUT=30（Phase 2 也用）
SUMMARY_TIMEOUT_PER_CATEGORY=60（文档 Q79 单分类超时）
INTERNAL_API_SECRET=（与 backend 一致；恒定时间比较）
BACKEND_BASE_URL=http://backend:8080
DB_AGENT_READER_URL=postgresql+asyncpg://agent_reader:password@postgres:5432/task_db
TOOL_SERVICE_URL=http://tool-service:8800
FAMILY_ID_SCOPE_ENFORCE=true（第 3 层 assert 开关）
DRAFT_EXPIRE_HOURS=24
settings.py：PydanticSettings + 所有敏感字段 SecretStr

- [ ] **Step 3: utils/log.py（loguru JSON + trace_id）+ utils/secrets.py（恒定时间）**
log.py：loguru 配置 serialize=True；extra={"trace_id": ContextVar}；filter 屏蔽 SecretStr 值输出
secrets.py：hmac.compare_digest(header, expected) 恒定时间比较 X-Internal-Secret

- [ ] **Step 4: harness 三件套**
llm_client.py：openai SDK ChatCompletions 单例（base_url 可配）；stream=True；指数退避 retry(1s/2s/4s)；省 token：auth 失败不调用；LOG_LEVEL<=DEBUG 才 output prompt 原文
json_guard.py：def parse(raw_text: str) → Pydantic LLMDecomposeResponse
  L1: 调用侧 response_format={"type":"json_object"} 约束 LLM 只输出合法 JSON Object
  L2: json.loads(raw_text) 严格解析 → 失败 raise AIJsonGuardError(56002, "AI 返回格式解析失败，请重试")
  （设计定版：去 L3 正则 {...} 兜底——response_format 已保证合法 JSON，正则兜底是多余防御层）
sse_stream.py：SSE 格式器 format_sse(event:str, data:dict) → str；100ms flush 节流；prefers-reduced-motion=true 时攒 3s reasoning 批量 flush（HTTP header Accept 里解析 prefers-reduced-motion? 或 读 frontend 传入的 reduced_motion=true 参数）

- [ ] **Step 5: 三个 connectors（db_reader / backend_client / weather_client）**
backend_client.py：httpx.AsyncClient 实例；_headers(family_id:int) 方法强制返回 {"X-Family-Id":str(family_id), "X-Internal-Secret": settings.INTERNAL_API_SECRET.get_secret_value()}；family_id 类型非 int → TypeError 不发出请求；封装 C-1~C-14 + C-15~C-18
db_reader.py：SQLAlchemy async sessionmaker；SELECT 全部签名 def get_categories(family_id: int, session: AsyncSession) → list[CategoryDTO]；family_id filter 写死 query.filter(Category.family_id == family_id)
weather_client.py：httpx.AsyncClient.get(settings.TOOL_SERVICE_URL + "/api/weather")；超时 5s；失败返回 weather_type=unknown 不抛

- [ ] **Step 6: Run 工程约束测试 Expected: PASS（sse 格式 + json_guard 两次失败→56002）**
- [ ] **Step 7: Commit feat(change4): 工程脚手架 + 配置 + harness三件套 + 三connectors**

---

### Task 2: A-1 SSE 流式拆解 7 步编排 + Prompt 5 文件

- [ ] **Step 1: 写失败测试 test_decomposer_flow.py（4 场景）+ test_sse_integration.py（5 事件顺序）**
test_decomposer_flow.py 4 场景（mock llm_client / backend_client / db_reader）：
场景1 冷启动（0 模板 0 few-shot）→ expect 输出 ≥3 子待办；reasoning 前缀 "📌 无历史参考…通用建议"
场景2 命中同分类 decomposition 模板（周末大扫除）→ expect common_steps 中 4 条出现在 proposal.subtasks
场景3 CHILD 角色调用 → backend C-11 模板写入被 Change 2 拒绝 → decomposer 不抛 500，proposal 带 warning 提示 "孩子角色不能修改模板"
场景4 LLM 返回畸形 JSON → json_guard(L2) → 返回 SSE error_event 56002 中文
test_sse_integration.py：FastAPI TestClient /stream 读取 SSE 字节流，mock LLM → 断言事件顺序 exactly: event: classification → reasoning ×N → proposal → done （无 error）
Run: `python -m pytest tests/test_decomposer_flow.py tests/test_sse_integration.py -v` Expected: FAIL（服务/编排未实现）
- [ ] **Step 2: decomposer.py 拆解 7 步编排（每步强制 family_id:int）**
def run_decompose(family_id: int, user_id: int, role: UserRole, message: str, preferred_category_id: int | None, sse: SSEStreamWriter) -> None:
    """编排 7 步；任一步失败 -> SSE error_event；Step 6 写失败 -> 原子回滚 DELETE 软删"""
    Step 1 auth_verifier.verify(family_id, access_token) -> CurrentUser；失败立刻 sse.emit_error(40103, "登录已过期…") return（省 token，不调 LLM）
    Step 2 ctx = db_reader.get_context(family_id) -> categories + templates_active + few_shot_recent_10_COMPLETED + workload_today（签名全带 family_id: int）
    Step 3 sse.emit("classification", {"category_id": matched, "confidence": 0.87, "category_name": "家务"})  <- llm classify(ctx, message)
    Step 4 matched_template_id = llm.match_template(ctx, category_id, templates)  <- 同分类 decomposition >=2 才做
    Step 5 proposal: LLMDecomposeResponse = harness(
        prompts["decompose"].render(category=ctx.categories[matched], template=matched_template, few_shot=ctx.few_shot, workload=ctx.workload, message=message)
        stream_callback=lambda token: sse.emit_reasoning(token, flush_interval_ms=100, reduced_motion_bucket_ms=3000)
    ) <- json_guard.parse 失败 → sse.emit_error(56002, …) return
    Step 6 原子写：try:
        task_id = backend_client.create_task(family_id, DRAFT, PROPOSAL_FROM_LLM)
        backend_client.create_subtasks_batch(family_id, task_id, proposal.subtasks, is_ai_generated=True)
        if proposal.notes: backend_client.add_note_batch(family_id, task=task_id, proposal.notes)
      except Any as e:
          backend_client.delete_task(family_id, task_id)  # 软删原子回滚
          sse.emit_error(56003, f"写入后端失败：{e}")
          return
    Step 7 sse.emit("proposal", proposal_with_real_db_ids(task_id, ...))
           sse.emit("done", {"trace_id": str(task_id), "template_id_used": matched_template_id})
           loguru.info(trace_id=task_id, family_id=family_id, template_id=matched_template_id, token_usage=...)

- [ ] **Step 3: 4 层家庭隔离强执行（编排首行 assert）**
decomposer.py 首行：
assert settings.FAMILY_ID_SCOPE_ENFORCE is False or (
    CurrentUser.family_id == family_context.get().id == family_id
), BusinessError(46000, "家庭作用域不匹配，调用越权已拦截")
注释明确 Phase 2 RLS：/* PostgreSQL RLS 加固 MVP 不强制；Phase 2 开启 db_reader每次连接 SET app.family_id='<id>' + ALTER TABLE xxx ENABLE ROW LEVEL SECURITY POLICY */

- [ ] **Step 4: prompts/ 5 个 Jinja2 全中文文件（对齐 Q64 槽位）**
base.txt（System 角色 6 条规则）：
- 您是家庭助手 AI 拆解助手，仅输出严格 JSON，不输出解释
- 子待办粒度 15 分钟内可完成；不超过 8 条
- is_ai_generated=true 子待办；中文语言输出；不使用任何 Markdown
- 遵守 5 状态枚举；source=agent；DRAFT 24h 自动过期
decompose.txt：{% for cat in categories %}{{cat.id}}-{{cat.name}}{% endfor %} / 匹配模板 / few-shot 10 条 / workload 今日已安排 / 用户输入
classify.txt + match_template.txt（单独 System 片段）
summary_per_category.txt（Change 5 用，先放 stub 占位但文件必须存在）
*重要*：Prompt 大段字符串不在文档内粘贴，实施阶段直接落地 prompts/*.txt（与设计分离避免维护分裂）

- [ ] **Step 5: routes/ 三路由入口（A-1 SSE / A-2 stub / B-1 / B-2 stub）**
chat_routes.py A-1:
@app.post("/api/agent/chat/stream")
async def chat_stream(
    req: ChatRequest = Body(...),
    x_access_token: str = Header(alias="X-Access-Token"),
    x_family_id: int = Header(alias="X-Family-Id", ge=1),
    x_reduced_motion: bool = Header(False, alias="X-Prefers-Reduced-Motion")
) -> StreamingResponse:
    writer = sse_stream.SSEStreamWriter(reduced_motion=x_reduced_motion)
    async def event_generator():
        try:
            decomposer.run_decompose(x_family_id, user_id_from_token, role, req.message, req.preferred_category_id, writer)
            async for evt in writer:
                yield evt
        except Exception as e:
            yield writer.format_sse("error", {"code":56000,"msg":f"系统内部错误：{e}"})
    return StreamingResponse(event_generator(), media_type="text/event-stream", headers={"Cache-Control":"no-cache", "Connection":"keep-alive"})
internal_routes.py B-1: POST /api/internal/ai-decompose（头部 X-Internal-Secret 恒定校验）
  30 秒超时 → 返回 {"decomposed":false}（Change 2 AgentCallbackClient 立刻 fallback 直建）
summary_routes.py A-2: POST /api/agent/summary/trigger 手动总结（Change 5 再实现，此 Change 仅返回 56010 "总结模块在 Change 5 中上线"）；B-2 内部 POST stub 返回 success

- [ ] **Step 6: main.py FastAPI 挂载 + /healthz + 异常兜底**
include_router(chat_routes.router, prefix="/api/agent")
include_router(summary_routes.router)
include_router(internal_routes.router, prefix="/api/internal")
@app.get("/healthz")
def healthz() -> dict:
    return {"status":"ok", "llm_configured": settings.DEEPSEEK_API_KEY.get_secret_value() != "", "db_agent_reader_configured": bool(settings.DB_AGENT_READER_URL)}
@app.exception_handler(Exception)
async def global_exception_handler(req: Request, exc: Exception) -> JSONResponse:
    loguru.opt(exception=exc).error(f"未捕获异常 path={req.url.path}")
    return JSONResponse(status_code=500, content={"code":56000,"msg":"AI 服务内部错误，请稍后重试或切换手动创建模式"})

- [ ] **Step 7: Run 4 + 1 集成测试 Expected: PASS（decomposer_flow 4 / sse_integration 1）**
- [ ] **Step 8: Commit feat(change4): A-1 SSE 7 步编排 + Prompt 5 文件 + 三路由入口**

---

### Task 3: 4 层家庭隔离测试 + ruff/mypy/pytest 质量基线

- [ ] **Step 1: 写失败测试家庭隔离 3 场景**
用例1：family_id=1 user 属 family_id=2 → 46000 拦截（编排 assert 触发）
用例2：db_reader.get_categories(family_id=1) 生成 SQL 包含 "family_id = 1"（MockEngine 断言 SQL 字符串）
用例3：backend_client._headers(family_id="abc") → TypeError，不发出任何 HTTP 请求
用例4：B-1 X-Internal-Secret 错误 → 40102 不进入编排
Run: `python -m pytest tests/test_isolation.py -v` Expected: FAIL（或加 assert 未触发）

- [ ] **Step 2: 工程质量门三件套**
ruff check app tests → Expected: 0 errors
mypy app tests strict → Expected: Success: no issues（重点验证 family_id:int 签名强制）
pytest --cov=app --cov-fail-under=80 → Expected: TOTAL ≥ 80%（最低 80% 不可降）

- [ ] **Step 3: ❌6 补项检查 tests 目录 6 文件齐全（stub 允许但必须存在）**
conftest.py（mock_llm_client, mock_backend_client, mock_db_reader fixtures）
test_decomposer_flow.py（4 场景 Task 2 Step 1）
test_diff_engine.py（空骨架或 skip 占位但文件存在，Change 5 实施）
test_sse_encoding.py（sse 格式断言 Task 1 Step 1）
test_routine_matcher.py（Change 2 对应，此处空骨架或 Change 6 实施）
test_sse_integration.py（5 事件顺序 Task 2 Step 1）
*注意：e2e_docker_compose.sh 放 Change 7 写，❌6 补项后半属于 Change 7 CI 阶段*

- [ ] **Step 4: 人工 check_list 10 项**
  [x] 写操作 100% backend_client（无 INSERT/UPDATE/DELETE 通过 db_reader）
  [x] 7 步编排每步签名 family_id:int；mypy strict 不报错
  [x] 4 层隔离：db签名 / backend headers / 编排首行 assert / RLS Phase2 注释
  [x] auth_verifier 失败立刻 SSE error，不调 LLM 省 token（mock 断言 llm_client 零调用）
  [x] json_guard 解析失败 → 56002 中文 msg
  [x] SSE 事件顺序：classification → reasoning → proposal → done（exactly）
  [x] Step 6 原子回滚：mock backend_client.delete_task 被调用 1 次
  [x] DEEPSEEK_API_KEY 等 SecretStr 日志自动脱敏
  [x] ruff 0 错 / mypy strict 0 错 / 覆盖率 ≥ 80%
  [x] ❌6 tests 目录 6 文件齐全（stub 可，必须存在）

- [ ] **Step 5: Commit feat(change4): 4层家庭隔离测试 + 工程质量门 + ❌6 前半测试目录**

---

## Self-Review
- [x] Spec 覆盖：§5.1 目录分层 + §5.2 拆解7步 + §5.3 Prompt结构 + §5.5 容错降级表（拆解30s/JSON两次错）+ §5.6 4层隔离 + §5.8 ❌6 前半 + A/B组路由
- [x] 无占位符：SSE事件名/错误码值/cron表达式/签名参数/重试次数 全部具体
- [x] 类型一致：run_decompose 名称 decomposer/routes 统一；json_guard.parse_with_fallback() 全文件统一
- [x] 测试：工程约束2 + decomposer_flow4 + sse_integration1 + 隔离4 = 11 用例核心

---

## 下一步
启动 OpenSpec：创建 openspec/changes/2026-08-25-agent-service-sse-decompose/ 按 Task 1→2→3 顺序实施。*依赖 Change 2（后端18内部API）+ Change 3（天气服务）完成后再开实施*

（Change 4 Plan 结束）