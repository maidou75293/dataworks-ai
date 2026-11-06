#!/usr/bin/env python3
"""CI：DELETE 与批量清理接口必须带租户条件或平台级权限。

背景（第三轮审计 P0-1）：`POST /api/integration/tasks/runs/prune` 只挂 `ops:stop`，
SQL 却是全平台删除，tenant_b 的 ADMIN 一条请求就能清掉别人的运行历史。
这类接口的共同点是：路径含 prune/purge/cleanup，或方法注解是 DELETE。

规则（扫 `backend/**/*Controller.java`）：
  1. 方法上有 `@DeleteMapping`，或路径含 prune / purge / cleanup / sweep；
  2. 满足任一即通过：
     - 方法/类上有 `system:manage` / `tenant:manage` 权限点；
     - 方法体出现 `requirePlatform` / `TenantGuard.platform`；
     - 方法体出现 `tenant_id` / `project_id in` / `TenantScope`；
     - 方法体调用了归属 helper（owned*/require*/find/getWorkflow/...）；
  3. 否则必须登记在 scripts/ci/destructive-allowlist.txt（写清理由）。

用法：python -I scripts/ci/check-destructive-tenant.py [--list]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "backend" / "src" / "main" / "java"
ALLOWLIST = pathlib.Path(__file__).with_name("destructive-allowlist.txt")

MAPPING = re.compile(
    r'@(?:[\w.]+\.)?(Delete|Post)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
DELETE = re.compile(r'@(?:[\w.]+\.)?DeleteMapping')
METHOD = re.compile(r"^\s{4}(?:public|private|protected)\s+[^;=]*?\b(\w+)\s*\(")
ATOM = re.compile(r'@(?:[\w.]+\.)?RequireAtom\(([^)]*)\)')
BULK = re.compile(r"/(prune|purge|cleanup|sweep)\b", re.I)

SAFE_PLATFORM = ("system:manage", "tenant:manage")
SAFE_BODY = re.compile(
    r"requirePlatform|TenantGuard\.platform|tenant_id|project_id\s+in|"
    r"TenantScope|scopedTenant|own(?:ed)?[A-Z]|check\(|require(?:Project|Run|WithSecrets|Event|User|Database)?\(|"
    r"requireDatabase|requireDataPlane|find\(|getWorkflow\(|ownedUser\(|"
    r"access\.check\(|guard\.deleteTable\(")


def methods(lines):
    names, current = [], None
    for line in lines:
        found = METHOD.match(line)
        if found:
            current = found.group(1)
        names.append(current)
    return names


def collect_blocks(lines):
    """按方法切块：从方法声明**上方的注解**到下一条方法声明。"""
    decls = [idx for idx, line in enumerate(lines) if METHOD.match(line)]
    starts = []
    for idx in decls:
        start = idx
        # 注解在声明上方，必须一起算进本方法，否则 @DeleteMapping 会落到上一个方法里
        while start > 0:
            prev = lines[start - 1].strip()
            if prev == "" or prev.startswith("@"):
                start -= 1
            else:
                break
        starts.append(start)
    starts.append(len(lines))
    out = []
    for i in range(len(starts) - 1):
        start, end = starts[i], starts[i + 1]
        out.append((start + 1, lines[start:end]))
    return out


def load_allowlist():
    entries = set()
    if ALLOWLIST.exists():
        for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line and not line.startswith("#"):
                entries.add(line.split()[0])
    return entries


def main():
    list_only = "--list" in sys.argv
    allowed = load_allowlist()
    hits = []
    for path in sorted(SRC.rglob("*Controller.java")):
        lines = path.read_text(encoding="utf-8").splitlines()
        rel = path.relative_to(ROOT).as_posix()
        for line_no, block in collect_blocks(lines):
            text = "\n".join(block)
            annotations = " ".join(
                m.group(0) for m in re.finditer(r"@[\w.]+\.(?:Delete|Post)Mapping[^\n]*", text))
            path_value = ""
            m = MAPPING.search(text)
            if m:
                path_value = m.group(2)
            destructive = (DELETE.search(annotations) is not None
                           or DELETE.search(text) is not None
                           or BULK.search(path_value) is not None)
            if not destructive:
                continue
            if any(atom in text for atom in SAFE_PLATFORM):
                continue
            if SAFE_BODY.search(text):
                continue
            method = "?"
            for candidate in block:
                found = METHOD.match(candidate)
                if found:
                    method = found.group(1)
                    break
            key = f"{rel}#{method}"
            hits.append((key, line_no, path_value or "(DELETE)"))
    unknown = [h for h in hits if h[0] not in allowed]
    for key, line_no, path_value in hits:
        print(f"{'  ok' if key in allowed else 'FAIL'}  {key}:{line_no}  {path_value}")
    print(f"\n命中 {len(hits)} 条，已登记 {len(hits) - len(unknown)} 条，未登记 {len(unknown)} 条")
    if list_only:
        return 0
    if unknown:
        print("\n新增了没有租户/平台保护的重删除口子：DELETE 或 path 含 prune/purge/cleanup。"
              "\n1) 加 @RequireAtom(\"system:manage\") 或 TenantGuard.requirePlatform()；"
              "\n2) SQL 带 tenant_id / project_id in；"
              "\n3) 确属平台级或内部调用，在 destructive-allowlist.txt 登记并写理由。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
