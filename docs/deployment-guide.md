# Family-App 部署与恢复手册

> v2.0 · 2026-10-12（Asia/Shanghai）。面向维护人员。
> 本文对照当前源码及2026-10-11运行核验编写；本轮没有部署或重新核验生产状态。

## 1. 当前适用范围

最近核验：基础服务10个，主库Flyway V14，工作库Alembic 0002。当前工作树包含尚未上线的微信会话、工作隔离、每日功能和OAuth/MCP；不能直接视为发布包。

用户已选择独立发布每日功能、必要工作隔离和只读接入，排除新微信会话改造。独立候选和迁移历史尚未完成，因此本手册当前提供准备、检查和恢复边界，不提供可直接运行的本次一键上线指令。

HEAD仅跟踪迁移到V12，运行程序含V13/V14。当前V15/V16是微信改造、V17是每日记录；不能简单跳过15/16再升级17，也不能修改已应用记录解决分歧。详见[架构](./架构设计文档.md)、[发布审查](./daily-release-review.md)。

## 2. 环境与操作位置

- 项目在WSL Ubuntu：`/home/mayh/code/family-app`。
- Windows使用WSL执行Linux命令，避免从UNC路径直接运行Vite或Gradle。
- 构建需要Java17、Node20及项目锁文件对应依赖；容器使用Docker与支持当前配置的Compose。
- Docker Desktop需已启用Ubuntu的WSL集成。不通过开放Docker socket权限绕过访问问题。
- 本文bash命令在项目根目录执行。Windows包装示例：

```powershell
wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && docker compose ps"
```

## 3. 服务与入口

| 服务 | 职责 | 宿主暴露 |
|---|---|---|
| nginx | 前端、API、微信、工作入口 | 基础配置NGINX_PORT映射80 |
| frontend / backend | 页面与主业务 | 不单独映射 |
| postgres | 主业务库 | 不映射5432 |
| agent-service / tool-service / wechat-service | 家庭AI、工具、微信 | 不单独映射 |
| tp-task-db | 工作库 | 不映射5432 |
| tp-task-service / tp-agent-service | 工作任务和工作AI | 不单独映射 |
| daily-oauth / daily-mcp | 可选授权与只读资源，未发布 | 不单独映射 |

基础编排为[docker-compose.yml](../docker-compose.yml)，生产必填检查为[docker-compose.production.yml](../docker-compose.production.yml)，TLS与OAuth各有可选覆盖层。两个bridge网络不代替应用鉴权。

用户已确认Cloudflare穿透与公网HTTPS可用，源站可保持HTTP。现有隧道无需因本次功能重新创建。公网TLS故障先检查域名、边缘证书和隧道状态；增加源站443不能自动修复这些问题。需要源站TLS时另行审核证书及[docker-compose.tls.yml](../docker-compose.tls.yml)。

## 4. 配置准备

已有安装保留当前配置；新隔离环境从[.env.example](../.env.example)准备自己的配置。凭据仅保存在本机受限文件，不上传聊天或提交Git。不要用完整`docker compose config`或容器环境输出分享配置；它们可能展开密钥。

| 配置组 | 关键变量与注意事项 |
|---|---|
| 主库 | POSTGRES_DB、POSTGRES_USER、POSTGRES_PASSWORD；已有卷的密码不会随环境变量自动修改 |
| AI只读角色 | AGENT_READER_PASSWORD；数据库角色实际密码须与连接配置一致 |
| 工作库 | TP_POSTGRES_DB、TP_POSTGRES_USER、TP_POSTGRES_PASSWORD；与主库分别备份 |
| 机器认证 | INTERNAL_API_SECRET；各受信服务一致，不提供给浏览器或模型 |
| 家庭AI | DEEPSEEK_API_KEY、DEEPSEEK_BASE_URL、DEEPSEEK_MODEL、AI_DECOMPOSE_ENABLED；以实际服务商及源码配置为准 |
| 工作AI | TP_LLM_API_KEY、TP_LLM_BASE_URL、TP_LLM_MODEL；无凭据不代表AI功能验收通过 |
| 微信 | WECHAT_APP_ID、WECHAT_APP_SECRET、WECHAT_TOKEN、OAuth基址、模板ID及相应开关；按实际公众号权限验收 |
| 密码找回 | PASSWORD_RESET_*；需要邮件渠道及正确公开地址，见[专项说明](./operations/password-recovery.md) |
| 每日功能 | DAILY_ENABLED默认false，另需本人启用自动生成；DAILY_WORK_URL指向可信工作服务 |
| 远程只读 | DAILY_REMOTE_ENABLED、DAILY_OAUTH_ENABLED默认false；OAuth配置见下节 |

手工配置主后端JDBC时保留`stringtype=unspecified`以兼容相关JSONB写入。Compose内部服务地址使用服务名，不能用宿主localhost替代。

改环境文件后，普通`restart`不会重建容器环境；需在获批发布流程中使用相同覆盖层重新创建受影响服务。不要为改一个开关无条件停掉整套系统。

## 5. 无部署副作用的检查

以下检查不会执行迁移或重建业务服务，但依赖本机配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.production.yml config --services
docker compose ps
docker compose exec -T nginx nginx -t
docker compose exec -T nginx wget -qO- http://127.0.0.1/nginx-health
```

可选OAuth编排解析：

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml -f docker-compose.daily-oauth.yml config --quiet
```

解析成功只说明语法和插值通过；OAuth部分参数允许空值插值，但服务启动时仍会拒绝缺失配置。健康检查也不替代用户登录、权限、模型或微信实际验收。

仅在确认实际账号/库名仍为默认值时，可读取迁移版本：

```bash
docker exec familyapp-postgres psql -U familyapp_user -d familyapp -Atc 'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'
docker exec familyapp-tp-task-db psql -U tp -d task_db -Atc 'SELECT version_num FROM alembic_version'
```

不要将默认值错误当成数据库损坏。日志只在本机查看，分享前排除令牌、邮箱及业务正文。

## 6. 发布候选与升级顺序

### 必须具备的交付材料

1. 来源基线、文件允许清单、内容摘要和可重建构建步骤。
2. 已审核的迁移集合，以及未来微信迁移接回路径。
3. 候选实际测试证据：主业务、旧微信、密码恢复、工作隔离、前端、OAuth/MCP。
4. 主库和工作库备份、实际恢复证据、已验证镜像及兼容性说明。
5. 精确服务更新范围、维护窗口、故障判定和关闭入口步骤。

### 获批后的执行顺序

固定候选镜像 → 暂停相关业务写入和后台作业 → 双库备份与恢复检查 → 按设计协调迁移及服务更新 → 手动功能验收 → 本人启用自动生成 → 另行完成真实远程授权验收。

工作容器入口会自动运行`alembic upgrade head`，主后端启动会运行Flyway；不能把“只更新容器”当成无数据库变化操作。网关还需正确解析更新后的服务地址。

当前`scripts/deploy.sh`整套构建当前目录、备份主库后更新所有服务；没有完成工作库备份，也不加载OAuth层或构建其installDist。`scripts/backup-postgres.sh`同样只覆盖主库。这两个脚本不能直接当本次发布流程。

首次开发环境也应与生产隔离：现有编排含固定容器名、网络名，不能只换Compose项目名就认为隔离完成。不得用生产目录中的`up/down`或E2E脚本试验新版本。

## 7. 双库备份和恢复要求

- 备份范围：主库、工作库，OAuth上线后包括独立授权Schema及其history；另保存必要配置与镜像恢复材料。
- 本地备份目录限制权限，使用自定义归档格式，先写`.partial`，成功后校验并标记完整；生成校验值和库版本记录。
- `pg_restore --list`仅检查目录可读，不代表数据可恢复。必须还原到独立数据库，再检查结构、行数/关联、序列、约束及应用登录和关键业务。
- 跨库一致性需协调写入窗口，两个dump分别成功不能证明业务一致。
- 数据库角色、所有权和授权需有恢复方案；数据库dump不能替代角色/凭据配置。不得为了让恢复通过随意忽略权限错误。
- 有效备份不得覆盖原库做演练。包含真实数据的备份仅在获准位置保存和使用，不外传。

目前已有恢复候选镜像`familyapp-recovery/backend:20261011`及本机忽略目录`.volumes/backend-recovery-20261011`中的归档和校验文件。已验证运行JAR校验一致、独立空库启动到V14、健康UP；它不是原镜像逐层复原，未完成真实双库业务恢复。

简化虚构表的双库dump/restore已验证数据、序列、外键和无效归档拒绝；不可将其表述为生产恢复验收。详细证据见[发布审查](./daily-release-review.md)。

## 8. 每日与OAuth专项准备（未发布）

每日服务启用后仍需本人勾选自动生成。按上海时间08:30/21:00生成记录；当前没有主动通知。工作隔离要协调升级，不能把缺失或default归属的响应当作本人数据。旧共享工作数据认领另行审计和确认。

OAuth需要最小权限角色：拥有daily_oauth Schema，只读public.users的id/email/password_hash/deleted_at；数据库URL与Flyway限定独立Schema，禁止共用主业务history。

客户端ID、随机secret、精确HTTPS回调与独立内省客户端在仓库外本地配置，不猜测回调，不共用客户端身份。授权应用仅支持单实例。应用登录token与OAuth token不可混用。

准备路径：`https://app.mayh.net.cn/mcp/daily`、`/daily-oauth`及元数据路径；目前尚未上线。公网内部接口和内省必须拒绝。验证完整登录/同意、双用户隔离、错误resource/scope、撤销及重启恢复后，才进行用户真实连接。

构建和隔离验证命令见[OAuth运行说明](./daily-oauth-runbook.md)，每日迁移与恢复见[每日运行说明](./personal-daily-planning-runbook.md)。这些测试会创建临时资源，不归入只读生产检查。

## 9. 验收与故障处理

| 现象 / 检查 | 处理方向 |
|---|---|
| Compose解析失败 | 检查必填配置及覆盖层；不输出完整密钥配置到聊天 |
| 数据库认证失败 | 核对实际角色与本地配置；改.env不等于改已有数据库密码 |
| Flyway/Alembic失败 | 停止扩大发布，保留错误和版本证据；不清库、不repair掩盖冲突 |
| 容器健康但AI无响应 | 单独核验模型配置、网络、权限和调用结果 |
| HTTPS不可达 | 区分Cloudflare边缘、隧道、源站nginx三段检查 |
| 每日生成失败 | 检查开关、工作源和用户归属；失败不是“今天没有任务” |
| MCP连接失败 | 检查HTTPS发现、回调、授权范围、内省和用户状态，不绕过认证 |
| 更新后旧工作数据不可见 | 旧default可能被隔离；不批量归给当前账号 |

出现故障优先关闭新增入口和调度、保留数据。OAuth停入口不删除已授权记录；有真实工作数据后不得回退为共享身份版本。V17及OAuth逆向均有“最新迁移、目标表为空”等限制，有数据时不能通过删除用户记录满足条件。

镜像存在不代表兼容新数据库，主库V14恢复候选不能证明可在升级后的结构上安全倒退。整库恢复需评估备份之后的数据损失，另行批准。清库、删除卷与全局prune不属于常规排障。

## 10. 发布记录

每次发布记录：时间与维护人、候选摘要、两库迁移前后版本、备份位置及恢复结果、配置变更键名（无值）、功能验收、遗留问题和回退判定。

本版手册更新不代表部署已完成。实施进度以[独立候选任务](../openspec/changes/personal-daily-remote-oauth/tasks.md)为准，用户操作见[用户手册](./用户使用手册.md)。
