## Why

用户确认 Cloudflare 穿透、HTTPS 可访问、邮箱密码可登录。现有本机 MCP 已验收，但远程读取需要个人 OAuth 授权，不能直接暴露应用登录令牌。

## What Changes

- 独立远程只读 MCP 资源服务，标准 OAuth 发现与每次请求内省；禁止 app token 和客户端指定身份。
- 新增默认关闭的后端内部每日读取入口，核验有效账号并复用实时权限过滤。
- 独立 Spring Authorization Server，复用后端邮箱密码认证，PKCE、同意、撤销、持久化授权及独立密钥。
- 配置 Cloudflare HTTPS 对外地址和可审查的网关/Compose 覆盖层，默认关闭，先隔离验证。

## Capabilities

### New Capabilities
- `personal-daily-remote-oauth`: 本人每日记录的远程 OAuth 只读访问。

### Modified Capabilities
无。

## Impact

2026-10-12 用户确认采用独立发布候选：仅每日计划、必要工作隔离及远程只读接入，排除尚未上线的微信会话改造。保留现有工作树，在独立目录装配可重建候选；不因准备候选而授权生产部署。

integrations/daily-mcp、独立授权服务、后端内部 Controller、可选网关与编排。授权存储单独规划迁移与回滚，不改变任务五值状态或家庭隔离。

## Non-goals

不部署公网、不注册真实 ChatGPT 连接、不读取或外发真实私人数据、不添加任务写入工具、不实现主动提醒。

## AI 数据流

本人在浏览器登录同意 → OAuth token → MCP 内省校验 → 内部后端按可信用户查询 → 最小记录返回。测试用虚构账号，模型不参与本轮。
