# add-frontend-family 实施计划

## 概览
- 总任务：7 个 (F1-F7)
- 关键里程碑：F1（基础设施）→ F2（API 层）→ F3-F6 并行（视图）→ F7（build + commit）
- 验证手段：每个 F 完成后 `npm run build` 必须成功；最后 green build

## F1 — 基础设施
**范围**：client.js + stores(auth/family) + router + main.js/App.vue + 布局组件(AppHeader/AppSider/AppLayout)
- [x] 安装 vue-router@4 pinia
- [x] 写 src/api/client.js（拦截器：X-Access-Token + X-Family-Id + 错误处理）
- [x] 写 src/stores/auth.js（Pinia + localStorage 持久化 token）
- [x] 写 src/stores/family.js（Pinia + localStorage 持久化 currentFamilyId）
- [x] 写 src/router/index.js（路由 + 守卫）
- [x] 改 src/main.js（注入 Pinia + Router）
- [x] 改 src/App.vue（<RouterView />）
- [x] 写 src/layouts/AppLayout.vue
- [x] 写 src/components/AppHeader.vue（家庭切换 + 登出）
- [x] 写 src/components/AppSider.vue（emoji 大字体导航）
**验证**：`npm run build` SUCCESSFUL

## F2 — API 层
**范围**：5 个 API 模块统一用 client.js
- [x] 写 src/api/auth.js（register/login/logout/me）
- [x] 写 src/api/family.js（9 端点）
- [x] 写 src/api/invite.js（4 端点）
- [x] 写 src/api/categories.js（CRUD + merge）
- [x] 改 src/api/tasks.js（移除独立 axios，用 client；加 updateStatus）
**验证**：`npm run build` SUCCESSFUL

## F3 — LoginView
**范围**：src/views/LoginView.vue
- [x] el-card 居中，tab 切换登录/注册
- [x] 登录表单：identity（大输入框 + emoji prefix）+ 密码（show-password）+ 登录按钮
- [x] 注册表单：邮箱 + 手机号 + 昵称 + 密码 + 家庭名 + 注册按钮
- [x] 成功写 authStore + 设当前家庭 + router.push('/tasks')
- [x] 失败 ElMessage 提示中文
**验证**：`npm run build` SUCCESSFUL

## F4 — TaskView 改造
**范围**：src/views/TaskView.vue（全重写）
- [x] 移除旧 TODO/DONE，改为 5 值 DFA 状态映射（中文 + tag type）
- [x] 卡片式布局（el-row + el-col + el-card）
- [x] DFA 按钮矩阵：
  - DRAFT → 开始(PENDING) / 取消(CANCELLED)
  - PENDING → 开始做(IN_PROGRESS) / 完成(COMPLETED) / 取消(CANCELLED)
  - IN_PROGRESS → 已完成(COMPLETED) / 暂停(PENDING) / 取消(CANCELLED)
  - COMPLETED / CANCELLED → 无操作按钮
- [x] canTransition(from, to) 双重防御（渲染 + 点击）
- [x] 创建表单：标题 + 描述 + dueDate + priority（MEDIUM）+ assigneeId
- [x] 用 tasksApi（list/create/update/del/updateStatus）
- [x] Array.isArray 兜底防 v-for 空响应
**验证**：`npm run build` SUCCESSFUL

## F5 — CategoryView
**范围**：src/views/CategoryView.vue
- [x] inline 创建表单：name 必填 + el-color-picker
- [x] el-table：色块 + name + taskCount(存在则显示) + 操作
- [x] 编辑弹窗：name + color
- [x] 删除两阶段：先 del(id) → 失败再弹合并对话框 del(id, mergeToId)
- [x] 独立合并区：源选择 + 目标选择 + 确认
- [x] 用 categoriesApi（list/create/update/del/merge）
**验证**：`npm run build` SUCCESSFUL

## F6 — FamilyView + InviteView
**范围**：FamilyView.vue（列表+详情+成员管理）+ InviteView.vue（生成+接受+取消）
- [x] FamilyView 列表：name + roleName 中文 + 成员数 + 设为当前/详情/创建
- [x] FamilyView 详情：家庭信息编辑 + 成员表
  - OWNER：改角色 ADULT↔CHILD + 踢人 + 转让管理员
  - 非 OWNER：退出家庭
- [x] 「设为当前」：familyStore.setCurrent + localStorage，刷新当前 View
- [x] InviteView（路由 /families/:id/invites）：
  - 接受邀请区：code 大输入框 toUpperCase 过滤非字母 + 加入按钮
  - 创建邀请：maxUses 1-100 默认 1 + ttlDays 1-30 默认 7
  - 我创建的邀请：table（code/maxUses/usedCount/expiresAt/status 中文）+ 取消按钮
  - 复制邀请码按钮 + 老人提示「发给家人」
**验证**：`npm run build` SUCCESSFUL

## F7 — 集成验证 + git commit
- [x] `npm run build` BUILD SUCCESSFUL（无 error 无 warning 超限）
- [x] `git status` 无临时文件残留
- [x] `git add frontend/ && git commit -m 'feat(frontend): add auth, family, invite, and category views with Vue Router + Pinia'`
- [x] 验证 openspec 目录齐全并写入 `.handoffs/current.md`

## 总验证清单
- [x] package.json 依赖：vue-router@4 + pinia 已加
- [x] `npm run build` 0 次 error
- [x] 5 个视图都编译（dist 存在独立 chunk）
- [x] 不新增其他 npm 依赖（除 vue-router 和 pinia）
- [x] 前端 commit 存在（8075a05）
- [x] handoffs/current.md 写回