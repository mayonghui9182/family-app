# Proposal: Change 12 - 微信模板消息推送（事件触达）

## Why

Change 11 打通了微信公众号入口与 openid 身份通道，但消息只能「用户主动点菜单」才能看到：每日总结 22:00 生成后没人知道、今天有什么任务得自己打开应用、新任务指派给成员后无任何提醒。家庭场景（尤其老人）依赖微信作为主要触达手段，模板消息是测试号唯一可用的主动推送通道（无需客服消息 48 小时窗口限制）。本 change 把三类家庭事件推到已绑定成员的微信：

1. **每日早报**（08:00）：今日到期 / 逾期任务计数，点击直达任务页；
2. **总结完成**（22:00 总结成功后）：通知家庭成员查看今日总结，点击直达首页总结面板；
3. **任务指派**（实时）：任务创建且指派人有账号绑定微信时，立即推「你有新任务」。

## What Changes

- wechat-service（Python）新增模板消息协议层：
  - `app/wechat/template.py`：事件 → 模板 payload 渲染（template_id、first/keyword/remark 字段、按事件区分的跳转 url），调用微信 `cgi-bin/message/template/send`；token 失效（40001/42001）刷新后重试一次，未关注（43004）不重试记失败
  - 内部端点 `POST /internal/template/send`（X-Internal-Secret）：批量 `{messages:[{openid, event, params}]}`，逐条隔离返回 `{results:[{openid, ok, errcode?}]}`；模板 id 未配置的事件该条 `skipped` 不报错
- backend（Java）新增推送域（`com.familyapp.wechat` 包内增量）：
  - `WechatNotifyClient`：RestTemplate 短超时（connect 2s / read 5s）调 wechat-service，携带 X-Internal-Secret；**任何异常吞掉记 warn，绝不抛出**（推送是旁路，不得影响业务）
  - `WechatNotifyService`：接收人解析（家庭未退出成员 → wechat_bind 绑定 openid）+ 三事件组装
  - `WechatNotifyJobs`：`@Scheduled` 08:00 早报（沿用 TaskSchedulerJobs 单实例 + 逐家庭隔离模式；今日到期/逾期双零跳过不打扰）
  - 接线：C-20 总结 finish 成功钩子（summary_ready）；任务创建两个落库点（TaskServiceImpl.create 手动入口、DirectTaskCreator AI/调度入口）assigneeId 非空且有绑定时推 task_assigned
- 配置与编排：
  - backend：`wechat.service-base-url`（默认 http://wechat-service:8900）、`wechat.notify.enabled`（默认 true，false 全链路 no-op）
  - wechat-service：`WECHAT_TPL_TASK_DIGEST_ID` / `WECHAT_TPL_SUMMARY_ID` / `WECHAT_TPL_TASK_ASSIGN_ID`（空 → 对应事件 skipped 降级）
  - docker-compose.yml 与 `.env.example` 同步上述变量
- **无 DB 迁移**（复用 Change 11 的 wechat_bind）；**无前端改动**（消息 url 指向既有 /tasks 与 /dashboard 路由）；**agent-service 不动**（backend 已有 @EnableScheduling，早报 cron 放 backend）

## 非目标 (Non-goals)

- 不做消息队列 / 异步任务表 / 失败重投队列（同步内部 HTTP + 单条隔离足够；微信侧失败仅记日志计数）
- 不做被动消息回复与公众号内 AI 对话（POST /wechat/callback 维持验签占位，Phase 3）
- 不做客服消息、订阅通知（一次性订阅）、小程序订阅消息
- 不做短信 / App Push / 邮件等其它通道
- 不做模板消息点击回执 / 已读统计
- 不做个人级任务摘要（早报为家庭级计数；个人任务详情点进 H5 看）
- 不做多实例分布式调度锁（沿用 Change 2 单实例部署假设；digest job 天然幂等无副作用，多实例最多重复推送，可接受）
- 真机联调（测试号建模板、真机收消息）DEFERRED 给用户，同 Change 11

## Capabilities

### Modified Capabilities

- `wechat-integration`：在 Change 11 渠道接入之上新增「出站模板消息」能力——事件渲染、批量发送内部端点、token 重试与未关注降级、三事件触发契约

## Impact

- wechat-service：新增 `app/wechat/template.py`、`app/routes/internal_routes.py` 增量、settings 三个模板 id；tests 新增 test_template.py
- backend：`com/familyapp/wechat/` 新增 WechatNotifyClient/WechatNotifyService/WechatNotifyJobs + DTO；WechatBindRepository 加 `findAllByUserIdIn`；TaskRepository 加到期/逾期计数查询；InternalAgentController C-20 与 TaskServiceImpl/DirectTaskCreator 接线；application.yml 配置项；tests 新增通知域测试
- docker-compose.yml：backend 加 WECHAT_SERVICE_BASE_URL / WECHAT_NOTIFY_ENABLED 环境变量；wechat-service 加三个 WECHAT_TPL_*_ID
- `.env.example`：段5 追加模板 id 与开关变量
- 数据流：backend 事件 →（infra-tier 内网 HTTP，X-Internal-Secret）→ wechat-service → 微信 API；openid 不出内网；H5 url 仅含公开基址
