-- 运行实例的业务进度事件：阶段固定为 prepare/read/transform/write/verify/done。
create table if not exists platform.sync_run_event (
    id      bigserial primary key,
    run_id  bigint      not null references platform.sync_task_run (id) on delete cascade,
    ts      timestamptz not null default now(),
    phase   varchar(16) not null,
    type    varchar(32) not null,
    message text,
    metrics jsonb
);

create index if not exists idx_sync_run_event_run
    on platform.sync_run_event (run_id, id);
