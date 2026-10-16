# Delta Spec: backend-internal-18api-scheduler

## ADDED Requirements

### Requirement: 内部 API 秘钥鉴权
所有 `/api/internal/**` 请求 SHALL 携带 `X-Internal-Secret` 头；服务端以 `MessageDigest.isEqual` 恒定时间比较。缺失 → HTTP 401 + code=40101「内部API秘钥缺失，请联系管理员」；不匹配 → 401 + code=40102「内部API秘钥不正确」。Spring Profile `test`/`internal-mock` 下 SHALL 跳过校验（X-Family-Id 等业务头仍强制）。

#### Scenario: 缺失秘钥被拒
- **WHEN** 不带头调用任意 /api/internal/** 端点
- **THEN** 401 且 code=40101

#### Scenario: 错误秘钥被拒
- **WHEN** X-Internal-Secret=WrongSecret
- **THEN** 401 且 code=40102

### Requirement: C 组 14 个 MCP 对齐端点
SHALL 提供：C-1 GET tasks(分页过滤) / C-2 GET tasks/{id}(嵌套 subtasks+notes) / C-3 POST tasks(source=agent) / C-4 POST subtasks/batch(is_ai_generated=true) / C-5 PUT tasks/{id} / C-6 PUT subtasks/{id} / C-7 DELETE subtasks/{id} / C-8 POST notes / C-9 DELETE notes/{id} / C-10 GET categories / C-11 GET templates?type=&is_active= / C-12 GET templates/few-shot?category_id=&limit=10(最近 COMPLETED 任务含子待办) / C-13 GET workload?assignee_id=&window_days=7(pending/completed/high_priority) / C-14 POST summary-log。写端点 @Transactional + Result 信封。

#### Scenario: 批量子待办
- **WHEN** C-4 提交 3 条子待办
- **THEN** created_count=3 且全部 is_ai_generated=true 落库

#### Scenario: few-shot 上限
- **WHEN** C-12 limit=10
- **THEN** 返回不超过 10 条最近完成任务及其子待办

### Requirement: C-15 routine due-today 匹配器
SHALL 遍历 family 的 active routine 模板并解析 4 种 JSONB conditions：
weekly{weekdays[],time} / monthly{day,time} / weather{weather_type,city} / combo{op:AND|OR, items[]递归}。
weather 经 WeatherClient 调 tool-service，失败按 56501 处理跳过该模板不阻断。

#### Scenario: weekly 命中与不命中
- **WHEN** weekdays=[1,5] 且今天是周一 → 命中；今天是周六 → 不命中

#### Scenario: combo AND/OR
- **WHEN** AND(weekly命中, weather命中) → 命中；OR 中任一分支命中即命中

### Requirement: C-16 一次性调度原子消化
pickNext SHALL 以条件 UPDATE（WHERE is_executed=false）保证同一条 schedule 全局仅被消费一次；无待执行返回空。

#### Scenario: 并发幂等
- **WHEN** 同一 schedule 被 10 次并发消费
- **THEN** 仅一次成功置 is_executed=true 且只产生一个下游任务

### Requirement: C-17 on_complete 依赖触发
TaskServiceImpl.changeStatus() 到达 COMPLETED（且旧状态非 COMPLETED）SHALL 在同事务末触发 task_triggers(on_complete, active)：needs_ai=true 走 AgentCallbackClient.B1（30s 超时降级直建 PENDING），否则直建 PENDING。上游事务回滚 SHALL 连带回滚下游写入；5 分钟内存去重防重复。

#### Scenario: 完成触发下游
- **WHEN** 带触发器的源任务 changeStatus→COMPLETED
- **THEN** 10 秒内下游 tasks 新增 1 条 source=routine/agent

### Requirement: C-18 模板新版本（事务两步）
createNewVersion SHALL @Transactional 执行：①INSERT versions(version=cur+1, parent_version_id=cur)；②UPDATE rule_templates SET version/content/parent_version_id。任一步失败整体回滚。

#### Scenario: 版本递增
- **WHEN** 对 version=1 模板创建新版本
- **THEN** versions 出现 version=2 行且 rule_templates.version=2、parent 指向新行

### Requirement: B 组回调客户端与降级
AgentCallbackClient SHALL 用 RestTemplate 调 B-1 /api/internal/ai-decompose（connect 5s/read 30s；失败或超时 → fallback 直建 PENDING 无子待办，附中文提示「AI 拆解未响应，已为您创建普通待办任务，可后续手动添加子步骤」）；B-2 /api/internal/summary/one-family 单分类 60s 独立超时记入 failed_categories 不阻断其它分类。

#### Scenario: B-1 超时降级
- **WHEN** agent-service 30s 未响应
- **THEN** 本地直建 PENDING Task 且响应携带中文降级提示

### Requirement: 调度器 cron 与多实例互斥
TaskSchedulerJobs SHALL 提供 @Scheduled(cron="0 0 2 * * ?") dailyRoutineJob（家庭粒度 SKIP LOCKED 互斥、单家庭异常隔离继续）与 @Scheduled(cron="0 0 3 * * ?") oneTimeScheduleJob（全局 pickNext 循环至空）。测试经手动调 bean 方法验证，不依赖真实 cron 时钟。

#### Scenario: 单家庭失败隔离
- **WHEN** 家庭 A 处理抛异常
- **THEN** 其余家庭继续处理，错误仅记日志
