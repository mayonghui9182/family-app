# 独立每日发布：源码基线与依赖审查

日期：2026-10-13。任务4.1完成基线核对及候选输入清单；候选转换、迁移和运行验收仍属于4.2–4.4，不代表可部署。

## 实际比对证据

运行 `python3 scripts/audit-daily-release-baseline.py`，只读取已保留的运行JAR、Git对象及源码，不连接业务库或读取环境变量。

- 已核验JAR SHA256为3d651317a83e2d34ab9ade806e5d4ba5369fda49f161052e5a06435754780e29。
- V1–V14及afterMigrate__agent_reader.sql，当前源码与JAR逐字节一致。V13/V14未被HEAD跟踪，但可以保留其已核验内容，不能从纯HEAD构建时遗漏。
- V15/V16/V17不存在于运行JAR。
- 扫描236个Java源文件，对应顶层类中74个不在运行JAR。类存在不证明源码实现相同；当前报告不声称恢复了生产源码。
- wechat/conversation包之外直接导入该包的文件为FamilyService.java。此为直接import扫描，不能排除反射、扫描注册、配置与其他语言依赖。
- 完整源码摘要、类存在性和Git差异见[daily-release-baseline.json](./daily-release-baseline.json)。生成时不扫描业务数据、凭据或备份正文。

## 纳入审查的文件与依赖

| 范围 | 文件 / 路径 | 处理 |
|---|---|---|
| 每日实现 | backend/src/main/java/com/familyapp/daily/*.java | 四个文件纳入；DailySources依赖家庭成员、家庭、任务、日程及TaskAssignees |
| 工作认证桥 | backend/src/main/java/com/familyapp/auth/WorkUserInternalController.java | 纳入；依赖UserRepository和内网拦截器 |
| 本人任务筛选 | backend/src/main/java/com/familyapp/task/TaskAssignees.java | 核对档案归属及旧assignee兼容，不能连带导入全部任务重构 |
| 错误码 | backend/src/main/java/com/familyapp/common/ResultCode.java | 纳入daily三个错误码，同时保留既有密码恢复错误码，避免从HEAD丢失 |
| 账号认证 | auth/AuthService.java、auth/AuthInterceptor.java、auth/recovery/*、user/UserRepository.java | 与运行类逐项核对；保留已上线密码恢复和安全行为，禁止盲目回退HEAD |
| 内部认证配置 | config/InternalSecretInterceptor.java及WebConfig.java | 保留机器认证，核验新增入口没有落入公开路径 |
| 工作域 | task-planner/task-service和agent-service归属/认证改动、0003 | 必须成组审查REST/MCP、关联实体及总结身份传递；不能仅复制新文件而漏掉既有文件改动 |
| 每日前端 | frontend/src/views/DailyView.vue | 纳入；依赖路由、API client、菜单/首页/更多入口及测试 |
| 前端共享文件 | router/index.js、api/client.js、AppSider.vue、DashboardView.vue、MoreMenuView.vue | 按差异段落抽取，不覆盖其他既有账号/家庭/工作入口 |
| OAuth/MCP | integrations/daily-oauth、integrations/daily-mcp | 纳入源文件、构建及测试，排除构建缓存和真实配置 |
| 发布配置 | application.yml、docker-compose*.yml、nginx HTTP/HTTPS及daily可选路由 | 按本次必要段落生成候选配置，不复制整份混合编排后直接上线 |
| 测试 | daily测试、工作归属测试、OAuth/MCP测试、原家庭/微信/密码恢复回归 | 在候选中重新执行，不沿用混合目录结果作为最终证据 |

## 明确排除与拆分点

- backend/.../wechat/conversation及其测试、新会话V15/V16结构与回滚材料：排除本次独立候选，原目录保留。
- agent-service新增会话生成/摘要及wechat-service新会话回调分支：不随本次发布；现有镜像是否可保留需核对内部接口兼容。
- FamilyService的WechatFamilyDeletionService注入、调用、WechatChatContext使用：候选中须拆除，保留旧家庭删除语义及已上线的安全修复，不直接用HEAD替换整文件。
- V17不能原样加入缺V15/V16的候选迁移链；由4.2设计独立历史和未来接回方案。
- .env、证书、业务卷、恢复归档、运行日志、IDE/Agent配置、构建缓存：不得进入候选源包。
- 其他未被允许清单覆盖的功能变动：默认不纳入，不能因在当前工作树中就视为已批准。

## 4.1剩余验证

> 下方为前轮未决记录；本轮结果和后续归属见文末。前端源码与运行bundle的字节同源仍不作保证，独立构建与功能保留由4.3/4.4验证。

### 2026-10-13运行文件核对

已运行Gradle compileJava（成功，任务UP-TO-DATE）后比对保留JAR中的全部218个业务class：211个与当前编译结果字节相同，7个不同。该证据以当前Gradle增量构建状态为基础，最终候选仍需独立重建。差异类为AuthInterceptor、ResultCode、InternalSecretInterceptor、FamilyService、TaskServiceImpl、WechatBindRepository、WechatNotifyClient。

对差异类执行javap -p -c并保存[daily-backend-bytecode.diff](./daily-backend-bytecode.diff)，只规范化常量池编号，没有将输出等价当作语义等价。已观察AuthInterceptor新增删除账号过滤、WechatBindRepository新增锁定查询、WechatNotifyClient新增详细客服发送结果方法；FamilyService和TaskServiceImpl差异较大，尚需逐方法审核，不可宣称7类均已审完。

实际读取两个工作容器/app/src内Python程序，在内存解析后仅记录摘要：任务服务36个运行文件中27个相同、9个不同；Agent22个运行文件中17个相同、5个不同。不同文件对应归属/认证/总结改动，运行目录没有包含新文件的比较不代表新文件不存在于当前工作树，装配清单须另行纳入work_auth.py/ownership.py等。

读取运行前端静态目录：74个HTML/JS/CSS文件中30个与当前dist同路径且字节相同，44个不同或本地缺失。文件名包含构建哈希，因此不能将44解释为44个业务功能变化；本轮未重建前端，不能把dist当作当前源码基线。需独立构建及功能回归。

摘要及运行镜像标识见[daily-runtime-file-audit.json](./daily-runtime-file-audit.json)，重复脚本为`scripts/audit-daily-runtime-files.py`。只读取程序目录，未读取环境、日志或数据库，未重启服务。Java临时字节码提取目录已自动删除。

1. 对运行JAR中已存在而当前源码已改的关键类重建或做字节码语义比较，明确哪些差异是已上线行为、哪些是新增；重点为认证、家庭、任务及通知。
2. 补齐前端和两个工作服务的运行产物基线，不仅凭Java JAR判断整个系统。
3. 将上述目录级清单展开为明确文件与哈希、来源选择和补丁段落；检查传递依赖后才能勾选4.1并用于4.3装配。

本轮完成可重复的迁移与类清单审计；未改应用代码、运行生产迁移、组装候选或上线。

## 2026-10-13七类审查与输入清单定稿

通过javap逐方法摘要与当前源码及调用点交叉核对，不能把Git差异全部当成尚未上线改动。运行FamilyService已经有JdbcTemplate、家庭锁和唯一管理员退出路径；原有TaskServiceImpl已经包含多项权限及查询能力，不能整文件还原HEAD。

| 差异类 | 候选处理 |
|---|---|
| AuthInterceptor | 保留当前删除账号过滤；运行类已有精确公开路径，不能恢复HEAD宽泛白名单 |
| ResultCode | 保留原密码恢复等错误码，新增daily三个错误码 |
| InternalSecretInterceptor | 保留空白机器密钥启动拒绝，属于新内部读取所需安全约束 |
| FamilyService | 保留旧锁与删除行为，只移除会话服务注入、routes及softDelete/physicalDelete钩子 |
| TaskServiceImpl | 保留已上线权限/查询及本人任务口径；当前创建委托依赖通用TaskCreationCommandService，将它作为共享依赖纳入并做候选回归，不把这个新委托称作已上线 |
| WechatBindRepository | 移除只由会话租约调用的findByIdForUpdate及对应导入 |
| WechatNotifyClient | 移除只由会话outbox调用的sendKefuTextDetailed，保留旧发送接口 |

TaskCreationCommandService没有依赖会话表；其通用创建路径使用既有家庭、任务、档案及通知队列。候选保留这些校验，移除会话专用createPendingAgentBatch和仅供该入口使用的逻辑，相关专项测试拆分。选择这是为了让当前任务服务可重建，不意味着允许发布微信会话功能。

已生成[daily-release-source-manifest.json](./daily-release-source-manifest.json)：每个输入文件包含路径、SHA256、include/exclude/transform/migration_design/test_review动作及原因。脚本`scripts/manifest-daily-release-sources.py`可重建清单。装配必须校验摘要，转换和迁移待定项不得原样复制后放行。

家庭agent-service、wechat-service、tool-service本次不重建，现有运行产物在发布前仍需验证可恢复和接口兼容；这些服务中的新会话代码不进入本次源包。前端以明确源码清单独立构建，哈希bundle差异不用于判断业务等价，原功能保留必须由候选回归证明。

因此4.1的基线、共享依赖和文件选择已形成；4.2负责迁移选择与验证，4.3负责实际转换装配，4.4负责独立构建及行为证据。尚未完成的后续工作没有作为4.1运行验收通过来报告。
