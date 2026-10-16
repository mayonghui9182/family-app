#!/usr/bin/env bash
# ===========================================================================
# family-app unified build / package / image / deploy / runtime control script
# Linux (WSL Ubuntu) version — original: scripts/dev.ps1
#
# 适用版本：主应用 + 微信服务 + task-planner 工作任务系统
#   - V10 Flyway migration 自动创建 wechat_bind 表（Change 11）
#   - V11 Flyway migration 自动创建 calendar_events 表（Change 13）
#   - 新增第 7 个容器 wechat-service（FastAPI:8900，无宿主端口，经 nginx /wechat/ 分流）
#   - 新增环境变量（.env.example 段5）：WECHAT_APP_ID/SECRET/TOKEN/OAUTH_BASE（Change 11）
#     + WECHAT_NOTIFY_ENABLED / WECHAT_TPL_TASK_DIGEST_ID / WECHAT_TPL_SUMMARY_ID
#     / WECHAT_TPL_TASK_ASSIGN_ID（Change 12）/ WECHAT_TPL_EVENT_DIGEST_ID（Change 13）
#     （四个模板 ID 留空即对应推送事件静默跳过，preflight 计数分母为 4）
#
# One-stop entry covering compile, package, docker image build, compose
# start/stop/restart/logs/status, interactive shells, and cleanup.
#
# Usage examples:
#   ./scripts/dev.sh help
#   ./scripts/dev.sh package
#   ./scripts/dev.sh build-images
#   ./scripts/dev.sh up
#   ./scripts/dev.sh down
#   ./scripts/dev.sh logs backend
#
# All commands execute relative to the repository root (parent of scripts/).
# ===========================================================================

set -e

# ===========================================================================
# shell 兼容性保护：脚本使用 bash-only 语法（[[, BASH_REMATCH, local 等）
# 如果用户用 sh/dash 执行（常见错误 `sh ./scripts/dev.sh`），自动以 bash 重跑
# ===========================================================================
SH_REEXEC=0
if [ -z "$BASH_VERSION" ]; then
    SH_REEXEC=1
fi
# 再检测 pipefail 支持（某些嵌入式 bash 也可能缺）
if (set -o pipefail) >/dev/null 2>&1; then
    set -o pipefail
else
    SH_REEXEC=1
fi
if [ "$SH_REEXEC" -eq 1 ] && command -v bash >/dev/null 2>&1; then
    echo "[自动切换] 检测到当前 shell 不支持 bash 语法，改用 bash 执行..."
    echo "       （推荐下次直接用: bash $0 $*  或  ./$0 $*）"
    exec bash "$0" "$@"
fi

# ---------------------------------------------------------------------------
# Paths (always resolve relative to the repository root)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
FRONTEND_DIR="$REPO_ROOT/frontend"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
VOLUMES_DIR="$REPO_ROOT/.volumes"
PG_DATA_DIR="$VOLUMES_DIR/postgres"

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# .env.example → .env 自动复制（首次部署友好）
# ---------------------------------------------------------------------------
# 生产部署由专用入口处理，避免旧环境变量解析器改变密码中的特殊字符。
if [[ "${1:-}" == "deploy" ]]; then
    shift
    exec bash "$SCRIPT_DIR/deploy.sh" "$@"
fi
ENV_FILE="$REPO_ROOT/.env"
ENV_EXAMPLE="$REPO_ROOT/.env.example"
if [[ ! -f "$ENV_FILE" && -f "$ENV_EXAMPLE" ]]; then
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    echo "[+] 首次部署：已从 .env.example 复制到 .env（请按需修改密码和密钥）"
fi

# ---------------------------------------------------------------------------
# Optional .env loader (JAVA_HOME / NODE_HOME / GRADLE_USER_HOME / etc.)
# ---------------------------------------------------------------------------
if [[ -f "$ENV_FILE" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
        # strip comments and trim
        trimmed="${line%%#*}"
        trimmed="$(echo "$trimmed" | xargs)"
        [[ -z "$trimmed" ]] && continue
        if [[ "$trimmed" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
            k="${BASH_REMATCH[1]}"
            v="${BASH_REMATCH[2]}"
            # strip surrounding quotes
            v="$(echo "$v" | sed 's/^["'\'']//;s/["'\'']$//')"
            export "$k=$v"
        fi
    done < "$ENV_FILE"
fi

# ---------------------------------------------------------------------------
# CLI argument parsing
# ---------------------------------------------------------------------------
COMMAND="${1:-help}"
shift || true

NO_CACHE=0
QUICK=0
PURGE_DATA=0
FORCE=0
PURGE_CONTAINERS=0
PURGE_IMAGES=0
PURGE_ALL=0
COMMAND_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-cache)         NO_CACHE=1 ;;
        --quick)            QUICK=1 ;;
        --purge-data)       PURGE_DATA=1 ;;
        --force)            FORCE=1 ;;
        --purge-containers) PURGE_CONTAINERS=1 ;;
        --purge-images)     PURGE_IMAGES=1 ;;
        --purge-all)        PURGE_ALL=1 ;;
        -h|--help)          COMMAND="help" ;;
        *)                  COMMAND_ARGS+=("$1") ;;
    esac
    shift
done

# If --purge-all, enable everything
if [[ $PURGE_ALL -eq 1 ]]; then
    PURGE_CONTAINERS=1
    PURGE_IMAGES=1
fi

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
c_cyan()    { echo -e "\033[1;36m$*\033[0m"; }
c_green()   { echo -e "\033[1;32m$*\033[0m"; }
c_yellow()  { echo -e "\033[1;33m$*\033[0m"; }
c_red()     { echo -e "\033[1;31m$*\033[0m"; }
c_gray()    { echo -e "\033[0;90m$*\033[0m"; }

write_step()  { c_cyan  "[+] $*"; }
write_ok()    { c_green "[OK] $*"; }
write_warn()  { c_yellow "[!!] $*"; }
write_fail()  { c_red   "[XX] $*"; }

have_cmd() { command -v "$1" >/dev/null 2>&1; }

safe_run() {
    echo -e "\033[0;90m> $*\033[0m" >&2
    "$@"
}

ensure_dir() {
    [[ -d "$1" ]] || mkdir -p "$1"
}

confirm_action() {
    local prompt="$1" auto_yes="$2"
    [[ "$auto_yes" == "1" ]] && return 0
    read -r -p "$prompt (y/N) " ans
    [[ "$ans" =~ ^[yY](es)?$ ]]
}

# 开发环境必填密钥引导：只补缺失值，绝不覆盖已有配置。
# 已有 task-planner 数据卷必须继续使用原密码；容器仍存在时从容器环境恢复，
# 无法确认旧密码时拒绝启动，避免生成新密码后把持久化数据库锁死。
random_secret() {
    if have_cmd openssl; then
        openssl rand -hex 32
    else
        od -An -N32 -tx1 /dev/urandom | tr -d " \n"
    fi
}

set_env_value() {
    local key="$1" value="$2" tmp
    if grep -qE "^$key=" "$ENV_FILE" 2>/dev/null; then
        tmp="$(mktemp)"
        awk -v key="$key" -v value="$value" \
            'index($0, key "=") == 1 {$0 = key "=" value} {print}' \
            "$ENV_FILE" > "$tmp"
        mv "$tmp" "$ENV_FILE"
    else
        printf "\n%s=%s\n" "$key" "$value" >> "$ENV_FILE"
    fi
    export "$key=$value"
}

ensure_required_dev_secrets() {
    local value existing_volume
    [[ -f "$ENV_FILE" ]] || touch "$ENV_FILE"

    if [[ -z "$INTERNAL_API_SECRET" ]]; then
        value="$(random_secret)"
        set_env_value INTERNAL_API_SECRET "$value"
        write_ok "已为开发环境生成 INTERNAL_API_SECRET（值未输出）。"
    fi

    if [[ -z "$TP_POSTGRES_PASSWORD" ]]; then
        value=""
        if docker inspect familyapp-tp-task-db >/dev/null 2>&1; then
            value="$(docker inspect familyapp-tp-task-db --format "{{range .Config.Env}}{{println .}}{{end}}" \
                | sed -n "s/^POSTGRES_PASSWORD=//p" | head -n 1)"
            [[ -n "$value" ]] && write_ok "已从现有 task-planner 数据库容器恢复 TP_POSTGRES_PASSWORD（值未输出）。"
        fi

        if [[ -z "$value" ]]; then
            existing_volume="$(docker volume ls -q | grep -E "(^|_)tp-postgres-data$" | head -n 1 || true)"
            if [[ -n "$existing_volume" ]]; then
                write_fail "检测到已有 task-planner 数据卷 $existing_volume，但无法确认原数据库密码。"
                c_gray "  请先把原密码写入 .env 的 TP_POSTGRES_PASSWORD，再重新启动。"
                return 1
            fi
            value="$(random_secret)"
            write_ok "已为全新 task-planner 数据库生成 TP_POSTGRES_PASSWORD（值未输出）。"
        fi
        set_env_value TP_POSTGRES_PASSWORD "$value"
    fi
}

# Detect whether a file uses CRLF line endings.
has_crlf() {
    [[ -f "$1" ]] || return 1
    file "$1" 2>/dev/null | grep -qi 'CRLF' && return 0
    grep -q $'\r$' "$1" 2>/dev/null
}

# Convert a file from CRLF -> LF in place (only when needed).
normalize_lf() {
    local f="$1"
    [[ -f "$f" ]] || return 0
    if has_crlf "$f"; then
        write_warn "Converting CRLF -> LF: ${f#$REPO_ROOT/}"
        sed -i 's/\r$//' "$f"
    fi
}

# Pre-build sanity checks. Returns 0 on success, non-zero if a hard blocker remains.
preflight_checks() {
    local errors=0
    write_step "Preflight checks ..."

    local gw="$BACKEND_DIR/gradlew"
    if [[ -f "$gw" ]]; then
        if has_crlf "$gw"; then
            normalize_lf "$gw"
            chmod +x "$gw"
            has_crlf "$gw" && {
                write_fail "backend/gradlew still has CRLF after fix. Run: sed -i 's/\r\$//' $gw"
                errors=$((errors+1))
            } || write_ok "backend/gradlew line endings OK (auto-fixed)."
        else
            [[ -x "$gw" ]] || chmod +x "$gw"
            c_gray "  -> gradlew OK"
        fi
    fi

    local wrapper_jar="$BACKEND_DIR/gradle/wrapper/gradle-wrapper.jar"
    if [[ ! -f "$wrapper_jar" ]]; then
        write_fail "Missing $wrapper_jar — Gradle wrapper not initialised."
        errors=$((errors+1))
    fi

    if [[ $errors -gt 0 ]]; then
        write_fail "Preflight found $errors blocker(s). Fix above and retry."
        return 1
    fi
    write_ok "Preflight passed."
}

# ---------------------------------------------------------------------------
# Cloudflare Tunnel 检查（公网入口保障）
# ---------------------------------------------------------------------------
CLOUDFLARED_TUNNEL_NAME="familyapp"
CLOUDFLARED_HOSTNAME="app.mayh.net.cn"
CLOUDFLARED_CONFIG="$HOME/.cloudflared/config.yml"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-}"

# 定位 cloudflared 二进制
_find_cloudflared() {
    [[ -n "$CLOUDFLARED_BIN" && -x "$CLOUDFLARED_BIN" ]] && return 0
    local candidates=(
        "$HOME/.local/bin/cloudflared"
        "/usr/local/bin/cloudflared"
        "/usr/bin/cloudflared"
    )
    local c
    for c in "${candidates[@]}"; do
        if [[ -x "$c" ]]; then CLOUDFLARED_BIN="$c"; return 0; fi
    done
    if have_cmd cloudflared; then CLOUDFLARED_BIN="cloudflared"; return 0; fi
    return 1
}

# 检查 cloudflared tunnel 是否活跃；未运行则自动启动。
# 策略：优先 systemd service（已持久化），回退手动后台启动。
check_cloudflared() {
    write_step "检查 Cloudflare Tunnel ($CLOUDFLARED_TUNNEL_NAME) ..."

    # ---- 1. 定位 cloudflared 二进制 ----
    if ! _find_cloudflared; then
        write_warn "cloudflared 二进制未找到，跳过 tunnel 检查。"
        c_gray "  提示：可从 https://github.com/cloudflare/cloudflared/releases 安装到 ~/.local/bin/"
        return 0  # 不阻塞部署
    fi

    # ---- 2. 检查 tunnel 是否已连接 ----
    local connections=0
    if "$CLOUDFLARED_BIN" tunnel info "$CLOUDFLARED_TUNNEL_NAME" 2>/dev/null | grep -q "CONNECTOR ID"; then
        # 有 connector 行 = tunnel 活跃
        connections=$("$CLOUDFLARED_BIN" tunnel info "$CLOUDFLARED_TUNNEL_NAME" 2>/dev/null \
            | grep -cE '^[0-9a-f]{8}-[0-9a-f]{4}' || echo 0)
    fi

    if [[ $connections -gt 0 ]]; then
        write_ok "Cloudflare Tunnel 运行中（$connections 条连接）→ https://$CLOUDFLARED_HOSTNAME/"
        return 0
    fi

    # ---- 3. Tunnel 未运行，尝试启动 ----
    write_warn "Tunnel 未运行（0 条连接），正在启动 ..."

    local started=0

    # 策略 A：systemd service（首选，已持久化）
    if command -v systemctl >/dev/null 2>&1 && \
       systemctl list-unit-files 2>/dev/null | grep -q '^cloudflared.service'; then
        c_gray "  尝试 systemctl start cloudflared ..."
        if sudo -n systemctl start cloudflared 2>/dev/null || \
           systemctl start cloudflared 2>/dev/null; then
            started=1
            c_gray "  systemd start 已触发，等待连接建立 ..."
        else
            c_gray "  systemctl start 需要密码或失败，回退到手动启动。"
        fi
    fi

    # 策略 B：手动后台启动（回退）
    if [[ $started -eq 0 && -f "$CLOUDFLARED_CONFIG" ]]; then
        c_gray "  手动后台启动 cloudflared tunnel ..."
        nohup "$CLOUDFLARED_BIN" tunnel --config "$CLOUDFLARED_CONFIG" run \
            "$CLOUDFLARED_TUNNEL_NAME" >/tmp/cloudflared.log 2>&1 &
        started=1
    elif [[ $started -eq 0 ]]; then
        write_fail "无法启动 cloudflared：既无 systemd service，也无 config 文件 ($CLOUDFLARED_CONFIG)"
        return 1
    fi

    # ---- 4. 等待连接建立（最多 20s）----
    local waited=0 interval=2 max=20
    while [[ $waited -lt $max ]]; do
        sleep $interval
        waited=$((waited + interval))
        if "$CLOUDFLARED_BIN" tunnel info "$CLOUDFLARED_TUNNEL_NAME" 2>/dev/null \
            | grep -q "CONNECTOR ID"; then
            local new_conns
            new_conns=$("$CLOUDFLARED_BIN" tunnel info "$CLOUDFLARED_TUNNEL_NAME" 2>/dev/null \
                | grep -cE '^[0-9a-f]{8}-[0-9a-f]{4}' || echo 0)
            if [[ $new_conns -gt 0 ]]; then
                write_ok "Cloudflare Tunnel 已启动（${new_conns} 条连接，等待 ${waited}s）→ https://$CLOUDFLARED_HOSTNAME/"
                return 0
            fi
        fi
        c_gray "  等待 tunnel 连接 ... ${waited}s / ${max}s"
    done

    write_warn "Tunnel 启动超时（${max}s 内未建立连接）。检查日志：/tmp/cloudflared.log"
    return 1
}

# ---------------------------------------------------------------------------
# Environment checks
# ---------------------------------------------------------------------------
assert_docker() {
    if ! have_cmd docker; then
        write_fail "Docker is required but not on PATH. Install Docker (or Docker Desktop for WSL) and try again."
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        write_fail "docker compose plugin is required but unavailable."
        docker compose version
        exit 1
    fi
}

assert_java() {
    if [[ -n "$JAVA_HOME" ]]; then
        if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
            write_fail "JAVA_HOME is set but $JAVA_HOME/bin/java does not exist."
            exit 1
        fi
        export PATH="$JAVA_HOME/bin:$PATH"
        return
    fi
    if ! have_cmd java; then
        write_fail "Java is required but not found. Install JDK 17+ or set JAVA_HOME in .env."
        exit 1
    fi
}

assert_gradle() {
    local gradle_cmd=""
    if [[ -x "$BACKEND_DIR/gradlew" ]]; then
        GRADLE_CMD="$BACKEND_DIR/gradlew"
    elif have_cmd gradle; then
        GRADLE_CMD="gradle"
    else
        write_fail "Neither backend/gradlew nor global 'gradle' were found."
        exit 1
    fi
}

assert_node() {
    if [[ -n "$NODE_HOME" ]]; then
        if [[ ! -x "$NODE_HOME/node" ]]; then
            write_fail "NODE_HOME is set but $NODE_HOME/node does not exist."
            exit 1
        fi
        export PATH="$NODE_HOME:$PATH"
    fi
    if ! have_cmd node; then
        write_fail "Node.js is required but not on PATH. Install Node 20+ or set NODE_HOME in .env."
        exit 1
    fi
    if ! have_cmd npm; then
        write_fail "npm not found alongside Node.js."
        exit 1
    fi
}

ensure_frontend_node_modules() {
    assert_node
    local nm="$FRONTEND_DIR/node_modules"
    if [[ ! -d "$nm" ]]; then
        write_step "Running 'npm install' in frontend (first-time bootstrap)..."
        ( cd "$FRONTEND_DIR" && safe_run npm install --no-audit --no-fund )
    fi
}

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
write_help() {
    cat <<EOF
USAGE:
  dev.sh <command> [options]

  Pre-flight
    preflight            Check repo is build-ready (gradlew line endings,
                         wrapper jar, permissions). Auto-fixes CRLF -> LF.

  Build / Compile (local, no Docker)
    compile              Compile backend + frontend (no tests)
    package              Build backend bootJar + frontend production dist
    test                 Run backend unit tests + frontend build

  Docker Images
    build-images         Build backend + frontend images (--no-cache to rebuild)
    build-backend-image  Build only backend image
    build-frontend-image Build only frontend image

  Deploy / Runtime (docker compose)
    deploy      [--tls]   生产部署：构建当前代码、数据库备份、健康检查
    up          [--quick] Start all 10 services
                         (nginx, postgres, backend, frontend, tool-service,
                          agent-service, wechat-service, tp-task-db,
                          tp-task-service, tp-agent-service). Default: build first.
                         --quick skips the compose build step (uses cache;
                         镜像缺失的服务仍会自动构建).
                         首次运行自动从 .env.example 复制到 .env。
    down        [--purge-data] [--force]
                         Stop and remove containers. --purge-data deletes
                         .volumes/postgres (needs --force or Y prompt).
    restart     [svc]    Restart all or one service
                         (nginx|postgres|backend|frontend|tool-service|
                          agent-service|wechat-service)
    status               docker compose ps + health (7 服务)

  E2E 冒烟测试
    e2e                  运行 E2E 冒烟脚本（需先 docker compose up）
    e2e-flyway           运行 Flyway 幂等性验证（仅启动 postgres）
    e2e-config           仅验证 /api/agent/v1/config 端点（快速健康检查）

  Interact
    logs        [svc]    Tail 50 lines of all or a single service logs.
    backend-logs         Shortcut for logs backend
    frontend-logs        Shortcut for logs frontend
    db-shell             Enter a postgres psql shell inside the postgres container

  Cleanup
    clean                Remove local build artifacts (backend/build + frontend/dist)
    purge                clean + optionally prune images/containers. Use
                         --purge-containers / --purge-images / --purge-all to select.

  Meta
    help / -h            Show this help.

EXAMPLES
  # 1. Fresh run (compile + build images + start)
  ./dev.sh package
  ./dev.sh build-images
  ./dev.sh up --quick

  # 2. Just start (after first build)
  ./dev.sh up --quick

  # 3. See backend logs
  ./dev.sh logs backend

  # 4. Stop (keep postgres data)
  ./dev.sh down

  # 5. Stop + FULLY RESET postgres data (prompt)
  ./dev.sh down --purge-data
EOF
}

assert_compose_file() {
    if [[ ! -f "$COMPOSE_FILE" ]]; then
        write_fail "docker-compose.yml not found at $COMPOSE_FILE"
        exit 1
    fi
    ensure_required_dev_secrets || exit 1
}

# ---------------------------------------------------------------------------
# Build (local)
# ---------------------------------------------------------------------------
invoke_compile() {
    write_step "Compiling backend ..."
    assert_java; assert_gradle
    ( cd "$BACKEND_DIR" && safe_run "$GRADLE_CMD" compileJava compileTestJava bootJar -x test --no-daemon )
    write_step "Compiling frontend (npm run build) ..."
    ensure_frontend_node_modules
    ( cd "$FRONTEND_DIR" && safe_run npm run build )
    write_ok "Compile complete."
}

# Locate the most recent backend bootJar, if any. Returns 0 and echoes the path.
_find_backend_jar() {
    local latest
    latest=$(ls -t "$BACKEND_DIR"/build/libs/*.jar 2>/dev/null | head -n1)
    [[ -n "$latest" ]] && echo "$latest"
}

package_backend() {
    local clean="$1"
    write_step "Packaging backend bootJar ..."
    assert_java; assert_gradle
    preflight_checks || return 1
    # 注意：gradle 没有 --clean 选项；需清理时改为先跑 clean 任务。
    local args=( bootJar -x test --no-daemon )
    [[ "$clean" == "1" ]] && args=( clean "${args[@]}" )
    # 显式 || return 1：本函数常被 `package_backend ... || return 1` 调用，
    # 该上下文下 set -e 失效，必须手动传递失败，否则会沿用旧 jar 静默成功。
    ( cd "$BACKEND_DIR" && safe_run "$GRADLE_CMD" "${args[@]}" ) || return 1
    local j
    j=$(_find_backend_jar)
    if [[ -n "$j" ]]; then
        local sz
        sz=$(du -sk "$j" | cut -f1)
        c_gray "  -> ${j#$REPO_ROOT/}  ${sz} KB"
    else
        write_fail "bootJar not produced under backend/build/libs/."
        return 1
    fi
}

package_frontend() {
    write_step "Packaging frontend dist ..."
    ensure_frontend_node_modules
    ( cd "$FRONTEND_DIR" && safe_run npm run build )
    local dist="$FRONTEND_DIR/dist"
    if [[ -d "$dist" ]]; then
        local sz
        sz=$(du -sk "$dist" | cut -f1)
        c_gray "  -> frontend/dist  ${sz} KB"
    fi
}

# Ensure backend bootJar exists. If --no-cache is set OR no jar is found,
# re-run the Gradle build locally (way faster than building inside Docker).
ensure_backend_bootjar() {
    local force_rebuild="$1"
    local j
    j=$(_find_backend_jar)
    if [[ -z "$j" || "$force_rebuild" == "1" ]]; then
        if [[ -z "$j" ]]; then
            write_warn "No backend bootJar found under backend/build/libs/. Packaging locally first..."
        else
            write_step "--no-cache set: rebuilding backend bootJar locally ..."
        fi
        package_backend "$force_rebuild" || return 1
    else
        write_ok "Using existing backend bootJar: ${j#$REPO_ROOT/}"
    fi
}

invoke_package() {
    package_backend "1" || return 1
    package_frontend || return 1
    write_ok "Package complete."
}

invoke_test() {
    write_step "Running backend unit tests ..."
    assert_java; assert_gradle
    ( cd "$BACKEND_DIR" && safe_run "$GRADLE_CMD" test --no-daemon )
    write_step "Verifying frontend build ..."
    ensure_frontend_node_modules
    ( cd "$FRONTEND_DIR" && safe_run npm run build )
    write_ok "All tests + frontend build passed."
}

# ---------------------------------------------------------------------------
# Docker images
# ---------------------------------------------------------------------------
invoke_build_images() {
    local services=("$@")
    assert_docker; assert_compose_file
    preflight_checks || return 1

    # Decide whether backend image is being built.
    local build_backend=1
    if [[ ${#services[@]} -gt 0 ]]; then
        build_backend=0
        local s
        for s in "${services[@]}"; do
            [[ "$s" == "backend" ]] && build_backend=1
        done
    fi

    # The optimized backend Dockerfile copies the pre-built bootJar from
    # host build/libs/ (instead of downloading Gradle + deps inside the
    # container every time — that was the 20+ minute hang).
    if [[ $build_backend -eq 1 ]]; then
        # 每次构建后端镜像都重新执行增量打包，不能仅按 JAR 是否存在复用旧代码。
        package_backend "$NO_CACHE" || return 1
    fi

    local args=( -f "$COMPOSE_FILE" build )
    [[ $NO_CACHE -eq 1 ]] && args+=( --no-cache )
    [[ ${#services[@]} -gt 0 ]] && args+=( "${services[@]}" )

    write_step "docker compose build (services: ${services[*]:-all}; no-cache=$NO_CACHE) ..."
    if ! safe_run docker compose "${args[@]}"; then
        write_fail "Image build failed. Common causes:"
        c_gray "  - backend/build/libs/*.jar missing? Run: ./dev.sh package"
        c_gray "  - gradle wrapper issue? Run: ./dev.sh preflight"
        c_gray "  - network pull timeouts — retry, or pre-pull base images with 'docker pull'"
        return 1
    fi
    write_ok "Image build complete."
}

# ---------------------------------------------------------------------------
# Compose lifecycle
# ---------------------------------------------------------------------------
invoke_up() {
    local quick="$1"
    assert_docker; assert_compose_file
    preflight_checks || return 1
    ensure_dir "$VOLUMES_DIR"
    ensure_dir "$PG_DATA_DIR"

    # Even with --quick, the new backend Dockerfile needs a locally built
    # bootJar. `docker compose up -d` will auto-build the backend image if
    # it's missing, and without a pre-built jar the COPY step fails.
    ensure_backend_bootjar "$NO_CACHE" || return 1

    if [[ "$quick" != "1" ]]; then
        # Full image build — uses all optimizations (reuses backend jar,
        # reports build errors with hints).
        invoke_build_images || return 1
    else
        write_step "Skipping explicit docker compose build (--quick set)."
    fi

    write_step "Starting services (postgres -> backend -> frontend via healthcheck) ..."
    if ! safe_run docker compose -f "$COMPOSE_FILE" up -d; then
        write_fail "compose up failed. If this was a COPY error on backend/build/libs/*.jar, run './dev.sh package' then retry."
        return 1
    fi

    write_step "Waiting for all compose services healthy (max ~180s) ..."
    local waited=0 interval=5 max=180 all_healthy
    local expected_services
    expected_services=$(docker compose -f "$COMPOSE_FILE" config --services | sed "/^$/d" | wc -l)
    while :; do
        all_healthy=1
        local running_count container_count unhealthy_count
        running_count=$(docker compose -f "$COMPOSE_FILE" ps --status running --services 2>/dev/null \
            | sed "/^$/d" | wc -l)
        container_count=$(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null \
            | sed "/^$/d" | wc -l)
        unhealthy_count=0
        while IFS= read -r container_id; do
            [[ -z "$container_id" ]] && continue
            local health
            health=$(docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" \
                "$container_id" 2>/dev/null || echo missing)
            if [[ "$health" != "healthy" && "$health" != "none" ]]; then
                unhealthy_count=$((unhealthy_count + 1))
            fi
        done < <(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)

        if [[ "$running_count" -ne "$expected_services" \
              || "$container_count" -ne "$expected_services" \
              || "$unhealthy_count" -ne 0 ]]; then
            all_healthy=0
        fi
        c_gray "  services: running=${running_count}/${expected_services}, containers=${container_count}/${expected_services}, unhealthy=${unhealthy_count}; waited ${waited}s / ${max}s"
        if [[ $all_healthy -eq 1 ]]; then break; fi
        if [[ $waited -ge $max ]]; then break; fi
        sleep $interval
        waited=$((waited + interval))
    done

    invoke_status

    if [[ $all_healthy -eq 1 ]]; then
        write_ok "All services healthy. (${expected_services}/${expected_services})"
        echo ""
        echo "  Frontend (nginx):      http://localhost:${NGINX_PORT:-80}/"
        echo "  Backend API:           http://localhost:${NGINX_PORT:-80}/api/（8080 无宿主端口，经 nginx 分流）"
        echo "  WeChat 入口:           http://localhost:${NGINX_PORT:-80}/wechat/（authorize / oauth/callback / callback）"
        echo "  Postgres (local):      localhost:5432 db=familyapp user=familyapp_user"
        echo "  tool-service (内部):   http://localhost:8080/api/tools/ (经 backend 代理)"
        echo "  agent-service (内部):  http://localhost:8080/api/agent/ (经 backend 代理)"
        echo "  wechat-service (内部): 经 nginx /wechat/ 与 backend infra-tier 代理，无宿主端口"
        echo "  工作任务 REST:         http://localhost:${NGINX_PORT:-80}/api/work-tasks/"
        echo "  工作任务 Agent:        http://localhost:${NGINX_PORT:-80}/api/work-agent/"
        echo "  AI 拆解开关:           AI_DECOMPOSE_ENABLED=$([[ -f "$ENV_FILE" ]] && grep -E '^AI_DECOMPOSE_ENABLED=' "$ENV_FILE" | cut -d= -f2 || echo 'true（默认）')"
        local tpl_count=0
        [[ -f "$ENV_FILE" ]] && tpl_count=$(grep -cE '^WECHAT_TPL_.+=.+' "$ENV_FILE" || true)
        echo "  微信推送开关:          WECHAT_NOTIFY_ENABLED=$([[ -f "$ENV_FILE" ]] && grep -E '^WECHAT_NOTIFY_ENABLED=' "$ENV_FILE" | cut -d= -f2 || echo 'true（默认）')"
        echo "  微信模板 ID:           WECHAT_TPL_*=${tpl_count}/4 已配置（0=全部事件静默跳过）"
        # ---- Cloudflare Tunnel 检查 ----
        check_cloudflared || true
    else
        write_warn "Timeout reached before all services healthy. Run './dev.sh logs <svc>' to diagnose."
    fi
}

invoke_down() {
    local purge_data="$1" force="$2"
    assert_docker; assert_compose_file

    write_step "Stopping and removing containers ..."
    safe_run docker compose -f "$COMPOSE_FILE" down

    if [[ "$purge_data" == "1" ]]; then
        if [[ ! -d "$PG_DATA_DIR" ]]; then
            write_warn "No postgres data dir to remove ($PG_DATA_DIR)."
            return
        fi
        if [[ "$force" != "1" ]]; then
            read -r -p "This will PERMANENTLY DELETE '$PG_DATA_DIR' (cannot recover). Type 'YES' to continue: " answer
            if [[ "$answer" != "YES" ]]; then
                write_warn "Skipped purge-data."
                return
            fi
        fi
        write_step "Removing $PG_DATA_DIR ..."
        rm -rf "$PG_DATA_DIR"
        write_ok "Purge-data complete."
    else
        write_ok "Containers stopped. Data kept at $PG_DATA_DIR."
    fi
}

invoke_restart() {
    local target="$1"
    assert_docker; assert_compose_file
    local args=( -f "$COMPOSE_FILE" restart )
    [[ -n "$target" ]] && args+=( "$target" )
    safe_run docker compose "${args[@]}"
    invoke_status
}

invoke_status() {
    assert_docker; assert_compose_file
    docker compose -f "$COMPOSE_FILE" ps || {
        write_fail "compose ps failed with $?"
        return 1
    }
    # Cloudflare Tunnel 状态行
    echo ""
    if _find_cloudflared 2>/dev/null; then
        local conn
        conn=$("$CLOUDFLARED_BIN" tunnel info "$CLOUDFLARED_TUNNEL_NAME" 2>/dev/null \
            | grep -cE '^[0-9a-f]{8}-[0-9a-f]{4}' || echo 0)
        if [[ $conn -gt 0 ]]; then
            c_green "  Cloudflare Tunnel: ${conn} connections → https://$CLOUDFLARED_HOSTNAME/"
        else
            c_yellow "  Cloudflare Tunnel: NOT running (0 connections) → $CLOUDFLARED_HOSTNAME"
        fi
    else
        c_gray "  Cloudflare Tunnel: cloudflared not installed"
    fi
}

invoke_logs() {
    local target="$1"
    assert_docker; assert_compose_file
    local args=( -f "$COMPOSE_FILE" logs --tail 50 -f )
    [[ -n "$target" ]] && args+=( "$target" )
    docker compose "${args[@]}"
}

invoke_db_shell() {
    assert_docker; assert_compose_file
    local name
    name=$(docker compose -f "$COMPOSE_FILE" ps --format '{{.Service}}|{{.Name}}' 2>/dev/null \
        | awk -F'|' '/^postgres\|/{print $2; exit}')
    if [[ -z "$name" ]]; then
        write_fail "postgres container is not running. Run './dev.sh up' first."
        exit 1
    fi
    exec docker exec -it "$name" psql -U familyapp_user -d familyapp
}

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
invoke_clean() {
    write_step "Removing local build artifacts ..."
    local dirs=( "$BACKEND_DIR/build" "$FRONTEND_DIR/dist" )
    for d in "${dirs[@]}"; do
        if [[ -d "$d" ]]; then
            c_gray "  rm $d"
            rm -rf "$d"
        fi
    done
    write_ok "Clean complete."
}

invoke_purge() {
    invoke_clean
    assert_docker

    if [[ $PURGE_ALL -eq 0 && $PURGE_CONTAINERS -eq 0 && $PURGE_IMAGES -eq 0 ]]; then
        write_warn "Purge invoked but no targets selected. Add one of: --purge-containers / --purge-images / --purge-all"
        return
    fi

    local do_containers=0 do_images=0
    [[ $PURGE_ALL -eq 1 || $PURGE_CONTAINERS -eq 1 ]] && do_containers=1
    [[ $PURGE_ALL -eq 1 || $PURGE_IMAGES -eq 1 ]]     && do_images=1

    if [[ $do_containers -eq 1 ]]; then
        if confirm_action "Prune ALL stopped containers" "$PURGE_ALL"; then
            write_step "docker container prune -f"
            docker container prune -f
        fi
    fi
    if [[ $do_images -eq 1 ]]; then
        if confirm_action "Prune DANGLING images (-a would remove ALL unused; not doing that by default)" "$PURGE_ALL"; then
            write_step "docker image prune -f"
            docker image prune -f
        fi
    fi
    write_ok "Purge complete."
}

# ---------------------------------------------------------------------------
# E2E 冒烟测试
# ---------------------------------------------------------------------------
invoke_e2e() {
    local subcmd="${1:-smoke}"
    local e2e_dir="$REPO_ROOT/scripts/e2e"
    case "$subcmd" in
        smoke|e2e)
            write_step "运行 E2E 冒烟测试（AI_DECOMPOSE_ENABLED=false 降级模式）..."
            assert_docker; assert_compose_file
            AI_DECOMPOSE_ENABLED=false bash "$e2e_dir/run_smoke.sh" --skip-build || {
                write_fail "E2E 冒烟测试失败。查看 /tmp/e2e_run*.log 排查。"
                return 1
            }
            write_ok "E2E 冒烟测试通过。"
            ;;
        flyway|e2e-flyway)
            write_step "运行 Flyway 幂等性验证..."
            bash "$e2e_dir/flyway_idempotent.sh" || {
                write_fail "Flyway 幂等性验证失败。"
                return 1
            }
            write_ok "Flyway 幂等性验证通过。"
            ;;
        config|e2e-config)
            write_step "验证 /api/agent/v1/config 端点..."
            assert_docker; assert_compose_file
            AI_DECOMPOSE_ENABLED=false bash "$e2e_dir/run_smoke.sh" --only-config || {
                write_fail "config 端点验证失败。"
                return 1
            }
            write_ok "config 端点验证通过。"
            ;;
        *)
            write_fail "未知子命令: $subcmd。可用: smoke | flyway | config"
            return 1
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Main dispatch
# ---------------------------------------------------------------------------
case "$COMMAND" in
    help|-h)           write_help ;;
    preflight)         preflight_checks ;;
    compile)           invoke_compile ;;
    package)           invoke_package ;;
    test)              invoke_test ;;
    build-images)      invoke_build_images ;;
    build-backend-image)  invoke_build_images backend ;;
    build-frontend-image) invoke_build_images frontend ;;
    up)                invoke_up "$QUICK" ;;
    down)              invoke_down "$PURGE_DATA" "$FORCE" ;;
    restart)           invoke_restart "${COMMAND_ARGS[0]:-}" ;;
    status)            invoke_status ;;
    logs)              invoke_logs "${COMMAND_ARGS[0]:-}" ;;
    backend-logs)      invoke_logs backend ;;
    frontend-logs)     invoke_logs frontend ;;
    db-shell)          invoke_db_shell ;;
    clean)             invoke_clean ;;
    purge)             invoke_purge ;;
    e2e)               invoke_e2e "${COMMAND_ARGS[0]:-smoke}" ;;
    e2e-flyway)        invoke_e2e flyway ;;
    e2e-config)        invoke_e2e config ;;
    *)
        write_help
        exit 1
        ;;
esac
