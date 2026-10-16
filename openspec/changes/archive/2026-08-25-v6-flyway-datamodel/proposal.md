# Proposal: v6-flyway-datamodel（AI 任务拆解系统 · Change 1/7）

> 归属于：docs/superpowers/plans/2026-08-25-ai-task-decomp-overview.md 的 7-change 路线图第一阶段。
> 规格：docs/superpowers/specs/2026-08-25-ai-task-decomp-design.md §2 全章 + ❌1 补项。
> 分支：feature/change1-v6-flyway-datamodel

## 背景

AI 任务拆解系统需要持久化底座：LLM 拆解产物要落成可跟踪的**子待办**（subtasks），注意事项要能多态挂载（notes），模板匹配需要**规则模板**及其版本历史（rule_templates / rule_template_versions），日常自动化需要**一次性调度**与**依赖触发**（task_schedules / task_triggers），自学习总结需要**执行日志**做分布式锁与审计（summary_execution_log）。同时，后续 Python agent-service 需要一个只读、列级受限的数据库账号（agent_reader）作为最小权限数据面。

## 目标

1. 新增 7 张全 `family_id` 多租户隔离的表：subtasks / notes / rule_templates / rule_template_versions / task_schedules / task_triggers / summary_execution_log
2. `tasks.status` 增加防御纵深 CHECK 约束（统一命名 chk_tasks_status）
3. PostgreSQL `agent_reader` 只读角色：DO 块条件创建 + 列级 SELECT 授权（排除 password_hash/access_token/deleted_at 等敏感列）+ 序列权限
4. ❌1 补项：过期 DRAFT 在任务查询中默认隐藏（Repository Specification 谓词）
5. family_id=1 演示家庭 3 条种子模板（周末大扫除 decomposition / 上学日检查书包 routine weekly / 月度记账 routine monthly）

## 非目标 (Non-goals)

- 不实现任何 Java HTTP 端点或调度器 —— 属于 Change 2（后端内部 Agent API + 调度）
- 不创建 Python tool-service / agent-service —— 属于 Change 3 / Change 4
- 不涉及任何前端改动 —— 属于 Change 6
- 不引入 Redis；不做行级安全 RLS（设计规格标注 Phase 2，仅留注释占位）
- 不修改 tasks 表既有业务语义，仅追加 CHECK 约束与查询默认谓词
- 不做 Compose 编排变更 —— 属于 Change 7

## 风险

| 风险 | 缓解 |
|---|---|
| H2 与 PG 方言差异（partial unique / 表达式索引 / DO 块 / 列级 GRANT 在 H2 不可用） | V6 主脚本用 H2 兼容语法；PG 专有能力全部放 afterMigrate__agent_reader.sql 回调，由 PG 真机执行 |
| agent_reader 密码为字面占位 placeholder-agent-reader-password | 生产部署前必须 ALTER ROLE 覆盖；已记录在迁移注释与本 proposal |
| V6 重放与既有约束命名冲突 | ALTER TABLE ... DROP CONSTRAINT IF EXISTS 后再 ADD；种子 INSERT 前 DELETE，保证幂等 |
| 回滚 | 见 design.md「回滚策略」段 |

## 实施记录（摘要）

- Commits：46c8f2a → 6c5c605（Task 1）→ 53e0bec（Task 2）→ 4a44e04 / dfd1c63（Fix Round 1）→ c0aa5fd（Final Fix Wave ×4 Important）
- 全分支终审：Critical 0，4 项 Important 已全部修复并通过 scoped re-review
- 详细过程日志：handoffs/current.md（tracked）「Change 1 执行进度」章节
