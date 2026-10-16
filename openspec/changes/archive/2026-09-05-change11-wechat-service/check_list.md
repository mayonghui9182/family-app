# Check List：微信公众号入口（Change 11）

> 状态约定：代码侧全部 TDD 完成并贴证；真机/测试号联调为 DEFERRED（用户侧，
> 无微信凭证与公网隧道），runbook 见 `.handoffs/current.md`。

## 一、backend 绑定域（Task 1，Java）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 1 | V10 迁移建 wechat_bind（openid NOT NULL/UNIQUE、user_id FK CASCADE、idx_wechat_bind_user、回滚段） | ✅ | `V10MigrationTest` 2 用例（正向建表+行为级唯一约束/FK 验证；DROP TABLE 回滚）；V10.sql 尾部回滚注释段 |
| 2 | 内部查绑定 GET /api/internal/wechat/bind?openid | ✅ | WechatBindControllerTest 用例 1/8（未绑定 40115 业务码 HTTP200；命中返回 userId） |
| 3 | 一次性票据：TTL 10 分钟、单次有效、32hex、进程内存 | ✅ | WechatTicketService（CAS consume + isValid 只读校验）；用例 2/5/6 |
| 4 | 公开绑定页端点 POST /api/auth/wechat-bind（不走 X-Internal-Secret） | ✅ | WechatAuthController；用例 3（成功写绑定行+返回 token） |
| 5 | 密码错 40113、不写绑定行、票据未消费可重试 | ✅ | 用例 4（先 401 断言后同票据重试成功） |
| 6 | 票据伪造/失效 40116；票据单次有效 | ✅ | 用例 5（40116）、用例 6（第二次 40116） |
| 7 | 同 openid 重复绑定幂等不产生重复行 | ✅ | 用例 7（二次后 COUNT(*)=1） |
| 8 | 已绑定 openid 免密换 token，token 与密码登录同权 | ✅ | WechatLoginInternalTest 用例 1（换 token 后访问 /api/tasks 成功）；未绑定 40115 用例 2 |
| 9 | 内部端点无密钥 403 | ✅（既有守卫覆盖） | InternalSecretInterceptor 已有测试守护 /api/internal/**；新端点前缀自动纳入 |
| 10 | 错误码中文文案 | ✅ | WECHAT_NOT_BOUND(40115)/WECHAT_TICKET_INVALID(40116) 入库 ResultCode |

## 二、wechat-service（Task 2，Python FastAPI）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 11 | 服务器配置验签：字典序 SHA-1，匹配返回 echostr，不匹配 403 | ✅ | test_signature.py 2 用例 |
| 12 | /wechat/authorize 302 微信授权页，state 随机 | ✅ | test_oauth.py 用例 1（两次 state 不同） |
| 13 | 已绑定 → 302 H5，token 在 URL fragment（不进 query/日志） | ✅ | test_oauth.py 用例 2（`#token=`，query 无 token） |
| 14 | 未绑定 → 302 绑定页 ?ticket=，openid 不入 URL | ✅ | test_oauth.py 用例 3（URL 无 openid） |
| 15 | state 伪造/复用拒绝 + 微信 errcode 降级中文页 | ✅ | test_oauth.py 用例 4/5/6（400 中文页；502 中文页） |
| 16 | access_token 单飞（10 并发 1 次 HTTP） | ✅ | test_token_manager.py 用例 1（calls==1） |
| 17 | <300s 主动续期；刷新失败保留旧值+60s 退避；首次失败抛错 | ✅ | test_token_manager.py 用例 2/3/4 |
| 18 | 菜单创建先取 token 再调 cgi-bin/menu/create，errcode=0 | ✅ | test_menu.py 用例 1（菜单体含两按钮） |
| 19 | setup-menu 无密钥 403 | ✅ | test_menu.py 用例 2（40300） |
| 20 | backend 连接器携带 X-Internal-Secret，403 透传 | ✅ | test_backend_client.py 1 用例 |
| 21 | 消息推送 POST 占位（验签后空串） | ✅ | routes/wechat_routes.py `wechat_message`（Change 12 模板消息再接） |

## 三、前端与编排（Task 3 代码部分）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 22 | /wechat/bind 绑定页：成功存 token 跳首页 | ✅ | WechatBindView.test.js 用例 1（localStorage + 路由 /） |
| 23 | 40113 显示中文错误且表单可重试；无票据整页降级引导菜单重进 | ✅ | 用例 2/3 |
| 24 | /wechat/callback fragment 承接：存 token 后清地址栏 | ✅ | WechatCallbackView.vue（replaceState 清 hash） |
| 25 | /wechat/bind 与 /wechat/callback 为登录守卫例外 | ✅ | wechatRoutes.test.js 1 用例（meta.public） |
| 26 | compose 编排：public+infra 双网段、无宿主端口、healthcheck | ✅ | docker-compose.yml wechat-service；`docker compose config --quiet` 通过 |
| 27 | nginx /wechat/ → wechat-service:8900 | ✅ | nginx/default.conf upstream + location |
| 28 | .env.example 微信变量组 | ✅ | 段5（APP_ID/SECRET/TOKEN/OAUTH_BASE，空模板注释） |

## 四、自动化门禁（四命令）

| 命令 | 结果 |
|------|------|
| `backend: ./gradlew test` | ✅ **242 passed / 0 failures / 0 errors**（baseline 230 + 新 12） |
| `wechat-service: python -m pytest -q` | ✅ **16 passed，coverage 89.24%**（≥80） |
| `agent-service: python -m pytest -q`（回归） | ✅ **85 passed**（无影响） |
| `frontend: npx vitest run` | ✅ **81/81 passed**（20 files；77+4 新） |
| `frontend: npm run build` | ✅ built in 3.57s |
| wechat-service `ruff check` / `mypy --strict` | ✅ All checks passed / 0 issues |

## 五、DEFERRED（真机/测试号，用户侧）

- [ ] 隧道（cpolar 等）域名在微信测试号后台两处配置：服务器配置 URL `<基址>/wechat/callback`（Token 与 WECHAT_TOKEN 一致）、网页授权域名
- [ ] 验签通过（微信后台点「提交」）
- [ ] 调 `POST /internal/setup-menu`（带 X-Internal-Secret）创建菜单
- [ ] 真机：已绑定点菜单免密进 H5（fragment token）；未绑定走绑定页，密码错可重试
- [ ] PC 浏览器回归确认 Change 9 移动布局不受影响
- 操作细节见 `.handoffs/current.md` Runbook
