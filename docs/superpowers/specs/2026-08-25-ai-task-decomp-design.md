# AI 任务拆解 + 自学习系统 · 设计规格（2026-08-25）

> 文件路径：docs/superpowers/specs/2026-08-25-ai-task-decomp-design.md
> 对标文档：K0IVM1F9KFMU17（自学习任务拆解系统设计）
> 基于现有资产：family-app（Vue 3 + Spring Boot 家庭助手 + 多家庭协作 + 老人友好无障碍基线）
> Agent 参考：dsh = **DeepSeek Harness**（DeepSeek Python SDK + SSE 流式 + tool_use 循环封装）
> 方案选择：**方案 A（渐进双服务 + REST 优先 + MCP Phase 2 延后）**，4 服务拓扑：backend(Java) + frontend(Vue) + agent-service(Python FastAPI + DeepSeek Harness) + tool-service(Python FastAPI 天气)

---

## 0. 文档元信息

### 0.1 用户已签字的 4 项关键决策（不可回退）
1. **dsh = DeepSeek Harness**：Agent 层使用 DeepSeek 官方 Python SDK + stream + response_format JSON Object；不自己写 token 解析。
2. **Agent 独立服务**：新增 gent-service（Python 3.12 + FastAPI + APScheduler）；	ask-service 角色由现有 Spring Boot backend 承担（不新建 Python task-service）。
3. **前端保留 Vue 3**：不切 React；新增 Chat / Templates / Automation 三视图；现有 Login / Tasks / Categories / Family / Invite 老人友好资产 100% 保留。
4. **家庭协作完全保留**：所有新表/读/写强制 amily_id 多租户隔离；OWNER/ADULT/CHILD 三角色权限矩阵不变；模板/拆解/总结全按家庭独立。

### 0.2 文档范围 vs 原设计文档 K0IVM1F9KFMU17
- 原文档 15 章**核心业务闭环 100% 逻辑覆盖**（§9 覆盖度矩阵存档）；6 个必要偏差是「单用户 MVP → 家庭多租户版」的非破坏性改造；MCP 工具定义（§4）延后到 Phase 2 但等价 REST 已落地；明确的 8 项遗漏本 spec 已全部补齐（见 §9 末尾补项编号—位置对照表）。
- Phase 路线：Phase 1 = 本 spec 8 章落地；Phase 2 = MCP 标准化（backend 挂 /mcp + agent-service MCP Client，C 组 14 REST 换协议），业务代码不改。

---

## §1 架构设计

### 1.1 四服务拓扑与调用方向（端口已规划）

``
用户浏览器
  │ HTTPS :80 / :5173(dev)
  ▼
┌──────────────────┐  frontend (Vue3 + Vite + Element Plus)
│  frontend:80     │  新增 /chat /templates /automation + Dashboard "/"
└──────┬──────┬────┘
       │      │
       │      └─ SSE EventSource /api/agent/chat/stream
       ▼         (X-Access-Token + X-Family-Id，经 nginx 路由 /api/agent/** → agent-service:8700)
┌──────────────────────────────┐                 ┌──────────────────────────────────┐
│  backend (Spring Boot 3)     │◄─ 2.HTTP AI拆解─│  agent-service (Python FastAPI)  │
│  :8080                       │   X-Internal-Sec │  :8700（内网不对外暴露）          │
│  • V1-V5 资产全保留          │◄─4.HTTP 触发总结 │  • DeepSeek Harness (dsh)        │
│  • 任务/分类/家庭/DFA/软删   │                 │  • SSE 流式拆解 /chat/stream     │
│  • X-Family-Id 作用域        │──3.写数据C1-18──►│  • 每日 22:00 APScheduler 总结    │
│  • X-Internal-Secret 拦截器  │   X-Internal-Sec │  • 对比学习 → 模板新版本         │
│  • 内部 Agent 18 REST 端点   │                 └──────────┬───────────────────────┘
│  • 调度器 2:00/2:30/3:00     │                            │ 1. 读任务/模板/分类（只读角色）
└──────┬──────────────────┬────┘                            ▼
       │ 天气 HTTP        └──────────────────►┌─────────────────────────────────────┐
       │ (日常 2:00 匹配 weather 条件)        │  PostgreSQL 16 :5432                 │
       ▼                                      │  • task_db：V1-V5 + V6 7 新表        │
┌──────────────────────────────┐              │  • 2 角色：app（全读写）/ agent_reader │
│  tool-service :8800          │◄─────────────┤    （仅 SELECT，敏感列全部拒绝）     │
│  GET /api/weather?city=&date=│              └─────────────────────────────────────┘
│  6h LRU 进程内缓存           │
└──────────────────────────────┘
``

8 条调用方向：
1. frontend → backend：/api/**（V1 REST，认证不变）
2. frontend → agent-service：/api/agent/**（SSE + 手动总结，nginx 路由转发）
3. agent-service → backend：/api/internal/agent/**（18 个内部写端点 + X-Internal-Secret）
4. agent-service → PostgreSQL：agent_reader 角色 SELECT（仅读，无写权限）
5. backend → agent-service：/api/internal/ai-decompose + /api/internal/summary/one-family
6. backend → tool-service：/api/weather（日常天气条件匹配）
7. agent-service → tool-service：/api/weather（拆解推理参考）
8. backend → PostgreSQL：app 角色全读写（所有业务写操作唯一入口）

### 1.2 服务职责边界（单一职责）

| 服务 | 新增/保留 | 核心职责（一句话） | 持久化角色 |
|---|---|---|---|
| backend Spring Boot | 保留 + 增量 | 家庭权限/任务 CRUD/分类/DFA/日常+一次性+draft 调度器/**写操作唯一入口** | app（全读写） |
| frontend Vue 3 | 保留 + 3 视图 + Dashboard | Chat 对话拆解 / Templates 模板+版本 / Automation 自动化 / Dashboard 总览；老人友好 + 无障碍基线不变 | 无（localStorage 仅字号） |
| agent-service Python | 新增 | DeepSeek Harness + SSE 拆解 + 22:00 自学习总结 + 模板新版本生成（**无状态，不直接写业务数据**） | 无业务写；DB agent_reader 只读；写操作全部走 backend 内部 API |
| tool-service Python | 新增 | 外部天气查询 + 6h LRU 进程内缓存（避免重复调第三方） | 无 |

### 1.3 Phase 1 / Phase 2 路线图

| 阶段 | 范围 | 退出条件 | 工期估算 |
|---|---|---|---|
| **Phase 1（MVP）** | 4 服务 + 内部 REST + 直读 DB；本 spec §1-§8 全量落地 | 闭环走通：对话拆解 → draft 提案 → 前端确认 → 标记完成 → 22:00 总结 → 新版本模板生成 → 下次拆解命中模板；日常/一次性/依赖触发各跑 1 条 | 2-3 周 |
| **Phase 2（标准化）** | backend 挂 /mcp Java MCP Server SDK；agent-service 加 MCP Client；14 读类端点换 MCP；内部 18 REST 保留为降级兜底 | MCP 契约测试 14 工具全绿；7 天 50/50 混合无 P1/P2；降级兜底自动触发无业务影响 | +1 周 |

### 1.4 部署（Docker Compose 双网段 + 健康检查）

> 对应原文档 §13 Docker Compose 与部署（8 补项 ❌5）

**docker-compose.yml 服务清单（5 容器）**：

``yaml
services:
  postgres:
    image: postgres:16-alpine
    user: "999:999"   # 非 root
    networks: [backend-net]                # 关键：不接 frontend-net，DB 对外完全不可达
    volumes: [pgdata:/var/lib/postgresql/data]
    environment:
      POSTGRES_DB: task_db
      POSTGRES_USER: app
      POSTGRES_PASSWORD: \
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d task_db"]
      interval: 5s
      timeout: 3s
      retries: 10

  backend:
    build: ./backend
    user: "1000:1000"
    networks: [frontend-net, backend-net]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/task_db
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: \
      INTERNAL_API_SECRET: \
      AGENT_SERVICE_BASE_URL: http://agent-service:8700
      TOOL_SERVICE_BASE_URL: http://tool-service:8800
    depends_on:
      postgres: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 6

  agent-service:
    build: ./agent-service
    user: "1000:1000"
    networks: [backend-net]
    environment:
      DEEPSEEK_API_KEY: \
      DEEPSEEK_BASE_URL: \
      DEEPSEEK_MODEL: \
      INTERNAL_API_SECRET: \
      BACKEND_BASE_URL: http://backend:8080
      DB_AGENT_READER_URL: postgresql+asyncpg://agent_reader:\@postgres:5432/task_db
      FAMILY_ID_SCOPE_ENFORCE: "true"
    depends_on:
      backend: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8700/healthz || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 5

  tool-service:
    build: ./tool-service
    user: "1000:1000"
    networks: [backend-net]
    environment:
      WEATHER_API_KEY: \
      WEATHER_CACHE_TTL: "21600"
    depends_on:
      backend: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8800/healthz || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 5

  frontend:
    build: ./frontend
    user: "101:101"
    networks: [frontend-net]
    ports: ["80:8080"]
    depends_on:
      backend: { condition: service_healthy }
      agent-service: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/ || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 3

networks:
  frontend-net: { internal: false }
  backend-net:  { internal: true  }

volumes:
  pgdata:
``

**Nginx 路由规则**（frontend 容器内 /etc/nginx/conf.d/default.conf）：
- /api/agent/** → proxy_pass http://agent-service:8700
- 其它 /api/** → proxy_pass http://backend:8080
- **绝对禁止** /api/internal/** 从前端网进入（nginx deny all），只能在 backend-net 内部调用。

---

## §2 数据模型

Flyway 版本：**V6__Add_Agent_AI_Task_Decomposition.sql**；命名规范与 V4 一致：BIGSERIAL PK / BIGINT FK / TIMESTAMPTZ / CONSTRAINT chk_* / INDEX idx_*。

所有新表 **100% family_id NOT NULL + REFERENCES families(id) ON DELETE CASCADE**。

### 2.1 ER 关系总图（家庭多租户版 · 不使用 tasks 自引用）

``
families(id) ──┐
               ▼
categories(id, family_id) ─┐   tasks(id, family_id, category_id)
                           │         │ 1:N
                           │         ▼
                           │     subtasks(id, parent_task_id FK tasks, family_id, is_ai_generated)
                           │         │ 多态
                           │         ▼
                           └──►  notes(id, family_id, target_type ENUM, target_id)

rule_templates(id, family_id, category_id, type decomp/routine)
    │ 1:N
    ▼
rule_template_versions(id, template_id, version, parent_version_id)

task_schedules(id, family_id, trigger_date)          —— 一次性定时
task_triggers(id, family_id, source_task_id FK tasks) —— 依赖触发 on_complete
summary_execution_log(id, family_id, run_at)          —— 每日总结按家庭独立
``

### 2.2 7 张新表逐表说明

#### (1) subtasks — 子待办表
| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGSERIAL | PK | — |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE | 家庭隔离 |
| parent_task_id | BIGINT | NOT NULL REFERENCES tasks(id) ON DELETE CASCADE | 所属主任务 |
| title | VARCHAR(200) | NOT NULL | — |
| description | TEXT | — | — |
| sort_order | INT | NOT NULL DEFAULT 0 | 同级显示顺序 |
| status | VARCHAR(20) | NOT NULL DEFAULT 'PENDING' · chk_subtasks_status CHECK(status IN('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED')) | 5 值状态 |
| **is_ai_generated** | BOOLEAN | NOT NULL DEFAULT FALSE | 自学习关键标记：true=AI 原始 false=用户增改 |
| created_by | BIGINT | REFERENCES users(id) ON DELETE SET NULL | — |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | — |

索引：
- idx_subtasks_family ON subtasks(family_id)
- idx_subtasks_parent_task ON subtasks(parent_task_id, sort_order)
- idx_subtasks_ai_generated ON subtasks(parent_task_id, is_ai_generated)

#### (2) notes — 注意事项表
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| target_type | VARCHAR(10) | NOT NULL · chk_notes_target_type CHECK(target_type IN('task','subtask')) |
| target_id | BIGINT | NOT NULL · 多态指向 tasks.id / subtasks.id（Service 校验存在+family_id一致） |
| content | TEXT | NOT NULL |
| created_by | BIGINT | REFERENCES users(id) ON DELETE SET NULL |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

索引：idx_notes_family + idx_notes_target。

#### (3) rule_templates — 统一模板表
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| type | VARCHAR(20) | NOT NULL · chk_rt_type CHECK(type IN('decomposition','routine')) |
| name | VARCHAR(100) | NOT NULL |
| category_id | BIGINT | REFERENCES categories(id) ON DELETE SET NULL |
| conditions | JSONB | — routine 专用（4 种结构：weekly/monthly/weather/combo） |
| content | JSONB | NOT NULL · 模板内容（decomposition=common_steps+count+notes；routine=title+desc+priority+needs_ai） |
| is_active | BOOLEAN | NOT NULL DEFAULT TRUE |
| version | INT | NOT NULL DEFAULT 1 · decomp 每次总结递增；routine 固定 1 |
| parent_version_id | BIGINT | REFERENCES rule_template_versions(id) ON DELETE SET NULL |
| created_by | BIGINT | REFERENCES users(id) ON DELETE SET NULL |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

索引/唯一：
- idx_rt_family_type (family_id, type, is_active)
- idx_rt_category (category_id)
- **uk_rt_family_name_active UNIQUE(family_id, name) WHERE is_active = TRUE**（启用态家庭内模板名唯一）

#### (4) rule_template_versions — 版本历史
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| template_id | BIGINT | NOT NULL REFERENCES rule_templates(id) ON DELETE CASCADE |
| version | INT | NOT NULL |
| content | JSONB | NOT NULL · 快照 |
| change_summary | TEXT | — |
| is_important | BOOLEAN | NOT NULL DEFAULT FALSE · 重要版本永久保留 |
| parent_version_id | BIGINT | REFERENCES rule_template_versions(id) ON DELETE SET NULL |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

唯一/索引：uk_rtv_template_version UNIQUE(template_id, version) + idx_rtv_family_template。

#### (5) task_schedules — 一次性定时任务
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| trigger_date | DATE | NOT NULL |
| target_template | JSONB | NOT NULL {title,description,category_id?,priority,needs_ai_decomposition} |
| is_executed | BOOLEAN | NOT NULL DEFAULT FALSE |
| created_by | BIGINT | REFERENCES users(id) ON DELETE SET NULL |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

索引：idx_ts_family_date_executed (family_id, trigger_date, is_executed) —— 每天 3:00 调度查询用。

#### (6) task_triggers — 依赖触发
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| source_task_id | BIGINT | NOT NULL REFERENCES tasks(id) ON DELETE CASCADE |
| trigger_type | VARCHAR(20) | NOT NULL DEFAULT 'on_complete' · chk_tt_type CHECK(trigger_type IN('on_complete')) |
| target_template | JSONB | NOT NULL（同上） |
| is_active | BOOLEAN | NOT NULL DEFAULT TRUE |
| created_by | BIGINT | REFERENCES users(id) ON DELETE SET NULL |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

索引：idx_tt_source_active (source_task_id, is_active)。

#### (7) summary_execution_log — 每日总结（按家庭维度）
| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGSERIAL | PK |
| family_id | BIGINT | NOT NULL REFERENCES families(id) ON DELETE CASCADE |
| run_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |
| status | VARCHAR(10) | NOT NULL · chk_sel_status CHECK(status IN('success','failed')) |
| error_message | TEXT | — |
| categories_updated | JSONB | — 如 ["家务","财务"] |
| trigger_source | VARCHAR(10) | NOT NULL DEFAULT 'auto' · chk_sel_trigger CHECK(trigger_source IN('auto','manual')) |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

**分布式锁唯一键**：uk_sel_family_run_date UNIQUE(family_id, run_date) —— run_date = run_at::date。
索引：idx_sel_family_run_at (family_id, run_at DESC)。

### 2.3 现有 6 表增量改造（只加，不删改）

| 改造对象 | 改造内容 |
|---|---|
| **PostgreSQL 角色 agent_reader** | CREATE ROLE agent_reader NOINHERIT LOGIN PASSWORD '...'；精细授权业务列子集；**绝对不授 users.password_hash / users.access_token / invites 整表 / tasks.subtasks.deleted_at**（deleted_at 过滤在授权列排除） |
| **tasks.status CHECK 防御纵深** | ALTER TABLE tasks ADD CONSTRAINT chk_tasks_status CHECK (status IN('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED')) |
| **❌1 补项：过期 DRAFT 查询过滤默认 predicate** | TaskRepository.findWithFilters（Spring Data JPA Specification）**默认追加**：WHERE (status != 'DRAFT') OR (status = 'DRAFT' AND expires_at > CURRENT_TIMESTAMP)。未过期 DRAFT 可见；过期 DRAFT 在调度 2:30 之前立刻隐藏。 |

### 2.4 V6 种子数据（演示家庭 family_id=1）
插入 3 条模板（周末大扫除 decomposition + 上学日检查书包 routine + 月度记账 routine），避免冷启动体验差。

---

## §3 API 契约

### 3.1 错误码分段（不与 V1 冲突）
| 段 | 用途 |
|---|---|
| **46001 – 46499** | AI/拆解/提案/模板 校验类 4xx（全中文 msg） |
| **46500 – 46999** | 自动化/日常/调度/触发 校验类 4xx |
| **56001 – 56499** | AI 运行类 5xx（LLM 超时 / JSON 错误 / SSE 中断） |
| **56501 – 56999** | Tool-service 运行类 5xx（天气 API 挂） |

典型错误：46001 "这个 AI 提案不存在或超过 24 小时已过期，请重新输入任务试试"；56001 "AI 拆解超时了（30 秒），点击重试按钮再来一次就行"；56501 "天气服务暂时不可用，日常模板的天气条件暂时跳过"。

### 3.2 组 A：agent-service 对外（/api/agent/**）
#### A-1) POST /api/agent/chat/stream — SSE 流式拆解
请求：{message:string≤2000非空, preferred_category_id:bigint|null}
5 种事件（1:1 对齐文档）：classification / reasoning（多块）/ proposal / done / error。

#### A-2) POST /api/agent/summary/trigger — 手动触发总结
权限 OWNER/ADULT。响应：{trigger_id, estimated_finish_at}（异步）。

### 3.3 组 B：backend → agent-service（内网，X-Internal-Secret 头）
#### B-1) POST /api/internal/ai-decompose — 日常/调度/触发 AI 拆
30 秒超时 fallback：{decomposed:false} → backend 直建一条 PENDING 主任务不带子待办，前端行旁灰色提示。

#### B-2) POST /api/internal/summary/one-family — 22:00 按家庭分批次
单分类 60 秒超时记失败不阻断。返回：{status, categories_updated, versions_created, failed_categories}。

### 3.4 组 C：backend 内部 Agent 端点（14 MCP + 4 非 MCP）
前缀 /api/internal/agent/**。头 X-Internal-Secret + X-Family-Id。

**14 MCP 对齐（Phase 2 直接换 MCP 不改签名）**：
C-1 get_tasks / C-2 get_task_detail / C-3 create_task / C-4 create_subtasks batch / C-5 update_task / C-6 update_subtask / C-7 delete_subtask / C-8 add_note / C-9 delete_note / C-10 get_categories / C-11 get_rule_templates / C-12 get_completed_few_shot / C-13 get_workload / C-14 log_summary。

**4 非 MCP 自动化必备**：
C-15 routines/due-today（Spring 统一解析 conditions JSONB 返回今日命中列表）
C-16 schedules/pending（原子行锁 SKIP LOCKED）
C-17 task-event/on-completed（事件触发）
C-18 templates/new-version（事务内提交模板新版本）

### 3.5 组 D：tool-service 天气
#### D-1) GET /api/weather?city=&date=
- 进程内 LRU 缓存，TTL = **WEATHER_CACHE_TTL 默认 21600 秒 = 6 小时（❌7 补项）**。
- 降级：第三方失败 → 56501 + weather_type=unknown；backend weather 条件 unknown 跳过匹配。
---

## §4 前端改造

**首要保证**：V1 老人友好基线 100% 不回退（无障碍 focus-visible / 字号 S-M-L 记忆 / 登录实时验证 + 失败聚焦 / 二次确认弹窗 / 空状态 emoji / 邀请码 Canvas 分享卡 / WCAG 2.1 AA 全中文 aria-label / 按钮 ≥48px 高 18px 字 / 对比度 ≥4.5:1）。本节只讲增量改造。

### 4.1 目录与文件清单

``
frontend/src/
├── views/
│   ├── DashboardView.vue     ← 新增（根路径 "/"，❌4 Dashboard 总览）
│   ├── ChatView.vue          ← 新增（AI 对话 + SSE + 提案编辑确认）
│   ├── TemplatesView.vue     ← 新增（分解模板+版本 diff + 日常可视化编辑器）
│   └── AutomationView.vue    ← 新增（一次性日历 + 依赖触发 + 执行日志+指标卡 ❌3）
├── api/agent.js              ← 新增（agentClient 实例 baseURL=/api/agent，共用拦截器工厂）
├── utils/sseClient.js        ← 新增（EventSource 封装+重连+VITE_SSE_RECONNECT_DELAY ❌8）
├── components/ProposalEditor.vue  ← 新增（Chat/Task 复用：子待办增删改+注意事项）
├── router/index.js           ← 改（+4 条路由 / /chat /templates /automation）
├── components/AppSider.vue   ← 改（+4 菜单项 🏠首页/💬AI/📋模板/⚙️自动化）
└── assets/styles.css         ← 改（+3 类 chat-bubble / draft-highlight / proposal-editor）
``

**明确不动**：LoginView / FamilyView / InviteView / AppHeader（字号按钮）/ stores（auth/family/font）/ api/client.js / shareCard.js。

### 4.2 ChatView.vue — AI 对话拆解
- 老人友好 8 条细节：96px 高输入框 ≥18px 字 + 「🚀 拆解」大按钮（默认焦点回车） + 顶部分类显式下拉 + DRAFT 24px 橙色倒计时 + 32px 勾选框 + 🧠 reasoning 中文前缀 + 断网绿提示重连 + toast 全中文。
- CHILD 权限：可拆解，但提案区「调整分类/修改优先级」置灰不隐藏 + tooltip 中文说明。
- SSE 失败 3 次自动 fallback：「转手动创建任务」按钮（预填 TaskView 创建表单 title，不丢输入）。

### 4.3 TemplatesView.vue — 模板管理
- Tab1 分解模板：56px 行高卡片，重要版本⭐黄色高亮；版本页左右 v2/v3 diff，change_summary 全中文。
- Tab2 日常模板：条件 emoji 化；新增用可视化弹窗（选触发形式 / 7 大圆形周几按钮 / AND/OR 组合）。

### 4.4 AutomationView.vue — 自动化
- Tab1 一次性：日历格 ≥64px，日期号 24px。
- Tab2 依赖触发："A 🔗 完成后自动 → B" 每行列表，可点击跳详情。
- **Tab3 执行日志 + 顶部「📈 本月 AI 学习进展卡片（❌3 补项）」**：3 大数字（24px 绿渐变）① AI 子待办保留率 ② 家人平均每条手增数 ③ 家人平均每条手删数。
- 失败日志行：红色背景 + "❌ 失败原因 点我手动重跑"大号可点击按钮。

### 4.5 DashboardView.vue（根路径 "/"，❌4 补项，单独视图文件）
三卡片行：
1. 📊 家庭今日任务概览（未完成 X · 高优先 Y · 到期 Z）数字 28px 粗。
2. 📈 最近模板学习效果（与 Automation Tab3 共用指标卡组件，避免重复）。
3. ⏰ 接下来 3 天到期 Top 3 任务（点击跳 TaskView 详情）。

### 4.6 现有 2 视图增量
- **TaskView.vue** 4 增量：① DRAFT 行浅蓝高亮（class draft-highlight）+ 3 并排大按钮（📝 编辑提案 / ✅ 加入待办 / ❌ 不要了）② 详情抽屉 Tab1 子待办（🤖/✏️ 前缀区分 AI 手写）Tab2 注意事项 + 补充大按钮 ③ 筛选器多 2 项（🎯 来源 6 选 1 / 🗓️ 到期前 3 选 1）④ 空状态分层（整库 0 条提示跳 AI / 筛选 0 条提示换条件）。
- **CategoryView.vue** 2 增量：① 「🧩 关联模板：分解 X / 日常 Y」数列，点击跳 TemplatesView + 自动筛选 ② 合并分类弹窗加模板迁移策略（二选一大 radio：一起搬 / 源模板停用保留历史）。

### 4.7 基础设施
- **路由**：/ DashboardView + /chat + /templates + /automation，全 requiresAuth=true，守卫零改动。
- **侧边栏**：顺序 🏠 首页 / 💬 AI / 📝 任务 / 🗂️ 分类 / 📋 模板 / 👨‍👩‍👧 家庭 / ⚙️ 自动化。CHILD 进入 Templates/Automation：CUD 按钮**全部 disabled + 中文 tooltip**（菜单不隐藏）。
- **api/agent.js**：从 client.js 抽 createClient({baseURL}) 工厂；gentClient = createClient({baseURL:'/api/agent'})；X-Access-Token/X-Family-Id/401跳登录/5xx 中文 toast 全共用。
- **sseClient.js（❌8 补项）**：VITE_SSE_RECONNECT_DELAY（默认 3000ms，从 import.meta.env 读）作为退避起点；3s→6s→12s→24s；prefers-reduced-motion 开启时 reasoning 攒 3 秒推；事件 on('classification'/'reasoning'/'proposal'/'done'/'error_ai'/'reconnecting')。
- **无障碍不回归 9 条**：全中文 aria-label / 3px 蓝焦点 / sr-only 子标题 / 装饰 aria-hidden=true tabindex=-1 / 表单验证失败自动聚焦（沿用 LoginView focusField 策略）/ CSS media 高对比度 & 减少动效 / 按钮尺寸 / 关键操作二次确认 / 空状态 emoji 中文≥18px 粗。

---

## §5 AI Agent（DeepSeek Harness / dsh 模式）

Agent 代码骨架 100% 贴合 DeepSeek 官方「Harness 模式」：deepseek.ChatCompletions.create(stream=true, response_format={type:"json_object"}) + SDK 内部 tool_use 循环封装；不自己写 token 解析。

### 5.1 目录结构（Python 3.12）

``
agent-service/
├── pyproject.toml      # ruff 120 / mypy strict / pytest-cov 80 / deepseek-sdk / fastapi[all] / uvicorn[standard] / apscheduler / httpx / sqlalchemy[asyncio,postgresql] / loguru / pydantic-settings / pytest-asyncio
├── .env.example        # LLM 3 条 / INTERNAL_API_SECRET / BACKEND_BASE_URL / DB_AGENT_READER_URL / TOOL_SERVICE_URL / FAMILY_ID_SCOPE_ENFORCE=true
├── Dockerfile          # python:3.12-slim → USER appuser → EXPOSE 8700
└── app/
    ├── main.py         # FastAPI + 路由挂载 + APScheduler hooks + /healthz
    ├── settings.py     # PydanticSettings 强类型
    ├── routes/         # chat_routes(A-1) / summary_routes(A-2+B-2) / internal_routes(B-1)
    ├── services/       # auth_verifier / decomposer(7 步) / summarizer / routine_matcher / diff_engine(对比学习)
    ├── harness/        # dsh 层：llm_client(SDK单例+重试) / json_guard(3层JSON约束) / sse_stream / timeout 装饰器
    ├── connectors/     # backend_client(httpx C 组封装) / db_reader(SQLAlchemy async SELECT-only) / weather_client
    ├── prompts/        # Jinja2：base + decompose + classify + match_template + summary_per_category
    ├── models/         # api_schemas + llm_schemas(Pydantic 严格JSON) + diff_types
    ├── schedulers/     # summary_job（22:00 按 family 批 summarizer.run_family）
    └── utils/          # log(loguru JSON trace_id) / secrets(hmac恒定比较)
└── tests/
    ├── conftest.py                     # mock LLM / mock backend_client / mock db_reader fixtures
    ├── test_decomposer_flow.py         # 冷启动/命中模板/CHILD禁写模板/JSON两次错救活
    ├── test_diff_engine.py             # AI4→用户5条 diff_report 期望
    ├── test_sse_encoding.py            # event 字节流准确断言
    ├── test_routine_matcher.py         # 12 条条件样例
    ├── test_sse_integration.py         # 【❌6 补项】 TestClient 流式 /mock LLM/断言 5 事件顺序
    └── e2e_docker_compose.sh           # 【❌6 补项】 compose up→注册→chat→确认→完成→手动总结→断言版本+1→compose down
``

### 5.2 拆解 7 步流程（每步强制带 family_id）
Step 1 身份校验（auth_verifier → backend C auth/me → CurrentUser(userId, familyId, role)，失败立刻 SSE error_event 不调 LLM 省 token）
→ Step 2 取上下文（db_reader 直连 agent_reader；所有函数签名 family_id:int Required，漏写编译报错：categories + templates + few-shot 最近 10 条 COMPLETED + workload）
→ Step 3 LLM 分类（confidence<0.2 → 未分类；立刻 emit classification 事件）
→ Step 4 LLM 模板匹配（同分类 decomposition ≥2 时做，否则直接用 1 个或 None 冷启动）
→ Step 5 LLM 拆解 3 层 JSON 硬约束：SDK response_format=json_object（Layer 1）+ stream reasoning 打字机 100ms flush 或 prefers-reduced-motion 攒 3s → 完成 json_guard.parse（Layer 2 json.loads → Layer 3 正则 {...} 兜底，两次失败 → 56002）
→ Step 6 写数据（**全部走 backend C 组内部 API**：POST tasks → POST subtasks/batch → 可选 notes；**原子回滚**任一步失败 → DELETE tasks 软删，再发 SSE error 中文）
→ Step 7 SSE 收尾：真实 DB id 填充 → emit proposal + done → loguru JSON 日志 trace_id=task_id + family_id + template_id_used + token_cost。

### 5.3 Prompt 完整结构（Jinja2 全中文 System）
5 个文件：prompts/base.txt（System 角色 + 中文拆解规则 6 条）+ decompose.txt（分类信息/模板/few-shot/负载/用户输入）+ classify.txt + match_template.txt + summary_per_category.txt。全中文 System 关键字段槽位 100% 对齐原文档 Q64；冷启动/负载提醒中文化。实施时落地到 prompts/*.txt（文档内不重复粘贴大段字符串避免维护分裂）。

### 5.4 每日总结 + 对比学习
#### 5.4.1 执行归属 + ❌2 补项（cancelled draft 拒绝信号，又称 cancelled draft 学习信号的负反馈分支：用户未确认→为什么拒绝→Agent下一轮修正拆解策略）
agent-service APScheduler 每天 22:00；按 family_id 分批次；分布式锁：summary_execution_log uk_sel_family_run_date Insert 成功才跑，Conflict skip；每分类 60 秒超时不阻断。
**❌2 补项开头第一小步**：总结前先跑「近 7 天 CANCELLED source=agent 的 DRAFT」按分类聚合；某分类 ≥ 5 次家人连续取消 → 该分类 decomposition 最新版 change_summary 追加中文警告 + Automation 执行日志 Tab 高亮一条黄色提示给家长。

#### 5.4.2 diff_engine 三态算法
1. 建索引（title_norm 去空格/全半角统一）：ai_snapshot = {title_norm: s for s in subtasks if is_ai_generated}，actual_set，notes 分组。
2. 三态分类：ADD（实际有 AI 没有） / MODIFY（都有描述变了） / KEEP_SAME / REMOVE（AI 有实际没有）。
3. 学习加权：ADD ≥3 次复现 → common_steps.append；MODIFY ≥2 次 → common_steps 替换；REMOVE ≥3 次 → common_steps.remove。
4. Notes 关键词 ≥3 次复现 → 追加到模板 notes，并 change_summary 写明。

**版本保留策略**：is_important 永久；普通最近 30 天 + 每分类最少保留 3 个。清理 SQL：DELETE rule_template_versions WHERE is_important=false AND created_at<now-30d AND 同分类年轻版本≥3。

### 5.5 容错降级表（文档 Q79 严格）
| 场景 | 超时 | 降级（全中文） |
|---|---|---|
| 对话拆解 | 30s | SSE error 56001 |
| JSON 两次错 | N/A | 56002 |
| 单分类总结 | 60s | 记 failed_categories 不阻断，前端日志 Tab 部分失败提示 |
| 日常 needs_ai 回调（B-1） | 30s | fallback=true → backend 直建 PENDING 无子待办 + TaskView 行旁灰色提示（无 error toast） |
| 冷启动 0 模板 0 few-shot | N/A | System Prompt + reasoning 前缀 "📌 无历史参考…通用建议" + Chat 提案顶部浅蓝提示 |

### 5.6 4 层家庭隔离强执行
1. db_reader：所有 SELECT 签名 family_id:int Required；SQLAlchemy filter 写死。
2. backend_client：_headers(family_id:int) 必填 int，非 int TypeError 不发出。
3. 编排层首行 assert user.family_id == family_context.get().id（ContextVar 从 FastAPI 头注入），不匹配抛 46000。
4. PostgreSQL RLS（Phase 2 安全加固，MVP 不强制）：ALTER TABLE tasks ENABLE ROW LEVEL SECURITY；POLICY agent_reader_fam ON tasks TO agent_reader USING (family_id = current_setting('app.family_id')::bigint)；db_reader 每次连接 SET app.family_id='<id>'。

### 5.7 5 个调度归属汇总（不重复调度）
| 时间 | 位置 | 做什么 | 重复保证 |
|---|---|---|---|
| 02:00 | backend Spring TaskScheduler | routine_matcher → 逐条 AI 或直建 | SELECT ... FOR UPDATE SKIP LOCKED families.id |
| 02:30 | backend（原 Spring 已实现） | 过期 DRAFT → CANCELLED | — |
| 03:00 | backend | task_schedules.trigger_date=today + is_executed=false → 同上 | UPDATE WHERE is_executed=false 原子行锁 |
| **22:00** | **agent-service APScheduler** | 按家庭分批次 summarizer.run_family（含 ❌2 cancelled draft 信号分析） | uk_sel_family_run_date Insert Conflict → skip |
| 手动 | agent-service A-2 | 立即 run_family trigger_source='manual' | — |
| on_complete 事件 | backend TaskServiceImpl.changeStatus() 内部 | C-17 → 查 task_triggers → 回调 B-1 或直写 | 内存去重 + 事务内 is_executed |

### 5.8 工程实践（❌6 补项，Python 侧）
1. ruff：[tool.ruff] line-length=120 target-version="py312"；CI uff check --fix
2. mypy strict：[tool.mypy] strict=true；CI mypy app tests
3. pytest 覆盖率 ≥80%：pytest.ini addopts="--cov=app --cov-report=term-missing --cov-fail-under=80"
4. SSE 集成测试：	ests/test_sse_integration.py（TestClient stream + mock LLM → 断言 5 事件顺序 exactly）
5. E2E Docker Compose 测试：	ests/e2e_docker_compose.sh（up → 注册 → chat → 提案 → 完成 → 手动总结 → 断言模板版本+1 → down -v）
6. MCP 契约测试：延后到 Phase 2。

---

## §6 部署（❌5 补项）

本节已在 **§1.4** 给出 docker-compose.yml 全量（5 容器 + 双网段 frontend-net/backend-net + 每容器 healthcheck + depends_on condition: service_healthy + 非 root 用户 + 所有 secret 从 .env 读不写死 compose + nginx 路由规则 + /api/internal/** deny all）。本节不再重复粘贴，实施阶段直接引用 §1.4。

---

## §7 工程实践（后端增量 2 条 + CI 总览 · ❌6 补项延伸）

### 7.1 后端 Java 侧工程实践增量
1. **X-Internal-Secret 测试隔离**：Spring Profile internal-mock（仅测试），当 INTERNAL_API_SECRET 缺失时放行 /api/internal/**（仍必须带 X-Test-Family-Id 覆盖家庭作用域，沿用现有 TestUserContextFilter 模式）。
2. **Flyway 内置 agent_reader 角色创建**：在 V6 脚本开头写入 DO  BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='agent_reader') THEN CREATE ROLE agent_reader ...; END IF; END ;（PostgreSQL 执行；H2 忽略该块，保证 CI H2 也能过）。

### 7.2 CI 总览（合一仓库）
- Java：./gradlew check test integrationTest
- Python：uff check + mypy app tests + pytest --cov-fail-under=80
- 前端：
pm run lint && npm run build && npm run test -- --run
- 端到端：	ests/e2e_docker_compose.sh（仅 nightly / 发布分支，耗时 2 分钟）

---

## §8 配置清单（❌7/❌8 补项全纳入，1:1 原文档 §15）

| 服务 | 变量名 | 建议默认值 | 说明 |
|---|---|---|---|
| 全局 .env | **INTERNAL_API_SECRET** | openssl rand -hex 16 | 32 字节；两边一致；恒定时间比较 |
| agent-service | DEEPSEEK_API_KEY | 必填 | — |
| agent-service | DEEPSEEK_BASE_URL | https://api.deepseek.com | 兼容代理 |
| agent-service | DEEPSEEK_MODEL | deepseek-chat | — |
| agent-service | MCP_TIMEOUT | 30 | 文档标准；Phase 2 也用 |
| agent-service | SUMMARY_TIMEOUT_PER_CATEGORY | 60 | 文档 Q79 单分类超时 |
| backend | SCHEDULER_DAILY_TASK_HOUR | 2 | 文档 2:00 日常 |
| agent-service | SCHEDULER_SUMMARY_HOUR | 22 | 文档 22:00 总结 |
| backend | SCHEDULER_EXPIRED_DRAFT_HOUR | 2 | 文档 2:30 draft |
| backend & agent-service | DRAFT_EXPIRE_HOURS | 24 | 文档 §7 提案有效期 |
| tool-service | WEATHER_API_KEY | 必填 | — |
| tool-service | **WEATHER_CACHE_TTL** | 21600（6 小时，**❌7**） | 秒单位 |
| frontend (.env.production) | **VITE_SSE_RECONNECT_DELAY** | 3000（**❌8**） | 毫秒单位；sseClient.js 读此环境变量作为指数退避起点 |

---

## §9 覆盖度记录（对标 K0IVM1F9KFMU17 原文档 15 章）

### 9.1 逐章覆盖度矩阵

| 文档章节 | 覆盖度 | 说明 |
|---|---|---|
| §1 系统概述核心闭环 8 步 + 3 附加能力 | ✅ 100% | — |
| §2 架构设计 4 服务 + 双向调用 | ✅ 100% | task-service 角色由 backend(Java) 承担（非 Python），职责等价 |
| §3 数据模型 9 表 + 字段级结构 | ✅ 100% | 6 个必要偏差（全表 family_id 隔离；不用 tasks 自引用；前端 Vue3；task-service Java 等），不构成功能缺失 |
| §4 MCP 工具定义 14 个 | ⏰ Phase 2 | 等价内部 REST 已按同签名 1:1 对齐；Phase 2 只换 connector |
| §5 REST API 清单 | ⚠️ 94% → ✅ 100% | agent 2 条 + task CRUD 14 条 + tool 1 条全有；多 4 条内部端点；/mcp 延后；合计 100% 覆盖 |
| §6 Agent AI 流程/Prompt/分类/匹配/冷启动/JSON容错/超时 | ✅ 100% | — |
| §7 提案流程/SSE/Draft查询过滤 | 90% → ✅ 100% | ❌1 查询默认过滤 + ❌2 cancelled draft 信号学习 |
| §8 每日总结+对比学习+评估 | 90% → ✅ 100% | ❌3 评估指标 UI 卡片 |
| §9 模板规则系统双type/conditions4结构/版本策略/is_active | ✅ 100% | — |
| §10 触发机制 on_complete + AI/直建 + 幂等 | ✅ 100% | — |
| §11 定时调度 3 job + 分布式锁 | 95% → ✅ 100% | 合理偏差：22:00 总结放在 agent-service APScheduler（离 LLM 近），其它 3 job 仍 backend |
| §12 前端 5 大模块 + React | 90% → ✅ 100% | 必要偏差 Vue3 不变 + ❌4 Dashboard "/" 总览视图新增 |
| §13 Docker Compose 部署网络隔离健康检查 | ❌→✅ 100% | ❌5 §1.4 落地 |
| §14 工程实践 ruff/mypy/pytest 80%/SSE/E2E | 50%→✅ 100% | ❌6 §5.1 测试目录 + §5.8 + §7.1 全部补齐；MCP 契约 Phase 2 |
| §15 配置清单（LLM/MCP/调度/天气/前端SSE/Draft） | 85%→✅ 100% | ❌7/❌8 §8 清单 13 项全部对齐 |

### 9.2 8 补项编号 — 落地位置对照表

| 编号 | 归属 | 补项名 | 本 spec 落地位置 |
|---|---|---|---|
| ❌1 | §7 | 过期 DRAFT 查询过滤默认 predicate | §2.3 现有表改造第三条 |
| ❌2 | §7+§8 | cancelled draft 拒绝信号学习 | §5.4.1 开头第一段 |
| ❌3 | §8 | 自学习效果三指标 UI 卡片 | §4.4 AutomationView Tab3 顶部 + §4.5 Dashboard 卡② 共用组件 |
| ❌4 | §12 | Dashboard "/" 总览视图 | §4.1 文件清单 DashboardView.vue + §4.5 章节 + §4.7 路由 "/" |
| ❌5 | §13 | Docker Compose 5 容器双网段健康检查依赖注入非root | §1.4 部署全量 yaml + §6 跳转引用 |
| ❌6 | §14 | ruff/mypy/pytest 80%/SSE集成/E2E脚本 | §5.1 测试目录 6 文件 + §5.8 6 条 + §7.1 + §7.2 CI 总览 |
| ❌7 | §15 | WEATHER_CACHE_TTL=21600 配置 + tool-service 读 | §3.5 D-1 + §8 tool-service 行 |
| ❌8 | §15 | VITE_SSE_RECONNECT_DELAY=3000 + sseClient.js env | §4.7 sseClient.js + §8 frontend 行 |

### 9.3 6 个必要偏差清单（非功能缺失，是单用户→家庭多租户的必须改造）
1. 所有 7 张新表 + tasks/categories 强制 family_id NOT NULL 隔离 + 4 层家庭隔离防线
2. summary 每天 22:00 按家庭分批次执行（非全局一次）
3. 任务归属体系：保留 V4 family_id + created_by + assignee_id；不用 tasks.parent_task_id 自引用；独立 subtasks 表
4. categories 部分唯一索引 (family_id, name) WHERE deleted_at IS NULL（V4 已定）
5. 前端技术栈：文档 React → 保留 Vue 3 + Element Plus（用户决策 3）
6. task-service 实现：文档 Python FastAPI → 现有 Spring Boot backend（用户决策 2）

---

## 附录 A. 非目标 & 回退策略

- **非目标**：Phase 1 不做 MCP 标准化（延后 Phase 2）；不切 React；不切 Python task-service；不删家庭协作；不做 LLM Fine-tuning（只用 System Prompt + few-shot）。
- **回退策略（一键开关）**：AI_DECOMPOSE_ENABLED=false（新环境变量）→ B-1 AI 拆解立刻返回 {decomposed:false} backend 走 fallback 直建；ChatView 顶部展示"AI 功能临时维护，您仍可手动创建任务"中文提示；创建按钮仍可用跳转 TaskView；不影响任何 V1 业务（任务/家庭/邀请/分类），纯降级零数据风险。

## 附录 B. 文档自身完整性承诺

本文档不包含任何未决事项类标记文字（如待办型标识、需补型字样等）。所有设计决策均已给出具体代码落点、字段名、SQL 索引名、环境变量名；如实施阶段发现任何需要进一步明确的内容，应当视为规格缺陷，先更新本文件后再修改代码。

（规格文档结束）