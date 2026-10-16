# 每日记录 OAuth 只读接入：本机验收与发布边界

## 当前交付

OpenSpec `personal-daily-remote-oauth`。独立 Spring Authorization Server（Java 17、Spring Boot 3.5.12、授权框架 1.5.8）与 HTTP MCP（SDK 1.30.0），不改变主应用依赖版本。邮箱登录复用已有账号密码哈希，浏览器明确同意 `daily:read`；仅查询计划和复盘，不生成、确认或修改任务。

授权码需 PKCE S256、固定 resource、精确登记回调；支持客户端 Basic/Post 认证、短期 opaque access token、轮换 refresh token、拒绝和个人撤销。授权、客户端及同意保存在独立 `daily_oauth` Schema。授权管理会撤销同一客户端的本人全部授权；其他用户不受影响。刷新、兑换及撤销在单实例内串行处理，防止并发重建已撤销授权。

MCP 每次请求内省并核验 active、issuer、audience、scope、过期及数字账号主体；不缓存撤销状态，不接受工具参数指定身份。内部后端再次检查活跃账号与现有数据权限。输出排除内部 ID、初始快照、描述和自由文本反馈；任务标题为数据，不作为执行指令。

## 已确认的入口

用户已确认 Cloudflare 穿透、`https://app.mayh.net.cn` 可打开、邮箱密码能登录。公网 HTTPS、源站 HTTP 可以共存；本次没有改 Cloudflare 设置，也没有安装源站证书。

准备的路径（尚未上线）：

| 用途 | 地址 |
|---|---|
| MCP 资源 | `https://app.mayh.net.cn/mcp/daily` |
| 资源元数据 | `https://app.mayh.net.cn/.well-known/oauth-protected-resource/mcp/daily` |
| 授权服务器 issuer | `https://app.mayh.net.cn/daily-oauth` |
| RFC 8414 元数据 | `https://app.mayh.net.cn/.well-known/oauth-authorization-server/daily-oauth` |
| 个人授权管理 | `https://app.mayh.net.cn/daily-oauth/authorizations` |

授权服务固定公开 issuer，不从 Host/转发头推导；登录、同意、表单提交与登录成功跳转使用 HTTPS 地址。Cookie 默认 Secure、HttpOnly、SameSite=Lax；内省路径只允许容器内网调用。原 `/api/internal` 网关拒绝保留。不要把隔离测试的 Cookie Secure=false 用于发布。

## 本机运行版本与发布范围

2026-10-11 只读核对：现有 `familyapp-postgres` 主库最新成功迁移为 V14，现有十个业务容器未被本轮重启。当前仓库有既有未提交修改；V15 公众号会话、V16 微信默认家庭、V17 每日计划均未在该库应用。

因此，**不能直接以当前工作树执行整套升级**。主应用发布前必须明确待发布变更与依赖，审核 V15/V16 是否随本次发布，备份并记录运行镜像。工作域还须协调所有权迁移及安全版本，不能只升级每日聚合后端。新的每日聚合会拒绝 `user_id=default`、缺失归属或他人归属的工作响应，避免部分升级泄露数据；失败不会保存成完整空计划。

本次只交付代码、隔离验收和可选发布材料。没有执行业务库迁移、建立真实 OAuth 数据库角色、打开公网路由、注册 ChatGPT 连接或读取真实计划。真实部署和数据连接需要另外明确授权。

## 待发布配置

`docker-compose.daily-oauth.yml` 是可选覆盖层，基础 Compose 不加载。`DAILY_OAUTH_ENABLED` 与 `DAILY_REMOTE_ENABLED` 默认 false；直接运行授权/MCP 程序也需显式启用，缺失必要配置会停止。后端内部读取同时禁止关闭主认证。

准备独立数据库角色：仅拥有 `daily_oauth` Schema，并只读 `public.users` 的 `id,email,password_hash,deleted_at`；不给主业务表写权限。Schema 先由管理员建立并归属新角色。数据库 URL 应包含 `?currentSchema=daily_oauth`，覆盖层同时限定 Flyway Schema，禁止通过主库 V14 的 history 接入授权迁移。

以下配置仅在本机、仓库外的受限环境文件中填写，不发聊天、不提交 Git：

| 配置 | 内容 |
|---|---|
| `DAILY_OAUTH_DATABASE_URL` | 主库连接地址及独立 Schema；数据库角色权限按上述限制 |
| `DAILY_OAUTH_DATABASE_USER/PASSWORD` | 独立授权角色的本地凭据 |
| `DAILY_OAUTH_CHATGPT_CLIENT_ID/SECRET` | 预登记 ChatGPT 客户端，随机 ASCII secret 为 32～72 字符 |
| `DAILY_OAUTH_CHATGPT_REDIRECT_URI` | 连接管理界面显示的精确 HTTPS 回调，不使用通配符、不猜测 callback_id |
| `DAILY_OAUTH_READER_ID/SECRET` | 单独的 MCP 内省客户端，不能与 ChatGPT 客户端共用 |
| `INTERNAL_API_SECRET` | 主应用已有内网凭据，仅提供给 MCP 服务，不交给 ChatGPT |

已有客户端配置不一致时启动拒绝，不自动覆盖 secret 或回调。需要轮换时先审核已有授权与客户端设置。主应用每日功能另需 `DAILY_ENABLED=true`；自动生成仍要本人主动启用。开启 OAuth 不会替用户开启定时计划。

## 构建与验收命令

所有命令在项目根目录执行；Windows 使用仓库约定的 WSL 包装。下面都是本机测试或静态校验，不是上线命令。

```sh
backend/gradlew -p integrations/daily-oauth test installDist --no-daemon
python3 scripts/verify-oauth-postgres.py
python3 scripts/verify-oauth-e2e.py
python3 scripts/verify-daily-oauth-nginx.py
docker compose -f docker-compose.yml -f docker-compose.daily-oauth.yml config --quiet
openspec validate personal-daily-remote-oauth --strict
```

授权服务 Dockerfile 使用 `installDist` 产物；MCP Dockerfile仅复制两个应用文件及固定依赖。新服务不发布宿主端口，运行用户为非 root；覆盖层只读文件系统、临时 /tmp，并在新服务启动后再启动可选网关路由。

PostgreSQL 脚本仅创建随机回环测试库，运行真实迁移/协议/受限逆向；端到端脚本使用随机独立网络、真实授权服务器与 MCP、受限账号角色，后端每日数据为模拟。包括两用户表单登录/同意/查询、个人撤销及真实重启恢复。测试脚本只删除本轮标签匹配的容器、匿名卷和网络；没有业务数据。临时预览可设置 `DAILY_OAUTH_PREVIEW_SECONDS=60`，只在测试结束后短暂保留模拟页面。

HTTP/HTTPS `nginx -t` 用一次性测试证书；这不是公网证书或真实 ChatGPT 验收。Java H2 条件跳过的 PostgreSQL用例必须在独立脚本实际运行，不能把跳过当通过。

早期 PostgreSQL 验证脚本曾仅删容器，已修正为同时删除本轮匿名卷。已失去容器归属信息的历史无标记卷不做全局清理，避免误删其他数据。

## 回滚与运行约束

- 只支持单实例授权服务。会话及限流在进程内，授权在数据库；扩容需先改成共享会话、限流及数据库并发协调，不能直接启动多个副本。
- 首选移除可选网关覆盖层、停止新服务并关闭后端 `DAILY_REMOTE_ENABLED`，保留授权 Schema 和每日记录。
- 逆向脚本 `integrations/daily-oauth/src/main/resources/db/rollback/V1__OAuth_Storage.sql` 仅在 V1 为最新且授权/同意为空时执行；有数据拒绝。客户端种子也会删除，重新迁移后需重新配置登记。
- 备份含令牌引用及账号相关授权信息，应受限保存；不能上传聊天或公共仓库。关闭入口不能删除已发送到用户会话的数据，只阻止新读取。
- 当前仍没有主动提醒：08:30/21:00 生成记录、MCP 查询和通知送达是不同步骤，通知渠道后续单独验收。

## 官方依据

[OpenAI MCP 认证](https://developers.openai.com/plugins/build/auth)、[Spring Authorization Server](https://docs.spring.io/spring-authorization-server/reference/getting-started.html)。授权表结构依据框架 1.5.8 Apache-2.0 发行包，保留出处，适配 PostgreSQL TEXT 并已做真库验证。
