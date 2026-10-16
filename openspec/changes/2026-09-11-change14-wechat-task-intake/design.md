# Design: Change 14 - 微信私聊消息自动对接 AI 拆解

## Context

身份链已上线且是本方案基石：`wechat_bind`（V10，`openid` UNIQUE → `user_id`）+ backend 进程内 `WechatService.loginByOpenid(openid)` → `Optional<LoginResponse>`，未绑定返回 empty；`wechat_bind` 数据归 backend 所有，**wechat-service 无 DB 无 SDK**。

拆解链已上线：`agent_decompose_sessions` 草稿箱（D-1 建草稿 / D-5 confirm / D-7 cancel；`ACTIVE/CONFIRMED/CANCELLED/ARCHIVED`；`PROPOSED_TASKS_LIMIT=20`）+ agent-service 编排器（Step1 `auth.verify` → Step2 上下文 → Step5 LLM → Step6/7 按 `session_id` 分流：非空则 `_flatten_to_proposed_tasks` + B-6 `save_proposal` 只写草稿表，为空则 `_persist` 直写业务表）。**confirm 之前，`tasks` 表一行不写。**

服务间鉴权三条既有轨道：`/api/internal/**`（backend，`X-Internal-Secret` 恒时比较）、`/api/agent/v1/**`（backend，`X-Access-Token` + `X-Family-Id`）、`/internal/**`（wechat-service，`X-Internal-Secret`）。

约束：5 值状态机不变；所有业务查询带 family_id；前端老人易用；`backend` 只有 `@EnableScheduling`、零 `@Async`；nginx 已把 `/wechat/` 代理到 wechat-service（read_timeout 30s）、`/api/agent/v1/chat/stream` 到 backend。

**三条取证到的硬事实，决定了本设计的形状：**

1. `LoginResponse` = `record(String accessToken, Long userId, String nickname, List<FamilySummaryResponse> families)` —— **没有 familyId**；`User` 实体也**无 `current_family_id`**，「当前家庭」是纯前端 localStorage 概念。但 `FamilySummaryResponse` = `(id, name, role, roleName, joinedAt)` **含 role**，因此家庭归属与角色闸门可在同一处、零额外查询判完。
2. A-2 `POST /api/v1/chat` 调 `decomposer.stream(...)` 时**只传了 `result_sink`、漏传 `session_id`**，导致草稿模式此前只有 A-1 SSE 一条路可走。全仓 grep 证实 **A-2 无任何生产调用方**（仅 `tests/test_misc.py:79`；nginx 只代理 `/chat/stream`；`internal_routes.py` 用的是进程内 `run_buffered`）。
3. `DecomposeSession.messages` JSONB 是**死列**：全 main 仅 `createDraft` 一处 `setMessages(new ArrayList<>())`，生产路径从未写入。`DecomposeSessionController` 无 `@GetMapping("/{id}")`，前端会话态只活在浏览器 Pinia。

## Decisions

### D1：受理通道——公众号 1v1 私聊，不做群绑定

- 事实依据：微信公众号对**个人群聊没有官方消息读取 API**；即便有，1v1 私聊事件的 XML 里也不存在 `ChatRoomUserName`，「显式绑定群→家庭」没有输入可用。
- 裁决：本轮**砍掉群绑定表，连预留列都不加**（用户明确指令）。
- 副作用（正是收益）：`wechat_bind.openid` 已是 UNIQUE → 一对一映射 user；家庭隔离经 `user → family_members` 免费获得，硬约束 4 无需新增机制。同时「非家庭成员发言入库」的合规风险（PIPL）随群通道一起消除。
- 「过滤与任务无关的消息」自动缩水：1v1 场景下**用户主动私聊即是意图声明**，不需要二分类前置模型，也不需要置信度阈值。只在入口保留一条最小过滤：`MsgType != text` 不受理。
- 备选方案：① 群聊 + 显式绑定表 —— 技术上读不到消息，直接否；② 「先分类再拆解」的前置小模型 —— 多一次 LLM 调用、多一处阈值调参、且与「用户主动发给我」这一事实重复，否。

### D2：入站幂等与异步解耦——V12 表 + `@Scheduled` 轮询

- 微信侧两条硬时限：**被动回复 5 秒上限**、消息推送失败重推。LLM 往返是十秒级，绝不可能同步完成。
- 因此 wechat-service 的同步链只做「验签 → XML 解析 → 调 backend 受理 → 回文本」，backend 受理端点只做「绑定校验 + 幂等落库」，重活交给异步作业。
- backend **零 `@Async`、零 `ThreadPoolTaskExecutor`**，只有 `@EnableScheduling` 与 6 处 `@Scheduled`。不引入新线程模型，沿用「表即队列」：`wechat_inbound_message.status` 驱动，`@Scheduled(fixedDelay=5000)` 捞 `PENDING`。
- 幂等表与队列表**二合一**：`msg_id` UNIQUE（抄 `uq_wechat_bind_openid` / `uq_tasks_event_date` 先例）吸收微信重推，`status` + `attempts` 吸收本侧重试。
- **必须扩 `spring.task.scheduling.pool.size` 到 4**：Spring 默认调度池单线程，长 LLM 调用会把其它 6 个 `@Scheduled` job 全部堵死。这是本 change 的隐性前置，不是可选项。
- 备选方案：进程内 `asyncio` 风格的一次性线程池 —— 进程重启即丢任务，且与「零 `@Async`」的既存风格冲突，否。

### D3：家庭归属裁决——四分支，歧义时宁拒不猜

服务端没有「当前家庭」，必须在受理时裁决。裁决表（输入 = `loginByOpenid` 返回的 `families[]`）：

| 情形 | 裁决 | `status` / `skip_reason` | 回推话术 |
|------|------|--------------------------|----------|
| openid 未绑定 | 不受理 | `SKIPPED` / `not_bound` | 「你还不是家庭应用的成员，请先在应用里绑定微信」 |
| `families.size() == 0` | 不受理 | `SKIPPED` / `no_family` | 「你还没有加入任何家庭，请先在应用里加入家庭」 |
| 唯一家庭且 `role == CHILD` | 不受理 | `SKIPPED` / `role_denied` | 「这个功能需要成年人成员，请让家人帮你添加」 |
| 唯一家庭且 `role in (OWNER, ADULT)` | 受理 | 进入 `PENDING` | — |
| `families.size() >= 2` | 不自动拆解 | `SKIPPED` / `multi_family` | 「你有 N 个家庭，请打开应用选好家庭后再用 AI 拆解」 |

- 理由：多家庭时**任何自动选择都是赌**，赌错等于把草稿建到错误家庭，直接违反硬约束 4。宁可拒绝并给出明确下一步，也不猜。
- `role == CHILD` 提前判定是省钱的正确性优化：agent 侧 `AuthVerifier` 只放 `OWNER/ADULT`（`_ALLOWED_ROLES`），CHILD 必然 40302，且失败点在 Step1、根本不调 LLM —— 但与其让作业吃一个 40302 再翻译话术，不如在受理侧同处判掉（families[] 已含 role，零额外查询）。
- 此决策同时反哺 D7：v1 限定单家庭 ⇒ 免密落地时前端「自动选第一个家庭」的家庭 == 草稿家庭，二者互证，不会出现「在 A 家庭建草稿、B 家庭看不到」的 404。
- `users.current_family_id` 是正解但属跨切面改动（DDL + 登录响应 + 前端家庭切换 + 存量数据迁移）→ 记入 Non-goals / Open Questions / check_list DEFERRED。

### D4：拆解出口——修 A-2 透传 `session_id`（反转上一轮结论）

- **上一轮我判定「agent-service 零改动，backend 消费 A-1 SSE 并丢弃中间帧」。取证后公开反转。**
- 新证据：A-2 `/api/v1/chat` 与 A-1 用**同一个编排器**，只差 `decomposer.stream(...)` 调用里漏传 `session_id=body.session_id` 一行；且 A-2 全仓无生产调用方。
- 改后行为推演（`decomposer.py` Step6/7 + sink 语义）：`session_id != None` → `save_proposal(B-6)` + `task_id = None`；`result_sink["task_id"] = None` 但**键存在** → A-2 现有判定 `if "task_id" not in sink` 为假 → 返回 `{code:0, data:{task_id:null, title, subtasks:[…]}}`。**返回体天然可用，backend 据 `code==0 && subtasks 非空` 判 DONE。**
- 选它的三条理由：① 无生产调用方 ⇒ 回归面≈0；② 服务端调用方拿 JSON 远比解析 `text/event-stream`（多帧、需自己判 done/error、需处理心跳注释帧）可靠；③ 补齐 `session_id` 是**修复 A-1/A-2 既有不对称**（`ChatStreamRequest` 早已有该字段、注释明写「传 session_id 时走 A-1 草稿语义」），不是为新需求造新语义。
- 向后兼容：`session_id` 为空时 A-2 仍走 B-1 直写业务表，行为逐字不变；`run_buffered` 保持 `access_token=None` + 不传 `session_id`，不动。
- 否决方案：backend 解析 SSE（要多写一个帧解析器 + 超时语义更糊）；复用 `run_buffered`（强制 B-1 直写业务表，与「只建草稿」的语义正面冲突）。

### D5：草稿承载——`init_task_title` 装原文，不碰 `messages` 死列

- `createDraft(sessionTypeStr, initPayload, familyId, actorUserId)` 的 init_payload 键是 **`title`**（不是 `initTaskTitle`），超 200 字符截断，空则落「未命名 AI 拆解任务」。
- 本 change 传 `Map.of("title", content)` —— 用户原文直接成为草稿标题，H5 详情页据此渲染「你说：…」。**零新写入方**，不给 `messages` 死列引入语义。
- 否决方案：把原文写进 `messages`（给一个全仓从未写入过的列发明新契约，且要同步设计前端消息气泡渲染，成本远大于收益）。

### D6：回推通道——客服消息 `POST /internal/kefu/send`

- 选客服消息而非模板消息：48 小时窗口内**免模板申请**，不需要去公众号后台再申请一个 template_id（当前第 4 个模板 ID 仍是挂账空值，且本机 3 个 ID 值相同）。
- 实现落点：新增 `wechat/kefu.py::send_text`，调 `cgi-bin/message/custom/send`，**复用 `WechatTokenManager`**（同一进程内单飞缓存，省 quota）；错误码处理照 `template.py`：40001/42001 刷新重试一次、43004/43010 不重试、传输异常单条隔离。
- 端点形态照 `/internal/template/send`：`_check_secret` 守护、逐条隔离返回 `results[]`、**端点整体永不 5xx**（body 格式错 → 400，其余一律 200 + `ok:false`）。
- backend 侧客户端沿用 `WechatNotifyClient` 的失败隔离铁律：连接 2s / 读 5s，任何异常只记 warn 返回空，**绝不抛出**（推链接是业务旁路，草稿已经建好了，用户去 App 里也能确认）。
- 48h 窗口过期 → 客服消息发不出（errcode 45015 类）：只记 warn，不重试、不报错给用户，草稿仍在 `ACTIVE`，等用户自己进 App。这是可接受的静默降级，因为**不确认即作废**，没有副作用。

### D7：确认入口——免密深链 + `next` 白名单 + D-8 详情端点

- 链接形态：`{h5}/wechat/oauth?next=/agent/draft/{id}` → wechat-service 静默授权 → 回调 302 `{h5}/wechat/callback#token=…&next=/agent/draft/{id}`。token 只在 fragment（不发服务器、不进 Referer/访问日志），openid 全程不出服务端 —— 与 Change 11 既有安全边界逐字一致。
- `OAuthStateStore.mint()` 从「只存 state→过期」扩展为「state→(过期, next)」，**`next` 必须过开放重定向白名单**：仅允许 `/` 开头、第二个字符非 `/`（挡 `//evil.com`）、且命中已知路由前缀白名单（`/agent/draft/`、`/tasks`、`/calendar`、`/dashboard`、`/chat`）。不合规 → 丢弃 `next`，落默认首页（不报错，保持「免密一定进得去」）。
- 为什么必须新增 D-8：前端会话态纯 Pinia（`stores/decomposeSse.js` 的 `sessionId: null`、`DecomposeChatPanel.vue` 仅 idle 才 init），`DecomposeSessionController` 只有 create/confirm/save-profile-only/cancel，**没有按 id 读取**。从微信点链接进来是一次全新页面加载，没有 D-8 就无从恢复。
- D-8 契约：`GET /api/agent/v1/decompose/sessions/{id}`，`requireFamilyId(curr)` 作用域（复用 `loadActiveScoped` 同族语义），返回 `{id, status, initTaskTitle, proposedTasks[], createdTaskIds[]}`；`CONFIRMED` 也 SHALL 可读（用户反复点同一链接时要知道「已经创建过了」，而不是 404 让人困惑）。
- 数据源：`init_task_title`（原文）+ `proposed_tasks`（待办清单）+ `status`（决定按钮态）。
- 「误建怎么撤回」不需要撤回机制：`ACTIVE` 草稿永不进业务表，不确认即作废，且 D-7 `DELETE /sessions/{id}`（cancel）已上线，页面上的「不要了」直接接它。

### D8：FeatureGuard 与失败话术

- 作业在调 agent-service 前 SHALL 先过 `FeatureGuard`：AI 开关关闭时**不建草稿、不调拆解**，直接 `SKIPPED / ai_disabled` 并回推「AI 拆解功能暂时关闭了，你可以先在应用里手动添加任务」。避免开关关闭期间堆积 `PENDING`。
- 拆解失败（`code != 0`、传输异常、subtasks 空）：`attempts < 3` 时回 `PENDING` 重试；耗尽 → `FAILED` + `last_error`，回推「这条我暂时没太看懂，你可以再说具体一点吗？比如『周六上午带爸爸去医院复查』」。
- 话术铁律：一律中文、第二人称、不用术语（不出现「拆解会话」「proposal」「agent」）、失败不指责用户、每条都给出下一步动作。老人可读优先于信息完整。

### D9：配置与编排

| 位置 | 变量 | 默认值 | 说明 |
|------|------|--------|------|
| backend `application.yml` | `spring.task.scheduling.pool.size` | `4` | 硬前置：默认 1 会让 LLM 长调用堵死全部 `@Scheduled` |
| backend | `wechat.intake.enabled` / env `WECHAT_INTAKE_ENABLED` | `false` | 总开关，默认关，灰度开；关闭时受理端点直接 `SKIPPED` |
| backend | `wechat.intake.poll-fixed-delay-ms` / env `WECHAT_INTAKE_POLL_MS` | `5000` | 轮询间隔 |
| backend | `wechat.intake.max-attempts` / env `WECHAT_INTAKE_MAX_ATTEMPTS` | `3` | 拆解重试上限 |
| backend | `agent-service.base-url`（既存） | `http://agent-service:8700` | 复用，新增方法走同一 base-url |
| wechat-service | `wechat_inbound_enabled` / env `WECHAT_INBOUND_ENABLED` | `false` | 入站受理开关，与 backend 开关同名语义、两道独立闸门 |
| wechat-service | `h5_base_url`（既存） | `http://localhost` | 拼 `next` 深链与客服消息内链接 |
| nginx | `/wechat/`（既存） | read_timeout 30s | 入站同步链必须 <5s，靠 backend 受理端点毫秒级返回保证 |
| docker-compose | 上述 env 透传 | — | 三个服务容器均需确认 env 注入 |

- 双开关（wechat-service + backend）不是冗余：wechat-service 侧关 = 连落库都不做（完全静默）；backend 侧关 = 落库但 `SKIPPED`（可留痕观察）。灰度时先开 backend 观察受理日志，再开 wechat-service 引流。

### D10：不做的事

- 不做微信群聊监控、群→家庭绑定表（含预留列）。
- 不做多轮反问对话、不做对话状态机。
- 不做多条消息攒批合并。
- 不新增 `TaskSource.WECHAT` 枚举。
- 不动 `TaskStatus` 5 值状态机；不动 `messages` 死列；不给 `agent_decompose_sessions` 加列。
- 不加 `users.current_family_id`（另立 change）。
- 不修 B-6 `save_proposal` 缺 family 作用域的既存隐患（见「已知风险」）。
- 不做图片/语音入站、不做安全模式（AES）加解密。
- 不做 `wechat_inbound_message` 的历史清理/归档（v1 数据量可忽略，列 Open Questions）。
- 不引入 `@Async` / 新线程池 / 消息队列组件。

## 已知风险（只记录，不在本 change 修）

- **B-6 缺 family 作用域**：`InternalAgentController` 的 `save_proposal` 用 `findByIdAndStatus(id, ACTIVE)`，未校验该 session 是否属于本次请求的 family，且 `setProposedTasks(new ArrayList<>(tasks))` 是整体覆盖。本 change 的调用路径 family 一致，不构成新漏洞，但它是既存越权面，SHALL 另立 issue 硬化（加 `familyId` 参数 + 作用域查询）。
- **`WechatNotifyJobs` 直取 `LocalDate.now()`**（既存同族问题，Change 13 已根治 `WechatNotifyServiceTest` 的硬编码）：与本 change 无耦合，不动。
- **客服消息在测试号上的可用性未验证**：个人测试号是否开放 `message/custom/send` 需真机验证 → check_list DEFERRED，并预留「降级为模板消息 / 只记日志」的退路。

## Migration Plan

1. 执行 `V12__Add_Wechat_Inbound_Message.sql`（只建表和索引，不触碰既有表）。回滚：`DROP TABLE wechat_inbound_message`（见 tasks.md Task 1 的回滚脚本与验证命令）。
2. 部署 agent-service：A-2 补 `session_id` 透传（`session_id=null` 时行为不变，可独立先上，零风险）。
3. 部署 backend：新迁移 + 新端点 + 新作业，两开关默认 `false`（此时全链路仍是零行为变化）。
4. 部署 wechat-service：入站解析 + 客服消息端点 + `next` 深链，开关默认 `false`。
5. 前端上线 `/agent/draft/:id` 详情页与深链恢复（backend recreate 后 SHALL `restart nginx` 防 502）。
6. 单用户自测（本人 openid + 单家庭）通过后，开 backend 开关观察日志，再开 wechat-service 开关放量。
7. 回滚顺序与部署相反：先关两道开关（立即静默）→ 前端下路由 → 服务回滚镜像 → 表保留不删（有数据即有痕迹，排障优先）。

## Open Questions

- 多家庭用户的正确解法是 `users.current_family_id` 还是「草稿 URL 上带 family 参数」？前者干净但跨切面，后者快但把状态放进链接。倾向前者、另立 change。
- 客服消息 48h 窗口 + 用户当天没点确认，是否要补一条次日模板消息提醒？（会牵回模板 ID 挂账，v1 倾向不做）
- `wechat_inbound_message` 是否需要保留期（如 30 天后删除）？涉及原文（PII）留存，即使 1v1 只存自家成员发言，也应有 TTL。
- 老人发的原文里带人名/时间口语（「下礼拜六」「后天」），现有 prompt 的日期解析准确率是否够？需不需要在本 change 内补评测样本，还是先上线观察真实数据。
