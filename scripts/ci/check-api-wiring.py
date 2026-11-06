#!/usr/bin/env python3
"""接口接线检查：后端有端点、前端从不调用 → 报警。

目的：防"功能做了但没接到界面"（2026-10-26 一次扫出 20 个零引用端点）。
判据：后端映射集合 − 前端调用集合。命中白名单跳过，命中原有缺口记为 WARN，其余为 ERROR。

用法：
  py -3 scripts/ci/check-api-wiring.py [--repo PATH] [--warn-only]
退出码：0 通过（或仅 WARN）；1 有新缺口。
"""
import argparse
import os
import re
import sys

SKIP_PREFIX = ("/api/internal", "/api/agent", "/api/health", "/api/auth/sso",
               "/api/udf/jars", "/api/ops/prefect", "/api/integration/tasks/runs/prune",
               "/api/rbac/permissions", "/api/lake/template")

PATH_RE = re.compile(r"""['"`](/(?:api|projects|alerts|alert-rules|alert-deliveries|notify-channels|access-requests|all-tenants|storage|warehouse-connections)[^'"`\s]*)['"`]""")


def norm(p):
    p = p.split("?")[0]
    p = re.sub(r"\$\{[^}]*\}", "{}", p)
    p = re.sub(r"\{[^}/]+\}", "{}", p)
    return p.rstrip("/") or "/"


def java_routes(repo):
    root = os.path.join(repo, "backend/src/main/java")
    out = []
    for d, _, fs in os.walk(root):
        if "/target/" in d:
            continue
        for f in fs:
            if not f.endswith(".java"):
                continue
            p = os.path.join(d, f)
            s = open(p, encoding="utf-8", errors="ignore").read()
            cls = re.search(r'@RequestMapping\("([^"]*)"\)', s)
            pre = cls.group(1) if cls else ""
            for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\("([^"]*)"\))?', s):
                path = m.group(2) or ""
                full = (pre.rstrip("/") + "/" + path.lstrip("/")) if path else pre
                if not full.startswith("/"):
                    full = "/" + full
                out.append((m.group(1).upper(), full, os.path.relpath(p, root)))
    return out


def web_paths(repo):
    root = os.path.join(repo, "web/src")
    out = set()
    for d, _, fs in os.walk(root):
        if "node_modules" in d:
            continue
        for f in fs:
            if not f.endswith((".tsx", ".ts")):
                continue
            s = open(os.path.join(d, f), encoding="utf-8", errors="ignore").read()
            out.update(m.group(1) for m in PATH_RE.finditer(s))
    return out


def load_list(path):
    if not os.path.exists(path):
        return []
    return [x.strip() for x in open(path, encoding="utf-8") if x.strip() and not x.startswith("#")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--warn-only", action="store_true")
    a = ap.parse_args()

    ci = os.path.join(a.repo, "scripts", "ci")
    allow = load_list(os.path.join(ci, "wiring-allowlist.txt"))
    known = load_list(os.path.join(ci, "wiring-known-gaps.txt"))

    fe = {norm(x) for x in web_paths(a.repo)}
    errors, warns = [], []
    for verb, path, src in java_routes(a.repo):
        if any(path.startswith(p) for p in SKIP_PREFIX):
            continue
        if path in ("/", "/api", "/index.html"):
            continue
        if any(path.startswith(p) for p in allow):
            continue
        t = norm(path)
        head = t.split("{}")[0]
        hit = any(x == t or (len(head) > 8 and x.startswith(head)) for x in fe)
        if hit:
            continue
        line = f"{verb:6} {path:56} {src}"
        if any(path.startswith(p) for p in known):
            warns.append(line)
        else:
            errors.append(line)

    for w in warns:
        print("WARN  " + w)
    for e in errors:
        print("ERROR " + e)
    print(f"\n接口接线检查：{len(errors)} 个新缺口 / {len(warns)} 个已知缺口（白名单 {len(allow)} 条）")
    if errors and not a.warn_only:
        print("处理：接界面，或把路径加进 scripts/ci/wiring-allowlist.txt 并注明理由")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
