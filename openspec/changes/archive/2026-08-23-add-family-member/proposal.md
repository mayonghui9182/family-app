## Why

`expand-task-model` 为 Task 加了 `userId VARCHAR(50)`（硬编码 default，MVP 不引入账户）；`add-category-mgmt` 加了 `categories` 表并同样用 `user_id = default` 单用户归属。当前存在三大阻塞：
1. **横向越权**：`TaskRepository.findWithFilters` 未按 user_id 过滤，多用户场景返回越界数据（单用户 default 下暂未暴露）；
2. **无角色协作**：老人孩子与成人成员角色（孩子不能删除任务/管理分类，成人可管理）完全缺失，家庭多人协作无法落地；
3. **tasks/categories 归属语义错误**：归属粒度应该是「家庭 family_id」，不是「用户 user_id」——任务和分类是家庭共享资产，个人私有另有 subtask/note 模块承接。

本变更落地 4 张新表（users / families / family_members / invites）、X-Access-Token 自定义鉴权链、注册/登录/家庭 CRUD/邀请全家桶 API，并 BREAKING 改造 tasks/categories：DROP `user_id` 列，改为 `family_id BIGINT NOT NULL` + 外键 + created_by + tasks.assignee_id + 写操作 OWNER/ADULT/CHILD 三维角色矩阵，为后续 `add-frontend-auth-family` 前端 change 提供稳定可用的后端 API。

同时落实 **D8（全中文提示）/ D9（全中文注释）/ D10（4 项老人易用细节）** 三条新增约束：所有 ResultCode.msg / Exception message 中文；新增 Java 文件的 JavaDoc / 注释 / API 注解全部中文；登录精确错误码区分「未注册」与「密码错」、6 位无歧义邀请码、角色中文名称显示、注册 `familyName` 空默认「<昵称>的家」。

## What Changes

- **新增 4 张表**：users（email/phone 至少一个；BCrypt password_hash；access_token + token_updated_at 7 天过期；deleted_at 软删）；families（name/description/avatar/created_by）；family_members（family_id+user_id UNIQUE，role OWNER|ADULT|CHILD CHECK，joined_at/left_at）；invites（code VARCHAR(6) UNIQUE 、max_uses/used_count、expires_at 默认 7 天、status ACTIVE|USED|EXPIRED|CANCELLED）
- **Flyway V4 BREAKING 迁移**：
  1. `DELETE FROM tasks / categories`（存量迁移选项 B，因 VARCHAR→BIGINT 无兼容 UPDATE）
  2. tasks：ADD family_id BIGINT / created_by / assignee_id → DROP user_id → SET family_id NOT NULL → FK families(id) ON DELETE RESTRICT
  3. categories：ADD family_id BIGINT / created_by → DROP user_id → SET family_id NOT NULL → FK families(id) ON DELETE RESTRICT；重建 partial unique index `uk_category_family_name (family_id, name) WHERE deleted_at IS NULL`
  4. 种子：1 演示用户 demo@family.app / familyapp123（BCrypt） + 1 Demo家（id=1） + OWNER 成员 + 4 默认分类（家务/育儿/工作/财务）
- **新增 X-Access-Token 鉴权链**：自定义 `AuthInterceptor implements HandlerInterceptor` + `@ConditionalOnProperty(familyapp.auth.enabled, matchIfMissing=true)` 仅生产注册；`familyapp.auth.enabled=false` 让现有 63 测试零改动通过。token=SecureRandom(32 byte).hex(64)；登出/过期 DB 清 access_token；X-Family-Id Header 被拦截器查 family_members 活跃成员资格后填入 ThreadLocal `UserContext.CurrentUser(userId/email/nickname/familyId/role)`
- **新增 auth package（8 文件 + 5 DTO）**：`POST /api/auth/register`（自动创建 OWNER 家庭，返回 token）、`POST /api/auth/login`（邮箱/手机号 4 种精确中文错误：邮箱未注册 / 手机号未注册 / 邮箱密码错 / 手机号密码错）、`POST /api/auth/logout`、`GET /api/auth/me`（返回家庭列表附带 `roleName` 中文：家庭管理员 / 成人成员 / 孩子成员）
- **新增 family package（8 文件 + 9 DTO）**：`/api/families` CRUD（DELETE 直接返回 501 NOT_IMPLEMENTED「暂未开放」）；leave/transfer-owner/members 改角色 & 踢人；`POST /api/families/{id}/invites` 生成 6 位 20 字母表邀请码（剔除 0O1IL）；`POST /api/invites/accept` 加入家庭；所有路径 familyId 动作独立用 `FamilyService.currentRoleOrThrow(familyId)` 校验（不依赖拦截器的 X-Family-Id Header）
- **tasks 模块 BREAKING 改造**：Entity DROP userId → familyId + createdBy + assigneeId；Repository findWithFilters 追加 AND t.family_id = :familyId（修复横向越权）；Service 空 familyId → 400「请先选择一个家庭后再操作」；assignee → 400「被分配任务的人必须是家庭成员」；角色 WRITE 矩阵：CHILD 只能 UPDATE 自己创建/分配给自己的任务（越权 403「孩子身份只能改自己创建或分配给自己的任务」）、CHILD 不能 DELETE 任务（403「孩子身份不能删除任务，请让家长操作」）、OWNER/ADULT 全权限
- **categories 模块 BREAKING 改造**：Entity DROP userId → familyId + createdBy；Repository findByUserId* 全换 familyId*；名称唯一检查换 existsByFamilyId...；角色矩阵：CHILD 对 CREATE/UPDATE/DELETE/MERGE 全 403「孩子身份不能管理分类（创建/修改/删除/合并）」
- **Task/Category 所有 Controller 强制 `@RequestHeader(value="X-Family-Id", required=true)`**：缺省直接 400 FAMILY_REQUIRED
- **ResultCode 全部 msg 中文**：原 13 条 msg 全中文化；新增 30 条（拆 AUTH_CREDENTIAL_INVALID → 4 个精确错误码）；Exception message 全部中文（老人前端直接显示）
- **唯一新增依赖**：`implementation 'org.springframework.security:spring-security-crypto'`（仅 BCryptPasswordEncoder，不引入过滤器链）

## Capabilities

### New Capabilities

- **auth**：邮箱或手机号注册（含家庭名自动「<昵称>的家」）+ 精确中文错误登录 + 不透明 token 登/出/7 天过期 + 我账户家庭一览
- **family-mgmt**：家庭 CRUD / 成员角色 OWNER|ADULT|CHILD / 转让管理员 / 退出（OWNER 需先转让） / 踢出 / 6 位邀请码生成与接受 / 孩子分类/任务删除保护
- **anti-leak**：tasks/categories 所有查询按 family_id 过滤 + Controller 强制 X-Family-Id + 资源级二次 familyId 对比（403 越权中文）

### Modified Capabilities

- **daily-task-mgmt**：Task 归属从 user_id VARCHAR → family_id BIGINT NOT NULL（BREAKING）；TaskResponse DROP userId，新增 familyId/createdBy/assigneeId；任务新增分配给家庭成员的 assignee 语义；孩子角色受写操作限制
- **category-mgmt**：Category 归属从 user_id VARCHAR → family_id BIGINT NOT NULL（BREAKING）；CategoryResponse DROP userId，新增 familyId/createdBy；名称唯一 partial index 从 (user_id,name) 改为 (family_id,name)

## Non-goals

- 不做家庭 DELETE 解散与资源重归属 → DELETE /api/families/{id} 返回 501「暂未开放」
- 不改前端 UI → 独立 change add-frontend-auth-family / add-frontend-task-model / add-frontend-category
- 不做邮箱/手机号验证码
- 不做头像 OSS/COS 上传（只留 avatar URL 字符串字段）
- 不做密码重置
- 不做审计日志（谁改了 assignee）
- 不引入 Spring Security 整条过滤器链（只依赖 crypto 包）
- 不做 subtask / note / rule-template 等 family_id 改造（各自模块 change 做）
- 不做自定义角色（仅 OWNER/ADULT/CHILD 三固定值）
- 不做邀请二维码渲染端点

## Impact

- **数据库**：Flyway V4 迁移脚本 — 4 新表 + tasks/categories BREAKING 列改造 + seed Demo家
- **后端 Java 新增 ~34 + 修改 ~14 类**：auth + family 两个新包；WebConfig 加拦截器；Task/Category Entity/Repo/Svc/Controller/DTO 全量改造；PasswordEncoder @Bean 在 FamilyApplication；ResultCode（原 13 条 msg 全中文 + 新增 30 条）；GlobalExceptionHandler 加 AuthException/FamilyAccessException 两条 handler
- **新增依赖 1 条**：spring-security-crypto（BOM 版本管理）
- **测试新增/改造 ~133 用例**：Auth(15) / Family(20) / Task 增量(10) / Category 增量(8) / PasswordEncoder(1) / AuthMvc(6) / RolePermissionMvc(8) + 原有 63 个测试零改动通过（@ConditionalOnProperty + familyapp.auth.enabled=false）
- **Docker Smoke 10 步清单**（家庭注册×2 / 邀请 / 接受 / 跨家庭 403 / 孩子 403 / token 过期 401）
- **API 兼容性**：多重 BREAKING
  - Task/Category Entity DROP user_id → 旧 client 再传 userId 无效；TaskResponse DROP userId 列（新增 familyId/createdBy/assigneeId）
  - tasks/categories 所有端点强制 X-Family-Id Header（缺省 400）
  - 未携带 X-Access-Token 访问任何 /api/** 非白名单 → 401（之前无认证 → 200 直接给数据）
