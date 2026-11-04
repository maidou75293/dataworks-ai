-- 运行实例生命周期与指标：定时/手动/补数/实时统一落在 sync_task_run。
alter table platform.sync_task_run
    add column if not exists run_type       varchar(16)  not null default 'MANUAL',
    add column if not exists started_at     timestamptz,
    add column if not exists finished_at    timestamptz,
    add column if not exists duration_ms    bigint,
    add column if not exists rows_read      bigint,
    add column if not exists rows_written   bigint,
    add column if not exists verify_mode    varchar(16)  not null default 'ROWS',
    add column if not exists verify_result  varchar(16),
    add column if not exists checksum       varchar(64),
    add column if not exists failure_reason varchar(500);

create index if not exists idx_sync_task_run_job
    on platform.sync_task_run (job_name);
