# 独立候选回归与双库恢复

2026-10-14，personal-daily-remote-oauth 4.4 完成。候选为 `.volumes/daily-candidates/candidate-3c1sghxc`；原应用源摘要未变化，生产环境未操作。

## 实际结果

| 验证 | 结果 | 证据 |
|---|---|---|
| 后端全量测试，包括真实 PostgreSQL 条件用例 | 524 项通过，无跳过 | daily-candidate-postgres-verification.json、候选 backend-postgres.log |
| OAuth 全量测试，包括受限角色真实 PostgreSQL | 24 项通过，无跳过 | 同上、候选 oauth-postgres.log |
| 候选 JAR 空库启动 | 健康 UP，主 V14、每日独立 V1，public 无每日表 | 同上、候选 postgres-startup.log |
| 前端独立构建及回归 | 上一阶段144项通过，源码未变，本轮不重复 | daily-candidate-build-verification.json |
| 工作服务真实 PostgreSQL/REST/MCP用户隔离 | 任务服务125、工作Agent103项通过 | 候选 work-ownership.log |
| MCP真实SDK及资源鉴权 | 9项通过 | 候选 mcp-tests.log |
| 浏览器授权→内省→MCP→个人撤销→重启 | 两用户通过；撤销后第一用户失效，第二用户授权保留 | 候选 oauth-e2e.log |
| 主/工作库真实结构备份恢复升级 | 21/8表恢复内容摘要一致，升级和恢复后写入通过 | daily-candidate-restore-verification.json |

旧微信绑定、入站、草稿、通知等及 PasswordRecoveryTest 已纳入候选后端全量测试；不含已排除的新微信会话功能。后端测试数量低于混合工作树原701项，原因是候选排除新会话代码及其专项测试，不是跳过失败测试。

OAuth/MCP跨服务使用候选OAuth安装产物和候选MCP源码，账号及记录均为虚构。该跨服务脚本的业务后端仍是模拟接口；真实候选后端的身份、每日读取及迁移另由后端测试和真实PG启动覆盖。没有把它声称为真实ChatGPT或公网端到端验收。

## 备份恢复范围

新建随机 PostgreSQL 容器内的四个独立数据库，源主库实际执行候选 V1–V14及原回调，源工作库实际执行0001/0002，添加虚构业务记录。分别 pg_dump/pg_restore 到两个新库，逐表规范化行内容摘要一致。

恢复主库增加独立每日V1后，原public表摘要保持不变；主键序列可继续分配并写入新任务，每日记录可写入。恢复工作库升级0003后旧分类仍为default；两个虚构用户可有同名分类；已有用户数据时降回0002被拒绝，历史及数据保留。

夹具备份保留在候选 restore-fixtures，目录700、文件600；不含生产数据。没有实施生产备份，也没有验证生产数据特有的一致性问题或大数据量恢复时长。实际部署前仍需取得本次维护窗口备份，并按发布方案确认可恢复。

首次演练因本机Docker内部网络不发布回环端口而停止并清理；调整为随机独立网络、仅回环端口后重跑。第二次断言误将迁移自带分类计入本轮三条夹具，修正为按夹具名称计数后完整重跑通过。没有修改业务迁移来迁就测试。

## 重跑入口

在仓库根目录运行：

```bash
python3 scripts/verify-daily-candidate-postgres.py
python3 scripts/verify-work-ownership.py --candidate
python3 scripts/verify-oauth-e2e.py --candidate
python3 scripts/verify-daily-candidate-restore.py
```

MCP使用现有工作服务Python镜像中的依赖、候选源码只读挂载、network none运行 unittest discover。工作隔离脚本也使用现有镜像作依赖运行时，并挂载候选源码；最终部署镜像仍需4.5固定，不能把本轮当成最终镜像验收。

所有测试应用、随机数据库容器及其附属卷/网络已清理。下一步4.5固定候选镜像、保留服务镜像和恢复材料，明确选择性发布与关闭入口步骤，供部署审批；仍不操作生产或注册真实ChatGPT连接。
