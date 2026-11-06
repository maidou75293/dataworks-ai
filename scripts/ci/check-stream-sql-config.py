#!/usr/bin/env python3
"""REQ-DW-4 静态守卫：流任务表单去掉并行度/消费组，配置口径收进 FlinkSQL + 帮助文档。

判据对齐卡面：
  ① StreamJob.tsx 表单不再有 name="parallelism" / name="consumerGroup" 两字段；
     保存时仍原样带回库里存量值（老任务不被静默清空，后端字段兼容保留）；
  ② 页面内脚本帮助（KafkaSqlHelp.tsx）有「并行度 / 消费组」章节，
     给出 SET + WITH 子句示例，示例组名写死（不引导 ${KAFKA_GROUP_ID} 触发后端 400）；
  ③ 帮助文案不再引用已删除的「右侧」表单字段。

用法：
  python -I scripts/ci/check-stream-sql-config.py            # 检查工作树
  python -I scripts/ci/check-stream-sql-config.py --base DIR # 检查 DIR 下副本（红检用）
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


def check(text: str | None, present: list[str], absent: list[str], where: str) -> None:
    if text is None:
        return
    for t in present:
        if t not in text:
            FAILS.append(f"{where}: 缺少 {t!r}")
    for t in absent:
        if t in text:
            FAILS.append(f"{where}: 不应出现 {t!r}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=str(ROOT), help="待检查目录（含 web/）")
    args = ap.parse_args()
    base = Path(args.base)

    job = read(base, "web/src/pages/StreamJob.tsx")
    check(job,
          present=["parallelism: job?.parallelism ?? 1",
                   "consumerGroup: job?.consumer_group ?? ''"],
          absent=['<Form.Item name="parallelism"', '<Form.Item name="consumerGroup"',
                  "parallelism: values.parallelism", "consumerGroup: values.consumerGroup"],
          where="StreamJob.tsx")

    helpc = read(base, "web/src/components/KafkaSqlHelp.tsx")
    check(helpc,
          present=["'并行度 / 消费组'",
                   "SET 'parallelism.default' = '2'",
                   "'properties.group.id' = 'dw_dev_orders_reader'",
                   "scan.parallelism"],
          absent=['右侧「并行度」', '右侧「消费组」',
                  "'properties.group.id' = '\\${KAFKA_GROUP_ID}'"],
          where="KafkaSqlHelp.tsx")

    if FAILS:
        for f in FAILS:
            print(f"FAIL {f}")
        print(f"RESULT: 流任务SQL配置守卫 违反 {len(FAILS)} 条")
        return 1
    print("PASS StreamJob 表单已移除并行度/消费组，保存带回存量值（老任务兼容）")
    print("PASS 帮助文档含「并行度 / 消费组」章节：SET + WITH 示例、组名写死")
    print("PASS 帮助文案不再引用已删除的右侧表单字段")
    print("RESULT: 流任务SQL配置守卫 3/3 PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
