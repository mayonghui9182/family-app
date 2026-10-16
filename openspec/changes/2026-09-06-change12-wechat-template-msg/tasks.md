# Tasks: Change 12 - 微信模板消息推送

- [x] **Task 1：wechat-service 模板消息层（TDD）**
  - **Step 1：失败测试（pytest + MockTransport）**
    - [x] `tests/test_template.py`：三事件渲染（template_id 取 settings、中文字段、url 按事件区分 /tasks vs /dashboard）；批量端点无密钥 403；未知 event 单条 ok=false 零微信调用；模板 id 未配置 → skipped=true 降级；errcode=0 全 ok；43004 不重试（send 调用恰 1 次）；40001 刷 token 重试成功（token 端点 2 次、send 2 次）；微信 500 传输错误单条隔离端点仍 200
  - **Step 2：实现**
    - [x] `app/settings.py`：加 wechat_tpl_task_digest_id / wechat_tpl_summary_id / wechat_tpl_task_assign_id（默认空串）
    - [x] `app/wechat/template.py`：EVENT 映射（event→env template_id、url path、字段渲染函数）；`send_template_message(client, manager, settings, openid, event, params)`：40001/42001 强制刷新重试一次、43004/43010 不重试、传输异常单条隔离
    - [x] `app/routes/internal_routes.py`：POST `/internal/template/send`（X-Internal-Secret 40300；body 批量；逐条结果 results）
  - **Step 3：验证** `cd wechat-service && ../agent-service/.venv/bin/python -m pytest -q`（预期 16+ 新增 ≈ 24 passed）+ ruff + mypy strict 0
  - [ ] **Step 4：Commit** `feat(change12/task1): wechat-service 模板消息批量发送端点`（用户未要求提交，暂不 commit）

- [x] **Task 2：backend 通知客户端 + 服务 + 接收人解析（TDD）**
  - **Step 1：失败测试（JUnit）**
    - [x] `WechatNotifyClientTest`（MockRestServiceServer）：POST /internal/template/send 路径 + X-Internal-Secret 头 + 批量 body；连接拒绝/500 → 不抛出返回空结果；enabled=false → 零 HTTP 调用
    - [x] `WechatNotifyServiceTest`（Mockito 仓库/mock client）：接收人解析（绑定成员收、未绑定不收、已退出成员不收）；digest 计数（今日到期/逾期正确，COMPLETED/CANCELLED/deletedAt 排除）；双零跳过；summary_ready 参数含家庭名+日期；task_assigned 仅 assigneeId 且有绑定才发
  - **Step 2：实现**
    - [x] `wechat/WechatNotifyClient.java`：RestTemplate（connect 2s/read 5s），`sendMessages(List<NotifyMessage>)` 吞所有异常返回 results；配置 `wechat.service-base-url`（默认 http://wechat-service:8900）、`wechat.notify.enabled`（默认 true）；DTO records（NotifyMessage/NotifyResult）
    - [x] `wechat/WechatNotifyService.java`：`notifyDigest(familyId, today)` / `notifySummaryReady(familyId)` / `notifyTaskAssigned(Task)`；接收人解析 via FamilyMemberRepository + WechatBindRepository.findAllByUserIdIn（新增）
    - [x] `WechatBindRepository`：加 `findAllByUserIdIn(Collection<Long>)`
    - [x] `TaskRepository`：加今日到期/逾期计数查询（deletedAt null + status NOT IN COMPLETED/CANCELLED + dueDate 条件）
    - [x] `application.yml`：wechat.* 配置项
  - **Step 3：验证** `cd backend && ./gradlew test`（预期 242 + 新增用例全绿）
  - [ ] **Step 4：Commit** `feat(change12/task2): backend 微信通知客户端与接收人解析`（暂不 commit）

- [x] **Task 3：三触发点接线 + 编排（TDD）**
  - **Step 1：失败测试**
    - [x] `WechatNotifyJobsTest`：digestForFamily 逐家庭隔离（一家异常不影响下家）；08:00 cron 方法存在
    - [x] 钩子接线测试（@SpringBootTest + MockRestServiceServer）：C-20 finish success → wechat-service 收到 summary_ready；finish failed → 不发；创建任务 assignee 已绑定 → task_assigned；**wechat-service 连接拒绝时任务创建仍 201、finish 仍 200**
  - **Step 2：实现**
    - [x] `wechat/WechatNotifyJobs.java`：`@Scheduled(cron="0 0 8 * * ?")` dailyDigestJob 遍历家庭逐家隔离；可测试入口 digestForFamily(familyId, today)
    - [x] `InternalAgentController` C-20 finishSummaryLog：success 后调 notifySummaryReady（失败不影响）
    - [x] `TaskServiceImpl.create` 与 `DirectTaskCreator`：save 后调 notifyTaskAssigned（assigneeId 非空才可能发；服务内部判断绑定）
    - [x] docker-compose.yml：backend environment 加 WECHAT_SERVICE_BASE_URL=http://wechat-service:8900、WECHAT_NOTIFY_ENABLED=${WECHAT_NOTIFY_ENABLED:-true}；wechat-service environment 加 WECHAT_TPL_TASK_DIGEST_ID/_SUMMARY_ID/_TASK_ASSIGN_ID（`:-` 空默认）；`docker compose config --quiet` 通过
    - [x] `.env.example` 段5 追加三模板 id + 开关（注释模板 + 测试号建模板说明）
  - **Step 3：验证** 四命令：`./gradlew test` + wechat-service pytest + agent-service pytest 回归 + `npx vitest run`（前端零改动预期 81/81 不变）+ `npm run build`
  - [ ] **Step 4：真机联调 DEFERRED（用户侧）**：测试号后台建 3 个模板（正文范例见 handoff runbook）→ env 填 id → 手动触发总结/建任务/等 08:00 验证收消息
  - [ ] **Step 5：Commit** `feat(change12/task3): 三事件触发接线 + compose 模板配置`（暂不 commit）

- [x] **收尾：验收 + handoff**
  - [x] check_list.md 逐条贴证（三事件触发/批量隔离/token 重试/43004 不重试/未配置降级/失败隔离/开关/四命令数字）
  - [x] `.handoffs/current.md`：Change 12 完成记录 + 真机 runbook（测试号建 3 模板正文范例、env 填法、三事件验证步骤）；**不归档**（真机 pending）
