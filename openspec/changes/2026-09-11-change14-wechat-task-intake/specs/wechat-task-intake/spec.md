# Delta Spec: wechat-task-intake (Change 14 - 微信私聊消息自动对接 AI 拆解)

> 新增能力：已绑定用户在公众号 1v1 私聊发送一句自然语言，系统幂等受理、自动建立 ACTIVE 拆解草稿、自动调用 agent-service 拆解、经客服消息回推确认链接；**在用户于 H5 确认之前，`tasks` 表零写入**。

## ADDED Requirements

### Requirement: 系统 SHALL 提供微信入站消息的幂等受理端点

backend SHALL 提供 `POST /api/internal/wechat/inbound-message`（`X-Internal-Secret` 守护），请求体含 `openid`、`msg_id`、`content`、`received_at`。该端点 SHALL 只做「绑定校验 + 家庭归属裁决 + 落库」，SHALL NOT 同步调用 agent-service 或任何 LLM，正常响应耗时 SHALL < 200ms。相同 `msg_id` 重复提交 SHALL 幂等（`uq_wim_msg_id` UNIQUE 约束），返回 `reason=duplicate` 且 `wechat_inbound_message` 表仅保留一行。`content` 超过 200 字符 SHALL 截断入库。

#### Scenario: 首次受理入站文本
- **WHEN** wechat-service 提交已绑定且仅有单个 ADULT 家庭的用户消息 `msg_id=M1`、`content="周六带爸去复查，提前挂号"`
- **THEN** 返回 `accepted=true`，表内新增一行 `status=PENDING` 且 `content` 与原文一致

#### Scenario: 微信重推同一消息不重复处理
- **WHEN** 同一个 `msg_id=M1` 因微信侧超时被重推第二次
- **THEN** 返回 `accepted=false, reason=duplicate`，表内 `M1` 仍只有一行，不会生成第二个草稿

#### Scenario: 受理端点绝不做重活
- **WHEN** 受理端点被调用
- **THEN** 对 agent-service 与 wechat-service 的 HTTP 调用次数均为 0

### Requirement: 家庭归属 SHALL 四分支裁决，歧义时拒绝而非猜测

由于 `LoginResponse` 不含 `familyId` 且 `User` 无 `current_family_id`，backend SHALL 依 `loginByOpenid` 返回的 `families[]`（每项含 `id` 与 `role`）裁决归属，并 SHALL 按下表落 `status=SKIPPED` 与 `skip_reason`，SHALL NOT 在歧义或多家庭时自动挑选家庭：未绑定 → `not_bound`；`families` 为空 → `no_family`；唯一家庭且 `role=CHILD` → `role_denied`；唯一家庭且 `role ∈ {OWNER, ADULT}` → 受理；`families.size() ≥ 2` → `multi_family`。裁决 SHALL 在同一次查询内完成，SHALL NOT 为此新增数据库查询或新增 `users` 表列。

#### Scenario: 多家庭用户不被自动拆解
- **WHEN** 某 openid 绑定的用户同时属于 2 个活跃家庭并发来一条消息
- **THEN** 该行落 `SKIPPED/multi_family`，不创建草稿、不调用拆解，回推文案提示用户先进应用选好家庭

#### Scenario: 孩子成员被前置闸门拦下
- **WHEN** 唯一家庭角色为 `CHILD` 的用户发来消息
- **THEN** 落 `SKIPPED/role_denied` 且 agent-service 收到的调用次数为 0（不因 40302 白跑一趟）

#### Scenario: 单家庭正常受理
- **WHEN** 用户仅有 1 个活跃家庭且角色为 `ADULT`
- **THEN** 受理成功，落库的家庭归属在后续建草稿时与该唯一家庭 `family_id` 一致

### Requirement: 系统 SHALL 以表驱动轮询异步完成拆解，且不饿死既有调度任务

backend SHALL 新增 `wechat_inbound_message` 表（迁移 `V12`）作为幂等表兼队列表，`status` CHECK 恰为 `PENDING/RUNNING/DONE/FAILED/SKIPPED` 五值。异步处理 SHALL 由 `@Scheduled(fixedDelay≈5000)` 轮询 `PENDING` 行实现，SHALL NOT 引入 `@Async` 或新线程池。因 Spring 默认调度池为单线程且 backend 已有 6 处 `@Scheduled`，项目 SHALL 将 `spring.task.scheduling.pool.size` 配置为 ≥ 2（默认取 4），使 LLM 级长调用 SHALL NOT 阻塞既有调度任务。逐条处理 SHALL 互相隔离（单条异常不外溢），`attempts` SHALL 有上限（默认 3），耗尽后落 `FAILED` 并记 `last_error`。

#### Scenario: 拆解成功回写终态
- **WHEN** 一条 `PENDING` 消息被轮询捞起且拆解返回成功
- **THEN** 该行 `status` 依次经 `RUNNING` 到 `DONE`，`session_id` 指向已生成的草稿

#### Scenario: 临时失败按次数重试
- **WHEN** 拆解调用因网络异常失败且 `attempts=1`（< 3）
- **THEN** 该行回到 `PENDING` 且 `attempts` 变为 2，同批其它行不受影响

#### Scenario: 长调用不堵死既有 job
- **WHEN** 一条消息的拆解耗时 30 秒，同时 08:00 早报 job 到期
- **THEN** 早报 job 在其预期时刻正常触发（调度线程未被独占）

### Requirement: 受理成功后 SHALL 建立 ACTIVE 草稿并以 `init_task_title` 承载用户原文

对已受理的消息，backend SHALL 调用 `decomposeSessionService.createDraft("TASK_CREATE", Map.of("title", content), familyId, userId)` 建立 `ACTIVE` 草稿，其中用户原文 SHALL 落在 `init_task_title` 列（超 200 字符由既有逻辑截断）。系统 SHALL NOT 向 `agent_decompose_sessions.messages` 写入任何内容（该列全仓仅 `createDraft` 初始化为空数组，属死列，本能力不得为其引入新语义）。在用户确认（D-5）之前，`tasks` 表 SHALL 零新增行。

#### Scenario: 草稿承载原文且不动死列
- **WHEN** 原文「周六带爸去复查」被受理并建草稿
- **THEN** 草稿 `status=ACTIVE`、`init_task_title="周六带爸去复查"`、`messages` 仍为空数组

#### Scenario: 未确认不落业务表
- **WHEN** 草稿已生成并回推链接，但用户始终未点确认
- **THEN** `tasks` 表行数不变，草稿保持 `ACTIVE`，无需任何撤回动作

### Requirement: 拆解 SHALL 经 A-2 缓冲端点以草稿模式进行

agent-service SHALL 在 `POST /api/v1/chat`（A-2 缓冲式）中把请求体的 `session_id` 透传给编排器，使其与 A-1 语义对齐：`session_id` 非空时 SHALL 经 B-6 写入草稿的 `proposed_tasks` 且 `task_id` 为 `null`，SHALL NOT 直写业务表；`session_id` 为空时 SHALL 保持既有 B-1 直写行为逐字不变。backend SHALL 以 `X-Family-Id` + `X-Access-Token`（免密换取的真实登录态）调用该端点，读超时 SHALL ≥ 60 秒。响应 `code==0` 但 `subtasks` 为空 SHALL 被调用方判定为失败。

#### Scenario: 草稿模式不产生任务
- **WHEN** backend 带 `session_id=7` 调用 `POST /api/v1/chat` 且拆解成功
- **THEN** 响应 `code=0`、`data.task_id=null`、`data.subtasks` 非空，且 `tasks` 表无新增行、session 7 的 `proposed_tasks` 被写入

#### Scenario: 不传 session_id 行为不变
- **WHEN** 调用方不传 `session_id`
- **THEN** A-2 仍按 B-1 直接写业务表并返回真实 `task_id`（回归护栏）

#### Scenario: 空方案不算成功
- **WHEN** 拆解响应 `code=0` 但 `subtasks` 为空数组
- **THEN** backend 判定该条失败并走重试/`FAILED` 分支，不回推「点此确认」链接

### Requirement: 拆解结果 SHALL 经微信客服消息回推确认链接

wechat-service SHALL 新增 `POST /internal/kefu/send`（`X-Internal-Secret` 守护），接受 `{messages:[{openid, content}]}` 并逐条调用微信 `cgi-bin/message/custom/send`，逐条隔离返回结果且端点整体 SHALL NOT 返回 5xx；遇 40001/42001 SHALL 刷新 token 重试一次，遇 43004/43010 SHALL NOT 重试。backend SHALL 在 `DONE` 时推送含 `{h5}/wechat/oauth?next=/agent/draft/{id}` 的文本，在 `FAILED` 时推送不含链接的中文兜底话术。回推失败 SHALL NOT 改变入站行的终态（草稿已建成，用户仍可从应用内确认）。所有推送话术 SHALL 为中文、第二人称、SHALL NOT 出现「拆解会话/proposal/agent/错误码」等技术术语。

#### Scenario: 成功回推带深链
- **WHEN** 一条消息拆解完成，session id 为 42
- **THEN** wechat-service 收到 1 条客服消息请求，`content` 含 `/agent/draft/42`

#### Scenario: 回推失败不回滚拆解
- **WHEN** 客服消息推送时 wechat-service 不可达
- **THEN** 入站行仍为 `DONE`、草稿仍可经应用访问，仅记录 warn 日志

#### Scenario: 窗口过期静默降级
- **WHEN** 用户发消息后超过 48 小时才完成拆解，客服消息被微信拒绝
- **THEN** 系统记录 warn、不重试、不向用户报错，草稿仍为 `ACTIVE`

### Requirement: 入站能力 SHALL 双开关可控并全链路优雅降级

wechat-service SHALL 提供 `wechat_inbound_enabled`、backend SHALL 提供 `wechat.intake.enabled`，两道开关独立且默认关闭。backend 开关关闭时受理端点 SHALL 落 `SKIPPED` 留痕而非 `PENDING`；wechat-service 开关关闭时 SHALL 不发起对 backend 的受理调用。AI 功能开关（`FeatureGuard`）关闭时，异步作业 SHALL NOT 建草稿、SHALL NOT 调拆解，直接落 `SKIPPED/ai_disabled` 并回推「功能暂时关闭」话术。

#### Scenario: 关闭开关后完全静默
- **WHEN** `WECHAT_INTAKE_ENABLED=false` 时用户发来消息
- **THEN** 不产生任何 LLM 调用与草稿，微信侧得到一句中文说明，表内留下 `SKIPPED` 行可排障

#### Scenario: AI 总开关关闭
- **WHEN** 功能开关关闭 AI 期间有 `PENDING` 行被轮询到
- **THEN** 该行转 `SKIPPED/ai_disabled`，不堆积成永久 `PENDING`，用户收到「AI 拆解功能暂时关闭」文案
