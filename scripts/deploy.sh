#!/usr/bin/env bash
# 一键部署：构建当前代码、备份数据库、保留旧镜像并验证所有服务健康。
set -euo pipefail
umask 077
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
compose=(docker compose -f docker-compose.yml -f docker-compose.production.yml)
build_args=()
check_only=0
for arg in "$@"; do
    case "$arg" in
        --tls) compose+=(-f docker-compose.tls.yml) ;;
        --no-cache) build_args+=(--no-cache) ;;
        --check) check_only=1 ;;
        -h|--help)
            echo '用法：bash scripts/deploy.sh [--tls] [--no-cache] [--check]'
            echo '默认加载生产配置；--tls 需要已配置证书；--check 仅检查编排。'
            exit 0 ;;
        *) echo "不支持的参数：$arg" >&2; exit 2 ;;
    esac
done
[[ -f .env ]] || { echo '请先根据 .env.example 配置 .env。' >&2; exit 1; }
command -v flock >/dev/null
exec 9>/tmp/family-app-deploy.lock
flock -n 9 || { echo '已有部署正在执行，请稍后重试。' >&2; exit 1; }
"${compose[@]}" config --quiet
docker info >/dev/null
if [[ "$check_only" == 1 ]]; then
    echo '生产编排检查通过。'
    exit 0
fi

release_dir="${DEPLOY_BACKUP_DIR:-$repo_root/.volumes/deployments}/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$release_dir"
trap 'echo "部署失败；检查上方错误。备份和旧镜像记录：$release_dir。未自动恢复数据库。" >&2' ERR
echo '正在打包当前后端代码……'
(cd backend && bash ./gradlew bootJar -x test --no-daemon)

# 构建前为当前运行镜像保留标签，防止构建覆盖原标签后无法回退。
release_tag="rollback-$(date -u +%Y%m%dT%H%M%SZ)-$$"
for service in $("${compose[@]}" config --services); do
    container_id="$("${compose[@]}" ps -a -q "$service")"
    [[ -n "$container_id" ]] || continue
    image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
    old_tag="familyapp-rollback/$service:$release_tag"
    docker image tag "$image_id" "$old_tag"
    printf '%s %s\n' "$service" "$old_tag" >> "$release_dir/images.txt"
done
echo '正在构建全部应用镜像；此阶段现有服务继续运行……'
"${compose[@]}" build "${build_args[@]}"

# 已存在的数据库必须正常运行并备份成功；不把停止的数据库误当成首次部署。
database_id="$("${compose[@]}" ps -a -q postgres)"
if [[ -n "$database_id" ]]; then
    echo '正在备份数据库……'
    "${compose[@]}" exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$release_dir/database.dump.partial"
    "${compose[@]}" exec -T postgres pg_restore --list < "$release_dir/database.dump.partial" > /dev/null
    mv "$release_dir/database.dump.partial" "$release_dir/database.dump"
fi
echo '正在更新服务并等待健康检查……'
"${compose[@]}" up -d --no-build --wait --wait-timeout 180
# 后端容器更新后重新解析 upstream，避免 nginx 保留旧容器地址。
"${compose[@]}" up -d --no-build --no-deps --force-recreate --wait --wait-timeout 60 nginx
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx wget -qO- http://127.0.0.1/nginx-health
"${compose[@]}" ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}'
echo "部署完成。数据库备份与旧镜像记录：$release_dir"
