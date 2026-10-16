# Proposal: Change 11 - 微信公众号入口（wechat-service + OAuth 绑定）

## Why

家庭成员日常停留在微信，family-app 目前没有任何消息触达通道，也没有免密入口：用户必须记域名、输账号密码。通过公众号测试号提供「菜单点击 → 免密进入 H5」的入口，是降低家庭使用门槛（尤其老人）的关键一步；同时为后续微信通知推送（Change 12）建立 openid 身份通道。

## What Changes

- 新增独立微服务 `wechat-service`（Python FastAPI，与 agent-service / tool-service 同构：infra-tier 不暴露宿主端口，`X-Internal-Secret` 双向校验）：
  - 微信协议适配层：回调 URL 验签（GET echostr）、access_token 生命周期管理（集中缓存，测试号配额 200 次/日）
  - OAuth 网页授权（snsapi_base 静默）：code 换 openid → 查绑定 → 签发 family-app JWT → 302 进 H5
  - 自定义菜单管理接口（测试号菜单配置：首页 / 我的任务 / AI 拆解 → OAuth 入口链接）
- backend（Java）新增：
  - `wechat_bind` 表（openid ↔ user_id，用户域归属 backend，V10 Flyway 迁移）
  - 绑定流程端点：未绑定用户跳转绑定页（关联已有账号 / 注册新账号后自动绑定）
- nginx 新增 `/wechat/` location 路由到 wechat-service（公网回调唯一入口仍走 nginx :80）
- docker-compose 新增 `wechat-service` 服务（public-tier + infra-tier 双网络）与环境变量（测试号 appid/secret/token、OAuth 回调域名）
- 前端新增微信回调承接页与账号绑定页（移动端样式，依赖 Change 9 适配后的布局体系）

## 非目标 (Non-goals)

- 不做被动消息交互与 AI 对话（Phase 3，后续 change）
- 不做模板消息 / 客服消息推送（Phase 2，Change 12）
- 不申请正式公众号（个人主体无服务号资质；测试号长期使用：关注上限 100 人，家庭场景足够）
- 不引入 Redis（wechat-service 单实例内存缓存 token 即可；多服务共享时再引入）
- 不引入消息队列（本期无推送场景，推送在 Change 12 按需评估）
- 不做 JS-SDK（分享 / 地理位置 / 拍照）
- 不做 unionid 与多应用身份打通（单测试号场景 openid 即可）

## Capabilities

### New Capabilities

- `wechat-integration`：微信渠道接入——回调验签、OAuth 免密登录、账号绑定、菜单管理、access_token 管理

### Modified Capabilities

- `family-mgmt`：认证域增量——支持 openid ↔ user_id 绑定关系（`wechat_bind`）与微信 OAuth 免密登录签发 JWT

## Impact

- 新增：`wechat-service/`（Python FastAPI + Dockerfile + tests）
- backend：`db/migration/V10__Add_Wechat_Bind.sql`、auth 域新增绑定/免密端点、`SecurityConfig` 放行清单（OAuth 回调地址）
- nginx：`default.conf` 新增 `/wechat/` location
- docker-compose.yml：新增 wechat-service 服务与 `WECHAT_*` 环境变量（`.env.example` 同步）
- 前端：新增微信回调页 / 绑定页 2 个视图 + 路由（requiresAuth 例外处理）
- 开发环境依赖：内网穿透隧道（固定域名，如 Cloudflare Tunnel）承载微信回调
- 数据流说明：本期不涉及 AI 模块；无推送链路（Change 12 引入）
