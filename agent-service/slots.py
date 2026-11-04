# -*- coding: utf-8 -*-
"""M2 槽位收集 + 写操作向导式确认（REQ-AGENT-WIZCONFIRM-1）。

源、目标、映射、运行方式、验收五个槽位。

设计原则：
- 不猜：缺哪个槽位就问哪个；每填一项立刻校验（数据源是否真实存在）。
- 槽位齐了按 **对象|动作|影响|回滚** 四栏回显（结构化事件 `wiz_confirm` + 文本），
  用户明确确认后才建任务并运行；确认阶段可以直接改槽（改槽回填再生效）。
- 写权限面跟随本人（DELEGATE 口径）：进入向导与执行前各校验一次使用者本人角色。
- drop/删除类动作一期不开放直接执行：消息里出现即拒绝。
- 超时自动取消：确认窗 30 分钟 / 收集草稿闲置 2 小时，过期即作废草稿、不写任何数据。
- 全程走真实内部接口，工具调用事件与 agent-core 一致（tool_call/tool_result）。
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid
from typing import Any, Callable

import httpx

# 本文件常被验收脚本按文件路径装载（importlib.spec_from_file_location），
# 此时 agent-service 目录不在 sys.path 上；wizard 是同目录兄弟模块，先补路径。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in __import__("sys").path:
    __import__("sys").path.insert(0, _HERE)
import wizard  # noqa: E402

KAFKA_BROKERS = os.environ.get(
    "AGENT_KAFKA_BROKERS", "kafka.dataworks-test-kafka.svc.cluster.local:9092")

_SESSIONS: dict[str, dict] = {}
_LOCK = threading.Lock()

TYPE_WORDS = [
    ("clickhouse", "ClickHouse", "CLICKHOUSE"),
    ("postgresql", "PostgreSQL", "POSTGRESQL"),
    ("postgres", "PostgreSQL", "POSTGRESQL"),
    ("sqlserver", "SQL Server", "SQLSERVER"),
    ("mysql", "MySQL", "MYSQL"),
    ("oracle", "Oracle", "ORACLE"),
    ("kafka", "Kafka", "KAFKA"),
    ("elasticsearch", "Elasticsearch", "ELASTICSEARCH"),
    ("rabbitmq", "RabbitMQ", "RABBITMQ"),
    ("hive", "Hive", "HIVE"),
]
TYPE_RE = re.compile("|".join(w for w, _, _ in TYPE_WORDS), re.I)
TYPE_MAP = {w: (label, db) for w, label, db in TYPE_WORDS}
TYPE_MAP["es"] = ("Elasticsearch", "ELASTICSEARCH")

DB_TYPES = {"PostgreSQL", "MySQL", "Oracle", "SQL Server", "ClickHouse", "Hive"}

SLOT_TITLES = {
    "source": "源",
    "target": "目标",
    "mapping": "字段映射",
    "runtime": "运行方式",
    "acceptance": "验收标准",
}


def is_create_intent(message: str) -> bool:
    text = message.strip()
    if not text:
        return False
    if any(w in text for w in ("查", "列表", "状态", "血缘", "质量", "多少条")):
        if not any(w in text for w in ("建", "创建", "新建")):
            return False
    if any(w in text for w in ("建任务", "创建任务", "新建任务", "建一个", "建一条",
                               "创建一条", "新建一条", "帮我建", "配一条", "配置一条")):
        return True
    return bool(re.search(r"(从|把).{0,30}(到|→|->)", text)) and "同步" in text


def try_handle(body, emit: Callable[[dict], None], client) -> bool:
    """命中建任务流程则处理并返回 True；否则交给 LLM。

    槽位不再只放进程内存：每轮从平台库加载草稿，处理完再写回，
    服务重启/换实例后仍能从「确认执行」那一步继续。
    超时口径（REQ-AGENT-WIZCONFIRM-1）：确认窗/收集窗过期即自动取消，
    草稿行删除（`_delete_state`），本轮不执行任何写入。
    """
    sid = body.sessionId
    with _LOCK:
        st = _SESSIONS.get(sid)
    if st is None:
        st = _load_state(client, sid)
    if st is not None:
        # 超时判定在读到草稿时立即做：确认窗/收集窗过期 → 取消 + 删草稿，不落任何写入
        expired = wizard.draft_expired(st)
        if expired == "confirm":
            st["_dropped"] = True
            _drop(sid, st)
            _delete_state(client, sid)
            _reply(emit, "确认窗超时，本次写操作已**自动取消**，没有创建或运行任何任务，"
                         "草稿也已清除。需要的话重新说一遍，我再走一遍确认。")
            return True
        if expired == "draft":
            st["_dropped"] = True
            _drop(sid, st)
            # 无论有没有新意图，过期草稿行都要删——留着下一轮还会读到僵尸草稿
            _delete_state(client, sid)
            if is_create_intent(body.message):
                # 新意图不背旧账：开新向导（权限闸在新建分支里）
                st = None
            else:
                _reply(emit, "上一次对话草稿闲置超时已自动取消（未写入任何数据）。"
                             "需要建任务直接说需求即可。")
                return True
    if st is None:
        if not is_create_intent(body.message):
            return False
        # 写权限面跟随本人：进向导前先确认本人有建改权限，
        # 别让 VIEWER 填完五个槽才被 Java 拒绝
        ok, why = _permission_gate(body, client)
        if not ok:
            _reply(emit, why)
            return True
        st = {"sessionId": sid, "stage": "collect", "asking": "source",
              "slots": {}, "tenant": body.tenantId, "user": body.userId,
              "projectId": body.projectId, "_dropped": False, "_meta": {}}
    else:
        # P2-29：已有草稿只允许原作者在同一租户内继续；否则 ignore，交给正常流程
        if (st.get("user") and body.userId and st.get("user") != body.userId) \
                or (st.get("tenant") and body.tenantId and st.get("tenant") != body.tenantId):
            return False
    try:
        return _handle(st, body, emit, client)
    finally:
        with _LOCK:
            if st.get("_dropped"):
                _SESSIONS.pop(sid, None)
            else:
                _SESSIONS[sid] = st
        if st.get("_dropped"):
            _delete_state(client, sid)
        else:
            _save_state(client, st)


def _load_state(client, sid: str):
    try:
        row = client.get("/api/internal/agent/drafts/" + sid, {})
        if not row or row.get("stage") not in ("collect", "confirm"):
            return None
        slots = row.get("slots") or "{}"
        if isinstance(slots, str):
            slots = json.loads(slots)
        slots = dict(slots or {})
        # 时效元数据寄生在槽位 JSON 的保留键里（草稿表只存 stage/slots/plan 三列，
        # 不为超时再加列）；加载时拆出来，保存时再放回去
        meta = slots.pop("_wizard", None) or {}
        if isinstance(meta, str):
            meta = json.loads(meta or "{}")
        return {"sessionId": sid, "stage": row.get("stage") or "collect",
                "asking": "source", "slots": slots, "plan": row.get("plan"),
                # P2-29：草稿带回 owner，确认前必须校验，不能只凭 sessionId 认领
                "tenant": row.get("tenantId") or "default",
                "user": row.get("username") or "", "projectId": None,
                "_meta": dict(meta), "_dropped": False}
    except Exception:
        return None


def _save_state(client, st: dict) -> None:
    try:
        payload = dict(st.get("slots") or {})
        payload["_wizard"] = dict(st.get("_meta") or {})
        client.put("/api/internal/agent/drafts/" + st["sessionId"], {
            "stage": st.get("stage"),
            "slots": payload,
            "plan": st.get("plan"),
        })
    except Exception:
        pass


def _permission_gate(body, client) -> tuple[bool, str]:
    """写权限面跟随本人（DELEGATE 口径）：只认使用者本人的角色，不从对话推断。

    对话向导是**直接执行**的写操作，比申请类更敏感：即便平台全局开关在 SYSTEM，
    本向导仍按「本人有建改权限才可用」判定（Java 侧 sync:write 仍是最终闸，
    这里提前拦是为了不让用户填完五个槽才被拒）。
    """
    try:
        data = client.get("/api/internal/agent/identity",
                          {"projectId": getattr(body, "projectId", None)})
    except Exception as e:  # noqa: BLE001 —— 身份拿不到按拒绝（与端口同一口径）
        return False, (f"暂时无法确认你的权限（身份接口异常：{type(e).__name__}），"
                       "对话建任务已拒绝——写权限面跟随本人，宁可多问一次也不冒写。")
    role = str((data or {}).get("role") or "").strip().upper()
    if not role:
        return False, ("你的账号没有绑定有效角色，对话建任务已拒绝"
                       "（写权限面跟随本人）。请先在平台获得数据开发/管理员角色。")
    if role not in wizard.WRITE_ROLES:
        return False, (f"你当前角色是 {role}，没有任务建改权限；助手写权限面跟随你本人，"
                       "不会超过你自己的权限。要同步数据可以让我发起变更申请（平台审批），"
                       "或请管理员为你添加数据开发角色。")
    return True, ""


def _delete_state(client, sid: str) -> None:
    try:
        client.delete("/api/internal/agent/drafts/" + sid)
    except Exception:
        pass


SLOT_ORDER = ("source", "target", "mapping", "runtime", "acceptance")


def _handle(st: dict, body, emit: Callable[[dict], None], client) -> bool:
    sid = st["sessionId"]
    msg = body.message.strip()
    if st["stage"] == "done":
        _drop(sid, st)
        return False
    if _is_cancel(msg):
        _drop(sid, st)
        _reply(emit, "已取消，没有创建或运行任何任务；草稿已清除，没有落任何数据。")
        return True

    if st["stage"] == "confirm":
        return _handle_confirm(st, body, emit, client)

    wizard.touch(st, "collect")
    hit = wizard.forbidden_hit(msg)
    if hit:
        # 一期不开放 drop/删除：拒绝该意图，草稿保留（用户往往只是顺口带了删除诉求）
        _reply(emit, wizard.refuse_message(f"检出违禁动作「{hit}」")
               + "\n当前建任务草稿保留；要继续请把槽位信息接着给我。")
        return True

    st["tenant"], st["user"] = body.tenantId, body.userId
    st["projectId"] = body.projectId
    parsed = _parse(msg, st, emit, client)
    for key, value in parsed.items():
        if isinstance(value, dict) and isinstance(st["slots"].get(key), dict):
            st["slots"][key].update(value)
        else:
            st["slots"][key] = value
    _validate(st, emit, client)

    missing = [k for k in SLOT_ORDER if not _slot_ok(st, k)]
    if missing:
        st["asking"] = missing[0]
        _reply(emit, _ask_text(st, missing, parsed))
        return True

    st["stage"] = "confirm"
    st["asking"] = ""          # 确认阶段不再有「裸回答兜底」，防误改槽
    wizard.touch(st, "confirm")
    _emit_confirm(st, emit)
    _reply(emit, _plan_text(st))
    return True


def _handle_confirm(st: dict, body, emit: Callable[[dict], None], client) -> bool:
    """确认阶段只认三类消息：执行 / 改槽回填 / （取消在上层已处理）。

    「改槽回填才执行」：确认阶段直接说修改内容即可（如「运行方式流式」），
    槽位就地生效并重新四栏回显；随后「确认执行」用的是**回填后的槽位**。
    认不出的消息一律引导，绝不执行（老行为是干等，口径不变但不写数据）。
    """
    msg = body.message.strip()
    hit = wizard.forbidden_hit(msg)
    if hit:
        _reply(emit, wizard.refuse_message(f"确认内容里检出违禁动作「{hit}」")
               + "\n确认草稿还在；要继续执行请回复「确认执行」。")
        return True
    if _is_confirm(msg):
        # 执行前再走一次本人权限闸：中途角色可能被调整，权限面始终跟随本人
        ok, why = _permission_gate(body, client)
        if not ok:
            _reply(emit, why)
            return True
        _execute(st, emit)
        return True
    parsed = _parse(msg, st, emit, client, fallback=False)
    changed = {k: v for k, v in parsed.items() if k in SLOT_TITLES}
    if not changed:
        _reply(emit, "这条消息里没识别出要改的槽位。要执行请回复「确认执行」；放弃回复「取消」；"
                     "要改哪项直接说（例如「验收 200 行」「运行方式批」「目标表 sync_test.orders2」）。")
        return True
    for key, value in changed.items():
        if isinstance(value, dict) and isinstance(st["slots"].get(key), dict):
            st["slots"][key].update(value)
        else:
            st["slots"][key] = value
    _validate(st, emit, client)
    missing = [k for k in SLOT_ORDER if not _slot_ok(st, k)]
    if missing:
        # 改槽后缺配置：退回收集窗追问，不把不完整的确认态留着
        st["stage"] = "collect"
        st["asking"] = missing[0]
        wizard.touch(st, "collect")
        _reply(emit, ("槽位已修改（" + "、".join(SLOT_TITLES[k] for k in changed)
                      + "），还缺以下配置：\n") + _ask_text(st, missing, changed))
        return True
    wizard.touch(st, "confirm")
    _emit_confirm(st, emit)
    _reply(emit, "槽位已更新，最新四栏回显（仍未写入任何数据）：\n\n" + _plan_text(st))
    return True


def _emit_confirm(st: dict, emit: Callable[[dict], None]) -> None:
    """结构化四栏回显事件：前端可渲染成确认卡片；文本回复仍由 _plan_text 给出。"""
    emit({"type": "wiz_confirm", "view": _view(st)})


def _view(st: dict) -> dict:
    """从槽位构造 对象|动作|影响|回滚 四栏视图（域内文案在这里，结构在 wizard）。"""
    s, t = st["slots"]["source"], st["slots"]["target"]
    mp = st["slots"]["mapping"]
    rt = st["slots"]["runtime"]["jobMode"]
    ac = st["slots"]["acceptance"]["desc"]
    fields = "全字段" if mp.get("mode") == "all" else \
        ", ".join(f"{f['name']}:{f['type']}" for f in mp.get("fields", []))
    src = f"Kafka(topic={s['topic']})" if s.get("type") == "Kafka" else \
        f"{s.get('datasourceName')}.{s.get('table')}"
    tgt = f"Kafka(topic={t['topic']})" if t.get("type") == "Kafka" else \
        f"{t.get('datasourceName')}.{t.get('table')}"
    object_ = f"同步任务（尚未创建）：{src} → {tgt}"
    action = f"创建同步任务并立即提交运行（{rt}，映射 {fields}，并行度 1）"
    impact = [
        f"目标端：{tgt}，只做写入，不修改源端。",
        "集群资源：约 1 核 / 2Gi，作业运行期间占用。",
        "若目标表已有相同主键，可能因主键冲突失败（不会自动删表）。",
        f"验收标准：{ac}。",
    ]
    rollback = [
        "停止：删除该 k8s Job，已写入目标端的数据不会回滚。",
        "任务：删除任务需人工申请（对话助手一期不开放删除/drop 直接执行）。",
        "数据：目标表清空需人工确认后处理。",
    ]
    slot_items = [{"key": k, "title": SLOT_TITLES[k], "value": _slot_brief(st["slots"][k])}
                  for k in SLOT_ORDER if k in st["slots"] and _slot_ok(st, k)]
    return wizard.make_view(op="create_sync_task", object_=object_, action=action,
                            impact=impact, rollback=rollback, slots=slot_items,
                            expires_in=wizard.CONFIRM_TTL_SECONDS)


def _parse(msg: str, st: dict, emit, client, fallback: bool = True) -> dict:
    """从一句自然语言里抽槽位。`fallback=False` 用于确认阶段：
    只认带明确指向的修改（表/映射/运行方式/验收…），
    绝不把寒暄或跑题消息兜底成「验收描述」——误改槽比不改槽更糟。"""
    slots = st["slots"]
    out: dict[str, Any] = {}
    asking = st.get("asking") if fallback else ""

    # ---- 类型：按出现顺序，结合“源/目标”标签 ----
    found = []
    for m in TYPE_RE.finditer(msg):
        label, db = TYPE_MAP[m.group(0).lower()]
        before = msg[max(0, m.start() - 8):m.start()].lower()
        role = "target" if ("目标" in before or "sink" in before) \
            else "source" if ("源" in before or "source" in before) else ""
        found.append({"type": label, "db": db, "role": role, "pos": m.start()})

    if not slots.get("source", {}).get("type") and not slots.get("target", {}).get("type"):
        unlabeled = [f for f in found if not f["role"]]
        labeled_src = [f for f in found if f["role"] == "source"]
        labeled_tgt = [f for f in found if f["role"] == "target"]
        if labeled_src:
            out.setdefault("source", {})["type"] = labeled_src[0]["type"]
        if labeled_tgt:
            out.setdefault("target", {})["type"] = labeled_tgt[0]["type"]
        rest = [f for f in unlabeled if f["type"] not in (
            out.get("source", {}).get("type"), out.get("target", {}).get("type"))]
        if not out.get("source", {}).get("type") and rest:
            out.setdefault("source", {})["type"] = rest.pop(0)["type"]
        if not out.get("target", {}).get("type") and rest:
            out.setdefault("target", {})["type"] = rest.pop(0)["type"]
    else:
        # 补缺失的一侧：如已有 source=Kafka，用户答“MySQL”
        for f in found:
            side = "target" if slots.get("source", {}).get("type") else "source"
            if not slots.get(side, {}).get("type"):
                out.setdefault(side, {})["type"] = f["type"]
                break

    # 单独回答类型（例如问“目标用什么类型”时只回 MySQL）
    if not found and asking in ("source", "target") and msg.upper() in (
            "MYSQL", "POSTGRESQL", "KAFKA", "ORACLE", "CLICKHOUSE"):
        out.setdefault(asking, {})["type"] = TYPE_MAP[msg.lower()][0]

    # ---- 表 / topic ----
    topic = _search(r"(?:topic|主题|队列)\s*(?:是|为|[:：=])?\s*([A-Za-z0-9_.\-]+)", msg)
    if topic:
        for side in ("source", "target"):
            if _type_of(st, out, side) == "Kafka" and not slots.get(side, {}).get("topic"):
                out.setdefault(side, {})["topic"] = topic
                break
    src_table = _search(r"(?:源表|来源表|读取表|source\s*table)\s*[:：=]?\s*([A-Za-z0-9_.]+)", msg, re.I)
    tgt_table = _search(r"(?:目标表|目标端表|写入表|sink\s*table)\s*[:：=]?\s*([A-Za-z0-9_.]+)", msg, re.I)
    if src_table:
        out.setdefault("source", {})["table"] = src_table
    if tgt_table:
        out.setdefault("target", {})["table"] = tgt_table
    if not src_table and not tgt_table:
        for m in re.finditer(r"(?<!目标)(?<!目标端)表\s*[:：=]?\s*([A-Za-z0-9_.]+)", msg):
            side = "target" if not _slot_ok(st, "target") else "source"
            out.setdefault(side, {}).setdefault("table", m.group(1))
            break

    # ---- 数据源：消息里点名的数据源名 ----
    ds_names = [n for n in _datasource_names(st, client, emit) if n.lower() in msg.lower()]
    if ds_names:
        if "目标" in msg or "sink" in msg.lower():
            side = "target"
        elif "源" in msg or "source" in msg.lower():
            side = "source"
        else:
            side = asking if asking in ("source", "target") else (
                "source" if not _slot_ok(st, "source") else "target")
        out.setdefault(side, {})["datasourceName"] = ds_names[0]

    # ---- 映射 ----
    if re.search(r"全字段|全部字段|所有字段|all\s*fields", msg, re.I):
        out["mapping"] = {"mode": "all"}
    else:
        m = re.search(r"(?:映射|字段)\s*[:：=]?\s*(.+)$", msg)
        if m:
            fields = []
            for tok in re.split(r"[,，、\s]+", m.group(1).strip("。；; ")):
                if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?::(?:int|integer|long|bigint|string|double|float|boolean|date|timestamp))?", tok, re.I):
                    name, _, typ = tok.partition(":")
                    fields.append({"name": name, "type": (typ or "string").lower()})
            if fields:
                out["mapping"] = {"mode": "fields", "fields": fields}

    # ---- 运行方式 ----
    if re.search(r"流式|实时|streaming", msg, re.I):
        out["runtime"] = {"jobMode": "STREAMING"}
    elif re.search(r"批量|批模式|全量|batch", msg, re.I):
        out["runtime"] = {"jobMode": "BATCH"}

    # ---- 验收 ----
    n = _search(r"(?:预期|验收)?\s*(\d+)\s*行", msg)
    if n:
        out["acceptance"] = {"expectedRows": int(n), "desc": f"目标端 {n} 行"}
    elif re.search(r"行数一致|数量一致|条数一致", msg):
        out["acceptance"] = {"expectedRows": None, "desc": "源/目标行数一致"}

    # 当前提问槽位的裸回答兜底
    if asking in ("source", "target") and msg.upper() in ("KAFKA", "MYSQL", "POSTGRESQL"):
        out.setdefault(asking, {})["type"] = TYPE_MAP[msg.lower()][0]
    if asking == "runtime" and "runtime" not in out:
        if re.search(r"流|实时", msg):
            out["runtime"] = {"jobMode": "STREAMING"}
        elif re.search(r"批|全量", msg):
            out["runtime"] = {"jobMode": "BATCH"}
    if asking == "acceptance" and "acceptance" not in out and len(msg) <= 60:
        out["acceptance"] = {"expectedRows": None, "desc": msg}
    return out


def _validate(st: dict, emit, client) -> None:
    slots = st["slots"]
    for side in ("source", "target"):
        slot = slots.get(side) or {}
        typ = slot.get("type")
        if not typ:
            continue
        # 用户点名了数据源时每次都要重新匹配：纠正名称后不能沿用上一次的结果
        if slot.get("ok") and slot.get("datasourceId") and not slot.get("datasourceName"):
            continue
        if typ == "Kafka":
            if slot.get("topic"):
                slot["ok"] = True
            continue
        ds = _resolve_datasource(st, client, emit, typ, slot.get("datasourceName"))
        if ds:
            slot["datasourceId"] = ds["id"]
            slot["datasourceName"] = ds["name"]
            slot["datasourceType"] = ds["type"]
            slot["ok"] = bool(slot.get("table"))
        else:
            slot["ok"] = False
            # 点名的数据源不存在：清掉上一轮可能绑定的错误 id，避免带着它执行
            if slot.get("datasourceName"):
                slot["datasourceId"] = None


def _resolve_datasource(st, client, emit, typ, name=None):
    db_type = next((db for _, label, db in TYPE_WORDS if label == typ), typ.upper())
    call_id = uuid.uuid4().hex[:10]
    started = time.time()
    emit({"type": "tool_call", "id": call_id, "name": "list_datasources",
          "toolId": "datasource.list", "module": "datasource",
          "args": {"type": db_type}})
    try:
        data = client.get("/api/internal/agent/datasources",
                          {"type": db_type, "projectId": st["projectId"]})
        rows = data.get("datasources", [])
        ms = int((time.time() - started) * 1000)
        emit({"type": "tool_result", "id": call_id, "name": "list_datasources",
              "toolId": "datasource.list", "module": "datasource", "ok": True,
              "durationMs": ms, "summary": f"{len(rows)} 个 {typ} 数据源"})
    except Exception as e:  # noqa: BLE001
        emit({"type": "tool_result", "id": call_id, "name": "list_datasources",
              "toolId": "datasource.list", "module": "datasource", "ok": False,
              "durationMs": int((time.time() - started) * 1000),
              "summary": f"{type(e).__name__}: {e}"})
        return None
    if name:
        # 用户明确点名了数据源：没匹配上就返回 None，让流程追问，
        # 不能退回到「唯一候选」——那会把 prod_pg2 静默换成 prod_pg 执行。
        for row in rows:
            if str(row.get("name", "")).lower() == name.lower():
                return row
        return None
    if len(rows) == 1:
        return rows[0]
    return None


def _datasource_names(st, client, emit) -> list[str]:
    names: list[str] = []
    for typ in ("PostgreSQL", "MySQL", "Oracle", "ClickHouse", "Hive", "Kafka"):
        db_type = next((db for _, label, db in TYPE_WORDS if label == typ), typ.upper())
        try:
            data = client.get("/api/internal/agent/datasources",
                              {"type": db_type, "projectId": st["projectId"]})
            names += [str(r.get("name")) for r in data.get("datasources", [])]
        except Exception:  # noqa: BLE001
            continue
    return names


def _slot_ok(st: dict, key: str) -> bool:
    slot = st["slots"].get(key) or {}
    if key == "source":
        if not slot.get("type"):
            return False
        if slot["type"] == "Kafka":
            return bool(slot.get("topic"))
        return bool(slot.get("datasourceId") and slot.get("table"))
    if key == "target":
        if not slot.get("type"):
            return False
        if slot["type"] == "Kafka":
            return bool(slot.get("topic"))
        return bool(slot.get("datasourceId") and slot.get("table"))
    if key == "mapping":
        if slot.get("mode") == "all":
            return _type_of(st, {}, "source") != "Kafka"
        return slot.get("mode") == "fields" and bool(slot.get("fields"))
    if key == "runtime":
        return slot.get("jobMode") in ("BATCH", "STREAMING")
    if key == "acceptance":
        return bool(slot.get("desc"))
    return False


def _type_of(st: dict, extra: dict, side: str) -> str:
    return (extra.get(side, {}) or {}).get("type") or \
        (st["slots"].get(side, {}) or {}).get("type") or ""


def _ask_text(st: dict, missing: list[str], parsed: dict) -> str:
    lines = []
    slot = st["slots"]
    if "source" in missing:
        s = slot.get("source") or {}
        if s.get("type") in DB_TYPES and not s.get("datasourceId"):
            lines.append(f"源端：{s['type']} 没匹配到数据源。请在消息里给出数据源名称，或先到「数据源」页创建。")
        elif s.get("type") == "Kafka" and not s.get("topic"):
            lines.append("源端：Kafka 的 topic 是什么？")
        elif s.get("type") and s.get("type") != "Kafka" and not s.get("table"):
            lines.append(f"源端：{s['type']} 读哪张表？（例如：源表 users）")
        elif not s.get("type"):
            lines.append("源端：从哪来？给类型和连接，例如「源 Kafka」或「源 PostgreSQL，数据源 dataworks_pg」。")
    if "target" in missing:
        t = slot.get("target") or {}
        if t.get("type") in DB_TYPES and not t.get("datasourceId"):
            lines.append(f"目标端：{t['type']} 用哪个数据源？给出数据源名称。")
        elif t.get("type") and t.get("type") != "Kafka" and not t.get("table"):
            lines.append(f"目标端：{t['type']} 写入哪张表？（例如：目标表 sync_test.orders）")
        elif not t.get("type"):
            lines.append("目标端：到哪去？给类型和数据源，例如「目标 MySQL，数据源 test_mysql」。")
    if "mapping" in missing:
        lines.append("字段映射：全字段，还是列出字段？例如「字段 id:int,name:string,amount:bigint」。")
    if "runtime" in missing:
        hint = "Kafka 源一般用流式" if _type_of(st, parsed, "source") == "Kafka" else "一次性同步用批"
        lines.append(f"运行方式：流式（STREAMING）还是批（BATCH）？{hint}。")
    if "acceptance" in missing:
        lines.append("验收标准：预期目标端多少行，或「行数一致」？")
    briefs = [f"{SLOT_TITLES[k]}={_slot_brief(st['slots'][k])}"
              for k in ("source", "target", "mapping", "runtime", "acceptance")
              if k in st["slots"] and _slot_ok(st, k)]
    head = ("已记录：" + "；".join(briefs) + "\n") if briefs else ""
    return ("好的。\n" if parsed else "") + head + "\n".join(lines)


def _slot_brief(slot: dict) -> str:
    if slot.get("type"):
        if slot["type"] == "Kafka":
            return f"Kafka topic={slot.get('topic', '?')}"
        return f"{slot['type']} {slot.get('datasourceName', '?')}.{slot.get('table', '?')}"
    if slot.get("mode"):
        return "全字段" if slot["mode"] == "all" else ",".join(f["name"] for f in slot["fields"])
    if slot.get("jobMode"):
        return slot["jobMode"]
    return slot.get("desc", "")


def _plan_text(st: dict) -> str:
    """四栏回显文本：结构事实由 _view/wizard 给，这里只补一张 ASCII 任务图。"""
    view = _view(st)
    s, t = st["slots"]["source"], st["slots"]["target"]
    mp = st["slots"]["mapping"]
    rt = st["slots"]["runtime"]["jobMode"]
    ac = st["slots"]["acceptance"]["desc"]
    fields = "全字段" if mp.get("mode") == "all" else \
        ", ".join(f"{f['name']}:{f['type']}" for f in mp.get("fields", []))
    src = f"Kafka(topic={s['topic']})" if s.get("type") == "Kafka" else \
        f"{s.get('datasourceName')}.{s.get('table')}"
    tgt = f"Kafka(topic={t['topic']})" if t.get("type") == "Kafka" else \
        f"{t.get('datasourceName')}.{t.get('table')}"
    graph = f"""任务图（尚未执行）：

  源   {src}
       │
  映射 {fields}
       │
  目标 {tgt}
  运行 {rt}，并行度 1
  验收 {ac}"""
    return wizard.render_text(view, graph)


def _execute(st: dict, emit) -> None:
    s, t = st["slots"]["source"], st["slots"]["target"]
    mp = st["slots"]["mapping"]
    mode = st["slots"]["runtime"]["jobMode"]
    code = "chat_%s_%s_%s" % (s.get("type", "src").lower(), t.get("type", "tgt").lower(),
                              time.strftime("%m%d%H%M%S"))
    fields = mp.get("fields") or []
    source_config: dict[str, Any] = {}
    sink_config: dict[str, Any] = {}
    src_ds = tgt_ds = None

    if s.get("type") == "Kafka":
        source_config = {
            "bootstrap.servers": KAFKA_BROKERS, "topic": s["topic"],
            "consumer.group": code + "_cg", "start_mode": "earliest",
            "format": "json",
            "schema": {"fields": {f["name"]: f["type"] for f in fields}},
        }
    else:
        src_ds = s.get("datasourceId")
        source_config = {"query": f"select * from {s['table']}"}
        if mp.get("mode") == "fields":
            source_config["mapping"] = {"include": [f["name"] for f in fields]}

    if t.get("type") == "Kafka":
        sink_config = {"bootstrap.servers": KAFKA_BROKERS, "topic": t["topic"],
                       "format": "json"}
    else:
        tgt_ds = t.get("datasourceId")
        table = t["table"]
        sink_config = {"table": table}
        if "." in table:
            db, _, tb = table.partition(".")
            sink_config = {"database": db, "table": tb}

    body = {
        "projectId": str(st["projectId"]), "code": code,
        "name": f"Agent 对话创建：{s.get('type')}→{t.get('type')}",
        "sourceType": s["type"], "sourceConfig": source_config,
        "sourceDatasourceId": src_ds,
        "sinkType": t["type"], "sinkConfig": sink_config,
        "sinkDatasourceId": tgt_ds,
        "jobMode": mode, "parallelism": 1,
    }

    call_id = uuid.uuid4().hex[:10]
    emit({"type": "tool_call", "id": call_id, "name": "create_task",
          "toolId": "platform.create_task", "module": "sync",
          "args": {"code": code, "source": s.get("type"), "sink": t.get("type")}})
    started = time.time()
    try:
        resp = _post(st, "/api/internal/agent/write/tasks", body)
    except Exception as e:  # noqa: BLE001
        emit({"type": "tool_result", "id": call_id, "name": "create_task",
              "toolId": "platform.create_task", "module": "sync", "ok": False,
              "durationMs": int((time.time() - started) * 1000),
              "summary": f"{type(e).__name__}: {e}"})
        _reply(emit, f"创建任务失败：{e}\n没有运行任何作业。")
        _drop(st["sessionId"], st)
        return
    emit({"type": "tool_result", "id": call_id, "name": "create_task",
          "toolId": "platform.create_task", "module": "sync", "ok": True,
          "durationMs": int((time.time() - started) * 1000),
          "summary": f"id={resp['id']} code={resp['code']}"})
    task_id = resp["id"]

    call_id = uuid.uuid4().hex[:10]
    emit({"type": "tool_call", "id": call_id, "name": "run_task",
          "toolId": "platform.run_task", "module": "sync", "args": {"id": task_id}})
    started = time.time()
    try:
        ran = _post(st, f"/api/internal/agent/write/tasks/{task_id}/run", {})
    except Exception as e:  # noqa: BLE001
        emit({"type": "tool_result", "id": call_id, "name": "run_task",
              "toolId": "platform.run_task", "module": "sync", "ok": False,
              "durationMs": int((time.time() - started) * 1000),
              "summary": f"{type(e).__name__}: {e}"})
        _reply(emit, f"任务已创建（id={task_id}），但运行失败：{e}\n可稍后重试，或到任务页检查配置。")
        _drop(st["sessionId"], st)
        return
    emit({"type": "tool_result", "id": call_id, "name": "run_task",
          "toolId": "platform.run_task", "module": "sync", "ok": True,
          "durationMs": int((time.time() - started) * 1000),
          "summary": f"job={ran['jobName']}"})

    live = "PENDING"
    for _ in range(8):
        time.sleep(3)
        try:
            status = _get(st, "/api/internal/agent/tasks/status", {"id": task_id})
            live = status.get("jobLiveStatus") or live
        except Exception:  # noqa: BLE001
            break
        if live in ("RUNNING", "SUCCESS", "FAILED"):
            break

    _reply(emit, f"""已执行：

  任务 id  {task_id}
  任务编码 {code}
  作业名   {ran['jobName']}
  实时状态 {live}

验收：{st['slots']['acceptance']['desc']}
- 作业状态不等于验收通过；请到目标端核对行数与字段（M3 已用 MySQL 真实计数验证）。
- 需要停止请到「集成 → 同步任务」页面对该任务点停止（对话入口暂不开放停止）。

审计：本次建任务/运行已写入 platform.agent_tool_audit（租户 {st.get('tenant')}、用户 {st.get('user')}）。""")
    st["stage"] = "done"


def _post(st: dict, path: str, body: dict) -> dict:
    headers = {"X-Internal-Token": os.environ.get("PLATFORM_INTERNAL_TOKEN", ""),
               "X-Tenant-Id": st.get("tenant", "default"),
               "X-User-Id": st.get("user", "unknown"),
               "Content-Type": "application/json; charset=utf-8"}
    url = os.environ.get("BACKEND_URL", "http://127.0.0.1:30090").rstrip("/") + path
    with httpx.Client(timeout=60.0) as client:
        resp = client.post(url, json=body, headers=headers)
        if resp.status_code >= 400:
            raise RuntimeError(_error_text(resp))
        return resp.json()


def _get(st: dict, path: str, params: dict) -> dict:
    headers = {"X-Internal-Token": os.environ.get("PLATFORM_INTERNAL_TOKEN", ""),
               "X-Tenant-Id": st.get("tenant", "default"),
               "X-User-Id": st.get("user", "unknown")}
    url = os.environ.get("BACKEND_URL", "http://127.0.0.1:30090").rstrip("/") + path
    with httpx.Client(timeout=30.0) as client:
        resp = client.get(url, params=params, headers=headers)
        resp.raise_for_status()
        return resp.json()


def _error_text(resp) -> str:
    try:
        data = resp.json()
        return str(data.get("message") or data.get("detail") or data)
    except Exception:  # noqa: BLE001
        return resp.text[:300]


def _reply(emit, text: str) -> None:
    emit({"type": "delta", "text": text})
    emit({"type": "final", "status": "success", "text": text})


def _is_confirm(msg: str) -> bool:
    """只有明确的肯定指令才算确认；否定句/疑问句绝不能触发执行。

    之前 ``确认执行 in msg`` 会让“先别确认执行”“不要确认执行”“为什么确认执行失败”
    全部落入执行分支，直接创建并运行同步任务。
    """
    text = msg.strip()
    if _is_cancel(text):
        return False
    if re.search(r"(不|别|勿|不要|先别|暂不|没有|不用).{0,6}(确认|执行|开始)", text):
        return False
    if re.search(r"(为什么|怎么|是否|能否|为何|啥时候).{0,8}(确认|执行|开始)", text):
        return False
    return bool(re.fullmatch(r"(确认执行|确认|执行|开始|可以|好的?)[。.!！]?", text)) or (
        "确认执行" in text and not re.search(r"(不|别|勿)", text))


def _is_cancel(msg: str) -> bool:
    return bool(re.search(r"取消|算了|不建了|放弃|不用了", msg))


def _search(pattern: str, text: str, flags=0):
    m = re.search(pattern, text, flags)
    return m.group(1) if m else None


def _drop(sid: str, st: dict | None = None) -> None:
    if st is not None:
        st["_dropped"] = True
    with _LOCK:
        _SESSIONS.pop(sid, None)
