# 家庭管理系统 - 阿里云部署指南

## 📋 目录

1. [服务器要求](#服务器要求)
2. [快速部署（推荐）](#快速部署推荐)
3. [手动部署步骤](#手动部署步骤)
4. [常用命令](#常用命令)
5. [域名与 HTTPS 配置](#域名与-https-配置)
6. [数据备份](#数据备份)
7. [常见问题](#常见问题)

---

## 🖥️ 服务器要求

| 配置 | 最低要求 | 推荐配置 |
|------|----------|----------|
| CPU | 1核 | 2核 |
| 内存 | 2GB | 4GB |
| 硬盘 | 20GB | 40GB+ |
| 系统 | Ubuntu 20.04/22.04 | Ubuntu 22.04 |
| 带宽 | 1Mbps | 3Mbps+ |

**推荐：** 阿里云轻量应用服务器（2核4G）即可流畅运行。

---

## 🚀 快速部署（推荐）

### 第一步：服务器初始化

```bash
# 1. 连接到阿里云服务器
ssh root@你的服务器IP

# 2. 下载项目代码（上传项目到服务器后跳过此步）
# 方式一：使用 git
git clone <你的仓库地址> /opt/family-app

# 方式二：使用 scp 上传（在本地执行）
scp -r ./项目目录 root@服务器IP:/opt/family-app
```

### 第二步：安装环境

```bash
cd /opt/family-app/deploy

# 一键安装 Docker、Docker Compose
bash scripts/init-server.sh
```

### 第三步：配置环境变量

```bash
cd /opt/family-app/deploy

# 复制配置模板
cp .env.example .env

# 编辑配置（重要！修改密码和密钥）
vim .env
```

需要修改的配置：
```env
# 数据库密码（必须修改！）
POSTGRES_PASSWORD=你的强密码

# JWT 密钥（必须修改！随机字符串至少32位）
JWT_SECRET=请修改为长随机字符串
```

### 第四步：构建后端

```bash
# 方式一：服务器上有 Maven 时直接构建
cd /opt/family-app/backend
mvn clean package -DskipTests

# 方式二：本地构建后上传 jar 包
# 在本地执行：
# cd backend && mvn clean package -DskipTests
# scp target/*.jar root@服务器IP:/opt/family-app/backend/target/
```

### 第五步：启动服务

```bash
cd /opt/family-app/deploy
bash scripts/deploy.sh
```

部署完成后，访问 `http://你的服务器IP` 即可使用！

---

## 📝 手动部署步骤

### 1. 安装 Docker

```bash
# 更新系统
apt update && apt upgrade -y

# 安装 Docker
curl -fsSL https://get.docker.com | bash

# 设置开机自启
systemctl enable docker
systemctl start docker

# 配置镜像加速
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
    "registry-mirrors": [
        "https://registry.docker-cn.com",
        "https://docker.mirrors.ustc.edu.cn"
    ]
}
EOF
systemctl daemon-reload
systemctl restart docker

# 验证安装
docker --version
docker compose version
```

### 2. 配置阿里云安全组

在阿里云控制台 → 安全组 → 入方向，开放以下端口：

| 端口 | 用途 | 是否必须 |
|------|------|----------|
| 22 | SSH 远程连接 | ✅ 必须 |
| 80 | HTTP 网站访问 | ✅ 必须 |
| 443 | HTTPS 网站访问 | ⭐ 推荐 |
| 5432 | PostgreSQL | ❌ 不要开放（仅本地访问） |
| 8080 | 后端API | ❌ 不要开放（仅本地访问） |

### 3. 部署应用

```bash
# 上传项目代码到服务器
# 进入 deploy 目录
cd /opt/family-app/deploy

# 复制并修改配置
cp .env.example .env
vim .env

# 启动服务
docker compose up -d --build

# 查看启动日志
docker compose logs -f
```

---

## 🔧 常用命令

```bash
# 进入 deploy 目录
cd /opt/family-app/deploy

# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f        # 所有服务
docker compose logs -f backend  # 仅后端
docker compose logs -f frontend # 仅前端

# 启动/停止/重启
docker compose up -d        # 启动
docker compose down         # 停止（不删除数据）
docker compose restart      # 重启所有
docker compose restart backend  # 重启后端

# 更新代码后重新部署
bash scripts/update.sh

# 备份数据库
bash scripts/backup.sh

# 恢复数据库
bash scripts/restore.sh backup_xxx.sql.gz
```

---

## 🌐 域名与 HTTPS 配置

### 绑定域名

1. 在阿里云域名控制台解析域名到服务器IP
2. 修改 Nginx 配置：

```bash
cd /opt/family-app/deploy/nginx/conf.d

# 编辑配置
vim default.conf
```

将 `server_name _;` 改为：
```nginx
server_name your-domain.com www.your-domain.com;
```

### 配置 HTTPS（免费证书）

```bash
# 安装 certbot
apt install -y certbot python3-certbot-nginx

# 申请证书（替换为你的域名）
certbot --nginx -d your-domain.com -d www.your-domain.com

# 证书会自动续期，无需手动操作
```

---

## 💾 数据备份

### 自动备份（推荐）

```bash
# 编辑 crontab
crontab -e

# 添加以下内容（每天凌晨3点备份）
0 3 * * * /opt/family-app/deploy/scripts/backup.sh >> /var/log/family-backup.log 2>&1

# 查看备份
ls -lh /opt/family-app/deploy/backup/
```

### 手动备份

```bash
bash scripts/backup.sh
```

### 数据恢复

```bash
bash scripts/restore.sh backup_20240101_030000.sql.gz
```

---

## ❓ 常见问题

### Q1: 访问不了网站？

检查：
1. 阿里云安全组是否开放了 80 端口
2. 容器是否正常运行：`docker compose ps`
3. 查看错误日志：`docker compose logs`

### Q2: 后端启动失败？

```bash
# 查看后端日志
docker compose logs backend

# 常见原因：
# - 数据库连接失败 → 检查 .env 中的数据库配置
# - 端口被占用 → 检查 8080 端口
```

### Q3: 忘记了管理员密码？

目前系统无密码登录，使用邀请码加入家庭。如需重置：

```bash
# 重置数据库（⚠️ 会清空所有数据！）
docker compose down -v
docker compose up -d
```

### Q4: 如何修改百度语音配置？

编辑 `.env` 文件，填入百度智能云的 API Key：

```env
BAIDU_SPEECH_APP_ID=你的AppID
BAIDU_SPEECH_API_KEY=你的APIKey
BAIDU_SPEECH_SECRET_KEY=你的SecretKey
```

然后重启后端：
```bash
docker compose restart backend
```

### Q5: 服务器内存不够怎么办？

1. 降低 JVM 内存：修改 `.env` 中的 `JAVA_OPTS`
2. 添加交换分区：

```bash
# 创建 2G 交换分区
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

---

## 📞 技术支持

如遇到问题，请检查：
1. 容器运行状态：`docker compose ps`
2. 服务日志：`docker compose logs -f --tail=100`
3. 服务器资源：`htop` 或 `free -h`
