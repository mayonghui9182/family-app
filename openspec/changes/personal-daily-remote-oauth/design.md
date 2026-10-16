## Context

域名 https://app.mayh.net.cn 经 Cloudflare 回源本机 HTTP，HTTPS 可打开由用户确认；未实测登录或公网安全配置。原 API 仍使用 X-Access-Token，远程 MCP 的 Bearer token 与其隔离。

## Goals / Non-Goals

先完成资源服务可信访问链，再完成授权服务器与部署材料；按 Harness 最小单元依次验证。不改变已有登录或微信行为，不开放公网，不把单元成功作为真实连接完成。

## Decisions

- 资源标识固定为配置的 HTTPS MCP URL；issuer 也是固定 HTTPS，不能从来路 Host 或 X-Forwarded 生成。
- 资源服务器用 MCP SDK AuthSettings/TokenVerifier，通过固定内省端点、独立客户端凭据核验 token；每次请求内省、不缓存，校验 active 严格布尔、iss/aud/exp/scope/client/sub。sub 是授权服务器可信的不可变应用账号数字 ID，必须与后端 active user 再校验。外部输入 user_id 无效。
- HTTP 内省/后端仅允许独立配置的回环或 Docker 内网；禁用代理和重定向、限制响应体及超时，不接收调用参数指定地址。
- 后端内部入口受现有 internal secret 拦截器保护、额外默认关闭开关；只读记录且再次验证活跃用户。网关已有 /api/internal 拒绝必须保留。
- 内部入口不允许关闭主认证；每日聚合再次校验工作响应 user_id，部分升级遇到 legacy/default 或他人任务时拒绝生成快照。
- 授权服务器采用 Spring Authorization Server，使用独立客户端、授权、同意持久化表，按当前 Spring 兼容版本验证；授权码 + PKCE S256、固定 daily:read/resource、精确回调、登录 CSRF 与限流、授权码一次性、撤销即时生效。邮箱密码在授权入口只校验，不签发或返回 app token。
- 授权服务器同意页明确私人数据范围；使用独立安全 Cookie，不与应用 token 混用。准备用户可撤销的授权管理。
- 授权服务通过受限数据库角色只读 public.users 必要账号列；授权表及 Flyway history 放在独立 daily_oauth Schema，不调用会签发 app token 的登录 API。单实例串行授权变更，个人撤销删除同客户端本人全部授权，禁止多副本发布。
- 客户端采用预注册方式；真实 client_id/redirect_uri 由连接界面获得后本地配置，模拟客户端用于测试。不开放 DCR 或任意 redirect。

## Risks / Trade-offs

内部密钥泄露会影响机器调用 → 原有网关拒绝、默认关闭入口、隔离服务、日志无凭据。内省不可用 → 拒绝访问，不以 stale token 降级。独立授权持久化与密钥管理增加运维成本 → 单独备份、短期 access token、关闭入口回滚保留授权表。

## Migration Plan

2026-10-13，4.2选择并隔离验证独立 `daily_app` Schema 与历史，账号外键引用 `public.users`。主链保留原 V1–V14及回调；每日独立V1之后，未来主链V15/V16正常执行并保留每日记录，禁止再纳入旧主链V17。候选通过先主后每日的迁移策略和显式SQL前缀接线（4.3）；验证范围、受限空表回退与后续接回路径见 `docs/daily-migration-history.md`。尚未装配候选或部署。

### 独立发布候选（2026-10-12 已确认）

- 当前 HEAD 0ff758f 仅跟踪到 V12，运行程序含 V13/V14，不能把 HEAD 当作生产源码。先将恢复 JAR 的迁移、关键类及资源与候选源产物逐项比较，记录可证明的差异；无法证明的差异必须列出并验证，不声称字节等同生产。
- 在独立目录以文件允许清单组装候选，记录来源路径、内容摘要和必要差异。不得整包复制脏工作树、环境文件、业务卷、备份或OAuth凭据；不修改现有未提交工作。
- 保留当前已上线的密码恢复、通知及旧微信功能所需依赖。排除新增 wechat/conversation、V15/V16 会话结构及其 FamilyService 删除钩子、新入口和后台作业；不能只关开关或只删迁移。共享认证、任务筛选 TaskAssignees、FamilyService、ResultCode、前端菜单和代理配置按实际调用依赖逐段审查。
- 纳入 daily、WorkUserInternalController、工作两个服务的用户隔离和0003、独立OAuth/MCP，以及必要的网关和前端入口。保持主认证、family_id和工作user_id隔离，旧default工作数据不认领。
- 候选不得直接复用“缺V15/V16但保留主库V17”的迁移链：Flyway升级到17后未来较低编号迁移可能被忽略。实施前选择并验证独立每日Schema/history或全仓协调的迁移编号方案，写明后续微信接回路径；禁止开启outOfOrder、repair或修改生产history来掩盖历史不一致。已应用V1–V14及回调脚本必须核对原内容。
- 所有验证针对候选产物重新执行：编译/前端构建、家庭与工作隔离、原微信/密码恢复回归、V14升级及双库实际结构恢复、OAuth/MCP真实协议测试。现有混合工作树的绿测不替代候选测试。
- 发布证据包含镜像摘要、源文件清单、迁移集合、备份恢复结果和关闭入口回退步骤。仅准备材料，部署和真实授权连接仍单独确认；主动提醒不在此候选范围。

资源服务无迁移，内部入口默认关闭；测试库验证授权持久化迁移/受限逆向后才准备发布。生产不回滚为匿名 MCP，不删除用户授权记录。网关/Compose 仅准备可选覆盖层，实际部署与真实连接单独确认。
