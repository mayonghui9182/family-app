# 每日独立迁移方案与验证

日期：2026-10-13。对应 personal-daily-remote-oauth 4.2；方案和隔离数据库验证完成，尚未接入独立候选应用，未部署。

## 迁移边界

| 迁移链 | 表及历史位置 | 本次候选 | 后续微信版本 |
|---|---|---|---|
| 主业务 | public / public.flyway_schema_history | 原 V1–V14 与原 afterMigrate 回调 | 顺序增加 V15、V16 |
| 每日 | daily_app / daily_app.flyway_schema_history | 独立 V1 | 保留不动 |
| OAuth | daily_oauth / 独立历史 | 既有授权服务管理 | 保留不动 |

每日表显式使用 daily_app 前缀，账号外键指向 public.users。按可信 user_id 限制查询；命名空间不替代用户权限。没有给既有 agent_reader 授予每日命名空间访问权。

迁移输入是 integrations/daily-migrations/V1__Personal_Daily_Planning.sql；当前放在非应用 classpath 目录，根工作树的 V17 暂不修改。候选必须排除主迁移 V17 和旧 V17 回退文件，禁止开启 outOfOrder、baselineOnMigrate、repair 或修改主业务历史。

## 候选装配必须完成的接线（4.3）

1. 将独立 V1 放入候选 db/daily-migration，只把原 V1–V14 与回调放入 db/migration；回退 SQL 不放在自动迁移目录。
2. 使用 Spring Boot 的 FlywayMigrationStrategy：先执行原主业务 Flyway，再使用同一数据源执行 daily_app 独立 Flyway。不要注册第二个 Flyway bean 而意外使默认迁移自动配置退出。任一步失败必须阻止应用启动。
3. 每日 SQL 明确使用 daily_app.daily_settings / daily_app.daily_records；主账号查询仍明确来自 public.users。同步测试夹具，验证启动时序，不能靠全局 search_path 隐式改变其他模块。
4. 结构迁移不依赖每日调度或远程读取开关；业务开关默认关闭。测试环境若禁用 Flyway，要提供对应每日命名空间夹具。
5. 更新候选输入清单，记录原输入和转换后摘要。根工作树原 V17 不能再次进入该发布线；将来合入微信前必须沿用独立每日布局并做兼容回归。如果其他环境已经执行 V17，不适用本方案，必须另做保留记录的数据搬迁，禁止直接删除历史。

## 隔离验证证据

运行 scripts/verify-daily-migration-history.py。使用保留运行 JAR 自带的 Flyway、JDBC 驱动和原 V1–V14/回调，新建随机 PostgreSQL 16 容器，仅随机回环端口，无业务卷、生产凭据或真实用户数据。账号是原迁移中的演示种子，记录为 fixture-only。

实际通过：

- V14 后建立每日 V1，主迁移版本和校验值不变，public 无每日表。
- 两条链重复迁移幂等；唯一约束、账号外键、版本更新冲突拒绝符合预期。
- 记录非空、仅个人设置非空时均拒绝删除每日结构。
- 将现有 V15/V16 加入主链后顺序执行成功，原 V1–V14 校验值及每日记录保留。
- 清空虚构每日数据后受限逆向成功；主库 V16 不变，独立每日 V1 可重建。
- 测试容器及附属匿名卷清理完成。

逐项结果、输入摘要和 PostgreSQL 镜像 ID 见 daily-migration-history-verification.json。此证据不代表候选应用启动、真实业务双库恢复或生产升级验收；这些属于 4.3/4.4。

## 回退边界

默认回退应用并保留每日结构和记录。rollback-empty.sql 仅用于停止每日写入的维护窗口：锁定两表及独立历史，验证仅 V1、两表为空，再事务内删除；不使用 CASCADE，也不修改 public 历史。已有设置或记录时必须拒绝。不要把清空夹具数据的测试步骤用于生产。
