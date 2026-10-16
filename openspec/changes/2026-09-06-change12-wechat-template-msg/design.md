# Design: Change 12 - 微信模板消息推送

## Context

Change 11 已建：wechat-service（FastAPI:8900，验签/OAuth/token 单飞/菜单，持微信凭证）、backend `wechat_bind`(openid↔user_id) 与内部端点、nginx `/wechat/` 公网入口。本 change 只加**出站推送**：backend 业务事件 → wechat-service → 微信 `cgi-bin/message/template/send`。

约束（沿用 Change 11 定版）：测试号形态（模板在测试号后台手工创建拿 template_id；关注上限 100 人）；无凭证环境全链路 mock/降级，真机联调 DEFERRED；内部调用 X-Internal-Secret；失败不得影响业务。

## Decisions

### D1：链路方向与职责切分

```
backend（事件 + openid 映射）          wechat-service（协议 + 模板）          微信
  C-20 finish / 08:00 cron /          POST /internal/template/send
  任务创建  ──批量 messages──▶  渲染 event→template_id/字段/url ──▶ cgi-bin/message/template/send
  ◀── results[{openid,ok}] ──        token 单飞/43004/40001 重试
```

- backend 拥有：什么事件、发给谁（wechat_bind）、业务参数（计数/标题/日期）。
- wechat-service 拥有：template_id（环境变量）、微信 data 字段布局（first/keyword1.../remark）、跳转 url 拼接（H5_BASE_URL + 事件路由）、token 与错误码处理。
- 理由：模板字段随微信后台模板定义变化，隔离在持凭证的服务内；backend 不出现任何微信模板细节。

### D2：wechat-service 契约

`POST /internal/template/send`，请求：
```json
{"messages": [
  {"openid": "oX...", "event": "task_digest", "params": {"today_due": 3, "overdue": 1, "family_name": "老王家"}},
  {"openid": "oY...", "event": "task_assigned", "params": {"title": "买菜", "due_date": "2026-09-07"}},
  {"openid": "oZ...", "event": "summary_ready", "params": {"family_name": "老王家", "date": "2026-09-06"}}
]}
```
响应 `200 {"results": [{"openid","ok":bool,"skipped":bool?,"errcode":int?,"errmsg":str?}]}`：
- 鉴权失败 → 403（沿用 setup-menu 的 40300）；body 结构错误 → 400。
- 单条隔离：任何一条渲染/发送异常不影响其它条；结果逐条返回。
- event 未知 → 该条 `ok=false, errcode=40004` 语义（本地校验错误，不调微信）。
- 该 event 的 template_id 未配置（env 空）→ `ok=false, skipped=true, errcode=null, errmsg="template_not_configured"`（降级可观测，不当错误报警）。

### D3：模板渲染与 url（event 映射表，wechat-service 内）

| event | template_id env | url | data 字段 |
|---|---|---|---|
| `task_digest` | WECHAT_TPL_TASK_DIGEST_ID | `{H5_BASE_URL}/tasks` | first=「今日家庭任务早报」；keyword1=今日到期 N 件；keyword2=逾期 M 件；remark=「点击查看任务详情」 |
| `summary_ready` | WECHAT_TPL_SUMMARY_ID | `{H5_BASE_URL}/dashboard` | first=「今日家庭总结已生成」；keyword1=家庭名；keyword2=日期；remark=「点击查看总结面板」 |
| `task_assigned` | WECHAT_TPL_TASK_ASSIGN_ID | `{H5_BASE_URL}/tasks` | first=「你有新的家庭任务」；keyword1=任务标题；keyword2=截止日期（无则「无截止日期」）；remark=「点击处理任务」 |

- 测试号模板后台内容需与字段对应（建模板步骤写进 handoff runbook）；字段值全中文。
- data 结构 `{"first":{"value":...},"keyword1":{"value":...},"remark":{"value":...}}`，color 不传（测试号默认黑色）。

### D4：微信错误码处理

- errcode=0 → ok。
- 40001/42001（token 失效/过期）→ 强制刷新 token 后**重试一次**；仍失败 → ok=false 返回 errcode。
- 43004（未关注）/43010（已取消关注）→ ok=false，**不重试**（用户已取关，重试无意义；日志单独计数）。
- 其它非零 errcode → ok=false + errcode/errmsg 原样返回。
- 传输层异常（httpx）→ 该条 ok=false, errmsg 为异常摘要，不抛 500（批量内隔离）。

### D5：backend 触发点与接收人

- **早报** `WechatNotifyJobs.dailyDigestJob()`：`@Scheduled(cron="0 0 8 * * ?")`（默认时区跟随容器 Asia/Shanghai）；遍历全部家庭（family 表规模为家庭数，量级小），逐家庭 try/catch 隔离；可测试入口 `digestForFamily(familyId, today)` 返回 `{sent, skipped, todayDue, overdue}`。
  - 计数：deletedAt IS NULL 且 status NOT IN (COMPLETED, CANCELLED)；today_due = dueDate = today；overdue = dueDate < today。**双零 → 跳过不推送**（不打扰）。
  - 接收人：`FamilyMemberRepository.findAllByFamilyIdAndLeftAtIsNull` → userIds → `WechatBindRepository.findAllByUserIdIn` → openids；无绑定 → skipped。
- **总结完成**：InternalAgentController C-20 `finishSummaryLog` 在 `summaryLockService.finish(...)` 成功且 req.status=success 后调 `wechatNotifyService.notifySummaryReady(familyId)`（家庭名/日期由服务内组装；finish DTO 不含家庭名）。失败状态不推。
- **任务指派**：任务创建落库后，`assigneeId != null` 且该 user 有绑定 → task_assigned（params：title、dueDate 或「无截止日期」）。落库点两处：`TaskServiceImpl.create`（手动/API）、`DirectTaskCreator`（AI/日常/一次性调度）；两处都在 save 之后调 `wechatNotifyService.notifyTaskAssigned(task)`。assigneeProfileId（无账号的老人档案）无绑定目标 → 自然跳过。
- 通知服务方法签名全部 `void`，内部吞异常（D6）。

### D6：失败隔离铁律（backend 侧）

- `WechatNotifyClient`：connect timeout 2s / read timeout 5s；任何 `RestClientException`/解析异常 → log.warn 计数，返回空结果，**绝不抛出**。
- `wechat.notify.enabled=false` 或 wechat-service base-url 未配置 → 客户端 no-op（直接返回空）。
- 钩子调用点不包业务事务语义：通知在事务提交后路径上执行（create/finish 方法内 save/finish 之后）；即使通知逻辑内部出错也被服务自身 catch，业务响应码不变。
- 测试必须断言：wechat-service 宕机（连接拒绝）时，任务创建仍 201、总结 finish 仍 200、早报 job 不抛出。

### D7：配置

| 位置 | 变量 | 默认 | 说明 |
|---|---|---|---|
| backend | `wechat.service-base-url` / env `WECHAT_SERVICE_BASE_URL` | http://wechat-service:8900 | infra-tier compose DNS |
| backend | `wechat.notify.enabled` / env `WECHAT_NOTIFY_ENABLED` | true | false 全链路 no-op |
| backend | `internal-api.secret` | 既有 | 复用 Change 5 内部密钥 |
| wechat-service | `WECHAT_TPL_TASK_DIGEST_ID` / `_SUMMARY_ID` / `_TASK_ASSIGN_ID` | 空 | 空 → 该事件 skipped 降级 |
| wechat-service | `H5_BASE_URL` | 既有（Change 11） | 模板跳转基址 |

compose：backend environment 加两项；wechat-service environment 加三项（`${WECHAT_TPL_...:-}`）；.env.example 段5 追加（注释空模板 + 测试号建模板说明）。

### D8：不做的事

- 无 DB 迁移（wechat_bind 已够用；不取关状态不入库——43004 仅日志，取关后重新关注 openid 不变）。
- 无前端改动（/tasks、/dashboard 均为既有路由，微信内打开走 Change 11 免密）。
- agent-service 不动（backend 自 @EnableScheduling，早报 cron 直接放 backend，与 TaskSchedulerJobs 同构）。
- 不引入 @Async/事件总线（同步调用 + 短超时足够；线程模型简单可测）。

### D9：测试策略

- **wechat-service**（pytest + MockTransport，沿用 change11 conftest）：
  - test_template.py：三事件渲染（template_id 取自 settings、字段中文、url 按事件区分）；批量端点鉴权 403；未知 event 单条 ok=false；未配置 template_id → skipped=true 不调微信；errcode=0 全 ok；43004 不重试（微信 HTTP 调用恰好 1 次）；40001 刷新重试后成功（token 端点 2 次、send 2 次）；微信 500 传输错误单条隔离不 500。
- **backend**（JUnit + Mockito/MockRestServiceServer + H2）：
  - `WechatNotifyClientTest`：MockRestServiceServer 验证路径/密钥头/批量 body；连接异常/500 → 不抛出返回空；enabled=false no-op 零 HTTP。
  - `WechatNotifyServiceTest`（Mockito 仓库 + mock client）：接收人解析（绑定/未绑定/已退出成员）；digest 计数正确（今日/逾期/完成/已删排除）；双零跳过；summary_ready 组装家庭名日期；task_assigned 仅 assigneeId 有绑定才发。
  - `WechatNotifyJobsTest`：digestForFamily 逐家庭隔离（一家抛错不影响下家）。
  - 钩子接线（@SpringBootTest + MockRestServiceServer 打 wechat-service）：C-20 finish success → 收到 summary_ready；finish failed → 不发；任务创建带 assignee 且绑定 → task_assigned；wechat-service 连接拒绝时创建任务仍 201。
- 四命令全绿 + build；Java 用例增量预计 +12~16。

### D10：风险与真机待验

- 测试号模板字段顺序/个数必须与 D3 表一致（建模板时手工对齐），否则微信返回 47003 类错误——runbook 提供模板正文范例。
- 测试号模板消息仅发给已关注用户；43004 日志在真机确认取关路径。
- 08:00 cron 时区依赖容器 TZ=Asia/Shanghai（compose 已设）。
- 模板消息有频率限制（测试号宽松），早报每家庭每日 1 条 + 总结 1 条 + 指派实时，量极小。

## Migration Plan

1. wechat-service 先上模板层（后端未接线时端点独立可测）。
2. backend 客户端/服务/jobs，开关默认开但 wechat-service 未配置模板 id 时全 skipped（安全灰度）。
3. 真机：测试号后台建 3 个模板 → env 填 id → 等 08:00 / 手动触发总结 / 建任务指派验证。

## Open Questions

- 早报时间固定 08:00（可后续改配置项；本期 cron 内联常量，与 02:00/03:00 job 同风格）。
