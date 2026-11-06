#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""REQ-DW-22 自检：扫描 web/src/pages 的裸表（<Table 没有 locale.emptyText / EmptyState）。

口径：
- 逐个 JSX `<Table ... />` 块（自带轻量扫描器：跳过字符串与 {...} 表达式，泛型 `<Table<T>>` 不误判）；
- 块内含 `emptyText` 视为已接入统一空态；
- dataSource 是「全大写模块常量」（如 TECH / ERROR_ROWS / MODEL_HELP）的表标记为 STATIC（内容恒定、
  理论上不会为空），单列报告，不算违规；
- 其余未接入的都进「剩余未接入清单」。

用法：
  python -I scripts/ci/check-table-empty-states.py          # 报告，恒退出 0（防新增裸表靠人看报告）
  python -I scripts/ci/check-table-empty-states.py --strict # 有非静态违规时退出 1（可选门禁）
"""
import json
import re
import sys
from pathlib import Path

PAGES_DIR = Path(__file__).resolve().parents[2] / 'web' / 'src' / 'pages'
TABLE_RE = re.compile(r'<Table(?![A-Za-z])')


def skip_string(s: str, i: int) -> int:
    """s[i] 是引号，返回闭合引号之后的下标。"""
    q = s[i]
    i += 1
    n = len(s)
    while i < n:
        c = s[i]
        if c == '\\':
            i += 2
            continue
        if c == q:
            return i + 1
        i += 1
    return n


def skip_expr(s: str, i: int) -> int:
    """s[i] 是 '{'，返回配对 '}' 之后的下标（跳过字符串与嵌套）。"""
    depth = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c in '\'"`':
            i = skip_string(s, i)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def find_block(src: str, start: int) -> int:
    """从 '<Table' 的 '<' 起，返回该自闭合标签 '/>' 之后的下标；找不到返回 len(src)。"""
    depth = 0
    i = start + 1
    n = len(src)
    while i < n:
        c = src[i]
        if c in '\'"`':
            i = skip_string(src, i)
            continue
        if c == '{':
            i = skip_expr(src, i)
            continue
        if c == '<':
            depth += 1
        elif c == '>':
            if depth == 0 and src[i - 1] == '/':
                return i + 1
            if depth > 0:
                depth -= 1
        i += 1
    return n


def datasource_of(block: str) -> str:
    m = re.search(r'dataSource\s*=\s*', block)
    if not m:
        return ''
    j = m.end()
    if j < len(block) and block[j] == '{':
        end = skip_expr(block, j)
        return block[j + 1:end - 1].strip()
    m2 = re.match(r'[A-Za-z_$][\w$.?]*', block[j:])
    return m2.group(0) if m2 else ''


def is_static(expr: str) -> bool:
    return bool(re.fullmatch(r'[A-Z][A-Z0-9_]*', expr or ''))


def main() -> int:
    strict = '--strict' in sys.argv
    as_json = '--json' in sys.argv
    files = sorted(PAGES_DIR.glob('*.tsx'))
    report = []
    total = covered = static_skipped = 0
    violations = 0
    for f in files:
        src = f.read_text(encoding='utf-8')
        rows = []
        for m in TABLE_RE.finditer(src):
            start = m.start()
            end = find_block(src, start)
            block = src[start:end]
            line = src.count('\n', 0, start) + 1
            ds = datasource_of(block)
            has_empty = 'emptyText' in block
            kind = 'covered' if has_empty else ('static' if is_static(ds) else 'bare')
            total += 1
            if has_empty:
                covered += 1
            elif kind == 'static':
                static_skipped += 1
            else:
                violations += 1
            if kind == 'bare':
                rows.append({'line': line, 'dataSource': ds[:60]})
        if rows:
            report.append({'file': str(f.relative_to(PAGES_DIR.parent.parent.parent)), 'bare': rows})
    if as_json:
        print(json.dumps({'total': total, 'covered': covered, 'static': static_skipped,
                          'bare': violations, 'files': report}, ensure_ascii=False))
    else:
        print(f'表总数 {total}｜已接入空态 {covered}｜静态常量表(static, 不计违规) {static_skipped}｜裸表 {violations}')
        for item in report:
            print(f'\n未接入（裸表）→ {item["file"]}')
            for r in item['bare']:
                print(f'  L{r["line"]}  dataSource={{{r["dataSource"]}}}')
        if not report:
            print('\n剩余未接入清单：空 —— 所有非静态表都有 locale.emptyText。')
    return 1 if (strict and violations) else 0


if __name__ == '__main__':
    sys.exit(main())
