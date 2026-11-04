#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""经验→SOP 提炼夜环（REQ-NIGHT-LEARN-1 · PRD《对话操作 dataworks 整改》④c）。

谁是「经验」：
1. 夜间岗位学习跑出来的 **badcase TOP**（高频低质问题，`reports[].badcaseTop`）；
2. 夜间**异常圈**事实（评测不达标 / TABLE 段 SQL 失败 / 超窗未终，`reports[].abnormalRuns`）；
3. 助手运行时 `experience.py` 落的 **工具失败流水**（`data/experience-*.jsonl`）。

提炼成什么：机器可读 **SOP 草案**（与 `agent-service/playbooks/` 同一 schema：schemaVersion/id/module/
title/goal/source/steps[每步带工具]/failWhen），落 `agent-service/playbooks-draft/`，
**人工过目后**由人移入 `playbooks/`。本模块永不写现役库（卡面「全自动+晨复核」的知识侧同构）。

三道闸（缺一不落盘，拒因写进返回，不静默）：
- **支持度闸**：同一签名（归一化后）一夜内出现 ≥ `SOP_MIN_SUPPORT`（默认 2；badcase 问题默认 3）次才值得成文——
  偶发一次就写 SOP = 把噪声固化成规矩；
- **路由闸**：`module` 必须 ∈ `prompts.KEYWORDS`（`playbook.routable_modules()`）——落不进任何可路由模块的
  「SOP」永远不会被提示词带出，写了也是死档案，宁可拒；
- **防腐锁**：直接复用 `playbook.validate`（工具名 ∈ registry∪MCP schema、source 路径存在、步骤齐、
  failWhen 非空且 if/then 双全）。过不了锁=不写文件，只记拒因。

确定性：无 LLM、无网络。同一批输入跑两次产出字节一致（sort_keys + 稳定 id=sha1(签名)），可复现可审。

用法（判据/演练）：
    python -I -c "import sys; sys.path.insert(0,'agent-service'); import sop_distill; \
        print(sop_distill.distill([], []))"
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, ".."))
if HERE not in sys.path:
    sys.path.insert(0, HERE)      # `-I` 裸跑时脚本目录不进 sys.path（safe_path），自锚一次才敢 import playbook

SCHEMA_VERSION = 1
MIN_SUPPORT = int(os.environ.get("SOP_MIN_SUPPORT", "2"))          # 异常/工具类签名支持度
BADCASE_MIN = int(os.environ.get("SOP_BADCASE_MIN", "3"))          # badcase 问题成文门槛
DRAFT_DIRNAME = "playbooks-draft"
SOURCE_DOC = "docs/夜间岗位学习-20261104.md"                        # 溯源=本卡定版文（仓库内存在才敢写）

# 家族→(module, 步骤序[action, tool], 失败判定)。工具名全部取现役 34 个之一（防腐锁兜住改名）。
FAMILIES = {
    "kb-gapfill": {
        "module": "kb",
        "title": "夜间高频低质问题补知识库",
        "goal": "把岗位夜学攒出来的高频问题（答不好/没答案）沉淀成可检索知识，并在回灌后验一次",
        "steps": [
            {"n": 1, "action": "先查知识库里有没有现成答案", "tool": "kb.search"},
            {"n": 2, "action": "向量召回近似问法，确认是同一问题域", "tool": "embed.query"},
            {"n": 3, "action": "把复核后的标准答案入库（带来源）", "tool": "kb.ingest"},
            {"n": 4, "action": "回灌评测确认该类问题命中新知识点", "tool": "kb.search"},
        ],
        "failWhen": [
            {"if": "检索与向量召回都无命中", "then": "按问题域建知识卡入库，不许静默跳过"},
            {"if": "入库失败或新答案仍答不对", "then": "转人工复核并留原始 badcase 问题文本"},
        ],
    },
    "table-sql-fail": {
        "module": "catalog",
        "title": "开圈 SQL 先过元数据校验",
        "goal": "夜间圈在选表入环段失败（表/列不存在、类型不符）时，先核元数据再入环，别拿错 SQL 空跑一夜",
        "steps": [
            {"n": 1, "action": "列项目内可见表，确认表名存在", "tool": "catalog.list_tables"},
            {"n": 2, "action": "看列名/类型，核对 qColumn/aColumn", "tool": "catalog.describe_table"},
            {"n": 3, "action": "小样本试跑修正后的 SQL", "tool": "doris.sql_query"},
        ],
        "failWhen": [
            {"if": "表或列在元数据里查不到", "then": "改列映射或换表，改完再开圈；本圈不重试到超窗"},
            {"if": "试跑仍报错", "then": "标记该岗特征数据失效，晨报异常圈单列并转人工"},
        ],
    },
    "datasource-unreach": {
        "module": "datasource",
        "title": "数据源连不通先查权限再重试",
        "goal": "同类连接失败反复出现时，按「数据源在册吗→有权吗→再试一次」的顺序处置，不盲目重跑",
        "steps": [
            {"n": 1, "action": "列出租户可见数据源，确认目标在册", "tool": "list_datasources"},
            {"n": 2, "action": "核对平台侧数据源配置与状态", "tool": "platform.list_datasources"},
            {"n": 3, "action": "缺读写权限就提申请，不绕权限", "tool": "request_access"},
        ],
        "failWhen": [
            {"if": "数据源不在册或状态异常", "then": "停止重试并报警，交人工核连接参数"},
            {"if": "申请权限未批", "then": "本夜按异常圈入报，不许降级读别库冒充"},
        ],
    },
}

_ABN_MAP = {                                            # 异常圈种类 → 家族
    "TABLE": "table-sql-fail",
}


def _sig(text):
    """签名归一：小写、数字打点成 #、空白折叠、截断——同题不同写法必须并到一个键。"""
    s = re.sub(r"\d+", "#", str(text or "").lower())
    s = re.sub(r"[\s,，。.;；:：!！?？'\"]+", " ", s).strip()
    return s[:80]


def _digest(*parts):
    raw = "|".join(str(p) for p in parts).encode("utf-8")
    return hashlib.sha1(raw).hexdigest()[:10]


def _draft_id(family, sig):
    return "sop-night-%s-%s" % (family, _digest(family, sig))


def _candidates(reports, exp):
    """把三路经验汇成候选：(family, sig, 支持度, 样例行)。纯计数，不猜语义。"""
    cand = {}

    def bump(family, sig, n=1, sample=None):
        if not sig:
            return
        key = (family, sig)
        e = cand.setdefault(key, {"n": 0, "sample": sample})
        e["n"] += n
        if e["sample"] is None:
            e["sample"] = sample

    for rep in reports or []:
        pid = rep.get("labelProjectId")
        for row in rep.get("badcaseTop") or []:
            q = str(row.get("question") or "").strip()
            if q:
                bump("kb-gapfill", _sig(q), max(1, int(num(row.get("count")) or 1)),
                     {"question": q[:120], "post": pid})
        for a in rep.get("abnormalRuns") or []:
            reason = str(a.get("reason") or "")
            kind = str(a.get("kind") or "")
            step = str(a.get("step") or "")
            if kind in ("RUN_FAILED", "OVER_WINDOW") and (step == "TABLE" or "sql" in reason.lower()
                                                           or "列" in reason or "表" in reason):
                bump("table-sql-fail", _sig(reason.split("：", 1)[-1]), 1,
                     {"post": pid, "runId": a.get("runId"), "reason": reason[:160]})

    for row in exp or []:
        tool = str(row.get("tool") or "")
        ok = row.get("ok")
        if ok is False or str(row.get("result") or "").lower() in ("fail", "error"):
            err = _sig(row.get("error") or row.get("message") or "连接失败")
            if tool in ("list_datasources", "platform.list_datasources", "doris.sql_query",
                        "list_catalog_tables"):
                bump("datasource-unreach", err, 1, {"tool": tool, "error": str(err)[:160]})
    return cand


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def threshold_for(family):
    return BADCASE_MIN if family == "kb-gapfill" else MIN_SUPPORT


def build_draft(family, sig, sample, night_date):
    """按家族模板拼一份草案（字段齐 schema；id 稳定可幂等）。"""
    fam = FAMILIES[family]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "id": _draft_id(family, sig),
        "module": fam["module"],
        "title": fam["title"],
        "goal": fam["goal"],
        "source": SOURCE_DOC,
        "steps": [{"n": s["n"], "action": s["action"], "tool": s["tool"]} for s in fam["steps"]],
        "failWhen": [{"if": f["if"], "then": f["then"]} for f in fam["failWhen"]],
        "provenance": {"generatedBy": "cc-ctrld/postlearn(REQ-NIGHT-LEARN-1)",
                       "nightDate": night_date, "family": family,
                       "signature": sig, "sample": sample},
    }


def existing_ids(*dirs):
    """已在库/已在草案区的 id，用于幂等（同签名同家族不重复产出）。"""
    out = set()
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(d, name), encoding="utf-8") as f:
                    out.add(str(json.load(f).get("id")))
            except Exception:
                continue
    return out


def distill(reports, exp, root=None, draft_dir=None, night_date=None,
            tools=None, modules=None, playbooks_dir=None, code_root=None):
    """主入口：候选→三道闸→写草案。返回 {written, rejected, drafts, nothing}。

    `root`=草案/溯源落点（夜环演练可指沙箱）；`code_root`=**取工具全集与路由模块的代码根**
    （工具名/模块名是代码事实，永远取现役仓库，不随沙箱 root 走偏=沙箱里没 registry.py 也能提炼）。
    """
    root = os.path.normpath(root or REPO_ROOT)
    code_root = os.path.normpath(code_root or REPO_ROOT)
    draft_dir = draft_dir or os.path.join(root, "agent-service", DRAFT_DIRNAME)
    playbooks_dir = playbooks_dir or os.path.join(root, "agent-service", "playbooks")
    night_date = night_date or _night_of(reports)

    import playbook                                    # 同目录，防腐锁与 schema 的唯一事实来源
    tools = playbook.tool_universe(code_root) if tools is None else tools
    modules = playbook.routable_modules() if modules is None else modules
    seen = existing_ids(playbooks_dir, draft_dir)

    rejected, drafts = [], []
    cand = _candidates(reports, exp)
    for (family, sig), info in sorted(cand.items()):
        need = threshold_for(family)
        if info["n"] < need:
            rejected.append({"id": _draft_id(family, sig),
                             "reason": "支持度不足（%d<%d）：偶发一次不成文" % (info["n"], need),
                             "family": family})
            continue
        draft = build_draft(family, sig, info.get("sample"), night_date)
        if draft["id"] in seen:
            rejected.append({"id": draft["id"], "reason": "同签名已在库/已在草案区（幂等跳过）",
                             "family": family})
            continue
        if draft["module"] not in modules:
            rejected.append({"id": draft["id"], "reason": "路由闸：module=%r 不在可路由模块" % draft["module"],
                             "family": family})
            continue
        errs = playbook.validate([draft], tools=tools, modules=modules, root=root)
        if errs:
            rejected.append({"id": draft["id"], "reason": "防腐锁：" + "；".join(errs), "family": family})
            continue
        drafts.append(draft)
        seen.add(draft["id"])

    written = 0
    if drafts:
        os.makedirs(draft_dir, exist_ok=True)
        for d in drafts:
            p = os.path.join(draft_dir, d["id"] + ".json")
            tmp = p + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
                f.write("\n")
            os.replace(tmp, p)
            written += 1
    return {"written": written, "rejected": rejected,
            "drafts": [d["id"] for d in drafts],
            "nothing": not cand, "draftDir": draft_dir,
            "candidates": len(cand)}


def _night_of(reports):
    for r in reports or []:
        if r.get("nightDate"):
            return r["nightDate"]
    return ""


def main(argv=None):
    """空跑自检：不读真实数据，只证明模块可用+模板全部过得了防腐锁。"""
    import argparse
    ap = argparse.ArgumentParser(description="经验→SOP 提炼夜环（草案生成器）")
    ap.add_argument("--check-templates", action="store_true", help="模板全过防腐锁=GREEN（改名/删工具必红）")
    a = ap.parse_args(argv)
    if a.check_templates:
        import playbook
        tools, modules = playbook.tool_universe(), playbook.routable_modules()
        bad = []
        for fam in sorted(FAMILIES):
            errs = playbook.validate([build_draft(fam, "自检签名", None, "1970-01-01")],
                                     tools=tools, modules=modules)
            if errs:
                bad.append((fam, errs))
        if bad:
            for fam, errs in bad:
                print("FAIL %s: %s" % (fam, "；".join(errs)))
            print("== sop-distill: SOPSKELETON-RED ==")
            return 1
        print("== sop-distill: SOPSKELETON-GREEN families=%d ==" % len(FAMILIES))
        return 0
    out = distill([], [])
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
