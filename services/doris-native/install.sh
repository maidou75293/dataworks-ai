#!/usr/bin/env bash
set -euo pipefail

ROLE="${1:?usage: install.sh fe|be}"
# REQ-OSS-SEC-CLEAN-1：内网网段不再入仓，装时显式给出（例：192.168.10.0/24）
NETS="${DORIS_PRIORITY_NETWORKS:?需设 DORIS_PRIORITY_NETWORKS=<doris 节点网段 CIDR>}"
TAR="${2:-/tmp/doris-bin.tar.gz}"

if [ ! -d /opt/doris/fe ] && [ ! -d /opt/doris/be ]; then
  mkdir -p /opt/doris
  tar -xzf "$TAR" -C /opt/doris --strip-components=1
fi

if [ "$ROLE" = "fe" ]; then
  mkdir -p /opt/doris/fe/doris-meta /opt/doris/fe/log
  if ! grep -q "DEV overrides" /opt/doris/fe/conf/fe.conf; then
    cat /tmp/fe.conf.append >> /opt/doris/fe/conf/fe.conf
    echo "priority_networks = $NETS" >> /opt/doris/fe/conf/fe.conf
  fi
  sudo cp /tmp/doris-fe.service /etc/systemd/system/doris-fe.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now doris-fe
  echo "FE installed and started"
elif [ "$ROLE" = "be" ]; then
  mkdir -p /opt/doris/be/storage /opt/doris/be/log
  if ! grep -q "DEV overrides" /opt/doris/be/conf/be.conf; then
    cat /tmp/be.conf.append >> /opt/doris/be/conf/be.conf
    echo "priority_networks = $NETS" >> /opt/doris/be/conf/be.conf
  fi
  sudo cp /tmp/doris-be.service /etc/systemd/system/doris-be.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now doris-be
  echo "BE installed and started"
else
  echo "unknown role: $ROLE" >&2
  exit 1
fi
