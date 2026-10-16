#!/usr/bin/env bash
# ============================================================
# Change 7 Task 3 子步骤 3：Flyway 幂等性测试（PR-5）
# 流程：只启 postgres -> backend 两次 flywayClean+flywayMigrate
#       两次都 exit 0 则幂等通过
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/assertions.sh"

# ---- trap：退出前清理 compose ----
trap cleanup_compose EXIT

# ---- Step 0：环境检查 ----
dump_env_for_log
check_docker
[[ -f "$COMPOSE_FILE" ]] || abort "未找到 compose 文件：$COMPOSE_FILE" "请在 family-app 仓库根目录运行"
[[ -d "$PROJECT_ROOT/backend" ]] || abort "未找到 backend 目录" "PROJECT_ROOT=$PROJECT_ROOT"

log_info "=== PR-5 Flyway 幂等性测试开始 ==="

# ---- Step 1：清理（防止遗留容器）----
cleanup_compose

# ---- Step 2：只启动 postgres ----
log_info "Step 2/5：启动 postgres 服务（单独）"
( cd "$PROJECT_ROOT" && docker compose -f "$COMPOSE_FILE" up -d postgres )
sleep 5
wait_healthy 60 postgres

# ---- Step 3：第一次 flyway ----
log_info "Step 3/5：第一次 flywayClean + flywayMigrate"
set +e
( cd "$PROJECT_ROOT/backend" && ./gradlew -Dflyway.cleanDisabled=false flywayClean flywayMigrate --no-daemon -q )
FW1_EXIT=$?
set -e
assert_exit_code "第一次 flyway (Clean+Migrate)" 0 $FW1_EXIT
log_ok "第 1 轮 flyway 完成"

# ---- Step 4：第二次 flyway（幂等）----
log_info "Step 4/5：第二次 flywayClean + flywayMigrate（幂等验证）"
set +e
( cd "$PROJECT_ROOT/backend" && ./gradlew -Dflyway.cleanDisabled=false flywayClean flywayMigrate --no-daemon -q )
FW2_EXIT=$?
set -e
assert_exit_code "第二次 flyway (Clean+Migrate)" 0 $FW2_EXIT
log_ok "第 2 轮 flyway 完成（幂等通过）"

# ---- Step 5：最终断言 ----
log_info "Step 5/5：总结断言"
assert_eq "flyway 第一轮退出码" $FW1_EXIT 0
assert_eq "flyway 第二轮退出码" $FW2_EXIT 0

echo ""
echo "✅ ✅ ✅  PR-5 PASS：Flyway 幂等性验证通过（两轮 Clean+Migrate 均 exit 0）"
echo ""
exit 0