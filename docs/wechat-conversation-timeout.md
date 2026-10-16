# 微信任务整理超时配置与排查

2026-10-15 已应用并验证。微信公众号文字进入可靠会话队列后，由 backend 调用 agent-service 整理候选；用户明确确认后才创建待办。

## 原因与处理

- 容器中火山域名曾解析到 Fake-IP，TLS 多次约五秒失败。Clash Verge 当前订阅的合并配置追加精确域名 `ark.cn-beijing.volces.com` 到 `dns.fake-ip-filter`，规则覆写置顶 `DOMAIN,ark.cn-beijing.volces.com,DIRECT`。保留其他规则和 DNS 设置，热加载并清理 DNS/Fake-IP 缓存。该配置针对现有 blacklist 模式，不能直接套用到 whitelist/rule 模式。
- SDK、LLM 包装层和后台消息重试叠加，挤占原整轮三十秒预算。现为独立会话预算，不影响其他 AI 功能。
- `json_object` 只保证 JSON，不保证业务字段。系统提示已加入实际输出 Schema，明确 `reply/tasks/slot_patch`；结果仍经严格类型及家庭引用校验。
- 后台过去只将消息标记 FAILED，缺少最终通知。现在失败状态和失败回复在同一事务提交；已切换家庭的旧消息不会向新家庭发送失败提示。

## 根目录 .env 的实际参数

```dotenv
CONVERSATION_TIMEOUT_SECONDS=35
CONVERSATION_LLM_CONNECT_TIMEOUT_SECONDS=8
CONVERSATION_LLM_READ_TIMEOUT_SECONDS=25
CONVERSATION_LLM_SDK_MAX_RETRIES=0
CONVERSATION_LLM_MAX_ATTEMPTS=1
CONVERSATION_LLM_THINKING=disabled
CONVERSATION_LLM_MAX_TOKENS=4096
WECHAT_CONVERSATION_MAX_ATTEMPTS=2
WECHAT_CONVERSATION_RETRY_INITIAL_MS=1000
WECHAT_CONVERSATION_RETRY_MAX_MS=3000
```

上述变量已通过根 Compose 注入。整轮预算上限四十秒，必须小于后端四十五秒读取时限；租约保持一百二十秒。禁止只提高单侧超时。读取超时是流读取间隔，整轮预算约束实际总耗时。

确定性请求/响应错误直接进入失败终态；连接或远端故障最多尝试两次。模型输出达到长度上限时明确失败，避免截断 JSON 被当成成功。关闭思考和限制输出仅作用于公众号任务会话；不同模型的参数兼容性应另行验证。

## 验证与恢复

- Python 138 项通过；Ruff/Mypy 通过。后端全量 701 项通过、4 项跳过。
- 线上实际会话类以虚构输入调用火山：短文 1.30 秒、18 条长文 7.79 秒，均通过 Schema 和引用校验。独立候选容器分别 2.26/4.86 秒。
- 线上容器 DNS 返回公网地址；连续六次独立 TLS/HTTP 连接耗时 0.07–0.16 秒，未认证请求返回预期 401。重载后的首次模型探测曾有一次连接失败，后续通过，不能据小样本承诺绝无网络波动。
- 只更新 backend、agent-service，所有服务健康，其余容器未变。没有重放历史日报，没有模拟真实微信入站或主动向用户发送测试消息；微信端真实收发仍需重新发一条消息验证。
- 发布镜像及回滚路径见本机 `.volumes/wechat-timeout-current.json`。受限目录 `.volumes/wechat-timeout-release` 保存原环境文件、原镜像引用、验证结果；代理恢复点见 `.volumes/wechat-proxy-change-current.json`。其中含私有配置，不应提交或上传。
- 回滚时使用原环境文件与 rollback.json，仅恢复 backend/agent-service；代理按记录的备份文件逐一恢复并热加载。历史 FAILED 消息不会自动重新处理，避免重复候选或重复任务。

## 补充：完全无回复时先检查公网入口

本次模型修复后用户的新消息没有到达应用，当前家庭最新入站仍为17:08。cloudflared进程虽active，连接目标却解析成Fake-IP并持续QUIC超时。已给两个隧道域名配置精确排除/直连并重启现有隧道；实际连接4条、ready200，公网只读微信GET验签200且回显匹配。公开探测仍有间歇超时，微信真实收发等待用户再次发送验证。未将程序健康或单次成功当成全链路验收。

Cloudflare官方说明隧道使用region1/region2.v2.argotunnel.com与7844端口，见[隧道网络要求](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/)。本机恢复材料见`.volumes/wechat-tunnel-repair-current.json`。

## 补充：候选清单及跨午夜日期

10-16 02:44微信实发一次处理成功、候选已保存，但旧版仅发送模型reply开场白，遗漏结构化tasks；同时未给模型可信日期基准，明天误记为10-16。已补后端确定性候选清单与带版本确认指令，预览与出站同源；长清单按UTF-8分段并单段幂等。接收时间received_at进入会话输入，以Asia/Shanghai计算相对日期。

后端704项通过/4跳过，Python139项通过；线上虚构模型实测明天为2026-10-17、2.16秒。发布/恢复记录见`.volumes/wechat-preview-current.json`。旧候选不自动改写，用户应先发送明确修改日期的消息，核对新清单再确认创建。

## 补充：分类追问与当前版本确认

10-16实发多轮后，第3版仍在但会话被ASK置为ACTIVE，旧确认入口错误拒绝。现已支持在ACTIVE中明确“确认创建 当前版本号”，保留版本、权限、到期、不可变快照和创建幂等校验；无版本、旧版、过期、取消、暂停仍拒绝。分类目录查询改为family_id范围内本地读取，空目录如实提示，不依赖模型，不新建分类或改候选。

后端712通过/4跳过；仅backend更新。恢复记录见`.volumes/wechat-followup-current.json`。旧失败确认不自动重放，由用户核对原当前清单后重新明确确认。外部模型仍可能连接失败，不能将本地路径修复表述为外部网络已完全稳定。
