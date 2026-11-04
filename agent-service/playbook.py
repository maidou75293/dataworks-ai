"""流程 SOP playbook 中间层：repo 内 JSON 版本化 + 模块路由渲染 + 防腐校验。

定位（项目负责人信 seq=24④ / PRD《对话操作 dataworks 整改》④）：
- 工具级手册（manual.py）讲"单个工具怎么用"，本层讲"一条业务链怎么走"；
- 16 条现役 E2E 验收用例作流程骨架，每链一份 playbook 落在
  `agent-service/playbooks/*.json`，字段=目标 goal / 步骤序 steps（每步带工具）/
  失败判定 failWhen / 溯源 source（仓库内文档或用例路径）；
- 加载走 prompts.py 的模块路由，命中话题才带出；**改文件即生效**
  （目录 mtime+size 签名变化即重读，不重启服务）；
- 防腐锁（本文件 validate）：playbook 引用的工具名必须在
  registry.py∪agent/tools/*.schema.json 里，source 路径必须存在于仓库，
  module 必须是 prompts.py 可路由模块——缺失/失效=红，禁止静默放宽；
- 经验→SOP 提炼环（REQ-NIGHT-LEARN-1）产出的草案过目后写入 playbooks/，
  与本层同一校验口径。

用法（无第三方依赖，宿主裸 python 可跑）：
  python -I -c "import sys; sys.path.insert(0,'agent-service'); import playbook; print(len(playbook.load_playbooks()))"
"""
from __future__ import annotations

import ast
import glob
import json
import os

SCHEMA_VERSION = 1
REQUIRED_FIELDS = ("schemaVersion", "id", "module", "title", "goal",
                   "source", "steps", "failWhen")

_HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(_HERE, ".."))


def default_dir() -> str:
    """playbook 目录；SOP_PLAYBOOK_DIR 仅供测试/夜环沙箱覆写，生产不设。"""
    return os.environ.get("SOP_PLAYBOOK_DIR") or os.path.join(_HERE, "playbooks")


# 目录签名→(签名, 列表)。签名变（新增/删改文件/仅内容变化）即重读=改文件即生效。
_CACHE: dict[str, tuple[tuple, list[dict]]] = {}


def _signature(directory: str) -> tuple:
    return tuple(sorted(
        (name, int(st.st_mtime_ns), st.st_size)
        for name, st in (
            (os.path.basename(p), os.stat(p))
            for p in glob.glob(os.path.join(directory, "*.json"))
        )
    ))


def load_playbooks(directory: str | None = None) -> list[dict]:
    """按文件名排序加载全部 playbook；文件级 JSON 解析错误响亮抛出。"""
    directory = directory or default_dir()
    sig = _signature(directory)
    cached = _CACHE.get(directory)
    if cached and cached[0] == sig:
        return cached[1]
    items = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json")),
                       key=os.path.basename):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data["_file"] = os.path.basename(path)
        items.append(data)
    _CACHE[directory] = (sig, items)
    return items


def clear_cache() -> None:
    _CACHE.clear()


# ---------- 防腐：registry 工具名清单 ----------

def registry_tool_names(root: str | None = None) -> set[str]:
    """registry.py 里全部 ToolSpec 的 tool 名（AST 静态提取，不依赖运行环境）。

    走 AST 而非 import：registry 依赖 enter_harness/httpx，验收脚本要在裸
    python 下可跑；静态提取对两处（生产加载、验收锁）给出同一口径。
    """
    root = root or REPO_ROOT
    path = os.path.join(root, "agent-service", "registry.py")
    names: set[str] = set()
    with open(path, encoding="utf-8") as f:
        tree = ast.parse(f.read(), filename=path)
    for node in ast.walk(tree):
        if (isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "ToolSpec"):
            for kw in node.keywords:
                if kw.arg == "tool" and isinstance(kw.value, ast.Constant) \
                        and isinstance(kw.value.value, str):
                    names.add(kw.value.value)
    return names


def mcp_tool_names(root: str | None = None) -> set[str]:
    """agent/tools/*.schema.json 里的 MCP 工具名（与 manual 导出同源目录）。"""
    root = root or REPO_ROOT
    names: set[str] = set()
    for path in sorted(glob.glob(os.path.join(root, "agent", "tools", "*.schema.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                name = json.load(f).get("name", "")
        except (OSError, ValueError):
            continue
        if name:
            names.add(name)
    return names


def tool_universe(root: str | None = None) -> set[str]:
    """防腐校验的工具名全集：registry 8 只 ∪ MCP schema 全集。"""
    return registry_tool_names(root) | mcp_tool_names(root)


def routable_modules() -> set[str]:
    """可路由模块名=prompts.py KEYWORDS（懒导入避免与 prompts 互相卡死）。"""
    import prompts
    return set(prompts.KEYWORDS)


# ---------- 防腐校验 ----------

def validate(playbooks: list[dict], tools: set[str] | None = None,
             modules: set[str] | None = None, root: str | None = None) -> list[str]:
    """返回错误清单；空=绿。任何缺失/失效都记名道姓，不允许静默放宽。"""
    tools = tool_universe() if tools is None else tools
    modules = routable_modules() if modules is None else modules
    root = root or REPO_ROOT
    errors: list[str] = []
    seen: set[str] = set()
    for pb in playbooks:
        pid = pb.get("id") or pb.get("_file") or "<无id>"
        for field in REQUIRED_FIELDS:
            if field not in pb:
                errors.append(f"{pid}: 缺字段 {field}")
        if pb.get("schemaVersion") != SCHEMA_VERSION:
            errors.append(f"{pid}: schemaVersion={pb.get('schemaVersion')} 应为 {SCHEMA_VERSION}")
        if pid in seen:
            errors.append(f"{pid}: id 重复")
        seen.add(pid)
        if pb.get("module") not in modules:
            errors.append(f"{pid}: module={pb.get('module')!r} 不在可路由模块 {sorted(modules)}")
        steps = pb.get("steps") or []
        if not steps:
            errors.append(f"{pid}: steps 为空")
        for i, step in enumerate(steps, 1):
            if not step.get("action"):
                errors.append(f"{pid}: 步骤{i} 缺 action")
            tool = step.get("tool")
            if not tool:
                errors.append(f"{pid}: 步骤{i} 缺 tool（playbook 步骤必须绑定工具名）")
            elif tool not in tools:
                errors.append(f"{pid}: 步骤{i} 引用工具 {tool!r} 不存在于 registry（防腐锁）")
        if not pb.get("failWhen"):
            errors.append(f"{pid}: failWhen 为空——没有失败判定的 playbook 等于没有锁")
        for fw in pb.get("failWhen") or []:
            if not fw.get("if") or not fw.get("then"):
                errors.append(f"{pid}: failWhen 条目必须同时有 if/then")
        source = pb.get("source") or ""
        if source and not os.path.exists(os.path.join(root, source)):
            errors.append(f"{pid}: 溯源 source 不存在: {source}")
    return errors


def validate_default() -> list[str]:
    """对生产目录跑全量防腐校验。"""
    return validate(load_playbooks())


# ---------- 渲染（进提示词） ----------

def render(playbooks: list[dict]) -> str:
    """把 playbook 渲染成紧凑文本段（步骤序+工具引用+失败判定）。"""
    if not playbooks:
        return ""
    lines = ["【流程SOP·现役E2E验收用例】以下是平台流程骨架（机器可读 playbook，"
             "与仓库 agent-service/playbooks/ 同源）。回答涉及这些业务链时必须给出"
             "与之一致的步骤序与工具引用；引用工具名不得超出每步标注的工具："]
    for pb in playbooks:
        steps = " → ".join(
            f"{s.get('n', i)}){s.get('action', '')}[{s.get('tool', '')}]"
            for i, s in enumerate(pb.get("steps") or [], 1))
        fails = "；".join(f"若{f.get('if', '')}则{f.get('then', '')}"
                          for f in pb.get("failWhen") or [])
        lines.append(f"<{pb.get('id')}> {pb.get('title', '')}｜目标：{pb.get('goal', '')}")
        lines.append(f"  步骤序：{steps}")
        lines.append(f"  失败判定：{fails}")
    return "\n".join(lines)


def render_for_modules(modules: list[str], directory: str | None = None) -> str:
    """按命中模块带出对应 playbook（保持文件名排序，稳定可复现）。"""
    if not modules:
        return ""
    hit = set(modules)
    return render([pb for pb in load_playbooks(directory) if pb.get("module") in hit])
