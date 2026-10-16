## Purpose

为 family-app 的公网网关、内部服务调用与部署凭据定义统一安全边界，确保机器接口和工作任务写入口不会因默认密钥或匿名代理而暴露。

## ADDED Requirements

### Requirement: 公网不得访问内部 API
系统 MUST 在公网网关拒绝所有 `/api/internal` 与 `/api/internal/**` 请求，并且不得将请求转发给 backend。

#### Scenario: 携带正确内部密钥访问公网内部端点
- **WHEN** 外部客户端通过 Nginx 请求 `/api/internal/wechat/login`，即使携带正确的 `X-Internal-Secret`
- **THEN** 网关返回 404，且 backend 不收到该请求

#### Scenario: 内部服务通过 Compose DNS 调用
- **WHEN** agent-service 或 wechat-service 使用 backend 内网地址请求 `/api/internal/**` 并携带正确内部密钥
- **THEN** 请求继续由 backend 的内部密钥拦截器按既有契约处理

### Requirement: 内部共享密钥必须显式配置
参与内部 API 调用的服务 MUST 要求非空 `INTERNAL_API_SECRET`，不得提供可用于运行的默认密钥。

#### Scenario: 生产 Compose 缺少内部密钥
- **WHEN** 部署环境未设置 `INTERNAL_API_SECRET`
- **THEN** Compose 配置解析或服务启动失败，并指出缺失的变量

#### Scenario: Python 服务收到空内部密钥
- **WHEN** agent-service 或 wechat-service 以空白 `INTERNAL_API_SECRET` 创建运行配置
- **THEN** 配置校验失败，服务不得进入可接受请求的状态

### Requirement: 工作任务公网入口必须复用主应用登录态
系统 MUST 在代理 `/api/work-tasks/**` 前验证主应用 `X-Access-Token`，未通过验证时不得调用 task-planner 上游。

#### Scenario: 匿名调用工作任务接口
- **WHEN** 客户端未携带 `X-Access-Token` 请求工作任务聊天或 REST 接口
- **THEN** 网关返回 401，且请求不转发到 task-planner

#### Scenario: 无效 token 调用工作任务接口
- **WHEN** 客户端携带格式错误、过期或不存在的 `X-Access-Token`
- **THEN** 网关返回 401，且请求不转发到 task-planner

#### Scenario: 已登录用户调用工作任务接口
- **WHEN** 客户端携带有效 `X-Access-Token` 请求 `/api/work-tasks/**`
- **THEN** 网关允许请求进入对应 task-planner 上游，并保留原业务响应与 SSE 行为

### Requirement: task-planner 数据库口令必须显式配置
主 Compose 部署 MUST 要求非空 `TP_POSTGRES_PASSWORD`，不得以固定默认口令启动 task-planner 数据库和服务。

#### Scenario: 缺少 task-planner 数据库口令
- **WHEN** 主部署环境未设置 `TP_POSTGRES_PASSWORD`
- **THEN** Compose 配置解析失败，并指出缺失的变量
