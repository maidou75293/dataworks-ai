-- 数据集成同步任务（首期：JDBC 源 -> Doris 目标）。
-- 密码按现有 platform.datasource 的做法存库；生产应改为 K8s Secret 引用。
create table if not exists platform.sync_task (
    id bigserial primary key,
    project_id bigint not null references platform.project (id) on delete cascade,
    code varchar(64) not null,
    name varchar(128) not null,
    source_jdbc_url text not null,
    source_driver varchar(128) not null default 'org.postgresql.Driver',
    source_user varchar(128) not null,
    source_password text not null,
    source_query text not null,
    sink_fenodes varchar(255) not null default '192.0.2.11:8030',
    sink_database varchar(128) not null,
    sink_table varchar(128) not null,
    sink_user varchar(128) not null,
    sink_password text not null,
    parallelism integer not null default 1,
    last_job_name varchar(128),
    last_status varchar(32),
    last_run_at timestamptz,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    unique (project_id, code)
);
