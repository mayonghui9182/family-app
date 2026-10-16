# 独立每日候选：装配与重建

2026-10-13，4.3 完成，尚未部署。当前候选目录：`.volumes/daily-candidates/candidate-3c1sghxc`。

## 来源与转换

执行 `python3 scripts/assemble-daily-candidate.py`。脚本先核验 daily-release-source-manifest.json 全部输入摘要，任何漂移均停止；每次创建新的随机候选目录，不覆盖旧候选或原应用。只复制允许文件，不复制 .env、业务卷、备份或登录凭据。

537 个输出文件及输入/输出摘要见 daily-candidate-assembly.json，候选自身另存 candidate-manifest.json。生成的迁移配置记录装配脚本摘要；独立 SQL 记录输入摘要。候选前端编译产物不从旧 dist 复制。

- FamilyService 仅去掉新微信会话删除钩子，保留原家庭锁及空家庭退出。
- 去掉绑定仓储的会话锁查询和通知客户端的详细 outbox 方法，旧微信接口保留。
- 通用任务创建校验保留，去掉微信确认批次入口及其专用分支。混合测试保留批次校验、事务回退、儿童默认值、通知提交可见性；去掉只属于已排除入口的固定状态测试。
- 每日查询改为显式 daily_app 表，新增主迁移完成后再执行每日迁移的启动策略，H2/PG 夹具同步转换。
- JAR 内实际检查不含会话包和主 V15/V16/V17，包含每日 V1 和启动策略。原迁移 V1–V14及回调按输入字节保留。

## 重建命令

以下命令仅在脚本返回的候选目录执行，不运行 compose up 或 flywayMigrate：

```bash
cd backend
./gradlew test bootJar --offline --no-daemon
cd ../frontend
npm ci --ignore-scripts --no-audit --no-fund
npm run build
npm run test:unit
cd ..
backend/gradlew -p integrations/daily-oauth test installDist --offline --no-daemon
```

Gradle 离线构建依赖现有本地缓存；新机器需先从依赖仓库获取对应依赖。npm 使用 package-lock 固定解析结果，本次因离线缓存缺少依赖而在线补齐，再在最终候选离线安装通过。没有上传项目或私人数据。

回到仓库执行 `python3 scripts/verify-daily-candidate-build.py`，核对候选源文件未被构建修改、原输入未漂移、测试结果及 JAR 内容，并记录构建产物摘要。

## 本轮证据

见 daily-candidate-build-verification.json：

- 后端编译、bootJar、测试通过：524 项，523 执行通过，1 项条件 PG 测试跳过。
- 前端构建通过；34 个测试文件、144 项测试通过。
- OAuth installDist、常规测试通过：24 项，12 执行通过，12 项条件 PG 测试跳过。
- 537 个候选源摘要及对应原输入复核通过。

首次候选 candidate-l89yxjxf 的 H2 测试在建 Schema 前清表，产生5个失败；已修正装配规则并生成当前新候选，全量重跑通过。旧候选保留作诊断，不作为发布来源。

## 下一步边界

4.4 仍需：实际候选 JAR 在独立 PostgreSQL 启动、执行条件 PG 测试；工作两服务隔离、旧微信/密码恢复和 OAuth/MCP 跨服务回归；实际业务结构双库备份恢复及升级。

4.5 才固定镜像与部署覆盖层。候选现有 Compose 是配置输入，仍含未随本次重建的 agent-service、wechat-service、tool-service 构建引用；不能直接整套 build/up，后续必须替换为经核验的保留镜像并明确发布单元。没有创建可直接上线的完整部署包，不把常规绿测等同上线验收。
