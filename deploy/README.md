# 自动化部署（Ansible）

> 先分清两种形态：**本机看界面** → 用 [`compose/`](compose/README.md)（六件套一键，只要 Docker，
> `bash compose/up.sh` 打印一次性口令）；**集群/生产** → 本文（Ansible 离线包，3 节点 k3s）。
> 集群清单镜像前缀统一 `${DW_REGISTRY}` 占位，由 `dw_registry` 变量在 apply 时替换，
> 默认=集群内私有仓库 30500（详见 [`k8s/README.md`](k8s/README.md) 规则 3）。

## 离线包（整个 deploy 目录可以直接拷走）

```
deploy/
├── deploy.sh
├── keys/id_ed25519           # SSH 私钥，600，gitignore
├── clusters/lab.kubeconfig   # 集群访问凭据，600，gitignore
├── secrets/lab.yml           # 23 项平台密钥，600，gitignore
├── artifacts/seatunnel/      # SeaTunnel 发行包 + 连接器（约 489M）
├── inventory/  tasks/  k8s/  # 代码与清单
└── ...
```

`artifacts/` 是同步 Job 需要的 hostPath 内容：`install` 会按校验和上传到控制机，
再由 `--tags artifacts` 解压到三台节点的 `/tmp/seatunnel-2.3.13`、`/tmp/seatunnel-connectors`。
接管已有 K8s 时不会执行这一步，节点上必须提前准备好这两个目录。

这三样和代码放在同一个目录，整体拷到另一台控制机也能直接跑。
`deploy.sh` 同步时会一并带过去，并在控制机上 `chmod 600`。

## 一条命令（在 deploy 目录下）

```bash
bash deploy.sh install     # 空机器：节点 → 离线产物 → k3s → 平台 → Keycloak → 验收
bash deploy.sh existing    # 已有 K8s：检查 → 平台 → Keycloak → 验收
bash deploy.sh verify      # 只验收
bash deploy.sh testdb      # 可选：测试 PostgreSQL
bash deploy.sh images      # 导入平台镜像（含 registry:2）
bash deploy.sh status      # 看 Pod 和健康检查
bash deploy.sh agent-service  # 首节点铺 agent-service 代码/venv 并 systemd 拉起(8097)
```

`install` 的完整顺序：

```
preflight → node（内核/防火墙/镜像中心 HTTP 配置）
→ k3s（首控/join/标签/kubeconfig）
→ registry（私有仓库 30500）
→ registry-load（把 images/ 灌进 30500，只这一台机器）
→ artifacts（SeaTunnel 发行包 + 连接器）
→ platform（Pod 全部从 30500 拉）
→ keycloak（realm + client）
→ agent-service（首节点铺代码 + systemd enable --now，8097）
→ verify（节点/健康检查/NodePort/agent-service）
```

装完再跑一次元数据层初始化（幂等；尚未并入 Ansible）：

```bash
bash scripts/ops/gravitino-init.sh   # metalake dataworks + catalog doris_dev
```

不跑的话「数据地图」会 502（`CATALOG_OBJECT_NOT_FOUND`）。

平台清单里包含：
- FlinkSQL 流开发运行时（`k8s/17-flink.yaml`：JobManager + TaskManager(4 slot) + SQL Gateway，
  镜像 `library/flink:1.20.1`）；线上独立集群见 `k8s/17b-flink-prod.yaml`。

**Flink 清单应用后必须再跑一次 S3 配置脚本**（UDF 的 `ADD JAR 's3://...'` 需要）：

```bash
bash scripts/ci/configure-flink-s3.sh all
```

它把 MinIO 凭据与 `s3.endpoint / path.style.access` 写进两个 `flink-conf` ConfigMap
并滚动重启 Flink 六个工作负载。清单里没有这一步的等价配置（Flink 1.20 的
flink-conf 不支持 `include`），漏跑时 UDF 在 Flink 侧提交会报「jar 不在对象存储」。

两个 Flink 清单还各有两个与 UDF 相关的前置（已在清单里，不要删）：
`s3-plugin` initContainer（把镜像自带的 `flink-s3-fs-hadoop` 放进 `/opt/flink/plugins`）
和 `flink-nss` ConfigMap（补 `/etc/passwd`，否则 Hadoop UGI 初始化抛 KerberosAuthException）。
详见 `docs/UDF资源管理-20261025.md`。
- 模拟业务数据服务（`k8s/18-mock-data.yaml`，ClusterIP `mock-data:8090`，镜像 `library/mock-data:2`）；
- Kafka 3.9 单节点 KRaft（`k8s/19-kafka.yaml`，`kafka-demo:9092`，镜像 `library/kafka:3.9.0-r2`）。

镜像分别由 `scripts/ci/build-flink-image.sh`、`build-mock-data-image.sh`、
`build-kafka-image.sh` 构建；更新镜像要换 tag，同一 tag + `IfNotPresent` 节点不会重新拉取。
数据集成作业的引擎镜像由后端环境变量 `SEATUNNEL_IMAGE` 指定，默认
`<首节点>:30500/library/seatunnel:dev`（= `dw_registry`，由 inventory 派生，见
`inventory/group_vars/all.yml`）；不要改回 `docker.io/...`——节点把
docker.io 转发到外部源，会 403。

不想让 install 灌镜像：`DW_SKIP_IMAGES=1 bash deploy.sh install`，然后手动 `registry-load`。

脚本会自动把 `deploy/` 同步到控制机 `dw-ops` 并在那执行 Ansible。
Windows 不能原生跑 Ansible，所以控制机是必须的——**但你不需要登录它**。

仓内不存真实网络拓扑/宿主锚点（REQ-OSS-PUBSCRUB-1），开工前先补两个本地文件
（均 gitignore，模板在册）：

```bash
cp inventory/lab.example.yml inventory/lab.yml   # ansible.cfg 默认吃这份：改三节点 IP
cp env.local.sh.example env.local.sh             # 控制机/私有仓库/crane/镜像目录
```

单个值也可用环境变量临时覆盖（优先级最高）：

```bash
DW_OPS=ubuntu@<控制机> DW_KEY=./keys/id_ed25519 bash deploy.sh install
```

## 两个入口（手动执行，调试用）

| 形态 | 入口 | 命令 |
|---|---|---|
| 空机器（装 k3s） | `site.yml` | `ansible-playbook site.yml` |
| 接管已有 K8s | `platform.yml` | `ansible-playbook platform.yml -i inventory/existing.yml` |

## 目录

```
deploy/
├── site.yml                 空机器：preflight → node → k3s → platform → agent-service → verify
├── platform.yml             已有 K8s：check → platform → verify
├── bootstrap-control.sh     控制机一次性准备
├── ansible.cfg
├── requirements.yml         kubernetes.core
├── inventory/
│   ├── lab.yml              空机器清单（3 台 + SSH）
│   ├── existing.example.yml 已有集群模板（只要 kubeconfig）
│   └── group_vars/all.yml   版本 / 端口 / 清单顺序 / 路径
├── secrets/
│   ├── lab.example.yml      18 项密钥模板
│   └── lab.yml              真值（0600，不进仓库）
├── tasks/
│   ├── 10-preflight.yml     只读体检
│   ├── 20-node.yml          系统基线
│   ├── 30-k3s.yml           首控 / join / kubeconfig
│   ├── 40-existing-check.yml 已有集群前置检查
│   ├── 50-platform.yml      密钥 → 标签 → 清单 → 等就绪
│   ├── 60-agent-service.yml 首节点 systemd 拉起 agent-service(8097)（DL-F1 防再死）
│   └── 90-verify.yml        验收（含 8097）
├── systemd/
│   └── agent-service.service 主机级 unit（与仓库 agent-service/ 同步维护）
└── k8s/                     平台对象唯一真源
```

## A. 空机器

```bash
cd deploy
bash bootstrap-control.sh

cp secrets/lab.example.yml secrets/lab.yml
chmod 600 secrets/lab.yml
$EDITOR secrets/lab.yml            # 18 项都必须填

# 镜像要先在节点上就绪（见下节）

ansible-playbook site.yml --check
ansible-playbook site.yml --tags preflight,node,k3s
ansible-playbook site.yml --tags platform
ansible-playbook site.yml --tags verify
```

## B. 接管已有 K8s

```bash
cd deploy
cp inventory/existing.example.yml inventory/existing.yml
$EDITOR inventory/existing.yml     # kubeconfig 路径 / StorageClass / 健康检查 IP

ansible-playbook platform.yml -i inventory/existing.yml --check
ansible-playbook platform.yml -i inventory/existing.yml
```

前置检查会明确拦住三种情况：kubeconfig 不存在、节点没 Ready、
StorageClass 不存在（会告诉你变量名）。

## SSH 访问模式（两种）

Ansible 原生支持，不需要开关变量：设置了哪个就用哪个。

| 模式 | 配置 | 适用 |
|---|---|---|
| 密钥（当前） | `ansible_ssh_private_key_file: ~/.ssh/id_ed25519` | 日常部署 |
| 密码 | `ansible_password` + `ansible_become_password` | 首次接管、还没推公钥的机器 |

密码模式：

```bash
cp inventory/lab-password.example.yml inventory/lab-password.yml
cp inventory/group_vars/vault.example.yml inventory/group_vars/vault.yml
ansible-vault encrypt inventory/group_vars/vault.yml
ansible-playbook site.yml -i inventory/lab-password.yml --ask-vault-pass
```

注意：

- 控制机要有 `sshpass`（`bootstrap-control.sh` 已装）
- 目标机 sshd 要允许 `PasswordAuthentication`
- root 密码登录通常被禁用，密码模式用 `ubuntu` + sudo 密码
- 装完立刻推公钥，之后切回密钥模式
- 重建过节点后如果报 `REMOTE HOST IDENTIFICATION HAS CHANGED`，
  在控制机执行 `ssh-keygen -f ~/.ssh/known_hosts -R <节点IP>` 清掉旧主机密钥；
  否则 SSH 会禁用密码认证，密码模式会直接连不上


## 密钥存在哪里（不在仓库里）

| 类型 | 位置 | 权限 |
|---|---|---|
| SSH 私钥 | `deploy/keys/id_ed25519`（同步到控制机同路径） | 600，gitignore |
| SSH 公钥 | 每台节点 `/home/ubuntu/.ssh/authorized_keys` | 644 |
| kubeconfig | `deploy/clusters/<cluster>.kubeconfig` | 600，gitignore |
| 平台账号密钥 | `deploy/secrets/<cluster>.yml` | 600，gitignore |
| 节点主机密钥 | 每台 `/etc/ssh/ssh_host_*` | 节点自己生成 |

私钥**故意不放进仓库**：仓库是代码，不是密钥库。备份请放密码管理器或加密介质。

## 项目存储：HDFS（三节点原生，不在 K8s 内）

```bash
bash deploy.sh hdfs      # NameNode 在首节点，DataNode 在三台节点
```

| 项 | 值 |
|---|---|
| WebHDFS | `http://<首节点>:9870`（NameNode=首节点；地址由 `dw_node1_ip` 派生，见 group_vars） |
| 数据目录 | `/data/hdfs/{namenode,datanode,tmp}` |
| 部署方式 | Ansible `hdfs.yml`；Hadoop 3.4.1 + 内置 JRE17 |
| 平台连接 | `hdfs_webhdfs_url`（`inventory/group_vars/all.yml`） |

**客户已有 HDFS**：不跑 `deploy.sh hdfs`，只把 `hdfs_webhdfs_url` 改成客户的
`http://<namenode>:9870`。平台只通过 WebHDFS 访问，不在客户集群里装 HDFS。

## 镜像目录

默认 `deploy/images/`（随包拷贝）。客户现场如果把镜像放在别的路径，指定即可：

```bash
DW_IMAGES=/data/offline/images bash deploy.sh install
```

`images.tsv` 是「镜像引用 / tar 文件 / tar 里的源 tag」的映射，新增镜像只改这一行表。

客户现场的正确顺序：**包只拷到一台机器，镜像灌进 30500，节点从仓库拉**。

```bash
bash deploy.sh registry-load       # 一台机器执行：把 images/ 推进 30500
bash deploy.sh install             # 节点按需从 30500 拉
```

逐节点 `images` 只作为离线兜底（例如仓库不可达）：

```bash
bash deploy.sh images
```

## 镜像

平台镜像（backend / prefect / minio / doris / obs 等）不在公网仓库里。
空机器形态需要先把 tar 导入节点：

```bash
# 在 Windows 宿主机（镜像 tar 目录用 DW_IMAGES 指过去）
bash scripts/lab/import-images.sh -b 379
```

已有集群形态：把镜像推进目标集群的镜像仓库，或让集群节点能拉到这些 tag。

## 密钥规则

1. `secrets/<cluster>.yml` 是唯一真源，部署只读，不生成、不覆盖。
2. 缺任何一项 → 停，列出缺的 key。
3. 接管已有集群：先按现有 Secret 填这份文件，保证第一次不换密码。
4. 可加密：`ansible-vault encrypt secrets/lab.yml`。

## 主机服务：agent-service(8097)（DL-F1，2026-10-30 收编）

助手对话链路：浏览器 → 网关 :30090 → Java `/api/agent/*` 代理 → **主机进程** `:8097`。
它是主机级 systemd 服务，不在 K8s 里；此前只在机器上 setsid 裸跑过，机器重建后无人拉起，链2 对话 503。

| 项 | 值 |
|---|---|
| unit 源文件 | `deploy/systemd/agent-service.service`（与仓库 `agent-service/` 同步维护） |
| 节点安装路径 | `/etc/systemd/system/agent-service.service` |
| 代码与 venv | `/opt/dataworks-agent/agent-service`（仓库 `agent-service/` + `agent/tools`）、依赖包 `/opt/agent-core`（enter-harness） |
| 环境文件 | `/opt/dataworks-agent/agent-service/.env`（600，属主 ubuntu） |
| token 来源 | k8s secret `platform-keys` 的 `AGENT_SERVICE_TOKEN` / `PLATFORM_INTERNAL_TOKEN`（与后端 `agent.service-token` 同源，见 `k8s/06-mcp.yaml`） |
| 拉起与自愈 | `systemctl enable --now agent-service`；`Restart=always`，开机自起 |
| 健康检查 | `curl http://<首节点>:8097/api/health` → `{"ok":true,...}` |

部署收编（防再死）：

- 空机器形态：`site.yml` 新增 play「主机服务 agent-service（首节点）」（tag `agent-service`），
  在平台之后、验收之前执行；`deploy.sh install` 阶段 4 的 tags 已加入 `agent-service`。
- 接管已有 K8s / 日常拉起：开发机 `deploy/` 下 `bash deploy.sh agent-service`（铺代码+venv+unit，幂等；
  agent-core 不在部署包里，用 `DW_AGENT_CORE=/path` 指定）。
- 验收：`90-verify.yml` 检查 `:8097/api/health` 且 `ok=true`；不想验收它，group_vars 设
  `requires_agent_service: false`。

模型 Key：`AGENT_MODEL_API_KEY` 只在 `.env`，不落仓库。为空时服务正常、health ok=true，
但 chat 会以 `status=failed` 结束（环境凭证缺失，非路由坏）；运维手填后
`systemctl restart agent-service`。

## 验收

`90-verify.yml` 会检查：节点 Ready、`/api/health` 200、`agent-service :8097/api/health ok=true`、
打印所有 NodePort。任何一项失败，playbook 非 0 退出。

## 回滚

仓库打 tag，回滚 = 切到上一个 tag 重跑；单服务可 `kubectl rollout undo`。
