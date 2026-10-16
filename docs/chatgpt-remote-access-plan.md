# app.mayh.net.cn 远程只读接入方案

2026-10-11 更新：用户后来确认 Cloudflare 公网 HTTPS 可打开、邮箱密码可登录。远程 OAuth/MCP 的代码及本机隔离验收已推进，最新实现和发布边界见 [运行说明](daily-oauth-runbook.md)。以下保留早期条件及规划记录，不作为当前上线状态。

## 已确认条件

2026-10-10 用户确认：域名 app.mayh.net.cn 指向本机、可远程访问；当前使用 HTTP，没有 OAuth 服务，已有微信小程序对接。尚未实测公网协议、证书或路由；不把仓库可选 TLS 文件视为线上 HTTPS 已启用。

仓库已有邮箱/密码登录 /api/auth/login 和个人账号体系；微信小程序对接是否提供同一账号的浏览器登录需单独核验，不假定其为 OAuth 授权服务器。当前 stdio MCP 已通过模拟数据验证，不可直接作为远程 HTTP 服务。

## 推荐实施顺序

1. HTTPS 入口：复用现有域名，部署有效证书与自动续期，验证外网证书链及到本机网关的路由。优先保持已有微信入口行为，先验证后再决定 HTTP 跳转，避免直接改动微信回调。仓库 nginx/https.conf 与 docker-compose.tls.yml 可作源站 TLS 起点；若 TLS 在外部代理终止，需明确可信代理与原始协议处理，不能相信任意客户端 X-Forwarded-Proto。
2. 授权服务器：采用成熟 OAuth 实现，优先评估 Spring Authorization Server 与当前 Java/Spring 版本兼容性，不手写授权码协议。复用现有应用账号，增加浏览器授权页面：本人登录、明确同意“读取每日计划和复盘”、查看并撤销授权。保持原有邮箱登录与微信绑定行为。没有邮箱/密码的微信用户需另行设计绑定或扫码确认，不默认授予权限。
3. 独立远程只读 MCP：建议路径 https://app.mayh.net.cn/mcp/daily（尚未创建）。仅两个只读工具；OAuth 授权码 + PKCE S256，daily:read scope，发布保护资源和授权服务器元数据。精确白名单 ChatGPT 连接界面提供的回调；先采用预注册客户端，不开放任意注册。
4. 令牌与账号：每个授权绑定明确应用用户，校验 audience、签发者、过期、scope、撤销及账号有效性。后端通过受限可信入口复用 DailyService.read 的实时权限过滤；禁止把调用方 user_id 或共用应用 token 当身份。新增授权表与迁移在独立变更内规划，先隔离数据库验证正向和受限回滚。
5. 验收与发布：模拟用户完成授权、拒绝、撤销、过期、PKCE 错误、错误 audience、重复授权码及双用户隔离；网关内部路由仍禁止公网访问。先提交可审查配置和测试结果，获发布授权后才启用 HTTPS/MCP，真实数据连接再由用户明确同意范围。

## 08:30 / 21:00 的关系

应用调度生成记录；MCP 供 ChatGPT 读取。主动提醒另行实现个人订阅、唯一通知记录、重试及失败显示。微信小程序已有对接不代表具备每天定时推送权限，须核验具体渠道与订阅机制；不承诺 MCP 连接后自动定时推送。

## 当前未执行

未申请证书、修改 DNS/网关、开放端口、部署 OAuth、迁移业务库、注册 ChatGPT 连接或上传真实数据。域名和身份服务条件已记录，后续先做上述独立变更的设计和本地隔离验证。

## 官方依据

- [OpenAI MCP 用户认证](https://developers.openai.com/plugins/build/auth)：OAuth 元数据、授权码、PKCE、resource、客户端与回调要求。
- [Spring Authorization Server](https://docs.spring.io/spring-authorization-server/reference/overview.html)：成熟授权服务器框架；具体依赖版本和所需协议扩展在实施前验证。
