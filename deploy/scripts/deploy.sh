#!/bin/bash
# =====================================
# 一键部署脚本
# 用法：bash scripts/deploy.sh
# =====================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_DIR="$(dirname "$DEPLOY_DIR")"

cd "$DEPLOY_DIR"

echo -e "${BLUE}===== 家庭管理系统 - 一键部署 =====${NC}"
echo ""

# 检查 .env 文件
if [ ! -f .env ]; then
    echo -e "${YELLOW}未找到 .env 文件，从 .env.example 复制...${NC}"
    cp .env.example .env
    echo -e "${RED}请修改 .env 文件中的配置（尤其是密码和密钥）后重新执行${NC}"
    exit 1
fi

# 加载环境变量
source .env

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Docker 未安装，请先执行 scripts/init-server.sh${NC}"
    exit 1
fi

echo -e "${GREEN}[1/5] 备份数据库...${NC}"
if docker ps | grep -q family-postgres; then
    BACKUP_FILE="../backup/backup_$(date +%Y%m%d_%H%M%S).sql"
    docker exec family-postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > "$BACKUP_FILE" 2>/dev/null || true
    echo -e "${GREEN}  备份完成：$BACKUP_FILE${NC}"
else
    echo -e "${YELLOW}  数据库容器不存在，跳过备份${NC}"
fi

echo -e "${GREEN}[2/5] 构建后端镜像...${NC}"
# 检查后端 jar 包是否存在
JAR_COUNT=$(find "$PROJECT_DIR/backend/target" -name "*.jar" 2>/dev/null | grep -v sources | grep -v original | wc -l)
if [ "$JAR_COUNT" -eq 0 ]; then
    echo -e "${YELLOW}  未找到 jar 包，尝试构建...${NC}"
    if command -v mvn &> /dev/null; then
        cd "$PROJECT_DIR/backend"
        mvn clean package -DskipTests -q
        cd "$DEPLOY_DIR"
    else
        echo -e "${RED}  未找到 Maven，请先在本地构建后端：mvn clean package -DskipTests${NC}"
        exit 1
    fi
fi
echo -e "${GREEN}  后端准备完成${NC}"

echo -e "${GREEN}[3/5] 构建前端镜像...${NC}"
# 检查 node_modules
if [ ! -d "$PROJECT_DIR/frontend/node_modules" ]; then
    echo -e "${YELLOW}  安装前端依赖...${NC}"
    if command -v pnpm &> /dev/null; then
        cd "$PROJECT_DIR/frontend" && pnpm install --frozen-lockfile
        cd "$DEPLOY_DIR"
    elif command -v npm &> /dev/null; then
        cd "$PROJECT_DIR/frontend" && npm install
        cd "$DEPLOY_DIR"
    else
        echo -e "${YELLOW}  未找到 npm/pnpm，将使用 Docker 构建${NC}"
    fi
fi
echo -e "${GREEN}  前端准备完成${NC}"

echo -e "${GREEN}[4/5] 启动服务...${NC}"
docker compose down -v 2>/dev/null || true
docker compose up -d --build

echo -e "${GREEN}[5/5] 等待服务就绪...${NC}"
sleep 5

# 等待数据库就绪
echo -n "  等待数据库..."
for i in {1..30}; do
    if docker exec family-postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" &> /dev/null; then
        echo " ${GREEN}OK${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# 等待后端就绪
echo -n "  等待后端服务..."
for i in {1..60}; do
    if curl -s http://127.0.0.1:8080/actuator/health &> /dev/null; then
        echo " ${GREEN}OK${NC}"
        break
    fi
    # 如果没有 actuator 就用其他方式检查
    if docker logs family-backend 2>&1 | grep -q "Started.*Application"; then
        echo " ${GREEN}OK${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# 检查容器状态
echo ""
echo -e "${BLUE}===== 服务状态 =====${NC}"
docker compose ps

echo ""
echo -e "${GREEN}===== 部署完成！=====${NC}"
echo ""
echo "  访问地址：http://$(curl -s ifconfig.me 2>/dev/null || echo '服务器IP')"
echo ""
echo "  常用命令："
echo "    查看日志：  cd $DEPLOY_DIR && docker compose logs -f"
echo "    重启服务：  cd $DEPLOY_DIR && docker compose restart"
echo "    停止服务：  cd $DEPLOY_DIR && docker compose down"
echo "    备份数据：  bash $SCRIPT_DIR/backup.sh"
echo ""
