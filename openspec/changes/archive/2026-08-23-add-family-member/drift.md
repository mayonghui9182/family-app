# Drift File: `add-family-member`
本 change 不产生 drift（family-mgmt / auth 子能力 / daily-task-mgmt 增量 / category-mgmt 增量 均已被本 changes 覆盖）。以下按 Proposal "What Changes" 对齐。

| Item | Proposal/Design/Spec 位置 | 实施后实现路径（由 executor 填） |
|---|---|---|
| users/families/family_members/invites 4 表 DDL | proposal.md §What Changes / spec.md §1.1-1.3 | backend/src/main/resources/db/migration/V4__family_auth.sql |
| tasks DROP user_id → family_id + created_by + assignee_id | proposal.md V4 迁移 / spec.md §1.2 | 同上 |
| categories DROP user_id → family_id + created_by + uk_category_family_name | proposal.md V4 / spec.md §1.2, §6.2 | 同上 |
| 种子 demo@family.app + 4 默认分类 | spec.md §1.3 | 同上 |
| spring-security-crypto 依赖 + BCryptPasswordEncoder Bean | proposal.md Impact + spec.md §8.2 | backend/build.gradle + FamilyAppApplication.java |
| AuthInterceptor + @ConditionalOnProperty + UserContext ThreadLocal | spec.md §2.2, §2.3 | auth/AuthInterceptor.java + auth/UserContext.java + config/WebConfig.java |
| Register（四校验 + 家庭名默认“昵称的家”） | spec.md §2.4 | auth/AuthController.java + dto/* |
| Login 4 精确错误码（D10.1） | spec.md §2.4 表 | auth/AuthService.java 分支 |
| Me + roleName 中文（D10.3） | spec.md §3.1 Role 表 | MeResponse.roleName |
| Logout 清 token 两列 | spec.md §2.1 | AuthService.logout() |
| Family 9 端点；DELETE=501 | spec.md §3.2 | family/FamilyController.java |
| FamilyService.currentRoleOrThrow(familyId) 独立校验 | spec.md §3.3 | family/FamilyService.java 方法 |
| 邀请码 6 位 20 字母（D10.2 去 0O1IL） | spec.md §4.1 + D10.2 | family/InviteCodeGenerator.java |
| Invites CRUD + accept 原子 consume + 5 状态码 | spec.md §4.2, §4.3 | family/InviteService.java + Controller |
| tasks 过滤 family_id（越权修复） | spec.md §5.2 | task/TaskRepository.findWithFilters + findByIdAndFamilyId |
| tasks Controller 强 X-Family-Id + 双保险对比 | spec.md §5.3 | task/TaskController.java 所有签名 |
| tasks CHILD 写矩阵 3 条 + assignee 校验 | spec.md §5.4, §5.5 | task/TaskService.java 三处 |
| categories findByUserId→findByFamilyId + 名称唯一 family_id | spec.md §6.2 | category/CategoryRepository + Service |
| categories CHILD CUD/MERGE 全 403 中文 | spec.md §6.4 | category/CategoryService.java 四处 |
| categories MERGE 保留事务 | spec.md §6.5 | CategoryService 类级 @Transactional |
| ResultCode 原 13 msg 全中文 + 新增 30 | spec.md §7.2 | common/ResultCode.java |
| Exception 2 条 + handler 2 条 | spec.md §7.1 | auth/AuthException + family/FamilyAccessException + GlobalExceptionHandler.java |
| application-test.properties bypass auth=false | spec.md §8.1 | src/test/resources/application-test.yml |
| 合计 132 green | tasks.md T9 | ./gradlew test |
| Docker Smoke 10 步 | design.md §4.4 | scripts/smoke-add-family-member.sh |
| D9 新增注释/JavaDoc/注解中文 100% | proposal.md §What Changes 尾部 | grep TODO 新增文件=0 |

参考 diff 数量：新增 Java 34 ±2；修改 Java 14 ±1；SQL 1；properties 3；gradle 1 行。
