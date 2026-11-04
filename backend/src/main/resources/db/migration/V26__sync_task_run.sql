-- 每次运行的参数快照与渲染后 SQL，便于复现与审计。
create table if not exists platform.sync_task_run (
    id           bigserial primary key,
    task_id      bigint       not null,
    job_name     varchar(256),
    params       jsonb,
    rendered_sql jsonb,
    created_by   varchar(128),
    status       varchar(32)  not null default 'SUBMITTED',
    created_at   timestamptz  not null default now()
);
create index if not exists idx_sync_task_run_task
    on platform.sync_task_run (task_id, created_at desc);
