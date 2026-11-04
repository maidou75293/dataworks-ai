#!/bin/sh
# 一次性：等 Doris FE 可连后建平台业务账号（与 deploy/tasks/50-platform.yml
# 「建 Doris 业务账号」同一条 SQL）。跑完即退。
# 注：不要加 FLUSH PRIVILEGES——Doris 没有这条 MySQL 语句，会 syntax error 退出 1（2026-11-04 实测）。
# 注意：口令只允许十六进制/字母数字（up.sh 生成即如此），否则单引号 SQL 会被打断。
set -eu
echo "[doris-init] 等待 Doris FE ${DORIS_HOST}:9030 ..."
i=0
until mysql -h "$DORIS_HOST" -P 9030 -u root --connect-timeout=3 -e "SELECT 1" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 120 ]; then
        echo "[doris-init] 超时：Doris FE 600 秒内没有可达（看 docker compose logs doris）"
        exit 1
    fi
    sleep 5
done
echo "[doris-init] FE 已就绪，创建业务账号 ${DORIS_BIZ_USER}"
mysql -h "$DORIS_HOST" -P 9030 -u root -e \
    "CREATE USER IF NOT EXISTS '${DORIS_BIZ_USER}'@'%' IDENTIFIED BY '${DORIS_BIZ_PASSWORD}'; GRANT ALL ON *.* TO '${DORIS_BIZ_USER}'@'%'"
echo "[doris-init] 完成"
