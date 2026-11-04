-- UDF 资源版本变更记录（2026-10-25，CC-B）。
--
-- V89 已落 Python/文件夹；V90 被 C 的迁移占用，本模块用 V91。
-- 记录资源的上传/启用/删除/目录移动，供界面「版本记录」展示；
-- resource_id 允许为空：资源删除后事件仍保留，用于审计回看。

create table if not exists platform.udf_resource_event (
    id            bigserial    primary key,
    tenant_id     varchar(64)  not null default 'default',
    project_id    bigint       not null references platform.project (id) on delete cascade,
    resource_id   bigint       references platform.udf_resource (id) on delete set null,
    resource_name varchar(64)  not null,
    version       integer,
    action        varchar(32)  not null,
    actor         varchar(64),
    detail        text,
    created_at    timestamptz  not null default now(),
    constraint udf_resource_event_action_check
        check (action in ('UPLOAD', 'ACTIVATE', 'DELETE', 'MOVE'))
);

create index if not exists idx_udf_resource_event_lookup
    on platform.udf_resource_event (tenant_id, project_id, resource_name, created_at desc);
create index if not exists idx_udf_resource_event_resource
    on platform.udf_resource_event (resource_id, created_at desc);
