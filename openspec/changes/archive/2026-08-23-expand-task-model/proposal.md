## Why

当前 Task 模型仅有 4 个状态（TODO/IN_PROGRESS/DONE/CANCELLED），缺少优先级、来源追踪、分类关联和过期机制。设计文档要求任务支持 draft 提案流程（AI 拆解后待确认）、优先级分级（high/medium/low）、来源标记（manual/agent/routine/schedule/trigger）和多维度过滤查询。这些是后续 subtask、category、note、rule-template 等模块的前置依赖。

## What Changes

- **BREAKING**: Task 状态枚举重命名：TODO -> PENDING，DONE -> COMPLETED，新增 DRAFT。最终 5 值：DRAFT | PENDING | IN_PROGRESS | COMPLETED | CANCELLED
- **BREAKING**: DFA 状态转换更新：DRAFT -> {DRAFT, PENDING, CANCELLED}（新增 draft->pending 确认路径和 draft->cancelled 24h 过期路径）
- 新增 TaskPriority 枚举：HIGH | MEDIUM | LOW（默认 MEDIUM）
- 新增 TaskSource 枚举：MANUAL | AGENT | ROUTINE | SCHEDULE | TRIGGER（默认 MANUAL）
- Task 实体新增字段：userId（预留多用户，MVP 固定 default）、categoryId（FK -> categories 表，nullable）、priority、source、expiresAt（draft 专用 24h 过期）
- Task 列表 API 新增多维过滤：GET /api/tasks?status=&categoryId=&source=&keyword=&dueBefore=
- Task 响应体扩展：返回 priority、source、categoryId、expiresAt、userId 字段
- Flyway V2 迁移：ALTER TABLE tasks 添加列 + 更新 status CHECK 约束 + 迁移存量数据（TODO->PENDING, DONE->COMPLETED）

## Capabilities

### New Capabilities

（无——本变更只扩展现有任务能力，不引入新独立能力）

### Modified Capabilities

- daily-task-mgmt: 状态枚举从 4 值扩展到 5 值（新增 DRAFT，重命名 TODO->PENDING、DONE->COMPLETED）；新增 priority/source/categoryId/expiresAt 字段及默认值规则；新增多维过滤查询需求；DFA 转换矩阵更新

## Non-goals

- 不实现 subtask（子待办）管理——独立 change add-subtask-mgmt
- 不实现 note（注意事项）管理——独立 change add-note-mgmt
- 不实现 category（分类）管理——独立 change add-category-mgmt（本变更只加 categoryId 字段，不建表）
- 不实现 rule-template / task-schedule / task-trigger——独立 change
- 不实现 AI/agent-service/MCP/SSE——Python 服务延后
- 不修改前端 UI——独立 change add-frontend-*
- 不修改软删除行为（deletedAt 逻辑保持不变）
- 不修改已归档的 add-daily-task-mgmt 变更内容

## Impact

- **后端 Java 代码**：Task.java、TaskStatus.java（修改枚举值）、新增 TaskPriority.java、TaskSource.java；DTO 类（CreateTaskRequest、UpdateTaskRequest、TaskResponse）新增字段；TaskRepository 新增过滤查询；TaskServiceImpl 更新 DFA 矩阵 + 枚举解析；TaskController 新增 @RequestParam 过滤参数；ResultCode 新增错误码
- **数据库**：Flyway V2 迁移脚本，ALTER TABLE tasks + 存量数据迁移
- **现有测试**：20 个测试中引用 TODO/DONE 的需更新为 PENDING/COMPLETED，DFA 转换测试需新增 DRAFT 路径
- **API 兼容性**：状态值的改变是 BREAKING——已部署的客户端需更新状态字符串映射
