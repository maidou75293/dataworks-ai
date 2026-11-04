"""只读工具注册表（单一事实来源）。

工具定义同时用于：
- enter_harness ToolRegistry（name/description/JSON Schema）
- 操作手册导出（manual.py）：模块 → 条目，条目与工具一一对应
- 模块路由（prompts.py）：按模块加载短提示词
"""
from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from typing import Callable

import httpx
from enter_harness.tooling import ToolRegistry

MAX_CHARS = 6000

MODULES = {
    "datasource": "数据源",
    "sync": "同步任务",
    "lineage": "血缘",
    "quality": "质量",
    "catalog": "目录",
}


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

    def get(self, path: str, params: dict | None = None) -> dict:
        params = {k: v for k, v in (params or {}).items() if v not in (None, "")}
        params.setdefault("projectId", self.project_id)
        with httpx.Client(timeout=30.0) as client:
            resp = client.get(self.base_url + path, params=params, headers=self.headers)
            resp.raise_for_status()
            return resp.json()

    def post(self, path: str, body: dict | None = None) -> dict:
        with httpx.Client(timeout=30.0) as client:
            resp = client.post(self.base_url + path, json=body or {}, headers=self.headers)
            resp.raise_for_status()
            return resp.json() if resp.content else {}

    def put(self, path: str, body: dict | None = None) -> dict:
        with httpx.Client(timeout=30.0) as client:
            resp = client.put(self.base_url + path, json=body or {}, headers=self.headers)
            resp.raise_for_status()
            return resp.json() if resp.content else {}

    def delete(self, path: str, params: dict | None = None) -> dict:
        with httpx.Client(timeout=30.0) as client:
            resp = client.delete(self.base_url + path, params=params or {}, headers=self.headers)
            resp.raise_for_status()
            return resp.json() if resp.content else {}


@dataclass
class ToolSpec:
    id: str
    module: str
    tool: str
    purpose: str
    preconditions: list[str]
    parameters: list[dict]
    steps: list[str]
    returns: str
    errors: list[str]
    example: str
    path: str
    handler: Callable
    parallel_safe: bool = True

    def schema(self) -> dict:
        props = {}
        for p in self.parameters:
            item = {"type": p["type"], "description": p["description"]}
            if p.get("enum"):
                item["enum"] = p["enum"]
            props[p["name"]] = item
        return {"type": "object", "properties": props}

    def description(self) -> str:
        params = "，".join(
            f"{p['name']}({p['type']}{'必填' if p['required'] else '可选'})：{p['description']}"
            for p in self.parameters) or "无参数"
        pre = "；".join(self.preconditions) or "无"
        return f"{self.purpose}。前置：{pre}。参数：{params}。返回：{self.returns}"

    def manual_entry(self) -> dict:
        return {
            "id": self.id,
            "module": self.module,
            "tool": self.tool,
            "purpose": self.purpose,
            "preconditions": self.preconditions,
            "parameters": self.parameters,
            "steps": self.steps,
            "returns": self.returns,
            "errors": self.errors,
            "example": self.example,
        }


def _specs(client: BackendClient) -> list[ToolSpec]:
    common_pre = ["已配置 PLATFORM_INTERNAL_TOKEN", "调用方携带租户与用户"]
    return [
        ToolSpec(
            id="datasource.list", module="datasource", tool="list_datasources",
            purpose="列出当前项目的数据源，可按类型或名称过滤",
            preconditions=common_pre + ["项目内已有数据源"],
            parameters=[
                {"name": "type", "type": "string", "required": False,
                 "description": "数据源类型，如 POSTGRESQL、MYSQL"},
                {"name": "keyword", "type": "string", "required": False,
                 "description": "名称关键字"},
            ],
            steps=["调用 /api/internal/agent/datasources", "按 type/keyword 过滤", "返回 id/name/type/jdbcUrl"],
            returns="count 与 datasources 数组",
            errors=["401 内部凭证无效", "缺少 X-User-Id 时 400"],
            example="列出所有 PostgreSQL 数据源",
            path="/api/internal/agent/datasources",
            handler=lambda type="", keyword="": client.get(
                "/api/internal/agent/datasources", {"type": type, "keyword": keyword}),
        ),
        ToolSpec(
            id="sync.list", module="sync", tool="list_sync_tasks",
            purpose="按最近运行时间列出同步任务及平台记录状态（实时 Job 状态需再用 sync.status）",
            preconditions=common_pre + ["项目内已有同步任务"],
            parameters=[
                {"name": "keyword", "type": "string", "required": False,
                 "description": "任务名称或编码关键字"},
                {"name": "status", "type": "string", "required": False,
                 "description": "最近状态：SUCCESS/FAILED/RUNNING/PENDING"},
            ],
            steps=["调用 /api/internal/agent/tasks", "按关键字/状态过滤", "最多返回 50 条"],
            returns="count 与 tasks 数组（含 lastStatus/lastRunAt）",
            errors=["401 内部凭证无效"],
            example="列出最近的同步任务",
            path="/api/internal/agent/tasks",
            handler=lambda keyword="", status="": client.get(
                "/api/internal/agent/tasks", {"keyword": keyword, "status": status}),
        ),
        ToolSpec(
            id="sync.status", module="sync", tool="get_sync_task_status",
            purpose="按编码或 id 查询同步任务最近一次运行状态",
            preconditions=common_pre + ["任务已存在"],
            parameters=[
                {"name": "code", "type": "string", "required": False,
                 "description": "任务编码；与 id 二选一"},
                {"name": "id", "type": "integer", "required": False,
                 "description": "任务 id；与 code 二选一"},
            ],
            steps=["二选一定位任务", "读取 last_status/last_run_at", "补 k8s Job 实时状态"],
            returns="任务基础信息与 jobLiveStatus",
            errors=["404 同步任务不存在", "400 未给 code/id"],
            example="查 inc_pg 最近一次同步状态",
            path="/api/internal/agent/tasks/status",
            handler=lambda code="", id=0: client.get(
                "/api/internal/agent/tasks/status",
                {"code": code, "id": int(id) if id else ""}),
        ),
        ToolSpec(
            id="lineage.query", module="lineage", tool="query_lineage",
            purpose="查询表/字段血缘边",
            preconditions=common_pre + ["已有发布期或运行期血缘"],
            parameters=[
                {"name": "table", "type": "string", "required": False,
                 "description": "表名关键字"},
                {"name": "env", "type": "string", "required": False,
                 "description": "环境", "enum": ["DEV", "PROD"]},
                {"name": "limit", "type": "integer", "required": False,
                 "description": "最多返回条数，默认 50"},
            ],
            steps=["调用 /api/internal/agent/lineage", "按表名模糊匹配", "返回边列表"],
            returns="count 与 edges 数组",
            errors=["401 内部凭证无效"],
            example="查 sync_test 相关血缘",
            path="/api/internal/agent/lineage",
            handler=lambda table="", env="DEV", limit=50: client.get(
                "/api/internal/agent/lineage",
                {"table": table, "env": env, "limit": int(limit)}),
        ),
        ToolSpec(
            id="quality.list", module="quality", tool="list_quality_results",
            purpose="查询质量规则最近结果",
            preconditions=common_pre + ["已配置质量规则并运行过"],
            parameters=[
                {"name": "table", "type": "string", "required": False,
                 "description": "表名关键字"},
                {"name": "limit", "type": "integer", "required": False,
                 "description": "最多返回条数，默认 50"},
            ],
            steps=["调用 /api/internal/agent/quality", "按表名过滤", "返回结果"],
            returns="count 与 results 数组（passed/detail）",
            errors=["401 内部凭证无效"],
            example="最近的质检失败项",
            path="/api/internal/agent/quality",
            handler=lambda table="", limit=50: client.get(
                "/api/internal/agent/quality", {"table": table, "limit": int(limit)}),
        ),
        ToolSpec(
            id="catalog.list", module="catalog", tool="list_catalog_tables",
            purpose="列出目录中已出现的表（来自血缘边）",
            preconditions=common_pre + ["已有血缘数据"],
            parameters=[
                {"name": "keyword", "type": "string", "required": False,
                 "description": "表名关键字"},
                {"name": "limit", "type": "integer", "required": False,
                 "description": "最多返回条数，默认 100"},
            ],
            steps=["调用 /api/internal/agent/catalog", "去重 source/target 表", "按关键字过滤"],
            returns="count 与 tables 数组",
            errors=["401 内部凭证无效"],
            example="目录里有哪些表",
            path="/api/internal/agent/catalog",
            handler=lambda keyword="", limit=100: client.get(
                "/api/internal/agent/catalog", {"keyword": keyword, "limit": int(limit)}),
        ),
        ToolSpec(
            id="platform.request_change", module="sync", tool="request_change",
            purpose="为删除/改配置/权限/调度/表结构变更生成审批单（不执行）",
            preconditions=common_pre + ["用户已明确要求做该变更"],
            parameters=[
                {"name": "type", "type": "string", "required": True,
                 "description": "DELETE_TASK/DELETE_DATASOURCE/UPDATE_CONFIG/"
                                "UPDATE_PERMISSION/UPDATE_SCHEDULE/ALTER_TABLE"},
                {"name": "targetId", "type": "integer", "required": False,
                 "description": "对象 id（删除任务/数据源时必填）"},
                {"name": "targetName", "type": "string", "required": False,
                 "description": "对象名称"},
                {"name": "reason", "type": "string", "required": True,
                 "description": "变更原因"},
                {"name": "impact", "type": "string", "required": False,
                 "description": "影响范围"},
                {"name": "rollback", "type": "string", "required": False,
                 "description": "回滚方式"},
            ],
            steps=["确认用户已明确要求", "调用 /api/internal/agent/write/change-requests",
                   "返回申请 id 与 PENDING_APPROVAL"],
            returns="ok/id/status/message",
            errors=["400 类型不支持", "404 申请对象不存在"],
            example="申请删除同步任务 12",
            path="/api/internal/agent/write/change-requests",
            handler=lambda type, reason, targetId=0, targetName="", impact="", rollback="":
            client.post("/api/internal/agent/write/change-requests", {
                "type": type, "reason": reason,
                "targetId": int(targetId) if targetId else None,
                "targetName": targetName, "impact": impact, "rollback": rollback,
            }),
        ),
        ToolSpec(
            id="platform.request_access", module="datasource", tool="request_access",
            purpose="代用户提交库/表的读取或写入权限申请（等待 owner 审批）",
            preconditions=common_pre + ["用户明确说了要申请哪个库/表、读还是写"],
            parameters=[
                {"name": "resourceType", "type": "string", "required": True,
                 "description": "DATABASE 或 TABLE"},
                {"name": "catalog", "type": "string", "required": True,
                 "description": "catalog，如 PG 或 DORIS"},
                {"name": "schemaName", "type": "string", "required": True,
                 "description": "库名"},
                {"name": "tableName", "type": "string", "required": False,
                 "description": "表名（resourceType=TABLE 时必填）"},
                {"name": "permission", "type": "string", "required": True,
                 "description": "READ 或 WRITE"},
                {"name": "reason", "type": "string", "required": False,
                 "description": "申请理由"},
            ],
            steps=["确认用户明确要求", "调用 /api/internal/agent/access-requests",
                   "返回申请 id 与 PENDING"],
            returns="ok/id/status/message",
            errors=["404 资源未纳管", "400 参数不合法"],
            example="帮我申请 tenant_b.orders 的读取权限",
            path="/api/internal/agent/access-requests",
            handler=lambda resourceType, catalog, schemaName, permission,
            tableName="", reason="":
            client.post("/api/internal/agent/access-requests", {
                "resourceType": resourceType, "catalog": catalog,
                "schemaName": schemaName, "tableName": tableName,
                "permission": permission, "reason": reason,
            }),
        ),
    ]


# 进程内由 build_tools 填充，供 manual 导出使用（无请求上下文时的静态规格）
TOOL_SPECS: dict[str, ToolSpec] = {}


def build_tools(emit: Callable[[dict], None], client: BackendClient,
                experience: Callable[[dict], None] | None = None,
                allow: set[str] | None = None) -> ToolRegistry:
    """allow=None 表示不限制；否则只注册 allow 里的工具名（DELEGATE 模式）。

    过滤只影响「模型能看到哪些工具」，真正的判定在 Java 内部接口——
    两处都做，是因为只靠服务端拒绝会让模型反复尝试同一个被拒的工具。
    """
    tools = ToolRegistry()
    specs = _specs(client)
    TOOL_SPECS.update({s.id: s for s in specs})
    by_tool = {s.tool: s for s in specs}
    if allow is not None:
        specs = [s for s in specs if s.tool in allow]

    for spec in specs:
        def make(s: ToolSpec):
            def handler(**kwargs):
                call_id = uuid.uuid4().hex[:10]
                started = time.time()
                emit({"type": "tool_call", "id": call_id, "name": s.tool,
                      "toolId": s.id, "module": s.module, "args": kwargs})
                try:
                    result = s.handler(**kwargs)
                    ms = int((time.time() - started) * 1000)
                    text = json.dumps(result, ensure_ascii=False, default=str)
                    if len(text) > MAX_CHARS:
                        text = text[:MAX_CHARS] + "...(truncated)"
                    emit({"type": "tool_result", "id": call_id, "name": s.tool,
                          "toolId": s.id, "module": s.module, "ok": True,
                          "durationMs": ms, "summary": _summary(result)})
                    if experience:
                        experience({"tool": s.id, "module": s.module, "ok": True,
                                    "durationMs": ms})
                    return text
                except Exception as e:  # noqa: BLE001
                    ms = int((time.time() - started) * 1000)
                    emit({"type": "tool_result", "id": call_id, "name": s.tool,
                          "toolId": s.id, "module": s.module, "ok": False,
                          "durationMs": ms, "summary": f"{type(e).__name__}: {e}"})
                    if experience:
                        experience({"tool": s.id, "module": s.module, "ok": False,
                                    "durationMs": ms, "error": f"{type(e).__name__}: {e}"})
                    raise
            return handler

        tools.register(spec.tool, make(spec), spec.description(), spec.schema(),
                       parallel_safe=spec.parallel_safe)

    return tools, by_tool


def _summary(result: dict) -> str:
    if "count" in result:
        parts = [f"count={result.get('count')}"]
        for key in ("datasources", "tasks", "edges", "results", "tables"):
            if isinstance(result.get(key), list):
                names = []
                for row in result[key][:5]:
                    names.append(str(row.get("name") or row.get("code")
                                     or row.get("rule_code") or row.get("task_code") or ""))
                parts.append(f"{key}=" + ",".join(n for n in names if n))
        return "; ".join(parts)
    return json.dumps(result, ensure_ascii=False, default=str)[:300]
