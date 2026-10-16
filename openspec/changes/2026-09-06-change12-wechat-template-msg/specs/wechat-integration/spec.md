# Delta Spec: wechat-integration (Change 12 - 模板消息推送)

> 在 Change 11 渠道接入之上新增**出站模板消息**能力：业务事件经内部端点批量推送到已绑定微信的家庭成员。

## ADDED Requirements

### Requirement: wechat-service SHALL 提供模板消息批量发送内部端点

wechat-service SHALL 提供 `POST /internal/template/send`（X-Internal-Secret 校验），接受批量消息 `{messages:[{openid, event, params}]}` 并逐条调用微信 `cgi-bin/message/template/send`，返回逐条结果 `{results:[{openid, ok, skipped?, errcode?, errmsg?}]}`。单条消息的渲染或发送失败 SHALL 被隔离，不影响同批其它消息，且端点整体 SHALL NOT 返回 5xx。

#### Scenario: 批量发送部分失败
- **WHEN** 内部端点收到 3 条消息，其中 1 条 openid 已取关（微信 errcode=43004）
- **THEN** 返回 200，results 中 2 条 ok=true、1 条 ok=false 且 errcode=43004，三条均有对应结果项

#### Scenario: 无密钥调用被拒
- **WHEN** 不带或带错误 X-Internal-Secret 调用 /internal/template/send
- **THEN** 返回 403，不产生任何对微信的调用

### Requirement: 事件 SHALL 渲染为对应模板且跳转 url 按事件区分

wechat-service SHALL 按 event 类型选择模板（template_id 来自环境变量）、组装中文字段（first/keyword/remark）与跳转 url：`task_digest` → `/tasks`（今日到期/逾期计数）；`summary_ready` → `/dashboard`（家庭名/日期）；`task_assigned` → `/tasks`（任务标题/截止日期）。event 未知 SHALL 返回该条 ok=false 且不调用微信；对应模板 id 未配置 SHALL 返回 skipped=true 的降级结果。

#### Scenario: 早报事件渲染
- **WHEN** 收到 event=task_digest、params={today_due:3, overdue:1}
- **THEN** 发往微信的 payload 使用 WECHAT_TPL_TASK_DIGEST_ID，keyword1 含「3」、keyword2 含「1」，url 以 /tasks 结尾

#### Scenario: 模板未配置时降级
- **WHEN** WECHAT_TPL_SUMMARY_ID 为空时收到 summary_ready 事件
- **THEN** 该条结果 skipped=true、ok=false，不调用微信 API，同批其它事件正常发送

### Requirement: access_token 失效 SHALL 单次重试，用户取关 SHALL NOT 重试

发送模板消息遇到微信 errcode 40001/42001（token 失效）SHALL 强制刷新 access_token 后重试一次；遇到 43004/43010（未关注/已取消关注）SHALL 直接返回失败且不重试；其它 errcode SHALL 原样返回失败结果。

#### Scenario: token 过期自动重试成功
- **WHEN** 首次发送返回 40001，刷新 token 后重试返回 errcode=0
- **THEN** 该条结果 ok=true，微信 token 端点被额外调用 1 次

#### Scenario: 取关用户不重试
- **WHEN** 发送返回 43004
- **THEN** 结果 ok=false 且对微信发送接口的调用恰好 1 次

### Requirement: backend SHALL 在三类业务事件触发模板消息

backend SHALL 在以下事件向相关家庭成员推送模板消息：(1) 每日 08:00 早报 job，逐家庭统计今日到期与逾期任务数（双零跳过）；(2) 每日总结运行成功收尾（C-20 finish success）后；(3) 任务创建且 assigneeId 对应用户已绑定微信时。接收人 SHALL 为该家庭未退出成员中已绑定 openid 者；无绑定成员时 SHALL 跳过。

#### Scenario: 早报推送
- **WHEN** 08:00 早报 job 运行，某家庭有 2 个今日到期任务、1 个逾期任务，且 3 名成员中 2 人已绑定微信
- **THEN** wechat-service 收到 2 条 task_digest 消息，params 计数为 today_due=2、overdue=1

#### Scenario: 无任务不打扰
- **WHEN** 早报 job 统计某家庭今日到期与逾期均为 0
- **THEN** 不产生任何模板消息调用

#### Scenario: 总结成功推送
- **WHEN** agent-service 回调 C-20 finish 且运行状态为 success
- **THEN** 该家庭已绑定成员各收到 1 条 summary_ready 消息；状态为 failed 的收尾不触发推送

#### Scenario: 任务指派实时推送
- **WHEN** 创建任务时 assigneeId 指向已绑定微信的用户
- **THEN** 该用户收到 1 条 task_assigned 消息（含任务标题与截止日期）；assigneeId 为空或指派人未绑定时不发送

### Requirement: 推送链路失败 SHALL NOT 影响业务

backend 推送客户端 SHALL 使用短超时（连接 ≤2s），wechat-service 不可达、返回错误或推送开关关闭时 SHALL 静默跳过（日志记录），业务接口与调度任务 SHALL 保持原有成功行为。`wechat.notify.enabled=false` 时全链路 SHALL 不产生对 wechat-service 的调用。

#### Scenario: 推送服务宕机不阻断业务
- **WHEN** wechat-service 连接被拒绝时用户创建带指派的任务
- **THEN** 任务创建接口仍返回成功，任务正常落库，错误仅记录日志

#### Scenario: 开关关闭
- **WHEN** WECHAT_NOTIFY_ENABLED=false 时早报 job 运行
- **THEN** 不产生任何对 wechat-service 的 HTTP 调用
