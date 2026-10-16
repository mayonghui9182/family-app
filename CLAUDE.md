# family-app

> 家庭事项管理 app。AI Agent Harness 控制面 + OpenSpec spec-driven 开发。
> 完整执行日志（tracked）：[handoffs/current.md](file:///c:/mayh/code/family-app/handoffs/current.md)

## 项目状态

**family-app —— 家庭事项管理 app**

业务领域：每日任务管理、家庭成员协作、孩子日常照顾、家庭采买；AI 任务拆解模块建设中。

技术栈：
- 后端：Java 17 / Spring Boot 3.2 / PostgreSQL 16 + Flyway 10（主业务）+ Python 3.12 / FastAPI（AI 模块，Change 3/4 启动后落地）
- 前端：Vue 3 + Vue Router 4 + Pinia + Element Plus + Vitest
- 数据库：PostgreSQL（主存储）+ Redis（规划中，Change 7 编排时落地）
- 部署：Docker（docker-compose 编排）

当前状态（2026-08-25 校准）：
- ✅ MVP 核心闭环已交付：任务管理 / 分类 / 家庭成员 / 邀请工作流 / 前端全链路，33 条前后端契约联调全绿
- ✅ 适老化与无障碍增强：WCAG aria-label + 键盘可聚焦 + Skip Link、字号 S/M/L 三档、老人易用性微调、邀请码 Canvas 分享卡
- 🚧 「AI 任务拆解系统」7-change 路线图执行中（规划：docs/superpowers/plans/2026-08-25-ai-task-decomp-overview.md）：
  - ✅ **Change 1：V6 Flyway 数据模型**（7 新表 + agent_reader 只读角色 + ❌1 DRAFT 过期过滤 + 种子模板）——分支 `feature/change1-v6-flyway-datamodel`，H2 全量测试 + PG 真机迁移/权限/锁行为五项验收全部通过
  - ⏳ 待启动：Change 3（tool-service 天气）→ Change 2（内部 18 API + 调度）→ Change 4（agent-service SSE）→ Change 5（总结+对比学习）∥ Change 6（前端四视图）→ Change 7（Compose+CI+E2E）

已就位的脚手架：
- OpenSpec 配置：`openspec/config.yaml`（context 已启用，含技术栈/业务/约定/rules/operations）；已完成 change 归档于 `openspec/changes/archive/`
- Harness 控制面：`.harness/` + `.handoffs/`（SOP 快照，gitignore）+ `handoffs/`（完整日志，tracked）+ `.claude/settings.json`
- 多 AI 工具配置：`.claude/` / `.codebuddy/` / `.roo/` / `.trae/`（已被 `.gitignore` 排除，本地缓存不入版本）
- JetBrains IDE 工程文件：`.idea/`（已被 `.gitignore` 排除）

## 🔧 Harness 控制面

- **起手读**：[.handoffs/current.md](file:///c:/mayh/code/family-app/.handoffs/current.md)（恢复上次会话状态；详细过程日志在 tracked 的 handoffs/current.md）
- **执行协议**：[.harness/sop.md](file:///c:/mayh/code/family-app/.harness/sop.md)（8 阶段 SOP）
- **Checkpoint 句式**：[.harness/checkpoints.md](file:///c:/mayh/code/family-app/.harness/checkpoints.md)
- **会话结束必做**：更新 `.handoffs/current.md` + 代码同 commit（PreToolUse hook 会在 `git commit` 时检查 handoff 是否 >2h 未更新）
- **Hooks**：`.claude/settings.json` → `.harness/hooks/stop.ps1` + `pre-commit.ps1`

## 工作流

- 使用 OpenSpec 进行 spec-driven 开发（见 `openspec/config.yaml`）
- 大型特性先用 superpowers writing-plans 拆 change 总览，再逐 change 走 OpenSpec 六件套（proposal/delta-spec/design/tasks/check_list/.openspec.yaml）
- AI 工具配置已就位：Claude Code (`.claude/`)、Trae (`.trae/`)、Roo (`.roo/`)、CodeBuddy (`.codebuddy/`)
- 修改任一 AI 工具的 OpenSpec 配置时，需同步其他三套以避免漂移

## ⚠️ 环境注意事项（新会话必读）

- 会话工作区在 WSL UNC 路径（`\\wsl$\...`）上：Windows ACL 沙箱对 9P 卷原理性不可用，需 danger-full-access 权限预设才能执行 shell；write/edit 工具对该路径有 tmpdir+硬链接兼容性问题——**写文件用「写 NTFS 临时目录 → wsl cp 进仓库」或 bash heredoc**
- shell 命令统一走 `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app && ..."`
- 用户硬约束（中文注释/UI 老人易用/5 值状态机/family_id 多租户隔离等）见 handoffs/current.md §F
