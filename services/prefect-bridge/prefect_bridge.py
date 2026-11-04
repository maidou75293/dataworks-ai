import asyncio
import hmac
import os
from typing import Any, Dict, List, Optional
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel
from prefect.client.orchestration import get_client
from prefect.exceptions import ObjectNotFound, PrefectHTTPStatusError
from prefect.client.schemas.actions import DeploymentScheduleCreate
from prefect.client.schemas.filters import LogFilter, LogFilterFlowRunId
from prefect.client.schemas.schedules import CronSchedule, RRuleSchedule

app = FastAPI(title="DataWorks Prefect Bridge", version="0.1")

# 共享内部凭证：bridge 能创建部署/运行、删除部署，绝不能裸奔在 NodePort 上。
# 未配置时 fail-closed（只保留 /health 供探针使用）。
INTERNAL_TOKEN = os.environ.get("PLATFORM_INTERNAL_TOKEN", "").strip()


def _check(token: Optional[str]) -> None:
    if not INTERNAL_TOKEN:
        raise HTTPException(status_code=503,
                            detail="PLATFORM_INTERNAL_TOKEN 未配置，bridge 拒绝服务")
    if not token or not hmac.compare_digest(token, INTERNAL_TOKEN):
        raise HTTPException(status_code=401, detail="invalid internal token")


class DeploymentRequest(BaseModel):
    flow_name: str
    deployment_name: str
    entrypoint: str
    work_pool: str = "platform"
    cron: Optional[str] = None
    # 一次性 / 带结束日期的调度用 RRULE 表达（DTSTART + UNTIL）；
    # 以前 bridge 只认 cron，后端传上来的 rrule 被静默丢掉，结束日期实际不生效。
    rrule: Optional[str] = None
    tags: List[str] = []
    description: Optional[str] = None


class RunRequest(BaseModel):
    parameters: Dict[str, Any] = {}


def _state(run) -> Optional[str]:
    return run.state.type.value if run.state is not None else None


def _state_message(run) -> Optional[str]:
    # Prefect 把异常摘要放在 state.message 里，失败时是用户能看懂的具体原因
    # （例如 UndefinedTable: relation "x" does not exist），平台落库后直接展示。
    if run.state is None:
        return None
    return getattr(run.state, "message", None)


# Flow 文件由 ConfigMap 卷挂载；bridge 与 worker 在同一节点、挂同一个卷，
# 所以这里能看到 worker 即将看到的文件，可作为「发布后是否可触发」的就绪探测。
FLOWS_DIR = os.getenv("FLOWS_DIR", "/opt/prefect/flows")


@app.get("/flows/{filename}")
async def flow_status(filename: str,
                      x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    if "/" in filename or filename.startswith("."):
        raise HTTPException(status_code=400, detail="invalid filename")
    return {"filename": filename, "ready": os.path.isfile(os.path.join(FLOWS_DIR, filename))}


@app.get("/health")
async def health():
    # Prefect 3 的 api_healthcheck() 在本环境恒返回 False，改成一次轻量读调用。
    async with get_client() as client:
        try:
            await client.read_work_pools(limit=1)
            return {"status": "ok"}
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"prefect unavailable: {exc}")


@app.post("/deployments")
async def create_deployment(req: DeploymentRequest,
                            x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        flow_id = await client.create_flow_from_name(req.flow_name)
        schedules = None
        if req.rrule:
            # RRULE 能表达一次性（COUNT=1）和截止日期（UNTIL），优先于 cron
            schedules = [DeploymentScheduleCreate(
                schedule=RRuleSchedule(rrule=req.rrule, timezone="Asia/Shanghai"),
                active=True,
            )]
        elif req.cron:
            schedules = [DeploymentScheduleCreate(
                schedule=CronSchedule(cron=req.cron, timezone="Asia/Shanghai"),
                active=True,
            )]
        deployment_id = await client.create_deployment(
            flow_id=flow_id,
            name=req.deployment_name,
            entrypoint=req.entrypoint,
            work_pool_name=req.work_pool,
            schedules=schedules,
            tags=req.tags or [],
            description=req.description,
        )
        return {
            "flow_id": str(flow_id),
            "deployment_id": str(deployment_id),
            "flow_name": req.flow_name,
            "deployment_name": req.deployment_name,
        }


@app.delete("/deployments/{deployment_id}")
async def delete_deployment(deployment_id: str,
                            x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        try:
            await client.delete_deployment(deployment_id)
        except ObjectNotFound:
            # Prefect 3 的 delete_deployment 把 404 包成 ObjectNotFound
            return {"deleted": deployment_id, "already": True}
        except PrefectHTTPStatusError as exc:
            # 删除必须幂等：同一个部署 id 可能被版本表/部署表各引用一次，
            # 第二次删除 Prefect 返回 404，不能变成 500 再被后端翻成 502。
            if exc.response is not None and exc.response.status_code == 404:
                return {"deleted": deployment_id, "already": True}
            raise HTTPException(status_code=502,
                                detail=f"prefect delete failed: {exc}") from exc
        return {"deleted": deployment_id}


@app.post("/deployments/{deployment_id}/runs")
async def create_run(deployment_id: str, req: RunRequest,
                     x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        run = await client.create_flow_run_from_deployment(
            deployment_id, parameters=req.parameters or {}
        )
        return {"flow_run_id": str(run.id), "name": run.name, "state": _state(run),
                "state_message": _state_message(run)}


@app.get("/runs/{run_id}")
async def get_run(run_id: str,
                  x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        run = await client.read_flow_run(run_id)
        return {
            "flow_run_id": str(run.id),
            "name": run.name,
            "state": _state(run),
            "state_message": _state_message(run),
            "deployment_id": str(run.deployment_id) if run.deployment_id else None,
            "parameters": run.parameters,
            "start_time": run.start_time.isoformat() if run.start_time else None,
            "end_time": run.end_time.isoformat() if run.end_time else None,
        }


@app.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: str,
                     x_internal_token: Optional[str] = Header(default=None)):
    """取消正在运行/排队的 Flow Run（v450 P1-8）。

    Prefect 3 用 Cancelling 状态：worker 收到后终止进程，最终收敛为 Cancelled。
    """
    _check(x_internal_token)
    from prefect.states import Cancelling
    async with get_client() as client:
        try:
            await client.set_flow_run_state(UUID(run_id), state=Cancelling())
        except ObjectNotFound:
            raise HTTPException(status_code=404, detail="FLOW_RUN_NOT_FOUND")
        run = await client.read_flow_run(UUID(run_id))
        return {"flow_run_id": str(run.id), "state": _state(run), "cancelling": True}


@app.get("/runs/{run_id}/tasks")
async def list_tasks(run_id: str,
                     x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        task_runs = await client.read_task_runs(limit=200)
        matched = [t for t in task_runs if str(t.flow_run_id) == run_id]
        matched.sort(key=lambda t: t.start_time.isoformat() if t.start_time else "")
        return {
            "tasks": [
                {
                    "id": str(t.id),
                    "name": t.name,
                    "task_key": t.task_key,
                    "state": _state(t),
                    "state_message": _state_message(t),
                    "start_time": t.start_time.isoformat() if t.start_time else None,
                    "end_time": t.end_time.isoformat() if t.end_time else None,
                }
                for t in matched
            ]
        }


@app.get("/runs/{run_id}/logs")
async def list_logs(run_id: str, task_run_id: Optional[str] = None, limit: int = 200,
                    x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        # 服务端按 flow_run_id 过滤：以前先取全局最新 200 条再筛，跑得多时
        # 目标 run 的日志早就被挤出窗口，崩溃实例连失败日志都看不到。
        logs = await client.read_logs(
            log_filter=LogFilter(flow_run_id=LogFilterFlowRunId(any_=[UUID(run_id)])),
            limit=min(max(1, limit), 200),
        )
        matched = [l for l in logs if str(getattr(l, "flow_run_id", None)) == run_id]
        if task_run_id:
            matched = [l for l in matched if str(getattr(l, "task_run_id", None)) == task_run_id]
        matched.sort(key=lambda l: l.timestamp.isoformat() if l.timestamp else "")
        return {
            "logs": [
                {
                    "timestamp": l.timestamp.isoformat() if l.timestamp else None,
                    "level": l.level,
                    "message": l.message,
                    "task_run_id": str(l.task_run_id) if getattr(l, "task_run_id", None) else None,
                }
                for l in matched
            ]
        }


@app.get("/deployments/{deployment_id}/runs")
async def list_runs(deployment_id: str, limit: int = 20,
                    x_internal_token: Optional[str] = Header(default=None)):
    _check(x_internal_token)
    async with get_client() as client:
        runs = await client.read_flow_runs(limit=200)
        matched = [r for r in runs if str(r.deployment_id) == deployment_id]
        matched.sort(key=lambda r: r.created, reverse=True)
        return {
            "runs": [
                {
                    "flow_run_id": str(r.id),
                    "name": r.name,
                    "state": _state(r),
                    "state_message": _state_message(r),
                    "parameters": r.parameters,
                    "start_time": r.start_time.isoformat() if r.start_time else None,
                    "end_time": r.end_time.isoformat() if r.end_time else None,
                }
                for r in matched[:limit]
            ]
        }
