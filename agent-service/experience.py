"""经验条目：运行时产生，单独落盘，不混进提示词。

每条包含租户/用户/工具/成功失败/耗时/错误摘要；后续可由人工筛选成技能，
但不会被自动拼进系统提示词。
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone

DATA_DIR = os.environ.get("AGENT_EXPERIENCE_DIR", "data")


def record(entry: dict) -> None:
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        path = os.path.join(DATA_DIR, "experience-" + datetime.now(timezone.utc).strftime("%Y%m%d") + ".jsonl")
        row = {"ts": datetime.now(timezone.utc).isoformat(), **entry}
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")
    except Exception:
        pass
