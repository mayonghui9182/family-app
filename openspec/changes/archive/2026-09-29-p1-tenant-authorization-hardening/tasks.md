## 1. 身份与任务角色守卫

- [x] 1.1 加固 TaskService 登录身份解析，拒绝 familyId/createdBy 伪造并保留无上下文内部调用，运行 TaskService 定向测试验证
- [x] 1.2 落实 CHILD 创建默认指派、只改本人相关任务且禁止删除/改派他人的矩阵，增加正负向测试并运行任务包测试

## 2. AI 内部链路家庭隔离

- [x] 2.1 为 InternalAgent B-5/B-7 增加 family_id scoped lock 与跨家庭负向测试，运行 InternalAgentBGroupTest 和 FamilyWorkflowApiTest
- [x] 2.2 更新 agent-service BackendClient、访谈调用点和测试假件传递 family_id，运行访谈及连接器测试

## 3. 档案与邀请授权

- [x] 3.1 将档案 patch 改为 familyId + profileId 联合查询，并让会话合并传入所属家庭；增加跨家庭不修改测试并运行 profile/agent 定向测试
- [x] 3.2 历史成员重邀时保留原角色，增加 CHILD 退出后重入用例并运行 family 定向测试

## 4. 综合验证与交接

- [x] 4.1 运行 backend 与 agent-service 全量测试、OpenSpec strict 和差异清洁度检查
- [x] 4.2 更新两份 handoff，记录跨服务同批发布要求、验证证据、未部署/未提交状态和下一阶段建议
