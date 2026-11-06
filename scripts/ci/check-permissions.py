#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""权限注解检查：新增「没有声明权限、又不是豁免/自助路径」的写操作端点 → 退出码 1。

为什么要这个检查（D49）：2026-10-26 扫端点，106 个没有权限注解（其中写操作 25 个）。
逐类核实后绝大多数是合理的（内部接口、自助接口、类级注解覆盖、方法内自己收口），
但**全靠人记得加**，漏一个没人发现——`POST /api/quality/gate` 就是这么漏的。

判据按 AuthInterceptor 的真实执行顺序，不看注释：
  1. 路径以 /api/internal/ 开头        → 拦截器强制校验 X-Internal-Token，视为已收口
  2. 路径在拦截器的**会话豁免**清单里  → 必须登记 allowlist 并写明方法内怎么鉴权，否则 ERROR；
     这类路径上挂 @RequireAtom 根本不会被读到（拦截器在注解判定之前就 return true），
     所以单独报 DECORATIVE——「看着有保护其实没有」比没写更危险
  3. 类级或方法级有 @RequireAtom / @RequireAnyAtom / @RequirePermission（含全限定写法
     `@com.lab.dataworks.auth.RequireAtom(...)`）→ OK
  4. 命中自助白名单（登录即可：/login、/me、/password、/switch-tenant、sso…）→ OK
  5. 其余：写操作（POST/PUT/PATCH/DELETE）无注解且未登记 → ERROR；
     GET 无注解只计数不拦（现状是「登录 + 租户收口」的正常面，65 条一次性登记没有意义，
     要收窄应另立卡）

用法：
  py -3 scripts/ci/check-permissions.py [--repo PATH] [--show-all]
退出码：0 通过（新缺口 0）；1 有新缺口／未登记豁免／装饰性注解。
"""
import argparse
import os
import re
import sys

# Windows 控制台默认 GBK，中文输出会 UnicodeEncodeError 把 CI 带崩
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # noqa: BLE001
    pass

VERB_ANN = ("Get", "Post", "Put", "Delete", "Patch")
MAPPING_RE = re.compile(r'@(?:[\w.]+\.)?(?:(Get|Post|Put|Delete|Patch)Mapping|RequestMapping)\b')
CLASS_MAPPING_RE = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
ANNOTATION_RE = re.compile(r'@(?:[\w.]+\.)?(RequireAtom|RequireAnyAtom|RequirePermission)\b')
MAPPING_VALUE_RE = re.compile(r'^\s*(?:@[\w.]+Mapping)\(\s*(?:value\s*=\s*)?"([^"]*)"')
REQUEST_METHOD_RE = re.compile(r'RequestMethod\.(\w+)')
WRONG_METHODS = ("POST", "PUT", "PATCH", "DELETE")
SIGNATURE_RE = re.compile(r'\b(public|private|protected)\b')

# 自助/公开接口：登录即可（或本就公开），不需要权限点。与 D49 卡面清单一致。
SELF_SERVICE = (
    "/api/auth/login", "/api/auth/logout", "/api/auth/me", "/api/auth/password",
    "/api/auth/switch-tenant", "/api/auth/sso", "/api/auth/tenants", "/api/health",
    "/actuator",
)


def norm(path):
    # 路径参数留个占位符再归一：直接删掉 {code} 会把 /api/ds/{code} 变成 /api/ds，
    # 前缀匹配（豁免清单里的 /api/ds/）就漏了——漏判比误判危险，这里宁可保留层级
    path = re.sub(r"\{[^}]*\}", ":param", path or "")
    path = re.sub(r"/{2,}", "/", path)
    path = path.rstrip("/")
    return path or "/"


def load_allowlist(path):
    """格式与 tenant-sql-allowlist.txt 一致：`相对路径#方法名   理由`。"""
    entries = {}
    if not os.path.exists(path):
        return entries
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, reason = line.partition("  ")
        entries[key.strip()] = (reason or "").strip()
    return entries


def session_exemptions(repo):
    """从 AuthInterceptor 源码取「跳过会话校验」的路径，别在检查脚本里抄第二份清单。"""
    src = os.path.join(repo, "backend/src/main/java/com/lab/dataworks/auth/AuthInterceptor.java")
    if not os.path.exists(src):
        return set(), set()
    text = open(src, encoding="utf-8", errors="ignore").read()
    block = text.split('if ("OPTIONS".equalsIgnoreCase(request.getMethod()))', 1)[-1]
    block = block.split("String token = bearer(request);", 1)[0]
    return (set(re.findall(r'path\.startsWith\("([^"]+)"\)', block)),
            set(re.findall(r'path\.equals\("([^"]+)"\)', block)))


def class_head(lines):
    """类声明之前的那段（含 package/import/注解/注释）。"""
    for i, line in enumerate(lines):
        if re.search(r'\b(class|record|interface|enum)\s+\w+', line) and not line.strip().startswith("//"):
            return lines[:i]
    return lines


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'//.*', '', text)
    return text


def method_name_at(lines, i):
    """从映射注解那行往下拼签名，去掉注解后第一个 `名字(` 就是方法名。"""
    window = []
    for j in range(i, min(i + 14, len(lines))):
        window.append(lines[j])
        # 收满一行方法签名就停：@RequestMapping(method={...}) 这种行里也有大括号，
        # 不能拿 "{ 出现" 当结束条件（否则签名还没读到就断了，方法名取空）
        if SIGNATURE_RE.search(strip_comments(lines[j])):
            break
    sig = strip_comments(" ".join(window))
    sig = re.sub(r'@[\w.]+(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?', ' ', sig)  # 去注解（含带括号/嵌套）
    sig = re.sub(r'\bnew\s+\w+(?:<[^>]*>)?\s*\(', ' ( ', sig)                   # 去 new X(...)
    m = re.search(r'\b(\w+)\s*\(', sig)
    name = m.group(1) if m else ""
    return "" if name in VERB_ANN or name.endswith("Mapping") else name


def annotations_around(lines, i):
    """方法自己的权限注解：映射注解上下紧邻的注解行都算（有人写在映射前，有人写在映射后）。"""
    found = []
    clean = [strip_comments(l).strip() for l in lines]

    def collect(idx):
        ann = ANNOTATION_RE.search(clean[idx])
        if ann:
            found.append(ann.group(1))
            return True
        # 空行、其它注解、跨行的注解参数 → 继续往上/下扫
        return not clean[idx] or clean[idx].startswith("@") or clean[idx] in (")", "(") \
            or clean[idx].startswith(")") or clean[idx].endswith("(")

    for j in range(i - 1, max(i - 8, -1), -1):
        if not clean[j]:
            continue
        if MAPPING_RE.search(clean[j]) and not ANNOTATION_RE.search(clean[j]):
            break
        if SIGNATURE_RE.search(clean[j]):
            break
        if not collect(j):
            break
    for j in range(i + 1, min(i + 8, len(lines))):
        if SIGNATURE_RE.search(clean[j]) or clean[j].startswith("{"):
            break
        if not collect(j):
            break
    return found


def verbs_for(line):
    """@RequestMapping 可能声明多个 method；没写就按 ANY（GET/POST 都收）。"""
    methods = [m.upper() for m in REQUEST_METHOD_RE.findall(line)]
    if methods:
        return sorted({m for m in methods})
    ann = MAPPING_RE.search(line)
    verb = (ann.group(1) or "").upper() if ann else ""
    return [verb] if verb else ["ANY"]


def scan_repo(repo):
    root = os.path.join(repo, "backend/src/main/java")
    rows = []
    for d, _, fs in os.walk(root):
        if os.sep + "target" + os.sep in d:
            continue
        for f in fs:
            if not f.endswith(".java"):
                continue
            p = os.path.join(d, f)
            text = open(p, encoding="utf-8", errors="ignore").read()
            if "@RestController" not in text and "@Controller" not in text:
                continue
            rel = os.path.relpath(p, repo).replace("\\", "/")
            lines = text.splitlines()
            head = class_head(lines)
            head_end = len(head)          # 类声明之前的行都是「类级」，那上面的 @RequestMapping 是前缀不是端点
            head_text = "\n".join(head)
            cls = CLASS_MAPPING_RE.search(head_text)
            pre = cls.group(1) if cls else ""
            class_annot = bool(ANNOTATION_RE.search(head_text))
            for i, line in enumerate(lines):
                if i <= head_end or not MAPPING_RE.search(line):
                    continue
                vm = MAPPING_VALUE_RE.search(line)
                sub = vm.group(1) if vm else ""
                if not sub and '"' in line:
                    anyv = re.search(r'"([^"]*)"', line)
                    sub = anyv.group(1) if anyv else ""
                path = norm((pre.rstrip("/") + "/" + sub.lstrip("/")) if sub else pre)
                rows.append({
                    "verbs": verbs_for(line), "path": path, "file": rel, "line": i + 1,
                    "method": method_name_at(lines, i),
                    "own": annotations_around(lines, i), "classAnnot": class_annot,
                })
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--show-all", action="store_true", help="把已登记例外与 GET 无注解一起打出来")
    args = ap.parse_args()
    rows = scan_repo(args.repo)
    allowlist = load_allowlist(os.path.join(args.repo, "scripts/ci/permissions-allowlist.txt"))
    exempt_prefixes, exempt_equals = session_exemptions(args.repo)
    exempt_all = exempt_prefixes | exempt_equals

    gaps, decorative, unlisted, listed, get_noannot, self_service = [], [], [], [], [], []
    total = 0
    for r in rows:
        key = f"{r['file']}#{r['method']}"
        r["key"] = key
        if r["path"].startswith("/api/internal"):
            total += len(r["verbs"])
            continue
        protected = bool(r["own"]) or r["classAnnot"]
        is_exempt = any(r["path"] == e or r["path"].startswith(e) for e in exempt_all)
        is_self_service = any(r["path"] == s or r["path"].startswith(s) for s in SELF_SERVICE)
        for verb in r["verbs"]:
            total += 1
            item = dict(r, verb=verb)
            if is_exempt:
                # 自助/公开接口（登录、健康检查、SSO、actuator）本就免会话，不需要登记
                if is_self_service:
                    self_service.append(item)
                    continue
                if protected:
                    # 豁免路径上挂注解＝拦截器根本读不到，看着有保护其实没有
                    decorative.append(item)
                (listed if key in allowlist else unlisted).append(item)
                continue
            if protected:
                continue
            if is_self_service:
                self_service.append(item)
                continue
            if key in allowlist:
                listed.append(item)
                continue
            (gaps if verb in WRONG_METHODS or verb == "ANY"
             else get_noannot).append(item)

    print(f"扫描端点 {total} 个（映射注解 {len(rows)} 处）；会话豁免清单 {sorted(exempt_all)}")
    print(f"已登记例外 {len(listed)} 条；自助接口 {len(self_service)} 条（不计缺口）")
    if args.show_all:
        for r in listed:
            print(f"  已登记  {r['verb']:6} {r['path']:46} {r['key']} —— {allowlist[r['key']]}")

    def report(title, items, tag, extra=""):
        print(f"\n{title}：{len(items)}")
        for r in items:
            print(f"  {tag} {r['verb']:6} {r['path']:46} {r['key']}:{r['line']}{extra}")

    report("新缺口（写操作无注解、未登记）", gaps, "ERROR")
    report("未登记的会话豁免路径（要写明方法内怎么鉴权）", unlisted, "ERROR")
    report("装饰性注解（豁免路径上的注解不会被拦截器读到）", decorative, "ERROR",
           "  —— 见 AuthInterceptor.preHandle 的 return true 分支")
    print(f"\n不计入失败的 GET 无注解（「登录+租户收口」面，另卡收敛）：{len(get_noannot)}")
    if args.show_all:
        for r in get_noannot:
            print(f"  WARN  {r['verb']:6} {r['path']:46} {r['key']}:{r['line']}")

    bad = len(gaps) + len(unlisted) + len(decorative)
    print(f"\n结论：{'通过（新缺口 0）' if bad == 0 else f'不通过，{bad} 项要处理'}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
