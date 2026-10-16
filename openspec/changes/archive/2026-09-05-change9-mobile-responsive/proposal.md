# Proposal: Change 9 - 前端移动端适配（响应式改造）

## Why

微信公众号入口（Change 11）的用户 100% 通过手机微信内置浏览器访问，而当前前端是固定 220px 侧边栏的桌面后台式布局，全项目 0 条响应式断点（`@media (max-width)`），手机上主内容区被挤压到约 150px，表格溢出、弹窗挤压，基本不可用。移动端适配是公众号入口的产品前置条件。

## What Changes

- `AppLayout.vue` 增加响应式断点（768px 分界）：
  - 移动端隐藏左侧 `el-aside` 侧边栏，主内容全屏宽度
  - 移动端底部新增 Tab 导航：首页 / 我的任务 / AI 拆解 / 更多（4 项）
  - 新增「更多」聚合页收纳低频入口：模板管理、自动化、分类管理、家庭管理
- `AppSider.vue` 桌面端保持现状不变；移动端导航由新组件承担
- 任务列表等高频页面的 `el-table` 在移动端切换为卡片列表展示
- 全局 `styles.css` 补充移动端触控适配（点击目标 ≥44px，复用现有老人友好大字号体系）
- 纯前端改动，后端 / API / DB 零变更，不引入新依赖（无 Vant / 无 UI 库替换）

## 非目标 (Non-goals)

- 不更换组件库（不引入 Vant / 不重写 Element Plus 组件）
- 不新建独立移动端应用（一套代码同时服务 PC 与手机）
- 不做平板等中间断点（仅桌面 / 移动两档）
- 低频管理页（分类管理、模板管理、自动化）移动端只保证可读可点，不做深度优化
- 不涉及任何微信相关代码（公众号入口属于 Change 11）

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `frontend-ui`：新增移动端布局要求——≤768px 时底部 Tab 导航 + 侧边栏隐藏 + 高频列表卡片化；触控目标 ≥44px；PC 端行为保持不变

## Impact

- 前端：`src/layouts/AppLayout.vue`、`src/components/AppSider.vue`（不改）、新增 `BottomTabBar.vue` / `MoreMenu.vue`、`src/assets/styles.css`、`src/views/TaskView.vue`（表格卡片化）及各高频视图
- 后端 / agent-service / tool-service：无
- 依赖：无新增
- 部署：无 docker-compose 变更
- 与 Change 11 无代码依赖，但产品顺序上先于 Change 11 上线
