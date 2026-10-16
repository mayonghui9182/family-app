# Tasks: Change 14 - 微信私聊消息自动对接 AI 拆解

> 执行约定：每个 Task 单会话内可验证；TDD 顺序固定「失败测试 → 实现 → 验证 → 提交」。
> shell 统一走 `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && ..."`；中文提交信息走「仓库内临时文件 + `git commit -F` + 用后 rm」。

- [x] **Task 1：V12 迁移——`wechat_inbound_message` 幂等队列表**

  > 实施偏离（2026-09-13，已验证）：
  > 1. 测试类落点从 `wechat/WechatInboundMessageMigrationTest` 改为仓库既有约定 `com.familyapp.V12MigrationTest`（V6/V9/V10/V11 皆然）。
  > 2. `idx_wim_pending` 由部分索引改为普通复合索引 `(status, received_at)`——H2 不支持 `CREATE INDEX ... WHERE`，且该索引非唯一、partial 仅省空间；列序由用例8 静态 DDL 断言钉死。
  > 3. 新增 `family_id`/`user_id` 两列（spec「落库的家庭归属与建草稿 family_id 一致」要求受理时裁决结果需持久化，供作业复用）。
  > 4. 验证补强：除单测外，另做 PG 真机 `BEGIN…ROLLBACK` DDL 干跑 + Flyway 实迁 + `\d` 结构核对。

  **Step 1：失败测试**
  新增 `backend/src/test/java/com/familyapp/wechat/WechatInboundMessageMigrationTest.java`：断言 `information_schema` 中存在表 `wechat_inbound_message`、列 `msg_id/status/session_id/attempts/skip_reason`，存在 UNIQUE 约束 `uq_wim_msg_id` 与部分索引 `idx_wim_pending`，且 `status` CHECK 约束恰好接受 5 值（`PENDING/RUNNING/DONE/FAILED/SKIPPED`）、写入第 6 值被拒。

  **Step 2：实现**
  新增 `V12__Add_Wechat_Inbound_Message.sql`（BIGSERIAL 主键、`openid` 不建外键以保留未绑定痕迹、`received_at/created_at/updated_at` TIMESTAMPTZ、中文 COMMENT）；同步 `WechatInboundMessage` 实体 + `WechatInboundMessageRepository`（`existsByMsgId`、`findTop20ByStatusOrderByReceivedAtAsc`、`findByMsgId`）。

  **Step 3：验证**
  `docker compose build backend && docker compose up -d backend`，日志出现 `Migrating schema "public" to version "12 - Add Wechat Inbound Message"` + `now at version v12`；跑 Step 1 测试绿。

  **回滚策略（DB schema 任务强制项）**
  - 逆向脚本（本地/预发手动执行，不入 Flyway 目录，避免与 `validateOnMigrate` 打架）：
    ```sql
    DROP INDEX IF EXISTS idx_wim_pending;
    DROP TABLE IF EXISTS wechat_inbound_message;
    ```
  - 回滚验证：执行后 `SELECT count(*) FROM information_schema.tables WHERE table_name='wechat_inbound_message'` = 0，且 `flyway_schema_history` 中 version 12 行被删除（`DELETE FROM flyway_schema_history WHERE version='12'` 后再启动 backend，Flyway 应重新成功应用 V12）。
  - 生产回退优先「关开关不删表」：`WECHAT_INTAKE_ENABLED=false` 即刻静默，表保留供排障（`skip_reason` 是唯一线索来源）。

  **Step 4：Commit** `feat(change14/task1): 新增 V12 微信入站消息幂等队列表`

- [x] **Task 2：backend 受理端点——`POST /api/internal/wechat/inbound-message`**

  > 实施偏离（2026-09-13，已验证：单测 12/12 绿 + 真机 curl）：
  > 1. **用例⑦ 断言订正**：`InternalSecretInterceptor` 的真实契约是 **HTTP 401 + code 40101（缺头）/40102（不匹配）**，不是本文原写的 403。测试按真机契约断言，并另测 401 响应耗时 3.2ms。
  > 2. **9 用例拆成 3 个测试类**（共 12 用例）：秘钥校验由 Profile 决定（`test` 放行、`internal-api-test` 真校验），而 `wechat.intake.enabled` 只能类级注入，两者在同一类内互斥 ⇒ `WechatInboundInternalControllerTest`(9) / `WechatInboundIntakeDisabledTest`(1) / `WechatInboundSecretGuardTest`(2)。
  > 3. **新增防御分支 + 用例⑩**：`msg_id` 空白 → **400 + 40099 且不落库**。原计划未覆盖，但空 `msg_id` 直插会撞 `uq_wim_msg_id`（第二条空 id 起 500），微信侧重推会把端点打成 5xx。
  > 4. **新增用例①b**：唯一家庭 role=**OWNER** 同样受理。`AuthService.register` 建的成员默认 OWNER，只测 ADULT 会漏掉最常见形态；裁决表只拦 CHILD。
  > 5. **控制器命名取本文 `WechatInboundInternalController`**（`proposal.md` 文件清单写的是 `WechatIntakeInternalController`，以本文为准，proposal 待订正）；复用既有 `/api/internal/wechat` 前缀 ⇒ `WebConfig` 零改动即被秘钥拦截器守护。
  > 6. **用例⑧ 加硬断言**：除 `status=SKIPPED/intake_disabled` 外，另断 `user_id IS NULL`，把「开关短路发生在 `loginByOpenid` 之前」变成可证伪条件（真机 5 行探针同样 `family_id/user_id` 皆空，互证）。
  > 7. **Step 3 耗时取证方式**：backend 容器未发布端口 ⇒ 在容器内 `curl 127.0.0.1:8080` 测得稳态 **6.3–7.0ms**（首请求 86.6ms 为 JIT/DispatcherServlet 冷启动）、400 2.0ms、401 3.2ms，满足 < 50ms。受理成功分支无法在 prod 真机走通（`wechat_bind` 空表、造绑定行等于污染生产数据），改由单测①/①b + 结构论证覆盖：端点内 `loginByOpenid → buildLoginResponse → ensureAccessToken` 全是本地 DB 与 `SecureRandom`，零 HTTP 外呼、零 LLM。探针行验证后已 `TRUNCATE` 清零。

  **Step 1：失败测试**
  `WechatInboundInternalControllerTest`（MockMvc）：① 已绑定 + 唯一 ADULT 家庭 → `data.accepted=true` 且落一行 `PENDING`；② 未绑定 openid → `accepted=false, reason=not_bound`；③ 无家庭 → `no_family`；④ 唯一家庭 role=CHILD → `role_denied`；⑤ 两个家庭 → `multi_family`；⑥ 同 `msg_id` 重推两次 → 第二次 `reason=duplicate` 且表内仍 1 行；⑦ 缺 `X-Internal-Secret` → 403；⑧ 开关 `wechat.intake.enabled=false` → `reason=intake_disabled` 且**不落 `PENDING`**（落 `SKIPPED` 留痕）；⑨ content 超 200 字符 → 截断入库。

  **Step 2：实现**
  `WechatInboundInternalController`（`/api/internal/wechat` 同族前缀，复用 `InternalSecretInterceptor`）+ `WechatIntakeService.accept(openid, msgId, content, receivedAt)`：`wechatService.loginByOpenid(openid)` → 按 D3 裁决表分支 → 幂等 `existsByMsgId` 短路 → `repository.save(...)`。裁决与落库同事务，**端点内零 HTTP 外呼、零 LLM**。

  **Step 3：验证**
  `./gradlew test --tests '*WechatInbound*'`；人工确认响应耗时 < 50ms（`time curl`）。

  **Step 4：Commit** `feat(change14/task2): 微信入站消息受理端点与家庭归属裁决`

- [x] **Task 3：调度池扩容 + 作业骨架 `WechatIntakeJob`**

  > 实施偏离（2026-09-13，已验证：单测 11/11 绿 + `*Wechat*` 回归 10 类 54 用例全绿 + 真机线程直证）：
  > 1. **用例 5 → 11**：补 ⑥ 遗留 `RUNNING`（attempts 已达上限）不重捞、⑦ `wechat.intake.enabled=false` 整批零动作、⑧ AI 总开关关 → `SKIPPED/ai_disabled`（D8）、⑨ 缺 `family_id` → `FAILED` 且 `last_error` 含 `family_id`（硬约束 4「宁拒不猜」）、⑩ 同批两行逐条隔离、⑪ 调度池实际为 4 线程（D2 硬前置）。
  > 2. **捞取范围含 `RUNNING`（本文 Step 1 只写 `PENDING`）**：进程中途被杀会留下 `RUNNING` 死行，`attempts` 有余额即应自愈重捞；已达上限的 `RUNNING` 不捞（人工排障对象）。`findProcessable` 与 `claim` 的 WHERE 谓词严格一致，否则自愈路径根本捞不到。
  > 3. **前置判定置于抢占之前**：AI 关 / 缺 `family_id` 两类行不消耗 `attempts` 额度——这类情况重试一万次结果也一样，先判定再 `claim`。
  > 4. **`createDraftFromMessage` 本 Task 只落骨架并显式抛异常**（Task 4 替换实现）。刻意不返回 `null`：返回 `null` 会让作业走成功路径标 `DONE`，用户以为草稿已建好，属最难发现的一类静默错误。
  > 5. **新增配置 `wechat.intake.poll-initial-delay-ms`（默认 15s，env `WECHAT_INTAKE_POLL_INITIAL_MS`）**：给 Flyway/连接池/微信 token 预热留窗口，避免冷启动白吃一次重试额度。批量大小由作业显式传 `PageRequest.of(0, 20)`，故**未**引入 `spring.data.web.pageable.*`（那套配置只在 Web 层解析 `Pageable` 时相关）。
  > 6. **测试形态两处坑**：(a) 类上不加 `@Transactional`——否则作业小事务并入测试事务（REQUIRED），什么都不提交，用例⑤ 失去意义；清理靠 `@BeforeEach` 全表 DELETE。(b) mock 上重复打桩（`when(...).thenThrow` 与 `doThrow(...).when` 皆然）会被 Mockito 先用 `null` 实参真实触发上一个 Answer（栈：`NullResultGuardian → StubbedInvocationMatcher`）而 NPE ⇒ 改为「一次打桩 + 可变 `Function<WechatInboundMessage, Long>` 字段换行为」。
  > 7. **一次真实的配置丢失事故**：`application.yml` 的 `spring.task.scheduling.pool.size` 首轮编辑未落盘（UNC 写入竞态），被用例⑪ 当场逮到 `expected: <4> but was: <1>`。教训：`assertInstanceOf(ThreadPoolTaskScheduler)` 在池大小为默认 1 时同样通过（Boot 始终提供该 bean），真正的把关点是数值断言；跨 UNC 路径编辑既有文件后必须 Grep 复核。
  > 8. **Step 3「观察两轮 tick 时间戳」不可执行，改法**：既有 6 处 `@Scheduled` 中 5 处是 cron（02:00 / 02:30 / 03:00 / 07:30 / 08:00），验证窗口内不会触发；唯一 `fixedDelay` 的 `expireDrafts`（300s）空批不打日志。改为**机制直证**：`docker compose exec backend sh -c 'cat /proc/7/task/*/comm'` 见 `scheduling-1`…`scheduling-4` 四条线程并存（扩容前恒为 1 条），叠加用例⑪ 与启动日志无 ERROR、`/actuator/health` = `{"status":"UP"}`、Flyway 仍 v12 无新迁移。cron 作业的真触发需等到明日 02:00，本 Task 不声称已观测。

  **Step 1：失败测试**
  `WechatIntakeJobTest`：① 无 `PENDING` 时零外呼；② 捞到 1 行 → 状态流转 `PENDING → RUNNING → DONE`、`session_id` 回写；③ 拆解抛异常且 `attempts<3` → 回 `PENDING` 且 `attempts+1`；④ `attempts=3` 耗尽 → `FAILED` + `last_error`；⑤ 作业方法内**不占用事务跨越外呼**（断言 RUNNING 落库先于外呼可见）。

  **Step 2：实现**
  `application.yml` 加 `spring.task.scheduling.pool.size: ${SPRING_SCHEDULING_POOL_SIZE:4}` + 注释说明「默认单线程会被 LLM 长调用堵死」；`WechatIntakeJob`：`@Scheduled(fixedDelayString="${wechat.intake.poll-fixed-delay-ms:5000}")`、开关早退、逐条隔离 try-catch（一条失败不影响同批）。

  **Step 3：验证**
  `./gradlew test --tests '*WechatIntakeJob*'`；`docker compose up -d backend` 后日志确认既有 6 个 `@Scheduled` job 未被新 job 饿死（观察两轮 tick 时间戳）。

  **Step 4：Commit** `feat(change14/task3): 入站作业轮询与调度线程池扩容`

- [x] **Task 4：建草稿——`WechatIntakeService.createDraftFromMessage`**

  **Step 1：失败测试**
  `WechatIntakeDraftTest`：① 原文 `"周六带爸去复查"` → `createDraft("TASK_CREATE", Map.of("title", 原文), familyId, userId)`，session 落 `ACTIVE`、`init_task_title` == 原文、`family_id` == 裁决家庭；② 原文 250 字符 → 截断 200；③ `messages` 列 SHALL 保持 `[]`（**断言不给死列引入写入**）；④ 建草稿后 `tasks` 表行数不变（草稿期零业务表写入铁律）。

  **Step 2：实现**
  作业内串接 `createDraft` → 把 `sessionId` 回写入站行 → 再调拆解。

  **Step 3：验证**
  `./gradlew test --tests '*WechatIntakeDraft*'`。

  **Step 4：Commit** `feat(change14/task4): 入站消息自动创建 ACTIVE 拆解草稿`

  **实施偏离与记录（2026-09-13）**
  1. 用例由 4 个增到 **5 个**：新增 ⑤「真实 bean 串接」——存一行 `PENDING`，直接 `new WechatIntakeJob(真实 service, 真实 repo, ...).pollInboundQueue()`，断言行落 `DONE` 且 `session_id` 指向**真实存在**的 `ACTIVE` 草稿。理由：Step 2 的「回写 sessionId」若无断言就是空口白话，而回写错值会让 Task 8 的回推深链成为死链。
  2. `session_type` 实参用 `DecomposeSessionType.TASK_CREATE.name()` 而非字面量 `"TASK_CREATE"`：把拼写错误从「运行时 40001」提前到编译期，且与 `createDraft` 的枚举校验分支保持单一事实源。
  3. `createDraftFromMessage` **不加** `@Transactional`：作业是在事务外调用它的（D2「事务绝不跨外呼」），事务由 `createDraft` 自身承担，只覆盖这一次建草稿。
  4. 用例③ 改走 `sessionRepo.findById(...).getMessages()` 而非裸 SQL：H2 下 JSONB 以字符串列存在，SQL 断言会退化成字符串比较，验不出「空数组」这个语义。
  5. 清理不用 `TRUNCATE`/全表删：按专属 `family_id=9100` + `openid='o-draft-test'` 圈定 DELETE 范围。同一 H2 库在同一 JVM 内跨测试类共享，全表清理会波及其他类的既有行。
  6. RED 阶段取证（失败原因须为「功能缺失」）：①②③④ 抛 `IllegalStateException: 建草稿链路尚未实现（Change 14 Task 4）`，⑤ `expected: <DONE> but was: <PENDING>`（占位异常被作业 `markRetry` 回队）——五例全红且红得正确。
  7. GREEN 结果：`WechatIntakeDraftTest` `tests="5" failures="0" errors="0" time="0.423"`；`--tests '*Wechat*'` 回归 11 个类 **59 用例全绿**、`BUILD SUCCESSFUL in 13s`。真机复验：`bootJar` → `compose build` → `up -d` → `Started FamilyAppApplication in 4.931 seconds`、日志 0 条 ERROR、`/actuator/health` `{"status":"UP"}`、`scheduling-1..4` 四条线程并存（新构造参数未引入 bean 循环依赖）。
  8. Step 2 的「**再调拆解**」不在本 Task 落地——拆解调用依赖 Task 6 的 `decomposeDraft` 客户端；本 Task 止于「建草稿 + sessionId 回写」。
  9. **事实订正**：`docker-compose.yml` 的 backend env 块（L61-78）**没有任何** `WECHAT_INTAKE_*` 条目（上一轮 handoff 记成「L198 已有 `WECHAT_INTAKE_ENABLED=false`」，是错的）。当前生产态的「关」来自 `application.yml` L73 的 `${WECHAT_INTAKE_ENABLED:false}` 默认值；真机已验证 0 条 `入站队列轮询` 日志。接线仍归 Task 13。

  **环境注意（非代码问题）**：`RunCommand` 复用微信侧终端时，第二条起会返回 exit `-4058` 且无输出（连 `pwd` 都一样）；对策是每条命令都开新终端。

- [x] **Task 5：agent-service A-2 透传 `session_id`（修复既有不对称）**

  **Step 1：失败测试**
  `agent-service/tests/test_chat_buffered.py`：① `POST /api/v1/chat` 带 `session_id=7` → 编排器收到 `session_id=7`、`save_proposal` 被调、响应 `code==0`、`data.task_id is null`、`data.subtasks` 非空；② 不带 `session_id` → 走 B-1 `_persist`（**回归护栏：现有行为逐字不变**）；③ auth 失败 → 不调 LLM、返回 `code` 为对应错误码。

  **Step 2：实现**
  `chat_routes.py` 的 `chat_buffered`：`decomposer.stream(...)` 增补 `session_id=body.session_id`（一行）。

  **Step 3：验证**
  `cd agent-service && pytest -q`（全量，确认 A-1/`run_buffered` 无回归）。

  **Step 4：Commit** `fix(change14/task5): A-2 缓冲端点补齐 session_id 透传`

  **实施偏离与记录（2026-09-13）**
  1. 新增文件 `agent-service/tests/test_chat_buffered.py`，3 用例照 Step 1 原文；为证明「`session_id` 真的传到 B-6」在用例文件内定义 `_RecordingBackend(FakeBackend)` 子类记录 `save_proposal(session_id, ...)`，**未改共享 `conftest.py`**（基类只有 `saved_proposal`，记不下 session_id，改它会波及全部既有测试）。
  2. 用例① 额外加了 Change 14 最想要的断言：`backend.calls == []` —— 草稿模式下 `create_task`/`create_subtasks_batch`/`add_note` 一次都不许发生。
  3. 用例② 的回归护栏取精确调用序列 `["create_task", "create_subtasks_batch", "add_note"]`（`VALID_DECOMPOSE_JSON` 恰有 1 条 note），并断言 `proposal_calls == []` 防「反向误走 B-6」。
  4. RED 结果 `1 failed, 2 passed`：②③ 是护栏、修前本就绿，① 才是功能缺失。红因原文 `AssertionError: 草稿模式绝不能返回真实 task_id / assert 501 is None` —— 直接证明修复前「A-2 带 session_id 会真建任务」（D5 破口），红因归因干净。
  5. Step 2 说的「一行」实际含两处：参数 `session_id=body.session_id` + 把单行 4 参数调用改写成多行（ruff line-length 120）；另给 `chat_buffered` docstring 补了漏传后果的中文说明。
  6. Step 3 用 `.venv/bin/pytest`（`~/.local/bin/pytest` 与 venv 版本易混，venv 才是项目环境）；跑单文件须带 `--no-cov`，否则撞 `pyproject.toml` 的 `--cov-fail-under=80` 门禁产生假失败。全量：**94 passed、覆盖率 94.97%**（门禁 80% 通过）。
  7. 加跑仓库既有门禁 `.harness/sop.md` 要求的 `ruff check .` + `mypy .`：改动文件（`chat_routes.py`、`test_chat_buffered.py`）**零错误**；但仓库存量债 = 1 个 E501（`app/connectors/db_reader.py:69`）+ 19 个 mypy 错误（`tests/conftest.py` 等 7 个文件）。`git diff --stat HEAD` 显示这些文件本轮未被动过 ⇒ 判定为存量，**按漂移纪律不顺手修**，转入挂账。
  8. 真机取证（防「源码改了镜像没重建」的假绿）：`docker compose build agent-service` → `up -d` → 日志 `Application startup complete` + `Uvicorn running on http://0.0.0.0:8700`、近 3 分钟 `grep -ci error` = 0；再在容器内 `python -c` 打 `/healthz` = `{"status":"ok"}`，并用 `inspect.getsource(chat_buffered)` 断言镜像里的源码含 `session_id=body.session_id` → `True`。
  9. Step 4 的 commit **未执行**（等用户明示），Task 1~5 产物全部留在工作树。

- [x] **Task 6：backend → A-2 客户端 `decomposeDraft`**

  **Step 1：失败测试**
  `AgentCallbackClientTest`（MockRestServiceServer）：① 请求 URL == `/api/v1/chat`、头含 `X-Family-Id` 与 `X-Access-Token`（**不是 `X-Internal-Secret`**）、body 含 `message` 与 `session_id`；② 响应 `code==0` → 返回 subtasks 列表；③ `code!=0` → 抛带中文 msg 的异常（交作业侧按重试处理）；④ `code==0` 但 `subtasks` 空 → 视为失败（防「拆解成功但啥也没有」的空草稿被当 DONE 推链接）；⑤ 读超时 60s 配置生效。

  **Step 2：实现**
  `AgentCallbackClient` 新增 `decomposeDraft(Long familyId, String accessToken, String message, Long sessionId)` + 独立 `RestTemplate`（connect 5s / read 60s）。

  **Step 3：验证**
  `./gradlew test --tests '*AgentCallbackClient*'`。

  **Step 4：Commit** `feat(change14/task6): backend 调用 A-2 草稿模式拆解`

  **实施偏离与记录（2026-09-13）**
  1. Step 1 列了 5 个用例，实做 **7 个**：⑤ 拆成「常量断言」+「超时真生效的行为断言」两条；另加 ③b「4xx 错误体里取中文 msg」（真机驱动，见下条）。
  2. **真机取证推翻了「A-2 失败一律 HTTP 200 + `code!=0`」的假设**。在 agent-service 容器内用 httpx 直打：缺 `X-Access-Token` → `HTTP 401 {"code":40103,"msg":"登录已过期，请重新登录"}`；缺 `X-Family-Id` → `HTTP 400 {"code":40099,...}`；错路径 → `404 {"detail":"Not Found"}`；B-1 缺字段 → `422`。只有编排期失败才回 HTTP 200 + `code!=0`。故实现里除解析 envelope 外，另 catch `RestClientResponseException` 并从错误体提中文 `msg`（`draftHttpFailure`），非 JSON 才落回 `getStatusText()`。③b 的 RED 红因原文：`Expecting actual: "草稿拆解调用失败：401 Unauthorized: "{"code":40103,...}"" to end with: "登录已过期，请重新登录"`。
  3. 测试错误码曾按记忆写成 `40101/登录已失效，请重新绑定`，回源 `agent-service/app/core/errors.py` 核对后改为真实的 `AuthError=40103 / "登录已过期，请重新登录"`。跨服务错误码一律回源核对，不采信 spec 或记忆。
  4. 超时可观性：`SimpleClientHttpRequestFactory`（Spring 6.1.11）无 getter，读不回已设超时。改双轨断言——`DRAFT_CONNECT_TIMEOUT_MS==5_000 && DRAFT_READ_TIMEOUT_MS==60_000` 常量断言，加一条用包私有 `buildTemplate(1_000, 200)` 打「只 accept 不响应」的 `ServerSocket(127.0.0.1:0)`、断言 5s 内抛。实测该用例耗时 0.215s，与 200ms 读超时吻合，证明超时真实生效。
  5. 为此把 `buildTemplate` 由 private 改包私有，并新增包私有测试构造器 `(baseUrl, internalSecret, draftTemplate)`；生产构造器补 `@Autowired`（照 `WechatNotifyClient` 惯例，多构造器时 Spring 需显式指定）。B-1 30s / B-2 60s 既有超时口径逐字未动。
  6. `DraftSubtask` 只映射 sink 真正消费的 `title/description/sort_order` 三字段，不设 `estimated_minutes` 等 P-4 形态字段（A-2 响应里多余字段为 null/[]）。
  7. 新挂账：`ChatStreamRequest.message` 有 `max_length=2000` 校验，超长微信原文会被 422 拒。截断策略属作业侧职责，交 Task 8/9 一并定，本 Task 不越界。（**Task 6b 已关闭此挂账**：受理侧把 `content` 截断到 200 字符，本链路不可达。）
  8. **规格缺口（需用户裁决）**：Task 4 偏离 8 明确「建草稿后再调拆解」不在 Task 4 落地、留给 Task 6，而 Task 6 原文只写客户端——**没有任何 Task 认领「作业侧把 decomposeDraft 串进 WechatIntakeJob」**（Task 8 只管回推）。本 Task 严格不越界改 `WechatIntakeService`。建议新增 Task 6b 或并入 Task 8。另注：作业期无 HTTP 请求上下文，`accessToken` 来源拟用 `wechatService.loginByOpenid(openid)` 换取，属待批准设计。
  9. 验证证据：RED = 编译期 `12 errors`（`DraftSubtask`/`decomposeDraft`/超时常量/`buildTemplate` 全部 cannot find symbol）；GREEN = 聚焦 `./gradlew test --tests '*AgentCallbackClient*'` BUILD SUCCESSFUL，JUnit XML `tests="7" failures="0" errors="0"`；因改动共享类，主动跑全量 `./gradlew test --continue` → BUILD SUCCESSFUL in 21s，61 份 XML 汇总两次：中途（含本 Task 前 6 用例）418 tests，加 ③b 后 **419 tests / 0 failures / 0 errors / 0 skipped**。即本 Task 净增 7、回归 0。
  10. Step 4 的 commit **未执行**（等用户明示），Task 1~6 产物全部留在工作树。

- [x] **Task 6b：作业侧把 `decomposeDraft` 串进 `WechatIntakeJob`（补 Task 4/6 之间的缺口）**

  > 本 Task 由用户裁决新增（2026-09-13，选推荐方案「新增独立 Task」）。起因：Task 4 把「建草稿后再调拆解」
  > 推给 Task 6，Task 6 原文只交付客户端，无人认领「串接」这一步。

  **前置事实（已取证，决定本 Task 范围）**
  - 拆解结果**不由 backend 落库**：`agent-service/app/services/decomposer.py` 在 `session_id` 非空时自行调
    B-6 `save_proposal` 写 `agent_decompose_sessions.proposed_tasks`。backend 只负责「触发 + 校验返回非空」。
  - 作业期无 HTTP 上下文 ⇒ 票据走 `WechatService.loginByOpenid(openid)` → `LoginResponse.accessToken()`
    （该方法已具备「有 token 复用、被 logout 清过则补发」语义）。
  - 受理侧已把 `content` 截断到 200 字符 ⇒ A-2 `message` 的 2000 上限在本链路不可达，Task 6 偏离 7 的挂账关闭。

  **Step 1：失败测试** 新建 `WechatIntakeDecomposeTest`，`WechatIntakeService` 走真实构造（纯 Mockito，不起 Spring 上下文）：
  ① 成功链路：以本次新建草稿的 `session_id` 调 `decomposeDraft`（ArgumentCaptor 钉 familyId/accessToken/原文/sessionId 四参数）；
  ② 取票据必须用行上的 `openid`，并把 `LoginResponse.accessToken()` 原样传下去；
  ③ **草稿复用**：行上已有 `sessionId` 时不得再建草稿（`createDraft` 零调用），拆解直接用该 id；
  ④ **中途中转**：新建草稿后立即把 `session_id` 落到入站行（否则回队重建 ⇒ 一条消息长出多个草稿）；
  ⑤ **票据缺失硬失败**：`loginByOpenid` 为空 → 抛中文可展示异常且**绝不调用 A-2**（退化成不带 `session_id` 的调用等于直写 `tasks`，破 D5）；
  ⑥ **session_id 为空硬失败**：不得以 `null` session_id 调 A-2（同上，D5 铁律的最后一道闸）；
  ⑦ A-2 抛异常时向上传播（重试语义归作业侧，本层不吞）、且草稿表与 `tasks` 表零新增写入。
  另在 `WechatIntakeDraftTest` 补一条真机级用例：真实作业 + mock 客户端首轮失败 → 行回 `PENDING` **但 `session_id` 已挂上**
  → 次轮成功 → `DONE`，全程 `agent_decompose_sessions` 只有 1 行。

  **Step 2：最小实现** `WechatIntakeService` 新增 `Long decomposeInbound(WechatInboundMessage row)`
  （复用或新建草稿 → 取票据 → 调 `decomposeDraft` → 返回 sessionId）；构造器注入 `AgentCallbackClient`；
  `WechatInboundMessageRepository` 新增 `attachSession(msgId, sessionId, now)`（沿用既有 CAS 批量 UPDATE 风格，不读实体改写）；
  `WechatIntakeJob.处理单条` 的 `createDraftFromMessage` 改为 `decomposeInbound`（作业仍是纯状态机，不掺业务编排），
  `WechatIntakeJobTest` 的打桩同步改名（注入失败/计数的语义不变）。

  **Step 3：验证** `./gradlew test --tests '*WechatIntakeDecompose*' --tests '*WechatIntakeJob*' --tests '*WechatIntakeDraft*'`，
  随后 `./gradlew test --continue` 全量零回归（改的是作业与服务的共享协作方）。

  **Step 4：Commit** `feat(change14/task6b): 作业侧串接 A-2 拆解并复用草稿`

  **完成判据**：`proposed_tasks` 由 agent 侧写入、backend 侧 `tasks` 仍零写入；单条消息在任意失败组合下最多只有 1 份草稿。

  **实施偏离与记录（2026-09-13）**
  1. Step 1 列了 7 条，实做 **10 条**（`WechatIntakeDecomposeTest`，纯 Mockito、不起 Spring 上下文）：⑤ 拆成「`loginByOpenid` 为空」与「token 为空白」两条（文案分别断言含「绑定」「公众号」）；① 另拆出「草稿建在裁决家庭下」一条；⑦ 另拆出「A-2 返回空列表也算失败」一条（异常路径按 `hasMessageEndingWith` 断言原样传播、本层不吞）。
  2. **`attachSession` 破例自带 `@Transactional`**：`decomposeInbound` 依 D2「事务绝不跨外呼」不能加 `@Transactional`，而 `@Modifying` 方法脱离事务会抛 `TransactionRequiredException` ⇒ 把 `@Transactional` 直接标在**仓储接口方法**上（Spring Data 的 `TransactionalRepositoryProxyPostProcessor` 会采纳接口方法的事务属性、自开短事务）。曾考虑给作业再包一层 `TransactionTemplate`，但那要改 `WechatIntakeJob` 构造器、波及 `WechatIntakeDraftTest` 里 6 处手工 `new WechatIntakeJob(...)`，属过度设计，已弃。注意**全仓此前没有任何 `@Modifying`**（本 change 的 CAS 批量 UPDATE 是新造风格、无先例可循），所以「短事务真的提交了」只能由真库证明：`WechatIntakeDraftTest` ⑥ 断言首轮失败后入站行 `session_id` 非空——若事务未提交该值必为 NULL。
  3. `attachSession` 的 WHERE 额外带 `and m.sessionId is null`（CAS 谓词），防并发或重跑覆盖已挂上的草稿。`markDone/markRetry/markFailed` 等既有迁移仍由作业 `TransactionTemplate` 逐处包，仓储类 javadoc 已同步记下这条例外。
  4. **既有 `WechatIntakeDraftTest` ⑤ 被本 Task 打破并改造**：它原先直接用容器里的真实 `WechatIntakeService`，Task 6b 后作业链路要多取一次票据（夹具 openid 未绑定 ⇒ 硬抛）并真打 `localhost:8700` 的 A-2。改为「真库 + 只替换两个外呼协作方（`WechatService`、`AgentCallbackClient`）」，为此引入 `@Autowired DecomposeSessionService` 手工组装服务；不补真实 register→ticket→bind 绑定夹具（成本高且与本 Task 无关）。⑤ 另用 `ArgumentCaptor` 钉死「外呼所带 session_id 就是回写进入站行的那一个」，并直接断言 `tasks` 零写入。
  5. **诚实标注**：Step 1 追加的真机用例 ⑥（首轮失败回队 → 复用同一草稿 → 次轮 `DONE`、全程草稿表恒为 1 行）写在实现**之后**，没有独立 RED；其等价红灯已由单测 ④（新建草稿后先中转 `session_id` 再外呼）覆盖。⑥ 的增量价值只有在真库上才拿得到（验证 `attachSession` 的提交真实生效），故保留，并在此记录它不满足严格 RED→GREEN 次序。
  6. Step 2 的改名比计划多一点：`WechatIntakeJobTest` 除把打桩从 `createDraftFromMessage` 换成 `decomposeInbound`，中文标识符 `建草稿`→`拆解`、`建草稿成功`→`拆解成功` 同步改名（11 用例语义与断言逐字未动，含「RUNNING 先提交再外呼」的 `外呼时状态` 取证与 ⑪ `assertEquals(4, poolSize)`）。`WechatIntakeJob.处理单条` 的 javadoc 补一句：失败时行上 `session_id` 可能已挂草稿，是有意为之，回队重跑会复用它。
  7. Step 3 验证证据：RED = 编译期 **13 errors**（`decomposeInbound` / `attachSession` 等 cannot find symbol，测试先于实现）；GREEN 聚焦三类 → `BUILD SUCCESSFUL in 8s`，JUnit XML `WechatIntakeDecomposeTest tests="10"`、`WechatIntakeDraftTest tests="6"`、`WechatIntakeJobTest tests="11"`，全部 `failures="0" errors="0" skipped="0"`；随后全量 `./gradlew test --continue` → `BUILD SUCCESSFUL in 23s`、**62 份 XML / 430 tests / 0 failures / 0 errors / 0 skipped**（Task 6 后基线 419，净增 10 + 1）。
  8. 完成判据落地口径：拆解结果由 agent-service 侧经 B-6 `save_proposal` 自行写 `proposed_tasks`，backend 只做「触发 + 校验返回非空」，⑤ 直接断言 `tasks` 零写入（D5 守住）；「一条消息最多 1 份草稿」由 ⑥ 在真库上证明。
  9. 顺带关闭 Task 6 偏离 7 的挂账（`ChatStreamRequest.message` 2000 上限）：受理侧已把 `content` 截断到 200 字符，本链路不可达。
  10. Step 4 的 commit **未执行**（等用户明示），Task 1~6b 产物全部留在工作树。

  **本 Task 三条硬约束（已写死在测试里，后续 Task 不得放宽）**：① A-2 绝不带 `null` session_id 外呼（否则退化成 B-1 直写 `tasks`，破 D5）；② 票据缺失必须在建草稿之前失败（不留空草稿）；③ 新建草稿必须立即中转 `session_id`（防一条消息长出多份草稿 ⇒ 多条回推）。

- [x] **Task 7：wechat-service 客服消息能力 `/internal/kefu/send`**

  **Step 1：失败测试**
  `wechat-service/tests/test_kefu.py`：`send_text` 正常发送 / 40001 刷新重试一次 / 43004 不重试 / 网络异常单条隔离；端点测试：无密钥 40300、body 格式错 400、单条失败仍 `code:0` + `results[].ok:false`、**任何用例都不出现 5xx**。

  **Step 2：实现**
  新增 `app/wechat/kefu.py`（`CUSTOM_SEND_PATH = "/cgi-bin/message/custom/send"`、`send_text`、复用 `WechatTokenManager`）；`internal_routes.py` 新增 `POST /internal/kefu/send`（body `{messages:[{openid, content}]}`，形态照 template）。

  **Step 3：验证**
  `cd wechat-service && pytest -q`。

  **Step 4：Commit** `feat(change14/task7): 公众号客服消息发送与内部批量端点`

  **实施记录（2026-09-13 实测回填）**
  - RED 证据：`pytest -q tests/test_kefu.py --no-cov` → **11 failed**，红因全部是路由不存在（`assert 404 == 200` /
    `KeyError: 'results'` / 期望 403、400 实得 404）⇒ 确认测的是端点契约，不是自证实现。
  - GREEN 证据：聚焦 **12 passed**；全量 **42 passed**、coverage **91.22%**（阈值 80）、ruff `All checks passed!`、
    `app/wechat/kefu.py` 覆盖率 **100%**。
  - 偏离 1：Step 1 列了 4 类 `send_text` 断言 + 4 类端点断言，实做 **12 条用例**，补的 5 条是：token 持续失效只重试一次
    （额度保护）、45015 超 48h 窗口原样透码、token 首取失败给中文提示且零出站、空数组批次仍 `code:0`、刷新后重发撞网络异常。
  - 偏离 2：**自建 `KefuResult` 而非复用 `template.SendResult`**。后者带模板消息专属的 `skipped` 字段，且
    `_TOKEN_INVALID_ERRCODES` 是私有常量——跨模块导入等于把 template 的内部实现升级成公共契约。
  - 偏离 3：端点校验比 template 端点**更严**——`openid`/`content` 必须是 `strip()` 后非空的字符串。空文本客服消息微信直接
    报错，白耗一次额度。
  - 偏离 4：**45015 不设特殊分支**，只把 `errcode` 原样透出。D6 分工：wechat-service 只管「能不能发出去」，
    「发不出去对用户说什么」归 backend 话术层（Task 8）。
  - 偏离 5：门禁环境坑——`wechat-service` **无自带 `.venv`**，走 `../agent-service/.venv/bin/python`（Change 12 先例）；
    跑单文件必须带 `--no-cov`，否则 `addopts` 里的 `--cov-fail-under=80` 会因单文件覆盖率低而误红。
  - 挂账（不顺手修）：`mypy .` 报 **10 errors in 5 files**，逐条核对全在本轮未触碰的存量测试文件
    （`test_oauth.py:7,21`、`test_menu.py:7,30`、`conftest.py:38`、`test_token_manager.py:13,19`、`test_template.py:11,22`），
    `kefu.py` / `test_kefu.py` / `internal_routes.py` 零错误 ⇒ 本轮零新增，属 wechat-service 存量类型标注债。
  - 偏离 6：Step 4 的 commit **未执行**（等用户明示），Task 1~7 产物全部留在工作树。

  **本 Task 两条硬约束（已写死在测试里，Task 8 不得放宽）**：① 批量端点**任何情况下不返回 5xx**（单条失败只体现在
  `results[].ok:false`），否则 backend 侧无法区分「微信拒绝」与「wechat-service 挂了」；② 同批共享一个
  `WechatTokenManager`（access_token 日额度 2000 次，逐条取 token 会把额度耗在鉴权上）。

- [x] **Task 8：backend 侧回推客户端 + 完成/失败话术**

  **Step 1：失败测试**
  `WechatIntakeNotifyTest`：① DONE → 调 `/internal/kefu/send`，content 含 `{h5}/wechat/oauth?next=/agent/draft/{id}`；② FAILED → 推「没太看懂」话术且不含链接；③ `wechat.notify.enabled=false` → 零 HTTP；④ kefu 客户端抛异常 → 作业仍标 DONE（推链接失败不回滚拆解结果）；⑤ 话术不含英文术语（正则断言无 `session/proposal/agent/error` 字样）。

  **Step 2：实现**
  复用/扩展 `WechatNotifyClient` 形态新增 `sendKefuText(openid, content)`；`WechatIntakeService` 增 `notifyDone/notifyFailed`，中文话术常量集中一处便于文案审校。

  **Step 3：验证**
  `./gradlew test --tests '*WechatIntake*'`。

  **Step 4：Commit** `feat(change14/task8): 拆解完成后回推微信确认链接`（**未执行**，等用户明示）

  **实施偏离与记录（2026-09-13）**
  1. 用例由 Step 1 的 5 个扩到 **22 个新增**：`WechatIntakeNotifyTest` 10（话术与深链编排）+ `WechatNotifyClientTest` 5→**12**（客户端出网行为 +7）+ `WechatIntakeJobTest` 11→**16**（作业回推时机 +5）。分层理由：Step 1 的 ①②⑤ 是「说了什么」、③④ 是「说不出去时怎么办」，两者断言手段完全不同（前者纯 Mockito 验实参、后者 `MockRestServiceServer` 验零 HTTP），混在一类会让「零 HTTP」断言退化成 verify(never) 的自证。
  2. **出链路由用 `/wechat/authorize`，不是 spec.md L83 行文的 `/wechat/oauth`**。代码事实：`wechat_routes.py` 只有 `GET /wechat/authorize`(L94) 与 `GET /wechat/oauth/callback`(L103)，`nginx/default.conf` L85-88 亦明写「OAuth 入口 /wechat/authorize」，Task 10 原文写的落地路由同样是 `/wechat/authorize` ⇒ spec.md 该处为文档笔误。深链最终形态 `{h5}/wechat/authorize?next=/agent/draft/{id}`，与 Task 10 的白名单前缀逐字对齐。挂 Task 14 自检时订正 spec 措辞，本轮不改写规格快照。
  3. **术语铁律与深链天然冲突**：`/agent/draft/` 里的 `agent` 是路由契约、不可改；而 ⑤ 要求话术无英文术语。解法不是放宽断言，而是断言前 `文本.replaceAll("https?://\\S+", "")` 先剥链——话术正文受约束，URL 不受约束。
  4. **回推时机是本 Task 唯一的事故风险点，已写成结构性约束**：`notify*` 必须在终态事务提交之后、且在作业 `try/catch (RuntimeException)` **之外**调用。第一版实现误把回推写在 try 内（紧贴 `markDone`），若回推自身抛异常会被 catch 当成「拆解失败」，把已 DONE 的行改回 PENDING/FAILED——正是 spec「回推失败 SHALL NOT 改变入站行的终态」的反面。终版改为 try 内只置局部变量（`sessionId` / `已转人工`），catch 之后统一决策推哪种话术；为此引入 `Long 本次草稿` / `已建草稿` 两个 effectively-final 副本满足 lambda 捕获。用例 ⑯ 用 `doThrow` 打桩 `notifyDone` 钉死该行为。
  5. **客户端出网前统一 `content.strip()`**：服务层话术刻意保留「链接独占一行」的前导/尾随换行（微信会把行尾紧跟链接的汉字吃进 URL，老人一点就是坏地址），strip 放在客户端而非话术里，既修掉真实产品缺陷又不破坏第 3 条的剥链断言。
  6. **backend 新增配置键 `wechat.h5-base-url`**（`WECHAT_H5_BASE_URL`，默认 `http://localhost`），注入 `WechatIntakeService` 使构造器 5→**7 参**（第 5 参 `WechatNotifyClient`、第 6 参 h5 基址）。`@Value` **必须自带默认值**：`application-test.yml` 无任何 `wechat`/`h5` 键，缺省会让 Spring 上下文起不来（非聚焦测试全红）。基址在构造里 `strip().replaceAll("/+$","")` 归一尾斜杠，防拼出 `//wechat/authorize`。两处旧构造点（`WechatIntakeDecomposeTest:70`、`WechatIntakeDraftTest:118`）同步转 7 参，全仓 `Grep "new WechatIntakeService\("` 核对无遗漏。
  7. **`family_id 缺失 → FAILED` 那条防御分支有意不回推**。spec 只规定三类回推场景（DONE 带链接 / FAILED 兜底 / ai_disabled 提示），该分支不在其中；且受理侧已把 family 裁决做在落库之前，该行经受理不可能缺 `family_id` ⇒ 不写「无测试的生产代码」。挂账：若将来该分支变为可达，需补一句中文兜底话术。
  8. **ai_disabled 也回推**（补 Step 1 未列的第三类话术）：按 design D8 + spec 双开关条款，AI 关闭时若静默 `SKIPPED`，用户视角即「我发了消息，什么都没发生」，比失败更难自查。落点在作业 `markSkipped` 之后，与 DONE/FAILED 同走 `回推(类型, msgId, Runnable)` 守卫（`catch (RuntimeException) → log.error`，绝不向上抛）。
  9. **DTO 复用而非新建响应形状**：`WechatNotifyDtos` 只新增 `KefuTextMessage`/`KefuBatchRequest` 两个 record，逐条结果仍复用 Change 12 的 `WechatNotifyResponse(code, results[])`——两条通道的响应体本就同形，复制一份会让「微信拒绝」的判定逻辑出现两个漂移源。类 javadoc 同步改为覆盖两条通道并注明该共用关系。
  10. `WechatInboundMessageRepository` **保持零改动**：曾考虑为「只在本线程真的把行改成 FAILED 时才说话」加 5 参 `markFailed(…, boolean skipped)` 重载，经第 4 条的重构（局部变量决策）后成为死代码，已撤销 ⇒ 撤销过程不新增仓储层测试负担。
  11. RED 取证：`compileTestJava` **44 errors**（`sendKefuText`/`notifyDone`/`notifyFailed`/`notifyAiDisabled` 与 7 参构造器全为「找不到符号」）⇒ 红因是功能缺失，非测试写错。
  12. GREEN 取证：聚焦 `./gradlew test --tests '*WechatIntake*' --tests '*WechatNotifyClient*' --continue` → `EXIT=0`、`BUILD SUCCESSFUL in 8s`（44 个编译错误全消）；全量 `./gradlew test --continue` → `EXIT=0`，Gradle HTML 报告 `build/reports/tests/test/index.html` 实测 **452 tests / 0 failures / 0 ignored / 100%**（波次 4 基线 430 + 本 Task 净增 22，逐类计数 `10/16/12/10/6` 与新增完全吻合）⇒ 零回归。
  13. **compose 侧 `WECHAT_H5_BASE_URL` 尚未接线**（只加了 `application.yml` 默认值）⇒ 归 Task 13 编排；未重建镜像（留到 Task 8 之后一次性做整链真机验证）。

- [x] **Task 9：wechat-service 入站回调——XML 解析 + 受理转发 + 被动回复**

  **Step 1：失败测试**
  `wechat-service/tests/test_inbound_callback.py`：① 合法 text 消息 XML → 调 backend 受理一次、透传 openid/MsgId/Content/CreateTime、被动回复「收到，正在理解…」；② `MsgType=event`（订阅/取关/点击菜单）→ 不调 backend、回空串；③ `MsgType=image/voice` → 不受理、回「目前我只能看懂文字，请直接打字发给我」；④ 验签失败 → 403 且不解析 body；⑤ backend 不可达 → 回空串 + 记 error（**不让微信侧看到 5xx，避免重推风暴**）；⑥ `not_bound` → 回复绑定引导文案；⑦ `multi_family` → 回复多家庭文案；⑧ 处理总耗时断言 < 5s（mock 计时）。

  **Step 2：实现**
  `wechat/inbound.py`：明文 XML 解析（`xml.etree`，**关闭外部实体防 XXE**）、字段提取；`wechat_routes.py` 替换 `POST /wechat/callback` 占位实现（`<10s` 硬约束：受理端点毫秒级 + 超时 3s fail-fast）；`BackendClient` 新增 `accept_inbound_message(...)`；`settings.py` 加 `wechat_inbound_enabled`。

  **Step 3：验证**
  `cd wechat-service && pytest -q`；真机（DEFERRED 到 Task 12）用测试号发一条文本，观察公众号回复。

  **Step 4：Commit** `feat(change14/task9): 公众号入站消息解析与受理转发`（**未执行**，等用户明示）

  **实施偏离与记录（2026-09-14）**
  1. 用例由 Step 1 的 8 个扩到 **22 个收集用例**（`tests/test_inbound_callback.py`，单文件；反证加固后 **28 个**，见第 14 条）。自增的 14 个全是「失败面/防线」而不是新特性：五值 `reason` + `duplicate` + 未知 reason 参数化（7）、XXE 大小写变体（2）、解析断裂/超 64KB（2）、`wechat_inbound_enabled` 默认关短路（1）、text 缺 `MsgId` 不转发（1）、`INBOUND_ACCEPT_TIMEOUT` 常量 fail-fast 检查（1）。分层理由：Step 1 只覆盖「说人话的分支」，而本 Task 真正的风险在「不出声的分支」——收口成空串的每一条路径都必须有测试钉住，否则实现时很容易顺手 `raise` 出去变成 5xx。
  2. **回执话术全文定稿**（Step 1 只给了省略号「收到，正在理解…」）：`收到，正在理解你的意思，任务草稿整理好后会在微信里发给你`。测试断言用 `startswith("收到，正在理解")` 而非全等，文案后续微调不破坏契约。
  3. **`Verdict.reason` → 话术的三处有意偏离**（`app/wechat/inbound.py` 注释已固化）：① `multi_family` 去掉 design D3 表里的「N 个」——Verdict 契约无计数字段，客户端造不出准确数字，宁可说「多个」也不许诺假精度；② `intake_disabled` 表内未定义 → 借用 D8 的 `ai_disabled` 话术「AI 拆解功能暂时关闭了，你可以先在应用里手动添加任务」；③ `duplicate` 与任何未知 reason → **静默回空串**（重复消息再答一次会双显；未知 reason 不许诺、不编造）。
  4. **不引 `defusedxml`**：改为解析前两道字节级防线——原始字节 `upper()` 后查 `<!DOCTYPE` / `<!ENTITY`（大小写变体一并挡）+ `MAX_BODY_BYTES = 64KB` 上限（挡实体膨胀炸弹与垃圾大包），再进 `xml.etree`。理由：微信明文推送字段固定、报文极小，为一个封闭报文格式新增运行时依赖不划算；防线放在解析器之前，比换解析器的默认策略更可证（已有 4 个用例直接打这两条分支）。
  5. **5 秒硬时限的实现细节**：`BackendClient._post` 新增可选 `timeout` 参数，且**只在显式传值时才带 `timeout=` 关键字**——httpx 里 `timeout=None` 的语义是「不限时」而不是「用默认」，无脑透传会把自建 client 的 10s 默认清零。受理端点用 `INBOUND_ACCEPT_TIMEOUT = 3.0`（毫秒级落库 + 留 2s 余量给回复出站），常量的存在性由用例⑫钉住，防止日后有人把它调大静默突破微信时限。
  6. **收口铁律落地形态**：除验签失败 403 外全部 200。`httpx.HTTPError`（`ConnectError`/`ReadTimeout`/`HTTPStatusError` 共同基类）一处 catch → 空串 + `logger.error`；解析失败同收口。事件回 `PlainTextResponse("")` + INFO 记事件类型；非文本回 `TEXT_ONLY_HINT`。
  7. **本模块首次引入日志（loguru）**：仓库既有惯例是 `from loguru import logger` + `{}` 花括号占位（agent-service `main.py` 有 `setup_logging()`），wechat-service 此前零日志，按惯例对齐而非新造 std 配置。副作用：loguru 不吃 pytest `caplog` ⇒ 测试侧新增 `log_sink(level)` 上下文管理器，用官方 `logger.add(callable)` 挂 sink、finally `logger.remove(id)`，避免污染其它用例。
  8. **缺 `MsgId` 在客户端先挡**：backend 受理端点对缺失 `msg_id` 回 400（Task 2 契约），转交过去必然白跑一趟且触发一次 error 日志噪音 ⇒ 回调侧直接空串收口，日志记 error 便于排查异常推送。
  9. **`CreateTime` 脏值不丢消息**：非纯数字按 `None` 处理，`to_received_at` 退化为「当前时间」，而不是拒收——幂等键是 `msg_id` 不是时间戳，时间戳只用于审计，为此丢消息代价不对等。
  10. **TDD 证据链**：RED → `14 failed / 6 passed`（红因全部是功能缺失：`KeyError: seen["calls"]`、缺 ERROR 日志、无 `INBOUND_ACCEPT_TIMEOUT`、开关字段不存在），6 个 pass 是对占位实现天然成立的防线类用例（403/XXE/开关关/缺 MsgId/墙钟）。GREEN 首轮暴露两处「实现未落盘」缺口（`wechat_routes.py` 导入块、`_post` 的 `timeout` 形参），补齐后 20 passed；再补 2 个解析收口用例 → 22 passed（**反证加固后为 28，见第 14~15 条**）。
  11. **测试自身笔误更正 1 处**（发生在 RED 取证之后、GREEN 之前，生产代码未迁就测试）：用例①把被动回复的 `ToUserName`/`FromUserName` 断言写成与推送同向（复制），实际应答方向是公众号 → 用户，必须**互换**；同时把歧义常量 `FROM_USER`/`TO_USER` 改名为 `OPENID`/`ACCOUNT`。方向断言现在与 `build_text_reply` 的注释互为验证。
  12. **门禁取证**（反证加固后重跑，逐字回显）：`cd wechat-service && pytest -q` → **70 passed / 0.51s**（42 存量 + 28 新增，零回归）、coverage **93.41%**（门槛 80% reached）、`app/wechat/inbound.py` **100%**；`ruff check .` → All checks passed（新文件曾报 4×UP017 `timezone.utc`→`UTC` + 4×E501，加固阶段又新报 2×UP012 冗余 `.encode()`，均改字节字面量后清零）；`mypy app` → Success，16 source files 零问题。
  13. **测试有效性反证（变异测试）**：GREEN 全绿不等于断言有牙。对生产代码逐个下刀（备份 → 变异 → 跑测试 → 无条件还原 → grep 复核还原），共 **13 个变异点，6 个当场被杀**：M1 `except httpx.HTTPError`→`ValueError`（切断收口铁律，2F）、M5 event 分支永假（1F）、M6 缺 `MsgId` 守卫永假（1F）、M8 `REASON_REPLIES.get(...)`→`return None`（5F，恰对应 5 条有话术的 reason）、M10 忽略 `CreateTime`（1F）、M13 入站开关守卫永假（1F）。**4 个幸存者**（变异后仍全绿）即 4 个实质缺陷：M2 清空 `FORBIDDEN_MARKERS`、M3 `MAX_BODY_BYTES` 抬到 10⁹、M11 去掉 `CreateTime` 的 `.isdigit()`、M12 去掉 `MsgType` 的 `.lower()`。
  14. **四个幸存者的归因与加固**（全部只改测试文件，**生产代码零改动**）：
      - M2/M3 同因——**解析器替防线挡了刀**：`&xxe;` 被引用会让 expat 抛 undefined entity、小写 `<!entity` 是非法标记、被截断的超长报文也是语法错，而这些断言又恰好被路由日志模板「微信入站消息解析失败」里的「解析失败」四字恒真满足。修法 = 构造**只有该防线能拦、其它机制放行**的判别式载荷（大写 `<!ENTITY` 声明 + 正文不引用 ⇒ 合法 XML；`Content` 填充 4 万字 ⇒ 合法且超长）+ 对「谁挡的」做**文案级归因断言**（错误须含 `DOCTYPE` / 日志须含「字节上限」）。复验：M2 → 2F、M3 → 1F。
      - M11 是本轮最严重的一个：去掉 `.isdigit()` 后 `int("not-a-number")` 抛 `ValueError`，而路由只 catch `InboundParseError` ⇒ **逃逸成 500**，正是收口铁律的反面；实施偏离第 9 条「脏值不丢消息」此前从未被测。新增 `test_dirty_createtime_falls_back_to_now_without_losing_message`（200 + 不丢消息 + `received_at` 退化当前时间）。复验：1F，报错逐字 `ValueError: invalid literal for int() with base 10: not-a-number`。
      - M12 的裁决 = **保留 `.lower()` 并补测试钉住**（而非删代码）：外部协议字段的大小写不在我方控制内，若分支判定大小写敏感，大写 `TEXT` 会落入「非文本」分支只回「只能看懂文字」⇒ 用户的任务被静默丢弃（老人场景硬伤）。新增 `test_msgtype_case_folding_decides_the_branch`（`TEXT`/`Text`/`EVENT` 三参数）。复验：3F，失败回显实证了上述误判。
      - 用例数 22 → **28**（辅助器 `inbound_xml` 相应新增 `create_time` 形参、并按 `msg_type.lower()` 决定是否带 `Content`）。
  15. **方法论沉淀（写入本仓 TDD 闭环必做环节）**：**覆盖率 100% ≠ 钉得住**。当某断言可被旁路满足（解析器自身抛错、日志模板里恰好含被断言的词）时，删掉防线测试仍全绿。变异反证是唯一能区分「测试在验证契约」与「测试在记录现状」的手段；判据 = 「N failed」才算杀掉，「全部 passed」即幸存者 = 断言无牙。
  16. **未收口事项**：真机发文本看公众号回复（DEFERRED 到 Task 12 整链验证）；`WECHAT_INBOUND_ENABLED` 与 compose 接线归 Task 13；镜像未重建。默认关 ⇒ 本 Task 合入后线上行为零变化（验签通过后直接空串）。

- [x] **Task 10：`next` 深链 + 开放重定向白名单**

  **Step 1：失败测试**
  `wechat-service/tests/test_oauth_next.py`：① `mint(next="/agent/draft/9")` → 回调 302 落地 `{h5}/wechat/callback#token=…&next=/agent/draft/9`；② `next="//evil.com"`、`next="http://evil.com"`、`next="/../etc"`、`next="/unknown/route"` → **白名单拒绝，next 被丢弃**、仍正常 302 落首页（不报错）；③ 未带 next → 行为与现状逐字一致（回归护栏）；④ 未绑定分支同样支持 next 透传（绑定后回原目标页）。

  **Step 2：实现**
  `OAuthStateStore` state → `(expires_at, next)`；`/wechat/authorize` 读 `?next=` 并校验白名单后 mint；回调拼接 fragment。白名单常量：`{"/agent/draft/", "/tasks", "/calendar", "/dashboard", "/chat"}`。

  **Step 3：验证**
  `cd wechat-service && pytest -q`。

  **Step 4：Commit** `feat(change14/task10): 免密登录支持 next 深链并收敛重定向白名单`（**未执行**，等用户明示）

  **实施偏离与记录（2026-09-14）**
  1. 用例由 Step 1 的 4 组扩到 **12 个测试函数 / 60 条收集用例**（`tests/test_oauth_next.py` 单文件）。自增的全是「绕过面 / 结构安全 / 回归护栏」而不是新特性：白名单常量逐字钉死（1）、`sanitize_next` 正例参数化（8）+ 反例参数化（21）、state 携带 next 且单次消费（1）、**next 不外泄给微信**（1：`/wechat/authorize` 的 302 里必须找不到 `/agent/draft`）、fragment 参数走私（1）、未带 next 的落地地址逐字回归（1，绑定 + 未绑定双断言）。分层理由：Step 1 的 4 组只覆盖「功能是否通」，而本 Task 的真实风险在「防线是否有牙」——免密登录的 302 会把 token 放进 fragment，`next` 一旦能被污染，token 就直接送给外部站点。
  2. **校验落点 = authorize 侧一次性收敛**：`sanitize_next` 只在签发 state 前跑一次，`OAuthStateStore` 里只存「已合法」的值 ⇒ 回调侧零校验、只负责编码。好处：①校验逻辑可单元测试，不必起 HTTP 链路；②HTTP 级用例仍可端到端断言；③不存在「某条回调分支忘了校验」的空间。
  3. **白名单外的 4 道形状规则**（Step 2 只给了白名单常量）：控制字符/空白（`ord(ch) <= 0x20 or ch == "\x7f"`，挡 CRLF 响应拆分与粘贴脏值）、`NEXT_MAX_LENGTH = 200`、禁反斜杠（浏览器把 `\` 归一为 `/`，`/\evil.com` 等价协议相对）、`NEXT_BOUNDARY_CHARS = {"/", "?", "#"}`（挡 `/tasksevil` 这类前缀粘连）。逐条对应一种真实绕过手法，缺一可被利用（变异反证第 11 条给出实证）。
  4. **非法 next 静默丢弃 → 回落首页，不报错页**：对齐 Step 1「仍正常 302 落首页（不报错）」，也是老人场景裁决——老人看不懂「重定向不合法」，只该看到应用正常打开。
  5. **`consume` 契约由 `bool` 改为 `tuple[bool, str | None]`**：`if store.consume(state):` 这种写法在非空元组上恒真，会把过期 state 也放行 ⇒ 文档注释明令「调用方必须解包判断首项，勿用整体真值」，并有 `test_state_store_carries_next_and_stays_single_use` 钉住四种返回形态（带 next / 裸 mint / 二次消费 / 伪造 state）。
  6. **路由形参不叫 `next`**：Step 2 语义上等价于 `?next=`，但若写成 `next: str | None = None` 形参会遮蔽内置函数 `next`（本仓库 ruff 未选 `A002`，**lint 拦不住，只能自觉**）。改用 `request.query_params.get("next")`，对外参数名不变。代价：OpenAPI 文档不再自动声明该参数（本服务非公开 API，可接受）。
  7. **拼接必编码**：`quote(next_path, safe="/")`。`next=/tasks?filter=a&token=attacker` 若不转义，`&` 会在 fragment 里另起一个键、直接覆盖同处的 token（`test_next_cannot_smuggle_extra_fragment_params` 实证：落地 fragment 只允许 `token`/`next` 两键且 token 未被替换）。`safe="/"` 保证 `/agent/draft/9` 逐字不变，因此 Step 1 ① 的落地断言可写成全等而非包含。
  8. **未绑定分支的 next 走 query 透传**：`/wechat/bind?ticket=…&next=/agent/draft/9`（绑定页读完 ticket 后按 next 决定回原目标页），与已绑定分支共用同一个 `next_suffix`，避免两分支行为漂移。
  9. **TDD 证据链**：RED → `ImportError: cannot import name 'NEXT_WHITELIST' from 'app.wechat.oauth'`（失败原因正确 = 功能不存在，不是测试写错）。GREEN 首轮 **50 passed**；随后为「每条防线都要有只靠它能拦的判别式载荷」补两批：先补 CRLF/超长/前缀后反斜杠（能穿过白名单前缀、只被各自形状规则拦）→ **56 passed**，变异反证后再补「前缀出现在路径中间」两条 → **60 passed**。
  10. **门禁取证**（全部改动落定后重跑，逐字回显）：`cd wechat-service && pytest -q` → **130 passed / 0.70s**（120 基线 + 10 新增参数，零回归）、coverage **93.71%**（门槛 80% reached）、`app/wechat/oauth.py` **95%**（仅 84-85 网络异常分支未覆盖，属存量）；`ruff check .` → All checks passed；`mypy app` → Success，16 source files 零问题。
  11. **变异反证（12 个变异点，10 杀 2 幸存）**：备份 sha256 → 按 needle 唯一定位整行下刀 → 跑 `tests/test_oauth_next.py --no-cov` → finally 无条件还原 + 还原校验 `RESTORE OK`（并复核生产文件内容与 `POST-RESTORE 60 passed`）。被杀：M1 白名单放宽为 `{"/"}`（12F）、M2 `startswith`→`in`（4F）、M5 禁反斜杠（2F）、M6 `..` 检查（4F）、M7 控制字符（2F）、M8 长度上限（2F）、M9 前缀边界（4F）、M10 authorize 不过白名单（24F）、M11 state 不携带 next（5F）、M12 不编码（5F）。**两个幸存者 M3（拆掉 `//` 检查）、M4（拆掉前导斜杠检查）经归因是可证冗余**：严格前缀白名单要求候选值必以 `/agent/draft/` 等前缀开头，而这类前缀本身就是单个 `/` 开头、不可能是 `//` 或无斜杠，故这两条规则在当前白名单形态下永无独立判别力。**裁决 = 保留为纵深防御**（若日后白名单放宽、或新增允许绝对地址的条目，这两条会立刻升级为主防线），并在报告里如实记为幸存者，不谎称零幸存。
  12. **M2 的教训与 Task 9 第 15 条同源**：第一轮反证时 M2 **幸存**——因为「前缀粘连」被边界规则挡、「带 scheme」被前导斜杠规则挡，测试里没有任何载荷能区分 `startswith` 与 `in`。补 `/evil/agent/draft/9`、`/evil/chat/room-1`（白名单前缀出现在路径**中间**：`in` 匹配放行、`startswith` 拒绝）后 M2 转为 4F 被杀。⇒ 判别式载荷必须构造在「只有这一条防线能拦」的位置上，冗余防线否则会以「全绿」的形式伪装成有效防线。
  13. **未收口事项**：本 Task 只保证服务端把 `next` **安全带到**前端（fragment / 绑定页 query）；H5 侧真正读 `next` 并跳草稿详情的逻辑归 **Task 12**，绑定页消费 next 同理。未部署、镜像未重建 ⇒ 线上行为零变化（未带 next 时落地地址逐字与改造前一致，已由回归护栏钉住）。

- [x] **Task 11：D-8 草稿详情读取端点**

  **Step 1：失败测试**
  `DecomposeSessionDetailTest`：① 本家庭 ACTIVE 草稿 → 200 + `{id,status,initTaskTitle,proposedTasks[],createdTaskIds[]}`；② **跨 family_id 访问 → 拒绝**（用另一家庭的 token 取，断言不可见）；③ `CONFIRMED` 同样可读（用户重复点链接要看到「已创建」而非 404）；④ `CANCELLED` 可读并标 `status`；⑤ AI 开关关闭 → 50310；⑥ proposedTasks 最多 20 条透传。

  **Step 2：实现**
  `DecomposeSessionController` 新增 `@GetMapping("/{id}")`（`guardAiEnabledOrThrow()` + `requireFamilyId(curr)`）+ `DecomposeSessionService.readScoped(...)`（复用 `loadActiveScoped` 的 family 作用域语义，但状态放开到 ACTIVE/CONFIRMED）。

  **Step 3：验证**
  `./gradlew test --tests '*DecomposeSession*'`。

  **Step 4：Commit** `feat(change14/task11): 新增按家庭作用域读取拆解草稿的 D-8 端点`（**未执行**，等用户明示）

  **实施偏离与记录（2026-09-15）**
  1. **`createdTaskIds[]` 从响应契约中省略**（Step 1 ① 原文要求返回）：该数据在当前 schema **没有落库来源**——confirm 链路里的新建任务 id 集合是 `DecomposeConfirmResp` 的局部构造值（仅 D-5 响应体出现），`agent_decompose_sessions` 表并无对应列；为一个读端点新开「草稿 → 任务」的持久化通道属超出 Task 11 范围的结构变更。「已创建」语义已由 `status=CONFIRMED` 完整承载（H5 详情页据 status 渲染「已创建」态即满足 design.md 的用户可见目标）。对照 design.md L83 的响应示例：其中同样未定义 `createdTaskIds` 的存储来源，Step 1 属规格侧笔误。**裁决 = 实现按现状省略，测试不钉该字段，本条为记账修复**；若 Task 12 真机验证发现「已创建但看不到具体任务」构成体验断裂，升级为主观缺陷另立 Task，不回改本端点。
  2. **响应体字段命名 = snake_case**（`session_type` / `init_task_title` / `proposed_tasks`）：Step 1 ① 与 design.md 示例写的是 camelCase（`initTaskTitle`、`proposedTasks`），但同一资源组的 **已发货契约**（D-1 create 返回 `session_id`/`session_type`、D-5 confirm 的响应体）均为 snake_case，前端 store 与既有测试也都按 snake_case 消费 ⇒ 记 design.md 为不一致方，实现向既成事实对齐。Task 12 前端接线时以本端点实际输出为准。
  3. **状态策略 = 三态可读 + ARCHIVED 单独 50311**：Step 2 说「状态放开到 ACTIVE/CONFIRMED」，Step 1 ④ 又要求「CANCELLED 可读并标 status」——两条自相矛盾。实现取并集：**ACTIVE/CONFIRMED/CANCELLED 均可读**（用户重复点深链必须看到真实终态而非 404，这是 ③④ 的共同意图）；**ARCHIVED 是唯一拒绝态**→ `AI_PROPOSAL_ALREADY_FINALIZED`（50311）。ARCHIVED 是 save-profile-only 的中间归档态、不属于草稿用户生命周期的对外可见态，其 50311 话术原文含「归档」，与本端点语义吻合。跨家庭与不存在**同码** `AI_PROPOSAL_NOT_FOUND`（46001），不区分「不存在 / 别人的」——防枚举（免密深链的 id 可被遍历，响应差异本身即信息泄漏）。
  4. **50310 用例独立成类**：`@TestPropertySource` 是类级注解，AI 开关关闭态无法与其余 7 个用例共存于同一测试类 ⇒ 新建 `DecomposeSessionDetailDisabledTest`（1 用例），主类 `DecomposeSessionDetailTest` 收 7 用例（Step 1 的 ①②③④⑥ + 自增 ④b ARCHIVED→50311 + 自增「不存在 id→46001」）。合计 **8 用例**，两条自增都是「拒绝态的可区分性」——④b 与自增⑦分别钉住 M4/M5 与 M3 的判别面。
  5. **聚焦验证命令的微调**：Step 3 的 `./gradlew test --tests '*DecomposeSession*'` 在 `bash -lc` 外层单引号环境里有通配符引号风险，实际执行用 `--tests DecomposeSessionDetailTest --tests DecomposeSessionDetailDisabledTest`（类全名、无 `*`、免引号）。语义等价且更精确（通配会连带跑 Task 8 时代的同名类）。
  6. **40041（缺 family header）覆盖缺口挂账**：`requireFamilyId` 的 40041 分支在 HTTP 级用例构造不出来——测试基建 `TestUserContextFilter` 对缺失/非法 header 一律回落缺省家庭上下文，无「已登录但无家庭」的注入缝。该防线由 D-5/D-7 同构继承（三段式守卫是共用代码），本轮不为它改测试基建 ⇒ 挂账，若 Task 14 人工自检认为必须钉，补一个 filter 级开关再议。
  7. **新环境坑 A（CRLF）**：跨 UNC 用编辑工具写入的生产 Java 文件是 **CRLF 行尾**（WSL 侧 `file` 证实 `with CRLF line terminators`）⇒ 变异脚本的多行 needle（含 `\n`）首跑 hits=0（M1/M3/M7 三把 NEEDLE-ERROR），单行 needle 无恙。修法 = 命中失败时 fallback `needle.replace(b"\n", b"\r\n")` 归一化重试（repl 同步）。**未杀任何测试语义**：首跑 4 把全部有效且还原校验通过。
  8. **新环境坑 B（同文件并行编辑互覆）**：对**同一文件**一次消息并行发两个 `SearchReplace` ⇒ 第二个基于旧缓冲写回，**第一个编辑静默丢失**（本 Task 的 XML 归档循环就是这样丢的，症状 = 变异跑完 `mutlog_d8/` 里没有归档文件）。纪律固化：**同文件编辑必须串行**，且每次编辑后 Grep 复核关键行（延续波次 7b「回显成功 ≠ 磁盘已写」教训的新变体）。
  9. **TDD 证据链**：RED = **8 failed**（症状为 404/500 类断言失败，红因正确 = 端点不存在，非测试写错）→ GREEN = **8 passed**（聚焦 7-8s，daemon 热）→ **变异反证 7 刀 7 杀 0 幸存**。驱动脚本 `.git/c14_mut_d8.py`：sha256 备份 → needle 唯一命中断言（hits≠1 即 SKIP 记错）→ `gradle test --rerun-tasks` → rc≠0=KILLED → 每刀后即时还原 + sha256 校验 → `finally` 无条件还原 → `RESTORE OK` → POST-RESTORE 复跑绿（三次复跑全 rc=0）。各刀战果：M1 删 Controller 守卫（1F=杀 D8-⑤）、M2 familyId 写死（1F=杀 D8-②）、M3 换 46001→40041（2F）、M4 拆 ARCHIVED 拒绝（1F=杀 D8-④b）、M5 状态收紧到仅 ACTIVE（2F=杀 D8-③④）、M6 proposed_tasks 清空（3F）、M7 status 写死（2F）。**零幸存对照 Task 10 的 10 杀 2 幸存**：D-8 是纯读端点、无「数学上不存在判别式载荷」的冗余规则位，预期并达成零幸存。
  10. **M6 多杀归因**（纪律：失败数超出预期必须逐名归因，不放行）：M6（`proposed_tasks` 强制清空）预期杀 D8-①⑥，实测 3F——XML 归档逐名核对多出 **D8-③**（CONFIRMED 用例同时断言 `proposed_tasks.length()==1`）。属合法多杀（该防线被三个用例共同依赖），不削弱 M1~M5/M7 各自判别力的独立性结论。
  11. **门禁取证**（全部改动落定后重跑，逐字回显）：`./gradlew test --continue --rerun-tasks -q` → **BUILD SUCCESSFUL（exit 0）**；不接受 rc=0 本身作证据，`backend/build/test-results/test/TEST-*.xml` 逐类加总 = **65 个测试类 / 460 tests / 0 failures / 0 errors / 0 skipped**（基线 63 类 / 452 tests + 本波新增 2 类 / 8 用例，逐类相加吻合；负向模式 `failures="[1-9]|errors="[1-9]|skipped="[1-9]` 对全量 XML 零命中）⇒ 零回归。
  12. **未收口事项**：本 Task 只交付后端读端点；H5 详情页、路由 `/agent/draft/:id`、`WechatCallbackView` 读 `next` 归 **Task 12**。镜像未重建 ⇒ 线上行为零变化（新路由在真机上尚不可达，无暴露面风险）。工作树：2 生产文件 M + 2 测试文件 ??，**commit 未执行**（等用户明示）。（另记一条时间事实：本波次执行中系统日期跨到 **2026-09-15**，门禁 XML 时间戳可证，偏离标题与交接日期以 09-15 为准。）

- [x] **Task 12：前端 H5 草稿详情页 + 深链恢复（老人易用）**

  **Step 1：失败测试**
  `frontend/src/views/__tests__/AgentDraftDetailView.spec.js`（vitest）：① 加载调 `GET /sessions/{id}` 并渲染「你说：<原文>」；② `proposedTasks` 逐条渲染标题；③ 确认按钮 → 调 D-5 confirm，成功后按钮禁用 + 文案「已创建 N 个任务」；④「不要了」→ 调 D-7 cancel；⑤ `status=CONFIRMED` 进入 → 直接显示已创建态、按钮不可点（防重复建任务）；⑥ 空 `proposedTasks` → 显示中文兜底、无 JS 报错；⑦ `WechatCallbackView` 支持 `next` 参数恢复目标路由，无 next 时仍落 dashboard。

  **Step 2：实现**
  `router/index.js` 加 `/agent/draft/:id`（`requiresAuth: true`）；新增 `AgentDraftDetailView.vue`（基础字号 ≥ 18px、按钮命中区 ≥ 48px、单列、高对比、状态四态齐：loading/empty/error/success）；`WechatCallbackView.vue` 读 fragment 里的 `next` 后 `router.replace`。

  **Step 3：验证**
  `cd frontend && npx vitest run && npm run build`；`docker compose build frontend && up -d frontend` + `docker compose restart nginx`；真机（挂账项）走「微信发一句 → 收链接 → 点开 → 确认 → 任务列表出现该任务」全链。

  **Step 4：Commit** `feat(change14/task12): H5 拆解草稿详情页与免密深链恢复`（**未执行**，等用户明示）

  **实施偏离与记录（代码落地 2026-09-15~09-16；本轮 2026-09-22 补记账并复核证据）**
  1. **测试载体与命名偏离**：Step 1 写 `frontend/src/views/__tests__/AgentDraftDetailView.spec.js`，实际落地为 **`frontend/src/tests/views/AgentDraftDetail.test.js`**——对齐本仓既有惯例（所有前端测试集中在 `src/tests/**`、后缀 `.test.js`，非 co-located `__tests__/*.spec.js`）。判定 = **记 spec 为不一致方，实现向既成事实对齐**（与 Task 11 第 2 条 snake_case 同一裁决逻辑）。
  2. **用例数 3 而非 ①-⑦ 七条，覆盖缺口如实挂账**：实际 3 个用例——`读取原文，保存用户修改后确认，重复打开无确认按钮`（钉住 ①②③⑤，断言 `confirm).toHaveBeenCalledTimes(1)`、重开后 `.draft-actions` 不存在、`created_task_id` 链接可见）、`空草稿不能确认，加载错误给重试入口`（钉住 ⑥ + error 态）、`保存期间切换草稿，不继续确认另一个会话`（竞态保护，超出 spec）。**未覆盖项**：**④「不要了」→ D-7 cancel 零断言**（mock 里有 `cancel: vi.fn()` 但无用例调用）、**⑦ `WechatCallbackView` 组件级无用例**、**loading 态文案无用例**。⇒ check_list 47 记「部分取证」、50 记「诚实挂账」，补测另立 Task，**不在本 change 内伪造取证**。
  3. **`safeNext` 抽成独立工具（spec 未要求）**：`next` 白名单校验落在 **`frontend/src/utils/safeNext.js`**（长度 ≤512、拒 `[\\%\x00-\x20]`/`..`/`//` 前缀，正则白名单仅放行 `/agent/draft/{id}`、`/tasks`、`/tasks/{id}/edit`、`/chat`、`/calendar`、`/dashboard`，其余归 `/`）。`WechatCallbackView.vue` 消费方式 = `const nextPath = safeNext(params.get('next'))` → `router.replace(nextPath)`，与 Step 2 一致。**其单测在 `src/tests/utils/familyRequests.test.js` L13/L15**（`/agent/draft/9` 原样放行、恶意 url 归 `/`）⇒ ⑦ 的**安全语义已被覆盖，缺的只是组件级渲染断言**。抽独立工具属正向偏离（可单测 + 可复用），予以记账认可。
  4. **`requiresAuth` 为继承而非显式声明**：`router/index.js` L40 的 `agent/draft/:id` 挂在 `AppLayout` 子路由下，**继承**父级 `requiresAuth: true`（未在本条路由重复写）。语义等价、且符合本仓「布局级统一鉴权」惯例 ⇒ 判定合规，不算缺口。
  5. **老人易用阈值源码级达标**：`.draft-page` `font-size: 18px` + `max-width: 680px; margin: auto`（单列）、`:deep(.el-button)` `min-height: 48px; font-size: 18px`、`:deep(.el-input__inner)` `min-height: 48px`、`:deep(.el-textarea__inner)` `min-height: 72px`，全中文文案（`正在读取你的安排…` / `暂时还没有拆解出待办，请稍后重试` / `这些待办已经创建，请勿重复添加` / `这份安排已取消`）⇒ 硬约束「中文注释 + UI 老人易用」正面回应。**375px 无横向滚动仅能源码推断（单列 + max-width），真机截图挂账**。
  6. **超出 spec 的竞态防护**：`generation` 计数 + `onBeforeUnmount` 递增 ⇒ 保存途中切走路由不会污染旧会话，并有专用用例钉住。属正向加固。
  7. **Step 3 验证结果（本轮 2026-09-22 真跑 + 容器产物取证）**：`npx vitest run` → **33 files / 142 tests passed**；`npm run build` → ✓ **4.06s**；**构建部署环节确认已完成**——运行中的 frontend 镜像 `59cd3fcf3a67`（created 2026-09-18T02:00Z）产物内含 `AgentDraftDetailView-CR5cSbos.js`（`grep` 命中空清单文案与 loading 文案），`grep "agent/draft"` 命中路由包 `index-Ddcbj0v5.js`，`docker compose ps -a` **7/7 healthy** ⇒ 详情页在运行栈中真实可达，非仅源码存在。**真机全链（微信发一句 → 收链接 → 点开 → 确认 → 任务列表出现）仍为挂账项**（需真实微信公众号环境，本仓无 Playwright 链路）。
  8. **工作树状态**：`AgentDraftDetailView.vue`、`utils/safeNext.js`、`tests/views/AgentDraftDetail.test.js` 等均为 **untracked**（09-15/09-16 mtime），`router/index.js`、`WechatCallbackView.vue` 为 modified ⇒ **`git commit -a` 会漏掉全部新增文件**，提交时须显式 `git add`。commit 未执行。

- [x] **Task 13：编排与文档收口**（编排已完成；文档项经取证判定「载体不适用、意图已由 design.md D2 满足」而收口——见下方记录 3-6）

  **Step 1：实现**
  `docker-compose.yml` / `.env.example` 透传 `WECHAT_INTAKE_ENABLED`、`WECHAT_INBOUND_ENABLED`、`WECHAT_INTAKE_POLL_MS`、`WECHAT_INTAKE_MAX_ATTEMPTS`、`SPRING_SCHEDULING_POOL_SIZE`；`repo-map.md` 补 `wechat_inbound_message` 与入站链路一句话。

  **Step 2：验证**
  `docker compose config >/dev/null && echo ok`；`docker compose ps -a` 全 healthy（7/7）。

  **Step 3：Commit** `chore(change14/task13): 入站拆解开关与仓库地图收口`（**未执行**，等用户明示）

  **实施偏离与记录（本轮 2026-09-22 核查）**
  1. **Step 1 前半（compose env 透传）= 已完成**：`docker-compose.yml` 与 `.env.example` 五个开关（`WECHAT_INTAKE_ENABLED`、`WECHAT_INBOUND_ENABLED`、`WECHAT_INTAKE_POLL_MS`、`WECHAT_INTAKE_MAX_ATTEMPTS`、`SPRING_SCHEDULING_POOL_SIZE`）逐条核实存在 ⇒ check_list 51① 记 ☑。
  2. **Step 2 = 已完成（本轮真跑）**：`docker compose config` → **COMPOSE_OK**；`docker compose ps -a` → **7/7 healthy**（agent-service / backend / frontend / nginx / postgres / tool-service / wechat-service，均 Up 2 hours）；backend 日志 `Successfully validated 14 migrations` + `Current version of schema "public": 14` ⇒ 运行栈已含 V13/V14 ⇒ check_list 51②④ 记 ☑。
  3. **Step 1 后半（`repo-map.md` 补一句话）= 载体不适用**：`grep -ci 'inbound\|入站' repo-map.md` = **0**，确认注记未落地；且**当前 `repo-map.md` 不是可编辑的文档，而是 aider tree-sitter 的原始终端转储**——1012 行、213 个 `⋮` 折叠符号、开头 9 行是 aider 启动横幅（含 `Do not propose changes to these files, treat them as *read-only*` 与 `auto refresh` 字样）、**零散文段落**，机器生成的签名索引里**没有「补一句话」的语义插入位**，手写散文也会被下次 auto refresh 冲掉。
  4. **历史真相（`git log -- repo-map.md` 提交全文复查，订正本条早期误判）**：当前 tracked 的 aider 转储版正是 `c476715`（2026-09-11）**按用户裁决入库**的——提交信息明文「repo-map.md 按用户裁决入库（+125/−407 aider 覆盖版），挂账 11 次的处置终结」；即 `642b19f` 的 1294 行清理版是被**有意替换**的一方。早期记录把 c476715 定性为「无意回退」有误，特此订正；工作树对该文件干净（无未提交修改）。
  5. **裁决结果（2026-09-22，按 (b) 执行）**：**不动 `repo-map.md`**（用户 09-11 裁决的只读转储，非可插注记载体）。「补入站链路一句话」的文档意图经取证判定**已由 design.md D2（L31-33）满足**——该节已完整写明「wechat-service 的同步链只做『验签 → XML 解析 → 调 backend 受理 → 回文本』」与「表即队列：`wechat_inbound_message.status` 驱动，`@Scheduled(fixedDelay=5000)` 捞 `PENDING`」⇒ 载体偏离如实记账，复选框诚实勾上。
  6. **未收口事项**：Task 13 的 commit 未执行（按本轮裁决「先不提交」，等用户明示）。

- [x] **Task 14：全量门禁与 spec 自检**

  **Step 1：验证**
  `./gradlew test` / `cd agent-service && pytest -q` / `cd wechat-service && pytest -q` / `cd frontend && npx vitest run && npm run build` 四条全绿；人工 self-review 四份 delta spec（本机无 `openspec` CLI，不承诺 `openspec validate`）。

  **Step 2：Commit**（仅在前三条有零星修补时）`test(change14/task14): 全量门禁与规格自检`（**未执行**，等用户明示）

  **实施偏离与记录（本轮 2026-09-22 真跑取证）**
  1. **四条门禁全绿（逐条真跑，非引用历史记账）**：
     - `./gradlew test` → BUILD SUCCESSFUL；不接受 exit 0 本身作证据，`backend/build/test-results/test/TEST-*.xml` **逐类加总 = 72 类 / 496 tests / 0 failures / 0 errors / 0 skipped**，负向模式 `failures="[1-9]|errors="[1-9]|skipped="[1-9]` 对全量 XML **零命中**（基线 65 类 / 460 tests 为波次 9 数据，增量来自 change14 之后的其他 change，非回归）。
     - `cd agent-service && pytest -q` → **96 passed**，coverage **93.39%**。
     - `cd wechat-service && pytest -q` → **130 passed**，coverage **93.71%**。
     - `cd frontend && npx vitest run` → **33 files / 142 tests passed**；`npm run build` → ✓ **4.06s**。
     - 附加（超出 Step 1）：`docker compose config` → COMPOSE_OK；`docker compose ps -a` → **7/7 healthy**；backend Flyway `Current version of schema "public": 14`。
  2. **「本机无 `openspec` CLI」的原注记已过时并予以纠正**：`which openspec` 实测得 **`/mnt/c/Users/98447/AppData/Roaming/npm/openspec`**（Windows npm 全局安装，WSL 侧经 `/mnt/c` 可调用）⇒ Step 1 括号内的免责声明不再适用，本轮**补跑真校验**：
     - `openspec validate --strict 2026-09-11-change14-wechat-task-intake` → **`Change '2026-09-11-change14-wechat-task-intake' is valid`**（严格模式通过）。
     - `openspec validate --all --strict` → **11 passed / 1 failed（12 items）**，本 change 在通过名单内。
  3. **人工 self-review**：check_list 第八节 52-58 七条全 ☑（proposal 含非目标与 AI 数据流表、四份 delta spec 能力名不含 change 编号、spec 格式对齐 Change 13 的 `#### Scenario` + `**WHEN**/**THEN**`、tasks 单会话可验证、Task 1 含回滚脚本、硬约束四条正面回应），本轮复核未发现需改判项。
  4. **顺带发现（不属本 change，仅记账不修）**：`openspec validate --all --strict` 的唯一失败项是 **`2026-09-16-profile-delete-guard`**，原因 = `Change must have at least one delta. No deltas found.`——该 change 无 `specs/` delta，按 CLI 提示应在其 `.openspec.yaml` 设 `skip_specs: true`（纯重构/文档类 change 的合法处置）。**属另一 change 的治理文件、超出本轮授权范围 ⇒ 未改动**，留给用户裁决。
  5. **Step 2 判定**：本轮无生产代码修补（仅回填 `tasks.md` / `check_list.md` 记账与 `.handoffs/current.md` 交接），Step 2 的 commit 前提「前三条有零星修补」不成立 ⇒ **不产生 task14 commit**；全部改动仍在工作树未提交。
