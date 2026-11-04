# 常驻小 Spark 集群（Spark Thrift Server）

平台脚本引擎 `SPARK` 的运行时：一个长期在线的 Spark Thrift Server（HiveServer2
协议，10000 端口），平台用 `jdbc:hive2://` 连它执行 SQL，和 Doris 数据源走同一套
「数据源 → 测试连接 → SQL 控制台」链路。

清单只有一份：**`deploy/k8s/17c-spark.yaml`**（已登记在
`deploy/inventory/group_vars/all.yml` 的 `platform_manifests` 里，按顺序在
`17-flink.yaml` 之后、`18-mock-data.yaml` 之前 apply）。
本目录只放说明，不放第二份 YAML —— `deploy/k8s/README.md` 的规则是「只保留这一份」。

## 形态

```
Deployment spark-thrift (1 副本, strategy=Recreate)
└── Pod spark-thrift            driver + Thrift Server
    ├── spark-submit --class HiveThriftServer2  (client 模式)
    ├── 端口 10000              对外只经 Service spark-thrift (ClusterIP)
    └── 通过 k8s API 拉起 ↓
        ├── Pod spark-executor-1   (1 core / 1g + 384m overhead)
        └── Pod spark-executor-2   (1 core / 1g + 384m overhead)
```

为什么是 client 模式而不是 cluster 模式：Thrift Server 是一个长驻 JVM，
driver 必须在同一个容器里持续提供 10000 端口。所以
`spark.submit.deployMode=client`，driver 就在 `spark-thrift` Pod 里，
executor 由它调用 k8s API 动态创建（需要 `ServiceAccount spark` 的 pod 增删权限）。

两处容易踩的坑已经在清单里处理：

1. `spark.driver.host=$POD_IP` —— 不写就会填成容器 hostname，executor 回连失败。
2. `/etc/passwd`、`/etc/group` 用 `spark-nss` ConfigMap 覆盖 —— 基础镜像里没有
   `spark` 用户时 Hadoop UGI 取不到用户名，Thrift Server 起不来。

## 前置条件

| 依赖 | 说明 |
| --- | --- |
| 镜像 | `<私有仓库>/library/dataworks-spark:3.5`（=DW_REGISTRY，取值见 deploy/env.local.sh.example），由 `scripts/ci/build-spark-image.sh` 构建（Spark 3.5.3 + Iceberg runtime + AWS bundle + hadoop-aws + spark-doris-connector，底座复用 `library/flink:1.20.1` 的 JRE17）；构建时自动登记进 `deploy/images/images.tsv` |
| 镜像 Entrypoint | **必须**是 `["/bin/bash","/opt/spark/entrypoint.sh"]`。executor Pod 用镜像 ENTRYPOINT 启动（不走 `spark-submit`），拿不到 JVM 参数，JDK17 下缺 `--add-opens java.base/sun.nio.ch` 就 `IllegalAccessError`。来源是 `deploy/spark/entrypoint.sh` + `scripts/ci/append-image-layer.py` 的 `ENTRYPOINT_ARG` 参数——两者缺一，构建脚本会直接报错退出 |
| Secret `minio-credentials` | 键 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`，Iceberg 与 `s3a://` 共用 |
| Secret `doris-credentials` | 键 `DORIS_USER` / `DORIS_PASSWORD`，Doris catalog 用 |
| MinIO | `minio.dw-spark.svc.cluster.local:9000`，桶 `lake`，warehouse `s3://lake/warehouse` |
| Doris FE | `doris-fe.dw-spark.svc.cluster.local:8030`（catalog 元数据）、9030（查询） |

凭据**不写进 ConfigMap**：ConfigMap 里只有启动脚本 `spark-thrift-entrypoint`，
容器启动时把 Secret 注入的环境变量渲染进 `/tmp/spark-conf/spark-defaults.conf`
再 `spark-submit`。这样 `kubectl get cm -o yaml` 看不到明文，ConfigMap 也能进版本库。

构建镜像（Windows 侧，产物在 `/d/lab/.work/`）：

```bash
bash scripts/ci/build-spark-image.sh 3.5          # 只出 tar，并登记进 deploy/images/images.tsv
PUSH=1 bash scripts/ci/build-spark-image.sh 3.5   # 额外 crane push 到 30500
```

**镜像进离线包这一步不能省**：`deploy/deploy.sh registry-load`（以及 `images`）
只按 `deploy/images/images.tsv` 逐行推/导镜像，`deploy/images/` 本身是 gitignore 的交付物。
脚本现在会自动把 tar 拷进 `deploy/images/` 并补一行，所以空机器重装
（`site.yml` → `deploy.sh registry-load`）也能拉到 `dataworks-spark:3.5`；
手工构建/替换镜像时同样要保证这一行存在，否则新环境表现为 `ImagePullBackOff`。

## 部署

走平台统一入口（会同步到控制机再 apply 全部清单，Spark 清单幂等）：

```bash
cd deploy
bash deploy.sh existing      # 已有集群只装平台
bash deploy.sh status        # 看 Pod / 健康检查
```

只重刷 Spark 这一份清单时（控制机上执行，kubectl 在控制机，不在 Windows）：

```bash
kubectl apply -f deploy/k8s/17c-spark.yaml
kubectl -n dw-spark rollout status deploy/spark-thrift --timeout=300s
```

`strategy=Recreate` 是有意的：Thrift Server 只有一个实例，滚动发布会出现
「两个 driver 抢同一批 executor」，所以先停后起；重启期间 SQL 会短暂失败。

冷启动给 5 分钟（`startupProbe` 30×10s）：driver 要先连 k8s API 把 2 个
executor 拉起来，第一次还要拉镜像。

## 验证

从源码到验收一条命令（地址一致性 → 构建推送 → apply → 等 rollout → 五步验收）：

```bash
bash scripts/ci/spark-up.sh 3.5
```

只查「地址有没有漂移」（清单 / 后端白名单 / 默认 URL / 前端模板四处）：

```bash
bash scripts/ci/check-spark-wiring.sh
```

只跑验收：

```bash
bash scripts/ci/verify-spark.sh
```

五步全绿再交付：① `select 1` ② Iceberg 表查询 ③ Doris connector 查询
④ Iceberg join Doris ⑤ 平台数据源测试连接。每步打印 PASS/FAIL 和原始输出。
常用变量：

```bash
NS=dw-spark \
ICEBERG_TABLE=iceberg.lake.demo_events \
DORIS_TABLE=doris.demo.events \
PLATFORM_URL=http://<首节点>:30090 \
PLATFORM_USER=admin PLATFORM_PASSWORD=*** \
bash scripts/ci/verify-spark.sh
```

## 平台接入

「数据源 → 新建」选类型 **Spark**：

| 字段 | 值 |
| --- | --- |
| JDBC URL | `jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/` |
| 用户名 | `spark`（Thrift Server 未开认证，用户名只进 UGI） |
| 密码 | 留空（后端对 `SPARK` 类型允许空口令） |

集群外的机器（宿主机/DBeaver）用 `kubectl -n dw-spark port-forward svc/spark-thrift 10000:10000`
再连 `jdbc:hive2://127.0.0.1:10000/`。

## 资源占用

| 组件 | requests | limits | 实际 RSS 量级 |
| --- | --- | --- | --- |
| driver（spark-thrift） | 1 core / 1Gi | 2 core / 2Gi | 1.2–1.6 GB |
| executor × 2 | 各 1 core / 1Gi + 384m overhead | — | 各 1.1–1.3 GB |
| **合计** | 3 core / 3 Gi | — | **约 3–4 GB** |

executor 的 requests/limits 由 Spark 自己按 `spark.executor.memory` +
`spark.executor.memoryOverhead` 生成，**不受** Deployment 的 `resources` 约束；
Deployment 里那一段只管 driver。集群配额（`00-namespace.yaml`）要留出这部分余量：
多加一个 executor 就多 1 core / 约 1.4 GB。

## 扩 / 缩 executor

改 `deploy/k8s/17c-spark.yaml` 里 entrypoint 的 here-doc（不是改 Deployment）：

```yaml
spark.executor.instances   2      # ← 改成 3/4；常驻实例数
spark.executor.cores       1
spark.executor.memory      1g     # 单 executor 堆
spark.executor.memoryOverhead 384m # 堆外，建议 ≥ max(384m, 0.1×堆)
```

然后 `kubectl apply -f deploy/k8s/17c-spark.yaml` 并
`kubectl -n dw-spark rollout restart deploy/spark-thrift`（ConfigMap 变更不会自动
触发重启）。验证：

```bash
kubectl -n dw-spark get pod -l app=spark-executor -w
```

想让负载自己伸缩时，把 `spark.dynamicAllocation.enabled` 改成 `true` 并加
`minExecutors=1` / `maxExecutors=4`；注意集群外没有 shuffle service，
`dynamicAllocation.shuffleTracking.enabled=true` 必须一起开，否则 executor 回收后
shuffle 数据丢失、查询报 `FetchFailed`。

## 排障

| 现象 | 原因 / 处理 |
| --- | --- |
| Pod `CrashLoopBackOff`，日志 `POD_IP required` | Deployment 少了 `fieldRef: status.podIP` |
| 日志 `Cannot locate default realm` / `No such user spark` | `spark-nss` ConfigMap 没挂上 `/etc/passwd` |
| executor Pod 一直 `Pending` | 命名空间配额不够（3 core / 4 Gi），或 `ServiceAccount spark` 的 Role 缺 `pods create` |
| executor 起不来，报 `exec: "executor": executable file not found` | 镜像没设 Entrypoint（`append-image-layer.py` 没收到 `ENTRYPOINT_ARG`，或构建时缺 `deploy/spark/entrypoint.sh`）；重跑 `build-spark-image.sh`，它现在会预检这两项 |
| executor 日志 `IllegalAccessError ... sun.nio.ch.DirectBuffer`（或 `StorageUtils`） | executor 用镜像 ENTRYPOINT 直起 java，没有 `--add-opens java.base/sun.nio.ch`；确认镜像 Entrypoint 是 `/opt/spark/entrypoint.sh` 且脚本里有 `SPARK_JAVA_OPT_*` + 兜底 add-opens |
| 新环境 Pod `ImagePullBackOff` | 私有仓库没有该 tag，或 `deploy/images/images.tsv` 没登记 `dataworks-spark`；对比 `curl -s $REGISTRY/v2/library/dataworks-spark/tags/list` 与 `images.tsv` |
| executor 起了又退，driver 日志 `Connection refused` | `spark.driver.host` 用了 hostname；确认是 `$POD_IP` |
| `select * from doris.…` 报 `Catalog not found` | `spark.sql.catalog.doris` 的类名或 fenodes 写错；executor 也要能解析 `doris-fe` |
| Doris catalog 报 `ClassNotFoundException com.mysql.jdbc.Driver` | 镜像缺 MySQL JDBC 驱动；`build-spark-image.sh` 已带 `mysql-connector-java-5.1.49.jar`，重跑构建 |
| Iceberg 查询 `ClassNotFoundException S3FileIO` | 镜像缺 `iceberg-aws-bundle.jar`，重跑 `build-spark-image.sh` |
| `No FileSystem for scheme "s3a"` | 镜像缺 `hadoop-aws` + `aws-java-sdk-bundle`（构建脚本会同时校验两者） |
| 10000 连不上但 Pod Running | 探针只看 TCP；查 driver 日志里 Thrift Server 是否 `Started` |

日志：

```bash
kubectl -n dw-spark logs deploy/spark-thrift --tail=200
kubectl -n dw-spark logs -l app=spark-executor --tail=100
```
