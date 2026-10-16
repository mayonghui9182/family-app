#!/usr/bin/env bash
# ============================================================
# E2E 公共断言函数库（Change 7 - D5）
# 全部错误消息使用中文；兼容 WSL / Git Bash / macOS zsh
# 使用方式：source "$(dirname "$0")/assertions.sh"
# ============================================================
set -euo pipefail

# ---- Windows 环境检测 ----
detect_windows_native() {
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then return 0; fi
    case "$(uname -s 2>/dev/null || echo Windows)" in
        *Windows*|*MINGW*|*MSYS*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---- 颜色常量 ----
if [[ -t 1 ]]; then
    C_RED=$'\033[0;31m'
    C_GREEN=$'\033[0;32m'
    C_YELLOW=$'\033[1;33m'
    C_GRAY=$'\033[0;90m'
    C_RESET=$'\033[0m'
else
    C_RED=""; C_GREEN=""; C_YELLOW=""; C_GRAY=""; C_RESET=""
fi

# ---- 路径与根目录定位 ----
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.yml"
ENV_FILE="$PROJECT_ROOT/.env"

log_info()  { echo "${C_GRAY}[INFO]${C_RESET}  $*" ; }
log_ok()    { echo "${C_GREEN}[ OK ]${C_RESET}  $*" ; }
log_warn()  { echo "${C_YELLOW}[WARN]${C_RESET}  $*" >&2 ; }
log_error() { echo "${C_RED}[FAIL]${C_RESET}  $*" >&2 ; }

# ---- abort：致命错误 ----
abort() {
    local msg="$1" hint="${2:-}"
    log_error "$msg"
    if [[ -n "$hint" ]]; then echo "        ������  提示：$hint" >&2; fi
    exit 1
}

# ---- assert_eq：相等断言 ----
assert_eq() {
    local label="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        log_ok "$label = $actual（期望 $expected）"
    else
        abort "$label 不匹配：实际=[$actual]，期望=[$expected]" "请检查上游命令是否成功执行"
    fi
}

# ---- assert_contains：包含断言 ----
assert_contains() {
    local label="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        log_ok "$label 包含关键字：$needle"
    else
        abort "$label 缺失关键字：[$needle]" "实际内容前500：${haystack:0:500}"
    fi
}

# ---- assert_exit_code：断言命令退出码 ----
assert_exit_code() {
    local label="$1" expected="$2" actual="$3"
    if [[ "$actual" -eq "$expected" ]]; then
        log_ok "$label 退出码 = $actual"
    else
        abort "$label 退出码不匹配：实际=$actual，期望=$expected" "请查看上方 stderr"
    fi
}

# ---- wait_healthy：等待 compose 服务 healthy ----
wait_healthy() {
    local timeout="$1"; shift
    local services=("$@")
    local deadline=$(( SECONDS + timeout ))
    log_info "等待服务健康（超时${timeout}s）：${services[*]}"
    while (( SECONDS < deadline )); do
        local all_healthy=true svc_lines
        # Go Template output (Compose v2/v5 both stable): one service per line, Service|Status, no column drift.
        # STATUS line contains "(healthy)" when passing healthcheck; literal substring match below.
        svc_lines="$(cd "$PROJECT_ROOT" && docker compose -f "$COMPOSE_FILE" ps --format '{{.Service}}|{{.Status}}' 2>/dev/null || true)"
        for svc in "${services[@]}"; do
            local svc_status
            svc_status="$(echo "$svc_lines" | awk -F'|' -v s="$svc" '$1==s { print $2 }' 2>/dev/null || true)"
            if [[ "$svc_status" == *"(healthy)"* ]]; then :; else all_healthy=false; break; fi
        done
        if $all_healthy; then log_ok "All services healthy: ${services[*]}"; return 0; fi
        sleep 3
    done
    abort "服务健康检查超时（${timeout}s）" "运行：docker compose ps 查看状态"
}

# ---- wait_http：等待 HTTP 端点 ----
wait_http() {
    local timeout="$1" url="$2" expected_status="${3:-200}"
    local deadline=$(( SECONDS + timeout ))
    log_info "等待 HTTP 就绪（超时${timeout}s）：$url -> $expected_status"
    while (( SECONDS < deadline )); do
        local code
        code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
        if [[ "$code" == "$expected_status" ]]; then log_ok "HTTP 就绪：$url -> $code"; return 0; fi
        sleep 2
    done
    abort "HTTP 端点超时：$url（期望 $expected_status）" "查看：docker compose logs <service>"
}

# ---- check_docker：前置检查 docker ----
check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        abort "❌ 未检测到 docker，请先安装并启动 Docker Desktop" "Windows 用户：WSL 设置中启用 WSL Integration"
    fi
    if ! docker compose version >/dev/null 2>&1; then
        abort "❌ docker compose 插件不可用" "请安装 Docker Desktop >= 3.4 或 compose v2 插件"
    fi
    log_ok "Docker + compose 可用：$(docker compose version --short 2>/dev/null || true)"
}

# ---- check_backend_jar：检查 jar 是否存在 ----
check_backend_jar() {
    local jar
    jar="$(ls "$PROJECT_ROOT/backend/build/libs/"*.jar 2>/dev/null | head -1 || true)"
    if [[ -z "$jar" ]]; then
        log_warn "backend/build/libs 下无 jar，执行：./gradlew bootJar -x test"
        ( cd "$PROJECT_ROOT/backend" && ./gradlew bootJar -x test --no-daemon ) || abort "backend bootJar 失败" "请检查 Java/JDK 21"
    else
        log_ok "backend jar 已存在：$(basename "$jar")"
    fi
}

# ---- cleanup_compose：compose down（E2E_KEEP=1 跳过）----
cleanup_compose() {
    if [[ "${E2E_KEEP:-0}" == "1" ]]; then
        log_warn "E2E_KEEP=1，跳过 docker compose down，容器保留供调试"
        return 0
    fi
    log_info "清理 compose 环境"
    ( cd "$PROJECT_ROOT" && docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true )
    log_ok "compose 环境已清理"
}

# ---- dump_env_for_log：打印关键路径 ----
dump_env_for_log() {
    log_info "项目根目录：$PROJECT_ROOT"
    log_info "compose 文件：$COMPOSE_FILE"
    log_info ".env 文件：$( [[ -f "$ENV_FILE" ]] && echo "已存在" || echo "不存在，用默认值" )"
}

