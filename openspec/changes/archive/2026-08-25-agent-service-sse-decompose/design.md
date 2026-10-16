# Design: agent-service-sse-decompose

## D1 — agent 运行时 = deepseek 官方 SDK（术语澄清）
规格 §5 标题「DeepSeek Harness / dsh 模式」指 deepseek 官方 Python SDK 的 harness 用法：
`deepseek.ChatCompletions.create(stream=True, response_format={"type":"json_object"})`，
不自己写 token 解析。**非** @deepseek-ai/dsh 产品（CLI/web/headless 插件 harness）。
评估结论：DSH headless 无流式、无租户隔离、无 JSON 保证、带终端/代码执行，与 A-1 SSE 契约冲突，
已放弃。agent-service 用 `openai` SDK（OpenAI 兼容，DeepSeek 官方推荐；PyPI 上的 `deepseek`/`deepseek-sdk` 均为第三方，不用），base_url 默认 https://api.deepseek.com，模型 `deepseek-chat`。规格 §5 的 `deepseek.ChatCompletions` 对应 `openai.chat.completions`。

## D2 — json_guard 2 层（去 L3 正则兜底）
L1 = response_format json_object（LLM 侧约束只输出合法 JSON Object）；L2 = `json.loads` 严格解析。
失败 → raise AIJsonGuardError(56002, 中文 msg)。**去掉 L3 正则 `{...}` 兜底**：response_format
已保证合法 JSON，正则兜底是多余防御层。规格 §5.2 Step5 / §5.5「JSON 两次错」同步改为「解析失败」。

## D3 — SSE 产品行为（非 harness 冗余）
reasoning 逐 token：100ms flush；`X-Prefers-Reduced-Motion` 或参数 reduced_motion=true 时攒 3s 批量。
事件名/格式 1:1 对齐规格 §3.2：classification / reasoning / proposal / done / error（data: JSON）。

## D4 — 4 层家庭隔离强执行
1. db_reader 所有 SELECT 签名 `family_id: int` Required，filter 写死
2. backend_client `_headers(family_id: int)`，非 int → TypeError 不发出
3. 编排首行 assert `CurrentUser.family_id == family_context.get().id == family_id`，不匹配 46000
4. RLS Phase 2 注释占位（MVP 不强制）

## D5 — Change 3 依赖解耦
weather_client 按规格 D-1 `GET /api/weather?city=&date=` 实现，httpx 超时 5s，失败/超时返回
`weather_type=unknown` 不抛。Change 3 未完成前仅用于「拆解推理参考」（可选增强），测试全 mock，
不阻塞 Change 4 交付。

## D6 — 写操作 0 例外
写操作 100% 经 backend_client 调 C 组内部 API（含 DELETE 软撤销）；Step 6 写失败原子回滚：
DELETE tasks 软删已建行。db_reader 仅 agent_reader 角色 SELECT 只读（DB 层也拒绝写）。

## D7 — 工程质量门
ruff check 0 错 / mypy app tests strict 0 错 / pytest --cov=app --cov-fail-under=80 必须 ≥80%。
tests/ 目录 6 文件齐全（stub 可，必须存在）：conftest / test_decomposer_flow / test_diff_engine(stub) /
test_sse_encoding / test_routine_matcher(stub) / test_sse_integration。
