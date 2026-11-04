-- 采集任务的类型与周期调度（离线任务按 cron 触发；实时任务常驻，不配 cron）。
alter table platform.sync_task
    add column if not exists task_type        varchar(16) not null default 'OFFLINE',
    add column if not exists schedule_enabled boolean     not null default false,
    add column if not exists schedule_cron    varchar(64),
    add column if not exists schedule_timezone varchar(64) not null default 'Asia/Shanghai',
    add column if not exists retry_times      integer     not null default 0,
    add column if not exists timeout_minutes  integer     not null default 60,
    add column if not exists last_schedule_at timestamptz;

create index if not exists idx_sync_task_schedule
    on platform.sync_task (schedule_enabled, task_type);
