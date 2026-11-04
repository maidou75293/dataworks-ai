# 单机演示形态（compose 六件套）

给第一次接触本平台的你：不需要 Kubernetes、不需要作者内网、本机装好 Docker 就能跑起来看界面。
生产/集群形态请走 [`deploy/README.md`](../README.md)（Ansible + k3s）。

## 一键（推荐）

```bash
cd deploy/compose
bash up.sh          # 生成随机本机口令 → 构建 → 起栈 → 等健康 → 打印一次性 admin 口令
```

- 首次构建要在容器里编译前后端（Maven / npm 走公网源），约 10~20 分钟；之后秒级复用缓存。
- 结束会打印 `http://localhost:30090` + 用户名 `admin` + 一次性口令；口令同时存 `.env`（已 gitignore）。
- 停止：`bash down.sh`；连数据清掉重来：`bash down.sh --volumes && rm .env`。

## 直接 compose（不跑 up.sh）

```bash
docker compose up -d
```

能用，但走的是 docker-compose.yml 里的 `demo-*` 兜底口令——**只允许本机看界面，
绝不能把这套端口暴露到任何公网/共享网络**。

## 起了什么

| 服务 | 镜像 | 端口（本机） | 作用 |
|---|---|---|---|
| web | 本目录 `Dockerfile.web`（nginx + SPA） | **30090** | 平台入口；`/api` 反代 backend |
| backend | 本目录 `Dockerfile.backend`（Spring Boot） | 仅容器网 | 平台 API |
| postgres | docker.io/library/postgres:16-alpine | 不映射 | 平台元数据库（Flyway 自动建表） |
| keycloak | docker.io/keycloak/keycloak:26.0 | 30081 | SSO 身份源（realm `dataworks` 自动初始化） |
| s3 | docker.io/chrislusf/seaweedfs:4.52 | 30900(S3) / 30901(控制台) | 对象存储（S3 协议） |
| doris | docker.io/apache/doris:2.1.9-all | 30930(MySQL 协议) | 内置数仓（业务账号自动创建） |
| keycloak-bootstrap / doris-init | 一次性 Job | — | realm/client、Doris 业务账号（跑完即退） |

## 最小验证

```bash
curl -s http://localhost:30090/api/health   # → {"status":"UP", ...}
```

浏览器打开 http://localhost:30090 ，用 `up.sh` 打印的一次性口令登录（用户名 admin）。

## 演示未含（能登录看界面，不代表这些功能可用）

Prefect 调度、SeaTunnel 数据集成、Flink 流开发、Spark、Gravitino 数据地图、监控栈、MCP 助手。
这些依赖集群形态或外部服务，compose 里把对应地址指到 `.invalid` 主机——点开会有明确报错，
不会把请求发进作者内网。依赖范围见根 README「最小依赖表」。

## 排障

- **为什么对象存储不是 MinIO**：MinIO 官方公有镜像与二进制下载已全面停发
  （2026-11-04 实测 hub.docker.com/minio/minio 404、quay.io 0 标签、dl.min.io 410），
  干净机器拿不到；后端用通用 AWS SDK（path-style），故以 SeaweedFS S3 替代，行为等价。

- **构建阶段 `failed to fetch anonymous token`（连 auth.docker.io 超时）**：Docker Desktop +
  宿主网络被拦时，BuildKit 解析基座镜像元数据由**宿主进程**直连 Docker Hub 鉴权（daemon 侧拉取
  却正常）。`up.sh` 已内置先 `docker pull` 四个构建基座绕开；裸跑 `docker compose up -d --build`
  遇到时手动预拉同样解决。
- **Doris BE 起不来（`show backends` 里 Alive=false）**：BE 的 `start_be.sh` 会校验
  `vm.max_map_count≥2000000`、无 swap、`nofile≥60000`，Docker Desktop（WSL2）默认过不了前两道
  且都是整机 VM 值、容器内改不动。本文件已注入官方逃生门 `SKIP_CHECK_ULIMIT=true`
  （doris 件同时 privileged 回写 vm 值；仅限本机演示形态）。
- 健康等超时：`docker compose ps` 看谁没起来，`docker compose logs backend` 看 Flyway/启动报错。
- 端口占用：30090/30081/30900/30901/30930 被占就改 `docker-compose.yml` 的 `ports:` 左侧。
- 磁盘：全部镜像约 6~8GB（Doris all-in-1 占大头）；构建缓存另算。
- Windows：Docker Desktop（WSL2 后端）或任意 Linux VM 均可；资源建议 ≥ 8GB 空闲内存。
