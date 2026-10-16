# Tasks: `add-family-member`
严格对齐 `docs/superpowers/plans/2026-08-23-add-family-member.md` 10 Task 编号。

| ID | 任务标题 | 主要产物 | 验收方式 |
|---|---|---|---|
| T0 | 环境准备：依赖 + 配置 + WebConfig 基础 | build.gradle 1 新依赖；application*.properties 3 项；WebConfig 类级条件注解 + PasswordEncoder Bean | compileJava；PasswordEncoderTest 1 green |
| T1 | Flyway V4 迁移：4 新表 + tasks/categories BREAKING + 种子 | db/migration/V4__*.sql（单文件）；PasswordEncoderTest 生成 BCrypt SQL；PostgreSQL 跑一遍 | flywayMigrate exit 0 + psql \d 列验证 |
| T2 | 领域模型：5 Entity + 5 Repository | 4 Entity 新增 + 改造 Task/Category；4 Repo 新增 + 改造 + findWithFilters 加 family_id | RepositoryTest 63 旧 + 3 新 green |
| T3 | ResultCode 中文 + 异常类 + handler + DTO 17 个 | 原 13 msg 中文 + 新增 30 条；2 Exception 类 + 2 handler；Auth(5)+Family(9)+TaskResponse(3 new fields)+CategoryResponse(2 new fields) | 编译通过；所有新 DTO 字段 match spec |
| T4 | Auth 实现：Service + Controller | 4 登录精确错误；register 自动建家 + 家庭名默认；Me 附 roleName；Logout 清 token | AuthServiceTest 15 + AuthMvcTest 6 green |
| T5 | Family & Member 实现：8 Service + 9 Controller | transfer-owner 限制；leave OWNER 先转让；currentRoleOrThrow 独立方法 | FamilyServiceTest 20 green；curl 三踢法全 403 中文 |
| T6 | Invite 实现 + 6 字母码算法 | 20 字母表（去 0O1IL）；接受原子 consume；5 状态精确码 | InviteServiceTest 18 green；并发 40060 |
| T7 | Tasks 改造：Entity/Repo/Svc/Ctrl/DTO | user_id→family_id；双保险 Header 对比；CHILD 写矩阵 3 条；assignee 成员校验 | TaskRolePerm 10+8 green |
| T8 | Categories 改造：Entity/Repo/Svc/Ctrl/DTO；重建 unique index；CHILD 矩阵 4 条 403 | findByUserId→findByFamilyId；unique family_id；MERGE 事务；CHILD CUD/MERGE 403 中文 | CategoryRolePerm 8 green |
| T9 | 补齐测试 + Docker smoke | 63 旧 + 68 新增 + 1 PasswordEncoder = 132 green；Docker 10 步 curl | ./gradlew test SUCCESS；smoke 10/10 OK=0 |
| T10 | Spec 自检 + openspec validate + 提交归档 | Self-review 3 项；validate --strict exit 0 或人工 self-review checklist 全 PASS；git commit 引用 change id | validate or checklist；commit message [change: add-family-member] |

依赖图：T0 → (T1 | T3) → T2 → (T4 | T5 | T6 | T7 | T8) → T9 → T10
执行方式：推荐 subagent-driven（T0~T3 串行后 T4~T8 并行）。详见 plan.md Parallelism Guidance。
