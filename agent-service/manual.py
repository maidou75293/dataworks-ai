"""操作手册：从工具注册表自动导出，不手写。

结构：模块 → 条目；条目与工具一一对应，固定字段：
id / 用途 / 前置条件 / 参数表 / 步骤 / 返回 / 常见错误 / 示例。

用法：
  python manual.py export [输出目录]
默认写 docs/agent-manual.json 与 docs/agent-manual.md。
"""
from __future__ import annotations

import glob
import json
import os
import sys
from datetime import date

from registry import MODULES, TOOL_SPECS, BackendClient, build_tools

EXTRA_MODULES = {"data_service": "数据服务", "governance": "变更申请"}
MCP_IDS = {
    "platform.create_task": "sync.create",
    "platform.run_task": "sync.run",
    "platform.stop_task": "sync.stop",
    "platform.request_change": "governance.request_change",
    "data.query_metric": "data.query_metric",
    "data.query_time": "data.query_time",
    "data.query_page": "data.query_page",
}
MCP_MODULE = {
    "platform.create_task": "sync",
    "platform.run_task": "sync",
    "platform.stop_task": "sync",
    "platform.request_change": "governance",
    "data.query_metric": "data_service",
    "data.query_time": "data_service",
    "data.query_page": "data_service",
}
MCP_STEPS = {
    "platform.create_task": ["校验源/目标/映射/运行/验收五个槽位", "向用户展示影响与回滚",
                             "用户确认后带 confirm=true 调用", "记录返回的任务 id"],
    "platform.run_task": ["确认任务存在且用户已知影响", "带 confirm=true 调用", "记录作业名"],
    "platform.stop_task": ["确认作业名", "带 confirm=true 调用", "确认 Job 已删除"],
    "platform.request_change": ["收集对象、原因、影响、回滚", "生成申请单",
                                "等待人工审批；本工具不执行删除或修改"],
    "data.query_metric": ["确认服务已发布且指标参数匹配", "调用并读取 rows/rowCount"],
    "data.query_time": ["确认服务已发布且时间参数匹配", "调用并读取 rows/rowCount"],
    "data.query_page": ["确认服务已发布", "传入 page/pageSize", "读取 rows/totalRows"],
}


def _mcp_dir() -> str:
    base = os.path.dirname(__file__)
    candidates = [
        os.environ.get("AGENT_TOOLS_DIR"),
        os.path.normpath(os.path.join(base, "..", "agent", "tools")),
        os.path.join(base, ".tools"),
    ]
    for path in candidates:
        if path and os.path.isdir(path):
            return path
    return candidates[1]


def _mcp_entries() -> list[dict]:
    """把 MCP 工具 schema 合并进手册；与 registry 同名的只读工具不重复。"""
    have = {s.tool for s in TOOL_SPECS.values()}
    entries = []
    for path in sorted(glob.glob(os.path.join(_mcp_dir(), "*.schema.json"))):
        try:
            spec = json.load(open(path, encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        name = spec.get("name", "")
        if name in have or name not in MCP_IDS:
            continue
        inp = spec.get("inputSchema", {}) or {}
        required = set(inp.get("required", []))
        params = []
        for pname, prop in (inp.get("properties") or {}).items():
            params.append({"name": pname, "type": prop.get("type", "string"),
                           "required": pname in required,
                           "description": prop.get("description", "")})
        sem = spec.get("semantics", {}) or {}
        outs = list((spec.get("outputSchema", {}).get("properties") or {}).keys())
        example = (spec.get("examples") or [{}])[0].get("input", {})
        entries.append({
            "id": MCP_IDS[name],
            "module": MCP_MODULE[name],
            "tool": name,
            "purpose": spec.get("description", ""),
            "preconditions": sem.get("preconditions", []),
            "parameters": params,
            "steps": MCP_STEPS.get(name, ["按参数调用", "读取返回"]),
            "returns": "返回字段：" + ("、".join(outs) if outs else "见 outputSchema"),
            "errors": sem.get("errorCodes", []),
            "example": json.dumps(example, ensure_ascii=False),
        })
    return entries


def _all_specs() -> list[dict]:
    _load_specs()
    entries = [spec.manual_entry() for spec in TOOL_SPECS.values()]
    entries += _mcp_entries()
    return entries


def _load_specs() -> None:
    if not TOOL_SPECS:
        # 导出不需要真实请求：用占位 client 生成静态规格
        build_tools(lambda event: None, BackendClient("http://localhost", "", "", "", 1))


def build_manual() -> dict:
    entries = _all_specs()
    modules = []
    for module, title in {**MODULES, **EXTRA_MODULES}.items():
        module_entries = [e for e in entries if e["module"] == module]
        if module_entries:
            modules.append({"id": module, "title": title, "entries": module_entries})
    return {
        "version": "1.1",
        "generatedAt": date.today().isoformat(),
        "source": "agent-service/registry.py + agent/tools/*.schema.json (auto-generated)",
        "modules": modules,
    }


def to_markdown(manual: dict) -> str:
    lines = ["# 数据中台 Agent 操作手册", "",
             f"> 自动生成：{manual['generatedAt']}；来源 `{manual['source']}`。",
             "> 工具变更后重新运行 `python manual.py export`，不要手改。", ""]
    for module in manual["modules"]:
        lines += [f"## {module['title']}（`{module['id']}`）", ""]
        for e in module["entries"]:
            lines += [f"### `{e['id']}` {e['purpose']}", "",
                      f"- **工具**：`{e['tool']}`",
                      "- **前置条件**：" + ("；".join(e["preconditions"]) or "无"),
                      "- **参数**：", "",
                      "| 名称 | 类型 | 必填 | 说明 |", "|---|---|---|---|"]
            for p in e["parameters"]:
                lines.append(f"| {p['name']} | {p['type']} | {'是' if p['required'] else '否'} | {p['description']} |")
            lines += ["", "- **步骤**：" + " → ".join(e["steps"]),
                      f"- **返回**：{e['returns']}",
                      "- **常见错误**：" + "；".join(e["errors"]),
                      f"- **示例**：{e['example']}", ""]
    return "\n".join(lines)


def main() -> None:
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "docs"
    os.makedirs(out_dir, exist_ok=True)
    manual = build_manual()
    with open(os.path.join(out_dir, "agent-manual.json"), "w", encoding="utf-8") as f:
        json.dump(manual, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "agent-manual.md"), "w", encoding="utf-8") as f:
        f.write(to_markdown(manual))
    print(f"exported {sum(len(m['entries']) for m in manual['modules'])} entries "
          f"in {len(manual['modules'])} modules -> {out_dir}")


if __name__ == "__main__":
    main()
