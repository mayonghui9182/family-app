#!/bin/bash
# =====================================
# 更新部署脚本（增量更新，不重建数据库）
# 用法：bash scripts/update.sh
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

echo -e "${BLUE}===== 家庭管理系统 - 更新部署 =====${NC}"
echo ""

# 加载环境变量
if [ -f .env ]; then
    source .env
fi

echo -e "${GREEN}[1/4] 备份数据库...${NC}"
if docker ps | grep -q family-postgres; then
    BACKUP_FILE="./backup/backup_$(date +%Y%m%d_%H%M%S).sql.gz"
    docker exec family-postgres pg_dump -U "${POSTGRES_USER:-family}" "${POSTGRES_DB:-family_app}" | gzip > "$BACKUP_FILE"
    echo -e "${GREEN}  备份完成${NC}"
fi

echo -e "${GREEN}[2/4] 重新构建镜像...${NC}"
docker compose build

echo -e "${GREEN}[3/4] 滚动重启服务...${NC}"
# 先重启后端，再重启前端
docker compose up -d --no-deps backend
sleep 3
docker compose up -d --no-deps frontend

echo -e "${GREEN}[4/4] 等待服务就绪...${NC}"
sleep 5

echo ""
echo -e "${BLUE}===== 服务状态 =====${NC}"
docker compose ps

echo ""
echo -e "${GREEN}===== 更新完成！=====${NC}"
