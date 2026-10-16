## Context

参见 proposal.md 的 Why。当前 Nginx 同时连接 backend、wechat-service 与两个 task-planner 服务所在的 public-tier；内部 Java/Python 服务之间则通过 Compose DNS 直连。主应用登录态由 `X-Access-Token` 表示，`GET /api/auth/me` 已能对其做完整校验。

## Goals / Non-Goals

**Goals:**

- 在不改变服务间内部 URL 的前提下切断公网 internal API。
- 复用已有登录态保护 task-planner 公网入口，保持 REST 与 SSE 契约不变。
- 让内部共享密钥和 task-planner 数据库口令缺失时显式失败。
- 提供不依赖真实外部账号的自动化验证。

**Non-Goals:**

- 不在本波引入 OAuth2、JWT 或新的统一身份服务。
- 不给 task-planner 增加用户、家庭或 ACL 数据模型。
- 不改变微信回调公网入口、主业务 API 或 MCP 的内部调用契约。

## Decisions

### D1. Nginx 在通用 `/api/` 之前拒绝 internal 前缀

同时配置 `location = /api/internal` 与 `location ^~ /api/internal/`，统一返回 404。选择 404 而非 403，避免向公网确认内部接口存在。内部服务调用使用 `http://backend:8080`，不经过 Nginx，因此不受影响。

替代方案是在 backend 中按来源 IP 拒绝公网请求；但应用只能看到代理地址，且错误配置可信代理后仍可能绕过，因此网关拒绝是更窄、更可靠的边界。

### D2. task-planner 使用 Nginx `auth_request` 复用 `/api/auth/me`

新增仅供 Nginx 内部使用的鉴权子请求 location，向 backend 转发 `X-Access-Token` 且不发送请求体。两个 `/api/work-tasks` location 在访问上游前执行该子请求。backend 返回 2xx 才放行，401/403 原样成为网关拒绝结果。

替代方案是在两个 Python 服务复制主库 token 查询逻辑，会引入主库访问、token TTL 规则重复与多租户耦合；另一个方案是让浏览器提供 task-planner 共享密钥，这会把机器密钥暴露给客户端，不能采用。

### D3. 部署变量用 Compose 必填表达式，应用配置拒绝空值

主 Compose 使用 `${VAR:?message}`，使缺失或空变量在容器创建前失败。Spring 配置去掉冒号后的默认值；两个 Python Settings 使用字段校验拒绝空白 SecretStr。测试环境提供显式测试密钥，不恢复生产默认值。

替代方案是保留开发默认值、只在 prod profile 校验；但现有 Compose 固定启用 prod，其他启动路径仍可能遗漏 profile，容易再次形成旁路。

### D4. task-planner 数据库只在主 Compose 强制强口令

主部署中的数据库与 task-service 使用同一个必填 `TP_POSTGRES_PASSWORD`。独立 task-planner 开发 Compose 的回环绑定与测试库约定保持不变，本波不改变其本地开发基线。

## Risks / Trade-offs

- [现有部署未配置新必填变量会停止启动] → 在 `.env.example` 与部署文档中明确生成方式，先配置后重建。
- [Nginx 子请求增加一次 backend 查询] → 仅工作任务入口承担该开销；先以正确性优先，后续可在有测量数据后评估短时缓存。
- [backend 不可用时工作任务入口同步不可用] → 主应用登录态本就依赖 backend，采用失败关闭比匿名降级安全。
- [Nginx 401 响应体不同于主应用 JSON 信封] → 前端以 HTTP 401 触发统一登录失效逻辑；不依赖子请求响应体。
- [只能阻止公网匿名访问，不能提供 task-planner 细粒度家庭隔离] → 在规格中明确为非目标，后续 P1 单独处理。

## Migration Plan

1. 为部署环境生成并保存强随机 `INTERNAL_API_SECRET` 与 `TP_POSTGRES_PASSWORD`。
2. 若复用既有 `tp-postgres-data` 卷，先在运行中的 PostgreSQL 内交互式轮换 `tp` 角色密码，再把同一值写入 `.env`；仅修改环境变量不会更新已有角色。
3. 先运行 Compose 配置校验，再构建并重建 backend、agent-service、wechat-service、tp-task-db、tp-task-service 与 Nginx。
4. 验证公网 internal 请求为 404、匿名工作任务请求为 401、有效登录态可访问工作任务接口。
5. 验证内部 AI 与微信调用仍能携带相同密钥访问 backend。
6. 回滚时可恢复上一版镜像与配置；保留已经生成的强密钥和数据库口令，不回退到默认值。
