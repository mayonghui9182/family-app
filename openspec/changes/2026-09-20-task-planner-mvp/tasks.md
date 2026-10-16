## 1. 子项目骨架与独立编排

- [x] 1.1 创建 `task-planner/` 目录骨架（`task-service/`、`agent-service/`、`docker-compose.yml`、`.env.example`、`.gitignore` 不新增、`README.md` 占位），按设计文档 §14 的层级建立 `src/{main.py,models,routes,mcp,scheduler,services,db.py}` 与 `tests/`；验证：`wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && find task-planner -type f | sort"` 输出与骨架清单一致。
- [x] 1.2 编写 `task-planner/docker-compose.yml`：postgres（独立卷 `task_planner_pgdata`）+ task-service（宿主端口 8100）+ agent-service（宿主端口 8101），统一 `container_name: task-planner-*`、独立 `networks: task-planner-net`、`TZ=Asia/Shanghai`；验证：`docker compose -p task-planner config` 无告警，且 `docker compose -p task-planner ps` 中容器名不与 family-app 既有容器冲突。
- [x] 1.3 编写 `task-planner/.env.example`（`POSTGRES_*`、`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`MCP_TASK_SERVICE_URL`、`AGENT_SERVICE_URL`、`TZ`），确认仓库既有 `.gitignore` 已忽略 `task-planner/.env`；验证：`git check-ignore -v task-planner/.env` 命中规则，`.env.example` 中无任何真实密钥。
- [x] 1.4 两个服务各自 `pyproject.toml`（FastAPI、uvicorn、SQLAlchemy、Alembic、pydantic-settings、httpx、APScheduler、`mcp` SDK 固定版本区间、pytest、pytest-asyncio），Dockerfile 以 `python:3.12-slim` 为基；验证：`docker compose -p task-planner build` 成功，容器内 `python -c "import fastapi, sqlalchemy, alembic, mcp, apscheduler"` 无 ImportError。

## 2. task-service 数据层（7 张表 + 迁移）

- [x] 2.1 实现 `db.py`（引擎/会话/`DATABASE_URL` 由 `POSTGRES_*` 组装）与 `models/`：`tasks`、`subtasks`、`notes`、`categories`、`rule_templates`、`rule_template_versions`、`summary_execution_log`，字段与枚举严格对齐设计文档 §3（含 `user_id` 默认 `"default"`、`is_ai_generated`、`expires_at`、`source`、`type`），全部中文注释；验证：`pytest task-service/tests/test_models.py` 通过（建表、默认值、枚举取值、外键级联删除各一条用例）。
- [x] 2.2 配置 Alembic（`alembic.ini` + `alembic/env.py` 读取同一 `DATABASE_URL`），生成首个迁移 `0001_init_task_planner.py` 建 7 张表并 seed 保留分类 `未分类`；**回滚策略**：迁移必须实现可用的 `downgrade()`（drop 7 张表，seed 随表删除）；验证：`alembic upgrade head && alembic downgrade base && alembic upgrade head` 三步连续成功，`GET /api/categories` 返回含 `未分类`。
- [x] 2.3 task-service 容器入口脚本先跑 `alembic upgrade head` 再 `uvicorn` 启动，失败时非 0 退出并打印错误；验证：`docker compose -p task-planner logs task-service` 可见迁移执行日志，人为改坏 `DATABASE_URL` 时容器退出且日志含明确错误。
- [x] 2.4 实现统一响应封套工具（`{"code":0,"msg":"success","data":...}`）与异常处理器（400 校验/非法状态流转、404 不存在、409 冲突）；验证：`pytest tests/test_envelope.py` 覆盖成功、校验失败、不存在三种响应形态。

## 3. task-service REST：任务与 draft 生命周期

- [x] 3.1 实现 `GET /api/tasks`（`status`/`category`/`source`/`keyword`/`due_before` 可组合过滤，创建时间倒序，过期 draft 过滤 `status != 'draft' OR expires_at > now()`）与 `GET /api/tasks/{id}`（含按 `sort_order,id` 排序的子待办、任务级与子待办级注意事项、404）；验证：`pytest tests/test_tasks_read.py` 覆盖 spec `task-mgmt` 的 6 个列表/详情场景。
- [x] 3.2 实现 `POST /api/tasks`（title 必填，`status=pending`、`priority=medium`、`source=manual`、`expires_at=null` 默认，未知 `category_id` → 400）与 `PUT /api/tasks/{id}`（部分更新 + 5 值状态机白名单流转，非法流转 400，刷新 `updated_at`）；验证：`pytest tests/test_tasks_write.py` 覆盖 spec 的默认值、缺 title、未知分类、合法/非法流转、终态不可复活共 8 个场景。
- [x] 3.3 实现 draft 提案生命周期：`draft→pending` 确认时清空 `expires_at`；draft 创建时写入 `created_at + 24h`；`DELETE /api/tasks/{id}` 物理删除并级联子待办与注意事项，`cancelled` 任务保留可查；验证：`pytest tests/test_draft_lifecycle.py` 覆盖 spec `Draft proposals expire 24 hours` 与 `Task deletion is physical and cascades` 的全部场景。

## 4. task-service REST：子待办 / 注意事项 / 分类

- [x] 4.1 实现子待办三接口（`POST /api/tasks/{id}/subtasks` 默认 `sort_order=0`、`status=pending`、`is_ai_generated=false`；`PUT /api/subtasks/{id}` 复用状态机校验；`DELETE /api/subtasks/{id}` 级联删注意事项）；验证：`pytest tests/test_subtasks.py` 覆盖 spec 的 4 个子待办场景。
- [x] 4.2 实现注意事项接口（`POST /api/tasks/{id}/notes` → `target_type=task`；`POST /api/subtasks/{id}/notes` → `target_type=subtask`；`DELETE /api/notes/{id}`；空 content 400，未知目标 404）；验证：`pytest tests/test_notes.py` 覆盖 spec 的 3 个注意事项场景。
- [x] 4.3 实现分类接口（`GET`/`POST`/`PUT` + `POST /api/categories/{id}/merge`：把源分类的任务与规则模板改挂到目标分类后删除源分类；重名 400；自合并 400）；验证：`pytest tests/test_categories.py` 覆盖 spec 的 3 个分类场景，含合并后任务与模板 `category_id` 均已变更。

## 5. task-service REST：模板与版本历史

- [x] 5.1 实现 `GET /api/templates`（`category`/`type` 可组合过滤，MVP 仅 `decomposition` 有行为）与模板创建/编辑（`common_steps`、`estimated_subtask_count`、`notes`、`is_active`）；验证：`pytest tests/test_templates.py` 覆盖 spec `Decomposition rule template persistence and retrieval` 与 `is_active=false` 排除候选的场景。
- [x] 5.2 实现版本历史：任何 `content` 变更 → `version+1` + 写 `rule_template_versions`（整份快照、`change_summary`、`parent_version_id`）；手动编辑的 `change_summary` 固定为 `用户手动编辑` 并记录手动编辑时间（供当天冲突判定）；`GET /api/templates/{id}/versions` 按 version 倒序；验证：`pytest tests/test_template_versions.py` 覆盖 spec 的快照、排序两个场景。
- [x] 5.3 实现版本保留策略（每模板保留最近 20 版，超出删最老的非重要版本；`is_important=true` 永不自动删除）与重要版本标记接口；验证：`pytest tests/test_version_retention.py` 覆盖 spec 的 21 版删除与重要版本保护两个场景。

## 6. task-service MCP Server（agent 的唯一数据通道）

- [x] 6.1 在 `POST /mcp` 挂载 Streamable HTTP MCP Server，注册任务域工具 `get_tasks`、`get_task_detail`、`create_task`（支持 `status=draft`+`expires_at`+`source=agent`）、`create_subtasks`（批量，`is_ai_generated=true`）、`update_task`、`update_subtask`、`delete_subtask`、`add_note`；工具语义直接复用 REST 的 service 层，避免两套规则；验证：MCP Inspector 或 `pytest tests/test_mcp_task_tools.py`（内存 client）确认工具可被发现且 `get_tasks` 过滤、`create_task` draft、`create_subtasks` 标记三个场景通过。
- [x] 6.2 注册分类与上下文工具 `get_categories`、`create_category`、`update_category`、`get_completed_tasks_by_category`（few-shot：返回同分类已办任务及其子待办）、`get_current_workload`（未完成数 + 优先级分布 + 即将到期数）；验证：`pytest tests/test_mcp_context_tools.py` 确认 11+2 个工具全部可发现，且 few-shot 与 workload 返回结构含 spec 要求的字段。
- [x] 6.3 注册学习闭环工具 `get_rule_templates`（按分类/type/`is_active` 过滤）、`update_rule_template`（写回 content + `change_summary`，触发 version+1 与版本行；**设计文档缺口的补充工具**）、`log_summary_execution`（写 `summary_execution_log`）；验证：`pytest tests/test_mcp_learning_tools.py` 覆盖模板写回产生新版本、日志落库两个场景。
- [x] 6.4 agent→task-service 连通性 smoke test：agent-service 侧 MCP Client 连接 `MCP_TASK_SERVICE_URL` 并完成一次 `get_categories` 往返；验证：`docker compose -p task-planner up -d` 后 `pytest agent-service/tests/test_mcp_smoke.py`（或容器内脚本）通过，日志记录工具名与耗时。

## 7. agent-service 骨架与 LLM 适配

- [x] 7.1 实现 agent-service `main.py`（FastAPI + 健康检查）、配置加载（`LLM_*`、`MCP_TASK_SERVICE_URL`、`TZ`）、JSON 结构化日志（stdout，记录 LLM 调用 prompt 摘要/耗时/token 与 MCP 调用工具名/耗时）；验证：`curl -fsS http://localhost:8101/healthz` 返回 200，日志为一行一 JSON。
- [x] 7.2 实现 LLM 适配层：DeepSeek OpenAI 兼容调用，统一 `response_format={"type":"json_object"}`、超时参数可注入、失败抛领域异常；实现 JSON 解析容错（直接 parse → 正则提取首个 `{...}` 重试一次 → 仍失败抛 `LLMOutputUnparsable`）；验证：`pytest agent-service/tests/test_llm_json.py` 用桩响应覆盖正常 JSON、带前后缀文本、完全不可解析三种输入。
- [x] 7.3 建立 prompt 模板文件（拆解 system prompt、分类 prompt、模板匹配 prompt、总结 prompt），文案对齐设计文档 §6/§8（2-8 条、优先历史模式、高负载提醒、≥2 次才更新、必带 reason）；验证：`pytest tests/test_prompts.py` 断言关键约束字符串存在且模板可渲染。

## 8. agent-service 拆解链路（POST /api/chat SSE）

- [x] 8.1 实现 `POST /api/chat` 的 SSE 骨架：`message` 空 → 400（不开流）；成功按 `classification → reasoning(逐块) → proposal → done` 顺序发事件，`data` 均为 JSON；失败只发一个 `error` 且其后不再有 `proposal`/`done`；验证：`pytest tests/test_chat_sse.py` 用桩 LLM 断言事件名顺序与 `done` 载荷 `{"task_id":...,"status":"draft_created"}`。
- [x] 8.2 实现分类环节：MCP `get_categories` → LLM 选一个分类；无匹配归 `未分类` 且绝不创建新分类；`classification` 事件含 `category` 与 `confidence∈[0,1]`；验证：`pytest tests/test_classification.py` 覆盖命中与兜底两个场景，并断言分类表数量未变。
- [x] 8.3 实现模板匹配与上下文装配：MCP `get_rule_templates`（仅 `is_active=true` 的 decomposition）→ LLM 选模板；近似匹配时 `agent_reasoning` 标注"参考模板X但有所调整"，无模板/无匹配走冷启动并标注"无历史参考"；同时装配 `get_completed_tasks_by_category` few-shot 与 `get_current_workload`（≥3 高优先级未完成时在 reasoning 提醒）；验证：`pytest tests/test_template_match.py` 覆盖 spec `Template matching` 与 `Decomposition context` 的 7 个场景。
- [x] 8.4 实现落库与提案输出：MCP `create_task(status=draft, source=agent, expires_at=now+24h)` → `create_subtasks(is_ai_generated=true)` → 发 `proposal`（`task_id/task_title/task_description/category/category_confidence/subtasks[]/agent_reasoning/expires_at`）→ 发 `done`；验证：`pytest tests/test_proposal_persist.py` 断言 task-service 中任务为 draft/agent/有 `expires_at`，子待办全部 `is_ai_generated=true` 且 `sort_order` 连续。
- [x] 8.5 实现数量与超时约束：>8 条按 `sort_order` 截断前 8 并在 `agent_reasoning` 注明；<2 条重试一次仍不足则发 `error` 且不落提案；整体 30 秒超时；LLM/MCP 失败发 `error`（文案"拆解失败，请重试"），已建 draft 不做强一致回滚（交给 24h 过期）；验证：`pytest tests/test_chat_guards.py` 覆盖 spec 的截断、重试后报错、超时、MCP 不可达、落库中途失败共 5 个场景。

## 9. agent-service 每日总结与自学习

- [x] 9.1 实现总结数据采集：窗口 = 当天 0:00 至执行时刻的新建/完成/修改任务，按任务**当前** `category_id` 分组，每分类最多 10 条有修改行为的任务（修改幅度大者优先）；对每条任务用 MCP `get_task_detail` 取 AI 原始子待办（`is_ai_generated=true`）与当前子待办集合并算 diff（用户删除/用户新增）；验证：`pytest tests/test_summary_collect.py` 覆盖窗口边界、跨分类改挂、10 条上限、diff 计算 4 个场景。
- [x] 9.2 实现按分类串行 LLM 总结：每分类一次调用、60 秒超时、输出 `{template_updates[], new_notes}`；校验 action ∈ {`add_step`,`remove_step`,`update_estimated_count`,`update_notes`} 且必填字段齐全（含 reason），未知/缺字段的条目跳过而不中断；单分类失败跳过不影响其他分类；验证：`pytest tests/test_summary_learn.py` 覆盖 ≥2 次重复才更新、单次偶发不更新、非法 action 跳过、单分类失败隔离 4 个场景。
- [x] 9.3 实现模板写回：经 MCP `update_rule_template` 应用 `add_step`（追加到 `common_steps` 末尾）/`remove_step`/`update_estimated_count`/`update_notes`，`change_summary` 列出动作与 reason；分类有修改证据但无 decomposition 模板时自动创建（`change_summary` = `每日总结自动创建`）；每分类处理完立即写回不等全部完成；验证：`pytest tests/test_template_writeback.py` 覆盖 spec `Learned updates are written back` 的 3 个场景。
- [x] 9.4 实现"用户手动编辑优先"冲突策略：模板当天被手动编辑过时只追加新步骤，不覆盖/不删除用户编辑内容，版本 `change_summary` = `自动补充（用户已手动编辑，未覆盖）`；验证：`pytest tests/test_manual_edit_priority.py` 覆盖 spec 中 `remove_step` 被抑制的场景。
- [x] 9.5 实现调度与手动触发：APScheduler 每日 22:00（server-local）执行；`POST /api/summary/trigger` 数据范围与定时一致，当天已成功执行过且未带 force 标记 → 409；每次运行经 MCP `log_summary_execution` 写一条日志（`status`/`error_message`/`categories_updated`/`trigger_source`=auto|manual）；验证：`pytest tests/test_summary_trigger.py` 覆盖 auto/manual 两种 `trigger_source` 与同日重复触发 409。
- [x] 9.6 实现失败重试与窗口补偿：运行级失败按 1min→5min→30min 退避最多 3 次，全失败记 `failed` 且不发通知；连续 3 天失败则下次成功时合并这 3 天数据窗口；每次运行顺带把 `expires_at` 已过期的 draft 置为 `cancelled`（未过期的不动）；验证：`pytest tests/test_summary_failure.py` 用桩时钟覆盖重试成功、三次耗尽、三天失败后扩窗、过期 draft 清理 4 个场景。

## 10. 集成验证与交付物

- [x] 10.1 端到端集成测试（compose 内真实 postgres + 桩 LLM）：`POST /api/chat` 建 draft → `PUT /api/tasks/{id}` 确认 pending → 子待办逐个 completed → 任务 completed → `POST /api/summary/trigger` → 断言模板 `version+1`、版本行 `change_summary` 含 reason、`summary_execution_log` 有一条 `manual/success`；验证：`pytest tests/test_e2e_loop.py` 全绿并输出断言明细。
- [x] 10.2 编写 `task-planner/README.md`：子系统定位（与 family-app 无数据交换）、目录结构、`.env` 配置项、启动/回滚命令（`docker compose -p task-planner up -d --build` / `down` / `down -v`）、curl 与 SSE 会话示例、手动触发总结示例、MVP 非目标清单；验证：按 README 命令在干净环境逐条执行可跑通冒烟（`GET /api/categories`、MCP 工具发现、`POST /api/chat`、`POST /api/summary/trigger`）。
- [x] 10.3 全量校验与治理检查：`docker compose -p task-planner run --rm task-service pytest` 与 agent-service 同等命令全绿；确认未修改 family-app 既有服务代码、既有 compose 与治理文件（`git status --porcelain` 仅出现 `task-planner/` 与 `openspec/changes/2026-09-20-task-planner-mvp/`、`.handoffs/`）；验证：命令输出作为证据附在会话中。（实际执行：compose 容器内 pytest 因 conftest 测试库指向宿主 `127.0.0.1:55432`（tp-dev-pg）而不可达，改用既定 venv 方式——task-service 104 passed、agent-service 84 passed；git status 因仓库存在其他并行变更的既有脏状态无法满足字面要求，经定向 diff 验证 family-app 既有路径零 task-planner 痕迹，本变更 tracked 触达仅 `.handoffs/current.md` 与 `handoffs/current.md`；tmp_probe 与 __pycache__ 已清理。）
- [x] 10.4 更新 `.handoffs/current.md`（本变更状态、已实现范围、未决事项、验证命令），并运行 `openspec validate --strict 2026-09-20-task-planner-mvp`；验证：validate 输出 0 error，handoff 中包含本次交接段落。（validate 输出 `Change '2026-09-20-task-planner-mvp' is valid`，exit 0；handoff 顶部已新增「39/39 任务全部完成」交接段，含已实现范围、组 10 证据、两处方法修正、验证命令与未决事项。）

## 11. 2026-09-21 审查优化

- [x] 11.1 保存不可变提案快照、模板归属、AI notes，新增 0002 可逆迁移
- [x] 11.2 按快照计算增删改名信号，多模板分组且同分类每次尝试一次 LLM
- [x] 11.3 服务端验证重复证据，new_notes 禁止直接写回，冷启动模板幂等
- [x] 11.4 模板行锁、expected_version、版本唯一约束、持久手动编辑时间
- [x] 11.5 总结互斥、失败分类重试、按日期扩窗、日志失败显式报告
- [x] 11.6 过期详情和确认拦截、空 key SSE error、模板 notes/count 注入后续拆解
- [x] 11.7 loopback 端口、测试库隔离、E2E 显式允许写入、分页采集和并行上下文
- [x] 11.8 新增边界回归；隔离库迁移往返；真实 MCP + 桩 LLM 全链路验证
- [x] 11.9 修正文档中回滚接口与 SSE 示例，同步 design/spec 和交接记录
