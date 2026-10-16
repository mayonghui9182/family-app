# Proposal: Change 14 - 微信私聊消息自动对接 AI 拆解

## Why

家里老人不会用 App，但天天用微信。现状是：想用 AI 拆解任务，必须在 App 里登录 → 进「AI 拆解」页 → 打字 → 等 SSE 出方案 → 点确认，五步，老人独立完成不了；而 Change 11/12 已经打通了「openid → 免密换 token」的身份链，公众号也已经是家庭成员的常驻入口。

本 change 把入口从 App 挪进微信对话：**已绑定的用户在公众号里发一句自然语言，系统自动建 ACTIVE 草稿、自动调用 agent-service 拆解、自动把「点此确认」链接用微信客服消息推回来，用户在 H5 页面点一下就生成正式任务**。全链路唯一的用户动作是「发一句话 + 点一次确认」。

同时消解原始需求「监控微信群聊消息」的两处不可行性：个人微信群聊无官方读取 API（技术不可能）、把非家庭成员发言入库属合规黑洞（法律不该做）。收敛后为 **1v1 私聊**，且「用户主动私聊」本身就是意图声明，原需求里的「过滤与任务无关的消息」自动降级为「只处理文本消息」，无需二分类前置。

## What Changes

### 数据模型（backend）

- 新增 Flyway 迁移 **`V12__Add_Wechat_Inbound_Message.sql`**，建表 `wechat_inbound_message`：幂等表与队列表二合一。列含 `id`、`openid`（VARCHAR，不建外键，未绑定 openid 也要留痕）、`msg_id`（微信 MsgId，**UNIQUE 约束 `uq_wim_msg_id`**，微信重推靠它去重）、`content`（TEXT，用户原文，超 200 字符截断）、`received_at`、`status`（CHECK IN `PENDING/RUNNING/DONE/FAILED/SKIPPED`）、`session_id`（可空，指向 `agent_decompose_sessions.id`）、`skip_reason`、`attempts`、`last_error`、`created_at`、`updated_at`；活跃队列部分索引 `idx_wim_pending`（`status='PENDING'`）。
- `agent_decompose_sessions` 表结构不变。草稿标题列 `init_task_title` 承载用户原文，**不给 `messages` JSONB 死列引入任何新写入方**。

### 后端（backend）

- 新增内部端点 `POST /api/internal/wechat/inbound-message`（`X-Internal-Secret` 守护，与 `WechatInternalController` 同族）：只做「绑定校验 + 幂等落库」，返回 `accepted / not_bound / duplicate` 三态，毫秒级，**绝不同步调 LLM**。
- 新增 `WechatIntakeService`：家庭归属裁决（0 个家庭 / 1 个家庭 / ≥2 个家庭 / 唯一家庭角色为 CHILD 四分支）→ `decomposeSessionService.createDraft("TASK_CREATE", Map.of("title", content), familyId, userId)`。
- 新增 `WechatIntakeJob`：`@Scheduled(fixedDelay = 5000)` 轮询 `PENDING` 行，`attempts` 上限 3，终态一律回写 `status`。
- `AgentCallbackClient` 新增方法 `decomposeDraft(familyId, accessToken, message, sessionId)`，POST agent-service **A-2** `/api/v1/chat`，读超时 60 秒（LLM 往返），带 `X-Family-Id` + `X-Access-Token`。
- 新增 `WechatKefuClient`（或复用 `WechatNotifyClient` 同形态）：POST wechat-service `/internal/kefu/send`，失败只记 warn、绝不抛出。
- 新增 `GET /api/agent/v1/decompose/sessions/{id}`（**D-8**，family 作用域），供免密直达的 H5 详情页服务端恢复。
- 配置：`spring.task.scheduling.pool.size` 由默认 1 扩到 4；新增 `wechat.intake.enabled` 总开关。

### 微服务（agent-service）

- **A-2 `POST /api/v1/chat` 补 `session_id=body.session_id` 一行透传**，修复其与 A-1 的既有不对称；`session_id` 为空时行为完全不变（向后兼容）。`run_buffered` 不动。

### 微服务（wechat-service）

- `POST /wechat/callback` 不再是占位：验签 → 明文 XML 解析 → 仅处理 `MsgType=text` → 同步调 backend 受理端点 → 按结果被动回复文本；其余 `MsgType`/`Event` 记 warn 后回空串。
- 新增 `wechat/kefu.py`：`send_text(client, manager, openid, content)` 调 `cgi-bin/message/custom/send`，复用 `WechatTokenManager`（含 40001/42001 刷新重试一次、43004/43010 不重试）。
- 新增 `POST /internal/kefu/send`（`X-Internal-Secret`）：逐条隔离、端点整体永不 5xx，形态照 `/internal/template/send`。
- `OAuthStateStore.mint()` 支持携带 `next` 深链，回调 302 时拼成 `{h5}/wechat/callback#token=…&next=…`；**`next` 必须过开放重定向白名单**（仅允许 `/` 开头、非 `//`、命中已知路由前缀）。

### 前端（frontend）

- 新增路由 `/agent/draft/:id` + `AgentDraftDetailView.vue`：展示「你说：…」+ 待办子任务清单 + 「确认创建」/「不要了」两个大按钮；老人易用（大字号、高对比、单列、无悬浮）。
- 免密落地页支持 `next` 深链恢复（当前固定落 `/dashboard`）。

## 非目标 (Non-goals)

- **不做微信群聊监控 / 群 → 家庭绑定表**：个人微信群无官方读取 API，1v1 通道 XML 里也没有 `ChatRoomUserName`，且非家庭成员发言入库属合规风险。连预留列都不加。
- **不做多轮反问式对话**：agent-service A-1 现状是单轮必出 proposal、无反问态，本 change 不引入对话状态机。
- **不攒批**：一条入站消息 = 一个草稿，v1 不做「多条短消息合并拆解」。
- **不新增 `TaskSource.WECHAT`**：确认前没有 task 行，确认后是正常来源任务，加枚举属 YAGNI。
- **不动 `TaskStatus` 5 值状态机**、不给 `messages` 死列引入写入语义、不加 `users.current_family_id`（跨切面改动，另立 change）。
- **不顺手修 B-6（`save_proposal`）缺 family 作用域的既存隐患**：只在 design 的「已知风险」里记录，另立 issue。
- **不做图片/语音入站解析**，不承诺微信安全模式（AES）验签解密，仅明文模式。

## AI 数据流（输入 / 模型 / 输出）

本 change 新增一条端到端 AI 通路，逐段契约如下：

| 段 | 输入 | 处理 / 模型 | 输出 |
|----|------|-------------|------|
| 入站 | 微信明文 XML：`ToUserName`、`FromUserName`(openid)、`MsgType`、`Content`、`MsgId`、`CreateTime` | wechat-service 验签（SHA-1）+ XML 解析（仅 `text`） | backend 受理请求 `{openid, msg_id, content, received_at}` |
| 裁决 | `content`（≤200 字符，超长截断）+ `LoginResponse.families[{id, role}]` | backend 规则判定（无模型）：0/1/N 家庭 + CHILD 闸门 | `wechat_inbound_message` 一行：`PENDING` 或 `SKIPPED(reason)` |
| 建草稿 | `PENDING` 行的 `content` | `createDraft("TASK_CREATE", {title: content}, familyId, userId)` | `agent_decompose_sessions` 一行 `ACTIVE`，`init_task_title` == 原文 |
| 拆解 | `{message: 原文, session_id}` + 头 `X-Family-Id`、`X-Access-Token` | agent-service 编排器：Step1 身份校验 → Step2 家庭上下文（成员档案 B-3、分类 C-10、天气、今日）→ Step3 分类 → Step4 模板匹配 → Step5 **LLM** → Step6 `session_id≠null` 分流 | 经 B-6 写 `proposed_tasks`（≤20 条，含 `title/description/priority/category/assignee/due_date`），`task_id=null`，**不写业务表** |
| 回推 | `session_id` + openid | backend → wechat-service `POST /internal/kefu/send` → 微信 `message/custom/send` | 客服文本含 `{h5}/wechat/oauth?next=/agent/draft/{id}` |
| 确认 | 用户点击 → OAuth 免密 → fragment `token` → 前端调 D-8 读草稿 → D-5 confirm | backend `taskService.create` 逐条落库 | `tasks` 表首次写入（正常来源与 5 值状态机），草稿转 `CONFIRMED` |

失败与降级出口：LLM 失败 / 空方案 → `attempts≤3` 重试，耗尽落 `FAILED` + 中文兜底话术（不带链接）；AI 开关关 → `SKIPPED/ai_disabled`；多家庭 → `SKIPPED/multi_family`。**模型调用次数与用户在 App 里点一次「AI 拆解」完全同构（1 次），无额外推理成本。**

## Capabilities

### Modified Capabilities

- `wechat-integration`：`POST /wechat/callback` 从占位实现改为真实消息受理入口；OAuth state 新增 `next` 深链与开放重定向白名单；新增内部客服消息端点。

### Added Capabilities

- `wechat-task-intake`：微信私聊文本 → 幂等受理 → 家庭归属裁决 → 自动建草稿 → 自动 AI 拆解 → 客服消息回推确认链接 → 异步作业状态机。
- `profile-decompose`：新增 D-8 草稿详情读取端点，使服务端可按 family 作用域恢复单个拆解会话。
- `frontend-ui`：新增 H5 草稿详情页与免密深链恢复路径（老人易用约束的具体化）。

## Impact

- `backend/src/main/resources/db/migration/V12__Add_Wechat_Inbound_Message.sql`（新增）
- `backend/src/main/java/com/familyapp/wechat/`：`WechatInboundMessage.java`、`WechatInboundMessageRepository.java`、`WechatIntakeService.java`、`WechatIntakeInternalController.java`（新增）；`WechatInternalController.java`（参照）
- `backend/src/main/java/com/familyapp/agent/`：`AgentCallbackClient.java`（新增方法）、`DecomposeSessionController.java`（新增 D-8）、`DecomposeSessionService.java`（新增按 id 读取）、`WechatIntakeJob.java`（新增）
- `backend/src/main/resources/application.yml`：`spring.task.scheduling.pool.size`、`wechat.intake.enabled`
- `agent-service/app/routes/chat_routes.py`：A-2 透传 `session_id`
- `wechat-service/app/routes/wechat_routes.py`：入站 XML 处理、`next` 深链；`app/wechat/kefu.py`（新增）；`app/routes/internal_routes.py`：`/internal/kefu/send`；`app/models/`（请求模型）
- `frontend/src/router/index.js`、`frontend/src/views/AgentDraftDetailView.vue`（新增）、`frontend/src/views/WechatCallbackView.vue`、`frontend/src/stores/`
- `docker-compose.yml` / `.env.example`：新增开关与超时相关环境变量
