## Why

每日计划已完成，但尚无独立的只读 AI 查询入口。现有工作 MCP 含写入工具，不能直接作为个人计划接入。

## What Changes

- 新增本机 stdio MCP 适配器，只读本人已生成的计划和复盘。
- 复用后端实时权限过滤，不接受 user_id、family_id 或任意 URL 参数。
- 采用显式本地启用与环境凭据，无外部连接、无自动上传。
- 提供后续 ChatGPT OAuth 接入及主动通知的分阶段配置说明。

## Capabilities

### New Capabilities
- `personal-daily-mcp`: 本机只读每日记录工具及失败保护。

### Modified Capabilities
无。

## Impact

新增 integrations/daily-mcp，不修改现有业务 API、认证、数据库、部署或任务状态。依赖现有 MCP 1.x SDK。

## Non-goals

本阶段不实现远程 OAuth、不注册 ChatGPT 连接、不发送提醒、不自动生成或修改记录。

## AI 数据流

用户主动调用本机工具 → 适配器使用本地登录凭据读取后端 → 后端权限过滤 → 最小记录返回调用客户端。无模型请求；是否传给外部模型由后续连接授权决定。
