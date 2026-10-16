# Change 7 设计：Compose 双网段 + E2E 冒烟

## D1 5 服务拓扑与双网段划分

```
                   ┌──────────── public-tier ────────────┐
  用户 :5173 ──►  frontend(Vite/5173)                    │
                      │ proxy /api                      │
                      ▼                                  │
                   backend(8080)  ◄── AgentProxyController ─► agent-service(8700)
                   ┌─────┴─────┐                        │
  双归属◄──────────┤           ├──────────►              │
                   ▼           ▼                        │
              postgres(5432)  tool-service(8800)         │
                   └─────────────┬──────────────────────┘
                                 │
                   ┌──────────── infra-tier ─────────────┘
                    postgres / backend / tool-service / agent-service
```

**网段规则**：
- `networks.public-tier`：frontend + backend（frontend 只跟 backend 通信，不可直连 DB/Agent/Tool）
- `networks.infra-tier`：postgres + backend + tool-service + agent-service（内部互调用）
- backend **同时加入两个网段**（对外 API 网关，对内连 DB 和 AI 服务）

**端口映射（host ↔ container）**：
- 5432:5432（postgres · 开发调试用；生产可移除）
- 8080:8080（backend · 对外）
- 5173:5173（frontend · 对外）
- **8800 不映射 host**（tool-service 仅 infra-tier 内部可达；Change 2 backend 通过 compose DNS `tool-service:8800` 调）
- **8700 不映射 host**（agent-service 仅 infra-tier 内部可达；Change 6 frontend 通过 backend 的 `/api/agent/v1/**` 代理调 C-25~C-34）

## D2 健康检查串联（depends_on condition=service_healthy）

```
postgres (pg_isready 5s×10)
  ├─► backend (actuator/health start-period=80s)
  │     ├─► frontend (GET / start-period=30s)
  │     └─► agent-service (/healthz start-period=40s)  ◄──┐
  └─► tool-service (/healthz start-period=20s)         ───┘ (agent also depends tool for weather hint)
```

实际 compose depends_on 精确矩阵：
| 服务 | depends_on(condition) |
|---|---|
| backend | postgres(healthy) |
| frontend | backend(healthy) |
| tool-service | ∅ |
| agent-service | backend(healthy) + tool-service(healthy) |

原因：agent-service 的 B-2 总结调 C-19~C-24 需要 backend；weather 提示（C-15 routine 匹配）需要 tool-service。

## D3 环境变量 × 13 项（.env.example）

| # | 变量名 | 默认值 | 说明 |
|---|---|---|---|
| 1 | POSTGRES_DB | familyapp | 数据库名 |
| 2 | POSTGRES_USER | familyapp_user | DB 主用户（写） |
| 3 | POSTGRES_PASSWORD | placeholder-postgres-password（开发） | 主密码 |
| 4 | AGENT_READER_PASSWORD | placeholder-agent-reader-password（PR-3） | agent_reader 只读角色密码，afterMigrate ALTER ROLE 覆盖 |
| 5 | INTERNAL_API_SECRET | dev-secret-1234-change-me | X-Internal-Secret 请求头，backend/agent 双向校验 |
| 6 | BACKEND_PORT | 8080 | |
| 7 | FRONTEND_PORT | 5173 | |
| 8 | AGENT_SERVICE_BASE_URL | http://agent-service:8700 | backend 调 agent |
| 9 | TOOL_SERVICE_BASE_URL | http://tool-service:8800 | backend 调 weather |
| 10 | DB_AGENT_READER_URL | postgresql://agent_reader:***@postgres:5432/familyapp | Change 4 agent-service 只读连接 |
| 11 | WEATHER_API_KEY | （留空，用户申请 ❌7） | tool-service 第三方 Key |
| 12 | DEEPSEEK_API_KEY | （留空） | agent-service LLM；空值时拆解返回「手动创建模式」降级 |
| 13 | AI_DECOMPOSE_ENABLED | true | 功能开关；false 时 frontend AI 按钮 CSS 隐藏 |

额外构建覆盖变量（保留原状）：JAVA_HOME / NODE_HOME / GRADLE_USER_HOME。

## D4 AI_DECOMPOSE_ENABLED 回退开关三端落实

1. **backend application.yml**：新增
   ```yaml
   familyapp:
     ai-decompose:
       enabled: ${AI_DECOMPOSE_ENABLED:true}
   ```
   AgentProxyController C-25/C-26/C-27~C-34：!enabled → 返回 Result(50310, "AI 拆解功能已关闭，请联系管理员")（中文）
2. **frontend client.js 或 stores/decompose.js**：mounted 时读 `/api/agent/v1/config`（新增 D-7 端点）→ enabled=false → `document.body.classList.add('ai-disabled')` 全局 CSS
3. **CSS**：`.ai-disabled .ai-decompose-entry, .ai-disabled .ai-decompose-btn { display: none !important; }`

**影响面最小化**：开关值 = false 时不启动 agent-service 容器（compose `profiles: [ai-enabled]` 或条件 depends_on 通过 COMPOSE_PROFILES=ai）。

## D5 E2E 冒烟（❌6后半）脚本结构

```
scripts/e2e/
├── run_smoke.sh         # 入口：up → seed → trigger → assert → teardown
├── flyway_idempotent.sh # PR-5：连续两次 flywayClean+flywayMigrate 断言 exit 0
└── assertions.sh        # 公共断言库：assert_eq / assert_contains / wait_healthy $svc
```

run_smoke.sh 步骤：
1. `docker compose up -d`（若无 compose 则 abort 友好中文）
2. `wait_healthy postgres backend frontend tool-service agent-service`（每 10s 轮询，最多 180s；超时打印 docker compose logs 末 30 行）
3. 注册测试家庭 / 登录 → 取 `X-Access-Token` + `X-Family-Id`
4. baseline = 调 C-34 metrics 存 `version_count_30d`
5. 调 C-32 `GET /api/agent/v1/summary/trigger/{familyId}`（force=true）→ 立即返回 trigger_id
6. 轮询 C-33 list 直到 `status='COMPLETED'`（最多 60s；agent 空 LLM key 时降级为空总结，version 仍 +1 由 C-22 清理+插入触发）
7. after = 再调 C-34 → `assert after.version_count_30d >= baseline+1` → 打印 `✅ E2E PASS: VERSION_BUMPED +1` → exit 0
8. 任何失败：`docker compose logs` 末 50 行 + `docker compose ps` → exit 1
9. cleanup=`docker compose down`（除非留证 `E2E_KEEP=true`）

flyway_idempotent.sh：
1. 只启动 postgres：`docker compose up -d postgres`
2. `cd backend && ./gradlew flywayClean flywayMigrate -x test`
3. **再执行一次同命令**（幂等验证）→ 第二次 BUILD SUCCESSFUL + flyway history 表 count 不增加版本
4. 两次都成功 → exit 0（PR-5 关闭）

## D6 遗留风险决策（本 Change 主动 Accept Now）
| ID | 内容 | 决策 |
|---|---|---|
| PR-1 | PG 真机 UNVERIFIED（Change 1 首次 afterMigrate 验证已在晚间 session 完成） | 已在 Change 1 合并前跑通，Accept now |
| PR-2 | DO 块 schemaname 缺省 | 多 schema 非本项目目标，Accept now |
| PR-4 | notes 表多态完整性 trigger | 业务层 Java 校验，DB trigger 属超设计，Accept now |
| PR-6 | V6MigrationTest COUNT 不验 type 分布 | 数据准确性已有 Change 2/4 其它测试覆盖，Accept now |

## D7 回滚策略
- Compose 文件改动：Git 管理，回滚 = `git checkout HEAD~1 -- docker-compose.yml .env.example`
- E2E 脚本：纯非破坏性，写操作只作用于 compose 临时卷（`.volumes/`），`down` 即清
- 配置变量：`.env` 本地文件已 gitignore，不入库
