# dataworks agent-service

agent-core 的独立 HTTP 进程，供数据中台 Java 后端以内部接口调用。当前只开放只读工具。

## 运行

```bash
cd agent-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install /opt/agent-core        # 或本机 agent-core 路径
cp .env.example .env                          # 填入模型与内部 token
setsid nohup bash -c 'set -a; . ./.env; set +a; \
  exec .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8097' \
  >/tmp/agent-service.log 2>&1 </dev/null &
```

环境变量：

| 变量 | 说明 |
|---|---|
| `AGENT_MODEL_BASE_URL` | OpenAI 兼容地址，默认 `https://api.deepseek.com/v1` |
| `AGENT_MODEL_API_KEY` | 模型 Key（不进代码库） |
| `AGENT_MODEL_NAME` | 模型名，默认 `deepseek-chat` |
| `BACKEND_URL` | Java 后端地址 |
| `PLATFORM_INTERNAL_TOKEN` | 调用 `/api/internal/agent/*` 的凭证 |
| `AGENT_SERVICE_TOKEN` | Java 代理调用本服务的凭证 |

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| POST | `/api/chat` | SSE 对话（请求头 `X-Agent-Token`） |
| GET | `/api/manual` | 全量操作手册（自动导出） |
| GET | `/api/manual/{module}` | 按模块取手册 |

SSE 事件：`start` / `delta` / `tool_call` / `tool_result` / `final` / `error`。

## 结构

| 文件 | 作用 |
|---|---|
| `app.py` | FastAPI、SSE、Agent 装配 |
| `model.py` | OpenAI 兼容 ModelPort（流式 + tool calls） |
| `registry.py` | 工具注册表（单一事实来源，含手册元数据） |
| `prompts.py` | 总则、操作分级、模块短提示词与路由 |
| `manual.py` | 从注册表导出手册 |
| `experience.py` | 经验条目落盘（不注入提示词） |

## 约束

- 只读：没有创建、运行、停止、删除工具。
- 写操作第二阶段接入，必须二次确认、沿用 RBAC、危险操作走审批。
- 每次工具调用由 Java 落 `platform.agent_tool_audit`，可按租户/用户追溯。
