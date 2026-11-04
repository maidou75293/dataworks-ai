-- FlinkSQL 流开发：一个任务 = 一段 SQL + 一组运行态字段。
--
-- 运行态不复制 Flink 的调度信息：job_id 指向会话集群里的 Job；
-- session_handle 用于停止时关闭 SQL Gateway 会话（网关重启后可能失效，
-- 停止作业仍可用 Flink REST 的 cancel，见 FlinkSqlService）。

create table if not exists platform.flink_sql_job (
    id             bigserial primary key,
    project_id     bigint       not null references platform.project (id) on delete cascade,
    code           varchar(64)  not null,
    name           varchar(128) not null,
    description    text,
    sql_text       text         not null default '',
    status         varchar(16)  not null default 'DRAFT',
    flink_job_id   varchar(64),
    session_handle varchar(64),
    last_error     text,
    submitted_at   timestamptz,
    stopped_at     timestamptz,
    tenant_id      varchar(64)  not null default 'default',
    created_by     varchar(128),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    unique (project_id, code),
    constraint flink_sql_job_status_check
        check (status in ('DRAFT', 'RUNNING', 'STOPPED', 'FAILED'))
);

create index if not exists idx_flink_sql_job_project
    on platform.flink_sql_job (tenant_id, project_id);

insert into platform.permission_catalog (code, name, group_name, danger) values
  ('flink:write', '建/改 FlinkSQL 流任务', '实时开发', '中'),
  ('flink:run',   '校验/提交/停止 FlinkSQL 流任务', '实时开发', '中')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, p.code
from platform.role r, (values ('flink:write'), ('flink:run')) p(code)
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;
