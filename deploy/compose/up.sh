#!/usr/bin/env bash
# 一键起单机演示栈（REQ-OSS-QUICKSTART-1）：
#   首次运行生成随机本机口令 → 构建前后端镜像 → docker compose up -d →
#   等 /api/health 返回 UP → 打印一次性 admin 口令。
# 再跑一次沿用 .env（口令不变）；重来 = rm .env && docker compose down -v。
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null 2>&1 || { echo "缺 Docker 引擎（Linux/macOS 装 Docker Desktop 或 docker engine）"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "缺 docker compose v2（docker compose --help 验证）"; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "缺 curl（用于等健康检查）"; exit 1; }

rnd() { # 无引号无空格的随机十六进制串
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex "${1:-8}"
  elif command -v python3 >/dev/null 2>&1; then python3 -c "import secrets;print(secrets.token_hex(${1:-8}))"
  else python -c "import secrets;print(secrets.token_hex(${1:-8}))"
  fi
}
rnd_b64_32() { # 32 字节 base64（后端 PLATFORM_SECRET_KEY 按 base64 解 AES-256）
  if command -v openssl >/dev/null 2>&1; then openssl rand -base64 32
  elif command -v python3 >/dev/null 2>&1; then python3 -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())"
  else python -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())"
  fi
}

if [ -f .env ]; then
  echo "沿用已有 .env（口令沿用上次生成的；要全新口令：rm .env && docker compose down -v 再跑本脚本）"
else
  echo "首次运行：为本机生成随机口令 → .env（已 gitignore，勿提交）"
  {
    echo "# 本机演示口令（up.sh 生成 $(date -Iseconds)）。只监听本机端口，勿用于对外服务。"
    echo "DB_NAME=dataworks_dev"
    echo "DB_USER=dataworks"
    echo "POSTGRES_PASSWORD=$(rnd 16)"
    echo "DORIS_PASSWORD=$(rnd 16)"
    # 对象存储（SeaweedFS S3）用 ./s3/s3.json 内的本机 demo 凭据，不随机化
    echo "S3_ACCESS_KEY=demoaccesskey"
    echo "S3_SECRET_KEY=demosecretkey123"
    echo "KEYCLOAK_ADMIN_USER=demoadmin"
    echo "KEYCLOAK_ADMIN_PASSWORD=$(rnd 12)"
    echo "KEYCLOAK_MCP_CLIENT_SECRET=$(rnd 16)"
    echo "PLATFORM_SECRET_KEY=$(rnd_b64_32)"
    echo "PLATFORM_INTERNAL_TOKEN=$(rnd 16)"
    echo "MCP_TOKEN=$(rnd 16)"
    echo "PLATFORM_ADMIN_PASSWORD=$(rnd 12)"
  } > .env
  chmod 600 .env 2>/dev/null || true
fi
# shellcheck disable=SC1091
set -a; . ./.env; set +a

echo
echo "== 预拉构建基座（daemon 侧拉取；已在本机则秒过。BuildKit 在部分 Windows/代理环境下会由宿主进程直连 auth.docker.io 被拦，预拉后本地命中即绕开）"
for img in maven:3.9-eclipse-temurin-17 eclipse-temurin:17-jre-jammy node:20-alpine nginx:1.27-alpine; do
  docker pull -q "$img" >/dev/null 2>&1 || echo "   （$img 预拉未成功：构建阶段会自行再试，若届时再遇宿主侧鉴权超时，手动 docker pull 后重跑本脚本）"
done

echo
echo "== 构建并启动六件套（postgres/keycloak/s3/doris/backend/web；首次要拉镜像+编译前后端，10~20 分钟）"
docker compose up -d --build

echo
echo "== 等健康：http://localhost:30090/api/health（最长 10 分钟；Flyway 首轮按序应用 129 个迁移）"
ok=0
for i in $(seq 1 120); do
  body=$(curl -s --max-time 5 http://localhost:30090/api/health || true)
  if echo "$body" | grep -q '"status": *"UP"'; then ok=1; break; fi
  sleep 5
done
if [ "$ok" != 1 ]; then
  echo "健康检查没通过。排障：docker compose ps && docker compose logs backend web postgres"
  exit 1
fi
echo "$body"

cat <<EOF

============================================================
 数据中台演示栈已就绪（一次性口令，只显示这一次；全部在 .env 里）
============================================================
 入口：      http://localhost:30090
 管理员登录： 用户名 ${PLATFORM_ADMIN_USER:-admin}   口令 ${PLATFORM_ADMIN_PASSWORD}
 Keycloak：  http://localhost:30081（admin=${KEYCLOAK_ADMIN_USER}，口令见 .env）
 对象存储：  SeaweedFS S3 http://localhost:30900（本机 demo 凭据在 ./s3/s3.json；控制台 http://localhost:30901）
 停止：      bash down.sh          （-v 连数据卷一起清：bash down.sh --volumes）
============================================================
 安全提醒：口令为本地演示生成，监听的是本机端口；对外暴露前先换全套并删 .env 重新生成。
EOF
