#!/usr/bin/env bash
# 唯一入口：在 deploy 目录下执行。
#
#   bash deploy.sh install     空机器：装 k3s + 平台
#   bash deploy.sh existing    已有 K8s：只装平台
#   bash deploy.sh verify      只验收
#   bash deploy.sh images      导入镜像（Windows 上跑）
#   bash deploy.sh status      看 Pod 和健康检查
#   bash deploy.sh agent-service  首节点铺代码/venv 并 systemd 拉起 agent-service(8097)
#
# Windows 不能原生跑 Ansible，所以脚本会自动把 deploy/ 同步到控制机并在那执行。
# 控制机/节点地址/crane 路径=宿主锚点，不写进仓库（REQ-OSS-PUBSCRUB-1）：
#   cp env.local.sh.example env.local.sh 按自己环境填（env.local.sh 已 gitignore）；
#   或直接 export DW_OPS / DW_KEY / DW_NODES / DW_REGISTRY / DW_NODE / DW_CRANE（环境变量优先）。
#   镜像目录默认 ./images（DW_IMAGES 可指过去）；私钥默认 keys/ 随 deploy 目录整体拷走。
set -euo pipefail
cd "$(dirname "$0")"

# 本地覆盖文件先兜默认值，DW_* 环境变量最高优先
[ -f env.local.sh ] && source env.local.sh

OPS="${DW_OPS:-}"
KEY="${DW_KEY:-$PWD/keys/id_ed25519}"
REMOTE="${DW_REMOTE:-dataworks/deploy}"
IMAGES_DIR="${DW_IMAGES:-$PWD/images}"
NODES="${DW_NODES:-}"
REGISTRY="${DW_REGISTRY:-}"
CRANE="${DW_CRANE:-$(command -v crane || true)}"
NODE="${DW_NODE:-}"
ACTION="${1:-install}"

SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
          -o BatchMode=yes -o ConnectTimeout=15)
say() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
# 宿主锚点缺失即响亮失败（照 SEC-CLEAN④ env fail-fast 手法，不静默用错机器）
need() { [ -n "${!1}" ] || die "$1 未设置：cp env.local.sh.example env.local.sh 填值，或 export DW_${1^^}"; }

sync_to_ops() {
  need OPS
  command -v tar >/dev/null || die "缺少 tar"
  command -v scp >/dev/null || die "缺少 scp"
  command -v ssh >/dev/null || die "缺少 ssh"
  # 临时包放在 deploy 目录外，否则 tar 会边写边读自己的输出
  local tar="${TMPDIR:-/tmp}/deploy-sync-$$.tgz"
  rm -f "$tar"
  tar -czf "$tar" --exclude='./artifacts' --exclude='./images' .
  say "同步到 $OPS:~/$REMOTE"
  scp -q "${SSH_OPTS[@]}" "$tar" "$OPS:/tmp/deploy-sync.tgz"
  ssh "${SSH_OPTS[@]}" "$OPS" \
    "mkdir -p ~/$REMOTE && find ~/$REMOTE -mindepth 1 -maxdepth 1 ! -name artifacts -exec rm -rf {} + && tar -xzf /tmp/deploy-sync.tgz -C ~/$REMOTE && rm -f /tmp/deploy-sync.tgz && chmod 600 ~/$REMOTE/keys/id_ed25519 ~/$REMOTE/secrets/*.yml ~/$REMOTE/clusters/*.kubeconfig 2>/dev/null || true"
  rm -f "$tar"
}

registry_load() {
  need REGISTRY
  [ -x "$CRANE" ] || die "找不到 crane：${CRANE:-未设置}（PATH 装 crane 或用 DW_CRANE 指定路径）"
  [ -f "$IMAGES_DIR/images.tsv" ] || die "缺少 images/images.tsv"
  while IFS=$'	' read -r ref file src; do
    [ -n "$ref" ] || continue
    target="$REGISTRY/${ref#docker.io/}"
    echo "  [push] $target"
    "$CRANE" push --insecure "$IMAGES_DIR/$file" "$target" >/dev/null || die "推送失败 $target"
  done < "$IMAGES_DIR/images.tsv"
}

import_images() {
  need NODES
  [ -f "$IMAGES_DIR/images.tsv" ] || die "缺少 images/images.tsv"
  [ -d "$IMAGES_DIR" ] || die "缺少 images/ 目录"
  for ip in $NODES; do
    say "导入镜像 -> $ip"
    # 一次性取节点镜像列表，避免每个镜像都开一次 SSH
    existing=$(ssh "${SSH_OPTS[@]}" "ubuntu@$ip" "sudo k3s ctr -n k8s.io images ls -q" 2>/dev/null || true)
    while IFS=$'	' read -r ref file src; do
      [ -n "$ref" ] || continue
      [ -f "$IMAGES_DIR/$file" ] || die "缺少 images/$file"
      if printf '%s
' "$existing" | grep -qxF "$ref"; then
        echo "  [skip] $ref"
        continue
      fi
      echo "  [upload] $file"
      scp -q "${SSH_OPTS[@]}" "$IMAGES_DIR/$file" "ubuntu@$ip:/tmp/$file"
      ssh "${SSH_OPTS[@]}" "ubuntu@$ip"         "sudo k3s ctr -n k8s.io images import /tmp/$file >/dev/null 2>&1; rm -f /tmp/$file; [ -n '$src' ] && sudo k3s ctr -n k8s.io images tag '$src' '$ref' >/dev/null 2>&1 || true; sudo k3s ctr -n k8s.io images ls -q | grep -qxF '$ref'"         || die "导入失败 $ref @ $ip"
    done < "$IMAGES_DIR/images.tsv"
  done
}

sync_hdfs_artifacts() {
  ssh "${SSH_OPTS[@]}" "$OPS" "mkdir -p ~/$REMOTE/artifacts/hadoop"
  for f in hadoop-3.4.1.tar.gz jre17.tar.gz; do
    local local_sum remote_sum
    local_sum=$(sha256sum "artifacts/hadoop/$f" | cut -d' ' -f1)
    remote_sum=$(ssh "${SSH_OPTS[@]}" "$OPS"       "sha256sum ~/$REMOTE/artifacts/hadoop/$f 2>/dev/null | cut -d' ' -f1" || true)
    if [ "$local_sum" != "$remote_sum" ]; then
      say "上传 HDFS 产物 $f"
      scp -q "${SSH_OPTS[@]}" "artifacts/hadoop/$f" "$OPS:~/$REMOTE/artifacts/hadoop/$f"
    fi
  done
}

sync_artifacts() {
  ssh "${SSH_OPTS[@]}" "$OPS" "mkdir -p ~/$REMOTE/artifacts/seatunnel"
  for f in seatunnel-dist.tgz seatunnel-connectors.tgz; do
    local local_sum remote_sum
    local_sum=$(sha256sum "artifacts/seatunnel/$f" | cut -d' ' -f1)
    remote_sum=$(ssh "${SSH_OPTS[@]}" "$OPS"       "sha256sum ~/$REMOTE/artifacts/seatunnel/$f 2>/dev/null | cut -d' ' -f1" || true)
    if [ "$local_sum" != "$remote_sum" ]; then
      say "上传离线产物 $f"
      scp -q "${SSH_OPTS[@]}" "artifacts/seatunnel/$f" "$OPS:~/$REMOTE/artifacts/seatunnel/$f"
    fi
  done
}

run_remote() {
  need OPS
  ssh "${SSH_OPTS[@]}" "$OPS" "cd ~/$REMOTE && $*"
}

# agent-service(8097)：主机级 systemd 服务，代码不在部署包里（在仓库 agent-service/ 与旁边的 agent-core），
# 所以由开发机直接铺到首节点（images/status 同样直连 $NODE，不绕控制机）。幂等，可重复执行。
agent_service_provision() {
  need NODE
  local core="${DW_AGENT_CORE:-$PWD/../../agent-core}"
  [ -d "$PWD/../agent-service" ] || die "找不到仓库 agent-service/（本脚本需在仓库的 deploy/ 下跑）"
  [ -d "$PWD/../agent/tools" ]    || die "找不到仓库 agent/tools（工具 schema 目录，manual.py 回退路径）"
  [ -d "$core" ] || die "找不到 agent-core（enter-harness 包）：$core —— 用 DW_AGENT_CORE=/path 指定"
  say "打包源码（agent-service + agent/tools + agent-core）"
  local t1="${TMPDIR:-/tmp}/dw-agent-service-$$.tgz" t2="${TMPDIR:-/tmp}/dw-agent-core-$$.tgz"
  tar --exclude=__pycache__ --exclude=.venv --exclude=.tools -czf "$t1" -C "$PWD/.." agent-service agent
  tar --exclude=.git --exclude=.venv* --exclude=build --exclude=dist --exclude=*.egg-info \
      --exclude=data --exclude=__pycache__ --exclude='*.log' --exclude=node_modules \
      -czf "$t2" -C "$(dirname "$core")" "$(basename "$core")"
  say "上传并铺到 ubuntu@$NODE:/opt（venv 首建要装 langgraph 等，慢在 pip）"
  scp -q "${SSH_OPTS[@]}" "$t1" "$t2" "ubuntu@$NODE:/tmp/"
  rm -f "$t1" "$t2"
  ssh "${SSH_OPTS[@]}" "ubuntu@$NODE" "NODE_IP=$NODE bash -s" <<'EOS'
set -euo pipefail
AS=/opt/dataworks-agent/agent-service
sudo mkdir -p /opt/dataworks-agent
# 1) 代码原地覆盖：保留既有 .venv 与 .env（运维手填的模型 Key 不能被冲掉）
tar -xzf "$(ls -t /tmp/dw-agent-service-*.tgz | head -1)" -C /opt/dataworks-agent
CT=/tmp/dw-ac-$$; rm -rf "$CT"; mkdir -p "$CT"
tar -xzf "$(ls -t /tmp/dw-agent-core-*.tgz | head -1)" -C "$CT"
sudo rm -rf /opt/agent-core.new && sudo cp -a "$(find "$CT" -mindepth 1 -maxdepth 1 -type d | head -1)" /opt/agent-core.new
sudo rm -rf /opt/agent-core && sudo mv /opt/agent-core.new /opt/agent-core && rm -rf "$CT"
sudo chown -R ubuntu:ubuntu /opt/dataworks-agent

# 2) venv + 依赖（tuna 源；agent-service 的 requirements 已含 enter-harness 所需依赖，故 --no-deps）
[ -x "$AS/.venv/bin/pip" ] || { python3 -c 'import ensurepip' 2>/dev/null || sudo apt-get install -y python3-venv >/dev/null; python3 -m venv "$AS/.venv"; }
PIP="$AS/.venv/bin/pip"
$PIP install -q -U pip -i https://pypi.tuna.tsinghua.edu.cn/simple
$PIP install -q -i https://pypi.tuna.tsinghua.edu.cn/simple -r "$AS/requirements.txt"
$PIP install -q -i https://pypi.tuna.tsinghua.edu.cn/simple --no-deps /opt/agent-core

# 3) .env + unit + enable：需要 k8s secret platform-keys（install 首装时阶段 4 才建，缺就顺延给 site.yml 的 agent-service play）
if sudo k3s kubectl -n dw-dev get secret platform-keys >/dev/null 2>&1; then
  # 模型配置真源=platform-keys/AGENT_MODEL_API_KEY（百炼 token-plan OpenAI 兼容口，BUG-AGENT-KEY-FIX-1）
  AMK=$(sudo k3s kubectl -n dw-dev get secret platform-keys -o jsonpath='{.data.AGENT_MODEL_API_KEY}' 2>/dev/null | base64 -d || true)
  if [ ! -f "$AS/.env" ]; then
    PIT=$(sudo k3s kubectl -n dw-dev get secret platform-keys -o jsonpath='{.data.PLATFORM_INTERNAL_TOKEN}' | base64 -d)
    AST=$(sudo k3s kubectl -n dw-dev get secret platform-keys -o jsonpath='{.data.AGENT_SERVICE_TOKEN}' | base64 -d)
    { umask 077; cat > "$AS/.env" <<EOF
AGENT_MODEL_BASE_URL=https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
AGENT_MODEL_NAME=qwen3.8-flash
# 模型 Key 真源=platform-keys/AGENT_MODEL_API_KEY：本行为空时服务正常但 chat 必失败（final=failed）；
# 由本脚本/60-agent-service.yml 从 Secret 收敛补齐，只填空行、从不覆盖手填值
AGENT_MODEL_API_KEY=$AMK
PLATFORM_INTERNAL_TOKEN=$PIT
AGENT_SERVICE_TOKEN=$AST
BACKEND_URL=http://$NODE_IP:30090
EOF
    }
    echo "ENV_CREATED"
  else
    # 收敛注入：只补 .env 里为空的模型三行；非空值（运维手填）绝不覆盖；Secret 无 key 时只补端点/模型名
    AMK="$AMK" python3 - "$AS/.env" <<'PYEOF'
import os, sys
p, key = sys.argv[1], os.environ.get("AMK", "").strip()
lines = open(p, encoding="utf-8").read().splitlines()
want = {"AGENT_MODEL_BASE_URL": "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
        "AGENT_MODEL_NAME": "qwen3.8-flash"}
if key:
    want["AGENT_MODEL_API_KEY"] = key
changed = False
out = []
for ln in lines:
    k, _, v = ln.partition("=")
    if k.strip() in want and not v.strip():
        out.append("%s=%s" % (k.strip(), want[k.strip()])); changed = True
    else:
        out.append(ln)
if changed:
    open(p, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("ENV_SYNCED" if key else "ENV_SYNCED_PARTIAL(Secret 无 AGENT_MODEL_API_KEY，chat 将红)")
else:
    print("ENV_KEPT" if key or "AGENT_MODEL_API_KEY" not in "".join(lines) else "ENV_KEPT(WARN: Key 行为空)")
PYEOF
  fi
  [ -f "$AS/agent-service.service" ] || { echo "FATAL: 缺 agent-service.service（应随 agent-service/ 铺过来）"; exit 1; }
  sudo cp "$AS/agent-service.service" /etc/systemd/system/agent-service.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now agent-service
  sleep 2
  echo "is-enabled=$(systemctl is-enabled agent-service) is-active=$(systemctl is-active agent-service)"
  curl -s -m 5 http://127.0.0.1:8097/api/health | head -c 200; echo
else
  echo "DEFERRED: platform-keys 尚不存在，.env/unit 顺延给 site.yml agent-service play（tasks/60-agent-service.yml）"
fi
EOS
  ssh "${SSH_OPTS[@]}" "ubuntu@$NODE" "rm -f /tmp/dw-agent-service-*.tgz /tmp/dw-agent-core-*.tgz"
  say "agent-service 完成；验收：bash deploy.sh status 或 py -3.12 scripts/e2e/dl1/f1_health_check.py（仓库侧）"
}

case "$ACTION" in
  install)
    [ -f secrets/lab.yml ] || die "缺少 secrets/lab.yml（cp secrets/lab.example.yml secrets/lab.yml 并填满 18 项）"
    sync_to_ops
    say "阶段 1/4：preflight → node → k3s"
    run_remote "ansible-playbook site.yml --tags preflight,node,k3s"
    say "阶段 2/4：私有仓库 30500"
    run_remote "ansible-playbook registry.yml"
    if [ "${DW_SKIP_IMAGES:-0}" != "1" ] && [ -d "$IMAGES_DIR" ]; then
      say "阶段 3/4：镜像灌进 30500（只这一台机器）"
      registry_load
    else
      say "阶段 3/4：跳过镜像灌库（DW_SKIP_IMAGES=1 或没有 images/）"
    fi
    sync_artifacts
    if [ "${DW_SKIP_AGENT:-0}" != "1" ]; then
      say "agent-service：铺代码到首节点（site.yml 的 agent-service play 依赖它；DW_SKIP_AGENT=1 可跳）"
      agent_service_provision
    fi
    say "阶段 4/4：artifacts → platform → keycloak → gravitino → agent-service → verify"
    run_remote "ansible-playbook site.yml --tags artifacts,platform,keycloak,gravitino,agent-service,verify"
    ;;
  existing)
    [ -f inventory/existing.yml ] || die "缺少 inventory/existing.yml（cp inventory/existing.example.yml inventory/existing.yml 并改 kubeconfig/StorageClass）"
    sync_to_ops
    say "接管已有 K8s：check → platform → verify"
    run_remote "ansible-playbook platform.yml -i inventory/existing.yml"
    ;;
  testdb)
    sync_to_ops
    say "部署测试库（PostgreSQL）"
    run_remote "ansible-playbook testdb.yml"
    ;;
  verify)
    sync_to_ops
    say "验收"
    run_remote "ansible-playbook site.yml --tags verify"
    ;;
  hdfs)
    sync_to_ops
    sync_hdfs_artifacts
    say "HDFS 三节点原生部署（NameNode 首节点 + DataNode 全部节点）"
    run_remote "ansible-playbook hdfs.yml"
    ;;
  registry-up)
    sync_to_ops
    say "只部署私有仓库 30500"
    run_remote "ansible-playbook registry.yml"
    ;;
  registry-load)
    say "把 images/ 灌进私有仓库 $REGISTRY（只需一台机器执行）"
    registry_load
    ;;
  images)
    say "逐节点导入（离线兜底；正常应从仓库拉）"
    import_images
    ;;
  agent-service)
    agent_service_provision
    ;;
  status)
    need NODE
    say "Pod"
    if command -v kubectl >/dev/null 2>&1; then
      kubectl -n dw-dev get pods 2>/dev/null || true
    else
      ssh "${SSH_OPTS[@]}" "ubuntu@$NODE" "sudo k3s kubectl -n dw-dev get pods" 2>/dev/null || true
    fi
    say "健康检查"
    curl -s --max-time 10 "http://$NODE:30090/api/health" || true
    echo
    ;;
  *)
    echo "用法: bash deploy.sh install|existing|verify|testdb|registry-up|registry-load|hdfs|images|status|agent-service"
    exit 2
    ;;
esac
