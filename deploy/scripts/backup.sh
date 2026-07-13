#!/bin/bash
# =====================================
# 数据库备份脚本
# 用法：bash scripts/backup.sh
# 建议添加到 crontab 定时执行
# =====================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="$DEPLOY_DIR/backup"

cd "$DEPLOY_DIR"

echo -e "${YELLOW}===== 数据库备份 =====${NC}"

# 加载环境变量
if [ -f .env ]; then
    source .env
fi

POSTGRES_USER=${POSTGRES_USER:-family}
POSTGRES_DB=${POSTGRES_DB:-family_app}

# 创建备份目录
mkdir -p "$BACKUP_DIR"

# 备份文件名
BACKUP_FILE="$BACKUP_DIR/backup_$(date +%Y%m%d_%H%M%S).sql"
BACKUP_FILE_GZ="${BACKUP_FILE}.gz"

echo -e "${GREEN}备份数据库到：$BACKUP_FILE_GZ${NC}"

# 执行备份
docker exec family-postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$BACKUP_FILE_GZ"

# 获取备份文件大小
FILE_SIZE=$(du -h "$BACKUP_FILE_GZ" | cut -f1)
echo -e "${GREEN}备份完成，文件大小：$FILE_SIZE${NC}"

# 只保留最近 30 天的备份
echo -e "${YELLOW}清理30天前的备份...${NC}"
find "$BACKUP_DIR" -name "backup_*.sql.gz" -mtime +30 -delete

# 统计当前备份数量
BACKUP_COUNT=$(ls -1 "$BACKUP_DIR"/backup_*.sql.gz 2>/dev/null | wc -l)
echo -e "${GREEN}当前备份数量：$BACKUP_COUNT 个${NC}"

echo -e "${GREEN}===== 备份完成 =====${NC}"
