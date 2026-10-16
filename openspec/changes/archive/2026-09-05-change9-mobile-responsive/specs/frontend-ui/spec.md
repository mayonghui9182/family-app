# Delta Spec: frontend-ui (Change 9)

> 相对于 openspec/specs/frontend-ui/spec.md 的增量变更：新增移动端响应式布局要求。PC 端现有行为全部保持不变。

## MODIFIED Requirements

### Requirement: 应用布局 SHALL 提供移动端响应式形态（≤768px 底部 Tab 导航 + 侧边栏隐藏）

视口宽度 ≤768px 时，`AppLayout` SHALL 隐藏左侧 `el-aside` 侧边栏（`AppSider` 不再渲染），主内容区 SHALL 占满全屏宽度；视口宽度 >768px 时布局 SHALL 与现状完全一致（侧边栏 220px + 顶部 Header）。移动端 SHALL 在视口底部固定渲染 `BottomTabBar`，包含 4 个 Tab：首页(🏠 /dashboard)、我的任务(📝 /tasks)、AI 拆解(🤖 /chat)、更多(⋯ /more)。桌面端 SHALL NOT 渲染 `BottomTabBar`。

#### Scenario: 手机宽度进入应用
- **WHEN** 视口宽度 375px 的用户登录后进入任意已适配路由
- **THEN** 页面无左侧侧边栏，主内容占满全宽，底部固定显示 4 个 Tab，当前路由对应 Tab 高亮

#### Scenario: 桌面端行为不回归
- **WHEN** 视口宽度 >768px（如 1280px）打开应用
- **THEN** 布局与 Change 9 之前完全一致：左侧 220px 侧边栏 + 顶部 Header，无底部 Tab

### Requirement: 「更多」聚合页 SHALL 收纳低频管理入口

路由 `/more` SHALL 渲染 `MoreMenu` 聚合页，以大图标 + 中文文字的卡片/列表形式收纳 4 个低频入口：模板管理(📋 /templates)、自动化(📈 /automation)、分类管理(🗂️ /categories)、家庭管理(🏠 /families)。每个入口点击后路由跳转到对应页面并保持登录态与当前家庭上下文（`X-Family-Id` 不变）。AppSider 桌面端 7 项菜单 SHALL 保持不变。

#### Scenario: 移动端用户进入分类管理
- **WHEN** 移动端用户在底部 Tab 点击「更多」后点击「分类管理」
- **THEN** 路由跳转 `/categories`，页面正常渲染且后续 API 请求携带当前家庭 `X-Family-Id`

### Requirement: 高频列表 SHALL 在移动端以卡片形式展示

任务列表（TaskView）SHALL 提供移动端卡片形态：≤768px 时每条任务渲染为一张纵向卡片（标题、状态中文标签、指派人、按钮组竖排），SHALL NOT 出现横向滚动条；>768px 时保持现有 el-card 网格布局不变。卡片上状态流转按钮 SHALL 复用现有 `canTransition` 守卫与语义色规范。

#### Scenario: 手机上查看任务列表
- **WHEN** 视口宽度 375px 的用户打开 /tasks 且存在 5 条不同状态任务
- **THEN** 页面纵向滚动呈现 5 张任务卡片，无横向滚动条；PENDING 卡片显示「开始做/完成/取消」按钮，与桌面端同一任务的可流转状态一致

### Requirement: 移动端触控目标 SHALL 符合 44px 最小尺寸

≤768px 时，所有可交互元素（Tab、按钮、菜单项、输入框）的有效点击区域 SHALL ≥ 44×44px；现有三档字号体系（S/M/L）与老人友好大按钮规范 SHALL 在移动端继续生效。

#### Scenario: 底部 Tab 可点击区域
- **WHEN** 移动端用户点击底部任一 Tab 的图标或文字附近 44px 范围
- **THEN** 该 Tab 被正确激活并完成路由跳转
