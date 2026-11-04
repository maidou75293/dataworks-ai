"""只读工具：agent-service → Java 内部接口（/api/internal/agent/*）。

每个工具调用都会：
1. 通过 SSE 发 tool_call / tool_result 事件（前端展示过程）
2. Java 内部接口落 platform.agent_tool_audit（租户/用户可追溯）
"""
from __future__ import annotations

import json
import time
import uuid
from typing import Callable

import httpx
from enter_harness.tooling import ToolRegistry

MAX_CHARS = 6000


class BackendClient:
    def __init__(self, base_url: str, internal_token: str,
                 tenant: str, user: str, project_id: int):
        self.base_url = base_url.rstrip("/")
        self.tenant = tenant
        self.user = user
        self.project_id = project_id
        self.headers = {
            "X-Internal-Token": internal_token,
            "X-Tenant-Id": tenant,
            "X-User-Id": user,
        }

    def get(self, path: str, params: dict) -> dict:
        params = {k: v for k, v in params.items() if v not in (None, "")}
        params.setdefault("projectId", self.project_id)
        with httpx.Client(timeout=30.0) as client:
            resp = client.get(self.base_url + path, params=params, headers=self.headers)
            resp.raise_for_status()
            return resp.json()

    def post(self, path: str, body: dict) -> dict:
        """写接口只用于「生成申请单」类动作，不直接改平台数据。"""
        payload = {k: v for k, v in body.items() if v not in (None, "")}
        payload.setdefault("projectId", self.project_id)
        with httpx.Client(timeout=30.0) as client:
            resp = client.post(self.base_url + path, json=payload, headers=self.headers)
            resp.raise_for_status()
            return resp.json()


def _truncate(text: str) -> str:
    return text if len(text) <= MAX_CHARS else text[:MAX_CHARS] + "...(truncated)"


def build_tools(emit: Callable[[dict], None], client: BackendClient) -> ToolRegistry:
    tools = ToolRegistry()

    def wrap(name: str, fn):
        def handler(**kwargs):
            call_id = uuid.uuid4().hex[:10]
            started = time.time()
            emit({"type": "tool_call", "id": call_id, "name": name, "args": kwargs})
            try:
                result = fn(**kwargs)
                ms = int((time.time() - started) * 1000)
                text = _truncate(json.dumps(result, ensure_ascii=False, default=str))
                emit({"type": "tool_result", "id": call_id, "name": name, "ok": True,
                      "durationMs": ms, "summary": _summary(result)})
                return text
            except Exception as e:  # noqa: BLE001
                ms = int((time.time() - started) * 1000)
                emit({"type": "tool_result", "id": call_id, "name": name, "ok": False,
                      "durationMs": ms, "summary": f"{type(e).__name__}: {e}"})
                raise
        return handler

    tools.register(
        "list_datasources",
        wrap("list_datasources", lambda type="", keyword="": client.get(
            "/api/internal/agent/datasources", {"type": type, "keyword": keyword})),
        "列出当前项目的数据源，可按类型（如 POSTGRESQL/MYSQL）或名称关键字过滤。返回 id/name/type/jdbcUrl。",
        {"type": "object", "properties": {
            "type": {"type": "string", "description": "数据源类型，如 POSTGRESQL"},
            "keyword": {"type": "string", "description": "名称关键字"}}},
        parallel_safe=True)

    tools.register(
        "list_sync_tasks",
        wrap("list_sync_tasks", lambda keyword="", status="": client.get(
            "/api/internal/agent/tasks", {"keyword": keyword, "status": status})),
        "列出同步任务，可按名称/编码关键字和最近状态（SUCCESS/FAILED/RUNNING/PENDING）过滤。",
        {"type": "object", "properties": {
            "keyword": {"type": "string"},
            "status": {"type": "string"}}},
        parallel_safe=True)

    tools.register(
        "get_sync_task_status",
        wrap("get_sync_task_status", lambda code="", id=0: client.get(
            "/api/internal/agent/tasks/status",
            {"code": code, "id": int(id) if id else ""})),
        "按任务编码或 id 查询同步任务最近一次运行状态（平台状态 + k8s Job 实时状态）。",
        {"type": "object", "properties": {
            "code": {"type": "string"},
            "id": {"type": "integer"}}},
        parallel_safe=True)

    tools.register(
        "query_lineage",
        wrap("query_lineage", lambda table="", env="DEV", limit=50: client.get(
            "/api/internal/agent/lineage",
            {"table": table, "env": env, "limit": int(limit)})),
        "查询表/字段血缘边，可按表名过滤，env 为 DEV 或 PROD。",
        {"type": "object", "properties": {
            "table": {"type": "string"},
            "env": {"type": "string", "enum": ["DEV", "PROD"]},
            "limit": {"type": "integer"}}},
        parallel_safe=True)

    tools.register(
        "list_quality_results",
        wrap("list_quality_results", lambda table="", limit=50: client.get(
            "/api/internal/agent/quality", {"table": table, "limit": int(limit)})),
        "查询质量规则最近结果，可按表名过滤。",
        {"type": "object", "properties": {
            "table": {"type": "string"},
            "limit": {"type": "integer"}}},
        parallel_safe=True)

    return tools


def _summary(result: dict) -> str:
    if "count" in result:
        parts = [f"count={result.get('count')}"]
        for key in ("datasources", "tasks", "edges", "results"):
            if key in result and isinstance(result[key], list):
                names = []
                for row in result[key][:5]:
                    names.append(str(row.get("name") or row.get("code")
                                     or row.get("rule_code") or row.get("task_code") or ""))
                parts.append(f"{key}=" + ",".join(n for n in names if n))
        return "; ".join(parts)
    return _truncate(json.dumps(result, ensure_ascii=False, default=str))[:300]
