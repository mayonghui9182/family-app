# task-planner（工作任务计划系统 · MVP）

> 工作隔离改造：数据按真实登录用户归属，REST/MCP/AI 请求均核验主后端身份。旧 default 数据隔离保留，需显式认领；不再提供无认证共享工作区。推荐使用仓库根 Compose。独立编排需配置可达的 BACKEND_BASE_URL 和 INTERNAL_API_SECRET。详见 `../docs/personal-daily-planning-runbook.md`。

family-app 仓库内的**独立子项目**，与家庭侧既有服务无数据交换、无共享网络与数据卷。

- `task-service/`（:8100）：有状态。REST API + MCP Server + Alembic 迁移，唯一连接 `task_db` 的服务。
- `agent-service/`（:8101）：无状态。LLM 调用、prompt 装配、MCP Client、每日 22:00 总结调度。

数据只有一条通道：agent-service 经 MCP 访问 task-service，不直连数据库。

## 目录结构

```
task-planner/
├── docker-compose.yml        # 独立编排（project 名 task-planner，独立网络与数据卷）
├── .env.example              # 环境变量样例（复制为 .env 后填写）
├── task-service/             # 有状态服务：REST + MCP Server + 迁移
│   ├── alembic/              # 数据库迁移（0001_init_task_planner）
│   ├── src/
│   │   ├── models/           # SQLAlchemy 模型（task/subtask/category/rule_template/note/summary_log）
│   │   ├── schemas/          # Pydantic 出入参
│   │   ├── routes/           # REST 路由（tasks/subtasks/notes/categories/templates）
│   │   ├── services/         # 业务逻辑 + 统一封套
│   │   └── mcp/server.py     # MCP Server（21 个工具，agent-service 唯一数据通道）
│   ├── tests/                # pytest（容器内运行）
│   └── Dockerfile
└── agent-service/            # 无状态服务：AI 拆解 + 每日总结
    ├── src/
    │   ├── llm/              # LLM 客户端 + Jinja2 prompt 模板（classification/template_match/decomposition/summary）
    │   ├── mcp_client/       # MCP Client（streamable HTTP）
    │   ├── routes/           # POST /api/chat（SSE）、POST /api/summary/trigger
    │   ├── services/         # 拆解编排、总结采集/学习/写回/运行
    │   └── scheduler/        # 每日 22:00 总结调度（APScheduler）
    ├── tests/                # pytest（含端到端集成测试 test_e2e_loop.py）
    └── Dockerfile
```

## 配置（.env）

```bash
cp .env.example .env
```

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `tp` / `tp` / `task_db` | 数据库凭据（仅 task-service 使用） |
| `TASK_SERVICE_PORT` / `AGENT_SERVICE_PORT` | `8100` / `8101` | 宿主端口映射 |
| `LLM_API_KEY` | （空，**必填**） | DeepSeek API Key |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI 兼容端点，可指向本地桩服务 |
| `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `MCP_TASK_SERVICE_URL` | `http://task-service:8100/mcp` | agent→task 的 MCP 地址（容器网络内） |
| `AGENT_SERVICE_URL` | `http://agent-service:8101` | agent 对外地址（预留） |
| `TZ` | `Asia/Shanghai` | 每日总结 22:00 以容器本地时区为基准 |
| `LOG_LEVEL` | `INFO` | 结构化日志级别 |

`.env` 已被仓库 `.gitignore` 忽略，不得提交任何真实密钥。

## 启动与回滚

```bash
# 启动（首次构建约 2-3 分钟）
docker compose -p task-planner up -d --build

# 查看状态（三个容器均应为 healthy）
docker compose -p task-planner ps

# 停止（保留数据卷）
docker compose -p task-planner down

# 彻底回滚（删除数据卷，数据库清空）
docker compose -p task-planner down -v
```

容器名统一 `task-planner-*` 前缀，网络 `task-planner-net`，数据卷 `task_planner_pgdata`，与 family-app 既有编排（`familyapp-*`）完全隔离。

## 冒烟验证

```bash
# 1. 存活探针（两个服务）
curl http://localhost:8100/healthz
curl http://localhost:8101/healthz

# 2. REST 读接口（统一封套 {"code":0,"msg":"success","data":...}）
curl http://localhost:8100/api/categories

# 3. SSE 拆解会话（需已配置 LLM_API_KEY；-N 关闭缓冲以实时看事件流）
curl -N -X POST http://localhost:8101/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "帮我准备下周三的产品发布会"}'

# 4. 手动触发每日总结（force=true 跳过"当天已运行"拦截）
curl -X POST http://localhost:8101/api/summary/trigger \
  -H 'Content-Type: application/json' \
  -d '{"force": true}'
```

MCP 端点位于 `http://localhost:8100/mcp`（streamable HTTP 协议），由 agent-service 作为客户端消费，不适合直接 curl；工具发现可通过 `agent-service/tests/test_mcp_smoke.py` 验证。

## API 概览

### task-service REST（:8100，前缀 /api，统一封套）

所有响应包裹为 `{"code": 0, "msg": "success", "data": ...}`；业务错误 `code != 0`，HTTP 状态码语义保留。

| 资源 | 端点 |
|------|------|
| 任务 | `GET /api/tasks`（过滤 status/category）、`GET /api/tasks/{id}`、`POST /api/tasks`、`PUT /api/tasks/{id}`、`DELETE /api/tasks/{id}` |
| 子待办 | `POST /api/tasks/{task_id}/subtasks`、`PUT /api/subtasks/{id}`、`DELETE /api/subtasks/{id}` |
| 备注 | `POST /api/tasks/{task_id}/notes`、`POST /api/subtasks/{id}/notes`、`DELETE /api/notes/{id}` |
| 分类 | `GET /api/categories`、`POST /api/categories`、`PUT /api/categories/{id}`、`POST /api/categories/{id}/merge` |
| 模板 | `GET /api/templates`（过滤 type/category/is_active）、`POST /api/templates`、`PUT /api/templates/{id}`、`GET /api/templates/{id}/versions`、`PUT /api/templates/{id}/versions/{version_id}`（重要版本标记） |

任务状态机（5 值，白名单迁移）：`draft → pending/cancelled`；`pending → in_progress/completed/cancelled`；`in_progress → completed/cancelled`；`completed`/`cancelled` 为终态。AI 拆解产出 `draft`（带 24h `expires_at`），用户确认转 `pending` 后清空过期时间；过期 draft 由每日总结自动 `cancelled`。

### task-service MCP（:8100/mcp，21 个工具）

按用途分组：

- **任务读写**：`get_tasks`、`get_task_detail`、`create_task`、`update_task`、`create_subtasks`、`update_subtask`、`delete_subtask`、`add_note`
- **分类**：`get_categories`、`create_category`、`update_category`
- **拆解上下文**：`get_completed_tasks_by_category`（few-shot 样例）、`get_current_workload`（当前负载）
- **模板**：`get_rule_templates`、`create_rule_template`、`get_template_versions`、`update_rule_template`
- **总结通道**：`get_tasks_changed_since`（窗口内变动任务）、`log_summary_execution`、`get_summary_executions`、`cancel_expired_drafts`

### agent-service（:8101）

| 端点 | 说明 |
|------|------|
| `POST /api/chat` | SSE 拆解会话。请求体 `{"message": "..."}`；事件序 `classification → reasoning(逐块) → proposal(完整 JSON) → done`，失败发 `error` 事件。**响应为 text/event-stream，无封套** |
| `POST /api/summary/trigger` | 手动触发每日总结。请求体 `{"force": true}` 可选（缺省 false 时当天已运行则 409）。**响应为裸 JSON**：`{status, trigger_source, categories_updated, error_message, attempts, window_since, cancelled_drafts}` |
| `GET /healthz` | 存活探针 |

每日 22:00（容器时区）自动触发总结；失败按 60s/300s/1800s 退避重试 3 次，每次运行恰好写一条执行日志。

## SSE 会话示例输出

```text
event: classification
data: {"category": "产品发布", "confidence": 0.92}

event: reasoning
data: {"chunk": "先确定演示主题..."}

event: proposal
data: {"task_id": "...", "task_title": "准备产品发布会", "task_description": "...", "category": "产品发布", "category_confidence": 0.92, "subtasks": [{"title": "确定主题", "description": "...", "sort_order": 1, "notes": ""}, {"title": "制作材料", "description": "...", "sort_order": 2, "notes": ""}], "agent_reasoning": "...", "expires_at": "..."}

event: done
data: {"task_id": "...", "status": "draft_created"}
```

`proposal` 中的任务以 `draft` 状态落库，24h 内需 `PUT /api/tasks/{id}` 传 `{"status": "pending"}` 确认，否则由每日总结自动取消。

## 数据升级与学习约束

- 新部署自动执行 Alembic `0002`：新增任务 `ai_snapshot`、模板 `last_manual_edit_at` 和冷启动模板 `learning_key`，模板版本增加唯一约束。若旧库已有重复版本号，迁移会失败而不会静默删除版本，请先人工核对历史。
- 原始提案、子待办和 AI 注意事项一起提交。没有原始快照的旧任务不自动学习，不用当前模板推测历史删除。
- 每分类每次尝试一次 LLM 调用，同分类多个模板分别接收证据。新增/删除必须在至少两个不同任务的真实 diff 中出现；数量/注意事项更新还需有效的 `evidence_task_ids`。`new_notes` 不直接写回。
- 模板自动写回携带 `expected_version`，版本冲突则重新采集；当天手动编辑仅允许追加。自动创建的冷启动模板有幂等键，重复总结不会反复创建同分类模板。
- 总结失败会重试失败分组，保留已成功写入的分类列表；未成功保存执行日志时返回 failed。定时与手动入口共用进程内互斥，运行期间即使 force 也返回 409。
- 本版只支持一个 agent-service 进程/worker；部署多副本前需要数据库执行租约。内存调度不会在重启后补跑停机期间的 22:00 任务，可手动触发。
- Compose 默认只发布到宿主 `127.0.0.1`。旧容器须重新创建才应用端口绑定，新代码须升级迁移后再启动；本轮开发验证没有自动升级现有业务栈。
- 模板恢复方式：读取 `GET /api/templates/{id}/versions` 的旧 content，再通过 `PUT /api/templates/{id}` 提交三个内容字段，生成一个新版本。版本标记接口只管理 `is_important`，不是回滚接口。

## 本地测试

运行镜像只安装运行依赖，不包含 pytest。分别在两个服务目录建立虚拟环境并安装 `pip install -e '.[dev]'`，避免两个名为 `src` 的包相互覆盖。

```bash
# 在 task-service 目录执行；夹具会清空测试库，只允许库名以 _test 结尾
TEST_DATABASE_URL=postgresql+asyncpg://tp:tp@127.0.0.1:55432/task_db_test PYTHONPATH=. python -m pytest -q

# 在 agent-service 目录执行，不需要真实数据服务的单元测试
PYTHONPATH=. python -m pytest -q --ignore=tests/test_mcp_smoke.py --ignore=tests/test_e2e_loop.py

# 完整集成测试：先在隔离的 *_test 数据库迁移并启动 task-service（示例端口18100）
# 此测试会执行 force 总结和过期草稿清理，禁止连接真实业务实例
TP_E2E_ALLOW_WRITES=1 MCP_TASK_SERVICE_URL=http://127.0.0.1:18100/mcp PYTHONPATH=. python -m pytest tests -q
```

`TEST_DATABASE_URL` 独立于业务 `DATABASE_URL`，未设置时仅使用默认开发测试库。E2E 未显式允许写入时跳过；显式启用后，服务不可达会失败，不能用 skip 作为集成验收成功。

## MVP 非目标

以下内容**不在本变更范围**，详见 `openspec/changes/2026-09-20-task-planner-mvp/proposal.md`：

- 三类自动化触发（routine 日常模板 / task_schedules 一次性定时 / task_triggers 依赖触发）及 02:00 日常任务生成调度
- React 前端（8 页）与 CLI 客户端——MVP 通过 SSE/REST 直接会话
- tool-service 天气能力（随自动化触发变更落地）
- 多用户、认证、family_id 租户隔离、微信生态集成——本子系统为单用户 MVP，仅预留 `user_id` 字段
- 修改 family-app 现有服务（backend / agent-service / frontend / wechat-service / tool-service）的任何代码或数据库
- routine 型模板的 conditions 字段管理——数据模型预留 `type` 枚举，MVP 只实现 decomposition 型行为
