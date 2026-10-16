# Proposal: backend-internal-18api-scheduler（AI 任务拆解系统 · Change 2/7）

> 归属：docs/superpowers/plans/2026-08-25-ai-task-decomp-overview.md 第二阶段（依赖 Change 1 已合入）。
> 规格：docs/superpowers/specs/2026-08-25-ai-task-decomp-design.md §3 全章 + §5.7 调度归属。
> 分支：feature/change2-backend-internal-api（BASE 1c03cd9）

## 背景

Change 4 的 Python agent-service 需要一个受秘钥保护的内部 API 面来读写家庭数据，以及后端侧的定时自动化：每天 2:00 匹配 routine 模板生成任务、3:00 消化一次性定时、任务完成时触发依赖下游。本 change 交付这层后端增量，使 agent-service 与调度闭环具备数据通道。

## 目标

1. X-Internal-Secret 鉴权拦截器（恒定时间比较），注册到 `/api/internal/**`；CI 用 `internal-mock` Profile 放行
2. 新增 46xxx / 56xxx 错误码段共 10 条（msg 全中文）
3. C 组 18 内部端点：14 个 MCP 对齐读写端点（tasks/subtasks/notes/categories/templates/workload/summary-log）+ 4 个自动化端点（due-today / schedules-pending / on-completed / templates-new-version）
4. B 组 AgentCallbackClient（RestTemplate）：B-1 AI 拆解 30s 超时降级直建 PENDING；B-2 单家庭总结按分类 60s 独立超时不阻断
5. 调度器三入口：@Scheduled cron 2:00 日常 routine / 3:00 一次性 schedule 原子行锁消化 / TaskServiceImpl.changeStatus() 事务末挂 on_complete 事件
6. 测试矩阵：InternalSecret 3 + C组集成 18 + RoutineMatcher conditions 12 + 调度/事件 4 ≈ 37 条

## 非目标 (Non-goals)

- 不实现 Python agent-service 本体（B 组对端在 Change 4 才存在，本 change 只做客户端与降级）
- 不实现 tool-service 天气服务（C-15 weather 条件经 WeatherClient 接口调用，失败按 56501 跳过；真服务在 Change 3）
- 不做前端改动（Change 6）；不做 Compose/Nginx 编排（Change 7）
- 不改动 V1-V5 存量对外 API 行为（除 changeStatus() 追加事件挂钩）
- 不引入消息队列；跨实例互斥用 SQL 行锁（FOR UPDATE SKIP LOCKED）而非 Redis

## 风险

| 风险 | 缓解 |
|---|---|
| H2(CI) 与 PG 对 UPDATE RETURNING / SKIP LOCKED 支持差异 | pickNext 采用可移植写法：事务内条件 UPDATE（WHERE is_executed=false）按影响行数判定原子性；SKIP LOCKED 仅用于 PG 增强路径或以 H2 兼容等价实现，测试覆盖并发幂等 |
| weather 条件依赖未存在的 tool-service | WeatherClient 单独封装，异常→该模板本轮跳过并记日志（不抛出阻断整批） |
| 内部秘钥泄漏 | 默认值 dev-secret-1234-change-me 仅本地；生产经 INTERNAL_API_SECRET 注入；比较用 MessageDigest.isEqual 恒时 |
| 回滚 | 见 design.md「回滚策略」（纯增量代码，git revert 即可；无 DB schema 变更） |

## 实施约束（继承自 §F 硬约束）

- Java 注释/错误消息全中文；Result<T> 信封（code/msg/data）；不向前端泄露 stack trace
- 端点字段名 100% 对齐规格 §3.4，不自创别名
- SDD 执行模式：fresh implementer per task → review → fix loop ≤5 → final broad review
