# AGENTS.md — 面向所有 AI Agent 的常驻指令

本仓库是 **family-app**：一个家庭任务管理应用（Java Spring Boot 后端 + Vue 3 前端 + 三个 Python FastAPI 微服务，Docker Compose 编排，含 AI 任务拆解与微信公众号集成）。

项目宪法见 **`CLAUDE.md`**（总纲与硬约束的唯一权威来源）。本文件不复制其内容，只做引用与导航。

## 地图指引（先查地图，再读代码）

| 地图文件 | 用途 |
|----------|------|
| `repo-map.md` | 仓库结构地图：模块职责、包结构、依赖关系全貌 |

**定位代码时按三层递进，不要通读整个仓库：**

1. **地图**：先查上表两份地图，确定目标模块/文件落点
2. **上下文**：只读落点文件及其直接关联文件（配置、接口定义）
3. **grep/glob**：仍不确定时，用精确搜索（类名、路由、字段名）收敛

## 会话协议

- **起手必读** `.handoffs/current.md`（跨会话交接：当前状态、进行中变更、未决事项）
- **执行遵循** `.harness/sop.md`（8 阶段 SOP），检查点见 `.harness/checkpoints.md`
- **会话结束** 更新 handoff（`.handoffs/current.md` 交接状态；`handoffs/current.md` 入库执行日志）

## 硬约束（四条，详见 handoffs/current.md §F）

1. **中文注释**：所有代码注释使用中文
2. **UI 老人易用**：前端面向老年用户，大字号、高对比、简化操作
3. **5 值任务状态机**：TaskStatus 固定 5 值，不得随意增删状态
4. **family_id 多租户隔离**：所有业务数据查询必须带 family_id 隔离

## 环境注意

- 仓库位于 WSL（`/home/mayh/code/family-app`），Windows 侧通过 UNC 路径 `\\wsl.localhost\Ubuntu\home\mayh\code\family-app` 访问
- shell 命令统一走：`wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && ..."`
- **不要修改既有治理文件**：`CLAUDE.md`、`.claude/settings.json`、`.harness/`、`.gitignore` 等
- 根目录 `docker` 是空文件，不是目录

## Kilo 专属配置入口

- `.kilo/agent/` —— 自定义 agent 角色（Markdown + frontmatter）
- `.kilo/command/` —— 项目级斜杠命令
- `.kilo/skills/` —— 项目技能（每技能一个子目录，含 SKILL.md）

各目录内 README.md 有格式说明；OpenSpec 命令/技能可从 `.claude/commands/opsx/`、`.claude/skills/` 复制适配。
