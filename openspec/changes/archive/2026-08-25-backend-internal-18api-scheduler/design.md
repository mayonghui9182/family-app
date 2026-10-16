# Design: backend-internal-18api-scheduler

## 文件清单（对齐 plan File Structure）

| 文件 | 操作 | 职责 |
|---|---|---|
| config/InternalSecretInterceptor.java | Create | X-Internal-Secret 恒时校验；test/internal-mock Profile 放行 |
| config/WebConfig.java | Modify | 注册拦截器 → /api/internal/** |
| common/ResultCode.java | Modify | 追加 46xxx×5 + 46501 + 56xxx×4 共 10 条中文码 |
| agent/InternalAgentController.java | Create | C-1~C-18 十八端点 |
| agent/RoutineMatcherService.java(+Impl) | Create | 4 种 conditions 解析（weekly/monthly/weather/combo 递归） |
| agent/PendingScheduleService.java(+Impl) | Create | 条件 UPDATE 原子消化 |
| agent/TaskEventService.java | Create | on_complete 触发 + 5min 内存去重 |
| agent/TemplateVersionService.java(+Impl) | Create | @Transactional 版本两步 |
| agent/AgentCallbackClient.java | Create | RestTemplate B-1(5s/30s) / B-2(60s/分类) |
| agent/WeatherClient.java | Create | tool-service 天气查询封装（Change 3 对端未存在，异常→skip） |
| task/TaskSchedulerJobs.java | Create | cron 2:00 日常 / 3:00 一次性 |
| task/TaskServiceImpl.java | Modify | changeStatus() 追加 COMPLETED 边沿事件挂钩 |
| application.yml | Modify | internal-api.secret / agent-service.base-url |
| test/.../agent/InternalSecretTest.java | Create | 3 用例 |
| test/.../agent/InternalAgentC1ToC18Test.java | Create | 18 集成用例 |
| test/.../agent/RoutineMatcherTest.java | Create | 12 conditions 用例 |
| test/.../task/SchedulerAndEventTest.java | Create | 调度/事件 4 用例（Awaitility） |

## 关键决策

### D1 · 可移植的原子消化（C-16）
plan 原文用 `UPDATE ... RETURNING`（PG 方言）。为保 H2 CI 通过：@Transactional 内执行
`UPDATE task_schedules SET is_executed=true WHERE id=(SELECT id ... WHERE is_executed=false ORDER BY id LIMIT 1)`，
按受影响行数==1 判定抢到，再 SELECT 读回该行。语义等价且双方言可用；PG 上的原子性由单条 UPDATE 保证不变。

### D2 · WeatherClient 抽象
C-15 weather 分支不直连 RestTemplate，经独立 Bean 注入；Connection/Read 超时各 3s；任何异常视为「天气不可知」，该模板本轮跳过（log.warn 中文），不影响同批其它模板 —— 对应 ResultCode 56501 的用户文案语义。

### D3 · Profile 放行实现
InternalSecretInterceptor.preHandle 内查 `Environment.getActiveProfiles()` 含 `test`/`internal-mock` 即放行（比 @ConditionalOnProperty 更直观，且保留业务头校验链路）。

### D4 · on_complete 边沿触发
仅在 oldStatus!=COMPLETED && newStatus==COMPLETED 时触发，避免重复完成重复点火；内存去重 Map<taskId,Instant> computeIfAbsent 5 分钟窗口作为第二道幂等（跨请求重复回调）。

### D5 · 测试并发用例（H2）
用例「10 次并发仅一次成功」在 H2 下用 ExecutorService+CountDownLatch 对同一 scheduleId 并发调用 pickNext；断言成功数==1 且 tasks 新增==1。H2 默认锁表级行锁足以验证条件 UPDATE 语义。

## 回滚策略

纯 Java 增量 + 配置追加，无 DB schema 变更：
1. `git revert` 本 change 全部 commit 即完全回滚
2. application.yml 两行配置删除无副作用（键未被存量代码引用）
3. WebConfig 注册行移除后 /api/internal/** 404，存量对外 API 不受影响
