# Proposal: agent-service-sse-decompose（AI 任务拆解系统 · Change 4/7）

> 归属：docs/superpowers/plans/2026-08-25-ai-task-decomp-overview.md 第四阶段（依赖 Change 2 已合入）。
> 规格：docs/superpowers/specs/2026-08-25-ai-task-decomp-design.md §3.2/§3.3 + §5 全章。
> 分支：feature/change4-agent-service-sse（BASE 1482414）

## 背景

Change 2 已交付后端 C 组 18 内部端点与 B 组回调客户端。本 change 交付 AI 侧的 Python agent-service：
对外 A-1 SSE 流式拆解（5 事件）、对内 B-1 AI 拆解 / B-2 单家庭总结入口，落地 7 步拆解编排、
4 层家庭隔离、deepseek 官方 SDK harness 模式（stream + response_format），以及 ruff/mypy/pytest-cov
工程质量门。总结本体与对比学习属 Change 5，本 change 仅搭骨架 stub。

## 目标

1. agent-service 工程脚手架：pyproject（ruff 120 / mypy strict / pytest-cov 80）/ settings（SecretStr）/ .env.example / Dockerfile
2. harness 三件套：llm_client（deepseek SDK 单例 + 指数退避）/ json_guard（2 层）/ sse_stream（100ms flush 或 reduced-motion 攒 3s）
3. 三 connectors：backend_client（httpx 封装 C 组，写操作 100% 经此）/ db_reader（SQLAlchemy async SELECT-only，签名 family_id:int）/ weather_client（调 Change 3，5s 超时降级 unknown）
4. A-1 SSE 流式拆解 7 步编排（classification → reasoning×N → proposal → done / error），每步强制 family_id
5. B-1 内部拆解（30s 超时 → decomposed:false）、A-2/B-2 stub（总结落 Change 5）
6. 4 层家庭隔离（db 签名 / backend headers / 编排首行 assert 46000 / RLS Phase2 注释占位）
7. 测试矩阵：工程约束 2 + decomposer_flow 4 + sse_integration 1 + 隔离 4 ≈ 11 用例核心

## 非目标 (Non-goals)

1. **不接入 DSH 产品**（@deepseek-ai/dsh CLI / web / headless / 插件生态）——已评估并放弃；agent 运行时 = deepseek 官方 Python SDK（§5 标题「DeepSeek Harness / dsh 模式」实指 SDK 内置 tool_use/response_format 的 harness 用法，非 DSH 产品）
2. 不实现 Change 5 总结本体与对比学习（summarizer / diff_engine 仅 stub，A-2/B-2 仅搭路由）
3. 不实现 tool-service 天气本体（Change 3）；weather_client 按 D-1 契约实现，测试用 mock，不阻塞本 change
4. 不实现前端四视图（Change 6）
5. 不引入 Redis / 分布式锁（Change 7）；22:00 总结调度本 change 仅注释占位
6. 不强制 PostgreSQL RLS（Phase 2 安全加固，仅注释占位）
