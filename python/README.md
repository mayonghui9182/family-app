# Python 辅助服务

## 简介

Python辅助服务为家庭生活APP提供数据爬取、分析和AI推荐等辅助功能。

## 功能模块

### 1. 爬虫模块 (crawlers)

- **weather.py** - 天气数据爬取
  - 获取实时天气
  - 获取7天天气预报
  - 获取生活指数
  - 数据存入PostgreSQL

### 2. 数据分析模块 (analysis)

- **growth_stats.py** - 宝宝成长数据分析
  - 身高/体重统计
  - BMI计算
  - 成长趋势分析
  - 生成分析报告

### 3. 公共模块 (common)

- **db.py** - 数据库连接
  - PostgreSQL连接
  - SQLAlchemy ORM

## 安装依赖

```bash
pip install -r requirements.txt
```

## 使用方法

### 运行数据初始化

```bash
cd python
python scripts/init_data.py
```

### 单独运行天气爬取

```bash
python -m src.crawlers.weather
```

### 运行成长分析

```bash
python -m src.analysis.growth_stats
```

## 环境配置

创建 `.env` 文件：

```
DB_HOST=localhost
DB_PORT=5432
DB_NAME=family_app
DB_USER=postgres
DB_PASSWORD=postgres
```

## 与Java后端集成

Python服务通过数据库与Java后端共享数据，Java后端提供REST API，Python负责：
1. 定时爬取天气数据
2. 数据分析与报表生成
3. AI推荐算法（扩展）

## 定时任务

可使用APScheduler或系统cron配置定时任务。
