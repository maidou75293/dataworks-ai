-- V87：表编辑（DDL 工单）。
--
-- 背景：表管理此前只有建库/建表/删表/详情/预览，全仓没有 ALTER TABLE 路径；
-- 用户只能去查询页手写 DDL，绕过预检、审批和审计。这里落地表编辑工单：
--   * table_ddl_job        每次 DDL 一张单（谁/何时/哪张表/DDL 前后/耗时/结果）
--   * table_ddl_job_event  状态迁移时间线
--
-- 设计见 docs/表编辑设计-20261025.md 第六节（编号按仓库实际顺延到 V87）。
-- 同一张表同一时间只允许一个活跃工单：partial unique index 兜住跨进程的并发。
-- 全部按 tenant_id 收口；工单按 id 直取时必须带 tenant_id（不暴露存在性）。

create table if not exists platform.table_ddl_job (
    id                     bigserial    primary key,
    tenant_id              varchar(64)  not null default 'default',
    project_id             bigint       references platform.project (id) on delete set null,
    doris_connection_id    bigint       references platform.warehouse_connection (id) on delete set null,
    connection_key         varchar(64)  not null default 'builtin',
    database_name          varchar(128) not null,
    table_name             varchar(128),
    env                    varchar(16)  not null default 'DEV',
    operation              varchar(32)  not null,
    params_json            jsonb        not null default '{}'::jsonb,
    base_ddl               text,
    base_ddl_hash          varchar(64),
    ddl_sql                text         not null,
    ddl_hash               varchar(64),
    ddl_after_hash         varchar(64),
    rollback_sql           text,
    impact_json            jsonb        not null default '{}'::jsonb,
    status                 varchar(32)  not null,
    version                int          not null default 0,
    reason                 text,
    requested_by           varchar(128) not null,
    requested_at           timestamptz  not null default now(),
    approver               varchar(128),
    approved_at            timestamptz,
    approval_comment       text,
    started_at             timestamptz,
    finished_at            timestamptz,
    duration_ms            bigint,
    doris_job_id           varchar(64),
    doris_state            varchar(32),
    doris_progress         varchar(32),
    last_polled_at         timestamptz,
    error_code             varchar(64),
    error_message          text,
    retryable              boolean      not null default false,
    request_id             varchar(64),
    client_ip              varchar(64),
    constraint table_ddl_job_status_check check (status in (
        'PENDING_APPROVAL', 'PENDING', 'RUNNING', 'FINISHED',
        'FAILED', 'CANCELLED', 'REJECTED')),
    constraint table_ddl_job_operation_check check (operation in (
        'CREATE_TABLE', 'CREATE_DATABASE', 'DROP_TABLE',
        'ALTER_ADD_COLUMN', 'ALTER_MODIFY_COLUMN', 'ALTER_DROP_COLUMN',
        'ALTER_SET_PROPERTIES', 'ALTER_RENAME_TABLE', 'TRUNCATE_TABLE')),
    constraint table_ddl_job_env_check check (env in ('DEV', 'PROD', 'UNKNOWN'))
);

-- 同一张表最多一个活跃工单；终态（FINISHED/FAILED/CANCELLED/REJECTED）不占用。
create unique index if not exists uq_table_ddl_active
    on platform.table_ddl_job
       (tenant_id, connection_key, lower(database_name), lower(coalesce(table_name, '')))
    where status in ('PENDING_APPROVAL', 'PENDING', 'RUNNING');

create index if not exists idx_table_ddl_job_lookup
    on platform.table_ddl_job (tenant_id, database_name, table_name, requested_at desc);

create index if not exists idx_table_ddl_job_status
    on platform.table_ddl_job (status, requested_at);

create index if not exists idx_table_ddl_job_requester
    on platform.table_ddl_job (tenant_id, requested_by, requested_at desc);

create table if not exists platform.table_ddl_job_event (
    id         bigserial    primary key,
    job_id     bigint       not null references platform.table_ddl_job (id) on delete cascade,
    tenant_id  varchar(64)  not null default 'default',
    action     varchar(64)  not null,
    actor      varchar(128),
    detail     text,
    created_at timestamptz  not null default now()
);

create index if not exists idx_table_ddl_job_event_job
    on platform.table_ddl_job_event (job_id, created_at);
