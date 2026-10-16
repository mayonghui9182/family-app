# Delta Spec：Compose 部署 + 运维基线（Change 7）

## 1. 范围
本规格是「前端-ui / family-mgmt / category-mgmt / daily-task-mgmt / v6-datamodel /
backend-internal-api / tool-service-weather / summary-diff-engine」的增量补充规格。
仅覆盖部署/运维层，不改业务逻辑。

## 2. Compose 拓扑（强制）
```
5 服务 MUST 全部在同一个 docker-compose.yml 中声明。
2 网段 MUST 分离：
  - public-tier：frontend + backend（对外可达）
  - infra-tier：postgres + backend + tool-service + agent-service（内部互调）
backend MUST 双归属（同时在 public-tier 和 infra-tier）。
tool-service / agent-service MUST NOT 映射 host 端口（内部服务安全基线）。
```

## 3. 健康检查串联（强制）
```
postgres healthy → backend healthy → frontend/agent-service healthy
                             ↑
tool-service healthy (agent-service 亦依赖)
```
- frontend MUST NOT 启动完成前 backend 返回 502；通过 depends_on(condition=service_healthy) 保障
- agent-service 启动时 MUST 已可调用 backend C-19~C-24；避免启动即写入失败重试风暴

## 4. 配置项清单（强制）
.env.example MUST 声明至少以下变量（全部含中文注释说明）：
1. POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD
2. AGENT_READER_PASSWORD（PR-3，值=placeholder-agent-reader-password）
3. INTERNAL_API_SECRET（后端↔Agent双向校验）
4. BACKEND_PORT / FRONTEND_PORT
5. AGENT_SERVICE_BASE_URL / TOOL_SERVICE_BASE_URL（compose DNS 名）
6. DB_AGENT_READER_URL（agent-service 只读 DB 连接串）
7. WEATHER_API_KEY（❌7 weather 第三方）
8. DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL（LLM）
9. AI_DECOMPOSE_ENABLED（功能回退开关，默认 true）

如部署时变量为空 MUST 有合理降级：
- DEEPSEEK_API_KEY 空 → agent-service 拆解返回「建议手动创建」56001 msg；不影响 /healthz 200
- WEATHER_API_KEY 空 → tool-service weather_type=unknown 56501；不影响 /healthz 200

## 5. AI_DECOMPOSE_ENABLED 功能开关（强制）
```
当 AI_DECOMPOSE_ENABLED=false：
  1. Backend AgentProxyController 的 C-25~C-34 + /config 外所有端点 MUST 返回 Result(50310)
     msg=「AI 拆解功能已关闭，请联系家庭管理员」（中文）
  2. Frontend DashboardView / TaskView / ChatView 的 AI 按钮 MUST 不可见（CSS 隐藏）
  3. agent-service 容器 MAY 不启动（通过 compose profiles 省资源）
```

## 6. E2E 冒烟脚本（强制）
```
scripts/e2e/run_smoke.sh MUST：
  1. docker compose up -d 启动 5 服务
  2. wait_healthy 5 服务（180s 超时）
  3. 注册/登录测试家庭 → 获取 X-Access-Token + X-Family-Id
  4. 保存 baseline = C-34 metrics.version_count_30d
  5. C-32 trigger(force=true) 异步触发总结
  6. 轮询 C-33 list 直到 status=COMPLETED（60s 超时）
  7. after = 再调 C-34
  8. assert after.version_count_30d >= baseline + 1
  9. cleanup（docker compose down）
```
```
scripts/e2e/flyway_idempotent.sh MUST：
  1. 只启动 postgres
  2. ./gradlew flywayClean flywayMigrate  # 第一次
  3. ./gradlew flywayMigrate              # 第二次（幂等）
  4. 两次都 BUILD SUCCESSFUL → exit 0
```
脚本错误消息 MUST 中文；在无 docker / 无 jar / 无 bash 环境 MUST 优雅降级不崩溃。

## 7. 验收
- ❌5：`docker compose ps` 5/5 healthy ✅
- ❌6后半：VERSION_BUMPED +1 E2E exit 0 ✅
- PR-3 / PR-5：脚本各自 exit 0 ✅
- 全量回归：179+85+13+50 tests 全绿 ✅
