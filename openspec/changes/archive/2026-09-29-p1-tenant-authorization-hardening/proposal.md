## Why

当前任务创建、AI 内部会话写入和档案合并仍存在可绕过 `family_id` 的路径，CHILD 历史成员重新受邀还会被静默提升为 ADULT，任务写操作也没有落实既有角色矩阵。这些缺口会造成跨家庭数据修改或角色越权，必须在继续部署前收口。

## What Changes

- 将登录请求中的家庭与创建人身份固定为 `UserContext`，拒绝请求体伪造 `familyId` 或 `createdBy`；无登录上下文的内部调用仍须显式提供二者。
- 为 InternalAgent B-5/B-7 增加必填 `family_id`，按家庭和会话 ID 加锁查询；agent-service 全链路同步传递家庭作用域。
- 将 AI 档案 patch 改为显式 `(familyId, profileId)` 作用域，禁止会话确认阶段合并其他家庭档案。
- 历史成员重新接受邀请时保留原角色，避免 CHILD 被自动提升为 ADULT；首次加入仍默认为 ADULT。
- 落实既有 CHILD 任务矩阵：可创建且默认指派自己，只能更新自己创建或分配给自己的任务，删除一律拒绝。
- 增加跨家庭、身份伪造、角色恢复和 CHILD 任务权限的负向自动化测试。

## Non-goals

- 不修改任务 5 值状态机、数据库 Schema、RLS 或现有软删除策略。
- 不处理 `uk_pp_family_name`、FamilyView 失败态以及 P2 并发/调度问题。
- 不改变 OWNER/ADULT 的既有任务写权限，不在本 change 中部署或提交代码。

## Capabilities

### New Capabilities

- `tenant-authorization`: 约束 HTTP 与内部 AI 调用的家庭作用域、调用者身份和档案合并边界。

### Modified Capabilities

- `daily-task-mgmt`: 落实既有 CHILD 任务权限矩阵，并明确请求体不得覆盖登录身份。
- `family-mgmt`: 历史成员重新接受邀请时保留退出前角色，不再统一重置为 ADULT。

## Impact

- Java 后端：任务服务、InternalAgent 会话端点、档案 patch、拆解确认、邀请恢复逻辑及相关测试。
- Python agent-service：BackendClient 的 B-5/B-7 契约、访谈调用链和测试假件。
- API：B-5/B-7 新增必填 `family_id` query 参数；属于内部跨服务契约的兼容性变更，backend 与 agent-service 必须同批发布。
- 数据流：用户请求的 `family_id` 由登录/家庭上下文输入；模型只生成补丁内容；输出在 backend 以会话所属家庭再次校验后才写入会话或档案。
