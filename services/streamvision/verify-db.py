"""Verify the migrated StreamVision DB; optionally patch k8s-specific config.

Usage (script is piped over ssh, so it reads itself from stdin):
    cat verify-db.py | ssh ubuntu@$SV_NODE "sudo python3 - <db-path> [--patch]"
"""
import sqlite3
import sys

path = sys.argv[1]
patch = "--patch" in sys.argv[2:]

conn = sqlite3.connect(path)
print("integrity_check:", conn.execute("PRAGMA integrity_check").fetchone()[0])

for table in ("users", "videos", "knowledge_items", "frames", "knowledge_chunks"):
    try:
        count = conn.execute(f"select count(*) from {table}").fetchone()[0]
        print(f"{table}: {count}")
    except Exception as exc:  # noqa: BLE001
        print(f"{table}: n/a ({exc})")

if patch:
    conn.execute(
        "update system_config set value=? where key='vllm.base_url'",
        ("http://127.0.0.1:8000",),
    )
    conn.execute(
        "update system_config set value=? where key='embed.base_url'",
        ("http://127.0.0.1:8002",),
    )
    conn.execute(
        "insert into system_config(key,value) values('source.folder_path','/data/incoming') "
        "on conflict(key) do update set value=excluded.value"
    )
    conn.commit()
    print("patched: vllm.base_url / embed.base_url / source.folder_path")

for key, value in conn.execute(
    "select key,value from system_config where key in "
    "('vllm.base_url','embed.base_url','vllm.model_path','source.folder_path') order by key"
):
    print(f"{key} = {value}")

conn.close()
