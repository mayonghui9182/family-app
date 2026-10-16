# Change 2: 后端内部 Agent 18 API + 调度增量 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 B4 规格 §3 全部后端增量：X-Internal-Secret 鉴权拦截器 + C 组 18 内部端点（14 MCP对齐 + 4 自动化必备）+ B 组 agent-service 回调 RestTemplate 客户端 + 3 调度器增量（2:00 日常/3:00 一次性/on_complete 依赖事件）+ 46xxx/56xxx 新错误码段（全中文）。

**Architecture:** 新增 InternalSecretInterceptor（恒定时间比较）→ WebConfig 注册到 /api/internal/** → InternalAgentController C-1~C-18 → 4 自动化 Service（RoutineMatcher/PendingSchedule/TaskEvent/TemplateVersion）→ AgentCallbackClient（B-1/B-2 RestTemplate 超时降级）。调度器沿用 Spring TaskScheduler @Scheduled cron 精确对齐 §5.7；事务写端点 @Transactional；H2 CI internal-mock Profile 放行 X-Internal-Secret。

**Tech Stack:** Java 17 / Spring Boot 3 / Spring Data JPA / RestTemplate / Spring TaskScheduler / Hibernate Validator / JUnit5 + MockMvc / Awaitility / H2 (CI)

## Global Constraints
- 18 端点签名 100% 对齐 B4 §3.4（Phase 2 换 MCP 不改签名）；字段名不可自创别名
- 所有写端点 @Transactional + Result<T> 信封（code/msg/data）；msg 全中文
- X-Internal-Secret 恒定时间 MessageDigest.isEqual()；空头 40101「内部API秘钥缺失」/错头 40102「内部API秘钥不正确」
- 错误码分段严格：46001-46499 AI校验4xx / 46500-46599 自动化校验4xx / 56001-56499 AI运行5xx / 56501-56599 工具5xx
- 调度原子性：task_schedules 用 UPDATE ... WHERE is_executed=false 原子行锁；routine 用 FOR UPDATE SKIP LOCKED 家庭粒度
- on_complete 事件：TaskServiceImpl.changeStatus() 事务末同步调用，同事务回滚不触发
- Java 注释/异常/错误全中文；不泄露 stack trace 给前端
- Spring Profile `internal-mock`：CI 放行 X-Internal-Secret，但 X-Family-Id/X-Test-Family-Id 仍强制

---

## File Structure
| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/java/com/familyapp/config/InternalSecretInterceptor.java | Create | X-Internal-Secret 恒定时间校验 |
| backend/src/main/java/com/familyapp/config/WebConfig.java | Modify | 注册拦截器到 /api/internal/** |
| backend/src/main/java/com/familyapp/common/ResultCode.java | Modify | 追加 46xxx/56xxx 10 条中文码 |
| backend/src/main/java/com/familyapp/agent/InternalAgentController.java | Create | C 组 18 端点 Controller |
| backend/src/main/java/com/familyapp/agent/RoutineMatcherService.java | Create | C-15 routine due-today（4 种 JSONB conditions） |
| backend/src/main/java/com/familyapp/agent/PendingScheduleService.java | Create | C-16 schedules/pending 原子行锁 |
| backend/src/main/java/com/familyapp/agent/TaskEventService.java | Create | C-17 on-complete 事件触发 |
| backend/src/main/java/com/familyapp/agent/TemplateVersionService.java | Create | C-18 templates/new-version 事务版本+1 |
| backend/src/main/java/com/familyapp/agent/AgentCallbackClient.java | Create | RestTemplate 调 B-1 AI拆解 / B-2 单家庭总结 |
| backend/src/main/java/com/familyapp/task/TaskSchedulerJobs.java | Create | @Scheduled 2:00 日常 / 3:00 一次性 |
| backend/src/main/java/com/familyapp/task/TaskServiceImpl.java | Modify | changeStatus() 末调用 onCompleted() |
| backend/src/test/java/com/familyapp/agent/InternalAgentC1ToC18Test.java | Create | 18 端点集成测试 |
| backend/src/test/java/com/familyapp/agent/RoutineMatcherTest.java | Create | C-15 12 条 conditions 断言 |
| backend/src/test/java/com/familyapp/agent/InternalSecretTest.java | Create | 秘钥拦截器 3 用例 |
| backend/src/main/resources/application.yml | Modify | 追加 internal-api.secret / agent-service.base-url |

---

### Task 1: 基础设施（拦截器 + 错误码 + 配置 + Profile mock）

- [ ] **Step 1: 写失败测试 InternalSecretTest 3 用例**
用例1：缺 X-Internal-Secret → MockMvc status 401 + Result.code=40101「内部API秘钥缺失，请联系管理员」
用例2：头「WrongSecret」 → 401 + code=40102「内部API秘钥不正确」
用例3：头「test-secret-correct」 → C-1 get_tasks 返回 200 + data=[]
Run: `cd backend ; ./gradlew test --tests InternalSecretTest` Expected: FAIL

- [ ] **Step 2: 追加 10 条 46xxx/56xxx ResultCode（全中文）**
46001 AI_PROPOSAL_NOT_FOUND "这个 AI 提案不存在或超过 24 小时已过期，请重新输入任务试试"
46002 CATEGORY_MISMATCH "分类不存在于当前家庭，检查 family_id 作用域"
46003 TEMPLATE_INACTIVE "此模板已停用，无法生成任务"
46004 SUBTASK_BATCH_EMPTY "批量新增子待办内容为空，至少 1 条"
46005 SCHEDULE_PAST_DATE "一次性定时日期已过去，选择今天之后的日期"
46501 TRIGGER_SOURCE_NOT_COMPLETED "依赖触发源任务状态不是 COMPLETED，暂不触发下游"
56001 AI_DECOMPOSE_TIMEOUT "AI 拆解超时了（30 秒），点击重试按钮再来一次就行"
56002 AI_JSON_TWICE_FAILED "AI 返回格式连续两次解析失败，已自动转手动创建模式"
56003 SUMMARY_CATEGORY_TIMEOUT "单分类总结超时（60 秒），该分类跳过不阻断其它分类"
56501 WEATHER_SERVICE_DOWN "天气服务暂时不可用，日常模板的天气条件暂时跳过"

- [ ] **Step 3: 写 InternalSecretInterceptor**
preHandle() 读 request.getHeader("X-Internal-Secret")；@Value("\${internal-api.secret}") String expectedSecret；
MessageDigest.isEqual(header.getBytes(UTF_8), expectedSecret.getBytes(UTF_8)) 恒定比较；
空头 → response.writer.write(JSON Result.error(40101)) return false；
错头 → response.writer.write(JSON Result.error(40102)) return false；
通过 → return true。
Spring Profile test 或 internal-mock 时跳过校验（@ConditionalOnProperty）。

- [ ] **Step 4: WebConfig 注册 + application.yml 配置**
WebConfig.addInterceptors → registry.addInterceptor(internalSecretInterceptor).addPathPatterns("/api/internal/**")
application.yml 追加两行：
internal-api:
  secret: ${INTERNAL_API_SECRET:dev-secret-1234-change-me}
agent-service:
  base-url: ${AGENT_SERVICE_BASE_URL:http://localhost:8700}

- [ ] **Step 5: Run InternalSecretTest Expected: PASS 3/3**
- [ ] **Step 6: Commit feat(change2): InternalSecret拦截器 + 10条ResultCode + 配置**

---

### Task 2: C 组 18 内部端点（Controller + 4 自动化 Service + AgentCallbackClient）

- [ ] **Step 1: 写失败测试 InternalAgentC1ToC18Test + RoutineMatcherTest 骨架**
InternalAgentC1ToC18Test 18 用例：
C-1 get_tasks → $.data 数组存在 / C-3 create_task 返回 id 非空 / C-4 batch subtasks size=3 → created=3 /
C-10 get_categories 返回数组 / C-11 templates → decomposition + routine 两类 /
C-12 few-shot 最近 10 条 COMPLETED / C-13 workload → pending_count + completed_count /
C-14 summary-log → 返回 id / C-15 due-today → 命中 routine 2 条 / C-16 pending → 返回 1 条未执行 /
C-17 on-completed → 10 秒内下游 task 插入 / C-18 new-version → version = 原版本 + 1
RoutineMatcherTest 12 条 conditions：
weekly 周一/周五命中 / weekly 周末不命中 / monthly day=1 今日命中 / monthly day=15 不命中 /
weather sunny 命中 / weather rain 不命中 / combo AND(weekly+weather) 命中 / combo OR 任一分命中 /
嵌套 combo 3 层 / past_date 不命中 / future_date 不命中 / 今日 exactly
Run: `./gradlew test --tests InternalAgentC1ToC18Test --tests RoutineMatcherTest` Expected: FAIL

- [ ] **Step 2: InternalAgentController C-1~C-14 签名骨架（14 MCP 对齐）**
C-1  GET /api/internal/agent/tasks?status=&category_id=&keyword=&page=&size= → Page<TaskDTO>
C-2  GET /api/internal/agent/tasks/{id} → TaskDTO（含 subtasks[] + notes[] 嵌套）
C-3  POST /api/internal/agent/tasks {title, description, category_id?, priority?, expires_at?, source="agent", assignee_id?} → TaskDTO
C-4  POST /api/internal/agent/subtasks/batch {task_id, subtasks:[{title,description,sort_order,is_ai_generated=true}]} → BatchSubtaskResp(created_count, ids[])
C-5  PUT /api/internal/agent/tasks/{id} {title?, description?, priority?, status?, category_id?, due_date?} → TaskDTO
C-6  PUT /api/internal/agent/subtasks/{id} {status?, title?, description?, sort_order?} → SubtaskDTO
C-7  DELETE /api/internal/agent/subtasks/{id} → Result<Void>
C-8  POST /api/internal/agent/notes {target_type(task|subtask), target_id, content} → NoteDTO
C-9  DELETE /api/internal/agent/notes/{id} → Result<Void>
C-10 GET /api/internal/agent/categories → List<CategoryDTO>
C-11 GET /api/internal/agent/templates?type=&is_active= → List<TemplateDTO>
C-12 GET /api/internal/agent/templates/few-shot?category_id=&limit=10 → FewShotDTO(tasks_with_subtasks[])
C-13 GET /api/internal/agent/workload?assignee_id=&window_days=7 → WorkloadDTO(pending, completed, high_priority)
C-14 POST /api/internal/agent/summary-log {status(success|failed), categories_updated[], failed_categories[], trigger_source} → SummaryLogDTO

- [ ] **Step 3: 4 个自动化 Service（C-15~C-18）**
C-15 RoutineMatcherService.dueToday(familyId):
  遍历 rule_templates.type=routine + is_active=true；解析 JSONB conditions 支持 4 种：
  - weekly: {"kind":"weekly","weekdays":[1,2,3,4,5],"time":"HH:mm"} → today.dayOfWeek.value in weekdays
  - monthly: {"kind":"monthly","day":1,"time":"HH:mm"} → today.dayOfMonth == day
  - weather: {"kind":"weather","weather_type":"sunny","city":"北京"} → 调 tool-service /api/weather 比对
  - combo: {"kind":"combo","op":"AND|OR","items":[嵌套4种结构]} → 递归计算逻辑组合
  返回 DueTodayRoutineDTO[]（template_id / routine_title / description / priority / needs_ai）
C-16 PendingScheduleService.pickNext(familyId):
  原子 SQL：UPDATE task_schedules SET is_executed=true WHERE id=(SELECT id FROM task_schedules WHERE family_id=? AND trigger_date=CURRENT_DATE AND is_executed=false ORDER BY id LIMIT 1) RETURNING *；若无返回 → Optional.empty()
C-17 TaskEventService.onCompleted(sourceTaskId):
  查询 task_triggers WHERE source_task_id=? AND is_active=true；逐条 target_template（JSONB 同 V6 结构）：
  needs_ai_decomposition=true → AgentCallbackClient.B1；false → TaskService.createDirectPending(target_template)
  同事务执行，上游回滚时整条链回滚；5 分钟内存去重 Map<TaskId, Instant> 防重复触发
C-18 TemplateVersionService.createNewVersion(templateId, newContentJsonb, changeSummary, isImportant=false):
  @Transactional 两步：① INSERT rule_template_versions (template_id, version=当前+1, content=newContentJsonb, change_summary, is_important, parent_version_id=当前版本id)
  ② UPDATE rule_templates SET version=新版本号, content=newContentJsonb, parent_version_id=刚插入versions.id WHERE id=templateId；任一步失败 RuntimeException 回滚

- [ ] **Step 4: AgentCallbackClient RestTemplate（B-1 30s 拆解 / B-2 单分类 60s 总结）**
RestTemplate 配置：HttpComponentsClientHttpRequestFactory；
B-1 setConnectTimeout=5000; setReadTimeout=30000（30秒拆解超时）
B-1 POST {agent-service.base-url}/api/internal/ai-decompose
请求体：{title, description, category_id, family_id, fallback=true}
响应体：DecomposeResp{ decomposed:boolean, task_id:Long?, error_code?, message? }
30s 超时 catch → fallback=true，backend 直建 PENDING Task 无子待办，返回 Result 附中文提示："AI 拆解未响应，已为您创建普通待办任务，可后续手动添加子步骤"
B-2 单独 RestTemplate：setReadTimeout=60000 × categories.size() 估算；单分类 60s 超时独立记 failed_categories，不阻断其它分类
POST {agent-service.base-url}/api/internal/summary/one-family
请求体：{family_id, category_ids[]?, timeout_per_category_ms=60000, trigger_source=auto}
响应体：SummaryResult{ status, categories_updated[], versions_created, failed_categories[{category_id, reason}] }

- [ ] **Step 5: Run InternalAgentC1ToC18Test + RoutineMatcherTest Expected: PASS（30+12 = 42 用例）**
- [ ] **Step 6: Commit feat(change2): C组18端点 + Routine匹配 + 原子Schedule + TaskEvent + TemplateVersion + AgentCallbackClient**

---

### Task 3: 调度器增量（2:00 日常 / 3:00 一次性 / on_complete 事件挂钩）

- [ ] **Step 1: 写失败测试（调度 3 用例 + 事件 1 用例）**
SchedulerTest（手动 @Autowired TaskSchedulerJobs bean 调用，不依赖 @Scheduled 注解时间）：
用例1：dailyRoutineJob.executeForFamily(familyId=1) → tasks表 10秒内 count 增加，status=PENDING, source=routine
用例2：oneTimeScheduleJob.executeForFamily(familyId=1) → task_schedules.is_executed=true + tasks表新增1条
用例3：10次并发调用 oneTimeScheduleJob → is_executed=true 只有1条（幂等行锁）
用例4：changeStatus(id, COMPLETED) 源任务 has trigger → Awaitility 10秒 下游 tasks 表 count+1
Run: `./gradlew test --tests "*Scheduler*Test" --tests "*Event*Test"` Expected: FAIL

- [ ] **Step 2: TaskSchedulerJobs.java 3 个调度入口**
@Scheduled(cron = "0 0 2 * * ?") // 每天 02:00 日常
public void dailyRoutineJob() {
  SELECT families.id FROM families FOR UPDATE SKIP LOCKED（多实例互斥）
  每家庭: List<DueTodayRoutine> = routineMatcherService.dueToday(familyId)
  每条 routine: needs_ai → agentCallbackClient.decomposeOrFallback(routine)；false → taskService.createDirectPending(routine.template)
  失败 catch 单家庭隔离：log.error(familyId, e)，继续下一个家庭
}

@Scheduled(cron = "0 0 3 * * ?") // 每天 03:00 一次性定时
public void oneTimeScheduleJob() {
  while (true) {
    Optional<TaskSchedule> schedule = pendingScheduleService.pickNextGlobal(); // 跨家庭先到先得原子行锁
    if (schedule.isEmpty()) break;
    needs_ai ? agentCallbackClient.decomposeOrFallback(schedule.get().targetTemplate) : taskService.createDirect(schedule.get())
  }
}

TaskServiceImpl.changeStatus() 追加：
if (TaskStatus.COMPLETED.equals(newStatus) && !TaskStatus.COMPLETED.equals(oldStatus)) {
  taskEventService.onCompleted(taskId); // 事务末触发，上游回滚链连带回滚
}

- [ ] **Step 3: 调度重复保证**
2:00 日常：FOR UPDATE SKIP LOCKED families.id 保证多实例每家庭只跑一次
3:00 一次性：pendingScheduleService.pickNext() 原子 UPDATE 行锁（天然唯一）
on_complete：内存 Map<TaskId, Instant> computedIfAbsent 5 分钟窗口，重复请求 return "DUPLICATE" 幂等
draft 过期调度：V1 已有 2:30，不重复（§5.7 归属矩阵已核对）

- [ ] **Step 4: Run 调度/事件测试 Expected: PASS 4/4**
- [ ] **Step 5: 全量后端回归 ./gradlew check test integrationTest → BUILD SUCCESSFUL（V1 老端点不受影响）**
- [ ] **Step 6: 人工 check_list 9 项打勾**
  [x] InternalSecret 恒定时间比较 [x] 10 ResultCode 全中文 [x] C-1~C-14 签名规格对齐
  [x] C-15 4 种 routine conditions（weekly/monthly/weather/combo） [x] C-16 原子 UPDATE SKIP LOCKED
  [x] C-17 on_complete 同事务 [x] C-18 @Transactional 版本+1 回滚 [x] B-1 30s 超时降级 [x] cron 2:00/3:00 精确
- [ ] **Step 7: Commit feat(change2): 调度器增量(2:00日常/3:00一次性/on_complete) + 重复保证**

---

## Self-Review
- [x] Spec 覆盖：§3.1-§3.4 端点/错误码 + §5.7 backend调度 + B组回调
- [x] 无占位符：每个 Step 给出具体 SQL UPDATE 模式/cron 表达式/错误码/字段名
- [x] 类型一致：C-18 createNewVersion / decomposeOrFallback 名称全程统一
- [x] 测试：InternalSecret 3 + C组18 + Routine 12 + 调度事件 4 = 37 条用例（覆盖正/反/并发）

---

## 下一步
启动 OpenSpec：创建 openspec/changes/2026-08-25-backend-internal-18api-scheduler/ 按 Task 1→2→3 顺序实施。

（Change 2 Plan 结束）