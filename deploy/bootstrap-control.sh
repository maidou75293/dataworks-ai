#!/usr/bin/env bash
# 控制机一次性准备（在控制机上执行，不要在集群节点上执行）。
#   bash deploy/bootstrap-control.sh
#
# 只做三件事：装 ansible/sshpass/kubernetes 依赖、装 collection、打印版本。
# 幂等：已经有的不重复装。
set -euo pipefail
cd "$(dirname "$0")"

need=()
command -v ansible-playbook >/dev/null 2>&1 || need+=(ansible)
command -v sshpass >/dev/null 2>&1 || need+=(sshpass)
command -v rsync >/dev/null 2>&1 || need+=(rsync)
python3 -c 'import kubernetes' >/dev/null 2>&1 || need+=(python3-kubernetes)

if [ "${#need[@]}" -gt 0 ]; then
  echo "== 安装：${need[*]}"
  sudo apt-get update -qq
  sudo apt-get install -y -qq "${need[@]}"
fi

echo "== 安装 Ansible collection"
if ansible-galaxy collection list 2>/dev/null | grep -q 'kubernetes.core'; then
  echo "kubernetes.core 已安装，跳过"
else
  ansible-galaxy collection install -r requirements.yml
fi

echo "== 版本"
ansible --version | head -2
python3 -c 'import kubernetes; print("kubernetes(py) " + kubernetes.__version__)'
