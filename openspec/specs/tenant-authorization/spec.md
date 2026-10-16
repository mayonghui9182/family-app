# tenant-authorization Specification

## Purpose

该能力统一约束登录请求、内部 AI 会话与档案合并的家庭作用域，确保任何写操作都不能凭请求体或裸对象 ID 越过当前家庭边界。

## Requirements

### Requirement: 登录任务写入身份必须由认证上下文决定
当任务写入存在登录上下文时，系统 SHALL 以认证上下文中的 userId、familyId 和 role 为唯一身份来源。请求体中的 familyId 或 createdBy 若与上下文不一致，系统 MUST 拒绝请求且不得写入任务；没有登录上下文的受信内部调用 MUST 同时显式提供 familyId 与 createdBy。

#### Scenario: 登录用户伪造其他家庭创建任务被拒绝
- **WHEN** 家庭 A 的登录用户提交创建任务请求，并在请求体中传入家庭 B 的 familyId
- **THEN** 系统返回 403 ROLE_NO_PERMISSION，家庭 A 与家庭 B 均不新增该任务

#### Scenario: 登录用户伪造其他创建人被拒绝
- **WHEN** 登录用户 U 提交创建任务请求，并在请求体中传入不同于 U 的 createdBy
- **THEN** 系统返回 403 ROLE_NO_PERMISSION，且不存在以被伪造用户为创建人的新任务

#### Scenario: 受信内部调用显式提供身份
- **WHEN** 无登录上下文的内部服务同时提供有效 familyId 与 createdBy 创建任务
- **THEN** 系统按显式身份创建任务；任一字段缺失时快速失败且不写库

### Requirement: AI 会话写入必须按 family_id 作用域查询
InternalAgent B-5 extract-patch 与 B-7 messages 写入 SHALL 要求显式 family_id，并以会话 ID 与 family_id 联合查询 ACTIVE 会话。不存在、非 ACTIVE 或属于其他家庭的会话 MUST 返回相同的 proposal-not-found 错误，防止枚举。

#### Scenario: B-5 拒绝跨家庭补丁写入
- **WHEN** 内部调用方以家庭 B 的 family_id 请求更新属于家庭 A 的 ACTIVE 会话补丁
- **THEN** 系统返回 proposal-not-found，且会话补丁保持不变

#### Scenario: B-7 拒绝跨家庭消息追加
- **WHEN** 内部调用方以家庭 B 的 family_id 请求向属于家庭 A 的 ACTIVE 会话追加消息
- **THEN** 系统返回 proposal-not-found，且消息列表保持不变

#### Scenario: agent-service 全链路传递家庭作用域
- **WHEN** agent-service 处理家庭 A 的档案访谈并调用 B-5 或 B-7
- **THEN** 每个内部请求均携带 family_id=A，后端只修改家庭 A 的会话

### Requirement: 档案补丁必须同时匹配会话家庭与档案 ID
AI 会话确认或仅保存档案时，系统 SHALL 以会话 family_id 与 profile_id 联合查询未删除档案。跨家庭或已删除档案 MUST 按不存在处理，不得修改其 profile_data。

#### Scenario: 恶意补丁引用其他家庭档案
- **WHEN** 家庭 A 的会话补丁包含家庭 B 的 profile_id 并被确认
- **THEN** 家庭 B 的档案保持不变，该 profile_id 以 NOT_FOUND 记入 discarded_keys

#### Scenario: 同家庭档案补丁正常合并
- **WHEN** 家庭 A 的会话补丁引用家庭 A 的有效档案并被确认
- **THEN** 合法字段按 schema 合并，响应包含该档案 ID
