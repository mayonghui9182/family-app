## Why

当前公网网关会把 `/api/internal/**` 和未鉴权的 task-planner 写接口直接转发，且内部调用密钥与 task-planner 数据库口令存在可用默认值。攻击者一旦获得默认密钥、openid 或直接调用工作任务接口，可能造成账号接管与未授权数据写入，因此必须在继续功能开发前收紧服务边界。

## What Changes

- **BREAKING**：公网 Nginx 不再代理 `/api/internal/**`，该前缀只允许 Compose 内部服务通过 backend 内网地址访问。
- **BREAKING**：`INTERNAL_API_SECRET` 与 `TP_POSTGRES_PASSWORD` 不再提供可运行默认值；生产 Compose 缺失变量时立即拒绝解析或启动。
- agent-service 与 wechat-service 对空内部密钥执行启动期配置校验，避免以拒绝所有请求的半可用状态运行。
- `/api/work-tasks/**` 在 Nginx 层复用主应用登录态校验，只有有效 `X-Access-Token` 的用户才能访问 task-planner 的聊天与 REST 接口。
- 增加网关、配置和回归测试，钉住公网拒绝、登录态守卫及缺失密钥失败行为。

## 非目标 (Non-goals)

- 本变更不修复业务层 `family_id` 越权、CHILD 角色权限、软删除索引等 P1 问题。
- 本变更不重构 task-planner 为主库多租户模型，也不新增用户级 ACL；它只关闭当前公网匿名写入口。
- 本变更不改变五值任务状态机，不改微信 OAuth、AI 拆解或任务规划业务协议。
- 本变更不处理 SMTP、真实 LLM、微信测试号等外部验收事项。

## Capabilities

### New Capabilities

- `service-boundary-security`: 规定内部 API、内部共享密钥、task-planner 公网访问与部署凭据的安全边界。

### Modified Capabilities

无。

## Impact

- 网关：`nginx/default.conf`。
- 主部署：`docker-compose.yml`、`.env.example`。
- 主后端：`backend/src/main/resources/application.yml` 及相关配置测试。
- Python 服务：`agent-service/app/settings.py`、`wechat-service/app/settings.py` 及测试夹具。
- task-planner：公网入口行为由匿名访问改为复用主应用登录态；服务内部 MCP 调用路径保持不变。
- 运维：部署方必须在启动前提供强随机 `INTERNAL_API_SECRET` 与 `TP_POSTGRES_PASSWORD`。
