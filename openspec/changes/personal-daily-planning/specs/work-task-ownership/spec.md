## Purpose

工作数据属于个人私人空间，身份与关联数据归属必须贯穿网页、服务及工具调用，防止家庭成员关系或已登录状态被误用为访问其他用户工作信息的授权。

## ADDED Requirements

### Requirement: Work data ownership
系统 SHALL 从可信认证确定用户；任务、分类、子待办、便签、模板、版本和总结日志的全部读写 MUST 按用户隔离，跨用户引用拒绝。

#### Scenario: Another signed-in user
- **WHEN** 用户 B 查询或更新用户 A 的数据，包括 REST 和 MCP
- **THEN** 列表不包含 A 的数据，按 ID 访问返回不存在

#### Scenario: Forged identity
- **WHEN** 请求伪造用户头或没有有效凭据
- **THEN** 在业务访问前拒绝请求

### Requirement: Legacy ownership is explicit
系统 SHALL 保留未认领旧数据，MUST NOT 自动归属首个登录用户，认领必须指定账号并保持关联一致。

#### Scenario: New account
- **WHEN** 新账号首次进入工作区
- **THEN** 不得读取旧 default 数据，获得本人独立未分类入口

### Requirement: Background processing is scoped
AI 和定时总结 SHALL 在明确用户范围执行，不得无身份扫描或修改所有人的任务。

#### Scenario: Scheduled processing
- **WHEN** 执行多个用户的自动总结
- **THEN** 每个执行使用独立用户上下文和幂等记录
