#!/usr/bin/env python3
"""REQ-DW-3 静态守卫：同步任务入口的「实时任务」复用流任务创建链路。

判据对齐卡面：
  ① 数据集成新建入口存在实时任务项，且跳转到 /stream?create=1；
  ② Stream 页消费 create 参数自动弹新建，创建仍走 POST /api/flink/jobs；
  ③ Integration 不得另起 flink 创建请求（不重复实现后端）。

用法：
  python -I scripts/ci/check-realtime-entry-reuse.py            # 检查工作树
  python -I scripts/ci/check-realtime-entry-reuse.py --base DIR # 检查 DIR 下副本（红检用）
退出码：0=通过，1=违反。
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

FAILS: list[str] = []


def read(base: Path, rel: str) -> str | None:
    p = base / rel
    if not p.exists():
        FAILS.append(f"缺文件 {rel}")
        return None
    return p.read_text(encoding="utf-8")


def must_present(text: str | None, tokens: list[str], where: str) -> None:
    if text is None:
        return
    for t in tokens:
        if t not in text:
            FAILS.append(f"{where}: 缺少 {t!r}")


def must_absent(text: str | None, tokens: list[str], where: str) -> None:
    if text is None:
        return
    for t in tokens:
        if t in text:
            FAILS.append(f"{where}: 不应出现 {t!r}（创建链路必须复用流开发）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=str(ROOT), help="待检查目录（含 web/）")
    args = ap.parse_args()
    base = Path(args.base)

    integ = read(base, "web/src/pages/Integration.tsx")
    must_present(integ, ["实时任务", "/stream?create=1"], "Integration.tsx")
    must_absent(integ, ["post<Job>('/api/flink/jobs'", "post('/api/flink/jobs'"], "Integration.tsx")

    stream = read(base, "web/src/pages/Stream.tsx")
    must_present(stream, [
        "searchParams.get('create')",   # 消费深链参数
        "openCreate()",                 # 复用原有新建
        "post<Job>('/api/flink/jobs'",  # 创建仍走唯一后端链路
    ], "Stream.tsx")

    if FAILS:
        for f in FAILS:
            print(f"FAIL {f}")
        print(f"RESULT: 实时任务入口复用守卫 违反 {len(FAILS)} 条")
        return 1
    print("PASS Integration 新建入口含实时任务项并深链 /stream?create=1")
    print("PASS Integration 未另起 flink 创建请求（复用流开发链路）")
    print("PASS Stream 消费 create 参数且创建走 POST /api/flink/jobs")
    print("RESULT: 实时任务入口复用守卫 3/3 PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
