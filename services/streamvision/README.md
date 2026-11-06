# StreamVision → K3s 部署说明

> **应用源码**：`dataworks-ai\`（2026-10-24 从 WSL
> `$HOME/stream-agent/` 复制，211 个文件 / 2.5M）。
> 本目录只放 K8s 构建与迁移件（Dockerfile / nginx.conf / migrate-data.sh / verify-db.py）。

> 2026-10-19。后端 + 前端在 K8s；vLLM / embed 留在 WSL，通过 `192.0.2.1` 桥接。
> 风险评估与 P0 记录：`docs/迁移到K8s-风险评估.md`。
> 2026-10-24：原 `deploy/streamvision/` 下的 K8s 清单已随部署重置删除，
> 需要时从集群导出：`kubectl -n streamvision get ns,pvc,deploy,helper pod -o yaml`。

## 1. 构建镜像（WSL）

```bash
# backend
docker build \
  -f dataworks-ai/services/streamvision/Dockerfile.backend \
  -t 192.0.2.11:30500/streamvision/backend:1.0.0 \
  ~/stream-agent/backend

# frontend（nginx.conf 必须在构建上下文里）
cp dataworks-ai/services/streamvision/nginx.conf \
   ~/stream-agent/frontend/nginx.conf
docker build \
  -f dataworks-ai/services/streamvision/Dockerfile.frontend \
  -t 192.0.2.11:30500/streamvision/frontend:1.0.0 \
  ~/stream-agent/frontend
```

## 2. 推送到私有仓库（Windows 侧 crane）

WSL 到 `192.0.2.0/24` 不通，必须经 Windows：

```bash
docker save -o /mnt/d/lab/.work/streamvision/backend.tar \
  192.0.2.11:30500/streamvision/backend:1.0.0
docker save -o /mnt/d/lab/.work/streamvision/frontend.tar \
  192.0.2.11:30500/streamvision/frontend:1.0.0

/d/lab/tools/crane/crane.exe push --insecure \
  D:/lab/.work/streamvision/backend.tar \
  192.0.2.11:30500/streamvision/backend:1.0.0
/d/lab/tools/crane/crane.exe push --insecure \
  D:/lab/.work/streamvision/frontend.tar \
  192.0.2.11:30500/streamvision/frontend:1.0.0
```

## 3. 创建命名空间、PVC、Secret

```bash
# 原清单已删除：Namespace / PVC / helper Pod / Deployment 均从集群现有对象导出后 apply
# kubectl -n streamvision get ns default -o yaml  # 示例，按实际对象导出

# Secret 从 WSL 的 backend/.env 直接管道注入，不落盘
wsl -e sh -c 'grep -E "^(JWT_SECRET|ADMIN_PASSWORD)=" $HOME/stream-agent/backend/.env' \
 | ssh -i /d/lab/keys/id_ed25519 -o StrictHostKeyChecking=no ubuntu@192.0.2.11 \
   'sudo k3s kubectl -n streamvision create secret generic streamvision-secrets --from-env-file=/dev/stdin --dry-run=client -o yaml | sudo k3s kubectl apply -f -'
```

## 4. 迁移数据

等 helper pod Running、PVC Bound 后：

```bash
# 确认 PV 路径与 migrate-data.sh 中的 PV_PATH 一致
/d/lab/bin/kubectl -n streamvision get pvc streamvision-data \
  -o jsonpath='{.spec.volumeName}{"\n"}'
/d/lab/bin/kubectl get pv <volume> -o jsonpath='{.spec.local.path}{"\n"}'

bash services/streamvision/migrate-data.sh
```

脚本会：停 WSL backend → `sqlite .backup` → tar → 传到 k8s-node-3 →
解压进 PVC → chown 1000 → `integrity_check` → 改 K8s 库的
`vllm.base_url` / `embed.base_url`。

## 5. 部署

```bash
# 原清单已删除：backend / frontend 从集群现有 Deployment 导出后 apply
/d/lab/bin/kubectl -n streamvision rollout status deploy/streamvision-backend --timeout=180s
/d/lab/bin/kubectl -n streamvision rollout status deploy/streamvision-frontend --timeout=180s
```

访问：`http://192.0.2.11:30100`（NodePort，所有节点都转发）。

## 6. 验收

```bash
curl -s http://192.0.2.11:30100/api/health
# {"status":"ok","service":"StreamVision","version":"0.1.0"}

# 抖音导入应返回 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://192.0.2.11:30100/api/videos/import-douyin
```

再在页面里过一遍：登录、视频包列表、知识库、详情、上传。

## 7. 切流与回滚

切流：K8s 验收通过后停 WSL 前端（`pkill -f "vite --host"`）；WSL backend 已在
数据迁移时停止，保持停止。

回滚（K8s 版本有问题时，已实测可拉起）：

```bash
# 后端
wsl -e sh -c 'tmux new-session -d -s sv-backend "cd $HOME/stream-agent/backend && \
  . venv/bin/activate && python -m uvicorn main:app --host 0.0.0.0 --port 8080 \
  --reload --timeout-graceful-shutdown 20 >> ../logs/backend.log 2>&1"'

# 前端（必须用完整 node 路径，tmux 非登录 shell 没有 nvm PATH）
wsl -e sh -c 'tmux new-session -d -s sv-frontend "cd $HOME/stream-agent/frontend && \
  $HOME/local/bin/node ./node_modules/.bin/vite --host 0.0.0.0 --port 5173 \
  >> ../logs/frontend.log 2>&1"'
```

注意：切流后 K8s 侧的新写入不在 WSL 库里；回滚需要把 K8s 的
`/data/streamvision.db` 反向复制回去，否则会丢失切换后的数据。

## 8. 已知限制

- 抖音导入在 K8s 版由 nginx 直接 403；Windows 采集器保留在 WSL，作为后续项。
- `vllm.model_path` 在库中仍是 WSL 路径，只影响模型展示；实际调用走
  `vllm.base_url=http://192.0.2.1:8000`。
- WSL IP 变化后由计划任务 `wsl-bridge-refresh` 自动刷新（登录时 + 每 5 分钟，
  默认刷新 8000/8002）。手动刷新：
  `powershell -NoProfile -ExecutionPolicy Bypass -File D:\lab\bin\wsl-bridge.ps1`
- local-path PVC 绑定 k8s-node-3，节点故障时不能漂移；开发环境可接受。
