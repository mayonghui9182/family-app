# 微信持续任务会话：发布、观察与回滚

## 已验收范围与真实平台边界

2026-10-04：backend 全量 Gradle、独立 PostgreSQL 迁移/并发/外键验证通过；agent-service 132 项测试、wechat-service 152 项测试与两个模块的 ruff/mypy 通过。跨服务验收使用本次构建的 backend/agent-service/wechat-service 镜像、真实 PostgreSQL、Nginx、签名 XML 回调和客服发送；仅外部微信与 OpenAI 兼容 LLM 由隔离平台模拟。

`python3 scripts/verify-wechat-e2e.py` 只建立随机前缀 `familyapp-wechat-e2e-*` 的容器、网络和匿名数据库卷。内部密钥随机生成且不输出，端口仅绑定本机。脚本结束按本次标签核对并清理测试资源，不重启业务容器、不读取生产密钥、不切换真实账号流量。

验收覆盖：单家庭连续追问；候选修改与旧版本拒绝；外部消息重复和新的重复确认均只建一次；历史列表、派生、二次删除；多家庭切换与非成员拒绝；旧 WAITING_FAMILY 消息按编号选择后只回放一次；真实微信服务发送至模拟平台、45015 终态、下一次有效确认消息被动提示原创建结果；关闭/恢复入口与 worker 不丢消息、不复制旧队列、不额外建任务。

模拟平台不证明真实公众号客服权限、发送额度、48 小时窗口或真实 LLM 语义质量。正式切流前还需管理员用授权测试微信账号完成真实平台冒烟；本次实现验收不等于生产已部署或真实平台已联通。

## 发布前检查

1. 备份数据库，记录版本与镜像摘要；同批发布 backend、agent-service、wechat-service 与网关配置。构建 backend 前执行 `cd backend && ./gradlew test bootJar`，再在根目录执行 `docker compose build backend agent-service wechat-service nginx`。
2. `docker compose config --quiet` 及合并 `docker-compose.production.yml` 的配置校验通过。内部密钥使用显式强随机值，`.env.example` 的空值不能直接运行；不得使用测试密钥。缺失/空白内部密钥启动失败。
3. HTTP/HTTPS 网关均拒绝 `/api/internal` 与 `/api/internal/`。Docker 内网服务携带密钥直连，不绕公网网关；公网不得发布 backend/agent/wechat 的内部端口。
4. 先保持 `WECHAT_CONVERSATION_ENABLED=false`、`WECHAT_CONVERSATION_WORKER_ENABLED=false`、`WECHAT_CONVERSATION_RETENTION_ENABLED=false`。迁移只向前，V15/V16 不回滚，旧 `wechat_inbound_message`、`agent_decompose_sessions` 和旧草稿确认接口保留。
5. 先停旧 backend intake 与 wechat inbound（`WECHAT_INTAKE_ENABLED=false`、`WECHAT_INBOUND_ENABLED=false`），确认旧进程已停止且无 RUNNING 遗留。只读检查旧表 `status='RUNNING'` 的数量；存在遗留时按原幂等凭据和日志核对，不重置后复制到新表。
6. 先在独立测试环境/独立测试公众号启用新入口和 worker，不在共用正式公众号全局启用后假定只有测试账号会消费。当前没有生产 openid 白名单；若共用公众号需要账号级灰度，必须先另行加入并验证白名单，不能只凭口头“测试号”限制流量。
7. 管理员使用授权测试微信账号发送“买菜→补充日期→修改→旧版本确认→当前版本确认→重复确认”，核对真实任务只有一批；再测试默认家庭、切换、历史与客服失败降级。只在有用户明确确认后写任务。

## 观察与扩大范围

指标默认不经公网暴露；`management.endpoint.health.show-details=never` 保持不变。内部监控采集时可显式配置受保护的指标端点，不把 `/actuator/metrics` 开放到公网。

| 指标 | 检查 |
| --- | --- |
| `familyapp.wechat.conversation.inbound.total/persistence` | 持久化失败与时延不持续增长，重投命中 duplicate |
| `familyapp.wechat.conversation.queue.depth` | 家庭作用域队列与未路由传输队列可下降，不持续积压 |
| `familyapp.wechat.conversation.lease.expired` | 过期 PROCESSING 租约被接管，而非永久卡住 |
| `familyapp.wechat.conversation.operation.total` | 固定 event：ai_rejected、confirmation_replay、summary_rebuild、cross_family_denied、experience_activated |
| `familyapp.wechat.conversation.outbox.result.total` | 固定 code：0、40001、40014、42001、45015、43004、other、transport |

所有标签不包含 openid、正文、会话或家庭 ID。业务成功计数在事务提交后增加，回滚不计为激活；安全拒绝即使回滚仍记录。健康检查在关闭模式报告 disabled，开启后按作用域刷新队列快照，数据库刷新失败为 DOWN，不回显异常正文。

建议观察至少一个完整业务使用周期：没有旧 RUNNING 遗留；无长期 PROCESSING/SENDING；队列能够排空；正常发送成功；预期的 45015/43004 不引发无限重试；重复确认指标可以增长，但同一会话修订的确认凭据和任务数量不得增长；摘要重建可恢复，跨家庭内容始终不可见。以上条件满足并记录真实平台冒烟证据后，才由管理员扩大范围。当前未完成生产观察，不能直接宣称可以全量切流。

## 经验和保留参数

- 明确偏好入口：“以后买菜默认给妈妈”；停用：“以后买菜不要默认给妈妈”或“以后买菜不再默认给妈妈”。消息来源可追溯，停用保留版本；同条消息重试不增版本、不创建任务。
- 仅已确认关联任务参与投影。同一 `task:{id}` 对同一规范化偏好仅贡献一个独立计数，两个支持仍 CANDIDATE、第三个才 ACTIVE；明确偏好/停用优先，自动学习不覆盖它们。
- 每轮按目标家庭重新选择最多五条有效经验，明确偏好、相关性、置信度、更新时间顺序；执行人和分类实时校验，候选保存实际经验 ID/version。模型建议不代表确认，本轮明确要求优先。
- 默认会话 TTL 24 小时、摘要六轮/6000 字、近期上下文最多16条；原文30天、摘要/候选180天、小批量50。清理独立默认关闭，启用前备份并验证审计保留例外。确认哈希/任务关联和实际任务不随历史隐藏删除。
- 默认渐进阈值2500ms、最多三段，不逐 token 发送；最大尝试5次、初始退避1000ms、最大60000ms。环境变量具体名称见 `.env.example` 的 `WECHAT_CONVERSATION_*` 段。

## 回滚演练与故障处置

隔离演练已验证：关闭入口和 worker 后写入的 PENDING 消息保持；重新启用后从原表继续消费；旧入站表仍为空，任务不额外增加。测试数据库、匿名卷与网络均已清理。

生产故障时先关闭新入口、worker；保留新表、消息、候选、确认与 outbox，不执行向下迁移，不删除凭据。等待已经开始的 AI/客服 HTTP 和租约结束，核对没有新消费后再修复或恢复。已开始外发无法保证撤回，发送失败也不回滚已创建任务。

确需恢复旧体验时，确认新 worker 和全部新作业已停止，再单独开启旧 intake/inbound；绝不将新表消息复制回旧队列。修复后重新关闭旧模式、确认无旧 RUNNING，恢复新入口/worker。两个模式同时开启会被启动校验拒绝。

旧 H5 `/agent/draft/{id}` 链接、`/api/agent/v1/decompose/sessions/{id}` 读取与确认路径保持原语义，后端全量回归继续覆盖；新会话不把旧草稿自动转成候选，不清除旧接口或旧表。微信解绑/账号注销不在本次范围，家庭删除仅处理目标家庭，不影响同绑定其他家庭。
