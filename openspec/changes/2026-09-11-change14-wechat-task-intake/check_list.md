# Check List：微信私聊消息自动对接 AI 拆解（Change 14）

> 状态：**实现阶段已结束，2026-09-22 完成全量证据回填**（初版「本轮只立规格、不写代码」的注记已过时）。`☑ 已取证`= 事实已由本轮真跑测试/源码审查/容器产物取证验证；`☐`= 诚实挂账，查无证据不伪造过关。
> **剩余挂账五项**：18（真机 PG 回滚演练，H2 级等价证据已有）、23（受理 <200ms 无计时断言）、33（LLM 恰 1 次无计数断言）、49（375px 真机截图）、50（`WechatCallbackView` 组件级用例；safeNext 逻辑已有单测）。

## 一、规格正确性（取证已完成的硬事实）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 1 | 下一版 Flyway 号为 **V12**（现存最高 `V11__Add_Calendar_Events.sql`） | ☑ 已取证 | `backend/src/main/resources/db/migration/` 目录清点：V1–V11 + afterMigrate |
| 2 | `LoginResponse` 无 `familyId`、`User` 无 `current_family_id`，故家庭必须裁决 | ☑ 已取证 | `auth/dto/LoginResponse.java:11-16`；`user/User.java` 字段清单 |
| 3 | `FamilySummaryResponse` 含 `role`，角色闸门零额外查询 | ☑ 已取证 | `family/dto/FamilySummaryResponse.java:9-15` |
| 4 | agent 侧只放 `OWNER/ADULT`，CHILD → 40302 且 Step1 短路不调 LLM | ☑ 已取证 | `agent-service/app/services/auth_verifier.py:26,53-58` |
| 5 | A-2 `/api/v1/chat` 漏传 `session_id`，且全仓无生产调用方 | ☑ 已取证 | `agent-service/app/routes/chat_routes.py` A-2 调用点；全仓 grep 仅命中 `tests/test_misc.py:79`；nginx 只代理 `/chat/stream` |
| 6 | `session_id≠null` 时 `task_id=null` 但 sink 键存在 → A-2 返回体天然可用 | ☑ 已取证 | `agent-service/app/services/decomposer.py:138-144` + Step6/7 分流 |
| 7 | `run_buffered` 强制 `access_token=None` 且走 B-1 直写，草稿模式不可用 | ☑ 已取证 | `decomposer.py::run_buffered` |
| 8 | `messages` JSONB 为死列（全 main 仅一处初始化为空） | ☑ 已取证 | grep `setMessages\|getMessages` → 3 处命中（实体 getter/setter + `DecomposeSessionService.java:119`） |
| 9 | `createDraft` 的 init_payload 键是 `title`（非 `initTaskTitle`），超 200 截断 | ☑ 已取证 | `DecomposeSessionService.java:78` 附近 |
| 10 | backend 零 `@Async`、仅 `@EnableScheduling` + 6 处 `@Scheduled`，默认单线程 | ☑ 已取证 | 全 main grep `@Async\|ThreadPoolTaskExecutor` 无业务命中；`@Scheduled` 6 处 |
| 11 | `DecomposeSessionController` 无 `@GetMapping("/{id}")`，故必须新增 D-8 | ☑ 已取证 | 控制器端点清点（create/confirm/save-profile-only/cancel） |
| 12 | `POST /wechat/callback` 现为占位（验签后回空串） | ☑ 已取证 | `wechat-service/app/routes/wechat_routes.py:79-91` |
| 13 | 客服消息可复用 `WechatTokenManager`（单飞缓存 + 刷新退避） | ☑ 已取证 | `wechat-service/app/wechat/token_manager.py` |
| 14 | 群聊通道不可行：无官方读取 API，1v1 XML 无 `ChatRoomUserName` | ☑ 已取证 | 平台事实 + 用户裁决（砍群绑定，连预留列都不加） |

## 二、数据模型与迁移（Task 1）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 15 | `wechat_inbound_message` 建表 + `uq_wim_msg_id` UNIQUE + `idx_wim_pending` 部分索引 | ☑ 已取证（2026-09-22，本轮 XML）：`V12MigrationTest` **8 用例 / 0F**——用例1 建表成功、用例3 msgId 唯一约束吸收微信重推、用例8 轮询索引列序钉死（status 先 received_at 后）。**载体偏离如实记账**：实际类名 `V12MigrationTest`（本仓 V{n}MigrationTest 惯例），非 spec 所写 `WechatInboundMessageMigrationTest`；`idx_wim_pending` 实为**普通复合索引非 partial index**（V12.sql 头注 L8-9：H2 不支持 `CREATE INDEX ... WHERE`，非唯一索引 partial 只省空间不影响正确性） | `V12MigrationTest` 用例1/3/8 |
| 16 | `status` CHECK 恰 5 值，写入第 6 值被拒 | ☑ 已取证（2026-09-22）：用例4 五值 CHECK 恰好接受（PENDING/RUNNING/DONE/FAILED/SKIPPED）+ 用例5 第六值被拒（8/0F） | `V12MigrationTest` 用例4/5 |
| 17 | 迁移应用至 `v12`，服务 healthy | ☑ 已取证且**期望已超越**（2026-09-22）：运行栈实测 `Successfully validated 14 migrations` + `Current version of schema "public": 14` ⇒ V12 早已应用（V13/V14 来自后续 change）；backend 容器 healthy（`ps -a` 7/7）。原期望文案 `now at version v12` 已过时，修正为 ≥v12 | backend 日志 + `docker compose ps -a` |
| 18 | **回滚脚本可执行且回滚后 Flyway 能重新应用 V12** | ☑ H2 级等价取证 / ☐ **真机演练挂账**（2026-09-22）：`V12MigrationTest` 用例7「重放脚本后默认值生效」+ 辅助方法（L124「DROP 掉表，重放 V12 脚本让 CREATE TABLE 真正执行」）证明**DROP→重放可重建**（与 `V10MigrationTest` 用例2 逆向回滚同模式）；回滚段脚本已写入 V12.sql 尾注 L46-53（DROP INDEX×2 + DROP TABLE + DELETE history 行），且明示「生产回退优先关开关不删表」。**缺口**：真机 PG 上「DELETE flyway_schema_history → 重启 → Flyway 重应用 V12」未演练 | `V12MigrationTest` 用例7 + V12.sql L46-53 |
| 19 | 既有表零改动（`agent_decompose_sessions`、`tasks` 列不变） | ☑ 已取证（2026-09-22，迁移文件全文审查）：V12.sql 仅 `CREATE TABLE IF NOT EXISTS wechat_inbound_message` + 2 个 `CREATE INDEX`，**零 ALTER 既有表**；grep 命中的 3 处 `DROP` 全部位于尾部回滚段**注释内**（L47-49），非正向 DDL；文件头 L4 明示「只增不改：不触碰任何既有表」 | V12.sql 全文审查 |

## 三、受理与异步作业（Task 2-4）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 20 | 受理端点仅落库、零外呼（无 LLM/无 HTTP 到 agent-service） | ☑ 已取证（2026-09-22，本轮 XML）：`WechatInboundInternalControllerTest` **9 用例 / 0F**（用例①②在列：① 受理落一行 PENDING、② 未绑定落 SKIPPED 留痕）；`WechatInboundSecretGuardTest` 2/0F 补内网密钥闸门（缺失→40101 / 错误→40102 且不落行） | `WechatInboundInternalControllerTest` ①② + `WechatInboundSecretGuardTest` |
| 21 | 同 `msg_id` 重推幂等，表内单行 | ☑ 已取证（2026-09-22）：用例⑥「同 msg_id 重推两次 → 第二次 reason=duplicate 且表内仍 1 行」（9/0F） | `WechatInboundInternalControllerTest` ⑥ |
| 22 | 四分支裁决（not_bound / no_family / role_denied / multi_family）+ 受理 | ☑ 已取证（2026-09-22）：用例①①b（受理，ADULT/OWNER）、②not_bound、③no_family、④role_denied（CHILD）、⑤multi_family（不猜家庭，硬约束 4）全在且全绿（9/0F） | `WechatInboundInternalControllerTest` ①-⑤ |
| 23 | 受理响应 < 200ms | ☐ **诚实挂账**（2026-09-22）：`WechatInboundInternalControllerTest` 无计时断言（grep `nanoTime/assertTimeout/elapsed` 零命中；文件内唯一 "200" 是用例⑨的字符截断断言），亦无 curl 实测记录。间接证据：用例①②证明受理端点零外呼仅落库，架构上 <200ms 风险低——但无量化证据，不伪造过关 | 无（挂账） |
| 24 | `spring.task.scheduling.pool.size` ≥ 2（默认 4） | ☑ 已取证（2026-09-22，配置+测试双证）：`application.yml` L27-29 `size: ${SPRING_SCHEDULING_POOL_SIZE:4}`（默认 4）；`WechatIntakeJobTest` 用例⑪「调度线程池已扩到 4（不得饿死既存 6 个 @Scheduled）」（16/0F） | `application.yml` + `WechatIntakeJobTest` ⑪ |
| 25 | 作业逐条隔离、`attempts` 上限 3、耗尽落 `FAILED` | ☑ 已取证（2026-09-22）：`WechatIntakeJobTest` **16 用例 / 0F**——`MAX_ATTEMPTS = 3` 钉死（L52）；用例⑩ 同批两行第一行抛异常第二行仍完成（逐条隔离）、④ attempts 达上限仍失败→FAILED+last_error、③ 未耗尽→回 PENDING attempts+1 | `WechatIntakeJobTest` ③④⑩ |
| 26 | 草稿 `init_task_title` == 原文，`messages` 仍为空数组 | ☑ 已取证（2026-09-22）：`WechatIntakeDraftTest` **6 用例 / 0F**——用例① init_task_title 等于原文 + family_id 等于裁决家庭、③ messages 列保持空数组（不给死列引入新写入方）、② 超长截断到 200 | `WechatIntakeDraftTest` ①③ |
| 27 | 确认前 `tasks` 表零写入 | ☑ 已取证（2026-09-22）：用例④「建草稿不写业务表：tasks 行数前后不变」（6/0F）；agent 侧 `test_chat_buffered.py` ① 同证（草稿模式 create_task/create_subtasks_batch/add_note 均不得被调用） | `WechatIntakeDraftTest` ④ |

## 四、AI 拆解接线（Task 5-6）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 28 | A-2 透传 `session_id` → 走 B-6 存 `proposed_tasks`、`task_id=null` | ☑ 已取证（2026-09-22，agent-service 96 passed 本轮真跑）：`test_buffered_with_session_id_stores_proposal_and_writes_no_task`——断言 `task_id is None`、subtasks 3 条标题逐一相等、`session_id` 原样透传到 B-6（`proposal_calls == [7]`）、直写零调用 | `test_chat_buffered.py` ① |
| 29 | A-2 不传 `session_id` 时 B-1 行为逐字不变（回归护栏） | ☑ 已取证（2026-09-22）：`test_buffered_without_session_id_keeps_b1_direct_write`——`task_id == 501`、`proposal_calls == []`（不误走 B-6）、B-1 三连 `create_task/create_subtasks_batch/add_note` 逐字不变 | `test_chat_buffered.py` ② |
| 30 | 身份失败短路，不调 LLM | ☑ 已取证（2026-09-22）：`test_buffered_auth_failure_short_circuits_llm`——`code == 40103`、无 data、`llm.calls == []`、backend 零调用 | `test_chat_buffered.py` ③ |
| 31 | backend → A-2 用 `X-Family-Id` + `X-Access-Token`（非 internal secret）、读超时 ≥60s | ☑ 已取证（2026-09-22）：`AgentCallbackClientTest` **7 用例 / 0F**——`posts_to_v1_chat_with_user_scoped_headers` 钉死 `header("X-Family-Id","7")` + `header("X-Access-Token",…)` + `headerDoesNotExist("X-Internal-Secret")`（L55-57）；`uses_60s_read_and_5s_connect_timeout_constants` + `draft_read_timeout_actually_fires` 双证读超时 | `AgentCallbackClientTest`（spec ①⑤ 对应上述两用例） |
| 32 | `code==0` 但 `subtasks` 空 → 判失败，不推「点此确认」 | ☑ 已取证（2026-09-22）：`decomposeDraft_treats_empty_subtasks_as_failure`（7/0F）；作业侧第二道防线 `WechatIntakeDecomposeTest` 用例⑩「拆解返回空列表也视为失败」（10/0F）；不推确认链接由 `WechatIntakeNotifyTest` 用例③ 钉死（失败话术不含链接） | `AgentCallbackClientTest` + `WechatIntakeDecomposeTest` ⑩ |
| 33 | LLM 调用次数与 App 内点一次拆解同构（1 次） | ☐ **诚实挂账**（2026-09-22）：`test_chat_buffered.py` 三个用例中仅 auth 短路用例断言 `llm.calls == []`，成功链路用例**无 `llm.calls` 计数断言**（「恰 1 次」未显式钉死）。间接证据：成功用例断言拆解结果 3 条且 FakeLLM 单次返回即满足，多次调用无动机——但无计数断言，不伪造过关 | 无（挂账） |

## 五、回推与入站（Task 7-10）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 34 | `/internal/kefu/send` 逐条隔离、永不 5xx、无密钥 40300 | ☑ 已取证（Task 7 记账补勾） | `test_kefu.py` |
| 35 | 40001/42001 刷新重试一次；43004/43010 不重试 | ☑ 已取证（Task 7 记账补勾） | `test_kefu.py` |
| 36 | 同批 token 调用 ≤1 次 | ☑ 已取证（Task 7 记账补勾） | `test_kefu.py` |
| 37 | 回推失败不改入站行终态（草稿仍可用） | ☑ 已取证（Task 8 记账补勾） | `WechatIntakeNotifyTest` ④ |
| 38 | 话术全中文、无技术术语（正则断言） | ☑ 已取证（Task 8 记账补勾） | `WechatIntakeNotifyTest` ⑤ |
| 39 | 入站回调 5 秒内应答；backend 故障回空串不外泄 5xx | ☑ 已取证（Task 9 记账补勾） | `test_inbound_callback.py` ⑤⑧ |
| 40 | 非文本（event/image/voice）不转交 backend | ☑ 已取证（Task 9 记账补勾） | `test_inbound_callback.py` ②③ |
| 41 | XML 解析关闭外部实体（防 XXE） | ☑ 已取证（Task 9 记账补勾） | 代码审查 + 恶意样本用例 |
| 42 | `next` 白名单挡 `//evil.com`、绝对 URL、未知路由，且仍正常落地 | ☑ 已取证（Task 10 记账补勾） | `test_oauth_next.py` ② |
| 43 | 无 `next` 时 302 结果与本 change 之前逐字一致 | ☑ 已取证（Task 10 记账补勾） | `test_oauth_next.py` ③ |

## 六、草稿读取与前端（Task 11-13）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 44 | D-8 带 `family_id` 作用域，跨家庭不可见 | ☑ 已取证（波次 9；变异 M2/M3 被杀） | `DecomposeSessionDetailTest` ② |
| 45 | D-8 可读 `CONFIRMED`（重复点链接不 404） | ☑ 已取证（波次 9；变异 M5/M7 被杀） | `DecomposeSessionDetailTest` ③ |
| 46 | AI 开关关闭 → 50310 | ☑ 已取证（波次 9；变异 M1 被杀） | `DecomposeSessionDetailDisabledTest`（⑤ 独立成类） |
| 47 | 详情页四态齐（loading/empty/error/success），空清单有中文兜底 | ☑ 已取证（2026-09-22，**部分取证**）：`AgentDraftDetailView.vue` 源码四态齐——loading `正在读取你的安排…`、error 含重试按钮 + 切家庭提示、success 渲染「你说：<原文>」+ 逐条标题、empty 中文兜底 `暂时还没有拆解出待办，请稍后重试`；`AgentDraftDetail.test.js` 用例 2 钉住 empty + error。**缺口如实记账**：loading 态文案无自动化用例（仅源码审查） | 源码审查 + `AgentDraftDetail.test.js` ⑥（实际文件名，非 spec 所写 `AgentDraftDetailView.spec.js`） |
| 48 | 已确认态禁用按钮，无第二次 confirm 调用 | ☑ 已取证（2026-09-22，**完全取证**）：`AgentDraftDetail.test.js` 用例 1 断言 `confirm).toHaveBeenCalledTimes(1)` + 重开后 `.draft-actions` 不存在 + `created_task_id` 链接可见；源码双保险——action bar 仅 `v-if="draft.status === 'ACTIVE'"`，`accept()` 首行 `if (busy.value \|\| draft.value?.status !== 'ACTIVE') return` | `AgentDraftDetail.test.js` ⑤（spec ⑤） |
| 49 | 字号 ≥18px、触控目标 ≥48px、375px 无横向滚动 | ☑ 样式审查通过 / ☐ **375px 真机截图不可取证**（2026-09-22）：源码 `.draft-page` `font-size: 18px`、`:deep(.el-button)` `min-height: 48px; font-size: 18px`、`:deep(.el-input__inner)` `min-height: 48px`、`:deep(.el-textarea__inner)` `min-height: 72px`、`max-width: 680px; margin: auto` 单列 ⇒ 三项样式阈值源码级达标。本仓无 Playwright/真机链路，**横向滚动一项挂账待人工真机确认** | 源码样式审查（真机截图挂账） |
| 50 | fragment 读 `next` 后 `replace`，无 next 落首页不变 | ☐ **诚实挂账**（2026-09-22）：spec ⑦ 要求的 `WechatCallbackView` 组件级用例**不存在**（`AgentDraftDetail.test.js` 3 个用例均未覆盖）。已有的替代证据链：`WechatCallbackView.vue` 源码 `const nextPath = safeNext(params.get('next'))` → `router.replace(nextPath)` ✓；`utils/safeNext.js` 白名单单测在 `tests/utils/familyRequests.test.js` L13（`/agent/draft/9` 原样放行）/ L15（恶意 url 归 `/`）✓；无 `next` 时 `safeNext` 兜底返回 `/`（行为不变）⇒ **逻辑已覆盖、组件级渲染未覆盖**，补测另立 Task，不在本 change 内伪造取证 | `familyRequests.test.js`（safeNext 单测，非 ⑦ 原计划载体） |
| 51 | compose env 透传 + `repo-map.md` 更新 + 7/7 healthy | ☑ **拆分记账（四项均已落定）**（2026-09-22）：**① compose env 透传 = ☑**，`docker-compose.yml` + `.env.example` 五个开关（`WECHAT_INTAKE_ENABLED`/`WECHAT_INBOUND_ENABLED`/`WECHAT_INTAKE_POLL_MS`/`WECHAT_INTAKE_MAX_ATTEMPTS`/`SPRING_SCHEDULING_POOL_SIZE`）已核；**② `docker compose config` = ☑ COMPOSE_OK**；**③ `repo-map.md` 更新 = 载体不适用（已裁决，不动该文件）**——当前文件是 aider tree-sitter **原始终端转储**（1012 行 / 213 个 `⋮` / 零散文段落，横幅声明 `read-only` + `auto refresh`，无「补一句话」的插入位），且经 `git log` 提交全文复查确认此版为 `c476715`（09-11）**按用户裁决入库**（非误覆盖）；「补入站链路一句话」的文档意图经取证**已由 design.md D2（L31-33）满足**（同步链「验签→XML解析→调 backend 受理→回文本」+ 表即队列 `wechat_inbound_message.status` 驱动 / `@Scheduled(fixedDelay=5000)` 捞 PENDING）；**④ 7/7 healthy = ☑ 已取证**，`docker compose ps -a` 实测 agent-service / backend / frontend / nginx / postgres / tool-service / wechat-service **7 个全 healthy（Up 2 hours）**；backend 日志 `Successfully validated 14 migrations` + `Current version of schema "public": 14`（2026-09-22T09:52 重启）⇒ V13/V14 已在运行栈应用；frontend 镜像 `59cd3fcf3a67`（created 2026-09-18T02:00Z）产物含 `AgentDraftDetailView-CR5cSbos.js`，且 `grep "agent/draft"` 命中 `index-Ddcbj0v5.js` ⇒ **Task 12 的构建部署环节早已完成、详情页在运行栈中可达**（容器内产物级取证，非源码推断） | `docker compose config`（✓）/ `ps -a`（✓ 7/7 healthy）/ 容器产物 grep（✓）/ repo-map.md（裁决：不动，意图落 design.md D2） |

## 七、自动化门禁（实现阶段每条必须贴真实输出）

| 命令 | 期望结果 | 本轮状态 |
|------|----------|----------|
| `./gradlew test` | BUILD SUCCESSFUL，含 Task 1-4、6、8、11 新测试 | ☑ 2026-09-15（波次 9）：`test --continue --rerun-tasks` exit 0；`TEST-*.xml` 逐类加总 = **65 类 / 460 tests / 0 failures / 0 errors / 0 skipped**（基线 63/452 + Task 11 新增 2 类/8 用例） |
| `cd agent-service && pytest -q` | 全绿，含 `test_chat_buffered.py`；A-1/`run_buffered` 无回归 | ☑ 2026-09-22（真跑）：**96 passed**，coverage **93.39%** |
| `cd wechat-service && pytest -q` | 全绿，含 `test_kefu.py`、`test_inbound_callback.py`、`test_oauth_next.py` | ☑ 2026-09-22（真跑）：**130 passed**，coverage **93.71%** |
| `cd frontend && npx vitest run && npm run build` | 测试全绿 + build 成功 | ☑ 2026-09-22（真跑）：**33 files / 142 tests passed**（含 `AgentDraftDetail.test.js` 3 用例、`familyRequests.test.js` safeNext 白名单）；`npm run build` ✓ **4.06s** |
| `docker compose ps -a` | 7/7 healthy，backend 日志 `now at version v12` | ☑ 2026-09-22（真跑）：**7/7 healthy**（agent-service / backend / frontend / nginx / postgres / tool-service / wechat-service，均 Up 2 hours）。**期望值已修正**：原文写 `now at version v12`，实测 backend 日志为 `Successfully validated 14 migrations` + `Current version of schema "public": 14` + `Schema "public" is up to date`（最近一次 2026-09-22T09:52:12）⇒ V13（TaskNotification）/ V14（PasswordReset）已应用，v12 是写这条期望时的旧基线 |

> 注：本节 2026-09-22 由「全仓状态盘点」波次真跑回填（backend 496 tests / 72 类，XML 逐类加总 + 负向模式 `failures="[1-9]|errors="[1-9]|skipped="[1-9]` 零命中）。**原「本机无 `openspec` CLI」注记已过时**：`which openspec` 实测得 `/mnt/c/Users/98447/AppData/Roaming/npm/openspec`（Windows npm 全局装的，WSL 侧可经 `/mnt/c` 调用）⇒ `openspec validate` 可跑，本 change 收尾时补真输出。

## 八、规格自检（人工 self-review）

| # | 检查项 | 状态 |
|---|--------|------|
| 52 | proposal 含「非目标 (Non-goals)」小节 | ☑ |
| 53 | proposal 含 AI 数据流「输入/模型/输出」表（config.yaml 对 AI 模块的硬要求） | ☑ |
| 54 | 四份 delta spec 的能力名**不含 change 编号**（不学 `change6-frontend`/`change7-compose`/`v6-flyway-datamodel` 反例） | ☑ |
| 55 | spec 格式对齐 Change 13：`#### Scenario` + 紧邻 `- **WHEN**` / `- **THEN**`，全中文无 GIVEN | ☑ |
| 56 | tasks 每个 Task 单会话可验证、含失败测试先行的四步 | ☑ |
| 57 | DB schema 任务（Task 1）含回滚脚本 + 回滚验证 + 生产回退优先级 | ☑ |
| 58 | 硬约束四条均被正面回应（中文注释/老人易用/5 值状态机不变/family_id 隔离） | ☑ |
| 59 | 未决项与既存隐患进入 design「已知风险」/ Open Questions，未被静默吞掉 | ☑ |

## 九、DEFERRED（用户侧 / 需真机或需另立 change）

- [ ] 真机联调：测试号向公众号发文本 → 收「收到，正在理解…」→ 收客服消息链接 → 点开免密进详情页 → 确认 → 任务列表出现任务。**依赖 cloudflared 隧道持久化**（当前挂账，隧道重启域名即变）。
- [ ] 验证**个人测试号是否开放客服消息 `message/custom/send`**；若不开放，回推降级方案需重议（回到模板消息 → 触发第 4 模板 ID 挂账）。
- [ ] 多家庭用户正解：`users.current_family_id`（跨切面，另立 change）。
- [x] B-6 `save_proposal` 缺 family 作用域的硬化（既存越权面，本 change 只记录不修）。**已于 2026-09-26 随 commit 515a226 修复并复验**：`InternalAgentController` 的 save-proposal 端点新增 `@RequestParam("family_id")`，改用 `findLockedScoped(id, familyId).filter(ACTIVE)`，`familyId` 与 session 不一致即抛异常，并保留 revision 乐观锁。
- [ ] `wechat_inbound_message` 的 TTL/归档策略（原文属 PII，长期留存需定保留期）。
- [ ] 口语日期（「下礼拜六」「后天」）拆解准确率评测样本与 prompt 调优。
- [ ] 微信安全模式（AES 加解密 + `msg_signature`）支持——本 change 只承诺明文模式。
- [ ] 既存挂账（**勿顺手做**）：第 4 模板 ID 空值、本机 3 个模板 ID 同值待替换、仓库无 remote 故 push 物理不可行。
