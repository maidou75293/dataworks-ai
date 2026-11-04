#!/usr/bin/env bash
# 在节点上启动 agent-service（独立进程，不经过 k8s）
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
export AGENT_MODEL_BASE_URL="${AGENT_MODEL_BASE_URL:-https://api.deepseek.com/v1}"
export AGENT_MODEL_NAME="${AGENT_MODEL_NAME:-deepseek-chat}"
export BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:30090}"
export AGENT_TOOLS_DIR="${AGENT_TOOLS_DIR:-.tools}"
exec .venv/bin/uvicorn app:app --host 0.0.0.0 --port "${PORT:-8097}"
