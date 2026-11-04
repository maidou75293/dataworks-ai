-- V106 补数据（backfill）作业与逐日切片。
--
-- 一张 job 表记录一次补数（工作流 + 日期区间 + 并行度），一张 day 表记录
-- 每一天的执行结果。补数复用既有 trigger 路径（Prefect createRun + wf_instance），
-- 平台只负责切片、排队、并发上限与状态收敛，不另写执行器。
--
-- 同一工作流同一时间只允许一个 RUNNING 作业：partial unique index 在数据库层兜底，
-- 应用层再返回可读的 409（并发提交时两个请求都可能先查到空闲）。

create table if not exists platform.backfill_job (
    id            bigserial primary key,
    tenant_id     varchar(64) not null,
    project_id    bigint      not null,
    workflow_id   bigint      not null references platform.workflow(id) on delete cascade,
    env           varchar(16) not null default 'DEV',
    date_start    date        not null,
    date_end      date        not null,
    parallelism   int         not null default 2,
    status        varchar(16) not null,
    total_days    int         not null default 0,
    done_days     int         not null default 0,
    failed_days   int         not null default 0,
    params_json   jsonb,
    created_by    varchar(64),
    created_at    timestamptz default now(),
    finished_at   timestamptz,
    constraint ck_backfill_parallelism check (parallelism between 1 and 4),
    constraint ck_backfill_range check (date_end >= date_start),
    constraint ck_backfill_status check (status in
        ('PENDING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELLED'))
);

create index if not exists idx_backfill_job_workflow
    on platform.backfill_job (workflow_id, created_at desc);
create index if not exists idx_backfill_job_tenant
    on platform.backfill_job (tenant_id, created_at desc);

-- 「同一工作流同一时间只允许一个 RUNNING」：并发提交时第二个直接冲突，
-- 不依赖应用层「先查后写」的时序。
create unique index if not exists ux_backfill_job_running
    on platform.backfill_job (workflow_id) where status = 'RUNNING';

create table if not exists platform.backfill_day (
    id          bigserial primary key,
    job_id      bigint      not null references platform.backfill_job(id) on delete cascade,
    biz_date    date        not null,
    status      varchar(16) not null,
    run_id      varchar(64),
    message     text,
    started_at  timestamptz,
    finished_at timestamptz,
    unique (job_id, biz_date),
    constraint ck_backfill_day_status check (status in
        ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

create index if not exists idx_backfill_day_job_status
    on platform.backfill_day (job_id, status);
