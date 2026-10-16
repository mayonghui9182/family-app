# Delta Spec: wechat-integration (Change 14 - 微信私聊消息自动对接 AI 拆解)

> 出站模板消息能力（Change 12）与身份/渠道能力（Change 11）的**入站方向补齐**：`POST /wechat/callback` 从占位实现改为真实消息受理入口；OAuth 免密流转新增 `next` 深链并收敛为白名单；新增客服消息出站通道。

## ADDED Requirements

### Requirement: wechat-service SHALL 受理公众号文本消息并转交 backend，且在 5 秒内应答

wechat-service 的 `POST /wechat/callback` SHALL 从占位实现（验签后回空串）升级为真实受理：验签通过后解析明文 XML，仅对 `MsgType=text` 同步调用 backend `POST /api/internal/wechat/inbound-message`（携带 `X-Internal-Secret`，payload `{openid, msg_id, content, received_at}`），并按受理结果返回中文被动回复。整条同步链 SHALL 在微信 5 秒被动回复上限内完成，SHALL NOT 在回调线程内调用 LLM 或拆解服务。backend 不可达或超时 SHALL 返回空串并记录日志，SHALL NOT 向微信暴露 5xx（避免触发重推风暴）。非文本类型 SHALL NOT 转交 backend。

#### Scenario: 文本消息被受理
- **WHEN** 已绑定用户在公众号私聊发送「周六带爸去复查」
- **THEN** wechat-service 调用 backend 受理端点恰好 1 次，并在 5 秒内返回中文被动回复

#### Scenario: 事件推送不受理
- **WHEN** 微信推送 `MsgType=event`（订阅、取关、菜单点击）
- **THEN** 不调用 backend 受理端点，返回空串，日志记录事件类型

#### Scenario: 非文本消息给出可读引导
- **WHEN** 用户发送图片或语音
- **THEN** 返回「目前我只能看懂文字，请直接打字发给我」，不产生落库

#### Scenario: backend 故障不外泄
- **WHEN** backend 连接被拒绝
- **THEN** 回调返回 HTTP 200 + 空串，仅记录 error 日志，微信侧不看到 5xx

### Requirement: 客服消息 SHALL 复用集中 token 缓存并提供批量内部端点

wechat-service SHALL 提供 `POST /internal/kefu/send`（`X-Internal-Secret` 守护），接受 `{messages:[{openid, content}]}`，逐条调用微信 `cgi-bin/message/custom/send` 并返回逐条结果 `{results:[{openid, ok, errcode?, errmsg?}]}`。单条失败 SHALL 被隔离且端点整体 SHALL NOT 返回 5xx（请求体格式错误返回 400）。客服消息 SHALL 复用既有 `WechatTokenManager` 的进程内单飞缓存，SHALL NOT 为每条消息单独获取 access_token。

#### Scenario: 批量发送逐条隔离
- **WHEN** 提交 3 条客服消息，其中 1 条 openid 已取关（43004）
- **THEN** 返回 200，`results` 含 3 项，2 项 `ok=true`、1 项 `ok=false`

#### Scenario: token 失效单次重试
- **WHEN** 首次发送返回 errcode=40001
- **THEN** 强制刷新 token 后重试一次，成功则该条 `ok=true`

#### Scenario: 同批复用同一 token
- **WHEN** 一批 5 条消息在同一进程内发送且 token 未过期
- **THEN** 对微信 token 端点的调用次数 ≤ 1

## MODIFIED Requirements

### Requirement: OAuth 网页授权 SHALL 支持静默获取 openid 并流转登录

wechat-service SHALL 提供 OAuth 授权入口端点：302 重定向到微信 `snsapi_base` 授权地址（redirect_uri 为自身回调端点，state 随机防 CSRF）。微信回跳携带 code 后，wechat-service SHALL 调用微信 `sns/oauth2/access_token` 以 code 换取 openid；成功后 SHALL 按「openid 已绑定 → 直接签发 family-app 登录态 302 进 H5；未绑定 → 302 到前端绑定页」分流。state 校验失败 SHALL 拒绝流转。

**（Change 14 新增）** 授权入口 SHALL 接受 `?next=<路径>` 并在 state 中暂存该目标；免密成功 302 时 SHALL 以 URL fragment 追加方式回传 `next`（形如 `{h5}/wechat/callback#token=…&next=/agent/draft/42`），token 与 `next` SHALL 都只进 fragment 不进查询串。`next` SHALL 经开放重定向白名单校验：仅允许以单个 `/` 开头（拒绝 `//`、协议相对与绝对 URL）且命中服务端已知路由前缀白名单；不合规时 SHALL 丢弃 `next` 并保持原有落地行为，SHALL NOT 报错中断免密流转。未绑定分支 SHALL 同样保留 `next` 以便绑定完成后回到目标页。

#### Scenario: 从微信链接直达草稿详情页
- **WHEN** 已绑定用户点击带 `?next=/agent/draft/42` 的授权链接并完成静默授权
- **THEN** 302 到 `{h5}/wechat/callback#token=<jwt>&next=/agent/draft/42`，`openid` 与 token 不出现在任何查询串中

#### Scenario: 开放重定向尝试被拒
- **WHEN** 授权入口携带 `?next=//evil.com` 或 `?next=https://evil.com`
- **THEN** `next` 被丢弃，用户仍正常免密落到 H5 首页，不跳转到任何外部地址

#### Scenario: 未知前端路由不作为 next
- **WHEN** `next` 为白名单前缀之外的路径（如 `/admin/secrets`）
- **THEN** `next` 被丢弃并落默认首页，行为与不带 `next` 时一致

#### Scenario: 既有免密行为不变
- **WHEN** 用户从公众号菜单进入且未携带 `next`
- **THEN** 302 结果与本 change 之前逐字一致（`{h5}/wechat/callback#token=…`）
