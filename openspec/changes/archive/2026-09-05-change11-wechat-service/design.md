# Design: Change 11 - 微信公众号入口（wechat-service + OAuth 绑定）

## Context

- 现有微服务拓扑：backend(Java:8080, public+infra 双网络) + agent-service/tool-service(Python, 仅 infra-tier, X-Internal-Secret 双向校验) + nginx(:80 统一入口)；compose 内无 Redis。
- 认证：自研 opaque token（64 hex，7 天，users.access_token），无 Spring Security 过滤器链；X-Access-Token + X-Family-Id Header 协议。
- 微信侧：公众号测试号（接口权限全量、access_token 配额 200 次/日、关注上限 100 人）；开发期回调依赖内网穿透隧道固定域名；测试号与服务号接口协议一致，仅 appid/secret 配置不同。
- 动机与非目标见 proposal.md。

## Goals / Non-Goals

**Goals:**
- 菜单点击 → OAuth 静默授权 → 已绑定免密进 H5 / 未绑定进绑定页的完整闭环
- 微信协议适配收敛在独立 wechat-service，backend 只增「绑定域」数据与端点
- token 获取次数压在配额内（集中缓存 + 单飞）
- 全链路可测试（不依赖真实微信即可跑单测）

**Non-Goals:**
- 被动消息/AI 对话、模板消息推送（后续 change）、Redis、消息队列、JS-SDK、unionid（见 proposal 非目标）

## Decisions

### D1: wechat-service 选型 Python FastAPI + 自写轻量协议层（不引 SDK）

**方案**：与 agent-service/tool-service 同构（FastAPI + httpx + pytest + Dockerfile）。微信协议面本期仅 4 个能力：SHA-1 验签、OAuth code 换 openid、菜单 JSON、access_token 获取——全部自写（约百行量级）。

**取舍**：Alternatives——① `wechatpy`（Python 生态但长期维护不活跃，引入后还要适配测试号行为）；② WxJava（成熟但要为此起一个 Java 服务或塞进 backend，违背独立适配层决策）。所需协议面小、稳定（微信这些接口多年未变），自写可控且依赖最少。

### D2: 绑定数据归属 backend，wechat-service 无库

**方案**：`wechat_bind` 表建在 backend（用户域 SSOT，与 users 同库同事务）；wechat-service 无数据库，经 X-Internal-Secret 调 backend 内部端点完成「查绑定/建绑定/换 token」。沿用 agent-service 的 connector 模式。

**取舍**：Alternatives——① wechat-service 自持绑定表（身份双份，漂移风险）；② 微信回调直连 backend（协议细节污染主服务，nginx 路由与密钥边界混乱）。D2 与现有分层心智一致：适配服务无状态，数据一律归主服务。

### D3: 未绑定用户经「一次性中间票据」进入绑定页，openid 不进前端 URL

**方案**：OAuth 回跳发现未绑定时，wechat-service 生成随机中间票据（TTL ≤10 分钟、单次有效，进程内存 Map 即可）并 302 到前端 `/wechat/bind?ticket=...`；绑定页提交 ticket + 账号凭证 → backend 校验凭证并经内部调用关联 openid。已绑定时同样先签发 family-app token 再 302，token 走 URL fragment（`#token=...`）避免进入服务器日志。

**取舍**：Alternatives——① openid 明文放 URL（泄露即被冒绑/冒登）；② 绑定页直接传 openid 给前端再回传（同风险）。票据方案多一次跳转但杜绝 openid 出网。

### D4: token 单飞 + 提前续期，内存缓存（无 Redis）

**方案**：access_token 缓存于 wechat-service 进程内；后台任务在剩余有效期 <300s 时刷新；并发未命中用 asyncio.Lock 单飞。测试号 200 次/日配额下，正确实现日耗 ≤12 次。

**取舍**：单实例部署下内存缓存足够；若未来 wechat-service 多副本或其它服务也要用 token，再引入 Redis（技术栈已规划），本期不建。

### D5: 网络拓扑与 nginx 路由

**方案**：
- wechat-service 加入 public-tier（收公网回调）+ infra-tier（调 backend），不映射宿主端口
- nginx 新增 `location /wechat/` → wechat-service（与 `/api/` 平行的第二个后端路由）
- 隧道（Cloudflare Tunnel 等）指向 nginx :80，微信后台只配一次隧道域名

**取舍**：Alternatives——隧道直连 wechat-service 绕过 nginx（失去统一入口与未来证书/TLS 收敛点）。所有外部流量仍从 nginx 走，与既有「对外端口收敛至 nginx」安全基线一致。

### D6: backend 侧放行与错误码

**方案**：OAuth 回调、绑定页为未登录可访问路由；backend 内部端点（查绑定/建绑定/换登录态）走现有 X-Internal-Secret 校验（复用 InternalAgentController 的守卫模式，新增 controller 而非混入 agent 域）；新增业务码：`WECHAT_NOT_BOUND`（未绑定分流）、`WECHAT_TICKET_INVALID`（票据失效），文案中文。

## Risks / Trade-offs

- [隧道域名变更导致回调/OAuth 域名失效] → 选固定域名隧道；Runbook 写入 handoffs：改隧道域名后仅需在测试号后台重配回调 URL 与 OAuth 域名两处
- [测试号被微信政策收紧（无 SLA）] → 本期协议层已隔离在 wechat-service；Change 12 引入 Notifier 渠道抽象，届时换渠道（企业微信/短信）只需新适配器
- [将来换正式号 openid 全变] → 绑定表以 user_id 为主，openid 仅是可重建的映射；重新扫码绑定即可，账号与业务数据零迁移
- [中间票据进程内存存储重启丢失] → TTL 仅 10 分钟，重启后用户从菜单重进即可；为 10 分钟场景引入 Redis 不值
- [POST 消息回调本期无人消费] → 验签端点先落地并返回空串（协议要求 5s 内响应），为 Phase 3 预留；不实现业务回复

## Migration Plan

1. backend V10 迁移（wechat_bind）：正向建表 + UNIQUE 索引；回滚 = DROP TABLE（Task 中真机验证正向+逆向）
2. compose 新增 wechat-service：`docker compose up -d wechat-service` 即可加入；回滚 = 移除 service 定义 + `docker compose up -d --remove-orphans`
3. nginx 加 location 属增量配置，回滚 = 移除该 location
4. 上线顺序：backend(V10) → wechat-service → nginx/compose → 测试号后台配置回调与菜单
