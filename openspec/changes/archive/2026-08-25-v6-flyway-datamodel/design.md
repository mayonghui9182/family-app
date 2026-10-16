# Design: v6-flyway-datamodel

## 文件清单

| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/resources/db/migration/V6__Add_Agent_AI_Task_Decomposition.sql | Create | 7 表 DDL + 索引 + tasks.status CHECK + 3 种子（H2 兼容语法） |
| backend/src/main/resources/db/migration/afterMigrate__agent_reader.sql | Create | PG 专有回调：agent_reader 角色 DO 块 + 列级 GRANT + partial unique / 表达式唯一索引升级 |
| backend/src/main/java/com/familyapp/task/TaskSpecifications.java | Create | `withValidDraftPredicate()` / `notDeleted()` Specification 工具 |
| backend/src/main/java/com/familyapp/task/TaskRepository.java | Modify | 追加 `extends JpaSpecificationExecutor<Task>`（保留 findWithFilters @Query 兼容 V1-V5 测试） |
| backend/src/main/java/com/familyapp/task/TaskServiceImpl.java | Modify | list() 重写为 Specification 链：notDeleted → validDraft → 用户过滤，Sort DESC createdAt |
| backend/src/test/java/com/familyapp/V6MigrationTest.java | Create | H2 迁移验证（7 表存在 + family_id=1 种子 3 条） |
| backend/src/test/java/com/familyapp/task/V6DraftFilterTest.java | Create | ❌1 专项 5 用例（@DataJpaTest） |
| backend/src/test/resources/db/v6-test-setup.sql | Create | H2 测试幂等预置（ddl-auto=create-drop 不跑 Flyway 时补种子） |

## 关键决策

### D1 · H2/PG 双方言隔离策略
V6 主脚本只用双方言交集语法（JSONB 在 H2 MODE=PostgreSQL 实测原生支持）；PG 专有能力（DO 块、`CREATE UNIQUE INDEX ... WHERE`、表达式索引 `(run_at::date)`、列级 GRANT）全部集中在 `afterMigrate__agent_reader.sql`。该回调不参与 Flyway 版本链（文件名不符合 V*__ 命名），由 PG 真机部署流程手工执行。

### D2 · 幂等性
- `ALTER TABLE ... DROP CONSTRAINT IF EXISTS ... ; ADD CONSTRAINT ...`
- 种子写入前 `DELETE FROM rule_templates WHERE family_id = 1`
- 全部 `CREATE TABLE/INDEX IF NOT EXISTS`

### D3 · 循环依赖处理
rule_templates.parent_version_id ↔ rule_template_versions 先建表后 `ALTER TABLE ADD CONSTRAINT fk_rt_parent_version` 补外键。

### D4 · 查询层走 Specification 而非改 @Query
保留 findWithFilters（V1-V5 的 TaskRepositoryTest 8/8 不受扰）；listTasks 改为 Specification 组合。终审 Critical-1（软删除泄漏）以 `notDeleted()` 作为链条起点修复。

### D5 · agent_reader 密码
字面占位 `placeholder-agent-reader-password`（Flyway placeholder 在 PowerShell/wsl 双层转义复杂度不可接受）；生产清单项。

## 回滚策略

1. **代码回滚**：还原 TaskRepository/TaskServiceImpl/删除 TaskSpecifications（git revert 对应 commit 即可，findWithFilters @Query 未删）。
2. **DDL 正向回滚脚本**（人工执行，按依赖逆序）：
   ```sql
   DROP TABLE IF EXISTS summary_execution_log;
   DROP TABLE IF EXISTS task_triggers;
   DROP TABLE IF EXISTS task_schedules;
   DROP TABLE IF EXISTS rule_template_versions;
   DROP TABLE IF EXISTS rule_templates;
   DROP TABLE IF EXISTS notes;
   DROP TABLE IF EXISTS subtasks;
   ALTER TABLE tasks DROP CONSTRAINT IF EXISTS chk_tasks_status; -- 恢复由 V2 迁移重放或重建 tasks_status_check
   DELETE FROM flyway_schema_history WHERE version = '6';
   DROP ROLE IF EXISTS agent_reader; -- afterMigrate 部分
   ```
3. **数据安全**：7 表均为新增对象，无既有数据迁移；回滚零数据损失（除新表内新增数据本身）。
