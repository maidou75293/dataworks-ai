-- 实时运行的周期采样：运行时长、处理行数、Kafka lag。checkpoint 由引擎日志另行解析。
create table if not exists platform.sync_run_metric (
    id      bigserial primary key,
    run_id  bigint      not null references platform.sync_task_run (id) on delete cascade,
    ts      timestamptz not null default now(),
    metrics jsonb
);

create index if not exists idx_sync_run_metric_run
    on platform.sync_run_metric (run_id, id);
