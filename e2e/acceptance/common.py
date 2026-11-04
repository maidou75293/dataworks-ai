#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dataworks 数据中台验收脚本公共库。

Windows 侧可达，因此默认调用 /mnt/c/Windows/System32/curl.exe。
非 WSL 环境（CI / 开发机）自动回退 urllib，接口保持完全一致。

只读约定：
  --readonly 时除 GET 外一律拒绝执行；唯一例外是 POST /api/auth/login
  （会话引导，不写业务数据），用于换取 token 后跑只读枚举。

退出码：0 = 无 FAIL（允许 SKIP）；1 = 存在 FAIL。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE = "http://192.0.2.11:30090"
AUTH_PATH = "/api/auth/login"

# curl.exe 候选：环境变量优先，其次 WSL 常见路径
_CURL_CANDIDATES = [
    os.environ.get("DW_CURL", "").strip(),
    "/mnt/c/Windows/System32/curl.exe",
    "/mnt/c/Windows/system32/curl.exe",
    "/mnt/c/Windows/System32/curl",
]
_curl_exe = ""  # 首次解析后缓存（"" 表示用 urllib）

BASE = DEFAULT_BASE.rstrip("/")
TIMEOUT = 30
READONLY = False
STARTED_AT = time.time()
RESULTS = []  # [{"name","status":"PASS|FAIL|SKIP","detail","http","ms"}]


# --------------------------------------------------------------------- 配置

def parse_args(description: str) -> argparse.Namespace:
    """统一的命令行：--base / --readonly / --timeout / --json。"""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--base", default=os.environ.get("DW_BASE", DEFAULT_BASE),
                        help="平台地址，默认 %s" % DEFAULT_BASE)
    parser.add_argument("--readonly", action="store_true",
                        help="只读模式：拒绝 GET 以外的请求（登录除外）")
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("DW_TIMEOUT", "30")),
                        help="单请求超时秒数，默认 30")
    parser.add_argument("--json", action="store_true", dest="as_json",
                        help="结束时额外输出机器可读的 JSON 摘要")
    args = parser.parse_args()
    configure(base=args.base, readonly=args.readonly, timeout=args.timeout)
    return args


def configure(base: str | None = None, readonly: bool = False, timeout: int | None = None) -> None:
    global BASE, READONLY, TIMEOUT
    if base:
        BASE = base.rstrip("/")
    READONLY = READONLY or readonly
    if timeout:
        TIMEOUT = timeout


def is_readonly() -> bool:
    return READONLY


# --------------------------------------------------------------------- HTTP

def _curl() -> str:
    global _curl_exe
    if _curl_exe:
        return _curl_exe
    for cand in _CURL_CANDIDATES:
        if cand and os.path.exists(cand):
            _curl_exe = cand
            return cand
    _curl_exe = "-"  # 负数标记：已判定无 curl
    return ""


def http(method: str, path: str, body=None, token: str | None = None,
         tenant: str | None = None, headers: dict | None = None,
         timeout: int | None = None, meta: dict | None = None):
    """发一次请求，返回 (status_code, parsed_body)。

    parsed_body：JSON 可解析则 dict/list，否则原始文本（空体为 None）。
    meta 传入 dict 时回填 {"status","latency_ms","raw","engine"}，便于打印证据。
    """
    method = method.upper()
    if READONLY and method != "GET" and path.split("?")[0] != AUTH_PATH:
        raise RuntimeError("readonly 模式拒绝执行 %s %s" % (method, path))
    if READONLY and method != "GET":
        warn("readonly：仅放行会话引导 %s" % AUTH_PATH)

    url = path if path.startswith("http") else BASE + path
    hdrs = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        hdrs["Authorization"] = "Bearer " + token
    if tenant:
        hdrs["X-Tenant-Id"] = tenant
    hdrs.update(headers or {})
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    tmo = timeout or TIMEOUT
    t0 = time.time()
    exe = _curl()
    if exe:
        cmd = [exe, "-sS", "--noproxy", "*", "--max-time", str(tmo),
               "-X", method, "-w", "\n%{http_code}"]
        for key, value in hdrs.items():
            cmd += ["-H", "%s: %s" % (key, value)]
        if payload is not None:
            cmd += ["--data-binary", payload.decode("utf-8")]
        cmd.append(url)
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=tmo + 10)
            raw_bytes = proc.stdout
            err = proc.stderr.decode("utf-8", "replace").strip()
            if proc.returncode != 0:
                status, text = 0, "CURL_ERROR(%s): %s" % (proc.returncode, err[:300])
            else:
                head, _, tail = raw_bytes.rpartition(b"\n")
                status = int(tail.strip() or b"0")
                text = head.decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            status, text = 0, "CURL_TIMEOUT: 超过 %ss" % tmo
    else:
        data = payload
        req = urllib.request.Request(url, data=data, method=method)
        for key, value in hdrs.items():
            req.add_header(key, value)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req, timeout=tmo) as resp:
                status = resp.status
                text = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            status = exc.code
            text = exc.read().decode("utf-8", "replace")
        except Exception as exc:  # 网络层失败
            status, text = 0, "URLLIB_ERROR: %s" % exc

    elapsed = int((time.time() - t0) * 1000)
    parsed = _parse(text)
    if meta is not None:
        meta.update({"status": status, "latency_ms": elapsed,
                     "raw": text, "engine": "curl.exe" if exe else "urllib"})
    return status, parsed


def _parse(text: str):
    if text is None or text == "":
        return None
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        return text


def qs(params: dict | None) -> str:
    if not params:
        return ""
    clean = {k: v for k, v in params.items() if v is not None}
    if not clean:
        return ""
    return "?" + urllib.parse.urlencode(clean)


# --------------------------------------------------------------------- 登录

def login() -> tuple[str | None, str]:
    """取 token：优先 DW_TOKEN，其次 DW_USER/DW_PASS 调登录接口。

    返回 (token, 说明)。无凭据时返回 (None, "no-credentials")，由调用方 SKIP。
    只尝试一次，不做重试/爆破（连续失败会触发平台 5 分钟锁定）。
    """
    token = os.environ.get("DW_TOKEN", "").strip()
    if token:
        return token, "DW_TOKEN"
    user = os.environ.get("DW_USER", "").strip()
    password = os.environ.get("DW_PASS", "")
    if not user:
        return None, "no-credentials"
    status, body = http("POST", AUTH_PATH, {"username": user, "password": password})
    if status == 200 and isinstance(body, dict) and body.get("token"):
        info("登录成功 user=%s tenant=%s 角色=%s" % (
            user, body.get("tenant", "-"), _roles_text(body.get("roles"))))
        return str(body["token"]), "login"
    info("登录失败 user=%s HTTP %s %s" % (user, status, snippet(body, 160)))
    return None, "login-failed:%s" % status


def _roles_text(roles) -> str:
    if isinstance(roles, list):
        out = []
        for item in roles:
            if isinstance(item, dict):
                out.append(str(item.get("code") or item.get("name") or item))
            else:
                out.append(str(item))
        return ",".join(out) or "-"
    return str(roles or "-")


# --------------------------------------------------------------------- 断言 / 输出

def check(name: str, ok: bool, detail: str = "", status: int | None = None) -> bool:
    _record("PASS" if ok else "FAIL", name, detail, status)
    return ok


def skip(name: str, detail: str = "") -> None:
    _record("SKIP", name, detail, None)


def _record(state: str, name: str, detail: str, status) -> None:
    RESULTS.append({"name": name, "status": state, "detail": str(detail),
                    "http": status, "ms": None})
    line = "%-4s %s" % (state, name)
    if status is not None:
        line += "  [HTTP %s]" % status
    if detail:
        line += "  " + str(detail)
    print(line, flush=True)
    RESULTS[-1]["ms"] = int((time.time() - STARTED_AT) * 1000)


def info(message: str) -> None:
    print("INFO " + message, flush=True)


def warn(message: str) -> None:
    print("WARN " + message, file=sys.stderr, flush=True)


def snippet(obj, limit: int = 160, keys=None) -> str:
    """把响应压成一行证据：列表显示条数+前两项，dict 可只保留关心字段。"""
    if isinstance(obj, dict):
        view = {k: obj.get(k) for k in keys if k in obj} if keys else obj
        text = json.dumps(view, ensure_ascii=False, default=str)
    elif isinstance(obj, list):
        text = "[%d] %s" % (len(obj), json.dumps(obj[:2], ensure_ascii=False, default=str))
    elif obj is None:
        text = "<empty>"
    else:
        text = str(obj).replace("\n", " ")
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


# --------------------------------------------------------------------- 结果表

def _width(text: str) -> int:
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _width(text))


def summary(as_json: bool = False) -> int:
    passed = sum(1 for r in RESULTS if r["status"] == "PASS")
    failed = sum(1 for r in RESULTS if r["status"] == "FAIL")
    skipped = sum(1 for r in RESULTS if r["status"] == "SKIP")
    elapsed = time.time() - STARTED_AT

    print("")
    header = [_pad("状态", 6), _pad("HTTP", 6), _pad("用例", 42), "详情"]
    print("-" * 96)
    print("  ".join(header))
    print("-" * 96)
    for row in RESULTS:
        detail = row["detail"] if len(row["detail"]) <= 46 else row["detail"][:45] + "…"
        print("  ".join([_pad(row["status"], 6),
                         _pad("-" if row["http"] is None else str(row["http"]), 6),
                         _pad(row["name"], 42), detail]))
    print("-" * 96)
    print("合计 %d   PASS %d   FAIL %d   SKIP %d   用时 %.1fs"
          % (len(RESULTS), passed, failed, skipped, elapsed))
    if failed:
        print("失败用例：")
        for row in RESULTS:
            if row["status"] == "FAIL":
                print("  - %s  %s" % (row["name"], row["detail"]))

    if as_json:
        print(json.dumps({"base": BASE, "readonly": READONLY,
                          "pass": passed, "fail": failed, "skip": skipped,
                          "elapsed_s": round(elapsed, 1), "results": RESULTS},
                         ensure_ascii=False, indent=2))
    return 1 if failed else 0
