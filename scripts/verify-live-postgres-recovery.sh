#!/usr/bin/env bash
# 在隔离 PostgreSQL 容器中恢复当前 Compose 数据库；仅用于发布前演练。
set -euo pipefail

if [[ "${1:-}" != "--confirm-live-restore-rehearsal" ]]; then
  echo "用法：$0 --confirm-live-restore-rehearsal" >&2
  exit 64
fi

umask 077
work_dir="$(mktemp -d /tmp/familyapp-live-restore.XXXXXX)"
container_name="familyapp-live-restore-$$"
archive_path="$work_dir/familyapp.dump"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

# pg_dump 只读访问现有 Compose 数据库；归档只存在于受限临时目录。
docker compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$archive_path"
docker compose exec -T postgres pg_restore --list < "$archive_path" >/dev/null

docker run -d --rm --name "$container_name" --network none \
  -e POSTGRES_USER=restore_check -e POSTGRES_DB=restore_check \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine >/dev/null
for attempt in {1..30}; do
  if docker exec "$container_name" pg_isready -U restore_check -d restore_check >/dev/null; then
    break
  fi
  sleep 1
done

docker exec -i "$container_name" pg_restore --exit-on-error --no-owner --no-privileges \
  -U restore_check -d restore_check < "$archive_path"

# 只比较核心表计数，不输出任何业务记录。
for table_name in users families tasks person_profiles; do
  source_count="$(docker compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -c \"SELECT count(*) FROM ${table_name}\"")"
  target_count="$(docker exec "$container_name" psql -U restore_check -d restore_check -At -c "SELECT count(*) FROM ${table_name}")"
  if [[ "$source_count" != "$target_count" ]]; then
    echo "恢复校验失败：${table_name} 行数不一致" >&2
    exit 1
  fi
done

echo "隔离恢复演练通过：归档可读，核心表结构与行数一致。临时归档和容器将自动清理。"
