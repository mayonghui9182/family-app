# 家庭日常生活APP

一款面向家庭的全方位生活管理应用，涵盖天气查询、旅游攻略、日常提醒、待办事项、宝宝成长记录等功能。

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue 3 + TypeScript + Vite | 3.5+ / 5.4+ |
| UI组件 | Element Plus + Tailwind CSS | 2.8+ / 3.4+ |
| 状态管理 | Pinia | 2.2+ |
| 后端 | Java 21 + Spring Boot 3.2 | 3.2.5 |
| ORM | MyBatis-Plus | 3.5.5 |
| 数据库 | PostgreSQL 16 / H2 (开发) | 16+ |
| 数据库迁移 | Flyway | 10.x |
| 辅助服务 | Python 3.12 | 3.12+ |
| API文档 | SpringDoc (Swagger) | 2.5+ |

## 项目结构

```
workspace/
├── frontend/                # Vue 前端项目
│   ├── src/
│   │   ├── api/            # API接口封装
│   │   ├── components/     # 公共组件
│   │   ├── layouts/        # 布局组件
│   │   ├── pages/          # 页面组件
│   │   ├── router/         # 路由配置
│   │   ├── stores/         # Pinia状态管理
│   │   ├── mock/           # Mock数据
│   │   ├── types/          # TypeScript类型
│   │   └── utils/          # 工具函数
│   ├── package.json
│   └── vite.config.ts
│
├── backend/                 # Java 后端项目
│   ├── src/main/
│   │   ├── java/com/family/app/
│   │   │   ├── controller/  # Controller层 (5个)
│   │   │   ├── service/     # Service层 (5个)
│   │   │   ├── mapper/      # Mapper接口 (14个)
│   │   │   ├── entity/      # 实体类 (14个)
│   │   │   ├── dto/         # 数据传输对象
│   │   │   ├── vo/          # 视图对象
│   │   │   ├── config/      # 配置类
│   │   │   └── common/      # 公共类
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/ # Flyway迁移脚本
│   └── pom.xml
│
├── python/                  # Python 辅助服务
│   ├── src/
│   │   ├── crawlers/        # 爬虫模块
│   │   ├── analysis/        # 数据分析模块
│   │   └── common/          # 公共模块
│   ├── scripts/             # 脚本
│   ├── requirements.txt
│   └── README.md
│
└── .trae/documents/         # 项目文档
    ├── PRD-家庭日常生活APP.md
    └── 技术架构-Vue+Java+PostgreSQL版.md
```

## 功能模块

### 1. 天气查询
- 🌤️ 实时天气展示
- 📅 7天天气预报
- 💧 生活指数（穿衣、紫外线、运动、洗车等）
- 🌬️ 空气质量指数

### 2. 旅游攻略
- 🏖️ 热门目的地推荐
- 🔍 攻略搜索筛选
- 📖 详细攻略展示
- 💡 出行贴士

### 3. 日常提醒
- ⏰ 定时提醒
- 🔄 重复提醒（每日/每周/每月）
- 📂 分类管理（生活/工作/健康/生日）
- 🔔 提醒开关

### 4. 待办事项
- ✅ 任务清单
- 🔥 优先级标记
- 📂 分类管理
- 📊 进度统计

### 5. 宝宝计划
- 👶 宝宝信息档案
- 📈 成长记录与曲线图
- 💉 疫苗接种提醒
- 🌟 成长里程碑
- 📝 日常记录

## 快速开始

### 环境要求

- Node.js >= 18
- Java >= 21
- Maven >= 3.9
- Python >= 3.12
- PostgreSQL >= 16 (可选，开发可用H2)

### 前端启动

```bash
cd frontend

# 安装依赖
pnpm install

# 启动开发服务器
pnpm dev

# 构建生产版本
pnpm build
```

访问地址：http://localhost:5173/

### 后端启动

```bash
cd backend

# 编译项目
mvn clean package -DskipTests

# 运行（使用H2内存数据库）
mvn spring-boot:run

# 或使用jar包
java -jar target/family-backend-1.0.0.jar
```

访问地址：
- API地址：http://localhost:8080/api/
- Swagger文档：http://localhost:8080/api/swagger-ui.html
- H2控制台：http://localhost:8080/api/h2-console

### 切换到PostgreSQL

1. 安装并启动PostgreSQL
2. 创建数据库：`CREATE DATABASE family_app;`
3. 修改 `backend/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/family_app
    username: postgres
    password: your_password
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
```

### Python辅助服务

```bash
cd python

# 安装依赖
pip install -r requirements.txt

# 运行数据初始化演示
python scripts/init_data.py

# 天气爬取
python -m src.crawlers.weather

# 成长分析
python -m src.analysis.growth_stats
```

## API接口列表

### 天气模块
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/weather/current` | 获取当前天气 |
| GET | `/api/weather/forecast` | 获取7天预报 |
| GET | `/api/weather/life-index` | 获取生活指数 |

### 旅游模块
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/travel/guides` | 攻略列表 |
| GET | `/api/travel/guides/{id}` | 攻略详情 |
| GET | `/api/travel/hot-destinations` | 热门目的地 |

### 提醒模块
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/reminders` | 提醒列表 |
| POST | `/api/reminders` | 新增提醒 |
| PUT | `/api/reminders/{id}` | 修改提醒 |
| DELETE | `/api/reminders/{id}` | 删除提醒 |
| PUT | `/api/reminders/{id}/toggle` | 切换开关 |

### 待办模块
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/todos` | 待办列表 |
| POST | `/api/todos` | 新增待办 |
| PUT | `/api/todos/{id}` | 修改待办 |
| DELETE | `/api/todos/{id}` | 删除待办 |
| PUT | `/api/todos/{id}/complete` | 标记完成 |

### 宝宝模块
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/baby/info` | 宝宝信息 |
| PUT | `/api/baby/info` | 更新宝宝信息 |
| GET | `/api/baby/growth` | 成长记录列表 |
| POST | `/api/baby/growth` | 添加成长记录 |
| DELETE | `/api/baby/growth/{id}` | 删除成长记录 |
| GET | `/api/baby/vaccines` | 疫苗列表 |
| PUT | `/api/baby/vaccines/{id}/toggle` | 标记疫苗完成 |
| GET | `/api/baby/milestones` | 里程碑列表 |
| PUT | `/api/baby/milestones/{id}/toggle` | 标记达成 |

## 开发说明

### 前端开发规范
- 使用 `<script setup lang="ts">` 语法
- 使用 Tailwind CSS 进行样式开发
- 使用 Pinia 管理状态
- 组件化开发，提高复用性

### 后端开发规范
- 遵循 RESTful API 设计规范
- Controller 统一返回 `Result<T>`
- 使用 MyBatis-Plus 简化数据库操作
- 使用 Flyway 管理数据库版本

### 数据库设计
共14张数据表，涵盖用户、天气、旅游、提醒、待办、宝宝等模块。
详细表结构见 `.trae/documents/技术架构-Vue+Java+PostgreSQL版.md`

## 设计特色

- 🎨 温暖橙色主题，粉色/绿色辅助色
- 📱 移动端优先的响应式设计
- ✨ 圆角卡片 + 渐变背景 + 柔和阴影
- 🎬 流畅的动画和过渡效果
- 🏗️ 清晰的代码结构和组件化设计

## 后续规划

- [ ] 用户认证与授权（JWT）
- [ ] 数据持久化（本地缓存 + 云端同步）
- [ ] 消息推送服务
- [ ] 家庭多人账号
- [ ] AI智能推荐
- [ ] 更多生活工具模块
- [ ] 小程序/App版本

## 许可证

MIT License
