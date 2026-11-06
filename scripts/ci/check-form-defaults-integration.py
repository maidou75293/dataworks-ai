#!/usr/bin/env python3
"""REQ-DW-14 守卫：数据集成「新建/配置」表单默认值。

断言（读本地工作树源码，静态判定）：
  F1 补数弹窗打开即带默认日期（T-7 ~ T-1），不再 setBackfillStart('') 留空
  F2 补数提交有日期格式/顺序校验（不再把空串原样 POST）
  F3 并行度 Form.Item 有 initialValue（与后端缺省 1 同源），且 openEdit 回填 ?? 1
  F4 周期调度 Form.Item 有 initialValue={false}，打开开关时预填常用 cron 模板
红检：对本分支修改前的 Integration.tsx（git show HEAD 版本）跑一遍，必须 FAIL。
"""
import re
import subprocess
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK，证据日志统一按 UTF-8 写
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = Path(__file__).resolve().parents[2]
TARGET = "web/src/pages/Integration.tsx"


def load(worktree: bool) -> str:
    if worktree:
        return (REPO / TARGET).read_text(encoding="utf-8")
    return subprocess.run(
        ["git", "-C", str(REPO), "show", f"HEAD:{TARGET}"],
        capture_output=True, text=True, encoding="utf-8", check=True,
    ).stdout


def checks(src: str) -> list[tuple[str, bool, str]]:
    out: list[tuple[str, bool, str]] = []

    m = re.search(r"const openBackfill = \(task: Task\) => \{(.*?)\n  \}", src, re.S)
    body = m.group(1) if m else ""
    ok = ("setBackfillStart('')" not in body
          and "subtract(7" in body and "subtract(1" in body
          and "YYYY-MM-DD" in body)
    out.append(("F1 补数弹窗预填 T-7~T-1", ok, "openBackfill 未预填默认日期或仍留空"))

    m = re.search(r"const submitBackfill = async \(\) => \{(.*?)\n  \}", src, re.S)
    body = m.group(1) if m else ""
    ok = ("backfillStart" in body and "isoDate.test(backfillStart)" in body
          and "isoDate.test(backfillEnd)" in body and "不能晚于" in body)
    out.append(("F2 补数提交先校验日期（空串/格式/顺序）", ok, "submitBackfill 缺日期校验"))

    ok = re.search(r'name="parallelism"[^>]*initialValue=\{1\}', src) is not None \
        and re.search(r"parallelism: task\.parallelism \?\? 1", src) is not None
    out.append(("F3 并行度初值=1（与后端缺省同源，编辑路径也兜底）", ok,
                "parallelism 无 initialValue 或 openEdit 未兜底"))

    ok = re.search(r'name="scheduleEnabled"[^>]*initialValue=\{false\}', src) is not None \
        and re.search(r"scheduleCron', '0 2 \* \* \*'", src) is not None
    out.append(("F4 周期调度默认关 + 打开时预填每天 02:00", ok,
                "scheduleEnabled 无初值或开关未预填 cron 模板"))

    return out


def main() -> int:
    worktree = "--head" not in sys.argv
    src = load(worktree)
    failed = 0
    for name, ok, why in checks(src):
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  → {why}"))
        failed += 0 if ok else 1
    mode = "工作树" if worktree else "HEAD 红检"
    print(f"[{mode}] {3 + 1 - failed} PASS / {failed} FAIL")
    if worktree:
        return 1 if failed else 0
    # HEAD 红检必须红
    return 0 if failed else 1


if __name__ == "__main__":
    sys.exit(main())
