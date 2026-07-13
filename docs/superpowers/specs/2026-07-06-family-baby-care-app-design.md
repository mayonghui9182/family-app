# 朵朵成长助手 - 设计文档

## 1. 产品定位

**家庭宝宝照顾协作工具** — 帮助3-4人家庭（爸妈+老人）协同照顾1-3岁幼儿的日常工具。

### 核心用户

| 角色 | 说明 | 权限 |
|------|------|------|
| 爸爸/妈妈 | 管理员，主要操作者 | 所有功能，可邀请新成员 |
| 爷爷/奶奶/外公/外婆 | 家庭成员，手机不太熟练 | 查看/完成待办、记录宝宝数据 |

### 核心痛点

1. 日常待办提醒 — 喂奶、换尿布、洗澡、吃药等，一天好多事忙起来就忘
2. 宝宝成长记录 — 身高体重、喂奶量、睡眠时长，想记录下来对照发育是否正常
3. 育儿里程碑 — 几个月该会什么了？发育达标了吗？心里没底

### MVP范围

**做：**
- 首页今日看板（今日待办 + 今日提醒 + 宝宝今日记录）
- 共享待办清单（添加/完成/删除 + 优先级 + 分类 + 截止时间 + 语音输入）
- 定时提醒（标题 + 时间 + 重复周期 + 分类 + 语音播报/播放）
- 成长记录（身高体重曲线 + 喂养/睡眠/日常记录）
- 里程碑（按月龄展示发育参考，可标记达成状态）

**不做（YAGNI）：**
- 天气查询、旅游攻略 — 和带娃无关
- 任务指派/排班 — 简单共享清单够用
- 照片日记 — 先聚焦核心
- 复杂权限管理 — 就两种角色：管理员/成员

---

## 2. 技术架构

### 整体架构

```
┌─────────────────────────────────────────┐
│          App端（uni-app + Vue 3）         │
│   iOS/Android · 适老化UI · 移动端优先      │
├─────────────────────────────────────────┤
│        uni-app框架 · uView UI组件库       │
│        Pinia状态 · uni-router路由         │
│        推送通知 · 语音录音 · 语音播放       │
└──────────────────┬──────────────────────┘
                   │ HTTP/JSON
┌──────────────────▼──────────────────────┐
│           后端（Spring Boot 3）           │
│   REST API · JWT认证 · 家庭组管理         │
│   推送服务 · 语音文件存储 · 提醒调度       │
├─────────────────────────────────────────┤
│   MyBatis-Plus · Spring Validation       │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│           PostgreSQL 数据库               │
│   用户·家庭组·待办·提醒·成长记录          │
└─────────────────────────────────────────┘

部署：家庭NAS / 树莓派 / 轻量云服务器
```

### 技术选型

| 层级 | 技术 | 选型理由 |
|------|------|----------|
| App框架 | uni-app + Vue 3 + TypeScript | 一套代码编译到iOS/Android，Vue语法，原生能力支持 |
| UI组件 | uView UI | uni-app生态成熟组件库，支持自定义主题 |
| 状态管理 | Pinia | 轻量简洁 |
| 推送通知 | uni-push + 个推/极光 | 系统级推送，到点必达 |
| 语音录音 | uni.getRecorderManager() | uni-app原生录音API |
| 语音播放 | uni.createInnerAudioContext() | uni-app原生音频播放API |
| 后端框架 | Spring Boot 3.2 + Java 21 | 稳定可靠 |
| ORM | MyBatis-Plus | 简化CRUD |
| 数据库 | PostgreSQL | 家庭数据量小但关系明确 |
| 认证 | JWT | 无状态，简单够用 |
| 语音识别 | 百度智能云语音识别（App端SDK） | 每月500小时免费额度，中文识别准，超额0.025元/分钟 |
| 语音存储 | 后端本地文件系统 `/uploads/voice/` | 语音文件存储路径 |
| 语音合成 | 百度智能云TTS（App端SDK） | TTS播报文字，与语音识别共用百度云账号 |
| 部署 | Docker Compose | 一键部署到NAS/树莓派/云服务器 |

### 部署方式

自己家用，Docker Compose一键部署：
- 方案1：部署在NAS/树莓派上，局域网访问
- 方案2：部署在云服务器（轻量级2核2G够用），外网也能访问

---

## 3. 功能详细设计

### 3.1 首页 - 今日看板

打开即看到"今天有什么事"：

- 今日待办：显示当天未完成和已完成的待办，进度条+完成率统计
- 今日提醒：显示当天待触发的提醒
- 宝宝今日记录：喂养/睡眠/日常记录概览
- 底部4个Tab导航（首页/待办/提醒/宝宝）
- 右下角快速添加按钮

### 3.2 待办清单 - 共享清单

全家共享一个清单，谁做了谁勾掉：

**功能：**
- 添加/完成/删除待办
- 优先级：高/中/低
- 分类：喂食/卫生/出行/教育/其他
- 截止时间：非重复性任务选择日期+时间
- 内容形式：文字或语音（切换按钮切换）
- 显示"谁完成的"（头像或名字缩写）
- 筛选：全部/进行中/已完成 + 分类筛选

**内容输入方式：**
- 文字模式：直接输入文字
- 语音模式：按住录音→自动转文字→用户编辑确认
- 两种模式通过切换按钮一键切换，已输入内容保留
- 语音待办在列表中显示播放按钮+时长

**适老化设计：**
- 待办项字号≥18px
- 勾选按钮≥44px（拇指友好）
- 完成的项目划掉但不隐藏

### 3.3 提醒 - 定时提醒

**功能：**
- 标题 + 时间 + 重复周期（不重复/每天/每周/每月）
- 分类：喂食/吃药/睡眠/体检/其他
- 开关切换（暂停/启用）
- 到点推送通知 + 语音播报
- 未响应自动重发：提醒后无操作，5分钟后再提醒
- 稍后提醒按钮：点击后5分钟后再提醒

**时间类型：**
- **绝对时间**：指定具体日期和时间
- **相对时间**：从现在开始计算（30分钟后/1小时后/2小时后）

**时间选择逻辑：**

| 重复类型 | 时间类型 | 选择方式 |
|----------|----------|----------|
| 不重复 | 绝对时间 | 选择日期 + 时间 |
| 不重复 | 相对时间 | 快捷选项：30分钟后/1小时后/2小时后/自定义分钟 |
| 每天 | 绝对时间 | 只选时间（日期自动计算） |
| 每周 | 绝对时间 | 选择星期几（多选）+ 时间 |
| 每月 | 绝对时间 | 选择几号 + 时间 |

**相对时间快捷选项：**

| 选项 | 说明 | 使用场景 |
|------|------|----------|
| 30分钟后 | 0.5小时后 | 临时事项，如"半小时后喂奶" |
| 1小时后 | 1小时后 | 短时间提醒，如"一小时后吃药" |
| 2小时后 | 2小时后 | 中等时间提醒，如"两小时后睡觉" |
| 自定义 | 输入具体分钟数 | 其他时长需求 |

**提醒类型示例（1-3岁）：**

| 提醒 | 重复 | 说明 |
|------|------|------|
| 吃维生素D | 每天 09:00 | 每日补充 |
| 午睡时间 | 每天 12:30 | 作息规律 |
| 睡前刷牙 | 每天 20:30 | 牙齿护理 |
| 周末游泳课 | 每周六 10:00 | 固定课程 |
| 喂奶提醒 | 30分钟后 | 临时提醒 |
| 吃药提醒 | 1小时后 | 临时提醒 |

### 3.4 待办-提醒交互规则

待办和提醒是关联但独立的两个实体：

| 操作 | 非重复性待办 | 重复性待办 |
|------|-------------|-----------|
| 提前完成 | 关闭提醒（永久） | 关闭本次提醒（下次继续） |
| 到期未完成 | 触发提醒 | 触发提醒 |
| 提醒后完成 | 关闭提醒 | 关闭本次提醒 |
| 删除待办 | 同时删除提醒 | 同时删除提醒 |

**核心原则：只有到期未完成的待办才需要提醒。**

**提醒触发逻辑：**
1. 提醒服务定时检查（每分钟）
2. 遍历所有启用的提醒
3. 检查 next_remind_at 是否到达
4. 获取关联的待办状态
5. 待办已完成 → 跳过，不触发
6. 待办未完成且到时间 → 触发提醒
7. 设置 next_remind_at = 当前时间 + snooze_minutes（5分钟）
8. remind_count + 1
9. 如果 remind_count >= max_remind_count → 今天不再重发
10. 重复性提醒 → 凌晨0点重置 completed_today=false, remind_count=0

**提醒重发规则：**
- 首次提醒后用户无操作（未点击已完成/稍后提醒）
- 5分钟后自动重发
- 最多重发2次（共3次提醒：首次+2次重发）
- 3次后今天不再提醒，明天继续（重复性提醒）

**稍后提醒规则：**
- 用户点击"稍后提醒"按钮
- 5分钟后再次提醒
- 稍后提醒不计入重发次数（或重置计数）
- 可以多次点击稍后提醒

**提醒触发时的语音行为：**
- 文字待办（content_type=text）：调用TTS语音合成播报文字内容
- 语音待办（content_type=voice）：直接播放原始语音文件

### 3.5 成长记录

针对1-3岁幼儿：

| 记录类型 | 字段 | 说明 |
|----------|------|------|
| 身高体重 | 日期、身高cm、体重kg | 每月记录，生成曲线图 |
| 喂养记录 | 日期、餐次、食物、量 | 一日三餐+零食 |
| 睡眠记录 | 日期、午睡时长、夜间睡眠 | 关注睡眠规律 |
| 日常记录 | 日期、类型、内容 | 灵活记录如"今天会说3个字的句子了" |

**成长曲线：**
- 身高/体重曲线图（ECharts）
- 对照WHO标准范围（P3-P97百分位）
- 显示正常范围阴影区域

### 3.6 里程碑 - 发育参考

针对1-3岁的里程碑（参考WHO标准）：

| 月龄 | 大运动 | 精细动作 | 语言 | 社交 |
|------|--------|----------|------|------|
| 12月 | 独站 | 拇食指捏物 | 叫妈妈爸爸 | 会再见 |
| 15月 | 独走 | 叠2块积木 | 说3-5个词 | 指认物品 |
| 18月 | 跑 | 叠3-4块积木 | 说10个词 | 模仿家务 |
| 24月 | 双脚跳 | 画直线 | 说2-3字句 | 和小伙伴玩 |
| 30月 | 单脚站 | 画圆 | 说出姓名 | 自己穿鞋 |
| 36月 | 骑三轮车 | 画十字 | 说短句 | 懂轮流 |

**功能：**
- 按宝宝月龄自动展示当前阶段里程碑
- 每个里程碑可标记：已达成/未达成/关注中
- 达成可记录日期和备注
- 超龄未达成的里程碑高亮提醒

---

## 4. 数据模型

### 核心表结构

```sql
-- 家庭组
CREATE TABLE family (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    invite_code VARCHAR(6) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 家庭成员
CREATE TABLE "user" (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES family(id),
    name VARCHAR(50) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'member',  -- admin/member
    avatar_color VARCHAR(7),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 宝宝档案
CREATE TABLE baby (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES family(id),
    name VARCHAR(50) NOT NULL,
    gender VARCHAR(5) NOT NULL,  -- male/female
    birth_date DATE NOT NULL,
    avatar_color VARCHAR(7),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 共享待办
CREATE TABLE todo (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES family(id),
    title VARCHAR(200) NOT NULL,
    content_type VARCHAR(5) NOT NULL DEFAULT 'text',  -- text/voice
    voice_url VARCHAR(500),        -- 语音文件URL（voice类型时）
    voice_duration INTEGER,         -- 语音时长（秒）
    category VARCHAR(20) NOT NULL DEFAULT 'other',  -- feeding/hygiene/outdoor/education/other
    priority VARCHAR(10) NOT NULL DEFAULT 'medium',  -- high/medium/low
    due_date DATE,
    due_time TIME,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_by BIGINT REFERENCES "user"(id),
    completed_at TIMESTAMP,
    created_by BIGINT NOT NULL REFERENCES "user"(id),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 提醒
CREATE TABLE reminder (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES family(id),
    todo_id BIGINT REFERENCES todo(id) ON DELETE CASCADE,  -- 关联待办
    title VARCHAR(200) NOT NULL,  -- 冗余存储待办标题，避免每次关联查询
    category VARCHAR(20) NOT NULL DEFAULT 'other',
    time_type VARCHAR(10) NOT NULL DEFAULT 'absolute',  -- absolute/relative
    remind_time TIME NOT NULL,  -- 绝对时间：具体时间点；相对时间：计算后的目标时间
    relative_minutes INTEGER,  -- 相对时间：距离当前的分钟数（30/60/120/自定义）
    repeat_type VARCHAR(10) NOT NULL DEFAULT 'none',  -- none/daily/weekly/monthly
    repeat_config VARCHAR(100),  -- JSON: 每周存星期几[1,3,5]，每月存几号[1,15]
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    completed_today BOOLEAN NOT NULL DEFAULT FALSE,  -- 重复性提醒：今天是否已处理（凌晨0点重置为false）
    next_remind_date DATE,  -- 重复性提醒下次触发日期，避免同一天重复触发
    next_remind_at TIMESTAMP,  -- 下次实际提醒时间（含重发/稍后提醒延迟后的时间）
    remind_count INTEGER NOT NULL DEFAULT 0,  -- 本次提醒已重发次数
    max_remind_count INTEGER NOT NULL DEFAULT 3,  -- 最大重发次数（默认3次：首次+2次重发）
    snooze_minutes INTEGER NOT NULL DEFAULT 5,  -- 稍后提醒/重发间隔分钟数（默认5分钟）
    last_triggered_at TIMESTAMP,
    created_by BIGINT NOT NULL REFERENCES "user"(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 成长记录
CREATE TABLE growth_record (
    id BIGSERIAL PRIMARY KEY,
    baby_id BIGINT NOT NULL REFERENCES baby(id),
    record_type VARCHAR(20) NOT NULL,  -- height_weight/feeding/sleep/daily
    record_date DATE NOT NULL,
    height DECIMAL(5,1),             -- cm
    weight DECIMAL(5,2),             -- kg
    meal_type VARCHAR(10),           -- breakfast/lunch/dinner/snack
    food_desc VARCHAR(200),
    amount VARCHAR(50),
    sleep_duration INTEGER,           -- 分钟
    content VARCHAR(500),             -- 日常记录内容
    recorded_by BIGINT NOT NULL REFERENCES "user"(id),
    note VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 里程碑
CREATE TABLE milestone (
    id BIGSERIAL PRIMARY KEY,
    baby_id BIGINT NOT NULL REFERENCES baby(id),
    month_age INTEGER NOT NULL,
    category VARCHAR(20) NOT NULL,    -- motor/fine_motor/language/social
    title VARCHAR(100) NOT NULL,
    description VARCHAR(200),
    standard_month INTEGER NOT NULL,  -- WHO标准月龄
    status VARCHAR(10) NOT NULL DEFAULT 'pending',  -- pending/achieved/concerned
    achieved_date DATE,
    note VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 表关系

```
Family 1───n User
Family 1───n Baby
Family 1───n Todo
Family 1───n Reminder
Baby   1───n GrowthRecord
Baby   1───n Milestone
Todo   1───0..1 Reminder（可选关联）
```

---

## 5. API接口设计

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/create-family` | 创建家庭组（管理员入口） |
| POST | `/api/auth/join-family` | 加入家庭组（成员入口） |
| POST | `/api/auth/logout` | 退出登录 |
| GET | `/api/auth/user-info` | 获取当前用户信息 |

**认证流程：**
1. 创建家庭组 → 返回 invite_code + token
2. 分享 invite_code 给家人
3. 家人输入 invite_code → 返回 token
4. 后续请求携带 token（JWT）

### 待办接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/todos` | 获取待办列表（支持筛选） |
| POST | `/api/todos` | 添加待办 |
| POST | `/api/todos/voice` | 上传语音文件 |
| PUT | `/api/todos/{id}` | 修改待办 |
| DELETE | `/api/todos/{id}` | 删除待办（同时删除关联提醒） |
| PUT | `/api/todos/{id}/toggle` | 完成/取消完成 |

### 提醒接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/reminders` | 获取提醒列表 |
| POST | `/api/reminders` | 添加提醒 |
| PUT | `/api/reminders/{id}` | 修改提醒 |
| DELETE | `/api/reminders/{id}` | 删除提醒 |
| PUT | `/api/reminders/{id}/toggle` | 启用/禁用提醒 |

### 成长记录接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/baby/{babyId}/growth` | 获取成长记录列表 |
| POST | `/api/baby/{babyId}/growth` | 添加成长记录 |
| PUT | `/api/baby/{babyId}/growth/{id}` | 修改成长记录 |
| DELETE | `/api/baby/{babyId}/growth/{id}` | 删除成长记录 |
| GET | `/api/baby/{babyId}/growth/stats` | 获取成长统计（曲线图数据） |

### 里程碑接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/baby/{babyId}/milestones` | 获取里程碑列表 |
| PUT | `/api/baby/{babyId}/milestones/{id}` | 更新里程碑状态 |

### 宝宝信息接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/baby/{babyId}` | 获取宝宝信息 |
| PUT | `/api/baby/{babyId}` | 更新宝宝信息 |

### API设计原则

- 统一响应格式：`{ code, message, data }`
- JWT认证：请求头 `Authorization: Bearer xxx`
- 家庭隔离：后端自动根据用户的 family_id 过滤数据
- 幂等性：PUT/DELETE接口支持幂等调用
- 分页：列表接口支持 `page, size` 参数

---

## 6. UI设计与适老化

### 设计风格

- 主色调：温暖橙色 `#FF8C42`
- 辅助色：柔和绿 `#6BCB77`（成长/完成）
- 背景色：米白 `#FFFAF5`
- 文字色：深灰 `#2D3436`
- 卡片：大圆角16px + 柔和阴影
- 宝宝页：粉色渐变 `#FF8FA3`

### 适老化设计要点

| 原则 | 实施方式 |
|------|----------|
| 大字体 | 正文≥18px，标题≥24px，按钮文字≥20px |
| 大按钮 | 按钮高度≥56px，可点击区域≥44px |
| 高对比度 | 文字与背景对比度≥4.5:1 |
| 清晰图标 | 图标尺寸≥24px，线性图标，圆角线条 |
| 简单布局 | 单栏布局，层级不超过3层 |
| 减少操作 | 点击两次以内完成核心操作 |
| 反馈明确 | 完成操作有明显视觉反馈（颜色变化/打勾动画） |

### 关键交互

| 操作 | 交互方式 |
|------|----------|
| 完成待办 | 点击圆形勾选框 → 打勾动画 + 颜色变绿 |
| 删除待办 | 左滑卡片 → 显示红色删除按钮 |
| 添加记录 | 点击"+"按钮 → 底部弹出表单 |
| 输入切换 | 文字/语音切换按钮一键切换，保留已输入内容 |
| 切换提醒 | 开关按钮 → 蓝色启用/灰色禁用 |
| 查看详情 | 点击卡片 → 页面切换 |

### 语音输入交互

```
文字模式 ←→ 语音模式（切换按钮）

语音模式流程：
1. 按住"说话"按钮 → 开始录音
2. 松开 → 停止录音，上传语音
3. 调用语音识别API → 返回文字
4. 显示识别结果供编辑确认
5. 保存：content_type=voice, text_content=识别文字, voice_url=文件路径

切回文字模式：保留已识别文字，继续编辑
```

### 提醒通知交互

```
提醒触发时（首次/重发）：
┌─────────────────────────────────────┐
│        💊 吃维生素 D                │
│        现在是 09:00                 │
│    （第 1/3 次提醒）                 │
├─────────────────────────────────────┤
│  文字待办 → TTS播报："请吃维生素D"  │
│  语音待办 → 播放原始录音            │
├─────────────────────────────────────┤
│     [已完成]    [5分钟后再提醒]     │
└─────────────────────────────────────┘

点击"已完成"：
  非重复 → 关闭提醒，设置 completed_today=true
  重复性 → 关闭本次提醒，设置 completed_today=true

点击"5分钟后再提醒"：
  设置 next_remind_at = 当前时间 + 5分钟
  remind_count 不变（或重置为0）
  5分钟后再次提醒

自动重发：
  用户无操作 → 5分钟后自动重发
  remind_count + 1
  最多重发2次（共3次）
  3次后今天不再提醒
```

### 时间选择交互

```
添加待办时的时间选择：

┌─────────────────────────────────────┐
│  ☐ 开启提醒                         │
│    时间类型：                        │
│    [绝对时间]  [相对时间]            │
│                                     │
│  ── 绝对时间模式 ──                 │
│    日期：[2024-07-06 📅]            │
│    时间：[09:00 ⏰]                 │
│                                     │
│  ── 相对时间模式 ──                 │
│    快捷选项：                        │
│    [30分钟后] [1小时后] [2小时后]    │
│    [自定义分钟：____]                │
│                                     │
│    计算结果：约 10:30 提醒           │
│                                     │
│    重复：[不重复 ▼]                 │
└─────────────────────────────────────┘

相对时间转换逻辑：
1. 用户选择"30分钟后"
2. 后端计算当前时间 + 30分钟 = 目标时间
3. 存储：time_type=relative, relative_minutes=30, remind_time=目标时间
4. 提醒服务按 remind_time 触发

切换回绝对时间：保留目标时间，允许手动调整
```

---

## 7. 与现有代码的差异

当前项目已有代码需要重构：

| 操作 | 内容 | 说明 |
|------|------|------|
| 删除 | 天气相关模块 | 不做天气功能 |
| 删除 | 旅游相关模块 | 不做旅游功能 |
| 删除 | 疫苗相关模块 | 1-3岁疫苗不是重点 |
| 新增 | Family表 + 家庭组功能 | 家庭邀请码机制 |
| 修改 | User表 | 加 family_id, role 字段 |
| 修改 | Todo表 | 加 content_type, voice_url, voice_duration, completed_by, sort_order |
| 修改 | Reminder表 | 加 todo_id, time_type, relative_minutes, repeat_config, completed_today, next_remind_date, next_remind_at, remind_count, max_remind_count, snooze_minutes，删除独立提醒场景 |
| 修改 | GrowthRecord表 | 调整字段支持喂养/睡眠/日常记录 |
| 重写 | 前端 | 从React/PWA迁移到uni-app，原生App体验，系统级推送 |
| 新增 | 推送通知 | uni-push + 个推/极光，到点必达 |
| 新增 | 语音输入/播放功能 | uni-app原生录音/播放 + 百度智能云ASR/TTS SDK |
