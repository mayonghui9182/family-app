# Change 7 Proposal：Compose 5 容器双网段 + CI + E2E

## 背景（Why）
当前 DAG 前 6 个 Change 全部落地（Change 1-V6 / 2-内部18API / 3-天气 / 4-agent SSE / 5-总结对比 / 6-前端四视图），
但 **生产/联调环境缺少统一部署入口**：
- `docker-compose.yml` 只定义 3 服务（postgres/backend/frontend），缺少 Change 3/4 交付的 `tool-service`（8800）和 `agent-service`（8700）
- `.env.example` 仅有 9 项（6 compose + 3 构建覆盖），未覆盖 8 补项配置清单（PR-3 agent_reader 密码 / WEATHER_API_KEY / LLM Key / internal secret × 2 服务等）
- ❌5 未达标：当前 compose 无法一键 `docker compose ps` 出 **5 容器全部 healthy**
- ❌6 后半未完成：缺少 E2E 冒烟脚本断言「总结 run 后 version+1 闭环」
- Change 1 遗留 PR-5（afterMigrate 连续两次 flywayMigrate 幂等性）无 CI 步骤

## 目标（What）
1. **docker-compose.yml**：3 服务 → 5 服务，`infra-tier` / `public-tier` 双网段，全容器 HEALTHCHECK 串联 depends_on(condition=service_healthy)
2. **.env.example**：补齐 ≥13 项运行时配置（数据库/密码/密钥/LLM/服务 URL/端口/功能开关）
3. **AI_DECOMPOSE_ENABLED**：功能开关（默认 true；false 时 frontend 隐藏 AI 入口按钮，backend AgentProxyController 返回 503）
4. **E2E 脚本**：`scripts/e2e/` 冒烟测试，断言「触发总结后模板版本号 +1」覆盖 ❌6 后半
5. **CI 基线验证**（如不做完整 CI 文件，则脚本化）：连续两次 `flywayClean + flywayMigrate` 验证 afterMigrate 幂等（PR-5）

## 非目标（Non-goals · 防漂移红线）
- ❌ 不修改业务代码（Task/Category/Agent 内部逻辑、Vue 组件、SSE 编排）
- ❌ 不新建测试框架（沿用 pytest / vitest / gradle test，不引入 Playwright/Cypress）
- ❌ 不搭建完整 CI 平台（GitHub Actions / Jenkins pipeline 不在本次范围；只提供本地可执行脚本）
- ❌ 不改动数据库 schema（不新增 Flyway 迁移）
- ❌ 不补 PR-2（DO 块 schemaname）/ PR-4（notes 触发器完整性）/ PR-6（COUNT type 分布）— 这些非 Compose/CI 范畴

## 风险
- Windows Docker Desktop 资源不足 → 解决方案：启动前预检 `docker info`，失败即中止并提示
- Healthcheck start-period 过短导致 Java/Python 容器被误判 unhealthy → 统一 `--start-period=60s`（backend 80s）
- LLM Key 未配置导致 agent-service healthz 200 但拆解失败 → healthz 只检查进程存活不查下游，E2E 脚本用 `skip_llm=true` 模式跑闭环

## 验收标准（8 补项 + 3 PR 覆盖）
| 编号 | 验收方法 |
|:---:|:---|
| ❌5 | `docker compose up -d` → wait 3 min → `docker compose ps` 5 行全是 healthy |
| ❌6后半 | `bash scripts/e2e/run_smoke.sh` → exit 0，日志含「VERSION_BUMPED +1」 |
| PR-3 | `.env.example` 含 `AGENT_READER_PASSWORD=placeholder-agent-reader-password` 占位说明 |
| PR-5 | `bash scripts/e2e/flyway_idempotent.sh` 两次 flywayMigrate exit 0 |
| 功能开关 | `AI_DECOMPOSE_ENABLED=false docker compose up` → Dashboard AI 按钮 `display:none` |

## 前置依赖
- Change 1-6 已合入 master（当前 master = 9c1c70c + Change 6 本地改动）
- 所有 Dockerfile 健康检查已就绪（见收集信息：frontend curl / backend actuator / agent urllib / tool urllib / postgres pg_isready）
