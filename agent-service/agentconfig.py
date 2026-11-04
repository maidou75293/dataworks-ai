"""对话智能体的模型侧配置（端点/密钥/模型名）——同源单一入口。

为什么存在（REQ-AGENT-CFGPANEL-1，项目负责人信箱 seq=24①）：真生效的模型路由只在
agent-service 的进程环境 / 节点 .env 里，而旧页面弹层改的是 Java 侧 DB 里的
身份模式——配置分裂，坏了没人知道（BUG-AGENT-KEY-FIX-1 实锤：key 为空对话
final=failed，/api/health 却依旧 ok=true，"服务活着"骗过了所有旧脚本）。
本模块把「现在到底生效的是什么」收敛成唯一来源：chat 建模型用它、健康灯探测
用它、配置页读数也用它。三条纪律：

- 永不外泄明文密钥：对外的 snapshot() 只给 keyConfigured + 尾 4 位指纹；
  长度 ≤8 的短密钥尾 4 位就接近整钥，按 **** 掩掉，不叫指纹。
- 改配置走 set_values()：写 .env（持久，重启后仍生效）+ 进程内立即生效
  （不需要重启、不需要登节点——「改配置不出页面」）。
- 健康灯与 chat 同源：灯探的就是 current() 里那组 baseUrl/key，灯红 ⇔
  用同一组配置发的对话冒烟红；缓存窗口只影响探测频率，不影响同源。

只用标准库：判据脚本要能在开发机裸 python 上直接 import 本模块跑红绿锁
（scripts/e2e/agent-cfgpanel-test.py），所以这个文件禁止引入任何第三方依赖。
"""
from __future__ import annotations

import hashlib
import os
import re
import threading
import time
import urllib.error
import urllib.request

ENV_BASE_URL = "AGENT_MODEL_BASE_URL"
ENV_API_KEY = "AGENT_MODEL_API_KEY"
ENV_MODEL_NAME = "AGENT_MODEL_NAME"

DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
DEFAULT_MODEL_NAME = "deepseek-chat"

# 值校验：三行都要同时被 systemd EnvironmentFile 和 `set -a; . ./.env` 正确解析，
# 所以只接受无空格无引号的 URL-safe 字符；拒绝换行/引号是防 .env 注入
# （值里塞一行 "AGENT_SERVICE_TOKEN=x" 就等于替节点改配置）。
_BASE_RE = re.compile(r"^https?://[^\s\"'<>]{1,256}$")
_NAME_RE = re.compile(r"^[A-Za-z0-9._:/+-]{1,64}$")
_KEY_RE = re.compile(r"^[A-Za-z0-9._:/+=-]{1,256}$")
# 解析 .env 行：KEY=value / export KEY='value'（取原文，两侧引号剥掉）
_LINE_RE = re.compile(r"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")

_lock = threading.RLock()
_state: dict = {}
_probe_cache: dict = {"sig": None, "at": 0.0, "result": None}


def default_env_path() -> str:
    """节点上 .env 与 app.py 同目录（systemd EnvironmentFile 也指这里）。"""
    override = (os.environ.get("AGENT_ENV_FILE") or "").strip()
    if override:
        return override
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")


def _env_or(key: str, fallback: str) -> str:
    return (os.environ.get(key) or "").strip() or fallback


def configure(base_url: str | None = None, api_key: str | None = None,
              model_name: str | None = None, env_path: str | None = None) -> None:
    """进程启动时注入生效值（app.py 传 os.environ 的三行）；省略=从环境变量读。

    可重复调用（判据沙箱用它换环境），调用即失效健康探测缓存。
    """
    with _lock:
        _state.clear()
        _state.update({
            "baseUrl": (base_url if base_url is not None
                        else _env_or(ENV_BASE_URL, DEFAULT_BASE_URL)).rstrip("/"),
            "apiKey": (api_key if api_key is not None
                       else _env_or(ENV_API_KEY, "")).strip(),
            "modelName": model_name if model_name is not None
                         else _env_or(ENV_MODEL_NAME, DEFAULT_MODEL_NAME),
            "envPath": env_path or default_env_path(),
        })
        _probe_cache["sig"] = None


def current() -> dict:
    """chat 与探针共用的生效配置（含明文 key，只给进程内部，绝不进 HTTP 响应）。"""
    with _lock:
        return dict(_state)


def fingerprint(key: str) -> str:
    """尾 4 位指纹；未配置=空串；短密钥（≤8）整体掩成 ****——宁可没指纹，不能泄整钥。"""
    key = (key or "").strip()
    if not key:
        return ""
    if len(key) <= 8:
        return "****"
    return key[-4:]


def read_env_file(path: str) -> dict:
    out = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                m = _LINE_RE.match(line.strip())
                if m:
                    out[m.group(1)] = m.group(2).strip().strip("'\"")
    except OSError:
        return {}
    return out


def _validate(updates: dict) -> None:
    if "baseUrl" in updates and not _BASE_RE.match(updates["baseUrl"]):
        raise ValueError("baseUrl 非法：需 http(s):// 开头、无空格引号")
    if "modelName" in updates and not _NAME_RE.match(updates["modelName"]):
        raise ValueError("modelName 非法：仅允许字母数字 . _ : / + -（1-64 位）")
    if "apiKey" in updates and updates["apiKey"] != "" and not _KEY_RE.match(updates["apiKey"]):
        raise ValueError("apiKey 含非法字符（禁空白/引号/换行，防 .env 注入）")


def _env_key(field: str) -> str:
    return {"baseUrl": ENV_BASE_URL, "apiKey": ENV_API_KEY, "modelName": ENV_MODEL_NAME}[field]


def set_values(base_url: str | None = None, model_name: str | None = None,
               api_key: str | None = None) -> dict:
    """改配置：进程内立即生效 + 写 .env 持久化。None=不改；api_key 传 ""=显式清除。

    原子写：tmp + os.replace；只动这三行，其它行（含注释）原样保留；行缺失则追加。
    写盘失败不回滚进程内值（节点 .env 不可写时对话仍按新配置活着），
    但 persisted=False + persistError 如实返回——页面必须看得见「没落盘」。
    """
    updates = {}
    if base_url is not None:
        updates["baseUrl"] = base_url.rstrip("/")
    if model_name is not None:
        updates["modelName"] = model_name
    if api_key is not None:
        updates["apiKey"] = api_key.strip()
    if not updates:
        return {"changed": [], "persisted": False, "persistError": None}
    _validate(updates)

    with _lock:
        before = dict(_state)
        _state.update(updates)
        changed = [k for k in ("baseUrl", "modelName", "apiKey") if k in updates]
        env_path = _state["envPath"]
        _probe_cache["sig"] = None  # 配置变＝灯必须重探，不许吃旧缓存

    try:
        lines: list[str] = []
        if env_path and os.path.isfile(env_path):
            with open(env_path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.read().splitlines()
        out_lines, replaced = [], set()
        for line in lines:
            m = _LINE_RE.match(line.strip())
            field = None
            if m:
                for cand in ("baseUrl", "modelName", "apiKey"):
                    if m.group(1) == _env_key(cand) and cand in updates:
                        field = cand
                        break
            if field:
                out_lines.append(f"{_env_key(field)}={updates[field]}")
                replaced.add(field)
            else:
                out_lines.append(line)
        for field in ("baseUrl", "modelName", "apiKey"):
            if field in updates and field not in replaced:
                out_lines.append(f"{_env_key(field)}={updates[field]}")
        if env_path:
            tmp = env_path + ".tmp-cfgpanel"
            with open(tmp, "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(out_lines) + "\n")
            os.replace(tmp, env_path)
        redacted_before = {
            k: (fingerprint(before["apiKey"]) if k == "apiKey" else before.get(k))
            for k in changed}
        return {"changed": changed, "persisted": bool(env_path),
                "persistError": None, "before": redacted_before}
    except OSError as e:
        redacted_before = {
            k: (fingerprint(before["apiKey"]) if k == "apiKey" else before.get(k))
            for k in changed}
        return {"changed": changed, "persisted": False,
                "persistError": str(e), "before": redacted_before}


def probe(base_url: str, api_key: str, timeout: float | None = None) -> dict:
    """探 OpenAI 兼容口 GET {base}/models —— 和 chat 用同一组 base/key。

    返回 {ok, status, error, latencyMs}；不抛异常：灯的语义就是「探的结果」，
    拒连/超时/401 都要变成可显示的 reason，而不是把 /api/health 打崩。
    """
    if timeout is None:
        try:
            timeout = float(_env_or("AGENT_HEALTH_PROBE_TIMEOUT_S", "3.0"))
        except ValueError:
            timeout = 3.0
    url = (base_url or DEFAULT_BASE_URL).rstrip("/") + "/models"
    req = urllib.request.Request(url, method="GET")
    req.add_header("Accept", "application/json")
    if api_key:
        req.add_header("Authorization", "Bearer " + api_key)
    # 直连探测器：不吃系统代理（代理在场时 127.0.0.1 桩会被绕成假红）
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    start = time.monotonic()
    try:
        with opener.open(req, timeout=timeout) as resp:
            resp.read(65536)
            status = resp.status
        return {"ok": status < 400, "status": status, "error": None,
                "latencyMs": int((time.monotonic() - start) * 1000)}
    except urllib.error.HTTPError as e:
        hint = "模型服务拒绝该密钥" if e.code in (401, 403) else "模型服务返回 HTTP %d" % e.code
        return {"ok": False, "status": e.code, "error": hint,
                "latencyMs": int((time.monotonic() - start) * 1000)}
    except Exception as e:
        return {"ok": False, "status": None,
                "error": "模型端点不可达: " + (str(e) or type(e).__name__),
                "latencyMs": int((time.monotonic() - start) * 1000)}


def health(force: bool = False) -> dict:
    """健康灯读数：key 没配=必红（对话必挂，BUG-AGENT-KEY-FIX-1 教训）；
    key 在但端点不可达/拒钥=红；端点 2xx=绿。带 sig 缓存（默认 20s），
    set_values/configure 会失效 sig——配置刚改完必须现探。"""
    cfg = current()
    out = {"keyConfigured": bool(cfg.get("apiKey")), "healthy": False,
           "reason": None, "probe": None, "cached": False}
    if not out["keyConfigured"]:
        out["reason"] = "未配置模型密钥（AGENT_MODEL_API_KEY 为空，对话必挂）"
        return out
    sig = (cfg.get("baseUrl", ""),
           hashlib.sha256((cfg.get("apiKey") or "").encode()).hexdigest()[:12])
    try:
        ttl = float(_env_or("AGENT_HEALTH_PROBE_CACHE_S", "20"))
    except ValueError:
        ttl = 20.0
    now = time.monotonic()
    with _lock:
        if (not force and _probe_cache["sig"] == sig
                and now - _probe_cache["at"] < ttl):
            out["probe"] = _probe_cache["result"]
            out["cached"] = True
        else:
            out["probe"] = probe(cfg.get("baseUrl", ""), cfg.get("apiKey", ""))
            _probe_cache.update({"sig": sig, "at": now, "result": out["probe"]})
    out["healthy"] = bool(out["probe"] and out["probe"].get("ok"))
    if not out["healthy"]:
        out["reason"] = (out["probe"] or {}).get("error") or "模型端点探测失败"
    return out


def snapshot(include_health: bool = True, force_probe: bool = False) -> dict:
    """对外唯一形态：端点/模型名明文 + 密钥只有「已配置 + 尾 4 位指纹」。

    这个函数是「密钥永不回显明文」的收口点：任何 HTTP 层只许透传本返回值；
    判据脚本会断言本输出的 JSON 里不出现任何真实 key 子串。
    """
    cfg = current()
    out = {
        "ok": True,
        "service": "dataworks-agent",
        "baseUrl": cfg.get("baseUrl", DEFAULT_BASE_URL),
        "modelName": cfg.get("modelName", DEFAULT_MODEL_NAME),
        "keyConfigured": bool(cfg.get("apiKey")),
        "keyFingerprint": fingerprint(cfg.get("apiKey", "")),
    }
    if include_health:
        h = health(force=force_probe)
        out["healthy"] = h["healthy"]
        out["healthReason"] = h["reason"]
        out["healthProbe"] = h["probe"]
        out["healthCached"] = h["cached"]
    return out
