# Delta Spec: family-mgmt (Change 11)

> 相对于 openspec/specs/family-mgmt/spec.md 的增量变更：认证域新增微信 openid 绑定与免密登录。现有 opaque token 体系、错误码体系全部沿用。

## ADDED Requirements

### Requirement: V10 迁移新增 wechat_bind 表（openid ↔ user_id 绑定关系）

系统 SHALL 通过 Flyway V10 迁移创建 `wechat_bind` 表：BIGSERIAL id、openid VARCHAR(64) NOT NULL、user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE、bound_at TIMESTAMPTZ NOT NULL DEFAULT now()；SHALL 建 UNIQUE(openid) 部分唯一索引（WHERE 全量）与 idx_wechat_bind_user(user_id)。迁移 SHALL 提供逆向回滚脚本（DROP TABLE）。删除 user SHALL 级联删除其绑定行。

#### Scenario: V10 正向迁移
- **WHEN** Flyway 从 V9 迁移到 V10（H2 测试库与 PostgreSQL 16 真机）
- **THEN** wechat_bind 表、UNIQUE(openid)、idx_wechat_bind_user 索引存在，既有表数据不受影响

#### Scenario: V10 逆向回滚
- **WHEN** 执行 V10 对应的回滚（DROP TABLE wechat_bind）
- **THEN** 表被删除，其余 schema 与回滚前一致，应用其他功能不受影响

### Requirement: 微信免密登录 SHALL 签发与现有体系一致的登录态

backend SHALL 提供内部端点：入参 openid（由 wechat-service 经 OAuth code 换得后传递），返回该 openid 绑定的 user 及其登录态（沿用现有 opaque access_token 生成/7 天过期规范）与默认家庭上下文；openid 未绑定时 SHALL 返回明确的「未绑定」业务码而非错误，由调用方分流到绑定页。签发的 token SHALL 与密码登录 token 同权（可访问全部已授权接口）。

#### Scenario: 已绑定 openid 免密换取 token
- **WHEN** wechat-service 携带 X-Internal-Secret 以 openid=O 调用内部端点，O 已绑定 user=U
- **THEN** 返回 U 的登录态（access_token + 用户信息 + roleName 中文），该 token 可直接调用 /api/tasks 等接口

#### Scenario: 未绑定 openid 返回业务码
- **WHEN** 以未绑定的 openid 调用该端点
- **THEN** 返回「未绑定」业务码（非 500），不创建任何用户或绑定数据

### Requirement: 绑定端点 SHALL 校验账号凭证后建立绑定

backend SHALL 提供绑定端点（内部，供绑定页流转调用）：入参一次性中间票据（关联未绑定 openid，有效期 ≤10 分钟、单次有效）+ 已有账号邮箱/手机号与密码（或注册新账号参数）；凭证校验通过（复用现有 login 校验与错误码）SHALL 写入 wechat_bind 并返回登录态；凭证错误 SHALL 沿用现有中文错误码且不写绑定。同一 openid 重复绑定 SHALL 幂等（返回成功，不产生重复行）。

#### Scenario: 凭证正确完成绑定
- **WHEN** 持有效中间票据提交邮箱与正确密码
- **THEN** wechat_bind 新增 openid→user 行，响应含登录态，前端 302 进 H5

#### Scenario: 凭证错误不写绑定
- **WHEN** 提交的密码错误（现有 40113 场景）
- **THEN** 返回 40113 中文提示，绑定表无新增行，中间票据未消费（可重试）

#### Scenario: 中间票据过期或重复使用
- **WHEN** 绑定请求携带过期或已消费的中间票据
- **THEN** 返回票据失效业务码，引导用户从公众号菜单重新进入
