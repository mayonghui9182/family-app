# Delta Spec: frontend-ui (Change 14 - 微信私聊消息自动对接 AI 拆解)

> 新增 H5 拆解草稿详情页与免密深链恢复：老人从微信点一次链接即可看到「自己刚说的那句话」被拆成的待办清单，并只用一个大按钮完成确认。

## ADDED Requirements

### Requirement: 前端 SHALL 提供无需应用内上下文的拆解草稿详情页

前端 SHALL 提供路由 `/agent/draft/:id`（`requiresAuth`），在**全新页面加载、无 Pinia 会话历史**的条件下，仅凭 D-8 响应即可渲染：用户原文（`initTaskTitle`，置于「你说：…」区块）、待办子任务清单（`proposedTasks`，逐条标题 + 可选截止日/负责人中文名）、以及两个主操作「确认创建」（→ D-5）与「不要了」（→ D-7 cancel）。页面 SHALL 覆盖 loading / empty / error / success 四态，空清单 SHALL 展示中文兜底而非空白。`status=CONFIRMED` 时 SHALL 直接呈现「已创建 N 个任务」并禁用确认按钮（防重复建任务）。

#### Scenario: 从微信链接直达并可确认
- **WHEN** 用户从微信客服消息点进 `/agent/draft/42` 且已完成免密登录
- **THEN** 页面显示其原文与拆解出的子任务清单，点「确认创建」后提示任务已创建，任务列表可见对应任务

#### Scenario: 重复点击不重复建任务
- **WHEN** 用户再次打开同一个已 `CONFIRMED` 的草稿链接
- **THEN** 页面显示「已创建 N 个任务」，确认按钮不可点击，不产生第二次 D-5 调用

#### Scenario: 拆解结果为空
- **WHEN** D-8 返回的 `proposedTasks` 为空数组
- **THEN** 页面显示中文提示（如「这次没能拆出可执行的步骤」）并保留「不要了」出口，无 JS 报错

#### Scenario: 读取失败可重试
- **WHEN** D-8 请求因网络失败
- **THEN** 页面显示中文错误与「重新加载」按钮，不出现英文错误码

### Requirement: 草稿详情页 SHALL 满足老人易用硬约束

详情页 SHALL 沿用本项目老人易用规范：正文基准字号 ≥ 18px、触控目标 ≥ 48×48px、高对比配色（不依赖颜色单独承载状态）、单列布局不横向滚动、一次只呈现一个主操作（确认与「不要了」视觉权重分明）、状态提示使用中文口语化文案。SHALL NOT 使用悬浮提示承载必要信息（触屏无 hover）。

#### Scenario: 小屏可读可点
- **WHEN** 在 375px 宽屏上打开草稿详情
- **THEN** 无横向滚动条，两个主按钮均可单指命中且文案完整可读

### Requirement: 免密落地 SHALL 支持深链恢复并保持无 next 时的既有行为

`/wechat/callback` 落地页 SHALL 从 URL fragment 读取 `next`，登录态写入后 `router.replace(next)`；`next` 缺失或不合法时 SHALL 落默认首页。SHALL NOT 把 token 或 `next` 放进查询串，SHALL NOT 在页面渲染文本中回显 token。

#### Scenario: 带 next 时跳转到目标页
- **WHEN** fragment 为 `#token=<jwt>&next=/agent/draft/42`
- **THEN** 保存登录态后 `replace` 到 `/agent/draft/42`，浏览器历史中不留下落地页

#### Scenario: 无 next 时行为不变
- **WHEN** fragment 只有 `#token=<jwt>`
- **THEN** 仍按既有实现落到默认首页（回归护栏）
