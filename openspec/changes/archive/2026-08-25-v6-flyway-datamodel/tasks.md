# Tasks: v6-flyway-datamodel

> 粒度约定：单任务一次会话内可完成并验证。本 change 已实施完毕，状态为完成态存档。
> DB schema 任务回滚策略见 design.md「回滚策略」（DDL 逆序 DROP + flyway_schema_history 清理 + 代码 revert 三段式）。

## Task 1 — V6 迁移主骨架（TDD）
- [x] Step 1: 写失败测试 V6MigrationTest（7 表存在 + family_id=1 种子 3 条）→ RED（FileNotFoundException）
- [x] Step 2: V6__Add_Agent_AI_Task_Decomposition.sql 主骨架（subtasks / notes / rule_templates / rule_template_versions / task_schedules / task_triggers / summary_execution_log + chk_tasks_status）
- [x] Step 3: 3 条种子模板（周末大扫除 decomposition / 上学日检查书包 routine weekly / 月度记账 routine monthly）
- [x] Step 4: PG 专有部分剥离至 afterMigrate__agent_reader.sql（agent_reader DO 块 + 列级 GRANT + partial/expression index 升级）
- [x] Step 5: V6MigrationTest → GREEN；全量回归 BUILD SUCCESSFUL
- [x] Evidence: commit 6c5c605

## Task 2 — ❌1 过期 DRAFT 默认过滤（TDD + Fix Loop）
- [x] Step 1: 失败测试 V6DraftFilterTest 3 用例 → RED（过期 DRAFT 未隐藏）
- [x] Step 2: TaskSpecifications.withValidDraftPredicate()/notDeleted() + TaskRepository 继承 JpaSpecificationExecutor + TaskServiceImpl.list() Specification 链
- [x] Step 3: GREEN 后终审发现 Critical-1 软删泄漏 → Fix Round 1/5（commit 4a44e04）：notDeleted() 链首 + familyId 边界用例
- [x] Step 4: 最终 V6DraftFilterTest 5/5、TaskServiceTest 21/21、全量回归通过
- [x] Evidence: commits 53e0bec → 4a44e04

## Task 3 — 双环境验收 + 终审修复波
- [x] Step 1: application-test.yml H2 MODE=PostgreSQL 确认
- [x] Step 2: Final Whole-Branch Review（3042 行 diff）→ 4 Important 一波修复（commit c0aa5fd）：afterMigrate DO 块包裹条件判断 / GRANT USAGE ON ALL SEQUENCES 收敛 / cb.currentTimestamp() 替换 / 测试注释修正
- [x] Step 3: Scoped re-review 通过，残留 PR 项全部 Accept/defer 有主
- [x] Step 4: PG 真机 `./gradlew flywayMigrate` + afterMigrate 回调执行 + agent_reader 权限抽查（原计划 defer 至 Change 7，本次会话补验 —— 结果记入 check_list.md）。**补验已完成（归档时漏勾）**：check_list.md 记 ✅ PASS —— flywayMigrate 6 行全 success、afterMigrate__agent_reader.sql 回调执行、agent_reader 权限矩阵抽查通过。
