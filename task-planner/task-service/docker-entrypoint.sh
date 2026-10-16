#!/bin/sh
# task-service 容器入口：先执行数据库迁移，成功后再启动 uvicorn。
# 迁移失败时以非 0 退出，避免服务在错误 schema 上对外提供接口（tasks.md 2.3）。
set -e

echo "[entrypoint] running alembic upgrade head ..."
if ! alembic upgrade head; then
    echo "[entrypoint] ERROR: alembic upgrade head failed; aborting startup" >&2
    exit 1
fi
echo "[entrypoint] migration done, starting: $*"

exec "$@"
