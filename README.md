# DataWorks-AI

一个可私有化部署的 **AI 数据中台**（对标 DataWorks 核心场景）：数据集成、数据开发、调度、质量、数据服务与可视化 BI，内置对话式智能体（Agent 服务）辅助数据开发。

Java (Spring Boot) 后端 + React/TypeScript 前端 + Python Agent 服务，全部容器化，单机 Docker Compose 即可起完整演示环境。

## Quickstart（3 分钟看界面）

唯一前提：**Docker**（Docker Desktop 或 docker engine + compose v2.24+）。前后端在容器里编译，本机不需要装 Java/Node。

```bash
cp deploy/compose/.env.example deploy/compose/.env   # 或直接 bash up.sh 自动生成随机口令
cd deploy/compose
bash up.sh
# 打开 http://127.0.0.1:30090  （admin 口令在 up.sh 结束时打印一次）
```

生产/集群部署见 `deploy/`（Ansible + k3s，支持接管已有 K8s 集群，模板见 `deploy/inventory/*.example.yml`）。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `backend/` | Spring Boot 主服务（数据集成/开发/调度/质量/服务/BI API，Flyway 迁移） |
| `web/` | React + TypeScript 前端 |
| `agent-service/` | 对话式智能体服务（OpenAI 兼容接口接入，配置见 `agent-service/.env.example`） |
| `services/` | 附加服务接入说明 |
| `deploy/` | 单机 compose 与集群 Ansible 部署模板（全部为示例值，真实口令放 gitignore 的 `.env` / `keys/` / `secrets/`） |
| `prompt-graph/` `e2e/` | 提示词资产与端到端验收框架 |
| `mos/` | 智能标注子系统（Label Studio 定制） |

## 配置约定

所有可调项遵循"**用户可改、必有默认值**"：每个服务带 `.env.example`，留空即用内置兜底（仅本机演示用，切勿对外暴露）；真实密钥永不入库、永不回显明文（密钥只显示尾 4 位指纹）。

## 安全

告警通道凭据等落盘均经 AES-256-GCM 加密（主密钥 `PLATFORM_SECRET_KEY`，缺失时后端拒绝启动）。发现安全问题请开 Issue 或直接联系维护者。

## License

Apache License 2.0（见 `LICENSE`，第三方组件声明见 `NOTICE`）。
