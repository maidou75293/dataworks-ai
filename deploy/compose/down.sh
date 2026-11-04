#!/usr/bin/env bash
# 停栈：bash down.sh [docker compose down 的参数，如 --volumes]
set -euo pipefail
cd "$(dirname "$0")"
exec docker compose down "$@"
