# -*- coding: utf-8 -*-
"""对话写操作「向导式确认」核心（REQ-AGENT-WIZCONFIRM-1，2026-11-04）。

两个调用方共用本模块，口径只在这里写一遍：

- `slots.py`（对话建任务向导）：槽位收集完成后按四栏回显，改槽回填再生效；
- `harness_ports.DataworksAction`（host 动作端口）：写工具走向导票，
  四栏进 `ActionResult.meta.view`，回填后的参数才是执行的参数。

**本模块只用标准库**——不 import enter_harness / httpx，验收脚本
`python -I scripts/e2e/agent-wizconfirm-test.py` 在没有内核的解释器里
也能直接断言向导语义（四栏/白名单/违禁词/超时/角色）。

项目负责人口径（信箱 seq=24③，PRD ③；骚扰红线=只读免确认）：

1. 只读免确认：查询类工具直接执行，不发确认（判定在端口/入口层）。
2. 写 = 对象|动作|影响|回滚 四栏回显 + 改槽回填才执行。
3. 权限面 DELEGATE=跟随本人：对话写操作用使用者本人的角色/权限判定，
   助手永远不超过使用者自己。
4. 一期白名单只有「建/改任务配置类」；drop/delete 类**不开放直接执行**，
   要删除只能走平台审批申请（`request_change`，人来批）。
5. 超时取消不落库：确认窗/草稿闲置过期即自动取消，草稿行删除，不执行写入。
"""
from __future__ import annotations

import re
import time
from typing import Any

VIEW_KEYS = ("object", "action", "impact", "rollback")
VIEW_VERSION = 1

# 一期直接执行的写操作白名单：只有建/改任务配置类。
WRITE_WHITELIST = frozenset({"create_sync_task", "update_task_config"})
# 写权限跟随本人：与 Java 侧 Permission.DEV_WRITE / atom sync:write 的
# 内置角色面一致（harness_ports 的 request_change 角色闸同源）。
WRITE_ROLES = frozenset({"DEVELOPER", "ADMIN"})

# 确认窗：四栏回显后多久没回填就自动取消（分钟级；30 分钟）
CONFIRM_TTL_SECONDS = 30 * 60
# 收集窗：槽位没填完的草稿闲置多久作废（2 小时）
DRAFT_TTL_SECONDS = 2 * 3600

# 违禁动作：出现在**工具名/参数/对话消息**里就直接拒绝（一期不开放）。
# 「申请删除」不在此列——request_change 只生成审批单，仍由人来批。
# 规则宁滥勿缺：误拒一句可以让用户换个说法，漏放一次 drop 就是删数据。
_FORBIDDEN_RES = (
    re.compile(r"(?<![A-Za-z_])drop(?![A-Za-z_])", re.I),
    re.compile(r"(?<![A-Za-z_])truncate(?![A-Za-z_])", re.I),
    re.compile(r"(?<![A-Za-z_])delete\s+from", re.I),
    re.compile(r"(删|清空|清除)[^\n。；;]{0,8}?(表|库|任务|数据源|项目)"),
    re.compile(r"(表|库|任务|数据源|项目)[^\n。；;]{0,6}?(删|清空|清除)"),
)
# 工具名第一个词是删除语义即不开放（drop_table / delete_task / truncate_x ...）
_FORBIDDEN_HEAD = frozenset({"drop", "delete", "truncate", "purge", "destroy", "clear"})

_REFUSE_HINT = ("对话助手一期不开放删除/Drop 类直接执行；"
                "写白名单只有「建/改任务配置」类。"
                "确需删除请让我发起变更申请（平台审批），或到对应页面由本人操作。")


# ------------------------------------------------------------------ 判定

def forbidden_hit(text: Any) -> str:
    """消息/参数文本里第一个命中的违禁片段；没有返回空串。"""
    for rx in _FORBIDDEN_RES:
        m = rx.search(str(text or ""))
        if m:
            return m.group(0)[:24]
    return ""


def tool_forbidden(name: str) -> bool:
    """工具名本身带删除/清空语义（drop_table / delete_task / truncate_x）视为不开放。"""
    toks = re.split(r"[_\-\s]+", str(name or "").strip().lower())
    if toks and toks[0] in _FORBIDDEN_HEAD:
        return True
    return bool(forbidden_hit(str(name or "").replace("_", " ")))


def role_ok(roles: Any) -> bool:
    """权限面跟随本人：写操作要求本人角色在开发/管理员面内。"""
    normalized = {str(r or "").strip().upper() for r in (roles or []) if r}
    return bool(normalized & WRITE_ROLES)


def refuse_message(scope: str = "") -> str:
    prefix = f"{scope}：" if scope else ""
    return prefix + _REFUSE_HINT


# ------------------------------------------------------------------ 视图

def make_view(*, op: str, object_: str, action: str,
              impact: Any, rollback: Any,
              slots: list[dict] | None = None,
              expires_in: int = CONFIRM_TTL_SECONDS) -> dict:
    """构造四栏回显视图；四栏缺一即报错（宁可在开发期炸，不在用户面缺栏）。"""
    view = {
        "version": VIEW_VERSION,
        "op": str(op or ""),
        "object": str(object_ or ""),
        "action": str(action or ""),
        "impact": _as_lines(impact),
        "rollback": _as_lines(rollback),
        "slots": [dict(s) for s in (slots or [])],
        "expires_in": int(expires_in),
    }
    for key in VIEW_KEYS:
        if not view[key]:
            raise ValueError(f"确认视图缺少「{key}」栏")
    return view


def _as_lines(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value.strip() else []
    return [str(v) for v in value if str(v).strip()]


def view_for_op(name: str, args: dict) -> dict:
    """端口层白名单写操作的标配四栏（从工具参数构造；未知参数逐项列出）。"""
    args = dict(args or {})
    known = {k: v for k, v in sorted(args.items())}
    obj_bits = []
    for key in ("code", "taskCode", "name", "id", "targetId", "targetName"):
        if args.get(key) not in (None, ""):
            obj_bits.append(f"{key}={args[key]}")
    object_ = "同步任务配置（" + ("、".join(obj_bits) or "未指明对象") + "）"
    if name == "create_sync_task":
        action_ = "创建同步任务配置"
        if args.get("run") in (True, "true", "1"):
            action_ += "并立即运行"
        impact = [
            "只在平台新增一条任务配置；不改动任何已有任务与数据。",
            "若同时运行：会向目标端写入数据，作业资源按配置占用。",
        ]
        rollback = [
            "删除该任务需发起变更申请（request_change），由审批人处理；",
            "对话助手一期不直接删任务、不删表（drop/delete 不开放）。",
        ]
    else:  # update_task_config
        action_ = "修改既有同步任务的配置"
        impact = [
            "配置保存后，下一次运行使用新配置；正在运行的作业不受影响。",
            "改错可能导致下次运行失败，请在回填前核对每一项。",
        ]
        rollback = [
            "再发一次「改配置」把参数回填为原值即可恢复；",
            "建议先查看当前配置（list_sync_tasks / get_sync_task_status）留底。",
        ]
    slots = [{"key": k, "title": k, "value": str(v)} for k, v in known.items()]
    return make_view(op=name, object_=object_, action=action_,
                     impact=impact, rollback=rollback, slots=slots)


def render_text(view: dict, graph: str = "") -> str:
    """把四栏视图渲染成给用户看的文本；结构事实只在 view 里，这里只排版。"""
    lines = ["即将执行的写操作（确认前不会写入任何数据）：", ""]
    if graph:
        lines.append(graph.rstrip())
        lines.append("")
    lines.append(f"【对象】{view.get('object', '')}")
    lines.append(f"【动作】{view.get('action', '')}")
    lines.append("【影响】")
    lines += [f"  - {t}" for t in view.get("impact") or []]
    lines.append("【回滚】")
    lines += [f"  - {t}" for t in view.get("rollback") or []]
    window = int(view.get("expires_in") or 0)
    label = (f"{max(1, window // 3600)} 小时" if window >= 3600
             else f"{max(1, window // 60)} 分钟")
    lines.append("")
    lines.append(f"确认执行请回复「确认执行」；要改哪一项直接说（例如「运行方式批」「验收 200 行」）；"
                 f"放弃请回复「取消」。确认窗 {label}，超时自动取消、不会写入任何数据。")
    return "\n".join(lines)


# ------------------------------------------------------------------ 时效

def touch(st: dict, stage: str = "") -> None:
    """记录活动时间；进入确认阶段时刷新确认起点。"""
    now = time.time()
    meta = st.setdefault("_meta", {})
    meta["updated_at"] = now
    if stage == "confirm":
        meta["confirm_at"] = now
    elif stage:
        meta.pop("confirm_at", None)


def draft_expired(st: dict, now: float | None = None) -> str:
    """返回 ""（未过期） / "confirm"（确认窗超时） / "draft"（收集窗闲置超时）。"""
    meta = dict(st.get("_meta") or {})
    now = time.time() if now is None else float(now)
    stage = str(st.get("stage") or "")
    updated = float(meta.get("updated_at") or 0)
    if not updated:
        return ""
    if stage == "confirm":
        anchor = float(meta.get("confirm_at") or updated)
        if now - anchor > CONFIRM_TTL_SECONDS:
            return "confirm"
        return ""
    if now - updated > DRAFT_TTL_SECONDS:
        return "draft"
    return ""
