#!/usr/bin/env bash
# 将自定义格式备份写入用户指定目录，失败时不留下看似完整的文件。
set -euo pipefail
umask 077
backup_dir="${1:?请指定备份目录，例如 /srv/family-backups}"
mkdir -p "$backup_dir"
backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$backup_dir/familyapp-$backup_stamp-$$.dump"
backup_partial="$backup_file.partial"
trap 'rm -f -- "$backup_partial"' EXIT
docker compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup_partial"
docker compose exec -T postgres pg_restore --list < "$backup_partial" > /dev/null
mv -- "$backup_partial" "$backup_file"
printf '备份已完成：%s\n' "$backup_file"
