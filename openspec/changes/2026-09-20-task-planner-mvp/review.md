# 2026-09-21 审查记录

## 优化实施结果（2026-09-21）

原始审查的 12 项问题已完成代码修复与对应验证。以下原始发现保留作问题来源，不代表当前实现状态。本次没有提交、归档或升级现有业务栈。

| 原问题 | 本次处理 |
|---|---|
| 1 原始 diff 不可靠 | 新增不可变 ai_snapshot，删除/改名比较真实原始提案；旧任务不推断历史 |
| 2 多模板学错对象 | 保存参考模板 ID/版本，同分类一次调用、按模板分别验证与写回 |
| 3 手动编辑被覆盖 | 行锁 + expected_version + 版本唯一约束；持久保存最后手动编辑时间 |
| 4 重复证据只靠 prompt | 服务端验证不同任务的真实增删证据；数量/备注要求有效任务引用；new_notes 不直接写回 |
| 5 默认暴露全部网卡 | Compose 端口改为 127.0.0.1 发布，旧容器需重建才能生效 |
| 6 全部失败仍成功 | 分类错误进入重试，最终失败如实记录，日志写入失败也不返回 success |
| 7 总结并发重复 | 单 worker 下定时/手动共用互斥，force 不绕过运行中保护，重试重新检查同日成功 |
| 8 按日志条数算失败天数 | 按本地日期聚合并回溯完整连续失败段 |
| 9 过期详情/确认漏拦截 | REST/MCP 详情和确认统一拒绝过期 draft，内部取消清理保留 |
| 10 空 key 异常断流 | 客户端初始化移入 SSE 错误捕获范围 |
| 11 AI notes 未存储 | notes、子待办、原始快照同事务提交，详情可读 |
| 12 学到字段未进入拆解 | 模板 notes/count 加入后续拆解 prompt |

额外优化：完整窗口分页；few-shot/workload 并行获取；冷启动模板唯一 learning_key 防重并尊重停用；生产镜像依赖层与源码层分离、不安装 dev 依赖、排除测试/缓存；独立 TEST_DATABASE_URL 校验；E2E 显式允许写入、启用后不可达直接失败；校正文档中的回滚接口、SSE 样例、重试次数和版本保留口径。

最终验证：

- task-service：**110 passed in 27.03s**，使用独立 task_db_test；含数据库并发写回、原始快照不变、notes 持久化、过期拦截。
- agent-service：**101 passed in 2.23s**，含 health、真实 HTTP MCP 与 PostgreSQL 的 E2E，LLM 为桩；验证一条样本不学习、两条真实样本才使模板版本增加。
- Alembic：独立 tp_review_migration_test 完成 0001→0002→0001→0002，旧任务保留且快照为空，保留分类存在。
- 两个 Docker review 镜像构建成功，后续重建依赖层命中缓存；镜像内运行模块/提示词导入正常，pytest 不在生产镜像中；Compose config 校验通过。
- OpenSpec strict 通过。两侧六个核心服务模块的 mypy 检查通过；Ruff 的 E9/F63/F7/F82 正确性检查通过。未宣称整个子项目完整风格规则检查零告警。

边界：尚未调用真实 LLM；未部署新版本。总结互斥只覆盖文档限定的单进程部署，多副本需要持久执行租约；依赖版本锁定与 MCP 连接复用仍可后续优化。旧任务无法恢复丢失的原始快照，保守跳过学习。运行时停机补跑、日志持久重试/跨运行分类水位仍是后续增强范围。

## 原始审查记录

结论：服务拆分与独立部署方向合理，但目前不建议按“自学习闭环已完成”归档。主要缺口在学习证据真实性、模板归属、并发写入保护与失败可观测性。本轮只审查，未修复业务代码。

范围：proposal/design/三个 delta spec，task-planner 的拆解、总结、数据服务、MCP、Compose 与相关测试。独立单用户、无认证、无前端是明确非目标，不将缺少 family_id 或前端本身认定为本次实现缺陷。

## 需优先修复（P1）

### 1. 删除与改名的学习证据不成立

- 位置：`task-planner/agent-service/src/services/summary_collect.py:117`；`task-planner/task-service/src/services/subtask_service.py:142`；design D7。
- 实现比较“当前模板步骤 − 当前子待办”，并非“原始 AI 提案 − 用户修改结果”。子待办物理删除、改名覆盖标题后，`is_ai_generated` 无法恢复原值；改名仍保留 true，也不会被识别成新增。
- 复现：模板为“制作PPT、打印材料”，AI 原本只生成“制作PPT”，用户完全未编辑，函数仍返回删除“打印材料”。反过来，冷启动无模板时，即使用户删掉 AI 生成的“打印材料”，返回的删除列表也为空。
- 影响：无修改任务被误判成修改；真正删除漏报；模板可能基于虚假证据持续劣化。这超过了 D7 所称的“改名视为删除+新增”的已知限制，交接说明也不能代替修改规格。
- 建议：保存不可变的原始提案快照，至少包含子待办身份、原始标题、采用的模板 ID/版本。可直接在任务上用 JSONB 保存，无需为了快照额外建表。当前集合与快照比较，明确未确认、自动过期和纯手动任务是否构成学习证据。

### 2. 支持一分类多模板，但总结固定改第一个模板

- 位置：`task-planner/agent-service/src/services/summary_collect.py:168`，`_fetch_template()` 使用 `limit=1`；模板查询按创建时间升序。
- 触发：同分类有“发布会”和“工作汇报”两个模板；拆解选择第二个，总结却始终取得第一个，所有任务的 diff 及写回都落在第一个。
- 影响：第二个模板得不到学习，第一模板吸收另一业务的步骤；分类合并后更容易出现。
- 建议：记录拆解使用的模板 ID/版本，按目标模板归属证据。一分类一次 LLM 调用可继续保留，但输出需携带目标 template_id，写入前验证归属。冷启动任务另行处理。

### 3. 手动编辑保护仍可能覆盖用户刚修改的内容

- 位置：`task-planner/agent-service/src/services/summary_learn.py:229`；`task-planner/task-service/src/services/template_service.py:205`。
- 触发：采集读到 v1，等待 LLM 时用户将模板改成 v2；版本检查能发现当天手动编辑，但 `_apply_updates()` 仍以 `group.template` 的 v1 组装完整 content，然后写回。
- 复现：手动编辑标记为 true 时，MCP 写回体仍含采集时的“旧步骤、旧备注”。新步骤追加在旧内容之后，用户刚改的字段会被恢复成旧值。
- 建议：在数据服务事务内基于最新内容执行追加；携带 expected_version 做乐观并发校验，冲突时重新读取并合并。单纯在 agent 写前再读一次仍有竞争窗口。版本历史增加 `(template_id, version)` 唯一约束，并序列化版本递增，防止重复版本号。

### 4. “至少两个任务重复”仅写在 prompt，没有写回门槛

- 位置：`task-planner/agent-service/src/services/summary_learn.py:140`、`:159`、`:381`。
- 复现：只有一条任务样本，桩 LLM 返回格式合法的 add_step，`learn_category()` 仍写模板并返回 changed=true。
- `new_notes` 更可在没有合法 template_updates 时直接改 notes，绕过 action 的 reason 校验与重复证据约束。
- 现有 `test_single_incidental_change_leaves_template_untouched` 预先让模型返回空数组，只证明空更新不会写入，不能证明规则得到执行。
- 建议：输出携带证据 task_id，服务端验证至少两个不同任务及其真实 diff；不足时拒绝自动写回。注意事项也纳入有证据、有 reason 的更新通道。

### 5. 无认证服务默认发布到全部宿主机网卡

- 位置：`task-planner/docker-compose.yml:58`、`:91`。
- 当前是 `8100:8100` / `8101:8101` 形式，没有绑定 loopback；在网络和宿主防火墙允许时，其他主机可直接读写、删除任务并触发收费 LLM 调用。
- 独立 Docker 网络不能限制已发布的宿主端口；MCP 的 Host 白名单也不保护 REST API。
- 建议：单用户 MVP 默认发布到 `127.0.0.1`，确需远程访问再显式配置入口与保护。这是落实 design D10 的暴露面约束，不要求本次扩展完整认证系统。

## 其他明确问题（P2）

### 6. 所有分类学习失败，仍然记总结成功

- 位置：`task-planner/agent-service/src/services/summary_run.py:162`。
- `learn_all()` 将异常存入 outcome.error；run_summary 不检查它，直接设置 success。
- 复现：唯一分类的 LLM 抛出 `LLM unavailable`，结果为 success、attempts=1、无错误信息。
- 影响：当天普通重试被 409 拒绝，连续失败补偿无法反映 LLM 持续不可用。模板取数失败也可能被当作“没有模板”并尝试新建重复模板。
- 建议：区分“无数据/成功无变更”“部分失败”“全部失败”，至少全部失败不能写 success；保留分类错误，重试失败分类，避免重复更新已成功分类。模板读取失败应跳过并记错，不能与模板不存在混为一谈。

### 7. 总结防重是先读后写，并发请求都能通过

- 位置：`task-planner/agent-service/src/services/summary_run.py:143`；`task-planner/agent-service/src/routes/summary.py:34`。
- 复现：同时执行两个 manual、force=false 请求，两者均 success，产生两条日志。APScheduler 的 max_instances=1 只约束自己的 job，不覆盖 HTTP 触发。
- 另外 `attempt == 0` 使首次读取日志失败后的重试不再执行同日成功检查。
- 建议：运行开始原子占用执行权，定时和手动共用；MVP 可先使用进程内锁并在获得锁后复查，多进程时放到 task-service/数据库。force 可绕过已完成检查，不应绕过正在运行的互斥。

### 8. “连续三天失败”实际判断的是“三条失败日志”

- 位置：`task-planner/agent-service/src/services/summary_run.py:90`。
- 复现：三条日志全在 9 月 21 日，窗口也被移到 9 月 18 日；实现没有读取日志日期。
- 反过来，连续失败超过三天仍只回溯三天，较早的积压任务可能永久漏掉。
- 建议：按配置时区聚合每天结果，检查连续日期；明确补偿起点是连续失败段开始，还是最后一次成功水位。不要把运行条数当失败天数。

### 9. 过期 draft 仅从列表隐藏，详情仍然可读

- 位置：`task-planner/task-service/src/services/task_service.py:51`、`:98`。
- 列表使用 `_draft_visibility_filter()`；详情直接按主键获取并序列化，无过期判断，REST 和 MCP 共用此路径。
- 与 task-mgmt 要求“list and detail queries”都过滤过期 draft 不一致。旧客户端持有 ID 时，甚至仍可通过 update_task 确认已过期提案。
- 建议：详情统一可见性规则；内部清理保留不受可见性限制的读取路径；明确过期确认的拒绝语义。补列表、详情、MCP 与确认边界测试。

### 10. 未配置 API key 时，SSE 错误契约失效

- 位置：`task-planner/agent-service/src/routes/chat.py:59`。
- `_build_llm()` 在 try 外；LLMClient 构造时对空 key 抛异常。
- 复现：ASGI 请求直接抛 LLMCallFailed，没有 SSE error 帧。实际服务已开始流响应后会异常断流。
- 建议：把客户端初始化纳入同一异常处理；加入空 key 用例。当前 Compose 明确允许空 key 启动，因此这是实际默认配置路径。

### 11. AI 生成的注意事项只在 SSE 中存在

- 位置：`task-planner/agent-service/src/services/decomposition.py:103`。
- `_normalize_subtasks()` 保留 notes，proposal 也返回 notes，但 create_subtasks 入参仅含 title/description/sort_order，之后没有调用 add_note。
- 影响：用户当场看到注意事项，刷新任务详情后消失。
- 建议：使用创建结果的子待办 ID 将 notes 持久化；用提案与后续 REST 详情内容一致性测试覆盖非空 notes。

### 12. 学到的 notes 和 estimated_subtask_count 没有进入后续拆解

- 位置：`task-planner/agent-service/src/services/decomposition.py:303`；`task-planner/agent-service/src/llm/prompts/decomposition.j2:23`。
- `_template_view()` 丢掉 notes；虽然保留 estimated_subtask_count，拆解提示词却不引用它。
- 复现：模板包含“必须确认无障碍通道”和预计 7 步，渲染后的拆解提示词均不含这些内容。
- 影响：四类学习动作中，更新注意事项与预期数量只有数据库变化，对后续拆解无作用。
- 建议：把两个字段明确放入拆解上下文，数量仍受 2–8 的硬边界约束；补写回后下一次拆解的提示词验证。

## 优化及文档校准

- 总结最多拉 200 条任务且没有分页，先截断再按分类挑 10 条，会漏掉其他分类或修改幅度更大的任务。应按游标遍历窗口，再分组采样；过滤、limit 应尽量在 SQL 中执行。
- 当前每条任务详情都重建 MCP 连接并 initialize，采集串行。可增加批量读取工具，或复用 MCP 客户端连接；拆解的 few-shot 与 workload 可并行获取。先测耗时，再决定优化范围。
- 两个 Dockerfile 均先 COPY src 再安装依赖，源码改动会击穿依赖缓存；生产镜像安装了 dev 依赖并带测试。建议固定可复现依赖，分离依赖层与应用层，区分运行和测试目标。
- task-service 测试用 `DATABASE_URL` 的 setdefault 后直接 drop_all。若继承真实库 URL 会清表，应改独立 TEST_DATABASE_URL 并校验库名。不要直接用生产库注入来“修好容器内测试”。
- 端到端测试对当前 MCP 实例执行 force 总结和 draft 清理，测试隔离应提升到独立数据库/Compose 实例，不能仅靠分类唯一名。集成验收时应有环境缺失即失败的模式，避免全部 skip 却宣称验收通过。
- README 第 110 行将版本重要标记接口写成“回滚”，实际只更新 is_important。可先文档化“读取旧版本 content，再 PUT 模板产生新版本”的恢复方法；交接中的“已实现回滚接口”需要校正。
- design R7 说所有查询带 user_id 过滤，实际任务查询没有；当前单用户不构成跨用户漏洞，但不得据此宣称已经完成多用户隔离预留。后续多用户需要重新审计完整数据路径。
- 重试 spec 同时写“1/5/30 分钟”与“最多三次 attempts”，实现是首次+三次重试共四次；应统一措辞。版本保留的“最近20份加重要版本”与“总共20份、优先淘汰非重要”也需统一。
- APScheduler 使用内存 jobstore；coalesce/max_instances 不会使进程停机期间错过的任务在重启后自动补跑，当前注释对此有误导。

## 本轮验证与限制

- agent-service 执行：`PYTHONPATH=. /tmp/tp-venv/bin/python -m pytest tests -q --ignore=tests/test_mcp_smoke.py --ignore=tests/test_e2e_loop.py --ignore=tests/test_health.py` → **81 passed in 0.16s**。
- 首次包含 test_health 的运行输出 13 个通过后长时间无进展，已中断；未把健康检查计作通过，未确认停顿根因。
- 额外以直接函数调用、AsyncMock 与 ASGITransport 复现：错误 diff、单样本写回、旧快照写回、全部分类失败却成功、同日三条失败误扩窗、空 key 非 SSE 异常、并发重复总结、模板字段未注入提示词。
- 没有运行会清库的 task-service 全量测试，没有调用真实 LLM，也没有对现有 Compose 数据做 E2E 写入。数据库并发与端口外部可达性结论来自代码/配置审查，未宣称做过真实环境复现。

## 建议修复顺序

1. 先修学习数据契约：原始快照、模板 ID/版本、有效证据门槛，并同步 design/spec。
2. 再修写入正确性：事务内版本检查、手动编辑合并、总结互斥、失败状态及补偿窗口。
3. 补齐生命周期、SSE、notes 持久化和拆解上下文；收紧默认端口，校正文档并补边界回归。
