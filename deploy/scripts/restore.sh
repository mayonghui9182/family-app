#!/bin/bash
# =====================================
# 数据库恢复脚本
# 用法：bash scripts/restore.sh backup_xxx.sql.gz
# =====================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"

cd "$DEPLOY_DIR"

if [ -z "$1" ]; then
    echo -e "${RED}请指定备份文件路径${NC}"
    echo "用法：bash scripts/restore.sh backup_xxx.sql.gz"
    exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "$BACKUP_FILE" ]; then
    echo -e "${RED}备份文件不存在：$BACKUP_FILE${NC}"
    exit 1
fi

# 加载环境变量
if [ -f .env ]; then
    source .env
fi

POSTGRES_USER=${POSTGRES_USER:-family}
POSTGRES_DB=${POSTGRES_DB:-family_app}

echo -e "${YELLOW}===== 数据库恢复 =====${NC}"
echo -e "${RED}警告：此操作将覆盖当前数据库！${NC}"
echo -e "备份文件：$BACKUP_FILE"
echo ""
read -p "确认恢复？输入 YES 继续：" confirm

if [ "$confirm" != "YES" ]; then
    echo "已取消"
    exit 0
fi

echo -e "${GREEN}正在恢复数据库...${NC}"

# 停止后端服务
docker compose stop backend

# 恢复数据库
gunzip < "$BACKUP_FILE" | docker exec -i family-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"

# 重启后端
docker compose start backend

echo -e "${GREEN}===== 恢复完成 =====${NC}"
