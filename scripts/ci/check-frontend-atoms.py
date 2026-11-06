#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端权限点 = 后端原子 一致性自查（BUG-TC6-11）。

为什么要这个检查：前端按钮用 `can('x')` 裁菜单/按钮，后端端点用 `@RequireAtom("y")` 判权限。
两边用的不是同一个原子名时，用户「看得见、点了 403」——种子角色恰好把两个原子捆在一起时
发现不了，任何一次角色调整才暴露（`Alerts.tsx` 的 ack/close 用 `ops:run`、
`AlertController` 要 `alert:ack` 就是这么漏的）。

本检查只做**静态报告**，三档：
  ERROR 前端引用了权限点目录里不存在的原子   → 拼错/已删除，必然「永远不可见」或恒真，必须改
  WARN  该原子在前端出现，但**没有任何端点**要求它 → 前端在拿一个没人执行的原子做裁剪（装饰性闸门）
  INFO  对应控制器要求的原子，配对页面一次都没引用 → 可能是漏裁剪（人工确认），也可能是
        别的页面/内部调用承担，故不判错

配对规则：`web/src/pages/<Name>.tsx` ↔ `<Name>Controller.java`（仅大小写与复数 s 的差异），
刻意保守——宁可少配几对，也不要一堆假告警把报告淹掉。

用法：
  python -I scripts/ci/check-frontend-atoms.py [--repo PATH] [--page Alerts] [--show-all]
  python -I scripts/ci/check-frontend-atoms.py --self-test     # 检查器自身的红/绿用例
退出码：默认 0（报告模式，不卡门禁）；--strict 时 ERROR>0 返回 1。解析失败返回 2。
"""
import argparse
import glob
import os
import re
import shutil
import sys
import tempfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # noqa: BLE001
    pass

ATOM = r"[a-z][a-z0-9]*(?::[a-z0-9-]+)+"
ATOM_LITERAL_RE = re.compile(r"['\"](" + ATOM + r")['\"]")
CAN_CALL_RE = re.compile(r"\bcan(Any|All)?\s*\(")
ATOM_IN_ATTR_RE = re.compile(r"@Require(?:Any)?Atom\s*\(([^)]*)\)")
CLASSNAME_RE = re.compile(r"\bclass\s+(\w+)")
CLASS_REQ_RE = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
MAPPING_RE = re.compile(
    r"@(?:[\w.]+\.)?(Get|Post|Put|Delete|Patch)Mapping(?:\s*\(\s*(?:value\s*=\s*)?\"([^\"]*)\")?")
CATALOG_INSERT_RE = re.compile(
    r"insert\s+into\s+[\w.]*permission_catalog\s*\([^)]*\)\s*values", re.I)


# ---------------------------------------------------------------- 源码清洗

def strip_comments(src, quote_marks=("\"", "'", "`")):
    """去掉 // 与 /* */ 注释（保留换行，行号不漂移），字符串内容原样保留。

    简易状态机：认识 C 风格字符串与模板字符串；不做 JSX/正则字面量解析——
    这份检查只关心 can('x') 与 @RequireAtom("x")，误判风险在校验用例里兜。
    """
    out = []
    i, n, quote = 0, len(src), None
    while i < n:
        ch = src[i]
        if quote:
            # 三引号（Java text block）
            if ch == "\\" and i + 1 < n and quote != '"""':
                out.append(src[i:i + 2])
                i += 2
                continue
            if quote == '"""' and src.startswith('"""', i):
                out.append('"""')
                i += 3
                quote = None
                continue
            if quote != '"""' and ch == quote:
                out.append(ch)
                i += 1
                quote = None
                continue
            out.append(ch)
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
            continue
        if src.startswith('"""', i):
            quote = '"""'
            out.append('"""')
            i += 3
            continue
        if ch in quote_marks:
            quote = ch
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def line_of(src, pos):
    return src.count("\n", 0, pos) + 1


def rel(root, path):
    return os.path.relpath(path, root).replace(os.sep, "/")


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


# ---------------------------------------------------------------- 扫描前端

def call_atoms(clean, open_paren_end):
    """从 `can(` 之后的位置取参数串（配平括号），返回其中出现的原子。"""
    depth, j, n = 1, open_paren_end, len(clean)
    while j < n and depth:
        if clean[j] == "(":
            depth += 1
        elif clean[j] == ")":
            depth -= 1
        j += 1
    return [a for a in ATOM_LITERAL_RE.findall(clean[open_paren_end:j])]


def scan_frontend(root):
    """返回 [{file, line, fn, atoms}]，只扫 web/src 下的 .ts/.tsx。"""
    hits = []
    base = os.path.join(root, "web", "src")
    for path in sorted(glob.glob(os.path.join(base, "**", "*.ts*"), recursive=True)):
        if not path.endswith((".ts", ".tsx")):
            continue
        raw = read(path)
        clean = strip_comments(raw)
        for m in CAN_CALL_RE.finditer(clean):
            atoms = call_atoms(clean, m.end())
            if not atoms:
                continue
            hits.append({
                "file": rel(root, path),
                "line": line_of(clean, m.start()),
                "fn": "can" + (m.group(1) or ""),
                "atoms": atoms,
            })
    return hits


def frontend_atoms_by_file(hits):
    out = {}
    for h in hits:
        out.setdefault(h["file"], set()).update(h["atoms"])
    return out


# ---------------------------------------------------------------- 扫描后端

def scan_controllers(root):
    """返回 {类名: {atoms:set, endpoints:[{method,path,atoms,line}], file}}"""
    out = {}
    base = os.path.join(root, "backend", "src", "main", "java")
    for path in sorted(glob.glob(os.path.join(base, "**", "*Controller.java"), recursive=True)):
        raw = read(path)
        clean = strip_comments(raw)
        cm = CLASSNAME_RE.search(clean)
        if not cm:
            continue
        cls = cm.group(1)
        prefix = ""
        cp = CLASS_REQ_RE.search(clean)
        if cp:
            prefix = cp.group(1)
        atoms, endpoints = set(), []
        for m in MAPPING_RE.finditer(clean):
            sub = m.group(2) or ""
            endpoints.append({
                "method": m.group(1).upper(),
                "path": (prefix + sub) or "/",
                "atoms": [],
                "line": line_of(clean, m.start()),
                "pos": m.start(),
            })
        # @RequireAtom 可能写在 @XxxMapping 之前也可能之后（本仓库两种都有），
        # 按「最近的那个 mapping」归属；距离超过 400 字符视为类级/游离注解，只进类原子集。
        for r in ATOM_IN_ATTR_RE.finditer(clean):
            names = re.findall(r'"([^"]+)"', r.group(1))
            atoms.update(names)
            if not endpoints:
                continue
            near = min(endpoints, key=lambda ep: abs(ep["pos"] - r.start()))
            if abs(near["pos"] - r.start()) <= 400:
                near["atoms"].extend(names)
        out[cls] = {"atoms": atoms, "endpoints": endpoints, "file": rel(root, path)}
    return out


def load_catalog(root):
    """权限点目录 = 所有迁移里 insert into ...permission_catalog 的原子并集（V55 之后还有追加）。"""
    atoms = set()
    base = os.path.join(root, "backend", "src", "main", "resources", "db", "migration")
    for path in sorted(glob.glob(os.path.join(base, "*.sql"))):
        sql = read(path)
        for m in CATALOG_INSERT_RE.finditer(sql):
            end = sql.find(";", m.end())
            seg = sql[m.end():end if end >= 0 else len(sql)]
            atoms.update(re.findall(r"\(\s*'(" + ATOM + r")'", seg))
    return atoms


# ---------------------------------------------------------------- 判定

def stem_of_page(path):
    return os.path.splitext(os.path.basename(path))[0].lower()


def stem_of_controller(cls):
    return cls[:-len("Controller")].lower() if cls.endswith("Controller") else cls.lower()


def stems_match(page_stem, ctrl_stem):
    """只认「相同」与「多个 s」；保守配对，避免 Alerts.csx 被硬配给 AlertRuleController 之外的类。"""
    return page_stem == ctrl_stem or page_stem == ctrl_stem + "s"


def analyze(root):
    hits = scan_frontend(root)
    by_file = frontend_atoms_by_file(hits)
    controllers = scan_controllers(root)
    catalog = load_catalog(root)
    all_endpoint_atoms = set()
    for c in controllers.values():
        all_endpoint_atoms |= c["atoms"]

    errors, warns, infos = [], [], []
    if not catalog:
        errors.append({"kind": "catalog", "detail": "没解析到权限点目录（permission_catalog 迁移），检查器口径失效"})
    known = catalog

    # ERROR：前端原子不在目录里
    for f, atoms in sorted(by_file.items()):
        for atom in sorted(atoms - known):
            if known:
                errors.append({"kind": "unknown-atom", "file": f, "atom": atom})

    # WARN：前端在用，但没有任何端点要求它
    for f, atoms in sorted(by_file.items()):
        for atom in sorted(atoms - all_endpoint_atoms):
            warns.append({"kind": "unused-by-endpoints", "file": f, "atom": atom})

    # INFO：配对页面 vs 配对控制器
    for f, atoms in sorted(by_file.items()):
        page_stem = stem_of_page(f)
        for cls, info in sorted(controllers.items()):
            if not stems_match(page_stem, stem_of_controller(cls)):
                continue
            missing = sorted(info["atoms"] - atoms)
            if missing:
                infos.append({
                    "kind": "page-miss", "file": f, "controller": cls, "atoms": missing,
                })
    return {
        "hits": hits,
        "by_file": by_file,
        "controllers": controllers,
        "catalog": catalog,
        "errors": errors,
        "warns": warns,
        "infos": infos,
    }


def render(res, page_filter=None, show_all=False):
    lines = []
    fe_atoms = set()
    for atoms in res["by_file"].values():
        fe_atoms |= atoms
    lines.append(
        "前端权限点自查（报告模式）：web/src %d 个原子 / %d 处 can*() 调用；"
        "后端 %d 个控制器、%d 个端点原子；权限点目录 %d 个"
        % (len(fe_atoms), len(res["hits"]), len(res["controllers"]),
           len(set().union(*[c["atoms"] for c in res["controllers"].values()]) if res["controllers"] else 0),
           len(res["catalog"])))

    def keep(item):
        return show_all or not page_filter or page_filter.lower() in item.get("file", "").lower()

    errs = [e for e in res["errors"] if keep(e)]
    warns = [w for w in res["warns"] if keep(w)]
    infos = [i for i in res["infos"] if keep(i)]

    lines.append("")
    lines.append("ERROR %d 条（前端引用了目录里没有的权限点）" % len(errs))
    for e in errs:
        if e["kind"] == "catalog":
            lines.append("  ! " + e["detail"])
        else:
            lines.append("  ! %s: can('%s') —— 目录里没有这个权限点" % (e["file"], e["atom"]))

    lines.append("")
    lines.append("WARN %d 条（前端在裁剪，但没有任何端点要求该原子）" % len(warns))
    for w in warns:
        lines.append("  ~ %s: can('%s')" % (w["file"], w["atom"]))

    lines.append("")
    lines.append("INFO %d 条（配对控制器要求、配对页面没引用；需人工确认是否漏裁剪）" % len(infos))
    for i in infos:
        lines.append("  - %s ↔ %s：页面未引用 %s" % (i["file"], i["controller"], ", ".join(i["atoms"])))

    lines.append("")
    lines.append("说明：本表按 can('x')/canAny(...) 与 @RequireAtom(\"x\") 的**原子名**比对；"
                 "「按钮该不该裁」看 WARN/INFO 两档，ERROR 档可直接改。")
    return "\n".join(lines)


# ---------------------------------------------------------------- 自检

SELF_FIXTURE = {
    "web/src/pages/Widgets.tsx": (
        "import { can } from '../permissions'\n"
        "// 注释里的 can('ghost:in-comment') 不该被算进去\n"
        "export const A = () => can('widget:read')\n"
        "export const B = () => canAny('widget:read', 'widget:write')\n"
        "export const C = () => can('widget:typo')\n"
    ),
    "backend/src/main/java/demo/WidgetController.java": (
        "package demo;\n"
        "import x.RequireAtom;\n"
        "@RequestMapping(\"/api/widgets\")\n"
        "public class WidgetController {\n"
        "  @RequireAtom(\"widget:read\")\n"
        "  @GetMapping(\"/{id}\")\n"
        "  public Object get() { return null; }\n"
        "  @PostMapping(\"/{id}\")\n"
        "  @RequireAtom(\"widget:write\")\n"
        "  public Object put() { return null; }\n"
        "}\n"
    ),
    "backend/src/main/java/demo/OtherController.java": (
        "package demo;\n"
        "@RequestMapping(\"/api/other\")\n"
        "public class OtherController {\n"
        "  @PostMapping(\"/run\")\n"
        "  @RequireAtom(\"other:run\")\n"
        "  public Object run() { return null; }\n"
        "}\n"
    ),
    "backend/src/main/resources/db/migration/V1__atoms.sql": (
        "insert into platform.permission_catalog (code, name, group_name, danger) values\n"
        "  ('widget:read', '看 widget', '演示', ''),\n"
        "  ('widget:write', '改 widget', '演示', '中'),\n"
        "  ('other:run', '跑 other', '演示', '中')\n"
        "on conflict (code) do nothing;\n"
    ),
}


def self_test():
    root = tempfile.mkdtemp(prefix="fw-atoms-")
    try:
        for relpath, body in SELF_FIXTURE.items():
            path = os.path.join(root, relpath)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)
        res = analyze(root)
        errs = {(e["file"], e.get("atom")) for e in res["errors"] if e["kind"] == "unknown-atom"}
        warns = {(w["file"], w["atom"]) for w in res["warns"]}
        infos = {(i["file"], i["controller"], tuple(i["atoms"])) for i in res["infos"]}
        wc = res["controllers"].get("WidgetController", {}).get("endpoints", [])
        ep_atoms = {(e["method"], e["path"]): e["atoms"] for e in wc}
        cases = [
            ("目录解析出 3 个原子", res["catalog"] == {"widget:read", "widget:write", "other:run"}),
            ("配对页面断言 3 处（注释不计）", len(res["hits"]) == 3),
            ("拼错的原子判 ERROR", ("web/src/pages/Widgets.tsx", "widget:typo") in errs),
            ("注释里的原子不计入", all("ghost:in-comment" not in a for a in res["by_file"].values())),
            ("没人执行的原子判 WARN", ("web/src/pages/Widgets.tsx", "widget:typo") in warns),
            ("配对控制器/页面齐全时无 INFO", infos == set()),
            ("注解在 mapping 之前也归对端点", ep_atoms.get(("GET", "/api/widgets/{id}")) == ["widget:read"]),
            ("注解在 mapping 之后也归对端点", ep_atoms.get(("POST", "/api/widgets/{id}")) == ["widget:write"]),
        ]
        failed = [name for name, ok in cases if not ok]
        for name, ok in cases:
            print(("  PASS " if ok else "  FAIL ") + name)
        if failed:
            print("自检失败 %d 项" % len(failed))
            return 1
        print("自检 %d/%d 通过" % (len(cases), len(cases)))
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description="前端权限点 = 后端原子 一致性自查")
    ap.add_argument("--repo", default=".", help="仓库根目录（默认当前目录）")
    ap.add_argument("--page", default=None, help="只看文件名包含该串的页面（如 Alerts）")
    ap.add_argument("--show-all", action="store_true", help="忽略 --page，输出全部")
    ap.add_argument("--strict", action="store_true", help="ERROR>0 时退出码 1")
    ap.add_argument("--self-test", action="store_true", help="跑检查器自身的红/绿用例")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    root = os.path.abspath(args.repo)
    if not os.path.isdir(os.path.join(root, "web", "src")):
        print("找不到 %s/web/src —— 请用 --repo 指定仓库根目录" % root)
        return 2
    res = analyze(root)
    print(render(res, args.page, args.show_all))
    if args.strict and res["errors"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
