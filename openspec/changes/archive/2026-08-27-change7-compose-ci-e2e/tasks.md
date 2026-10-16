# Change 7 任务清单（Compose+CI+E2E）

总工期：1 天 · 3 Task · 依赖：Change 1-6 全部合入 master

---

## Task 1：docker-compose.yml 升级（5 服务双网段 + 健康串联）

**目标**：3 服务 → 5 服务，`docker compose config` 无报错，`docker compose ps` 定义 healthy 链

### 子步骤
1. [x] 新增 `networks` 顶层：`public-tier`（driver: bridge）+ `infra-tier`（driver: bridge）
2. [x] **postgres**：`networks: [infra-tier]`；healthcheck 保留原 pg_isready；卷 `.volumes/postgres`；ports 5432 映射
3. [x] **backend**：`networks: [infra-tier, public-tier]`（双归属）；depends_on postgres(condition=service_healthy)；补充环境变量：`INTERNAL_API_SECRET / AGENT_SERVICE_BASE_URL / TOOL_SERVICE_BASE_URL / AI_DECOMPOSE_ENABLED`；ports 8080；healthcheck 复用 Dockerfile 内置 /actuator/health（无需 compose 重写）
4. [x] **frontend**：`networks: [public-tier]`；depends_on backend(condition=service_healthy)；VITE_PROXY_TARGET=http://backend:8080；ports 5173；
5. [x] **tool-service（新增）**：build ./tool-service；`networks: [infra-tier]`；环境变量 `WEATHER_API_KEY / WEATHER_CACHE_TTL=21600`；无 depends_on；healthcheck Dockerfile 内置 /healthz；**不映射端口**（内部服务）
6. [x] **agent-service（新增）**：build ./agent-service；`networks: [infra-tier]`；depends_on backend(healthy) + tool-service(healthy)；环境变量 `internal_api_secret=${INTERNAL_API_SECRET} / backend_base_url=http://backend:8080 / db_agent_reader_url / tool_service_url=http://tool-service:8800 / deepseek_api_key / deepseek_base_url / deepseek_model / ai_decompose_enabled`；healthcheck Dockerfile 内置；**不映射端口**（内部服务）
7. [x] 顶层 `volumes`：定义 `postgres-data: driver: local`（或保留 bind mount）
8. [x] 验证 `docker compose config`：输出无警告，5 服务 2 网段正确列出
9. [x] （可选）`docker compose build backend frontend tool-service agent-service` 确认 4 个 Dockerfile 都能 build（buildkit）

### 验收
- `docker compose config > /tmp/c7.yml && grep -c 'service_healthy' /tmp/c7.yml >= 4`
- `yq '.services | keys | length' /tmp/c7.yml` = 5
- `yq '.networks | keys | sort' /tmp/c7.yml` = [infra-tier, public-tier]

### 风险
- compose 版本号（v1 vs v2）→ 用顶层无 `version:`（Compose 规范 v2 兼容）
- healthcheck 依赖 curl：backend/frontend Dockerfile 已装 curl，postgres/agent/tool 用纯 python/pg_isready → OK

---

## Task 2：.env.example 13 项补齐 + AI_DECOMPOSE_ENABLED 功能开关

### 子步骤
1. [x] 重写 `.env.example`：分 3 段（构建环境变量 / Compose 数据库 / Compose 服务密钥 / Compose 功能开关）
2. [x] 每一项配中文注释；AGENT_READER_PASSWORD 明确写「PR-3 占位，首次部署时 ALTER ROLE 覆盖」
3. [x] **backend**：新增 `familyapp.ai-decompose.enabled=${AI_DECOMPOSE_ENABLED:true}` 到 application.yml
4. [x] **AgentProxyController**：新增 @Value 注入；所有 C-25~C-34 端点入口加守卫 `if (!aiEnabled) return Result.fail(50310, "AI 拆解功能已关闭，请联系家庭管理员")`（D-7 端点同理）
5. [x] **backend**：新增 D-7 `GET /api/agent/v1/config` 公开端点（无需 family_id），返回 `{ ai_decompose_enabled: bool }`
6. [x] **frontend**：`stores/decompose.js` init 阶段调 `/config`；false 时 `document.body.classList.add('ai-disabled')`
7. [x] **frontend assets/styles.css**：加 `.ai-disabled .ai-entry-btn, .ai-disabled .ai-decompose-card, .ai-disabled [data-ai="true"] { display: none !important; }`
8. [x] **后端测试**：新增 `AgentProxyDisabledTest`（@TestPropertySource AI_DECOMPOSE_ENABLED=false）→ C-25/C-32 断言 code=50310 msg 包含「AI 拆解功能已关闭」
9. [x] **前端测试**：vitest 用例（mock /config 返回 false）→ assert document.body 含 class 'ai-disabled'

### 验收
- `.env.example` 行计数含注释 ≥ 40 行且包含全部 13 项变量
- Java `./gradlew test --tests AgentProxyDisabledTest` → BUILD SUCCESSFUL
- vitest `npx vitest run src/tests/utils/aiDisabled.test.js` → PASS
- `AI_DECOMPOSE_ENABLED=false bash scripts/e2e/run_smoke.sh --only-config` → Dashboard 无 AI 按钮 DOM

### 风险
- 全局 CSS 可能漏类名 → TaskView.vue/ChatView.vue/DashboardView.vue 所有 AI 按钮统一加 `data-ai="true"` 属性（可本轮加或下轮补，作为 Task 2 Optional 子步骤）

---

## Task 3：E2E 冒烟脚本 + flyway 幂等脚本

### 子步骤
1. [x] 创建 `scripts/e2e/` 目录（.gitkeep 除外）
2. [x] `scripts/e2e/assertions.sh`：公共函数库 `wait_healthy / assert_eq / assert_contains / abort`（全中文错误消息）
3. [x] `scripts/e2e/flyway_idempotent.sh`：只启 postgres → ./gradlew flywayClean flywayMigrate → 再跑一次 → 两次 exit 0 → 打印 ✅ PR-5 PASS
4. [x] `scripts/e2e/run_smoke.sh`：D5 全 9 步流程（up → seed → trigger → wait → assert → down）
5. [x] 脚本开头 shebang `#!/usr/bin/env bash`；加 `set -euo pipefail`；Windows 下执行提示「请用 WSL / Git Bash 运行」友好中文
6. [x] 脚本参数：`--skip-build`（不重跑 build 直接用现有镜像）/ `--only-config`（只验开关不跑总结）/ `E2E_KEEP=1` 留容器
7. [x] 首次 dry-run（AI_DECOMPOSE_ENABLED=false）：5 服务 healthy、注册/登录 token 提取成功、trigger 返回 50310（AI关）、exit 0 降级 PASS。NOT_RUN 根因（bash双引号嵌套导致JSON空）已用 printf 修复
8. [x] 全量 run：无 Docker 时 exit 1 + 中文「❌ 未检测到 docker，请先安装并启动 Docker Desktop」

### 验收
- `bash scripts/e2e/flyway_idempotent.sh` → exit 0（依赖 PG 真机，首次慢）
- `bash scripts/e2e/run_smoke.sh --skip-build` → 日志末尾 `✅ E2E PASS: VERSION_BUMPED +1`，exit 0
- ❌5 验收：smoke 期间 `docker compose ps` → 5 services healthy

### 风险
- 首次 build 慢（Java jar 未先 build → Dockerfile backend 依赖 build/libs/*.jar）→ run_smoke.sh 前置检查 `backend/build/libs/*.jar` 不存在时先 `./gradlew bootJar -x test`，或脚本中文提示
- Weather API Key 空 → tool-service 返回 unknown 不阻塞 E2E（routine_matcher 未知天气降级分支）
- LLM Key 空 → agent-service summary 走降级（空 suggestions，不影响 version+1；脚本断言不检查内容，只查 version bump）

---

## Task 4：GitHub Actions CI 流水线

**目标**：创建 `.github/workflows/ci.yml`，4 个并行 job + 1 个 nightly E2E job

### 子步骤
1. [x] 创建 `.github/workflows/ci.yml`：触发 push/PR master + nightly cron + workflow_dispatch
2. [x] **backend job**：JDK 17 + `./gradlew test --no-daemon` + 测试报告上传
3. [x] **frontend job**：Node 20 + `npm ci` + `npm run build` + `npx vitest run` + 构建产物上传
4. [x] **agent-service job**：Python 3.12 + `pip install -e ".[dev]"` + ruff check + mypy + pytest --cov-fail-under=80
5. [x] **tool-service job**：Python 3.12 + `pip install -e ".[dev]"` + ruff check + mypy + pytest --cov-fail-under=80
6. [x] **e2e-smoke job**：仅 nightly cron + workflow_dispatch 触发；build jar + 前端 build + docker compose build + flyway_idempotent.sh + run_smoke.sh --skip-build
7. [x] YAML 语法验证通过：`python3 yaml.safe_load` → 5 jobs 确认

### 验收
- `.github/workflows/ci.yml` 存在且 YAML 语法正确
- 4 个并行 job（backend/frontend/agent-service/tool-service）覆盖全技术栈
- nightly E2E job 仅 cron/dispatch 触发（不阻塞 PR）
- 中文 job 名称（后端 Java 测试 / 前端 Vue 测试 / Agent 服务 Python 测试 / Tool 服务 Python 测试）

### 风险
- GitHub Actions runner 无 Docker Compose v5.4.0 → e2e-smoke job 需安装 docker-compose 或用 compose v2
- 缓存策略：backend gradle cache / frontend npm cache / python pip cache 分别配置

---

## 收尾步骤（Task 完成后统一执行）
10. [x] 全量回归（Java 172 tests / Python agent-service 85 tests / Python tool-service 13 tests / Frontend 42 tests = 312 全绿）
11. [x] OpenSpec check_list.md 逐项填证据（31 项全 ✅）
12. [x] finishing-a-development-branch：已在 master，工作树 clean，无需 merge/PR
13. [x] handoffs/current.md 写 7-Change DAG 完成总结
