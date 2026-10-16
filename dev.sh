#!/usr/bin/env bash
# 根目录便捷入口：实际实现集中在 scripts/dev.sh。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$ROOT_DIR/scripts/dev.sh" "$@"
