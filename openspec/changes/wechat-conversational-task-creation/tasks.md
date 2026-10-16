## 1. 数据库与持久化模型

- [x] 1.1 新增下一可用版本的 Flyway 迁移，一次性创建 `wechat_chat_context`、`wechat_task_conversations`、`wechat_conversation_messages`、`wechat_candidate_versions`、`wechat_task_confirmations`、`wechat_confirmation_tasks`、`wechat_message_outbox`、`family_task_experiences`、`family_task_experience_evidence`，补齐 CHECK、唯一键、以 `family_id` 开头的索引及可行的复合外键；迁移内写明回滚策略为“先关闭新入口/worker并保留有数据的表，只有未承载生产数据时才按子表到父表顺序删除”，并通过 H2 Flyway 测试、PostgreSQL 真机正向迁移和约束查询验证。
- [x] 1.2 实现 chat context、conversation、message、candidate 的枚举、JPA 实体和 family-scoped repository，验证“每绑定每家庭仅一个 active_slot”“公众号+外部消息 ID 复合幂等”“跨家庭按对象 ID 查询不可见”的 repository 测试通过。
- [x] 1.3 实现 confirmation、confirmation-task link 和 outbox 实体与 repository，验证候选版本只能确认一次、幂等键唯一、同会话 outbox 序号与 dedupe_key 唯一，以及关联记录不能引用其他 `family_id` 的测试通过。
- [x] 1.4 实现 family experience 和 evidence 实体与 repository，验证状态/版本约束、同一独立性键不重复计数、所有查询必须带 `family_id`，并运行相关 repository 测试。
- [x] 1.5 为新表增加 PostgreSQL 真机集成测试，覆盖级联删除/SET NULL、JSONB、并发 active_slot 冲突和复合家庭约束；验证失败迁移不会破坏旧 `wechat_inbound_message`、`agent_decompose_sessions` 或旧 H5 草稿读取。

## 2. 微信入站可靠接收与模式开关

- [x] 2.1 在 backend 新增 `POST /api/internal/wechat/conversation/messages` DTO、Controller 和短事务受理服务，持久化完整正文、公众号标识、openid、msg_id 与接收时间；验证首次消息成功、同公众号重复消息幂等、不同公众号相同 msg_id 可分别接收、未绑定消息仍留审计记录且接口在时限内返回。
- [x] 2.2 修改 wechat-service 的 BackendClient 和回调路由，在新模式下传递 `ToUserName` 作为公众号标识及完整正文；验证签名失败不落库、backend 确认持久化后才回 200、2.5 秒超时/持久化失败返回可重试 5xx、重复投递最终只处理一次。
- [x] 2.3 增加 `WECHAT_CONVERSATION_ENABLED`、worker/租约/重试参数和新旧模式互斥校验，所有新开关默认关闭；验证新 conversation 与旧 intake 同时开启时应用启动失败，单独关闭时旧行为和既有测试保持不变。
- [x] 2.4 为入站日志增加正文禁打、openid 脱敏哈希和接收时延/重复命中指标，验证日志断言中不出现用户原文或完整 openid，指标测试能区分成功、重复和持久化失败。

## 3. 家庭路由、会话状态机与确定性命令

- [x] 3.1 实现基于 `wechat_bind → active family_members → 未删除 families` 的实时家庭列表与选择服务，支持单家庭自动选择、多家庭编号挑战和唯一名称匹配；验证非成员家庭不泄露、同名歧义不切换、编号挑战过期失效且角色名称正确。
- [x] 3.2 实现 `wechat_chat_context` 的 route_revision、五分钟家庭选择挑战和未选家庭消息 `WAITING_FAMILY` 回放；验证选定家庭后原始任务文本只处理一次、无需重发，篡改或过期选择不会调用 AI。
- [x] 3.3 实现 conversation 状态服务及 `ACTIVE/AWAITING_CONFIRMATION/PAUSED/CONFIRMED/CANCELLED/EXPIRED` 迁移，保存 `paused_from_status` 和 24 小时可配置 TTL；验证家庭切换暂停原会话、切回恢复原状态、“新建任务”终止旧会话、超时不恢复未确认候选。
- [x] 3.4 实现确定性命令解析器，按家庭命令、会话控制、历史控制、精确确认、普通文本的固定优先级识别“新建任务/当前摘要/取消会话/历史会话/继续 N/删除 N/清空历史/确认创建 N”；验证模糊肯定、带额外修改文字和删除确认不会误触任务创建。
- [x] 3.5 在选择家庭、恢复会话、读取历史和确认前统一调用实时成员/角色守卫；验证退出家庭、家庭删除、ADULT→CHILD 角色变化在下一条消息立即生效，且旧家庭的摘要、候选和经验不会返回。

- [x] 3.6 增加默认家庭独立偏好及后续迁移，当前有效家庭优先、未选时默认家庭优先、否则按家庭 ID 升序自动进入首个有效家庭；实现“设置默认家庭 家庭名/默认家庭”，验证普通切换不改默认、设置默认不切换当前、失效安全回退、同名/非成员拒绝及原消息幂等处理。

## 4. agent-service 结构化会话契约

- [x] 4.1 在 agent-service 定义 turn 输入模型和 `ASK_CLARIFICATION/PRESENT_CANDIDATE/REFUSE_OR_HELP` 严格输出模型，限制候选 1～20 条、slot 来源、回复长度和任务字段；验证未知动作、越权工具字段、无效分类/成员引用及畸形 JSON 被 json guard 拒绝。
- [x] 4.2 新增仅受内部密钥保护的 `POST /api/internal/task-conversations/turn`，实现面向老人、简短中文的任务会话 prompt，并把历史/用户正文明确包成非系统指令；验证端点不调用任何任务写接口，经验与本轮输入冲突时本轮输入优先。
- [x] 4.3 新增 `POST /api/internal/task-conversations/summarize` 及固定摘要 schema，输出已明确事实、待确认问题、用户决定、候选概览和最后消息游标；验证摘要不能输出确认授权或任务写动作，游标与输入范围一致。
- [x] 4.4 在 backend 实现 agent conversation client 和双重 schema 校验，传入当前家庭最小信息、slot、当前候选、滚动摘要、最近约八轮消息及经验接口结果；验证请求不含 openid/密钥/其他家庭数据，超时和无效响应只产生安全回退且不推进 revision。
- [x] 4.5 为 backend 与 agent-service 增加共享契约样例或 contract tests，验证 snake_case 字段、动作枚举、候选边界、摘要游标和错误信封在两端一致。

## 5. 有序消费与候选版本编排

- [x] 5.1 实现按 `wechat_bind_id` 抢占 `wechat_chat_context` 过期租约、选择最早可处理消息和续租/接管的 CAS repository 操作；验证同一绑定并发 worker 只有一个获胜、不同绑定可并行、进程中断后租约过期可恢复。
- [x] 5.2 实现 conversation worker 主循环，把确定性命令在本地处理，只有普通任务文本才调用 agent-service，并确保数据库事务不跨 AI/微信 HTTP；用事务监测测试验证外呼期间没有活动事务。
- [x] 5.3 实现 ASK/REFUSE 动作落消息及 slot patch，严格保留来源消息和“用户原文/模型推断”标记；验证模型推断不会写入确认字段，失败重试不会重复追加同一会话消息。
- [x] 5.4 实现 PRESENT 动作校验和不可变 `wechat_candidate_versions`，原子递增 conversation revision 并转为 `AWAITING_CONFIRMATION`；验证每次修改生成新版本、旧版本不可覆盖、候选跨家庭分类或成员引用被拒。
- [x] 5.5 在 AI 调用前后比较 route_revision、conversation revision 和状态，丢弃家庭切换、取消或新消息推进后的陈旧响应；用并发测试证明家庭 A 的延迟结果不能写入家庭 B 或覆盖更新后的候选。
- [x] 5.6 实现消息处理指数退避、最大次数和 FAILED 可诊断终态，验证一次失败只影响当前消息、队列后续消息按序继续，重试不会重复候选或业务动作。

## 6. 统一任务创建与精确确认

- [x] 6.1 从现有 TaskService 创建路径抽取显式 `TaskCreationCommandService`，输入 familyId、actorUserId、source 和批量请求，复用字段、分类、执行人、最新角色及五值状态机校验；验证现有 HTTP 创建回归、CHILD 不提权、跨家庭请求全部拒绝。
- [x] 6.2 为批量命令实现“全部预校验后一次事务写入”，新任务固定 `PENDING` 且来源沿用 `AGENT`；验证第二条无效时第一条也不落库，任务通知不会在事务提交前外发。
- [x] 6.3 实现确认服务：锁定 `family_id + conversation_id`、校验当前候选 revision、重新验证成员关系、写唯一 confirmation 和 confirmation-task links，并把会话置为 `CONFIRMED`；验证 OWNER/ADULT 正常确认、最新 CHILD 策略生效、旧 revision 和模糊确认零写入。
- [x] 6.4 实现确认幂等返回：相同 `(conversation, revision)` 或 confirmation key 重试返回首次 task IDs；用并发测试和“提交成功但响应丢失”测试证明不会重复建任务。
- [x] 6.5 把任务创建结果和通知安排在事务提交后写入 outbox，验证客服发送异常、应用在提交后崩溃或重复恢复都不改变已创建任务，也不会提前通知已回滚任务。
## 7. 有序 outbox 与渐进式微信回复

- [x] 7.1 实现 outbox 入队服务和同会话单调序号，使用语义 dedupe_key 创建“处理中/追问/候选/创建结果”消息；验证重复业务动作只生成一条逻辑消息、前后序号稳定且正文进入消息历史。
- [x] 7.2 实现 outbox worker，复用现有 `/api/internal/kefu/send`，按序发送并记录 SENT/FAILED、尝试次数、微信 errcode 和下次重试时间；验证 token 失效只刷新重试一次、45015/43004 进入不可立即恢复终态、单条失败不影响任务或会话状态。
- [x] 7.3 实现可配置快速阈值和 `not_before` 进度消息：快速完成时取消未发送“正在整理”，慢请求最多按配置发送三段；用时钟测试验证顺序、取消和不进行逐 token 高频推送。
- [x] 7.4 在下一次有效入站时检测未送达最终结果并优先补发或通过被动回复提示，验证补发使用原 dedupe_key、不重复确认或创建任务。

## 8. 历史会话、滚动摘要与数据保留

- [x] 8.1 实现当前家庭最近五条历史列表、暂停/终态摘要编号和“继续 N”派生会话，验证只返回当前 `family_id`、parent_conversation_id 正确、旧候选和确认键不被复用。
- [x] 8.2 实现“删除 N/清空历史”的五分钟二次确认挑战和逻辑隐藏，验证未确认不删除、编号或家庭变化使挑战失效、删除家庭 A 不影响家庭 B。
- [x] 8.3 实现摘要触发器：每六轮、上下文阈值、暂停和终态时调用 summarize，并以 summary_through_message_id CAS 提交；验证并发新消息导致旧摘要被拒，游标断裂会从保留期内原始消息重建。
- [x] 8.4 实现上下文裁剪，只向模型提供滚动摘要和最近约八轮原始消息，slot 与候选仍从结构化列读取；验证摘要内容不能改变确认 revision、任务字段或成员授权。
- [x] 8.5 实现可配置清理作业，默认原始消息 30 天、摘要/候选 180 天，小批量删除或匿名化正文，同时保留最小确认哈希和任务关联；验证边界日期、审计保留例外及日志不含正文。
- [x] 8.6 接入现有家庭软删除与空家庭物理删除清理：清除仅指向目标家庭的当前/默认偏好及相关挑战，终止活动会话、隐藏历史、清除摘要并移除该家庭经验/证据，原文和候选进入已有小批量清理；保持现有删除权限、任务和审计规则，不删除整个微信绑定上下文。验证同一绑定其他家庭完全不受影响、失败整体回滚，以及真实 PostgreSQL 下已确认关联不阻塞空家庭退出。微信解绑与账号注销另行规划，不计入本次实现。

## 9. 家庭任务经验

- [x] 9.1 实现经验规范化、`CANDIDATE/ACTIVE/DISABLED` 版本服务和用户明确偏好/否定入口，验证明确“以后…”可带消息来源激活，否定生成停用新版本且其他家庭不可见。
- [x] 9.2 在确认事务后为已创建任务生成候选证据，只读取确认快照、实际任务和 confirmation-task link；验证未确认、已取消、已超时或纯模型推断不会增加证据。
- [x] 9.3 实现任务结果证据投影器，比较初始快照、任务当前字段/状态和最近观察指纹，幂等记录修改、完成、取消或延期；验证同一 task 不论反复编辑只贡献一个独立计数。
- [x] 9.4 实现“三个不同任务证据自动激活”与置信度/版本更新，验证两个证据仍为候选、第三个独立 task 才激活、跨家庭或重复 evidence 不计数。
- [x] 9.5 实现按适用范围、明确确认优先级、相关性、置信度和更新时间选取最多五条活动经验，并把实际 experience ID/version 写入候选快照；验证超过五条时排序稳定、停用经验不注入、家庭切换立即换成目标家庭集合。
- [x] 9.6 增加经验与本轮输入冲突测试，证明明确指定的执行人、日期或分类覆盖历史默认值，且最终候选仍通过当前家庭分类和档案校验。

## 10. 安全、配置、集成验证与切流

- [x] 10.1 在 Nginx 显式拒绝公网 `/api/internal/**`，保留 Docker 内网服务直连，并让生产环境默认内部密钥 fail-fast；验证公网 curl 无法访问内部端点、wechat-service/backend/agent-service 内网调用仍成功，`docker compose config --quiet` 通过。
- [x] 10.2 更新 application 配置、wechat-service Settings、docker-compose 和 `.env.example`，接线 conversation、worker、TTL、保留期、渐进阈值和重试参数但保持默认关闭；验证配置样例不含可用于生产的默认密钥，新旧消费模式互斥。
- [x] 10.3 增加会话队列深度、租约过期、AI 动作拒绝、确认幂等、outbox 错误码、摘要重建、跨家庭拒绝和经验激活指标及健康检查，验证测试能观察每类关键状态且指标标签不含 openid/正文。
- [x] 10.4 运行 backend 全量 Gradle 测试与 PostgreSQL 并发/迁移集成测试，核对测试报告零 failure/error；运行 agent-service 与 wechat-service 的 pytest、ruff、mypy，确认本 change 零新增错误。
- [ ] 10.5 构建全部受影响镜像并在旧 intake 关闭、新 conversation 仅测试账号开启的环境执行端到端验收：单家庭连续追问、多家庭切换、待选消息回放、候选修改、旧版本确认拒绝、重复确认、历史恢复/删除、客服失败补发和跨家庭隔离全部通过。
- [x] 10.6 验证回滚演练：关闭新入口和 worker 后已持久化消息不丢失，修复后可继续消费；如临时恢复旧 intake，确认新 worker 已完全停止且新表消息不会复制进旧队列，并记录演练结果。
- [x] 10.7 完成灰度观察清单和运维说明，确认无 RUNNING 遗留、队列/错误码/重复创建指标正常后再扩大启用范围；验证旧草稿链接仍可读取确认，且本 change 不删除旧表或旧接口。
