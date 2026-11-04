-- 上游依赖判断用「最近一次成功时间」，避免上游正在跑（PENDING）时下游被永久挡住。
alter table platform.sync_task
    add column if not exists last_success_at timestamptz;
