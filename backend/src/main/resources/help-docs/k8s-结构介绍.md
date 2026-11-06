---
title: K8s 结构介绍
manual-date: 2026-11-03
order: 1
sources: docs/系统监控.md, docs/运维-异常Pod展示与集群内存优化-20261103.md, docs/运维-k3s扩容与SeaTunnel恢复-20261020.md, deploy/README.md, deploy/k8s/
---

# K8s 结构介绍

本页介绍平台跑在什么样的集群上，每一节对应「平台管理 → 系统监控」（页面 `/system`）的一个区块。
内容全部提炼自仓库在册文档（见文末来源），不另编事实；数字截至手册生成日期。

## 一、集群总览：三节点 k3s

- 集群是 **k3s v1.36.5+k3s1**，三台虚拟机即三个节点，控制面和负载混跑：

| 节点 | IP | 说明 |
|---|---|---|
| k8s-node-1 | 192.0.2.11 | 平台入口；私有镜像仓库固定在这台 |
| k8s-node-2 | 192.0.2.12 | 工作节点 |
| k8s-node-3 | 192.0.2.13 | 工作节点 |

- 主机是 Hyper-V 虚拟机，静态内存 9G×3（2026-11-03 从 12G 削减），每节点可分配（allocatable）约 8.7Gi。
- k3s 自带组件保留 Traefik（MCP 网关要用 Ingress）、CoreDNS、local-path 存储；
  服务入口直接用 NodePort，不依赖云厂商负载均衡。

## 二、命名空间职责

系统监控页同时观察三个命名空间（配置 `platform.monitor.namespaces`，默认即下面三个）：

| 命名空间 | 职责 | 里面有什么 |
|---|---|---|
| dw-dev | 平台主战场 | 后端、PostgreSQL、MCP、Keycloak、Gravitino、Prefect 全家（server/worker/pool-worker/bridge）、embedding、SeaTunnel×3、MinIO×3、Doris（FE+BE）、Kafka、Flink 开发集群、Flink 生产集群、监控组件 |
| dw-spark | Spark 执行侧 | Spark Thrift Server 及其 executor（当初因 dw-dev 配额打满迁出去） |
| kube-system | 集群自身 | Traefik 入口、CoreDNS 等；2026-11-03 起纳入监控，入口组件卡死不再成盲区 |

## 三、入口与端口

对外一律 NodePort 直达（IP 用任一节点，惯例 192.0.2.11）：

| 端口 | 入口 | 用途 |
|---|---|---|
| 30090 | dataworks-backend | **平台入口**：页面和 API 同一个端口（前端构建产物打进后端 jar） |
| 30080 | mcp-nodeport | MCP 网关（Traefik Ingress，主机名 data-ai-agent） |
| 30081 | keycloak | 统一身份 |
| 30091 | prometheus | 指标库 HTTP API |
| 30092 | grafana | 历史曲线看板（系统监控页右上角直达） |
| 30093 | gravitino | 统一元数据服务 |
| 30500 | registry | 私有镜像仓库（见第七节） |
| 30900 | minio-external | MinIO S3 对外端点 |

## 四、四集群卡（对应系统监控页顶部）

- **Doris 集群**：FE×1（8030）+ BE×3（8040，DaemonSet 每节点一个）。数仓存储底座，
  查询、元数据、质量、看板都跑在它上面。
- **SeaTunnel Zeta 集群**：StatefulSet seatunnel-0/1/2。数据集成引擎，同步作业跑在这里；
  扩缩容直接改 StatefulSet 副本数，新节点自动加入。发行包与连接器放在节点
  `/tmp` 的 hostPath 上——节点重启后目录被清会起不来，处置见《运维手册》第三节。
- **MinIO 对象存储**：StatefulSet minio-0/1/2（3 节点纠删码）。S3 后端，
  承担项目存储与冷热分离远端；健康判定以 Pod 实况为准（Prometheus 抓取失败不翻转状态）。
- **HDFS 集群**：项目存储后端三选一（HDFS / MinIO / local-path）之一。
  若在 K8s 外（三节点原生或客户已有），平台只通过 WebHDFS 探测，卡片显示「外部 HDFS」。

## 五、监控采集链路（系统监控页每个数字从哪来）

```text
node-exporter(9100) + kubelet/cadvisor(10250) + apiserver(6443)
+ Doris FE/BE 指标 + 后端 /actuator/prometheus      ——15s 抓取——▶  Prometheus(30091)
+ 后端 /api/internal/metrics/k8s（替代 kube-state-metrics）        保留 7 天，5Gi PVC
                                                                    │
                          页面 /system 每 15s 刷新 ◀── /api/system/*┤
                          Grafana(30092) 历史曲线 ◀─────────────────┘
告警：Prometheus 规则(11 条) → Alertmanager → 后端 Webhook → 告警中心（source=SYSTEM）
```

- 页面区块：汇总卡（节点就绪 / Pod 运行中 / 异常 Pod / 副本不足 / 最高磁盘 / 未关闭告警）、
  节点表（CPU/内存/磁盘进度条）、异常 Pod 表（含 kube-system，带命名空间列）、
  核心组件表、工作负载表。
- 不用 kube-state-metrics：DEV 拉不到外部镜像仓库，节点/Pod/Deployment 指标由后端
  `/api/internal/metrics/k8s` 按同口径导出。

## 六、核心组件清单（核心组件表的 16 行）

平台后端、PostgreSQL、Prefect Server、Prefect Worker、Prefect Pool Worker、
Doris FE、Doris BE、MinIO、Keycloak、Gravitino、SeaTunnel、Spark Thrift、
Prometheus、Grafana、Alertmanager、node-exporter。
组件副本被缩到 0 也不会从清单里消失——页面上会显示不就绪，而不是少一行。

## 七、资源账本：配额、LimitRange 与右调

- **ResourceQuota `dw-dev-quota`**：上限 34Gi 内存 / 48 CPU。2026-11-03 人工右调后
  requests.memory 用量 32.4Gi → 17.7Gi（余量约 16Gi），**滚动重建死锁先看这里**。
- **LimitRange 默认 1Gi/128Mi**：给没写资源声明的新 Pod（同步 Job 类）兜底。
- **在线右调**：k3s v1.36.5 支持 Pod `resize` 子资源，改内存上限不重启容器；
  但 JVM/进程内存声明类（Doris `-Xmx`、Flink `taskmanager.memory.process.size`）
  改了上限不会自动改堆，必须滚动重启并联动 ConfigMap。
- 系统监控页有「右调建议器」：按 Prometheus 7 日 P95 出每负载建议，人工一键应用，
  带三条硬闸（无数据不产建议 / 应用前校验配额与节点余量 / 堆与 limit 联动防倒挂）。

## 八、镜像从哪来、源码放在哪

- 镜像统一走私有仓库 `192.0.2.11:30500`：发布包拷到**一台机器**，
  `bash deploy.sh registry-load` 灌进 30500，三节点按需拉取；不要逐台 scp，
  工作负载镜像地址不要改回 `docker.io/...`（DEV 无外网，改了必 ImagePullBackOff）。
- 后端镜像 = 前端构建产物 + Spring Boot jar，叠在固定 JRE17 基础镜像上：
  `scripts/ci/build-backend-image.sh <版本>` 构建、`deploy-backend-image.sh <版本>` 滚动。
- 部署唯一入口 `deploy/`（Ansible：空机器 `site.yml`，已有 K8s `platform.yml`）；
  运行时旁路服务源码在 `services/`（MCP、embedding、Prefect bridge、StreamVision 等）。

---

来源（单一事实源，正文以这些在册文档为准）：
`docs/系统监控.md`、`docs/运维-异常Pod展示与集群内存优化-20261103.md`、
`docs/运维-k3s扩容与SeaTunnel恢复-20261020.md`、`docs/系统监控-内存右调-20261103.md`、
`deploy/README.md`、`deploy/k8s/` 清单、后端 `system/` 包代码注释。
