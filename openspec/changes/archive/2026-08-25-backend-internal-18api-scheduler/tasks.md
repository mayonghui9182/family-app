# Tasks: backend-internal-18api-scheduler

> 粒度：单任务一次会话内可完成并验证。无 DB schema 变更，回滚=git revert（见 design.md）。
> 执行模式：SDD（fresh implementer per task → review → fix loop ≤5）。
>
> **记账订正（2026-09-26）**：以下 19 项在归档时全部漏勾，实际均已交付。复验证据：
> - 代码存在性：9 个类/测试文件均在库（`InternalAgentController`、`InternalSecretInterceptor`、四 Service、`AgentCallbackClient`、`TaskSchedulerJobs` 等）。
> - 测试实跑：`cd backend && ./gradlew test --tests com.familyapp.agent.InternalSecretTest --tests com.familyapp.agent.InternalAgentC1ToC18Test --tests com.familyapp.agent.RoutineMatcherTest --tests com.familyapp.agent.SchedulerAndEventTest` → **BUILD SUCCESSFUL**，合计 **38 tests / 0 failures / 0 errors**（InternalSecretTest 3、InternalAgentC1ToC18Test 18、RoutineMatcherTest 13、SchedulerAndEventTest 4）。
> - 人工核对：check_list.md 9 项全「通过」，并记「全量 149 tests 0 failures」。
> - 差异说明：Task 2 Step 1 原写「RoutineMatcherTest 12 conditions 用例」，实测为 **13** 用例（多 1 例），全绿。

## Task 1 — 基础设施（拦截器 + 错误码 + 配置 + Profile mock）
- [x] Step 1: 失败测试 InternalSecretTest 3 用例（缺失 40101 / 错误 40102 / 正确放行 C-1 空数组）
- [x] Step 2: ResultCode 追加 10 条中文码（46001/46002/46003/46004/46005/46501/56001/56002/56003/56501）
- [x] Step 3: InternalSecretInterceptor（MessageDigest.isEqual 恒时；test/internal-mock 放行）
- [x] Step 4: WebConfig 注册 /api/internal/**；application.yml 追加 internal-api.secret 与 agent-service.base-url
- [x] Step 5: InternalSecretTest PASS 3/3
- [x] Step 6: Commit feat(change2)

## Task 2 — C 组 18 端点 + 4 自动化 Service + AgentCallbackClient
- [x] Step 1: 失败测试骨架 InternalAgentC1ToC18Test（18 用例）+ RoutineMatcherTest（12 conditions 用例）
- [x] Step 2: InternalAgentController C-1~C-14（签名对齐规格 §3.4）
- [x] Step 3: 四 Service（RoutineMatcher/PendingSchedule/TaskEvent/TemplateVersion）
- [x] Step 4: AgentCallbackClient（B-1 30s 降级直建 / B-2 分类级 60s 不阻断）
- [x] Step 5: 30+12 用例全绿
- [x] Step 6: Commit feat(change2)

## Task 3 — 调度器增量与事件挂钩
- [x] Step 1: 失败测试 SchedulerAndEventTest 4 用例（手动调 bean；Awaitility ≤10s）
- [x] Step 2: TaskSchedulerJobs cron 2:00/3:00 两入口 + TaskServiceImpl.changeStatus() COMPLETED 边沿挂钩
- [x] Step 3: 多实例互斥与幂等保证（家庭粒度 SKIP LOCKED / 条件 UPDATE / 内存去重）
- [x] Step 4: 调度/事件测试 4/4 绿
- [x] Step 5: 全量回归 BUILD SUCCESSFUL（存量端点不受影响）
- [x] Step 6: 人工 check_list 9 项打勾
- [x] Step 7: Commit feat(change2)
