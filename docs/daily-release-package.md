# 每日独立发布包与操作顺序

2026-10-14。候选目录：/home/mayh/code/family-app/.volumes/daily-candidates/candidate-3c1sghxc。
状态：2026-10-14用户明确批准后第一阶段已上线，五服务更新、双库迁移及十服务健康确认。OAuth关闭，真实ChatGPT未连接。实际事实见 daily-production-release.json；激活flags路径见 .volumes/daily-active-release.json。

## 固定材料

- 源输入/输出摘要：docs/daily-candidate-assembly.json；候选原文件未覆盖根工作树。
- 7个最终镜像及基础镜像/上下文摘要：docs/daily-release-images.json。
- 镜像隔离启动与归档：docs/daily-release-image-verification.json。
- 原主库V14后端恢复：.volumes/backend-recovery-20261011，JAR及镜像归档已保留。
- 原网关/家庭AI/微信程序恢复候选：docs/daily-runtime-recovery.json；原程序和有差异依赖包、模板已保留。
- 候选归档及三个恢复候选归档均已核对OCI索引与blob摘要，并实际加载到现有镜像仓库。没有删除现有镜像，因此不是全新仓库恢复证明。
- 候选release-config/images.yml固定十个基础服务镜像，禁用build和pull；oauth-images.yml固定两个可选服务镜像。两种配置组合使用虚构变量config --quiet通过，未读取生产.env。生成时Compose版本5.4.0、项目名family-app。
- 原生产三个缺失镜像服务仍运行，不在本次选择性更新名单。覆盖层里的恢复镜像仅是需要重新创建时的已知候选，禁止顺带up这些服务。

## 建议第一阶段批准范围

仅更新 backend、tp-task-service、tp-agent-service、frontend、nginx。主数据库新增daily_app表/独立历史，public仍V14；工作数据库0002升级0003。OAuth、远程读取关闭；不创建真实OAuth账号或发送个人数据。旧default工作数据保留隔离，不自动认领。

已有证据：候选后端524/OAuth24全部通过，工作125+103、前端144、MCP9；实际结构双库虚构记录dump/restore升级通过；7最终镜像启动通过。OAuth跨服务业务来源仍为模拟后端，真实公网ChatGPT未验收；主动通知未实现。

## 批准后执行前检查

1. 维护窗口前重新核对十个生产容器镜像ID、数据库主V14/工作0002、卷挂载及项目名；若漂移停止，不覆盖其他人的发布。备份期间禁止家庭/工作/微信写入；入口维护、后台发送/总结与调度暂停顺序需在实际部署时核对。
2. 验证归档摘要、镜像ID与全部候选输入摘要。确认Docker磁盘空间、两个卷及恢复镜像可用。不要执行全局prune、compose down -v、repair或乱序迁移。
3. 本机受限维护目录备份主库及工作库。生产备份仅在明确部署批准后进行，不上传。实际账号名/库名以本机配置为准，不能盲用示例。两份归档必须pg_restore --list通过，记录摘要；如需导入临时数据库核验，使用新随机测试库，不覆盖生产。
4. 对现有运行配置和本机凭据做受限保存，禁止提交或输出。确认SMTP、内部共享密钥、主/工作数据库密码和只读账号沿用既有值。此准备阶段未读取或保存这些凭据。
5. 解析配置仅使用--quiet，避免完整config输出密码。必须显式指定根仓库.env的绝对路径与候选--project-directory，固定-p family-app；禁止自动读取候选.env。选用候选基础Compose、production覆盖层、release-config/images.yml。

## 选择性发布顺序

定义实际Compose参数为：--env-file /home/mayh/code/family-app/.env，--project-directory指向上文候选目录，-p family-app，依次-f候选docker-compose.yml、docker-compose.production.yml、release-config/images.yml。

- 维持维护状态，DAILY_ENABLED=false。只对backend执行up -d --no-build --no-deps backend；观察健康及public V14/daily_app V1。启动即运行结构迁移，不能在备份前尝试启动。
- 只对tp-task-service执行up -d --no-build --no-deps tp-task-service；入口启动会执行Alembic，核对0003及旧default归属。旧工作Agent保持暂停，避免升级期间共享身份误用。
- 对tp-agent-service、frontend、nginx分别选择性更新，均使用--no-build --no-deps。不更新两个数据库、tool-service、家庭agent-service、wechat-service，不执行无服务名单的up。
- 在维护状态验收邮箱登录/密码恢复、家庭切换及旧微信内网接口、工作双账号隔离；核对机器身份头不由客户端伪造。真实公众号发送、SMTP邮件验收如涉及对外消息应明确授权，不能静默发送。
- 结构和业务检查通过后，在明确启用范围内设置DAILY_ENABLED=true并只重建backend。个人设置仍默认关闭，由本人启用；08:30/21:00生成记录，不承诺主动提醒送达。再解除维护并观察。

## 回退与关闭入口

优先关闭DAILY_ENABLED、DAILY_REMOTE_ENABLED及远程网关路由，保留数据；OAuth若已运行，先阻断入口和停止两服务，不删除授权表或密钥。

主库daily_app有任何记录/个人设置时禁止运行rollback-empty.sql。主库public历史保持V14，不修改历史；安全退回恢复后端镜像时保留daily_app结构。前端可退回已保存旧镜像，但我的一天入口将不可用。

工作库升级0003后，保持新版受信任身份服务及网关鉴权。禁止退回旧共享匿名工作镜像；有用户数据时0003降级必须拒绝。旧主后端若缺少工作身份内部接口，工作访问应保持拒绝/维护状态，不能为恢复可用性关闭认证。修复候选后再恢复工作入口。

若出现主/工作数据损坏，停止所有相关写入后从维护窗口双库备份恢复；这是单独确认的数据恢复操作，可能丢弃发布后写入，不能自动执行。恢复前保留故障数据与证据，不删除卷。

## 第二阶段远程授权

只有第一阶段运行稳定且明确批准后，才准备真实预注册client_id/精确HTTPS回调及受限OAuth数据库角色。不得把app登录令牌给ChatGPT；不能从现有模拟回调推导真实回调。

合并候选docker-compose.daily-oauth.yml及release-config/oauth-images.yml；新增角色/Schema需按docs/daily-oauth-runbook.md只读public.users必要列管理，令牌历史在daily_oauth。单实例，不滚动重叠启动。后端远程默认关闭；最后才启用远程读取及网关optional路由，验证真实授权、撤销与账号隔离。真实连接会读取个人数据，需另外明确授权。

## 恢复材料的实际限制

恢复候选不是原镜像字节复原。Python依赖版本已与运行容器匹配，有差异包从固定程序目录保留；健康/配置检查通过，但尚未验证所有外部平台接口。原三个服务本阶段保持原容器，恢复候选仅应急材料。实际恢复这些服务前应补相应业务验收，禁止把健康页正常当成公众号/LLM真实验收。

