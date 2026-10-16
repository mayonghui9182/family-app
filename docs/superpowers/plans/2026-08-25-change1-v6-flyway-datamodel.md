# Change 1: V6 Flyway 数据模型迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 落地 PostgreSQL V6 迁移脚本：7 张新表（全 family_id 多租户隔离）+ agent_reader 只读角色 + tasks.status CHECK 防御纵深 + TaskRepository DRAFT 过期自动隐藏 predicate + 3 条家庭演示模板种子数据。

**Architecture:** 沿用 V1-V5 Flyway 命名规范（BIGSERIAL PK / BIGINT FK / TIMESTAMPTZ / chk_* / idx_* / uk_*）。数据脚本 PostgreSQL DO 块包裹角色创建（H2 自动忽略该块保证 CI 过）；Java 代码层 Repository Specification 追加 DRAFT 默认过滤谓词。

**Tech Stack:** Java 17 / Spring Boot 3 / Spring Data JPA Specification / Flyway 10+ / PostgreSQL 16 / H2 (CI)

## Global Constraints
- 所有表列索引约束 100% 对齐 B4 规格 §2
- 所有新表 100% 含 family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE
- 任务状态 5 值固定：DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED
- 错误消息/注释全中文
- PostgreSQL 专有语法用 DO 条件块包裹（H2 跳过）
- ❌1 补项：TaskRepository 默认 WHERE (status != 'DRAFT') OR (status = 'DRAFT' AND expires_at > CURRENT_TIMESTAMP)

---

## File Structure
| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/resources/db/migration/V6__Add_Agent_AI_Task_Decomposition.sql | Create | V6 主迁移（7表+索引+CHECK+agent_reader+种子） |
| backend/src/main/java/com/familyapp/task/TaskRepository.java | Modify | 追加 withValidDraftPredicate 静态工具 |
| backend/src/main/java/com/familyapp/task/TaskService.java | Modify | listTasks 入口默认拼接 Specification |
| backend/src/test/java/com/familyapp/task/V6DraftFilterTest.java | Create | ❌1 专项测试（3 用例） |
| backend/src/test/java/com/familyapp/V6MigrationTest.java | Create | H2 迁移验证（7表存在+3种子） |
| backend/src/test/resources/application-test.yml | Modify | H2 兼容模式（必要时） |

---

### Task 1: V6 迁移脚本主骨架（7 表 DDL + 种子数据）

- [ ] **Step 1: 写 V6MigrationTest 失败测试（2 用例）**
  断言 7 表存在 + family_id=1 种子 3 条模板；Run ./gradlew test --tests V6MigrationTest Expected: FAIL

- [ ] **Step 2: 写 V6.sql 主骨架（逐表对齐规格 §2.2）**
  (1) subtasks：7 字段 + chk_subtasks_status 5 状态 + is_ai_generated BOOLEAN；索引 3 条
  (2) notes：多态 target_type ENUM(task/subtask) + target_id；索引 2 条
  (3) rule_templates：type ENUM(decomposition/routine) + conditions JSONB + content JSONB + uk_rt_family_name_active 部分唯一
  (4) rule_template_versions：content 快照 + is_important；uk_rtv_template_version；建表后 ALTER TABLE 补 rule_templates.parent_version_id FK（循环依赖顺序）
  (5) task_schedules：trigger_date DATE + target_template JSONB + is_executed；idx_ts_family_date_executed
  (6) task_triggers：source_task_id FK + trigger_type ENUM(on_complete)；idx_tt_source_active
  (7) summary_execution_log：status/trigger_source ENUM；**uk_sel_family_run_date(family_id, (run_at::date))** 分布式锁键

- [ ] **Step 3: 追加 tasks.status 纵深 CHECK + 3 条种子模板**
  ALTER TABLE tasks ADD CONSTRAINT chk_tasks_status CHECK (status IN(5值))；插入 3 条 rule_templates（周末大扫除 decomposition / 上学日检查书包 routine weekly / 月度记账 routine monthly）

- [ ] **Step 4: PostgreSQL DO 块 agent_reader 列级授权（H2 忽略）**
  角色创建（IF NOT EXISTS）+ 列级授权（tasks/categories/families/users 排除 deleted_at/password_hash/access_token/invites）+ 7 新表全列 + 序列权限

- [ ] **Step 5: 运行 V6MigrationTest Expected: PASS**
- [ ] **Step 6: Commit feat(change1): V6 7 新表 DDL + 种子**

---

### Task 2: ❌1 补项 DRAFT 默认过期过滤（3 专项测试）

- [ ] **Step 1: 写失败测试 V6DraftFilterTest 3 用例**
  用例1：过期 DRAFT(2小时前) + 正常 PENDING → 返回 size=1 无过期
  用例2：未过期 DRAFT(12小时后) → 正常返回
  用例3：显式 ?status=DRAFT 混合 → 仅未过期

- [ ] **Step 2: Run 测试 Expected: FAIL**

- [ ] **Step 3: TaskRepository 追加静态工具 withValidDraftPredicate()**
  Specification<Task> = cb.or(notEqual(status,DRAFT), and(equal(status,DRAFT), greaterThan(expiresAt, NOW)))
  TaskRepository 接口继承 TaskSpecifications

- [ ] **Step 4: TaskService.listTasks 默认拼接**
  finalSpec = Specification.where(validDraft).and(userSpec)

- [ ] **Step 5: Run V6DraftFilterTest Expected: PASS 3/3**
- [ ] **Step 6: 全量回归 ./gradlew check test integrationTest Expected: SUCCESS**
- [ ] **Step 7: Commit feat(change1): ❌1 DRAFT 默认过期过滤 + 3 测试**

---

### Task 3: H2/PostgreSQL 双环境 CI 兜底

- [ ] **Step 1: application-test.yml 检查**  jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE

- [ ] **Step 2: 最终验收 3 条命令**
  ① PostgreSQL 真机：./gradlew flywayClean flywayMigrate → SUCCESS
  ② H2 CI：./gradlew check test integrationTest → BUILD SUCCESSFUL
  ③ 人工 9 项清单：全表 family_id FK / 所有 CHECK / 部分唯一 / 分布式锁键 / FK 顺序 / tasks 纵深 / ❌1 测试 / PG 专有块隔离 / 种子 3 条

- [ ] **Step 3: 如有变动 Commit 修正 application-test.yml**

---

## Self-Review
- [x] Spec 覆盖：§2 + ❌1 + 种子；无遗漏
- [x] 无占位符：所有步骤命令具体可执行
- [x] 类型一致：withValidDraftPredicate() 名称全程统一
- [x] 测试：每个交付物失败-通过循环；❌1 覆盖 3 边界

---

## 下一步
启动 OpenSpec：创建 openspec/changes/2026-08-25-v6-flyway-datamodel/（proposal/delta-spec/design/tasks/check_list），按 Task 1→2→3 实施。

（Change 1 Plan 结束）
