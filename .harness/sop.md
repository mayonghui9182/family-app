# family-app Harness SOP

> 基于《从玩具到生产力：用真实项目讲透 AI Agent 的 Harness Engineering》，针对 family-app 定制的 8 阶段控制协议。
>
> **核心原则**：每一个阶段只给模型一个带边界的输入；必须先交付中间产物；Harness 控制点核对无误后，才允许进入下一步。

---

## 架构定位

```
Harness（物理控制面）
  ├── 真相源（Spec is Truth）→ CLAUDE.md + .harness/ + .handoffs/
  ├── 行为协议（SOP + Checkpoints）→ 本文件 + checkpoints.md
  ├── 恢复机制（Handoffs）→ .handoffs/current.md
  └── 自动化（Hooks）→ .claude/settings.json + .harness/hooks/*.ps1
```

每一层都在模型**外部**发挥作用——这是与"在 Prompt 里加约束"的本质区别。

---

## 8 阶段 SOP

| 阶段 | 给模型的输入 | 要求返回的中间产物 | 控制动作 |
|------|------------|----------------|---------|
| **0 目标收敛** | 先读相关文档，不准上来写代码 | 模块目标复述 + 边界 + 疑问清单 | 先纠偏再放行 |
| **1 状态恢复** | 先读 `.handoffs/current.md` | 已完成项、未完成项、接续建议 | 用外部真相源恢复，不靠印象 |
| **2 上下文装配** | 只给相关文件路径索引，不整包投喂 | 本轮最小上下文清单（文件列表） | 按需补充，避免 Context 爆满 |
| **3 任务分块** | 本轮只做一个最小可验证单元 | 1-3 个动作 + 风险 + 验证方式 | 只批准当前轮次，不批总计划 |
| **4 链路设计** | 先判断执行模式，不盲改代码 | 数据流图 + 关键文件 + 接口签名 | 先定路线再执行 |
| **5 执行前 Checkpoint** | 先别改代码，先做 Checkpoint | 当前理解 + 下一步 + 风险 + 验证方式 | 对齐后再批准执行 |
| **6 外部验证** | 不接受"我觉得好了" | 测试结果 / 实际行为的事实判断 | 用证据，不用主观感觉 |
| **7 回写交接** | 暂停前必须先写 Handoff | 完成项 + 偏差 + 残留问题 + 下一轮最小目标 | 给下一轮留干净恢复点 |

---

## 阶段 6 外部验证标准（family-app 专用）

> 技术栈：Java (Gradle) + Python + Vue (Vite) + PostgreSQL + Redis + Docker

| 操作类型 | 验证证据 |
|---------|---------|
| Java 后端编译 | `./gradlew compileJava` 零错误 |
| Java 单元测试 | `./gradlew test` 通过 |
| Java Lint | `./gradlew checkstyleMain` 零警告（可选，按配置） |
| Python AI 模块测试 | `pytest` 通过（在 AI 模块目录下） |
| Python Lint | `ruff check .` + `mypy .` 零警告（在 AI 模块目录下） |
| Vue 前端构建 | `npm run build` 无报错（Vite） |
| Vue 前端测试 | `npm run test` 通过（vitest） |
| Vue Lint | `npm run lint` 零警告（eslint + prettier） |
| API 改动 | 本地实际调用（curl / Postman / 前端联调），返回符合 schema |
| DB Schema 改动 | migration 正向跑通 + 基础 CRUD 查询返回预期结构 + 可逆向回滚 |
| Redis 改动 | 连接测试通过 + 基础命令（GET/SET/HSET 等）返回预期 |
| Docker 改动 | `docker compose config` 校验通过 + `docker compose up -d` 启动正常 |
| UI 改动 | dev server 可访问 + 关键路径视觉验证（手动或 Playwright） |
| OpenSpec 改动 | `openspec validate` 通过 + `openspec status --json` 返回预期状态 |

**原则**：宁可跑一次不必要的验证，也不要漏一次必要的验证。
**技术栈扩展**：后续增加组件时，必须在本表补充对应验证项，否则 SOP 阶段6 不完整。

---

## 纠偏链路

当模型开始漂移（谈总目标而不是阶段目标，或开始扩展范围）：

```
1. 停止当前轮次
   ↓
2. 复述阶段目标（不谈总目标）
   ↓
3. 缩小范围：当前最小目标是什么
   ↓
4. 确认证据：目前有哪些事实（测试 / 日志 / 实际行为）
   ↓
5. 重定义这一轮最小目标 → 重新进入阶段 3（任务分块）
```

漂移信号：
- "顺手修一下这个..."
- "既然改了 X，不如也改 Y..."
- 阶段目标与总目标混用
- 验证没通过但说"应该没问题"

---

## 三层目标体系

- **总目标**（整个项目）：family-app——具体业务目标待定（项目刚启动，仅有 OpenSpec 脚手架）
- **阶段目标**（当前板块 / Sprint）：读 `current.md` 的「当前阶段目标」段
- **本轮目标**（这次会话）：读 `current.md` 的「下一轮最小目标」段

**只允许谈本轮目标，阶段目标用来校准，总目标用来锚定方向。**

---

## 高危操作（必做 Checkpoint）

以下操作触发强制 Checkpoint（阶段 5），不允许跳过：

- 修改数据库 Schema / 跑 migrate
- 改动核心类型定义（公共 interface / shared types）
- 涉及认证、鉴权、密钥管理的改动
- 触碰 CI/CD 配置 / 部署脚本
- 删除任何已 commit 的文件或目录
- 修改 Harness 本身（SOP / hooks / checkpoints）
- 修改 OpenSpec 配置（`openspec/config.yaml`）或归档/创建 change

---

*Last updated: 2026-08-13 · family-app Harness v1.0*
