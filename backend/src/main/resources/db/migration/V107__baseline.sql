-- V107 任务基线（SLA 预警）。
--
-- 基线 = 一条承诺完成时间 + 一组工作流；每分钟由 BaselineService 用历史 P90 耗时
-- 和依赖图关键路径预测完成时间，命中预警窗口写 PREDICT_LATE，过点未完成写 BREACH，
-- 恢复后写 RECOVERED。
--
-- 事件按 (baseline_id, stat_date, kind) 唯一：同一天同一类事件只保留一条，
-- PREDICT_LATE 每次评估刷新预测值但不刷新首次通知时间，时间线不会每分钟膨胀。

create table if not exists platform.baseline (
    id              bigserial primary key,
    tenant_id       varchar(64) not null,
    project_id      bigint      not null references platform.project(id) on delete cascade,
    name            varchar(128) not null,
    deadline_time   time        not null,
    warn_before_min int         not null default 30,
    level           varchar(8)  not null default 'P2',
    owner           varchar(64),
    enabled         boolean     not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (project_id, name),
    constraint ck_baseline_level check (level in ('P1', 'P2', 'P3')),
    constraint ck_baseline_warn check (warn_before_min between 0 and 1440)
);

create index if not exists idx_baseline_tenant
    on platform.baseline (tenant_id, project_id);
create index if not exists idx_baseline_enabled
    on platform.baseline (enabled) where enabled;

create table if not exists platform.baseline_member (
    baseline_id bigint not null references platform.baseline(id) on delete cascade,
    workflow_id bigint not null references platform.workflow(id) on delete cascade,
    primary key (baseline_id, workflow_id)
);

create index if not exists idx_baseline_member_workflow
    on platform.baseline_member (workflow_id);

create table if not exists platform.baseline_event (
    id               bigserial primary key,
    baseline_id      bigint      not null references platform.baseline(id) on delete cascade,
    stat_date        date        not null,
    predicted_finish timestamptz,
    deadline_at      timestamptz,
    late_minutes     int,
    kind             varchar(16) not null,
    notified_at      timestamptz not null default now(),
    detail           text,
    unique (baseline_id, stat_date, kind),
    constraint ck_baseline_event_kind check (kind in ('PREDICT_LATE', 'BREACH', 'RECOVERED'))
);

create index if not exists idx_baseline_event_date
    on platform.baseline_event (baseline_id, stat_date desc, notified_at desc);
