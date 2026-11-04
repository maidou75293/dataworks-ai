# DataWorks-AI（中文说明）

**开源、可私有化部署的一站式数据平台** —— 对标 DataWorks 核心场景：数据集成、数据开发、调度运维、数据质量、数据服务与可视化 BI，并内置**对话式智能体（Agent 服务）**辅助数据开发。单机 `docker compose` 三分钟看界面，Ansible + k3s 上生产。

[English README](README.md) · [Apache-2.0](LICENSE)

## 为什么做这个

团队从"定时任务 + 脚本"长大之后，面对的选项通常只有两个：上云数据平台（数据与管道绑死一家厂商），或者自己拼装六件套（Airflow + dbt + DolphinScheduler + Superset + Ranger + …），然后被迫养一支 SRE。

DataWorks-AI 把这条工作流做成**一个产品**：数据源 → 集成 → 开发 IDE → 调度 → 质量 → 发布 API/BI → 治理审计 → 运维，上面再罩一层能查元数据、帮你写任务的**对话智能体**。默认私有化：凭据 AES-256-GCM 落盘加密、全程不出内网、整栈 Docker/K8s 可气隙部署。

## 30 秒起环境

唯一前提 **Docker**（compose v2.24+）。前后端都在容器里编译，本机不需要装 Java/Node。

```bash
git clone https://github.com/maidou75293/dataworks-ai.git
cd dataworks-ai/deploy/compose
bash up.sh
```

`up.sh` 会生成本机随机口令 → 构建镜像 → 起栈（web、backend、PostgreSQL、Keycloak SSO、SeaweedFS 对象存储、Apache Doris）→ 等健康检查 → 打印**一次性 admin 口令**。浏览器打开 `http://localhost:30090`。停止用 `bash down.sh`。

> 首次构建要拉 Maven/npm 依赖，约 10~20 分钟；之后有缓存，秒级启动。
> 直接 `docker compose up` 也能起，但走的是公开的 `demo-*` 兜底口令——**只许本机看界面，端口绝不能暴露到公网/共享网络**。

## 功能盘点

| 域 | 能力 |
|---|---|
| **数据集成** | 批量同步任务、异构数据源接入；演示栈自动创建 Doris/PG 业务账号 |
| **数据开发** | SQL IDE（Flyway 元数据迁移）、工作流 DAG 编辑器、函数/UDF 管理、流开发（Flink on K8s） |
| **调度运维** | DAG 调度（Prefect 内核）、运行实例、任务基线、告警中心（邮件/webhook，通道密钥加密存储）、运维总览 |
| **质量与治理** | 质量规则、SQL 变更审批流（强制备份计划）、权限申请流、安全中心、元数据目录、血缘、元数据巡检 |
| **数据服务与 BI** | 数据集发布成 API、内置可视化、多租户工作空间 |
| **多模态与模型（MOS）** | 标注项目、训练集、训练任务、模型评测、模型资产、数据飞轮——给 AI 训练数据用的一条小型 MLOps 泳道 |
| **智能体服务** | 接任意 OpenAI 兼容大模型；对话式数据开发；系统配置页改参数**免重启**即时生效；密钥界面只显示尾 4 位指纹 |
| **安全** | Keycloak SSO + 本地账号、会话管理、密钥统一走 AES-256-GCM（主密钥 `PLATFORM_SECRET_KEY` 缺失则后端拒绝启动）、密钥访问有审计 |

架构速览：`React SPA (nginx) → Spring Boot API（34 个模块）→ PostgreSQL 元数据 / Doris 数仓 / SeaweedFS S3`，可选 `→ Python agent-service → 你自选的大模型`。K8s 生产形态为模板化清单（`deploy/k8s/`）+ Ansible inventory 驱动。

## 目录结构

| 路径 | 内容 |
|---|---|
| `backend/` | Spring Boot 主服务，Flyway 迁移在 `src/main/resources/db/migration` |
| `web/` | React + TypeScript 前端 |
| `agent-service/` | Python 智能体服务（环境变量驱动，热配置） |
| `deploy/compose/` | 单机演示栈（`up.sh` / `down.sh`） |
| `deploy/` | 生产路径：Ansible playbook + k3s/K8s 模板，全部值为 `*.example.*` 模板 |
| `mos/` | 多模态资产 / 标注子系统 |
| `services/` `prompt-graph/` `e2e/` | 周边服务、提示词资产、端到端验收框架 |

## 配置约定

所有可调项遵循"**用户可改、必有默认值**"：每个组件带 `.env.example`，留空即用安全本地兜底；真实口令只放 gitignore 的 `.env` / `keys/` / `secrets/`，界面永不回显明文。生产部署的主机相关值从你自己的 `env.local.sh` 解析（模板 `deploy/env.local.sh.example`）——仓库内按设计**零内网 IP**。

## 诚实的局限

- compose 演示栈是**单机评估形态**；生产调度/流处理能力走 K8s 路径。
- MOS/模型训练泳道假定你有自己的 GPU 集群——平台做编排，不做训练本身。
- 智能体效果取决于你接的大模型，仓库不附带任何模型权重。
- 本仓 2026-11-05 首发，文档毛边可能先于代码 bug——带复现步骤的 issue 是最快的修复路径。

## 开发

```bash
# 后端（容器内编译，或本机 JDK 21 + Maven）
cd backend && ./mvnw spring-boot:run
# 前端
cd web && npm ci && npm run dev
```

`.github/workflows/` 的 CI 门在每个 PR 上运行。

## 许可

Apache License 2.0 —— 见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
