"""dataworks agent-service：agent-core 的独立 HTTP 进程（只读工具）。

启动：
  uvicorn app:app --host 0.0.0.0 --port 8097
环境变量：
  AGENT_MODEL_BASE_URL / AGENT_MODEL_API_KEY / AGENT_MODEL_NAME
  BACKEND_URL / PLATFORM_INTERNAL_TOKEN / AGENT_SERVICE_TOKEN
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import queue
import threading
import uuid
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from enter_harness import Agent, AgentConfig, InMemoryStore, RunRequest, Scope
from enter_harness.errors import CancelledError
from enter_harness.llm import create_model
import agentconfig
import experience
import harness_ports
import memory
import slots
from manual import build_manual
from prompts import system_prompt
from registry import BackendClient, build_tools

BACKEND_URL = os.environ.get("BACKEND_URL", "http://127.0.0.1:30090")
INTERNAL_TOKEN = os.environ.get("PLATFORM_INTERNAL_TOKEN", "").strip()
# .env.example 里 AGENT_SERVICE_TOKEN= 是「存在但为空」，get 的默认值不会生效。
# 显式 or 回退；两者都为空时 fail-closed，避免 8097 端口裸奔（本地调试可显式放行）。
SERVICE_TOKEN = (os.environ.get("AGENT_SERVICE_TOKEN") or "").strip() or INTERNAL_TOKEN
if not SERVICE_TOKEN and os.environ.get("AGENT_ALLOW_ANONYMOUS") != "1":
    raise RuntimeError(
        "AGENT_SERVICE_TOKEN / PLATFORM_INTERNAL_TOKEN 未配置：agent-service 拒绝启动。"
        "本地调试可设置 AGENT_ALLOW_ANONYMOUS=1。")
# 模型侧配置收口到 agentconfig（REQ-AGENT-CFGPANEL-1）：chat、健康灯、系统配置页
# 读同一份 current()。旧版把三行 env 拍成模块常量，页面想改必须重启进程——
# 「改配置不出页面」做不到的根因就在这里。
agentconfig.configure()


app = FastAPI(title="dataworks-agent-service", version="0.1.0")


# 宿主主动中止统一用内核的 CancelledError：模型层不会把它包成 ModelError，
# RunResult.status 会是 "cancelled"，宿主据此跳过落库。

# Harness 进程级端口：装配检查失败直接抛，服务不启动（缺端口必须可见）
HARNESS = harness_ports.init_runtime()
log = logging.getLogger("agent.app")


class ChatIn(BaseModel):
    message: str
    sessionId: str = ""
    history: list[dict[str, Any]] = []
    projectId: int = 1
    tenantId: str = "default"
    userId: str = ""
    # 助手权限模式：SYSTEM=平台全权（测试用）；DELEGATE=只注册使用者有权限的工具
    identityMode: str = "SYSTEM"
    allowedTools: list[str] = []


def sse(payload: dict) -> str:
    return "data: " + json.dumps(payload, ensure_ascii=False, default=str) + "\n\n"


@app.get("/api/manual")
def manual(x_agent_token: str = Header(default="")):
    if SERVICE_TOKEN and x_agent_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="invalid agent token")
    return build_manual()


@app.get("/api/manual/{module}")
def manual_module(module: str, x_agent_token: str = Header(default="")):
    if SERVICE_TOKEN and x_agent_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="invalid agent token")
    data = build_manual()
    for item in data["modules"]:
        if item["id"] == module:
            return item
    raise HTTPException(status_code=404, detail="module not found")


@app.get("/api/health")
def health():
    # 存活探针保持轻量（Java 代理侧 3s 超时）；网络级健康灯在 /api/config，
    # 这里只顺带报「密钥配没配」——BUG-AGENT-KEY-FIX-1 的教训：ok=true 不等于能用。
    cfg = agentconfig.current()
    return {"ok": True, "service": "dataworks-agent", "model": cfg["modelName"],
            "keyConfigured": bool(cfg["apiKey"]),
            "harness": harness_ports.health(HARNESS)}


class ConfigIn(BaseModel):
    # null/省略 = 不改；apiKey 传 "" = 显式清除（脱敏语义见 agentconfig 模块头）
    baseUrl: str | None = None
    modelName: str | None = None
    apiKey: str | None = None


def _require_agent_token(x_agent_token: str) -> None:
    if SERVICE_TOKEN and x_agent_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="invalid agent token")


@app.get("/api/config")
def get_config(x_agent_token: str = Header(default="")):
    """系统配置页「对话智能体」区块的同源读数：端点/模型名明文，密钥只出指纹。

    snapshot() 是脱敏唯一收口点——判据脚本断言整个 JSON 不含真实 key 子串。
    """
    _require_agent_token(x_agent_token)
    return agentconfig.snapshot()


@app.post("/api/config")
def post_config(body: ConfigIn, x_agent_token: str = Header(default="")):
    """改配置不出页面：进程内立即生效 + 写 .env 持久化（免重启、免登节点）。"""
    _require_agent_token(x_agent_token)
    try:
        result = agentconfig.set_values(base_url=body.baseUrl,
                                        model_name=body.modelName,
                                        api_key=body.apiKey)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    snap = agentconfig.snapshot(force_probe=True)
    snap.update({"changed": result["changed"], "persisted": result["persisted"],
                 "persistError": result["persistError"], "before": result["before"]})
    if result["persistError"]:
        # 没落盘必须看得见：否则重启后回到旧配置，页面读数就成了假绿
        snap["warning"] = "已生效但未持久化：" + result["persistError"]
    return snap


@app.post("/api/chat")
def chat(body: ChatIn, x_agent_token: str = Header(default="")):
    if SERVICE_TOKEN and x_agent_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="invalid agent token")
    if not body.message.strip():
        raise HTTPException(status_code=400, detail="message 不能为空")
    if not body.userId:
        raise HTTPException(status_code=400, detail="userId 不能为空（审计需要）")

    session_id = body.sessionId or ("s_" + uuid.uuid4().hex[:12])
    # 首个请求的 sessionId 为空；把服务端生成的 id 回填，槽位状态按同一 id 存取。
    body.sessionId = session_id
    events: queue.Queue = queue.Queue()
    stop = threading.Event()          # 客户端断开后置位，模型回调据此中止
    state = {"done": False, "aborted": False}

    def emit(event: dict) -> None:
        events.put(event)

    def on_delta(text: str) -> None:
        if stop.is_set():
            # 抛出后内核 provider 的流循环退出，后台异步生成器被关闭（断连回收）
            raise CancelledError("客户端已断开")
        emit({"type": "delta", "text": text})

    def mark_aborted() -> None:
        if not state["aborted"] and not state["done"]:
            state["aborted"] = True
            harness_ports.record_abort()

    # 模型走内核 provider：on_delta 在同步执行器里逐块回调（内核内部桥接 async 流），
    # 失败重试、usage、tool_calls 聚合都由内核负责，宿主不再手写 SSE 解析。
    # 每次对话现取 agentconfig.current()：与健康灯同源，页面改完不必重启。
    cfg = agentconfig.current()
    model = create_model(
        "openai",
        on_delta=on_delta,
        base_url=cfg["baseUrl"], api_key=cfg["apiKey"], name=cfg["modelName"],
        temperature=0.2, timeout=120,
    )
    client = BackendClient(BACKEND_URL, INTERNAL_TOKEN, body.tenantId,
                           body.userId, body.projectId)

    def note(item: dict) -> None:
        experience.record({"tenantId": body.tenantId, "userId": body.userId,
                           "sessionId": session_id, **item})

    # 捕获本轮最终回答与工具摘要，结束后落库；事件照常推给前端
    captured = {"text": ""}
    tool_calls: list[dict] = []

    def emit_capture(event: dict) -> None:
        etype = event.get("type")
        if etype == "delta":
            captured["text"] += event.get("text") or ""
        elif etype == "final":
            text = event.get("text")
            if text is not None:
                captured["text"] = str(text)
        elif etype == "tool_call":
            tool_calls.append({
                "id": event.get("id"), "name": event.get("name"),
                "args": event.get("args"), "ok": None,
            })
        elif etype == "tool_result":
            for item in tool_calls:
                if item.get("id") == event.get("id"):
                    item["ok"] = event.get("ok")
                    item["summary"] = event.get("summary")
                    item["durationMs"] = event.get("durationMs")
        emit(event)

    allow = None
    if (body.identityMode or "").upper() == "DELEGATE":
        allow = set(body.allowedTools)
    tools, _ = build_tools(emit_capture, client, experience=note, allow=allow)

    def run() -> None:
        agent = None
        try:
            emit({"type": "start", "sessionId": session_id,
                  "tenantId": body.tenantId, "userId": body.userId})
            # 会话落库：先取历史，再把本轮用户消息写进去
            memory.ensure_conversation(client, session_id, body.projectId, body.message)
            history, summary = memory.load_context(client, session_id)
            memory.append_message(client, session_id, "user", body.message)

            # 「记住 / 忘记 / 我的记忆」是确定性指令，不经过模型
            memo_reply = memory.handle_memory_command(client, body.message, body.projectId)
            if memo_reply:
                emit_capture({"type": "delta", "text": memo_reply})
                emit_capture({"type": "final", "status": "success", "text": memo_reply})
                memory.append_message(client, session_id, "assistant", memo_reply,
                                      status="done")
                memory.maybe_summarize(client, session_id)
                return

            # M2：建任务对话走槽位收集，完成后确认再执行；其余请求交给 LLM。
            if slots.try_handle(body, emit_capture, client):
                if captured["text"]:
                    memory.append_message(client, session_id, "assistant",
                                          captured["text"], tool_calls or None, "done")
                memory.maybe_summarize(client, session_id)
                return

            # Harness 拼装：鉴权 → 排序（MUST/IS/WANT/NOW）→ 预算 → trace。
            # 组装顺序：系统指令 → 制度 → 事实/知识/身份 → 偏好 → 摘要。
            # 拼装失败不再回退旧路径：直接走异常分支报错并落一条失败消息。
            # 旧 v301 手工拼装已删除（2026-10-23）——两套拼装并存会让「少注入」
            # 变成静默降级，回归也测不出到底走的哪一套。
            bundle = harness_ports.build_bundle(
                HARNESS, client, body.projectId, body.tenantId,
                body.userId, tools)
            assembled, harness_info, _ident = harness_ports.assemble_context(
                bundle, question=body.message, summary=summary)
            if harness_info.get("dropped"):
                # 预算/鉴权丢弃必须可见，不能悄悄少注入
                log.warning("harness dropped=%s used=%s",
                            harness_info["dropped"], harness_info["used_chars"])
            harness_sources = list(harness_info.get("sources") or [])
            harness_ports.record_run(harness_info)
            # DELEGATE 模式：工具已被按权限过滤，提示词说明「缺权限该怎么办」，
            # 避免模型把「没有这个工具」解释成系统故障。
            mode_note = "" if allow is None else (
                "当前平台把助手设为「跟随使用者权限」：你只能调用已提供的工具；"
                "若用户要查看或操作其没有权限的内容，如实说明并建议走「权限申请」，"
                "不要反复重试同一个动作。")
            extra_system = "\n\n".join(part for part in (
                system_prompt(body.message), assembled, mode_note) if part)
            # 使用计数在拼装之后更新，避免「读一次就改排序」（排序稳定）
            memory.record_usage_ids(client, bundle.context.picked_ids)
            agent = Agent(AgentConfig(
                scope=Scope(body.tenantId, "dataworks", body.userId, ("user",)),
                model=model,
                store=InMemoryStore(),
                tools=tools,
                tool_whitelist=["list_datasources", "list_sync_tasks",
                                "get_sync_task_status", "query_lineage",
                                "list_quality_results",
                                "request_change", "request_access"],
                executor="react",
                # 动作走宿主端口：清单/可见性/审批/执行都在 ActionPort 里（host 模式）
                action_port=bundle.action,
                # Harness 运行时端口：审计哈希链 / 检查点 / 审批队列 / 验收
                audit=HARNESS.audit,
                state_port=HARNESS.state,
                approval_queue=HARNESS.approval,
                verify_port=HARNESS.verify,
                metadata={"extra_system": extra_system},
            ))
            result = agent.run(RunRequest(
                objective=body.message,
                # 历史来自平台库，刷新/重启后仍然连续；前端传来的 history 只作兜底
                metadata={"history": history or body.history or []},
            ))
            if result.status == "cancelled":
                harness_ports.record_cancelled()
            if stop.is_set() or result.status == "cancelled":
                # 断连发生在模型返回前后：不再落库、不再推事件
                raise CancelledError("客户端已断开")
            final_text = result.answer if isinstance(result.answer, str) \
                else json.dumps(result.answer, ensure_ascii=False, default=str)
            usage = getattr(result, "usage", None)
            harness_ports.record_tokens(getattr(usage, "input_tokens", 0),
                                        getattr(usage, "output_tokens", 0))
            # 引用复核：回答里的 [来源: 标题#指纹] 必须来自本轮注入的来源（只判定，不改回答）
            if harness_sources:
                check = harness_ports.verify_citations(
                    bundle, final_text, harness_sources)
                if check["unknown"] or check["mismatch"]:
                    log.warning("citation check failed: %s", check)
            emit_capture({"type": "final", "sessionId": session_id,
                          "status": result.status, "text": final_text})
            memory.append_message(client, session_id, "assistant", final_text,
                                  tool_calls or None, "done")
            memory.maybe_summarize(client, session_id)
        except CancelledError:
            # 客户端已断开：不再推事件、不落助手消息，模型流由内核关闭
            mark_aborted()
            log.info("client disconnected, aborted session=%s", session_id)
        except Exception as e:  # noqa: BLE001
            if stop.is_set():
                # 客户端已断开时，任何异常都不是「回答失败」：不推事件、不落「调用失败」，
                # 否则断连会被记成一条助手错误消息（内核文档 §4.3 的判据就不成立了）。
                mark_aborted()
                log.info("client disconnected during run, aborted session=%s err=%s",
                         session_id, type(e).__name__)
                return
            # try_handle/Agent 构造也在 try 内：任何异常都必须发 error，
            # 并在 finally 放结束哨兵，否则前端 SSE 会永久等待。
            message = f"{type(e).__name__}: {e}"
            emit({"type": "error", "message": message})
            memory.append_message(client, session_id, "assistant",
                                  f"调用失败：{message}", status="error")
        finally:
            state["done"] = True
            if agent is not None:
                try:
                    agent.close()
                except Exception:  # noqa: BLE001
                    pass
            events.put(None)

    threading.Thread(target=run, daemon=True).start()

    async def stream():
        try:
            while True:
                item = await asyncio.to_thread(events.get)
                if item is None:
                    break
                yield sse(item)
        finally:
            # 浏览器断开 → 置停止位，模型回调在下一块抛 CancelledError，内核关闭上游流
            stop.set()
            mark_aborted()
            events.put(None)

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache",
                                      "X-Accel-Buffering": "no"})
