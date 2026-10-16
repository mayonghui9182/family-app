# Check List: v6-flyway-datamodel

> 验证时间：2026-08-25 会话（PG 真机 = docker postgres:16-alpine，H2 = CI 等价）

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 1 | H2 全量测试 `./gradlew test --rerun-tasks`（101 tests） | ✅ PASS | 本会话强制重跑 BUILD SUCCESSFUL（5 tasks executed） |
| 2 | PG 真机 `./gradlew flywayMigrate` 正向迁移 V1→V6 | ✅ PASS | flyway_schema_history 6 行全部 success=t |
| 3 | 种子数据 3 条模板（decomposition×1 + routine×2） | ✅ PASS | 周末大扫除 / 上学日检查书包 / 月度记账，is_active=t |
| 4 | agent_reader 权限矩阵（敏感列拒绝/白名单允许） | ✅ PASS | users.password_hash=f / users.email=t / invites=f / subtasks=t / tasks.deleted_at=f |
| 5 | uk_rt_family_name_active 已升级 partial unique | ✅ PASS | pg_indexes.indexdef 含 `WHERE (is_active = true)` |
| 6 | uk_sel_family_run_date 表达式唯一索引 | ✅ PASS | `(family_id, sel_run_date_utc(run_at))` |
| 7 | 分布式锁行为：同家庭同日第二次 Insert 冲突 | ✅ PASS | duplicate key violation 实测，ROLLBACK 干净 |
| 8 | ❌1 过期 DRAFT 过滤 + 软删不泄漏 | ✅ PASS | V6DraftFilterTest 5/5（含 list_excludes_soft_deleted_tasks） |
| 9 | 人工 9 项清单（family_id FK / CHECK / FK 顺序 / 纵深约束 / PG 专有块隔离） | ✅ PASS | V6.sql + afterMigrate 复核；V6 与回调职责分离正确 |
| 10 | 回滚策略可执行 | ✅ PASS | design.md「回滚策略」三段式（代码 revert / DDL 逆序 DROP / history 清理） |

## 本次验证发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| FIX-A | `./gradlew flywayMigrate` 报 No database found to handle jdbc:postgresql | Flyway Gradle 插件独立类加载器缺 PG 方言包，`implementation` 依赖不可见 | build.gradle 增加 buildscript classpath `flyway-database-postgresql:10.15.0` |
| FIX-B | afterMigrate 第 92 行 SQLSTATE 42P17 functions in index expression must be marked IMMUTABLE | `timestamptz::date` 是 STABLE（依赖会话时区），PG 禁止进索引表达式 | 新增 IMMUTABLE 函数 `sel_run_date_utc(t)` 固定按 UTC 取日，索引改为 `(family_id, sel_run_date_utc(run_at))` |
