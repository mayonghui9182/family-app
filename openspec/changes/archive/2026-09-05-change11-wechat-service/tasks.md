# Tasks: 微信公众号入口（Change 11）

> 执行顺序：Task 1 → Task 2 → Task 3 → 收尾（强顺序依赖；每 Task 完成后对应测试全绿再进下一步）

- [x] **Task 1：backend V10 迁移 + 绑定域端点（Java）**
  - **Step 1：TDD 失败测试**
    - [x] V10MigrationTest：H2 + PG 真机 V9→V10 正向建表/索引；回滚脚本 DROP TABLE 后 schema 复原（2 用例，**含回滚策略验证**）
    - [x] WechatBindControllerTest：内部查绑定（命中/未绑定业务码）；中间票据绑定（成功写行+发 token / 密码错 40113 不写绑定且票据可重试 / 票据伪造失效 / 票据单次有效 / 同 openid 重复绑定幂等 / 票据签发）（8 用例；无密钥 403 由 InternalSecretInterceptor 既有测试覆盖）
    - [x] WechatLoginInternalTest：已绑定 openid 换取登录态，token 与密码登录同权访问 /api/tasks；未绑定 40115（2 用例）
  - **Step 2：V10 Flyway 迁移** `backend/src/main/resources/db/migration/V10__Add_Wechat_Bind.sql`
    - [x] wechat_bind 表：id/openid VARCHAR(64) NOT NULL/user_id FK users ON DELETE CASCADE/bound_at TIMESTAMPTZ DEFAULT now()
    - [x] UNIQUE(openid) + idx_wechat_bind_user(user_id)；文件尾部附回滚段（DROP TABLE，供真机逆向验证）
  - **Step 3：Java 实现**
    - [x] 新包 `com.familyapp.wechat`：WechatBind(实体)/Repository/WechatService/WechatInternalController（内部端点：GET bind?openid、POST ticket、POST login）+ WechatAuthController（公开 POST /api/auth/wechat-bind，绑定页浏览器直调）+ 一次性票据服务 WechatTicketService（TTL 10 分钟单次有效 CAS，进程内存）
    - [x] ResultCode 追加 WECHAT_NOT_BOUND(40115, HTTP200 流程态) / WECHAT_TICKET_INVALID(40116) 中文文案；X-Internal-Secret 校验复用现有内部守卫
  - **Step 4：验证** `./gradlew test` 全绿 —— **242/0/0**（baseline 230 + 新增 12 用例）
  - [ ] **Step 5：Commit** `feat(change11/task1): V10 wechat_bind + 绑定域内部端点 + 票据服务`（用户未要求提交，暂不 commit）

- [x] **Task 2：wechat-service 骨架（Python FastAPI）**
  - **Step 1：TDD 失败测试（pytest，全程 mock 微信 HTTP）**
    - [x] test_signature.py：token/timestamp/nonce 字典序 SHA-1 匹配返回 echostr；不匹配 403（2 用例）
    - [x] test_oauth.py：/wechat/authorize 302 微信授权 URL 且 state 随机；code 换 openid→已绑定 302 H5(fragment token)；未绑定→302 绑定页(ticket，openid 不入 URL)；state 伪造/复用拒绝；微信 errcode 降级中文页；healthz（7 用例）
    - [x] test_token_manager.py：10 并发单飞仅 1 次 HTTP；<300s 主动续期；刷新失败保留旧值+60s 退避；首次失败抛错（4 用例）
    - [x] test_menu.py：setup-menu 先取 token 再调 cgi-bin/menu/create，errcode=0 + 菜单体断言；无密钥 403（2 用例）
    - [x] test_backend_client.py：X-Internal-Secret 携带与 403 透传（1 用例）
  - **Step 2：实现**
    - [x] `wechat-service/app/`：main.py(create_app 可注入 + healthz)、settings.py(WECHAT_APP_ID/SECRET/TOKEN SecretStr/OAUTH_CALLBACK_BASE/H5_BASE_URL/BACKEND_BASE_URL/INTERNAL_API_SECRET)、wechat/signature.py、wechat/oauth.py(授权 URL+code 换 openid)、wechat/token_manager.py(asyncio.Lock 单飞+300s 续期+失败退避)、wechat/menu.py、connectors/backend_client.py(httpx+密钥头)、routes/wechat_routes.py(验签 GET/POST 占位 + authorize + oauth/callback 分流 + 一次性 state)、routes/internal_routes.py(setup-menu 密钥守护)
    - [x] `wechat-service/Dockerfile`(8900+healthcheck) + `pyproject.toml`(fastapi/uvicorn/httpx/pydantic-settings，pytest/ruff/mypy strict，cov≥80) + `.env.example` + `.dockerignore`
  - **Step 3：验证** `python3 -m pytest -q` —— **16 passed，coverage 89.24%；ruff All checks passed；mypy strict 0 issues**
  - [ ] **Step 4：Commit** `feat(change11/task2): wechat-service 验签/OAuth/token单飞/菜单`（用户未要求提交，暂不 commit）

- [x] **Task 3：前端绑定流转 + compose/nginx 编排 + 联调验收**
  - **Step 1：TDD 失败测试（vitest）**
    - [x] WechatBindView.test.js：票据提交成功→存 token 跳首页；40113 显示后端中文错误且表单可重试；无票据→提示从菜单重进（3 用例）
    - [x] router：/wechat/bind 与 /wechat/callback 为 meta.public 例外，无 token 不被踢 /login（1 用例）
  - **Step 2：前端实现**
    - [x] `src/views/WechatBindView.vue`（老人友好大输入框/全宽按钮；无票据整页降级；40113 行内中文报错）；`src/views/WechatCallbackView.vue`（fragment token 承接：setToken 后 history.replaceState 立即清地址栏再跳首页）；`src/api/auth.js` 加 wechatBind；router 两条公开懒加载路由
  - **Step 3：编排**
    - [x] docker-compose.yml：wechat-service（public-tier+infra-tier、无宿主端口、WECHAT_* 环境变量、healthcheck）；`.env.example` 追加段5（WECHAT_APP_ID/WECHAT_APP_SECRET/WECHAT_TOKEN/WECHAT_OAUTH_BASE，注释掉的空模板）
    - [x] nginx/default.conf：`upstream wechat_upstream` + `location /wechat/` → wechat-service:8900（proxy 头与 /api 同款）；`docker compose config --quiet` 通过
  - **Step 4：联调验收（测试号真机）—— DEFERRED（用户侧，无微信凭证/隧道）**
    - [ ] 隧道域名配置回调 URL 验签通过；测试号后台配 OAuth 域名；菜单创建（POST /internal/setup-menu）；手机微信点菜单：已绑定免密进 H5、未绑定走绑定页；PC 浏览器回归确认 Change 9 布局不受影响
    - [x] 自动化四命令：`./gradlew test`(242/0/0) + wechat-service `pytest`(16 passed/89.24%) + agent-service `pytest`(85 passed) + `npx vitest run`(81/81) + `npm run build`(✓ 3.57s)
  - [ ] **Step 5：Commit** `feat(change11/task3): 绑定页 + compose/nginx 编排 + 测试号联调`（用户未要求提交，暂不 commit；真机联调后再随收尾提交）

- [x] **收尾：Change 11 验收 + handoff 交接**
  - [x] Step 1：验收清单逐条贴证到 change 目录 check_list.md（验签/OAuth 分流/绑定幂等/token 单飞/菜单/四命令全绿；28 项代码证据 + 第五节 DEFERRED 真机清单）
  - [x] Step 2：Runbook 写入 `.handoffs/current.md`：隧道域名变更后测试号两处重配步骤（服务器配置验签 URL + 网页授权域名）、建菜单 curl、真机点菜单验收；下一步指向 Change 12（模板消息推送）。**change 不归档**（真机联调 pending 用户侧）
  - Step 3：用户确认后走 openspec-archive
