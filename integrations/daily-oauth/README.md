# 独立每日 OAuth 授权服务

Java 17，Spring Boot 3.5.12 与 Spring Authorization Server 1.5.8。与主应用独立构建；读取同一应用账号的必要认证字段，在独立 Schema 存储授权。默认不启动、不修改主应用登录令牌。

构建：项目根目录执行 `backend/gradlew -p integrations/daily-oauth test installDist --no-daemon`。

配置、发布范围、协议验证和回滚见 [运行说明](../../docs/daily-oauth-runbook.md)。当前只完成本机隔离验收，未部署公网或注册真实 ChatGPT 连接。

只支持一个实例；不要把测试配置或测试账号种子用于现有业务数据库。框架授权表来源为 Spring Authorization Server 1.5.8（Apache-2.0），PostgreSQL 适配仅在未部署的本变更内完成。
