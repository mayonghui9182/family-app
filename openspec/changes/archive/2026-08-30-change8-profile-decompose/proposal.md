# Proposal: Change 8 - 家庭人员档案 + AI 对话式拆解

## Objective
1. 建立统一 person_profiles 档案系统，支持注册用户(有 user_id) + 虚拟成员(宝宝/老人/宠物，无 user_id)，B1 精简版 4 类白名单 JSONB
2. 任务创建页「指派给谁」从输入 user_id 改为下拉选家庭成员档案(P-6 轻量选项 DTO)，assignee_id 列保留 1 个大版本过渡
3. 新增 AI 多轮沟通环节(会话 agent_decompose_sessions)：用户在任务创建页 & 档案页都能和 AI 聊天；「确认」才写档案 + 生成任务，「放弃」零脏写，「只保存档案」仅更新不生成
4. 基于档案的任务 3 Tab 建议(S-1)：同类已办<=10 / 同类模板<=10 / 该成员所有待办<=20；匹配规则 4.1A(分类+档案)
5. 任务创建页新增「AI 拆解新增」按钮(与直接创建并列)，支持按 taskId 或任务描述文本直接拆解

## Out of Scope
- 不做 health 健康曲线 / 身高体重血压字段(B2 标准版)
- 不做头像图片上传(先用 emoji)
- 不做分钟级 dueDate 解析(仅日粒度)
- 不立即删除旧列 tasks.assignee_id(Change 10)
- 不开放 CHILD 角色编辑档案权限
- 不做跨家庭档案复用
- 不做 AI 定时自动更新档案

## Specs
- 详见 specs/profile-decompose/spec.md
- 详见设计文档 docs/superpowers/specs/2026-08-30-profile-decompose-design.md(Change 8 §0~§9)
