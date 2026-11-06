#!/usr/bin/env python3
"""表单字段接线检查：界面有输入项、保存请求里却没这个字段 → 报警。

目的：防"填了也不生效"（2026-10-26 项目编辑 13 个字段只提交 3 个就是这么发现的）。
判据：同一文件内 Form.Item name 集合 − 该文件 api 调用体里的 key 集合。
默认只告警（启发式，有误报可能）；--strict 时失败。

用法： py -3 scripts/ci/check-form-fields.py [--repo PATH] [--strict]
"""
import argparse
import os
import re
import sys

FIELD_RE = re.compile(r'<Form\.Item[^>]*name="([A-Za-z_][\w]*)"')
CALL_RE = re.compile(r'\b(put|post|patch|api|del|request)\s*(?:<[^>]*>)?\s*\(', re.I)
KEY_RE = re.compile(r'(?:^|[,{;\s])([A-Za-z_][\w]*)\s*:')


def payload_keys(src, start, window=1200):
    seg = src[start:start + window]
    return set(KEY_RE.findall(seg)) | {x.strip("'\"") for x in re.findall(r'(?:name|field)\s*:\s*[\'"]([\w]+)[\'"]', seg)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()

    root = os.path.join(a.repo, "web/src/pages")
    bad = []
    for f in sorted(os.listdir(root)):
        if not f.endswith(".tsx"):
            continue
        p = os.path.join(root, f)
        s = open(p, encoding="utf-8", errors="ignore").read()
        # 整表单直传的写法无法靠 key 名判断，直接跳过避免误报
        if re.search(r"\.\.\.\s*(values|fields|formValues)\b", s) or \
           re.search(r"\b(put|post|patch)\s*(?:<[^>]*>)?\s*\([^)]*\b(values|fields|formValues)\b", s):
            continue
        fields = set(FIELD_RE.findall(s))
        if not fields:
            continue
        keys = set()
        for m in CALL_RE.finditer(s):
            keys |= payload_keys(s, m.end())
        missing = sorted(fields - keys)
        # 过滤明显是查询条件/展示项的名字
        skip = {"keyword", "search", "page", "size", "pageSize", "tenant", "projectId"}
        missing = [x for x in missing if x not in skip]
        if missing:
            bad.append((f, missing))

    for f, missing in bad:
        print(f"WARN  {f}: 表单字段未出现在保存请求里 → {', '.join(missing[:8])}")
    print(f"\n表单字段检查：{len(bad)} 个文件存在可疑字段（启发式，需人工确认）")
    if a.strict and bad:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
