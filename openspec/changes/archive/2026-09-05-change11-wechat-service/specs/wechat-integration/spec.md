# Delta Spec: wechat-integration (Change 11 - 新能力)

> 新能力：微信公众号渠道接入（测试号形态）。承载微信协议适配与免密入口，业务身份归属 backend（见 family-mgmt delta）。

## Purpose

为 family-app 提供微信公众号入口能力：验证微信服务器回调、网页授权免密登录、openid 与 family-app 账号绑定、自定义菜单管理、access_token 生命周期管理。后续通知推送（Change 12）与消息交互（Phase 3）均构建在本能力之上。

## ADDED Requirements

### Requirement: wechat-service SHALL 验证微信服务器回调签名

wechat-service SHALL 在 GET 回调端点上按微信规范校验签名：将 `token`、`timestamp`、`nonce` 字典序排序后 SHA-1，与 `signature` 比对；校验通过 SHALL 原样返回 `echostr`，否则返回 403。POST 消息回调 SHALL 使用同一签名校验，未通过 SHALL 拒绝处理。

#### Scenario: 微信服务器配置回调 URL 时的 echo 验证
- **WHEN** 微信服务器 GET `https://<隧道域名>/wechat/callback?signature=...&echostr=abc&timestamp=...&nonce=...` 且签名与配置 token 匹配
- **THEN** wechat-service 返回响应体 `abc`（纯文本），微信后台配置保存成功

#### Scenario: 签名不匹配的伪造请求
- **WHEN** 任意客户端以不匹配的 signature 请求回调端点
- **THEN** 返回 HTTP 403，echostr 不被返回，业务逻辑不执行

### Requirement: OAuth 网页授权 SHALL 支持静默获取 openid 并流转登录

wechat-service SHALL 提供 OAuth 授权入口端点：302 重定向到微信 `snsapi_base` 授权地址（redirect_uri 为自身回调端点，state 随机防 CSRF）。微信回跳携带 code 后，wechat-service SHALL 调用微信 `sns/oauth2/access_token` 以 code 换取 openid；成功后 SHALL 按「openid 已绑定 → 直接签发 family-app 登录态 302 进 H5；未绑定 → 302 到前端绑定页」分流。state 校验失败 SHALL 拒绝流转。

#### Scenario: 已绑定用户点击菜单进入应用
- **WHEN** 已绑定 openid 的用户在公众号菜单点击「我的任务」，完成微信静默授权回跳
- **THEN** wechat-service 用 code 换得 openid，查绑定表得到 user_id，签发 family-app 登录 token，302 到 H5 任务页，用户全程无感知输入

#### Scenario: 未绑定用户首次进入
- **WHEN** 从未绑定的用户完成微信授权回跳
- **THEN** 302 到前端绑定页，URL 携带一次性中间票据（非明文 openid），由用户主动完成账号关联

#### Scenario: OAuth state 不匹配
- **WHEN** 回跳请求的 state 与会话中签发值不一致（如重放/伪造）
- **THEN** 流转终止并展示中文错误提示，不签发任何登录态

### Requirement: 账号绑定 SHALL 要求凭证确认且幂等

绑定动作（见 family-mgmt delta 的绑定端点）SHALL 要求用户提供 family-app 账号凭证（已有账号密码校验，或注册新账号）后建立 openid ↔ user_id 关系；同一 openid 重复绑定 SHALL 幂等返回成功；一个 openid SHALL 最多绑定一个 user_id。

#### Scenario: 已有用户绑定微信
- **WHEN** 用户在绑定页输入 family-app 邮箱与正确密码提交
- **THEN** backend 校验凭证后写入绑定关系，后续该 openid 免密登录直达 H5

#### Scenario: 重复绑定幂等
- **WHEN** 已绑定 openid=O→user=U 的用户再次走完绑定流程并提交同一账号
- **THEN** 返回成功且绑定表不产生重复行

### Requirement: 自定义菜单 SHALL 可由 wechat-service 管理

wechat-service SHALL 提供内部菜单管理端点（查询/创建），菜单项配置为「名称 + OAuth 入口链接」；创建时 SHALL 先获取全局 access_token 再调用微信 `cgi-bin/menu/create`。菜单配置（项与文案）SHALL 可通过环境变量/配置文件调整而无需改代码。

#### Scenario: 配置测试号菜单
- **WHEN** 通过内部端点提交菜单配置（首页 / 我的任务 / AI 拆解 三个 OAuth 链接项）
- **THEN** 微信 `cgi-bin/menu/create` 被调用且返回 errcode=0，用户公众号会话内可见新菜单

### Requirement: access_token SHALL 集中缓存并防并发刷新

wechat-service SHALL 将全局 access_token 缓存在进程内存中，并在过期前（预留安全余量 ≥300s）主动刷新；并发请求 token 时 SHALL 单飞（single-flight）确保同一时刻最多一次对微信的刷新调用（测试号配额 200 次/日，正确缓存下日耗 ≤12 次）。刷新失败 SHALL 保留旧值并按退避重试，SHALL NOT 因单次刷新失败导致服务不可用。

#### Scenario: token 过期前自动续期
- **WHEN** 服务运行超过一个 token 有效期且期间有零星调用
- **THEN** 微信 token 获取接口总调用次数 ≤ (86400 / (7200-300)) + 1 次/日

#### Scenario: 并发刷新单飞
- **WHEN** 10 个并发请求同时到达且缓存 token 已过期
- **THEN** 对微信的 token 刷新调用恰好发生 1 次，10 个请求拿到同一新 token

### Requirement: 内部调用 SHALL 沿用 X-Internal-Secret 双向校验

wechat-service 调用 backend 内部端点（校验绑定、创建绑定、换取登录态）SHALL 携带 `X-Internal-Secret`；backend SHALL 拒绝缺失或不匹配的内部调用。wechat-service 自身的菜单/推送内部端点 SHALL 同样校验该密钥，公网回调端点除外。

#### Scenario: 无密钥调用内部端点
- **WHEN** 不带 X-Internal-Secret 请求 wechat-service 或 backend 的内部端点
- **THEN** 返回 401/403 类错误，业务不执行
