#!/usr/bin/env python3
"""CI：扫描控制器里「只按主键读写」的 SQL，提醒补租户条件。

背景（docs/byid-tenant-audit.md）：第一、二轮外部审计的 P0 大多是同一类写法——
列表接口过滤了租户，但 `/{id}` 的读/改/删只写 `where id = ?`，tenant_b 就能改
别的租户的项目/告警/服务。逐条 code review 抓不住新增接口，所以放进 CI。

判定规则（只看 `backend/**/*Controller.java`）：
  1. 语句里出现 `where <alias>.id = ?`（或 `where id = ?`）；
  2. 同一条语句里没有 `tenant_id` / `project_id in` / `tenant_member` 这三个
     租户条件中的任意一个；
  3. 且 `文件#方法名` 不在 allowlist 里。

allowlist 的每一行都要写理由：要么「这个对象先经过 owned*/find() 校验」，要么
「这张表是平台级、没有租户维度」。用法：

    python -I scripts/ci/check-tenant-sql.py            # 检查
    python -I scripts/ci/check-tenant-sql.py --list     # 列出当前所有命中
退出码 1 表示有未登记的主键直查。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "backend" / "src" / "main" / "java"
ALLOWLIST = pathlib.Path(__file__).with_name("tenant-sql-allowlist.txt")

KEY = re.compile(r"where\s+(?:[a-z_][a-z0-9_]*\.)?id\s*=\s*\?", re.I)
TENANT = re.compile(r"tenant_id|project_id\s+in|tenant_member|tenantGuard|TenantScope", re.I)
METHOD = re.compile(r"^\s{4}(?:public|private|protected)\s+[^;=]*?\b(\w+)\s*\(")
CONTROLLER = re.compile(r"Controller\.java$")


def method_names(lines):
    """给每一行标上它所属的方法名（粗略：类里 4 空格缩进的方法声明）。"""
    names, current = [], None
    for line in lines:
        found = METHOD.match(line)
        if found:
            current = found.group(1)
        names.append(current)
    return names


def statements(lines):
    """产出 (起始行号, 方法名, sql 片段)：三引号块 + 单行字符串常量。"""
    in_block = False
    block, start = [], 0
    for idx, line in enumerate(lines, 1):
        if '"""' in line:
            if not in_block:
                in_block, block, start = True, [], idx
                rest = line.split('"""', 1)[1]
                if '"""' in rest:  # 同一行开闭
                    in_block = False
                    block.append(rest.split('"""', 1)[0])
                    yield start, block
                    block = []
                else:
                    block.append(rest)
                continue
            block.append(line.split('"""', 1)[0])
            in_block = False
            yield start, block
            block = []
            continue
        if in_block:
            block.append(line)
            continue
        for literal in re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', line):
            if KEY.search(literal):
                yield idx, [literal]


def load_allowlist():
    entries = {}
    if not ALLOWLIST.exists():
        return entries
    for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries[line.split()[0]] = line
    return entries


def main():
    list_only = "--list" in sys.argv
    allowed = load_allowlist()
    hits = []
    for path in sorted(SRC.rglob("*Controller.java")):
        if not CONTROLLER.search(path.name):
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        methods = method_names(lines)
        rel = path.relative_to(ROOT).as_posix()
        for line_no, sql_lines in statements(lines):
            sql = " ".join(sql_lines)
            if not KEY.search(sql) or TENANT.search(sql):
                continue
            method = methods[line_no - 1] or "?"
            key = f"{rel}#{method}"
            snippet = re.sub(r"\s+", " ", sql).strip()[:110]
            hits.append((key, line_no, snippet))

    unknown = [hit for hit in hits if hit[0] not in allowed]
    for key, line_no, snippet in hits:
        mark = "  ok" if key in allowed else "FAIL"
        print(f"{mark}  {key}:{line_no}  {snippet}")
    print(f"\n命中 {len(hits)} 条，已登记 {len(hits) - len(unknown)} 条，未登记 {len(unknown)} 条")
    if list_only:
        return 0
    if unknown:
        print("\n新增了「只按主键访问、没有租户条件」的 SQL。"
              "\n1) 优先改成 `where id = ? and tenant_id = ?` 或在取对象时走 TenantGuard；"
              "\n2) 若确实已由 owned*/find() 前置校验或本就是平台级表，"
              "在 scripts/ci/tenant-sql-allowlist.txt 登记 `路径#方法名  理由`。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
