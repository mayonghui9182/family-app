#!/usr/bin/env bash
# ============================================================================
# 用户密码重置脚本（可重复使用）
#
# 用法：
#   scripts/reset-user-password.sh <邮箱或手机号> [新密码]
#
# 示例：
#   scripts/reset-user-password.sh 17610969182@163.com           # 自动生成随机密码
#   scripts/reset-user-password.sh 17610969182@163.com Abc12345  # 指定新密码
#   scripts/reset-user-password.sh 17610969182                    # 按手机号重置
#
# 行为说明：
#   1. 参数含 @ 按 email 匹配，否则按 phone 匹配；仅匹配未软删除（deleted_at is null）的账号
#   2. 密码以 BCrypt（强度 10）哈希写入 password_hash，与后端 BCryptPasswordEncoder 一致，
#      哈希前缀归一化为 $2a$ 以匹配库内既有格式；永不明文存储
#   3. 重置后清空 access_token / token_updated_at —— 所有已登录设备强制下线，须用新密码重登
#   4. 省略新密码时自动生成 12 位随机密码并打印（请妥善保管并转告用户）
#   5. 写入后回读校验：用 bcrypt.checkpw 验证库内哈希确实匹配新密码，不通过则报错退出
#
# 依赖：docker（主库容器在跑）、python3 + bcrypt 模块
# 连接参数可用环境变量覆盖：PG_CONTAINER / PG_USER / PG_DB
# ============================================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-familyapp-postgres}"
PG_USER="${PG_USER:-familyapp_user}"
PG_DB="${PG_DB:-familyapp}"

die() { echo "错误：$*" >&2; exit 1; }

# ---- 0. 参数校验 ----
identifier="${1:-}"
[[ -n "$identifier" ]] || die "用法：$0 <邮箱或手机号> [新密码]"
new_password="${2:-}"
generated=0

# ---- 1. preflight：依赖与容器可用性（避免后续命令替换静默失败）----
command -v python3 >/dev/null 2>&1 || die "缺少 python3。"
python3 -c 'import bcrypt' 2>/dev/null || die "缺少 python bcrypt 模块，请先执行：pip3 install bcrypt"

command -v docker >/dev/null 2>&1 || die "找不到 docker 命令。若在 WSL 内，请确认 Docker Desktop 已开启 WSL integration。"
docker info >/dev/null 2>&1 || die "docker 守护进程不可达。请确认 Docker Desktop 已启动完成后重试。"

container_state="$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null || echo "missing")"
[[ "$container_state" == "true" ]] || die "数据库容器 ${PG_CONTAINER} 未在运行（当前状态：${container_state}）。请先 docker compose up -d postgres"

# psql 封装：SQL 经 stdin 传入。
# 关键：psql 只对 stdin/文件输入做 :'var' 变量插值，-c 命令串不插值——实测 psql 16.15
# 用 -c 时 :'var' 会被原样发给服务器导致 "syntax error at or near :"，故必须走 stdin。
# ON_ERROR_STOP=1 让 SQL 错误以非零码退出，配合 if ! 捕获，不让 set -e 吞掉错误信息。
# 用法：run_sql "<SQL，可含 :'var'>" [-v var=val ...]
run_sql() {
  local sql="$1"; shift
  local out
  if ! out="$(printf '%s\n' "$sql" | docker exec -i "$PG_CONTAINER" \
      psql -U "$PG_USER" -d "$PG_DB" -tA -v ON_ERROR_STOP=1 "$@" 2>&1)"; then
    die "数据库查询失败：${out}"
  fi
  printf '%s' "$out"
}

# ---- 2. 判定查询字段：含 @ 为邮箱，否则为手机号 ----
if [[ "$identifier" == *@* ]]; then
  field="email"
else
  field="phone"
fi

# ---- 3. 查找用户（排除软删除；identifier 经 psql -v 变量插值防注入）----
rows="$(run_sql "select id || '|' || nickname from users where ${field} = :'identifier' and deleted_at is null;" \
  -v identifier="$identifier")"

[[ -n "$rows" ]] || die "未找到 ${field} 为 ${identifier} 的有效用户（已软删除的账号不可重置）。"
[[ "$(printf '%s\n' "$rows" | grep -c .)" -eq 1 ]] \
  || die "匹配到多个用户，请人工确认：$(printf '%s' "$rows" | tr '\n' ' ')"

user_id="${rows%%|*}"
nickname="${rows#*|}"

# ---- 4. 确定新密码 ----
# 用 python3 secrets 而非 `tr </dev/urandom | head`：后者 head 提前关管道会让 tr 收到
# SIGPIPE，在 set -o pipefail 下整条命令以 141 失败。
if [[ -z "$new_password" ]]; then
  new_password="$(python3 -c 'import secrets,string;print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(12)))')" \
    || die "随机密码生成失败。"
  generated=1
fi

# ---- 5. 生成 BCrypt 哈希（$2b 前缀归一化为 $2a，与库内既有格式一致）----
hash="$(python3 - "$new_password" <<'PYEOF'
import sys, bcrypt
h = bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt(rounds=10)).decode()
print(h.replace("$2b$", "$2a$", 1))
PYEOF
)" || die "BCrypt 哈希生成失败。"
[[ "$hash" == \$2a\$10\$* ]] || die "生成的哈希格式异常：${hash}"

# ---- 6. 更新密码哈希并作废登录 token（强制重新登录）----
run_sql "update users set password_hash = :'hash', access_token = null, token_updated_at = null, updated_at = now() where id = :'uid'::bigint;" \
  -v hash="$hash" -v uid="$user_id" >/dev/null

# ---- 7. 回读校验：库内哈希必须能验证新密码 ----
stored="$(run_sql "select password_hash from users where id = :'uid'::bigint;" -v uid="$user_id")"
if ! python3 - "$new_password" "$stored" <<'PYEOF'
import sys, bcrypt
sys.exit(0 if bcrypt.checkpw(sys.argv[1].encode(), sys.argv[2].encode()) else 1)
PYEOF
then
  die "回读校验失败，密码未生效，请人工检查 users(id=${user_id})。"
fi

echo "✅ 密码重置成功"
echo "   用户：${nickname}（id=${user_id}，${field}=${identifier}）"
if [[ "$generated" -eq 1 ]]; then
  echo "   新密码（自动生成，请转告用户）：${new_password}"
else
  echo "   新密码：已按指定密码设置"
fi
echo "   注意：该用户所有已登录设备已下线，需用新密码重新登录。"
