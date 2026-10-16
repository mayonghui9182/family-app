# AI 任务拆解系统 · 变更拆分总览（2026-08-25）

> 对应规格：docs/superpowers/specs/2026-08-25-ai-task-decomp-design.md
> 拆分原则：单一可交付物 + 可独立测试 + 明确依赖边界；每个 change 最多跨 2 层（避免前后端+DB+部署全混）
> 每个 change 独立 OpenSpec 流程：proposal → delta spec → design → tasks → 实施 → 验证 → 归档

---

## 依赖拓扑（DAG，串行 + 并行标记）

```
Change 1: V6 Flyway 数据模型迁移 ──┐
                                     ├──► Change 4: agent-service 骨架+SSE拆解 ──► Change 5: 总结+对比学习 ──┐
Change 2: 后端内部 Agent API+调度  ──┘                                                                    │
Change 3: tool-service 天气服务 ──────────────────────────────────────────────────────────────────────────┼──► Change 7: Compose+CI
                                                                                                          │
Change 6: 前端三视图+改造（SSE端点就绪可并行做UI骨架，SSE联调转Change 4后）──────────────────────────────────────┘
```

| 阶段 | 可并行组 | 说明 |
|:---:|:---|:---|
| **第一阶段（并行）** | Change 1 + Change 3 | V6 数据模型 vs 天气服务，零依赖，可同时开工 |
| **第二阶段（串行）** | Change 2（依赖 Change 1） | 后端内部 API 必须等表结构就绪 |
| **第三阶段** | Change 4（依赖 2+3） | Agent 服务需后端内部 API + 天气 + 只读角色 |
| **第四阶段（并行）** | Change 5 + Change 6 UI骨架 | 总结/对比学习 vs 前端 UI 骨架（SSE 联调等 Change 4） |
| **第五阶段** | Change 7（依赖 4+5+6） | Compose 编排 + CI 流水线 + 端到端验收 |

---

## 7 个 Change 清单（范围 + 验收 + 估算）

### Change 1: V6 Flyway 数据模型迁移（无依赖 · 2天）
**范围**：backend/src/main/resources/db/migration/V6__Add_Agent_AI_Task_Decomposition.sql
- 7 张新表：subtasks / notes / rule_templates / rule_template_versions / task_schedules / task_triggers / summary_execution_log
- PostgreSQL agent_reader 角色创建 + 列级 SELECT 授权（敏感列拒绝）
- 现有 6 表增量：tasks.status CHECK 防御纵深；Repository DRAFT 默认 predicate（代码层）
- V6 种子数据：family_id=1 演示家庭 3 条模板（周末大扫除 decomposition / 上学日检查书包 routine / 月度记账 routine）
**验收**：✅ ./gradlew flywayMigrate PostgreSQL 16 无错 ✅ H2 CI 环境 flywayClean + migrate 通过 ✅ agent_reader 登录只能 SELECT 指定列 ✅ 种子数据 3 条模板存在 ✅ 过期 DRAFT 在 findWithFilters 自动隐藏
**对应规格**：§2 全章 + ❌1 补项

### Change 2: 后端内部 Agent 18 API + 调度增量（依赖 Change 1 · 3天）
**范围**：backend Spring Boot 增量
- X-Internal-Secret 拦截器 + WebConfig 注册（白名单 /api/internal/**，header 恒定比较）
- C 组 18 内部端点（/api/internal/agent/**）：14 MCP 对齐（C-1~C-14）+ 4 自动化必备（C-15~C-18）
- B 组回调：InternalAgentClient（RestTemplate/WebClient）调 agent-service B-1 AI 拆解 + B-2 单家庭总结
- 调度器增量：2:00 日常 routine_matcher → C-15 → B-1/直建；3:00 task_schedules 原子行锁 SKIP LOCKED → C-16 → B-1/直建；TaskServiceImpl.changeStatus() 内部事件 → C-17 task_triggers on_complete
- 新 ResultCode：46xxx 校验 / 56xxx 运行（全中文 msg）
- Internal-mock Spring Profile（CI 放行 /api/internal/**）
**验收**：✅ SpringBootTest C-1~C-18 全绿 ✅ X-Internal-Secret 错头返回 401 中文 ✅ 2:00/3:00 调度触发 1 条任务（Awaitility）✅ H2 CI integrationTest 通过
**对应规格**：§3 全章（A/B/C/D 错误码段 / 18+4 端点）+ §5.7 调度归属 backend 侧

### Change 3: tool-service 天气服务（无依赖，可并行 Change 1 · 1天）
**范围**：新增 tool-service/ 目录（Python 3.12）
- FastAPI 脚手架 + pyproject.toml（ruff 120 / mypy strict / pytest）
- D-1 GET /api/weather?city=&date=：httpx 调第三方，进程内 LRU 6h 缓存
- WEATHER_CACHE_TTL 从环境变量读（默认 21600 秒 = ❌7 补项）
- 降级：第三方失败 → 56501 中文 msg + weather_type=unknown
- /healthz 端点 + Dockerfile（python:3.12-slim USER appuser EXPOSE 8800）
**验收**：✅ pytest LRU 缓存命中/失效用例 ✅ mock 第三方 5xx → 降级 unknown ✅ ruff check 0 错 ✅ mypy strict 0 错 ✅ docker build 成功
**对应规格**：§3.5 D-1 + ❌7 补项 + §8 tool-service 配置

### Change 4: agent-service 骨架 + SSE 流式拆解（依赖 Change 2 + Change 3 · 3天）
**范围**：新增 agent-service/ 目录（Python 3.12 + FastAPI + APScheduler）
- 目录分层：routes / services / harness / connectors / prompts / models / schedulers / utils / tests
- A-1 SSE 流式拆解 5 事件（classification / reasoning / proposal / done / error）
- harness 三层：llm_client（SDK stream + retry）/ json_guard（3层 JSON 约束 parse / 两次错 56002）/ sse_stream（100ms flush / prefers-reduced-motion 攒 3s）
- 拆解 7 步流程：auth_verifier（调 backend C auth/me）→ db_reader（agent_reader SELECT，签名 family_id Required）→ LLM 分类 → 模板匹配 → LLM 拆解 → 写数据（backend_client 走 C 组 API，原子回滚）→ SSE 收尾+id回填
- Prompt 5 文件：base / decompose / classify / match_template / summary_per_category（Jinja2 全中文）
- 4 层家庭隔离强执行（db_reader 签名 / backend_client _headers / 编排 assert / RLS Phase 2 注释）
- /healthz + B-1 /api/internal/ai-decompose + B-2 /api/internal/summary/one-family 入口
**验收**：✅ pytest test_decomposer_flow（冷启动/命中模板/JSON两次错救活）✅ test_sse_encoding 字节流断言 ✅ test_sse_integration 5 事件顺序 exactly ✅ mock LLM token 省流（auth_fail 不调 LLM）✅ 家庭隔离 用例 family_id 不匹配 46000
**对应规格**：§5.1-§5.3 + §5.5-§5.6 + ❌6 补项前半

### Change 5: agent-service 每日总结 + 对比学习（依赖 Change 4 · 2天）
**范围**：agent-service 增量
- summarizer.run_family：按分类循环单分类 60s 超时不阻断
- diff_engine 三态算法（ADD/MODIFY/REMOVE × 加权阈值 × notes 关键词复现）+ 版本保留策略（is_important 永久 / 普通 30 天 / 最少 3）
- ❌2 补项：总结前先跑「近 7 天 CANCELLED source=agent DRAFT」≥5 次 → change_summary 警告 + Automation Tab 高亮提示（cancelled draft 学习信号负反馈分支）
- APScheduler 22:00 summary_job：按 family 分批次，分布式锁 summary_execution_log uk_sel_family_run_date（Insert 成功才跑，Conflict skip）
- A-2 手动触发总结 /api/agent/summary/trigger：OWNER/ADULT，异步立即返回
**验收**：✅ test_diff_engine（AI4 子待办→用户5条，diff_report 断言 ADD/MODIFY/REMOVE 三态）✅ ❌2 补项 5 次取消 → change_summary 含警告关键字 ✅ 分布式锁：并发两次 Insert → 只有一个 success ✅ 单分类 60s 超时 → failed_categories 不阻断其它分类
**对应规格**：§5.4 全章 + ❌2 补项 + ❌6 补项后半 E2E

### Change 6: 前端 4 新视图 + 2 旧视图增量 + SSE 封装（SSE联调依赖 Change 4，UI骨架可并行 · 3天）
**范围**：frontend/src/ 增量（老人友好 + 无障碍基线零回退）
- 新视图：DashboardView.vue "/"（❌4 补项 3 卡片）+ ChatView.vue（SSE 拆解 + DRAFT 24h 倒计时 + CHILD 置灰）+ TemplatesView.vue（分解模板版本 diff + 日常可视化编辑器）+ AutomationView.vue（日历 + 依赖 + ❌3 补项 指标卡 Tab3）
- 新组件：ProposalEditor.vue（子待办增删改 + 注意事项）
- 新基础设施：api/agent.js（共用拦截器工厂）+ utils/sseClient.js（指数退避 VITE_SSE_RECONNECT_DELAY=3000 = ❌8 补项 + prefers-reduced-motion 攒 3s）
- 旧视图增量：TaskView.vue（DRAFT 高亮 + 3 大按钮 + 子待办 AI/手写前缀 + 筛选器多 2 项 + 空状态分层）+ CategoryView.vue（关联模板数列 + 合并模板迁移二选一）
- Router 4 条路由 + AppSider 侧边栏 4 菜单项 + CHILD 角色 CUD 置灰 tooltip
- 无障碍 9 条不回归
**验收**：✅ npm run build 0 错 ✅ Vitest sseClient.js 重连退避测试 ✅ CHILD 角色按钮 disabled + tooltip 中文 ✅ WCAG 键盘 Tab 顺序正确 ✅ focus-visible 3px 蓝焦点 ✅ ChatView SSE 断网 3 次 → 转手动创建按钮
**对应规格**：§4 全章 + ❌3 + ❌4 + ❌8 补项

### Change 7: Docker Compose 5 容器 + CI 流水线 + 一键回退（依赖 4+5+6 · 1天）
**范围**：根目录 + CI 配置
- docker-compose.yml 5 容器：postgres / backend / agent-service / tool-service / frontend（双网段 frontend-net/backend-net + depends_on condition: service_healthy + 非 root + .env 变量注入）
- Nginx 路由：/api/agent/** → agent-service；其它 /api/** → backend；/api/internal/** deny all
- 全局 .env.example：13 条环境变量（§8 清单）
- CI 流水线合一：Java ./gradlew check test integrationTest + Python ruff+mypy+pytest 80% + 前端 lint+build+test + E2E compose（仅 nightly）
- AI_DECOMPOSE_ENABLED=false 一键回退开关（B-1 立刻 fallback；ChatView 顶部中文提示）
- tests/e2e_docker_compose.sh（up → 注册 → chat → 提案确认 → 完成 → 手动总结 → 断言模板版本+1 → down -v）
**验收**：✅ docker compose up 全容器 healthy ✅ 前端 80 端口访问 ✅ /api/internal/** 从外网 curl 返回 403 ✅ E2E 脚本跑通断言 ✅ AI_DECOMPOSE_ENABLED=false ChatView 提示且任务仍可创建
**对应规格**：§1.4 + §6 + §7 + §8 配置 + 附录 A 回退策略 + ❌5 + ❌6 E2E

---

## 8 补项跨 Change 归属追踪表

| 编号 | 补项名 | 主责 Change | 联调 Change | 验证点 |
|:---:|:---|:---:|:---:|:---|
| ❌1 | 过期 DRAFT 查询默认 predicate | Change 1 | — | TaskRepository.findWithFilters 过期 DRAFT 自动隐藏 |
| ❌2 | cancelled draft 学习信号（拒绝→修正策略） | Change 5 | Change 7 | 5 次取消 → change_summary 警告 + Automation Tab 高亮 |
| ❌3 | 自学习效果三指标 UI 卡片 | Change 6 | Change 5 | Automation Tab3 + Dashboard 卡② 三大 24px 数字 |
| ❌4 | Dashboard "/" 总览视图 | Change 6 | — | 根路由 "/" 3 卡片 28px 数字 |
| ❌5 | Compose 5 容器双网段健康检查 | Change 7 | — | docker compose ps 全 healthy |
| ❌6 | ruff/mypy/pytest/SSE/E2E | Change 3+4+5 | Change 7 | Python CI 全绿 + E2E 脚本断言 |
| ❌7 | WEATHER_CACHE_TTL=21600 | Change 3 | — | 6h 缓存命中 / 失效 LRU 测试 |
| ❌8 | VITE_SSE_RECONNECT_DELAY=3000 | Change 6 | — | sseClient.js 3s→6s→12s→24s 指数退避 |

---

## 下一步（每个 Change 的标准流程）
1. 新建 openspec/changes/<YYYY-MM-DD>-<change-slug>/ 目录
2. 写 proposal.md（背景 / 目标 / 非目标 / 风险）
3. 写 specs/<slug>-spec.md（delta spec，至少 1 Scenario 条 requirement）
4. 写 design.md（文件清单 + 伪代码 + 权限矩阵）
5. 写 tasks.md（≤15 条，[ ] 可打勾）
6. 写 check_list.md（≤10 条验证清单）
7. 代码实施 + 单测 + 集成测试
8. 运行 check_list 全勾 → 归档到 openspec/changes/archive/

（总览结束）