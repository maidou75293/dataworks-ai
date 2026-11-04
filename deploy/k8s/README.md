# 平台对象清单（唯一真源）

一个目录，文件名前缀就是依赖顺序；顺序写在
`inventory/group_vars/all.yml` 的 `platform_manifests` 里。

```
00-namespace          命名空间 + 配额
05-registry           私有仓库 30500（单 Pod，不建集群内缓存）
01-secrets.j2         11 个 Secret，值来自 secrets/<cluster>.yml
01-postgres           PostgreSQL（initdb 自动建 prefect_dev / gravitino_dev）
02-minio              对象存储
03-doris-service      Doris 客户端 Service
05-doris-k8s          Doris FE / BE
04-embedding          embedding 服务
06-mcp-*              MCP 服务与 ConfigMap
07-prefect            Prefect server
08-prefect-*          flows / work pool
09-prefect-bridge     Prefect 桥接
12-keycloak           身份认证
14-gravitino          元数据服务
13-observability      Prometheus / Grafana
16-monitoring         告警 / node-exporter
22-local-shared       本地文件共享卷（local-path PVC）
11-backend            后端（前端静态资源已打进 jar）
10-nodeports          对外 NodePort（只保留 MCP；PG 不对外）
19-domain             Ingress
```

规则：

1. **只保留这一份**，不再有第二套模板，也不用 Helm。
2. `storageClassName: local-path` 会在 apply 前按 `platform_storage_class` 替换，
   已有集群换 StorageClass 只改一个变量。
3. 镜像统一写 `${DW_REGISTRY}/...` 前缀占位，apply 前按 `dw_registry` 变量替换
   （默认 `{{ registry_host }}:30500`=集群内私有仓库；接管已有集群/外部仓库
   `-e dw_registry=registry.example.com:5000`，纯公网镜像 `-e dw_registry=docker.io`——
   自建件 dw-runtime/dataworks-backend 等需先 push 到所设仓库）。
   **绕过 Ansible 直接 `kubectl apply` 的脚本必须先 sed 展开该占位**
   （已改：configure-flink-s3.sh / spark-up.sh / doris-meta-ro.sh）。
4. Secret 用 `stringData`，避免 `kubectl apply` 把明文写进
   `last-applied-configuration` 注解。
5. 需要 Jinja 的文件才用 `.j2`；普通清单除第 2/3 条的固定占位外按原样读取，
   避免 Prometheus/Grafana 里的 `{{ }}` 被误渲染。

SeaTunnel 已纳入（去掉 `/tmp` hostPath，直接用镜像自带发行包与连接器）；测试库（ftp/webhook）暂未纳入。
