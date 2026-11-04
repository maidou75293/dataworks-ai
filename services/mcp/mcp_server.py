import asyncio
import hashlib
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel

DORIS_HOST = os.environ.get("DORIS_HOST", "doris-fe.dw-dev.svc.cluster.local")
DORIS_PORT = os.environ.get("DORIS_PORT", "9030")
DORIS_USER = os.environ.get("DORIS_USER", "dataworks")
EMBEDDING_URL = os.environ.get("EMBEDDING_URL", "http://embedding.dw-dev.svc.cluster.local:8000")
GRAPH_PATH = os.environ.get("GRAPH_PATH", "/opt/mcp/graph.json")
GRAVITINO_URL = os.environ.get("GRAVITINO_URL", "http://gravitino.dw-dev.svc.cluster.local:8090")
GRAVITINO_METALAKE = os.environ.get("GRAVITINO_METALAKE", "dataworks")
TOOLS_DIR = os.environ.get("TOOLS_DIR", "/opt/mcp/tools")
AUDIT_PATH = os.environ.get("AUDIT_PATH", "/opt/mcp/audit.log")
MCP_TOKEN = os.environ.get("MCP_TOKEN", "")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "")
KEYCLOAK_REALM = os.environ.get("KEYCLOAK_REALM", "")
KEYCLOAK_CLIENT_ID = os.environ.get("KEYCLOAK_CLIENT_ID", "")
KEYCLOAK_CLIENT_SECRET = os.environ.get("KEYCLOAK_CLIENT_SECRET", "")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://127.0.0.1:30090")
PLATFORM_INTERNAL_TOKEN = os.environ.get("PLATFORM_INTERNAL_TOKEN", "")

app = FastAPI(title="DataWorks MCP Gateway", version="0.1")


def load_tools() -> List[Dict[str, Any]]:
    tools = []
    if os.path.isdir(TOOLS_DIR):
        for name in sorted(os.listdir(TOOLS_DIR)):
            if name.endswith(".json"):
                with open(os.path.join(TOOLS_DIR, name), encoding="utf-8") as f:
                    tools.append(json.load(f))
    return tools


def load_graph() -> Dict[str, Any]:
    if os.path.exists(GRAPH_PATH):
        with open(GRAPH_PATH, encoding="utf-8") as f:
            return json.load(f)
    return {"nodes": []}


_SECRET_KEYS = ("password", "secret", "token", "api_key", "apikey",
                "private_key", "accesskey", "secretkey", "authorization")


def _redact(value: Any, depth: int = 0) -> Any:
    if depth > 8:
        return "..."
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if any(s in str(k).lower() for s in _SECRET_KEYS):
                out[k] = "***"
            else:
                out[k] = _redact(v, depth + 1)
        return out
    if isinstance(value, list):
        return [_redact(v, depth + 1) for v in value[:50]]
    if isinstance(value, str) and len(value) > 160:
        return value[:160] + "..."
    return value


def audit(caller: str, tool: str, params: Dict[str, Any], status: str,
          duration_ms: float, approval: Optional[str] = None) -> None:
    # 审计日志会落到 stdout / 共享卷：嵌套的 sourceConfig.password 也必须脱敏
    safe_params = _redact(params or {})
    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "caller": caller,
        "tool": tool,
        "params": safe_params,
        "status": status,
        "duration_ms": round(duration_ms, 1),
        "approval": approval,
    }
    try:
        with open(AUDIT_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError:
        pass
    print(json.dumps(entry, ensure_ascii=False), flush=True)


def _keycloak_active(token: str) -> bool:
    if not (KEYCLOAK_URL and KEYCLOAK_REALM and KEYCLOAK_CLIENT_ID and KEYCLOAK_CLIENT_SECRET):
        return False
    data = urllib.parse.urlencode({
        "token": token,
        "client_id": KEYCLOAK_CLIENT_ID,
        "client_secret": KEYCLOAK_CLIENT_SECRET,
    }).encode()
    req = urllib.request.Request(
        f"{KEYCLOAK_URL.rstrip('/')}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token/introspect",
        data=data,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode())
        return bool(body.get("active"))
    except Exception as exc:
        print(f"keycloak introspection failed: {exc}", flush=True)
        return False


def check_auth(authorization: Optional[str]) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        if not MCP_TOKEN and not KEYCLOAK_URL:
            return "anonymous"
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1]
    if MCP_TOKEN and token == MCP_TOKEN:
        return "api-key"
    if _keycloak_active(token):
        return "keycloak"
    raise HTTPException(status_code=401, detail="invalid token")


def embed(texts: List[str], is_query: bool) -> List[List[float]]:
    payload = json.dumps({"texts": texts, "is_query": is_query}).encode()
    req = urllib.request.Request(
        EMBEDDING_URL.rstrip("/") + "/embed",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode())
    return body["embeddings"]


def _run_mysql(stmt: str, include_headers: bool = False, xml: bool = False) -> str:
    env = dict(os.environ)
    env["MYSQL_PWD"] = os.environ.get("DORIS_PASSWORD", "")
    cmd = [
        "mysql", "--protocol=TCP", "-h", DORIS_HOST, "-P", DORIS_PORT,
        "-u", DORIS_USER, "--batch", "--raw",
    ]
    if xml:
        # xml 模式能区分 SQL NULL 与字符串 'NULL'（batch 模式两者都输出 NULL）
        cmd.append("--xml")
    if not include_headers and not xml:
        cmd.append("-N")
    # Send SQL through stdin: large INSERTs exceed the OS argv limit with -e.
    proc = subprocess.run(cmd, input=stmt, env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=120)
    if proc.returncode != 0:
        err = proc.stderr.strip()[:500]
        # 语法错 / 列不存在 / 表不存在 / 函数不存在：是用户 SQL 的问题，按 400 返回原文，
        # 后端可以直接展示；其余（连接、权限、内部错误）仍按 500，由后端归一成通用文案。
        client_error = re.search(
            r"(ERROR 1064|ERROR 1105|ERROR 1054|ERROR 1146|ERROR 1305|Unknown column|"
            r"Unknown function|does not exist|doesn't exist|Current database is not set|"
            r"mismatched input|Syntax error)", err, re.I)
        if client_error:
            # Doris 的 1105 会把整个 token 列表打出来（几千字符）：只留 detailMessage 开头
            match = re.search(r"detailMessage\s*=\s*(.*)", err, re.S | re.I)
            message = match.group(1).strip() if match else err
            message = " ".join(message.split())
            if len(message) > 400:
                message = message[:400] + "…"
            # 位置信息在 stderr 的「at line N」里，别在提取 detailMessage 时丢掉
            at_line = re.search(r"at line\s+(\d+)", err, re.I)
            where = f"at line {at_line.group(1)}: " if at_line else ""
            raise HTTPException(status_code=400, detail=f"SQL_INVALID: {where}{message}")
        raise HTTPException(status_code=500, detail=err)
    return proc.stdout


def _code_only(sql: str) -> str:
    """去掉注释、字符串、反引号标识符，只留下可执行结构。"""
    out = []
    i = 0
    n = len(sql)
    while i < n:
        ch = sql[i]
        if ch in ("'", '"', "`"):
            quote = ch
            i += 1
            while i < n:
                if sql[i] == quote:
                    if i + 1 < n and sql[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append(" ")
        elif ch == "-" and i + 1 < n and sql[i + 1] == "-":
            i += 2
            while i < n and sql[i] != "\n":
                i += 1
            out.append(" ")
        elif ch == "/" and i + 1 < n and sql[i + 1] == "*":
            i += 2
            while i + 1 < n and not (sql[i] == "*" and sql[i + 1] == "/"):
                i += 1
            i = min(i + 2, n)
            out.append(" ")
        else:
            out.append(ch)
            i += 1
    return "".join(out)


# 查询接口只读：WITH 也能包住写操作，SELECT ... INTO OUTFILE 也会落盘，必须扫关键字。
_WRITE_WORDS = re.compile(
    r"(?is)\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|"
    r"load|export|outfile|into|rename|replace\s+into|set|use|kill|admin|copy|call|do|"
    r"merge|vacuum|analyze|lock)\b")


_READ_START = re.compile(r"(?is)^(select|with|show|desc|describe|explain)\b")
_WRITE_START = re.compile(
    r"(?is)^(insert|update|delete|create|drop|alter|truncate|merge|replace|grant|revoke|"
    r"set|use|load|export|install|uninstall|analyze|refresh|kill|call|begin|commit|rollback)\b")

# ---------- F-B（裁定乙，2026-11-02）：MCP 通道只读元数据白名单 ----------
# 业界主流口径：只读元数据放行，租户权限由后端 QueryController 按库归属兜底
# （D09/D10/V100 收口，后端 SHOW/DESC 口径不动）。这里把「语句形态」列入白名单，
# 而不是像 v450 那样放任意 SHOW 开头——v450 口径下 SHOW PROCESSLIST/CONFIG/VARIABLES
# 都能过，等于把别人的会话 SQL、集群配置透给持 MCP_TOKEN 的调用方（越权面）。
#   放：SHOW DATABASES/CATALOGS/TABLES/DATA [FROM|LIKE]、SHOW [FULL] COLUMNS/FIELDS/
#       INDEX/INDEXES/KEYS FROM、SHOW CREATE TABLE|DATABASE、SHOW PARTITIONS FROM、
#       SHOW TABLE STATUS、SHOW BACKENDS/FRONTENDS（建表副本数预检依赖，见 ENV-1 T4）、
#       SHOW ALTER TABLE COLUMN|ROLLUP（schema change 作业状态，TableDdlService 依赖）、
#       SHOW MATERIALIZED VIEWS FROM、DESC[RIBE] <表>、EXPLAIN + 只读语句本体。
#   拒：PROCESSLIST/GRANTS/GRANT/VARIABLES/STATUS/CONFIG/PLUGINS 等未列形态，
#       写操作、拼句（;）、EXPLAIN ANALYZE（会真正执行，与 metaKind 口径一致）。
_META_SHOW_PLAIN = {
    "databases", "catalogs", "backends", "frontends", "tables", "data",
}


def _meta_allowed(code: str) -> bool:
    """SHOW/DESC 只读元数据是否在放行形态白名单内。

    code 已经过 _code_only（注释、字符串、反引号标识符内容被剥掉），这里只认语句
    形态；形态词之后的残余内容仍要过 _WRITE_WORDS，防「合法开头 + 拼接风险词」。
    """
    t = code.lower().split()
    if not t:
        return False
    if t[0] in ("desc", "describe"):
        return len(t) >= 2  # desc <库>.<表> [列]；光秃秃的 desc 没有对象
    if t[0] != "show" or len(t) < 2:
        return False
    i = 1
    if t[i] == "full" and i + 1 < len(t) and t[i + 1] in ("columns", "fields", "tables"):
        i += 1
    kind = t[i]
    nxt = t[i + 1] if i + 1 < len(t) else ""
    scan_from = i + 1  # 残余风险词扫描起点
    if kind in _META_SHOW_PLAIN:
        ok = True
    elif kind in ("columns", "fields", "index", "indexes", "keys", "partitions"):
        ok = nxt in ("from", "in")
    elif kind == "table" and nxt == "status":
        ok = True
    elif kind == "create" and nxt in ("table", "database"):
        # 「create」是形态词（SHOW CREATE TABLE 纯读 DDL，v450 反而被写词扫描误杀，
        # ColdHotController 的表属性弹窗就断在这）；从形态词之后再扫风险词
        ok, scan_from = True, i + 3
    elif kind == "alter" and nxt == "table" and i + 2 < len(t) \
            and t[i + 2] in ("column", "rollup"):
        # 同上：SHOW ALTER TABLE COLUMN/ROLLUP 读的是 schema change / 物化视图作业状态
        ok, scan_from = True, i + 4
    elif kind == "materialized" and nxt == "views":
        ok = True
    else:
        ok = False
    if not ok:
        return False
    return not _WRITE_WORDS.search(" ".join(t[scan_from:]))


def _guard_code(code: str) -> None:
    """对「去注释/去字符串后的语句」做只读判定；不合规直接 raise。"""
    if _WRITE_START.match(code):
        raise HTTPException(status_code=400, detail="SQL_NOT_READ_ONLY")
    low = code.lower()
    if low.startswith("explain"):
        # EXPLAIN 不执行本体，但本体必须照样过同一个白名单；EXPLAIN ANALYZE 会真跑，拒
        parts = low.split(None, 1)
        body = parts[1].strip() if len(parts) > 1 else ""
        if not body or body.startswith("explain") or _WRITE_START.match(body):
            raise HTTPException(status_code=400, detail="SQL_NOT_READ_ONLY")
        _guard_code(body)
        return
    if not _READ_START.match(code):
        first = code.split()[0][:32]
        raise HTTPException(
            status_code=400,
            detail=f"SQL_INVALID: 无法识别的语句开头 '{first}'，请检查拼写"
                   "（查询应以 SELECT / WITH 开头）")
    if ";" in code:
        raise HTTPException(status_code=400, detail="SQL_NOT_READ_ONLY")
    if low.startswith(("select", "with")):
        if _WRITE_WORDS.search(code):
            raise HTTPException(status_code=400, detail="SQL_NOT_READ_ONLY")
        return
    if not _meta_allowed(code):
        raise HTTPException(
            status_code=400,
            detail="SQL_NOT_READ_ONLY: 元数据语句不在只读白名单（放行 SHOW "
                   "DATABASES/TABLES/COLUMNS/CREATE/PARTITIONS/BACKENDS 等只读形态与 "
                   "DESC/EXPLAIN；会话、配置、授权类语句请走平台管理接口）")


def _assert_read_only(sql: str) -> str:
    stmt = sql.strip().rstrip(";")
    code = _code_only(stmt).strip()
    if not code:
        raise HTTPException(status_code=400, detail="SQL_INVALID: SQL 为空")
    # v450 P1-4：语句拼写错误报 SQL_INVALID 而不是只读拒绝；F-B 之后 SHOW/DESC 系
    # 走显式白名单（见 _meta_allowed），不再「开头是 show 就放」。分号拼句在
    # _guard_code 里于「确认是已知语句开头」后判，保证拼错仍报 SQL_INVALID。
    _guard_code(code)
    return stmt


def _limit(stmt: str, max_rows: int) -> str:
    """外层强制 LIMIT：不能靠 `" limit " in sql` 判断，`LIKE '% limit %'`
    或调用方自带的超大 LIMIT 都能绕过，导致整个表被拉进内存。"""
    cap = max(1, min(int(max_rows), 1000))
    low = stmt.lower().lstrip()
    # v457 MT-P1-2：SHOW/DESC/EXPLAIN 是元数据语句，不能包成子查询
    # （`SELECT * FROM (show databases) ...` 必然语法错）。它们本身不返回大结果集。
    if low.startswith(("show", "desc", "describe", "explain")):
        return stmt
    # WITH 没有自带 LIMIT 时直接追加，兼容性最好；其他情况外包一层
    if low.startswith("with") and " limit " not in low:
        return f"{stmt} LIMIT {cap}"
    return f"SELECT * FROM ({stmt}) AS _dw_limited LIMIT {cap}"


def run_sql(sql: str, max_rows: int = 100) -> Dict[str, Any]:
    stmt = _limit(_assert_read_only(sql), max_rows)
    rows = [line.split("\t") for line in _run_mysql(stmt).splitlines() if line]
    return {"columns": [], "rows": rows, "row_count": len(rows)}


def run_sql_with_columns(sql: str, max_rows: int = 100) -> Dict[str, Any]:
    """查询并把结果转成「列 + 行」。

    v449 P2-1：以前用 batch 制表符输出，SQL NULL 会被渲染成字符串 "NULL"，
    前端无法区分 NULL 与真实的 'NULL'。改用 mysql --xml：xsi:nil="true" 才是 NULL。
    零行结果没有 <row>，回退到 batch 模式只为拿列名（不会再读数据）。
    """
    stmt = _limit(_assert_read_only(sql), max_rows)
    raw = _run_mysql(stmt, xml=True)
    try:
        root = ET.fromstring(raw.strip() or "<resultset/>")
    except ET.ParseError:
        raise HTTPException(status_code=502, detail="SQL_RESULT_UNPARSEABLE")
    nil = "{http://www.w3.org/2001/XMLSchema-instance}nil"
    rows: List[List[Any]] = []
    columns: List[str] = []
    for row in root.findall("row"):
        cells: List[Any] = []
        names: List[str] = []
        for field in row.findall("field"):
            names.append(field.get("name") or "")
            cells.append(None if field.get(nil) == "true" else (field.text or ""))
        if not columns:
            columns = names
        rows.append(cells)
    if not rows:
        header = _run_mysql(stmt, include_headers=True).splitlines()
        columns = header[0].split("\t") if header else []
    return {"columns": columns, "rows": rows, "row_count": len(rows)}


def run_write(sql: str) -> None:
    stmt = sql.strip().rstrip(";")
    if not re.match(r"(?is)^(insert|delete)\b", stmt) or ";" in stmt:
        raise HTTPException(status_code=400, detail="SQL_NOT_WRITABLE")
    _run_mysql(stmt)


def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def vector_literal(vec: List[float]) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in vec) + "]"


def kb_search(params: Dict[str, Any]) -> Dict[str, Any]:
    query = params["query"]
    kb_id = int(params.get("kb_id", 1))
    top_k = min(int(params.get("top_k", 5)), 50)
    acl_tags = params.get("acl_tags") or []
    vec = embed([query], bool(params.get("is_query", True)))[0]
    where = [f"is_current = true", f"kb_id = {kb_id}"]
    if acl_tags:
        tags = ",".join("'" + sql_escape(str(t)) + "'" for t in acl_tags)
        where.append(f"acl_tags IN ({tags})")
    sql = (
        "SELECT chunk_id, doc_id, "
        "REPLACE(REPLACE(title, '\\n', ' '), '\\t', ' ') AS title, "
        "REPLACE(REPLACE(content, '\\n', ' '), '\\t', ' ') AS content, "
        "content_hash, "
        f"inner_product_approximate(embedding, {vector_literal(vec)}) AS score "
        "FROM kb_dev.kb_chunk WHERE " + " AND ".join(where) +
        f" ORDER BY score DESC LIMIT {top_k}"
    )
    result = run_sql(sql, top_k)
    items = []
    for row in result["rows"]:
        items.append({
            "chunk_id": int(row[0]), "doc_id": int(row[1]),
            "title": row[2], "content": row[3],
            "contentHash": row[4] or "", "score": float(row[5]),
        })
    return {"results": items}


def kb_get_chunk(params: Dict[str, Any]) -> Dict[str, Any]:
    chunk_id = int(params["chunk_id"])
    sql = (
        "SELECT chunk_id, doc_id, "
        "REPLACE(REPLACE(title, '\\n', ' '), '\\t', ' ') AS title, "
        "REPLACE(REPLACE(content, '\\n', ' '), '\\t', ' ') AS content, "
        "acl_tags FROM kb_dev.kb_chunk "
        f"WHERE chunk_id = {chunk_id} AND is_current = true "
        "ORDER BY version DESC LIMIT 1"
    )
    result = run_sql(sql, 1)
    if not result["rows"]:
        raise HTTPException(status_code=404, detail="CHUNK_NOT_FOUND")
    row = result["rows"][0]
    return {
        "chunk_id": int(row[0]), "doc_id": int(row[1]), "title": row[2],
        "content": row[3], "acl_tags": [t for t in row[4].split(",") if t],
    }


def split_chunks(content: str, size: int, overlap: int) -> List[str]:
    size = max(100, size)
    overlap = max(0, min(overlap, size // 2))
    chunks, start = [], 0
    while start < len(content):
        chunks.append(content[start:start + size])
        start += size - overlap
    return [c for c in chunks if c.strip()]


def kb_ingest(params: Dict[str, Any]) -> Dict[str, Any]:
    kb_id = int(params["kb_id"])
    doc_id = int(params["doc_id"])
    version = int(params.get("version", 1))
    title = params.get("title", "")
    content = params.get("content", "")
    acl_tags = params.get("acl_tags") or []
    if not content.strip():
        raise HTTPException(status_code=400, detail="KB_EMPTY_CONTENT")
    chunks = split_chunks(content, int(params.get("chunk_size", 500)),
                          int(params.get("chunk_overlap", 50)))
    vectors = []
    for i in range(0, len(chunks), 64):
        vectors.extend(embed(chunks[i:i + 64], False))
    acl = ",".join(acl_tags)
    rows = []
    for idx, (chunk, vec) in enumerate(zip(chunks, vectors)):
        digest = hashlib.sha256(f"{doc_id}:{version}:{idx}".encode()).hexdigest()
        chunk_id = int(digest[:15], 16)
        content_hash = hashlib.sha256(chunk.encode("utf-8")).hexdigest()
        rows.append(
            f"({chunk_id},{version},{kb_id},{doc_id},true,"
            f"'{sql_escape(title)}','{sql_escape(chunk)}','{content_hash}',"
            f"{vector_literal(vec)},'{sql_escape(acl)}',now())"
        )
    # Replace the document's previous chunks (DEV semantics; versions remain in audit).
    run_write(f"DELETE FROM kb_dev.kb_chunk WHERE kb_id = {kb_id} AND doc_id = {doc_id}")
    for start in range(0, len(rows), 50):
        insert = (
            "INSERT INTO kb_dev.kb_chunk (chunk_id,version,kb_id,doc_id,is_current,"
            "title,content,content_hash,embedding,acl_tags,updated_at) VALUES "
            + ",".join(rows[start:start + 50])
        )
        run_write(insert)
    return {"doc_id": doc_id, "version": version, "chunk_count": len(rows), "status": "ok"}


def kb_delete(params: Dict[str, Any], approval: Optional[str]) -> Dict[str, Any]:
    if approval != "approved":
        raise HTTPException(status_code=409, detail="APPROVAL_REQUIRED")
    kb_id = int(params["kb_id"])
    doc_id = int(params["doc_id"])
    version = int(params["version"])
    sql = (
        f"DELETE FROM kb_dev.kb_chunk WHERE kb_id = {kb_id} "
        f"AND doc_id = {doc_id} AND version = {version}"
    )
    run_write(sql)
    return {"doc_id": doc_id, "version": version, "deleted_chunks": -1,
            "mode": "hard" if params.get("hard") else "soft"}


def doris_sql_query(params: Dict[str, Any]) -> Dict[str, Any]:
    sql = params["sql"]
    # Doris 对 `SELECT * AS alias` 的报错是 "Invalid call to ... getDataType() on
    # unbound object"，完全看不懂；这里提前给出可操作的提示。
    if re.search(r"(?is)\bselect\s+\*(?:\s*,\s*[^)]*?)?\s+as\b", sql):
        raise HTTPException(
            status_code=400,
            detail="SQL_INVALID: SELECT * 不能起别名。请改成 SELECT * FROM 表，"
                   "或用 SELECT COUNT(*) AS total 之类的聚合。",
        )
    return run_sql_with_columns(sql, int(params.get("max_rows", 100)))


def graph_search(params: Dict[str, Any]) -> Dict[str, Any]:
    query = params["query"].lower().strip()
    limit = int(params.get("limit", 5))
    tokens = [t for t in re.split(r"\s+", query) if t] or [query]
    nodes = load_graph().get("nodes", [])
    scored = []
    for node in nodes:
        text = json.dumps(node, ensure_ascii=False).lower()
        # Exact phrase wins; otherwise score each whitespace-separated token so a
        # multi-word query like "元数据 表结构 catalog" still matches.
        score = 10 + text.count(query) if query and query in text else 0
        score += sum(text.count(t) for t in tokens)
        if score:
            scored.append((score, node))
    scored.sort(key=lambda x: -x[0])
    return {"nodes": [n for _, n in scored[:limit]]}


def graph_get_node(params: Dict[str, Any]) -> Dict[str, Any]:
    nodes = load_graph().get("nodes", [])
    for node in nodes:
        if node.get("id") == params["id"]:
            return node
    raise HTTPException(status_code=404, detail="NODE_NOT_FOUND")


def _seg(value: Any) -> str:
    return urllib.parse.quote(str(value), safe="")


def _gravitino_get(path: str) -> Dict[str, Any]:
    url = GRAVITINO_URL.rstrip("/") + path
    # Gravitino negotiates versioned media types; a plain `Accept: application/json`
    # gets HTTP 406, so send no Accept header and let it use the default version.
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="ignore")[:300]
        print(f"gravitino GET {path} -> {exc.code}: {body}", flush=True)
        if exc.code == 404:
            raise HTTPException(status_code=404, detail="CATALOG_OBJECT_NOT_FOUND")
        raise HTTPException(status_code=502, detail="CATALOG_UNAVAILABLE")
    except Exception as exc:
        print(f"gravitino GET {path} failed: {exc}", flush=True)
        raise HTTPException(status_code=502, detail="CATALOG_UNAVAILABLE")


def _catalog_base(catalog: str) -> str:
    return (f"/api/metalakes/{_seg(GRAVITINO_METALAKE)}"
            f"/catalogs/{_seg(catalog)}")


def _catalog_type(catalog: str) -> str:
    body = _gravitino_get(_catalog_base(catalog))
    return str((body.get("catalog") or {}).get("type") or "").lower()


def catalog_list(params: Dict[str, Any]) -> Dict[str, Any]:
    body = _gravitino_get(f"/api/metalakes/{_seg(GRAVITINO_METALAKE)}/catalogs")
    return {
        "metalake": GRAVITINO_METALAKE,
        "catalogs": [i["name"] for i in body.get("identifiers", [])],
    }


def catalog_list_schemas(params: Dict[str, Any]) -> Dict[str, Any]:
    catalog = params["catalog"]
    body = _gravitino_get(_catalog_base(catalog) + "/schemas")
    return {"catalog": catalog,
            "schemas": [i["name"] for i in body.get("identifiers", [])]}


def catalog_list_tables(params: Dict[str, Any]) -> Dict[str, Any]:
    catalog, schema = params["catalog"], params["schema"]
    if _catalog_type(catalog) != "relational":
        return {"catalog": catalog, "schema": schema, "tables": []}
    body = _gravitino_get(_catalog_base(catalog) + f"/schemas/{_seg(schema)}/tables")
    return {"catalog": catalog, "schema": schema,
            "tables": [i["name"] for i in body.get("identifiers", [])]}


def catalog_list_filesets(params: Dict[str, Any]) -> Dict[str, Any]:
    catalog, schema = params["catalog"], params["schema"]
    if _catalog_type(catalog) != "fileset":
        return {"catalog": catalog, "schema": schema, "filesets": []}
    body = _gravitino_get(_catalog_base(catalog) + f"/schemas/{_seg(schema)}/filesets")
    return {"catalog": catalog, "schema": schema,
            "filesets": [i["name"] for i in body.get("identifiers", [])]}


def catalog_describe_fileset(params: Dict[str, Any]) -> Dict[str, Any]:
    catalog, schema, fileset = params["catalog"], params["schema"], params["fileset"]
    body = _gravitino_get(
        _catalog_base(catalog) + f"/schemas/{_seg(schema)}/filesets/{_seg(fileset)}")
    detail = body.get("fileset", {})
    return {
        "catalog": catalog,
        "schema": schema,
        "fileset": fileset,
        "type": detail.get("type"),
        "storageLocation": detail.get("storageLocation"),
        "comment": detail.get("comment", ""),
        "properties": detail.get("properties", {}),
    }


def catalog_describe_table(params: Dict[str, Any]) -> Dict[str, Any]:
    catalog, schema, table = params["catalog"], params["schema"], params["table"]
    body = _gravitino_get(
        _catalog_base(catalog) + f"/schemas/{_seg(schema)}/tables/{_seg(table)}")
    detail = body.get("table", {})
    columns = []
    for col in detail.get("columns", []):
        col_type = col.get("type")
        columns.append({
            "name": col.get("name"),
            "type": col_type if isinstance(col_type, str) else json.dumps(col_type, ensure_ascii=False),
            "nullable": col.get("nullable", True),
            "comment": col.get("comment", ""),
        })
    return {
        "catalog": catalog,
        "schema": schema,
        "table": table,
        "comment": detail.get("comment", ""),
        "columns": columns,
        "properties": detail.get("properties", {}),
    }


def backend_call(method: str, path: str, params: Dict[str, Any],
                 tenant: str, user: str) -> Dict[str, Any]:
    """MCP → Java 内部接口；租户/用户透传，Java 侧落审计。"""
    if not PLATFORM_INTERNAL_TOKEN:
        raise HTTPException(status_code=503, detail="PLATFORM_INTERNAL_TOKEN 未配置")
    headers = {
        "Content-Type": "application/json",
        "X-Internal-Token": PLATFORM_INTERNAL_TOKEN,
        "X-Tenant-Id": tenant or "default",
        "X-User-Id": user or "mcp-client",
    }
    url = BACKEND_URL.rstrip("/") + path
    data = None
    if method == "GET":
        clean = {k: v for k, v in (params or {}).items() if v not in (None, "")}
        if clean:
            url += "?" + urllib.parse.urlencode(clean)
        req = urllib.request.Request(url, headers=headers, method="GET")
    else:
        data = json.dumps(params or {}, ensure_ascii=False).encode()
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="replace")
        raise HTTPException(status_code=exc.code, detail=body[:500])


def need_confirm(tool: str, params: Dict[str, Any], impact: str) -> Optional[Dict[str, Any]]:
    """建/跑/停必须显式确认；未确认只返回影响说明，不执行。"""
    if params.get("confirm") is True:
        return None
    return {
        "needs_confirmation": True,
        "tool": tool,
        "impact": impact,
        "next": "确认后以 confirm=true 重新调用",
    }


def dispatch(tool: str, params: Dict[str, Any], approval: Optional[str],
             tenant: str = "default", user: str = "mcp-client") -> Any:
    if tool == "embed.query":
        return {"dim": 512, "embeddings": embed(params["texts"], bool(params.get("is_query", True)))}
    if tool == "kb.search":
        return kb_search(params)
    if tool == "kb.get_chunk":
        return kb_get_chunk(params)
    if tool == "kb.ingest":
        return kb_ingest(params)
    if tool == "kb.delete":
        return kb_delete(params, approval)
    if tool == "doris.sql_query":
        return doris_sql_query(params)
    if tool == "graph.search":
        return graph_search(params)
    if tool == "graph.get_node":
        return graph_get_node(params)
    if tool == "catalog.list":
        return catalog_list(params)
    if tool == "catalog.list_schemas":
        return catalog_list_schemas(params)
    if tool == "catalog.list_tables":
        return catalog_list_tables(params)
    if tool == "catalog.describe_table":
        return catalog_describe_table(params)
    if tool == "catalog.list_filesets":
        return catalog_list_filesets(params)
    if tool == "catalog.describe_fileset":
        return catalog_describe_fileset(params)

    # ---------- platform.*：数据中台系统操作 ----------
    if tool == "platform.list_datasources":
        return backend_call("GET", "/api/internal/agent/datasources", params, tenant, user)
    if tool == "platform.list_tasks":
        return backend_call("GET", "/api/internal/agent/tasks", params, tenant, user)
    if tool == "platform.get_task_status":
        return backend_call("GET", "/api/internal/agent/tasks/status", params, tenant, user)
    if tool == "platform.lineage":
        return backend_call("GET", "/api/internal/agent/lineage", params, tenant, user)
    if tool == "platform.quality":
        return backend_call("GET", "/api/internal/agent/quality", params, tenant, user)
    if tool == "platform.create_task":
        blocked = need_confirm(tool, params,
                               "将创建一个同步任务；不会立即运行，但会写入任务配置")
        if blocked:
            return blocked
        body = dict(params)
        body.pop("confirm", None)
        return backend_call("POST", "/api/internal/agent/write/tasks", body, tenant, user)
    if tool == "platform.run_task":
        blocked = need_confirm(tool, params,
                               "将启动一次同步作业并占用集群资源；目标端可能被写入数据")
        if blocked:
            return blocked
        task_id = int(params["id"])
        body = {"params": params.get("params") or {}}
        return backend_call("POST", f"/api/internal/agent/write/tasks/{task_id}/run",
                            body, tenant, user)
    if tool == "platform.stop_task":
        blocked = need_confirm(tool, params,
                               "将停止该任务当前运行的作业；已写入目标端的数据不会回滚")
        if blocked:
            return blocked
        task_id = int(params["id"])
        return backend_call("POST", f"/api/internal/agent/write/tasks/{task_id}/stop", {}, tenant, user)
    if tool == "platform.request_change":
        # 只生成申请单，不执行删除/改配置，因此不需要 confirm；审批由人工完成。
        body = dict(params)
        body.pop("confirm", None)
        return backend_call("POST", "/api/internal/agent/write/change-requests",
                            body, tenant, user)

    # ---------- data.*：已发布数据服务 ----------
    if tool == "data.query_metric":
        return backend_call("POST", f"/api/internal/agent/data/{params['code']}",
                            {"metric": params.get("metric", ""),
                             "start": params.get("start", ""),
                             "end": params.get("end", ""),
                             "page": params.get("page", 1),
                             "pageSize": params.get("pageSize", 50)}, tenant, user)
    if tool == "data.query_time":
        return backend_call("POST", f"/api/internal/agent/data/{params['code']}",
                            {"start": params.get("start", ""), "end": params.get("end", ""),
                             "page": params.get("page", 1),
                             "pageSize": params.get("pageSize", 50)}, tenant, user)
    if tool == "data.query_page":
        return backend_call("POST", f"/api/internal/agent/data/{params['code']}",
                            {"page": params.get("page", 1),
                             "pageSize": params.get("pageSize", 50)}, tenant, user)
    raise HTTPException(status_code=404, detail="TOOL_NOT_FOUND")


class ToolCall(BaseModel):
    params: Dict[str, Any] = {}


@app.get("/health")
def health():
    return {"status": "ok", "tools": len(load_tools()), "graph_nodes": len(load_graph().get("nodes", []))}


@app.get("/tools")
def tools():
    return {"tools": load_tools()}


@app.post("/tools/{tool}")
def call_tool(tool: str, body: ToolCall,
              authorization: Optional[str] = Header(default=None),
              x_approval: Optional[str] = Header(default=None),
              x_tenant_id: Optional[str] = Header(default=None),
              x_user_id: Optional[str] = Header(default=None)):
    caller = check_auth(authorization)
    started = time.time()
    try:
        result = dispatch(tool, body.params, x_approval,
                          x_tenant_id or "default", x_user_id or "mcp-client")
        audit(caller, tool, body.params, "ok", (time.time() - started) * 1000, x_approval)
        return {"tool": tool, "result": result}
    except HTTPException as exc:
        audit(caller, tool, body.params, f"error:{exc.detail}", (time.time() - started) * 1000, x_approval)
        raise


@app.post("/mcp")
async def mcp_rpc(request: Request):
    caller = check_auth(request.headers.get("authorization"))
    payload = await request.json()
    method = payload.get("method")
    rid = payload.get("id")
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": load_tools()}}
    if method == "tools/call":
        params = payload.get("params", {})
        tool = params.get("name")
        args = params.get("arguments", {})
        started = time.time()
        try:
            # P2-30：dispatch 里是同步的 subprocess/urllib（长 SQL 会阻塞事件循环，
            # /health 超时触发 liveness 重启），放到线程池执行。
            result = await asyncio.to_thread(
                dispatch, tool, args, request.headers.get("x-approval"),
                request.headers.get("x-tenant-id") or "default",
                request.headers.get("x-user-id") or "mcp-client")
            audit(caller, tool, args, "ok", (time.time() - started) * 1000,
                  request.headers.get("x-approval"))
            return {"jsonrpc": "2.0", "id": rid,
                    "result": {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]}}
        except HTTPException as exc:
            audit(caller, tool, args, f"error:{exc.detail}", (time.time() - started) * 1000,
                  request.headers.get("x-approval"))
            return {"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32000, "message": str(exc.detail)}}
    return {"jsonrpc": "2.0", "id": rid,
            "error": {"code": -32601, "message": "method not found"}}
