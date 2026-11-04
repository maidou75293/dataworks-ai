# -*- coding: utf-8 -*-
"""会话持久化、记忆组装与知识检索（内核模型 v0.1）。

设计（与三方统一标准对齐）：
- 记忆只三类：semantic / procedural / profile；会话与摘要留在会话表，不落记忆表；
- authority 由 kind 派生，不落库：procedural=MUST、semantic=IS、profile=WANT；
- 身份不是记忆：实时读账号体系，只读，单独成段；
- 记忆只存用户明确说的内容，不自动推断；写入只追加，撤回=归档；
- 知识检索走 MCP kb.search，片段带标题，回答要求引用来源；
- 任何写入失败都降级，不影响本轮回答。
"""
from __future__ import annotations

import json
import re

MAX_HISTORY = 20
MAX_CONTENT = 4000
# 摘要之后保留的原文条数；更早的内容进 summary
KEEP_RECENT = 8
SUMMARY_MAX = 4000
# 偏好常驻上限（内核：profile 是 WANT，只改表达，不占预算）
PREFERENCE_MAX = 5

KIND_LABEL = {"semantic": "事实", "procedural": "制度", "profile": "偏好"}
SUBTYPE_LABEL = {
    "fact": "事实", "decision": "决策", "policy": "制度",
    "contract": "口径", "sop": "流程", "skill": "做法", "preference": "偏好",
}


def _safe(fn, default=None):
    try:
        return fn()
    except Exception:
        return default


def ensure_conversation(client, session_id: str, project_id: int, first_message: str) -> None:
    if not session_id:
        return
    title = re.sub(r"\s+", " ", (first_message or "").strip())[:80] or "新对话"
    _safe(lambda: client.post("/api/internal/agent/conversations", {
        "sessionId": session_id, "projectId": project_id, "title": title,
    }))


def append_message(client, session_id: str, role: str, content: str,
                   tool_calls: list[dict] | None = None, status: str | None = None) -> None:
    if not session_id or content is None:
        return
    _safe(lambda: client.post(
        f"/api/internal/agent/conversations/{session_id}/messages", {
            "role": role,
            "content": content[:100_000],
            "toolCalls": tool_calls or None,
            "status": status,
        }))


def load_context(client, session_id: str, limit: int = KEEP_RECENT + 4) -> tuple[list[dict], str]:
    """返回（摘要之后的最近原文, 滚动摘要）。"""
    meta = _safe(lambda: client.get(
        f"/api/internal/agent/conversations/{session_id}/summary"), {}) or {}
    upto = int(meta.get("uptoSeq") or 0)
    summary = (meta.get("summary") or "").strip()
    rows = _safe(lambda: client.get(
        f"/api/internal/agent/conversations/{session_id}/messages",
        {"limit": limit, "afterSeq": upto}), [])
    out = []
    budget = 8000
    for row in rows or []:
        if row.get("role") not in ("user", "assistant"):
            continue
        content = (row.get("content") or "").strip()
        if not content:
            continue
        content = content[:MAX_CONTENT]
        if out and len(content) > budget:
            break
        budget -= len(content)
        out.append({"role": row["role"], "content": content})
    return out, summary


def summary_block(summary: str) -> str:
    if not summary:
        return ""
    return "【更早对话摘要（原文已压缩；细节不确定时以最近对话为准）】\n" + summary


def _brief(role: str, content: str) -> str:
    text = re.sub(r"\s+", " ", content).strip()
    if role == "user":
        return "- 用户目标：" + text[:200]
    return "- 助手结论：" + text[:160]


def maybe_summarize(client, session_id: str) -> None:
    """超过 KEEP_RECENT 条时，把更早的消息压成确定性摘要并写回。

    不用模型：只做要点摘录（用户目标 + 助手结论首句），成本为零且可控。
    不做「摘要的摘要」：到上限就丢最旧内容，回溯走原文。
    """
    meta = _safe(lambda: client.get(
        f"/api/internal/agent/conversations/{session_id}/summary"), {}) or {}
    upto = int(meta.get("uptoSeq") or 0)
    rows = _safe(lambda: client.get(
        f"/api/internal/agent/conversations/{session_id}/messages",
        {"limit": 100, "afterSeq": upto}), []) or []
    if len(rows) <= KEEP_RECENT:
        return
    older = rows[:-KEEP_RECENT]
    if not older:
        return
    lines = []
    previous = (meta.get("summary") or "").strip()
    if previous:
        lines.append(previous)
    for row in older:
        content = (row.get("content") or "").strip()
        if row.get("role") in ("user", "assistant") and content:
            lines.append(_brief(row["role"], content))
    text = "\n".join(lines)
    if len(text) > SUMMARY_MAX:
        text = text[-SUMMARY_MAX:]
    _safe(lambda: client.put(
        f"/api/internal/agent/conversations/{session_id}/summary",
        {"summary": text, "uptoSeq": int(older[-1].get("seq") or 0)}))


def load_memories(client, project_id: int) -> dict:
    data = _safe(lambda: client.get("/api/internal/agent/memories",
                                    {"projectId": project_id}), {}) or {}
    return {
        "platform": list(data.get("platform") or data.get("system") or []),
        "project": list(data.get("project") or []),
        "user": list(data.get("user") or []),
    }


def load_identity(client, project_id: int) -> dict:
    return _safe(lambda: client.get("/api/internal/agent/identity",
                                    {"projectId": project_id}), {}) or {}


def _row_kind(row: dict) -> str:
    kind = str(row.get("kind") or "").lower()
    if kind in KIND_LABEL:
        return kind
    # 兼容旧字段（迁移期）
    legacy = str(row.get("memType") or "").upper()
    return {"RULE": "procedural", "PREFERENCE": "profile"}.get(legacy, "semantic")


def _row_text(row: dict) -> str:
    value = row.get("content")
    if value is None:
        value = row.get("value")
    return str(value or "")


def _row_key(row: dict) -> str:
    return str(row.get("key") or "")


def _row_subtype(row: dict) -> str:
    subtype = str(row.get("subtype") or "").lower()
    if subtype:
        return subtype
    legacy = str(row.get("memType") or "").upper()
    return {"RULE": "policy", "PREFERENCE": "preference", "DECISION": "decision"}.get(
        legacy, "fact")


def _rows_of(memories: dict, kind: str) -> list[dict]:
    out = []
    for scope in ("platform", "project", "user"):
        for row in memories.get(scope) or []:
            if _row_kind(row) == kind:
                out.append({**row, "_scope": scope})
    return out


def _tokens(text: str) -> set[str]:
    """轻量分词：中文双字 + 英文/数字词，够做事实召回，不引入额外依赖。"""
    lowered = (text or "").lower()
    latin = set(re.findall(r"[a-z0-9_]{2,}", lowered))
    han = re.findall(r"[一-鿿]", lowered)
    bigrams = {han[i] + han[i + 1] for i in range(len(han) - 1)}
    return latin | bigrams


def _relevance(question: str, row: dict) -> int:
    q = _tokens(question)
    if not q:
        return 0
    return len(q & _tokens(f"{_row_key(row)} {_row_text(row)}"))


def select_facts(memories: dict, question: str = "", per_scope: int = 8) -> list[dict]:
    """语义记忆召回：决策常驻，其余事实按本轮问题命中；无命中保留 3 条常用。

    只读不写：不在这里改 use_count，避免「读一次就改排序」。
    """
    rows = _rows_of(memories, "semantic")
    if not question:
        return rows[:per_scope]
    mandatory = [r for r in rows if _row_subtype(r) == "decision"]
    optional = [r for r in rows if _row_subtype(r) != "decision"]
    optional.sort(key=lambda r: (-_relevance(question, r), -int(r.get("useCount") or 0)))
    picked = mandatory[:per_scope]
    for row in optional:
        if len(picked) >= per_scope:
            break
        if _relevance(question, row) > 0:
            picked.append(row)
    if not any(_relevance(question, row) > 0 for row in rows):
        for row in optional:
            if len(picked) >= min(3, len(rows)):
                break
            if row not in picked:
                picked.append(row)
    return picked


def _scope_label(scope: str) -> str:
    return {"platform": "平台", "project": "项目", "user": "个人"}.get(scope, scope)


def instruction_block(memories: dict) -> str:
    """procedural（MUST）：制度与口径，全量注入，不做关键词裁剪。"""
    rows = _rows_of(memories, "procedural")
    if not rows:
        return ""
    lines = ["【制度与口径（必须遵守，任何偏好或临时说法都不能覆盖）】"]
    for row in rows:
        label = SUBTYPE_LABEL.get(_row_subtype(row), "制度")
        lines.append(f"- [{_scope_label(row['_scope'])}·{label}] "
                     f"{_row_key(row)}：{_row_text(row)}")
    return "\n".join(lines)


def identity_block(identity: dict) -> str:
    """身份不是记忆：来自账号体系，只读，只定称呼与权限范围。"""
    if not identity:
        return ""
    name = identity.get("displayName") or identity.get("username") or identity.get("userId")
    if not name:
        return ""
    parts = [str(name)]
    if identity.get("role"):
        parts.append(str(identity["role"]))
    if identity.get("department"):
        parts.append("部门=" + str(identity["department"]))
    if identity.get("projectName"):
        parts.append("当前项目=" + str(identity["projectName"]))
    return "【当前用户身份（客观数据，用于称呼与权限判断，不代表任何偏好）】\n" + " / ".join(parts)


def fact_block(memories: dict, question: str = "") -> str:
    """semantic（IS）：决策常驻，其余事实命中才进。"""
    rows = select_facts(memories, question)
    if not rows:
        return ""
    lines = ["【记忆事实（项目/个人事实与决策；与平台数据冲突时以平台数据为准）】"]
    for row in rows:
        label = SUBTYPE_LABEL.get(_row_subtype(row), "事实")
        lines.append(f"- [{_scope_label(row['_scope'])}·{label}] "
                     f"{_row_key(row)}：{_row_text(row)}")
    return "\n".join(lines)


def preference_block(memories: dict) -> str:
    """profile（WANT）：只约束表达，最多 5 条，权重最低。"""
    rows = _rows_of(memories, "profile")[:PREFERENCE_MAX]
    if not rows:
        return ""
    lines = ["【用户偏好（只影响表达形式，不改变结论、范围与流程）】"]
    for row in rows:
        lines.append(f"- {_row_key(row)}：{_row_text(row)}")
    return "\n".join(lines)


def injected_rows(memories: dict, question: str = "") -> list[dict]:
    """本轮真正进入上下文的记忆行：制度全量 + 命中事实 + 偏好（前 5）。"""
    rows = _rows_of(memories, "procedural")
    rows += select_facts(memories, question)
    rows += _rows_of(memories, "profile")[:PREFERENCE_MAX]
    return rows


def record_usage_ids(client, ids: list[str]) -> None:
    """拼装之后按「实际注入的行」计数；ids 来自 harness context 的选择结果。"""
    numbers = []
    for item in ids or ():
        try:
            numbers.append(int(item))
        except (TypeError, ValueError):
            continue
    if not numbers:
        return
    _safe(lambda: client.post("/api/internal/agent/memories/used", numbers))


def _remember_args(text: str) -> tuple[str, str] | None:
    for prefix in ("请记住", "记住", "记一下"):
        if text.startswith(prefix):
            body = text[len(prefix):].strip(" ：:，,。").strip()
            if not body:
                return None
            for sep in ("是", "：", ":", "=", "为"):
                if sep in body:
                    key, value = body.split(sep, 1)
                    key, value = key.strip(" 　：:，,。"), value.strip(" 　：:，,。")
                    if key and value:
                        return key, value
            return "备注", body
    return None


def _memory_kind(text: str) -> tuple[str, str]:
    """按标准 §2.1 的形状规则判 kind，不靠猜：命中哪种说法就是哪类。"""
    if re.search(r"以后都|统一用|统一为|必须|不许|口径|规范|流程是|标准是", text):
        return ("procedural", "contract" if "口径" in text else "policy")
    if re.search(r"决定|决策|约定|定了用", text):
        return "semantic", "decision"
    if re.search(r"偏好|喜欢|习惯|倾向|先给|少用|别用", text):
        return "profile", "preference"
    return "semantic", "fact"


def _scope_for(body: str, project_id: int) -> str:
    if project_id and not re.search(r"(我|我的|偏好|习惯)", body[:12]):
        return "project"
    return "user"


def handle_memory_command(client, message: str, project_id: int) -> str | None:
    """识别「记住 / 忘记 / 我的记忆」；命中返回直接回复，未命中返回 None。"""
    text = (message or "").strip()
    if not text:
        return None

    args = _remember_args(text)
    if args:
        key, value = args
        body = text.split("记住", 1)[-1]
        scope = _scope_for(body, project_id)
        if scope == "project" and not project_id:
            scope = "user"
        kind, subtype = _memory_kind(text)
        resp = _safe(lambda: client.post("/api/internal/agent/memories", {
            "scope": scope,
            "projectId": project_id if scope == "project" else None,
            "key": key[:128],
            "value": value[:2000],
            "kind": kind,
            "subtype": subtype,
        }))
        if not resp:
            return "记忆没有保存成功（后端不可用），这一轮回答不受影响。"
        # 普通成员说「记住」时，后端会把项目级降级为个人记忆，以实际保存的层级回复
        saved_scope = str((resp or {}).get("scope") or scope).lower()
        label = "项目级" if saved_scope == "project" else "用户级"
        kind_label = SUBTYPE_LABEL.get(str((resp or {}).get("subtype") or subtype),
                                       KIND_LABEL.get(kind, "事实"))
        return f"已记住（{label}·{kind_label}）：{key} = {value}"

    forget = re.match(r"^(请)?忘记\s*[:：]?\s*(.+)$", text)
    if forget:
        key = forget.group(2).strip(" 　：:，,。")
        archived = 0
        for scope, owner in (("project", project_id), ("user", None)):
            if scope == "project" and not project_id:
                continue
            params = {"scope": scope, "key": key}
            if owner:
                params["projectId"] = owner
            resp = _safe(lambda: client.delete("/api/internal/agent/memories", params), {})
            archived += int((resp or {}).get("archived") or 0)
        return (f"已忘记「{key}」（归档 {archived} 条，可审计恢复）。"
                if archived else f"没有找到「{key}」这条记忆。")

    if text in ("我的记忆", "查看记忆", "有哪些记忆", "记忆列表"):
        memories = load_memories(client, project_id)
        identity = load_identity(client, project_id)
        parts = [instruction_block(memories),
                 identity_block(identity),
                 fact_block(memories),
                 preference_block(memories)]
        block = "\n\n".join(p for p in parts if p)
        return block or "还没有记住任何内容。可以说「记住 项目库是 gongxiang_dev」。"

    return None
