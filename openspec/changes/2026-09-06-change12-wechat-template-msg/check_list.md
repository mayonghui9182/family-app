# Check List：微信模板消息推送（Change 12）

> 状态约定：代码侧全部 TDD 完成并贴证；真机/测试号联调为 DEFERRED（用户侧，
> 无微信凭证与公网隧道），runbook（含 3 个模板正文范例）见 `.handoffs/current.md`。

## 一、wechat-service 模板消息层（Task 1，Python）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 1 | settings 三模板 id（task_digest/summary/task_assign）默认空串 | ✅ | `app/settings.py`；test_template.py `tpl_settings()` 全量置位 |
| 2 | task_digest 渲染：first「今日家庭任务早报」、keyword1=`今日到期 N 件`、keyword2=`逾期 M 件`、remark 点击查看，url=`{h5}/tasks` | ✅ | test_template.py digest 渲染用例（断言 url 与中文字段） |
| 3 | summary_ready 渲染：keyword1=家庭名、keyword2=日期，url=`{h5}/dashboard` | ✅ | summary 渲染用例 |
| 4 | task_assigned 渲染：keyword1=任务标题、keyword2=截止日期；无截止日期兜底「无截止日期」，url=`{h5}/tasks` | ✅ | assign 渲染用例 + 未配置降级用例内断言兜底文案 |
| 5 | 未知 event → ValueError，零微信调用，单条 ok=false 不影响同批 | ✅ | unknown event 用例 |
| 6 | 模板 id 未配置 → skipped=true 静默跳过，同批其他消息正常发送 | ✅ | 未配置降级用例（一条 skipped 一条 send） |
| 7 | POST `/internal/template/send`：无 X-Internal-Secret → 403/40300 | ✅ | 403 用例 |
| 8 | 畸形 body / 错误形状 → 400/40000（ValueError/TypeError/KeyError/AttributeError 全捕获） | ✅ | malformed body 用例 |
| 9 | token 失效 errcode 40001/42001 → 强制 refresh 后重试一次（token 端点 2 次、send 2 次、TOKEN-1→TOKEN-2） | ✅ | 40001 refresh retry 用例 |
| 10 | 用户拒收/未关注 errcode 43004/43010 → ok=false 不重试（send 恰 1 次） | ✅ | 43004 no-retry 用例 |
| 11 | 微信 500/传输异常 → 单条隔离（errmsg 含「网络异常」），批量端点仍 200 code=0 | ✅ | WeChat 500 隔离用例；WechatApiError 逐条 catch |
| 12 | 批量共享一个 WechatTokenManager（单批不重复取 token） | ✅ | internal_routes.py 批级单例；mock 记录 calls 断言 |

## 二、backend 客户端 + 服务 + 接收人解析（Task 2，Java）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 13 | WechatNotifyClient：POST `{baseUrl}/internal/template/send`，带 X-Internal-Secret，批量 body 序列化 | ✅ | WechatNotifyClientTest（MockRestServiceServer 断言路径/头/body） |
| 14 | 连接拒绝（127.0.0.1:9）/500/非 2xx/空 body → 不抛出，warn 日志，返回空 results | ✅ | WechatNotifyClientTest 连接失败 + 500 用例 |
| 15 | `wechat.notify.enabled=false` 或消息空 → 零 HTTP 调用 no-op | ✅ | WechatNotifyClientTest disabled 用例 |
| 16 | 超时配置 connect 2s / read 5s（SimpleClientHttpRequestFactory） | ✅ | WechatNotifyClient @Autowired 构造器 |
| 17 | digest 计数：今日到期/逾期 derived query；COMPLETED/CANCELLED/deletedAt 排除；双零 → 不发送 | ✅ | WechatNotifyServiceTest 计数 + 双零用例 |
| 18 | 接收人解析：仅家庭未退出成员（leftAt null）且 wechat_bind 有 openid；未绑定/已退出不收 | ✅ | WechatNotifyServiceTest 绑定/未绑定/退出成员用例 |
| 19 | summary_ready：params 含 family_name（家庭名，缺省「我的家庭」）+ date=当天；全程吞异常 | ✅ | WechatNotifyServiceTest summary 用例 |
| 20 | task_assigned：task null/assigneeId null/无绑定 → 不发；params title + due_date | ✅ | WechatNotifyServiceTest assign 用例（null 守卫 + 绑定断言） |
| 21 | WechatBindRepository.findAllByUserIdIn / findByUserId；TaskRepository 两计数查询 | ✅ | 仓库新增方法；服务测试 mock 验证调用 |
| 22 | application.yml：wechat.service-base-url / wechat.notify.enabled（env 可覆盖） | ✅ | application.yml wechat 段 |

## 三、三触发点接线 + 编排（Task 3）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 23 | WechatNotifyJobs `@Scheduled(cron="0 0 8 * * ?")` 08:00 遍历家庭，逐家 try/catch 隔离（家 2 抛错，家 1/3 仍执行） | ✅ | WechatNotifyJobsTest |
| 24 | C-20 finishSummaryLog status=success → notifySummaryReady；failed → 不发 | ✅ | WechatNotifyFlowTest summaryFinishSuccess/Failed 用例 |
| 25 | API 创建任务（/api/tasks）assignee 已绑定 → task_assigned（params title/due_date 透传） | ✅ | WechatNotifyFlowTest taskCreateWithAssignee 用例 |
| 26 | DirectTaskCreator（AI 拆解 C-3 建任务）save 后同样触发 notifyTaskAssigned | ✅ | DirectTaskCreator 构造器注入 + createFromTemplate 调用；DirectTaskCreatorTest 编译/通过 |
| 27 | wechat-service 宕机（client 抛 RuntimeException）→ finish 仍 code=0、建任务仍 code=0，业务零影响 | ✅ | WechatNotifyFlowTest clientThrowing 用例 |
| 28 | 早报 job 端到端计数：今日 2 / 逾期 1 / COMPLETED 排除，已绑定成员收到 task_digest | ✅ | WechatNotifyFlowTest digestJob 用例 |
| 29 | compose：backend 加 WECHAT_SERVICE_BASE_URL/WECHAT_NOTIFY_ENABLED；wechat-service 加三模板 id（`:-` 空默认） | ✅ | docker-compose.yml；`docker compose config --quiet` 通过 |
| 30 | .env.example（根 + wechat-service）段5 追加开关与三模板 id 注释模板 | ✅ | 根 .env.example 段5；wechat-service/.env.example |

## 四、自动化门禁（四命令）

| 命令 | 结果 |
|------|------|
| `backend: ./gradlew test` | ✅ **263 passed / 0 failures / 0 errors**（change11 baseline 242 + 新 21：client 5 + service 10 + jobs 1 + flow 5） |
| `wechat-service: python -m pytest -q` | ✅ **25 passed，coverage 89.71%**（≥80；16 baseline + 9 新） |
| `agent-service: python -m pytest -q`（回归） | ✅ **85 passed，coverage 95.19%**（零影响） |
| `frontend: npx vitest run` | ✅ **81/81 passed**（change12 前端零改动） |
| `frontend: npm run build` | ✅ built 成功（dist 产物正常） |
| wechat-service `ruff check` / `mypy app` | ✅ All checks passed / Success: no issues found in 14 source files |

## 五、DEFERRED（真机/测试号，用户侧）

- [ ] 微信测试号后台「模板消息」新建 3 个模板（正文范例与字段标题见 handoff runbook），拿到 3 个模板 ID
- [ ] `.env` 填 WECHAT_TPL_TASK_DIGEST_ID / WECHAT_TPL_SUMMARY_ID / WECHAT_TPL_TASK_ASSIGN_ID（WECHAT_NOTIFY_ENABLED 默认 true）
- [ ] 真机验证：完成一次每日总结 → 收 summary_ready（点跳 /dashboard）；建一条指派给自己的任务 → 收 task_assigned（点跳 /tasks）；等 08:00 或手动触发 → 收 task_digest
- [ ] 拒收路径：关注后取关触发一次，确认 43004 不重试、日志 ok=false 且业务无报错
- [ ] 模板 id 留空回归：不配置时三事件静默跳过，容器与业务正常
- 操作细节见 `.handoffs/current.md` Change 12 Runbook
