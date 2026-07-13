#!/bin/bash
# =====================================
# 服务器初始化脚本
# 在阿里云服务器上首次执行，安装 Docker、Docker Compose
# 用法：bash scripts/init-server.sh
# =====================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}===== 家庭管理系统 - 服务器初始化 =====${NC}"
echo ""

# 检查是否为 root 用户
if [ "$EUID" -ne 0 ]; then 
    echo -e "${RED}请使用 root 用户执行此脚本${NC}"
    exit 1
fi

echo -e "${GREEN}[1/6] 更新系统包...${NC}"
apt-get update -y && apt-get upgrade -y

echo -e "${GREEN}[2/6] 安装必要工具...${NC}"
apt-get install -y curl wget vim git unzip htop

echo -e "${GREEN}[3/6] 安装 Docker...${NC}"
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com | bash
    systemctl start docker
    systemctl enable docker
    echo -e "${GREEN}Docker 安装完成${NC}"
else
    echo -e "${YELLOW}Docker 已安装，跳过${NC}"
fi

echo -e "${GREEN}[4/6] 安装 Docker Compose...${NC}"
if ! docker compose version &> /dev/null; then
    # 使用 Docker 自带的 compose 插件
    apt-get install -y docker-compose-plugin
    echo -e "${GREEN}Docker Compose 安装完成${NC}"
else
    echo -e "${YELLOW}Docker Compose 已安装，跳过${NC}"
fi

echo -e "${GREEN}[5/6] 设置 Docker 镜像加速（阿里云）...${NC}"
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<EOF
{
    "registry-mirrors": [
        "https://registry.docker-cn.com",
        "https://docker.mirrors.ustc.edu.cn"
    ],
    "log-driver": "json-file",
    "log-opts": {
        "max-size": "100m",
        "max-file": "3"
    }
}
EOF
systemctl daemon-reload
systemctl restart docker

echo -e "${GREEN}[6/6] 创建应用目录...${NC}"
mkdir -p /opt/family-app/{backup,uploads}

echo ""
echo -e "${GREEN}===== 初始化完成！=====${NC}"
echo ""
echo "Docker 版本：$(docker --version)"
echo "Docker Compose 版本：$(docker compose version)"
echo ""
echo -e "${YELLOW}下一步：${NC}"
echo "  1. 将项目代码上传到 /opt/family-app"
echo "  2. cd /opt/family-app/deploy"
echo "  3. cp .env.example .env 并修改配置"
echo "  4. bash scripts/deploy.sh"
