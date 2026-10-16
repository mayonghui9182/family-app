# Check List: `add-family-member`

## Proposal Review
- [x] Why 明确三大阻塞（横向越权 / 无角色协作 / 归属语义错误）
- [x] What Changes 覆盖：4 表 / V4 BREAKING 迁移 / X-Access-Token / auth+family 包 / tasks+categories 改造 / Header 强制 / 中文 ResultCode / spring-security-crypto
- [x] New/Modified Capabilities：auth/family-mgmt/anti-leak 新增；daily-task-mgmt+category-mgmt 增量
- [x] Non-goals 10 条明确（解散/验证码/头像上传/密码重置/审计日志/SS 全迁移/二维码/其他子模块 family_id/自定义角色）
- [x] Impact：数据库 / ~34+14 文件 / 1 依赖 / 133 测试 / Docker Smoke 10 / 3 BREAKING 列

## Spec Review (9 节全勾)
- [x] 1. Persistence：4 表 + tasks/categories 改造 + 种子 5 类 / partial index / FK 级联全齐
- [x] 2. Auth：opaque 64 hex / 7 天 / 单 token / Header 协议 / @ConditionalOnProperty / ThreadLocal / 注册 4 校验 / Login 4 精确错误
- [x] 3. Family Lifecycle：3 角色中文；9 端点；leave/transfer 约束；currentRoleOrThrow 独立校验
- [x] 4. Invite：6 位 20 字母；4 端点 CRUD；accept 原子 consume；5 状态码精确中文
- [x] 5. Tasks 增量：字段变更 + Repo findWithFilters + 双保险 familyId 对比 + CHILD 矩阵(3) + assignee 校验
- [x] 6. Categories 增量：字段变更 + findByFamilyId + 名称唯一条件 + CHILD 矩阵(4 CUD/MERGE 全 403) + MERGE 事务保留
- [x] 7. Error Model：13 msg 中文保留原值；新增 30；Exception 2 + handler 2
- [x] 8. Observability/Security：测试 bypass；BCrypt cost=10 + 种子 hash；三层越权双保险 (Repo/Controller/Service)
- [x] 9. D8~D10 老人易用 6 条清单（D8 msg 中文 / D9 注释中文 / D10.1 4 登录码 / D10.2 6 位 20 字母 / D10.3 roleName / D10.4 家庭名默认）

## Tasks Review
- [x] T0-T10 覆盖全设计
- [x] 依赖图正确
- [x] 每任务指定验证方式

## Implementation Checklist (T9 完成后再勾)
- [x] (T9) 63 旧测试 green（familyapp.auth.enabled=false bypass）
- [x] (T9) 新 68 单测 + 1 PasswordEncoderTest green
- [x] (T9) 合计 132 green
- [x] (T9) Docker Smoke 10/10 步返回 code=0
- [x] (D9) grep 新增 34 个 Java 文件 TODO=0；grep 注释/JavaDoc/注解含中文数 >0
- [x] (D8) grep 新增异常 msg 不含英文
- [x] (T10) openspec validate --strict exit 0 或人工 Self-Review 3 项全勾
