## Context

See proposal.md - Why. 当前系统已经有 FamilyContextInterceptor、带 family_id 的 Repository 查询和 B-6 scoped lock 作为正确先例，但任务创建仍允许 DTO 身份优先，B-5/B-7 仍按裸 session id 写入，档案 patch 仍按裸 profile id 写入。工作区存在其他未提交改动，实施必须以小补丁保留现状。

## Goals / Non-Goals

**Goals:**

- 在 HTTP、内部服务和 AI 合并三条写链上形成可测试的家庭边界。
- 落实现有主规格中的 CHILD 任务矩阵，并保持 OWNER/ADULT 行为不变。
- 让 backend 与 agent-service 的内部契约显式、可同时发布、失败时不泄漏对象是否存在。

**Non-Goals:**

- 不引入 RLS、新表、迁移或新的角色枚举。
- 不改变任务 DFA、档案 schema 白名单和邀请消费并发模型。
- 不处理本 change 之外的 P1/P2 项。

## Decisions

### D1：区分“有登录上下文”与“无上下文内部调用”

TaskService 在能读取 UserContext 时只信任其中的 familyId/userId/role；DTO 同名字段仅用于一致性校验。无 UserContext 时，内部调用必须显式给出 familyId 与 createdBy。相比删除 DTO 字段，此方案保持 InternalAgent 与既有单元测试的服务层调用能力，同时堵住公共 HTTP 请求体伪造。

### D2：B-5/B-7 对齐 B-6 的 scoped lock

两个端点新增必填 family_id query 参数，并使用 session id + family id 的加锁查询。跨家庭、缺失和非 ACTIVE 统一返回 proposal-not-found。agent-service 的 BackendClient 和访谈调用点同时传递现有 family_id；不从 session id 反查后再信任结果，避免“先裸查再比较”的枚举窗口和误写风险。

### D3：档案 patch 接口显式接受 familyId

PersonProfileService.applyProfilePatch 改为接收 familyId 与 profileId，并只调用带 familyId、deletedAt 条件的仓储查询。DecomposeSessionService 从已 scoped 锁定的会话传入 familyId；它不再预先裸查 profile。相比依赖 ThreadLocal，此方案适用于内部确认流程和单元测试，也把授权参数写进方法签名。

### D4：CHILD 任务策略复用现有 UserContext 角色

CHILD 创建任务时，未指定执行人则自动填入本人 userId 与本人有效档案；指定他人则拒绝。更新前先按 familyId 取任务，再校验 createdBy、assigneeId 或 assigneeProfileId 是否属于当前 CHILD，并禁止改派他人；删除始终 40302。OWNER/ADULT 路径不增加限制。

### D5：重邀恢复历史角色

历史 membership 仅清除 leftAt 并刷新 joinedAt，不再覆盖 role；首次加入仍建 ADULT。角色变更只能继续通过 OWNER 管理接口完成，邀请本身不承担提权能力。

## Risks / Trade-offs

- [内部 API 为兼容性变更] → backend 与 agent-service 必须同批发布，并以两侧契约测试钉住 family_id。
- [UserContext 缺失可能来自测试或内部调用] → 只允许显式 familyId + createdBy 的受信路径，缺一即失败。
- [CHILD 的 assigneeProfileId 兼容双字段] → 同时检查 userId 和当前家庭本人档案，覆盖旧/新字段。
- [工作区已有重叠改动] → 只做最小上下文补丁，验证目标文件 diff，不重写整文件。

## Migration Plan

1. 先部署包含 scoped 后端端点与任务/档案守卫的 backend。
2. 同一发布批次部署会传 family_id 的 agent-service，避免旧客户端收到 400。
3. 运行跨家庭负向 smoke 后再开放流量。
4. 回滚时 backend 与 agent-service 一并回滚到上一兼容版本；本 change 无数据库回滚步骤。
