# Design: 工作任务计划系统 MVP（自学习任务拆解核心闭环）

## Context

动机与范围见 `proposal.md`（Why / What Changes / Non-goals），行为契约见 `specs/task-planner/{task-mgmt,ai-decomposition,template-learning}/spec.md`。本文只记录实现取向的技术决策。

当前约束：

- 仓库为 family-app（Java Spring Boot backend + Vue3 frontend + 三个 Python FastAPI 微服务，docker compose 编排，含 production/tls 覆盖层）。既有 `agent-service` 名称已被家庭 AI 模块占用。
- 完整设计来源：`bus_docs/task-planner-design/task-planner-design.html`（v1.0，86 项决策），本变更实现其 MVP 子集。
- 硬约束：中文注释；不修改既有治理文件（`CLAUDE.md`、`.harness/`、`.claude/settings.json`、`.gitignore`）；不改动 family-app 现有服务与数据库；shell 命令统一走 `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && ..."`。
- 家庭侧 `TaskStatus` 已是 `DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED` 5 值，与设计文档状态机一致，无需调整既有枚举。

## Goals / Non-Goals

**Goals:**

- 一条可端到端验证的链路：`POST /api/chat`（SSE）→ MCP 落库 draft 提案 → `PUT /api/tasks/{id}` 确认 → 执行/完成 → 22:00 总结 → 模板版本 +1。
- task-service 与 agent-service 职责边界清晰：数据与状态只在一个服务里，AI 与调度只在一个服务里。
- 独立部署单元：单独 compose project、单独 PostgreSQL 实例与数据卷、单独 `.env`，与 family-app 主栈互不干扰。
- 学习机制可回滚：任何自动模板更新都留版本历史与 change_summary。

**Non-Goals（设计层面额外边界，范围层面见 proposal）:**

- 不设计认证/授权层，只在部署层限制暴露面。
- 不引入消息队列、Redis、对象存储：MVP 全部同步调用 + PostgreSQL。
- 不做 LLM 供应商抽象层的多供应商实现，只保留单一适配点。
- 不为 `task_schedules` / `task_triggers` / `routine` 建表或写调度，避免产生死代码与死表。

## Decisions

### D1. 独立子项目而非扩展既有服务

`task-planner/` 作为仓库内独立子项目（自带 `docker-compose.yml`、`.env.example`、`README.md`），不复用 family-app 的 backend / agent-service。

- 备选：扩展 Java backend（复用现有任务模型）——被否：家庭多租户 + family_id 隔离与单用户工作场景的数据语义、模板学习机制完全不同，混入会污染既有 spec 与表结构。
- 备选：扩展既有 Python `agent-service`——被否：名称与职责冲突，既有服务面向微信/家庭场景，且会耦合两条发布节奏。
- 代价：少量基础设施重复（FastAPI 骨架、DB 会话、日志格式），可接受。

### D2. 两服务分工：有状态 task-service + 无状态 agent-service

- task-service：SQLAlchemy 模型、REST CRUD、MCP Server、Alembic 迁移。唯一持有 `task_db` 连接的服务。
- agent-service：LLM 适配、prompt 模板、MCP Client、APScheduler（22:00 总结）。不直连数据库。
- 备选：单体服务——被否：AI 调用（慢、易超时、需独立重启）与数据 CRUD（快、需稳定）混在一个进程会互相拖累；且未来 CLI / 其他 agent 复用数据层时需要稳定边界。
- 推论：**每日总结的调度放在 agent-service**（它是 AI 逻辑），task-service 不承载任何定时器；02:00 routine 调度未来也放 agent-service。

### D3. agent → 数据的唯一通道是 MCP（Streamable HTTP，`POST /mcp`）

agent-service 不直连 PostgreSQL，也不走 task-service 的 REST，而是作为 MCP Client 自动发现工具。

- 备选：直连 DB——被否：schema 知识扩散到两个服务，迁移期易破裂。
- 备选：内部 REST——被否：设计文档已定 MCP，且 MCP 工具自描述，未来 CLI/其他 agent 可直接复用同一套工具。
- **设计文档缺口修补**：文档 §4 的 15 个工具里没有「写回模板」的工具，但 §8 要求总结「立即通过 MCP 更新模板」。本变更新增 MCP 工具 `update_rule_template`（参数：`template_id`、`content`、`change_summary`；语义：递增 version + 写版本行），已在 `template-learning` spec 中作为契约固化。
- 风险面：MCP Python SDK 的 Streamable HTTP 传输仍在演进，需固定版本并先写连通性 smoke test（见 R3）。

### D4. 统一响应封套沿用 family-app 约定

所有 task-service REST 返回 `{"code":0,"msg":"success","data":...}`，错误用非 0 code + 恰当 HTTP 状态（400 校验/非法状态流转、404 不存在、409 当天已总结）。

- 备选：FastAPI 默认裸 JSON——被否：与仓库既有 spec（`category-mgmt` 等）风格不一致，未来若做统一前端/CLI 需要两套解析。

### D5. draft 过期 = 查询过滤 + 每日总结批量 cancelled

不新增独立清理定时器。列表/详情查询用 `status != 'draft' OR expires_at > now()` 过滤，22:00 总结顺带把过期 draft 置为 `cancelled` 保留学习痕迹。

- 备选：独立 APScheduler 每小时清理——被否：多一个调度点与失败面；查询过滤已保证用户视角不可见。
- 已知代价：总结连续失败时过期 draft 会滞留（但不可见），由 R5 的退避与窗口扩宽兜住。

### D6. 三段 LLM 调用而非单次大 prompt

分类 → 模板匹配 → 拆解，各自 `response_format: json_object`，各自可独立降级与观测（日志记 prompt 摘要 + 耗时 + token）。

- 备选：单次调用返回全部字段——被否：无法单独定位「分类错」还是「拆解错」，冷启动/高负载提示也难注入。
- 备选：function calling 强约束 schema——保留为后续优化，MVP 用 json_object + 正则容错（成本更低，供应商兼容更好）。

### D7. 学习证据采用不可变提案快照

任务 `ai_snapshot` JSONB 保存原始子待办 ID、标题、描述、顺序、注意事项，以及 `template_id/template_version/category_id`。MCP `create_subtasks` 在单个事务中保存子待办、notes 和快照；快照只写一次，后续改名或物理删除不修改它。原始集合与当前集合比较得到 add/remove，改名为 remove+add；不再用当前模板推测删除。没有快照的历史任务、未确认 draft 和 cancelled 不参与自动模板修改。

按任务当前分类选修改幅度最大的 10 条，再按使用的模板分组。一分类每次尝试仍只调用一次 LLM；多模板输出 `template_results[{target_key,template_updates}]`，每组只验证本组的证据。改挂分类的任务进入新分类冷启动组，不更新旧分类模板。

新增/删除步骤必须在至少两个不同任务的真实 diff 中出现；count/notes 动作携带 `evidence_task_ids`，至少两个有效修改样本，count 还验证当前步骤数与 old_value。`new_notes` 仅作观察，不直接写回。冷启动自动模板以 `cold_start:<category_id>` 唯一 learning_key 保证重复运行不重复创建；停用的模板不自动复活或重建。

### D7a. 写回并发、失败与部署边界

- 模板更新在行锁事务内检查 expected_version，冲突拒绝旧快照写回；版本表 `(template_id,version)` 唯一。`last_manual_edit_at` 持久记录手动编辑时间，避免版本淘汰丢掉当日保护证据。
- agent 单进程内锁覆盖手动和定时入口，force 不绕过运行中互斥；本版部署单 worker，扩展多副本之前必须引入数据库执行租约。
- 分类失败被隔离，但整轮标记失败并重试失败组；已成功组不重复学习，日志保留实际已更新分类。全部分类失败不能记录 success；日志写入失败明确返回 failed。
- 连续失败按本地日期聚合，同一天多次失败不等于多天；连续失败至少三天时回溯完整失败段。首次执行加三次退避重试（1/5/30 分钟），共最多四次尝试。

### D8. 模板内容用 JSONB，不拆步骤子表

`rule_templates.content = {common_steps[], estimated_subtask_count, notes}`，版本表整份快照。

- 备选：`template_steps` 子表——被否：步骤没有独立生命周期（无单独启停/统计需求），JSONB 快照 + 版本行更简单且天然支持整份回滚。

### D9. 表裁剪：MVP 建 7 张，2 张后置

建：`tasks`、`subtasks`、`notes`、`categories`、`rule_templates`、`rule_template_versions`、`summary_execution_log`。不建：`task_schedules`、`task_triggers`（随自动化触发变更再加 Alembic 迁移）。`rule_templates.type` 保留 `routine` 枚举值但不实现其行为。

### D10. 单用户与暴露面控制

`user_id` 固定 `"default"`，无认证。安全靠部署层：task-planner compose 使用独立 network，容器名与卷名统一加 `task-planner-` / `task_planner_` 前缀，宿主机端口使用独立端口段（避开 family-app 既有映射），默认仅绑定宿主 127.0.0.1，且文档明示不得直接对公网暴露。密钥（`POSTGRES_PASSWORD`、`LLM_API_KEY`）只经 `.env` 注入，`.env` 不入 git（仓库既有 `.gitignore` 已覆盖 `.env`，不修改治理文件）。

### D11. `未分类` 作为保留分类

初始化（首次启动或 Alembic data migration）时若不存在则创建名为 `未分类` 的分类，作为 LLM 无匹配时的兜底归属，避免 agent 自动建分类导致碎片化。

### D12. 子待办数量越界的确定性处理

设计文档只给了 prompt 规则（2-8 条）。实现层固定为：>8 条按 `sort_order` 截断前 8 条并在 `agent_reasoning` 注明；<2 条重试一次，仍不足则发 `error` 事件且不产出提案。选择确定性规则而非"再问一次模型"，是为了让 SSE 契约可测。

## Risks / Trade-offs

- **R1 命名与编排冲突**（task-planner 的 `agent-service` 与 family-app 既有 `agent-service` 同名）→ 独立 compose project name（`-p task-planner`）、独立 network、容器名/卷名加前缀、独立端口段；CI 与文档中的 compose 命令一律显式带 `-p task-planner -f task-planner/docker-compose.yml`。
- **R2 自动学习把模板改坏（漂移/劣化）**→ ≥2 次重复模式阈值；每次更新留版本快照与 change_summary；`is_active=false` 可停用；用户当天手动编辑则只追加不覆盖；版本保留 20 份可回滚。
- **R3 MCP SDK / Streamable HTTP 兼容性**→ `pyproject.toml` 固定 SDK 版本区间；实现顺序上先做「工具发现 + `get_categories` 往返」smoke test 再写业务逻辑；失败时 agent 统一发 `error` 事件，不影响 task-service REST 可用性。
- **R4 LLM 不可用或超时导致拆解失败**→ 30 秒总超时 + `error` 事件；已创建的 draft 不做强一致回滚，靠 24h 过期机制收敛（spec 已固化该行为）。
- **R5 总结连续失败使学习停摆、过期 draft 堆积**→ 1/5/30 分钟三次退避；连续 3 天失败后下次成功扩宽窗口合并总结；过期 draft 在查询层始终不可见。
- **R6 无前端，验收依赖接口**→ 提供 `task-planner/README.md` 中的 curl / SSE 示例与 pytest 端到端用例作为验证证据；前端另立变更。
- **R7 单用户假设被误用为多用户**→ 数据模型保留 `user_id`，当前仅任务表预留 `user_id`，MVP 固定 default；不宣称现有查询已有多用户隔离，未来认证与隔离必须单独设计。
- **R8 与家庭侧概念混淆**（两边都有 tasks/categories/AI 拆解）→ 目录、数据库、端口、compose project 全隔离；README 首段明确"本子系统与 family-app 主应用无数据交换"。

## Migration Plan

**首次部署**

1. 复制 `task-planner/.env.example` 为 `task-planner/.env`，填 `POSTGRES_PASSWORD`、`LLM_API_KEY`（不提交 git）。
2. `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/task-planner && docker compose -p task-planner up -d --build"`。
3. task-service 容器入口先执行 `alembic upgrade head` 再启动 FastAPI（建 7 张表 + seed `未分类` 分类）。
4. 冒烟验证：`GET /api/categories` 返回封套；MCP 工具发现列出全部工具；`POST /api/chat` 收到 `classification → reasoning → proposal → done`；`POST /api/summary/trigger` 生成一条 `summary_execution_log`。

**回滚**

- 停用：`docker compose -p task-planner down`（数据卷保留，可再次 up 恢复）。
- 彻底清理：`docker compose -p task-planner down -v`（删除 `task_db` 数据卷）。
- family-app 主栈不需要任何回滚动作——本变更不触碰其代码、镜像与数据库。

**后续演进（不在本变更）**

- 自动化触发变更：新增 `task_schedules`、`task_triggers` 表 + 02:00 routine 调度 + tool-service 天气。
- 前端变更：React（或按仓库习惯改 Vue）界面接入既有 REST/SSE。
- 多用户变更：`user_id` 由固定值改为认证主体。

## Open Questions

- 22:00 的时区基准：容器 `TZ` 未显式设置时以 UTC 运行，与"当天 0:00 至执行时刻"的窗口口径需要在实现时统一（spec 用 server-local time 表述，两种口径都满足）；建议在 `.env.example` 增加 `TZ=Asia/Shanghai` 并在实现期确认。
- DeepSeek 的具体模型档位与单次总结的 token 预算上限：不影响契约，实现期按成本调。
- 模板"标记重要版本"的 REST 路径形态（`PUT /api/templates/{id}/versions/{vid}` 还是模板级 PATCH）：行为已在 spec 固化，路径实现期定。
