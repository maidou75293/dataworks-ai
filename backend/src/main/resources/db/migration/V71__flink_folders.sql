-- 流开发对齐数据开发：任务（脚本）放在项目目录树里。
-- 目录单独一张表（允许空目录），任务行上存相对路径。
create table if not exists platform.flink_folder (
    id         bigserial primary key,
    project_id bigint       not null references platform.project (id) on delete cascade,
    path       varchar(512) not null,
    tenant_id  varchar(64)  not null default 'default',
    created_at timestamptz  not null default now(),
    unique (project_id, path)
);

alter table platform.flink_sql_job
    add column if not exists folder varchar(512) not null default '';

create index if not exists idx_flink_folder_project
    on platform.flink_folder (project_id);
