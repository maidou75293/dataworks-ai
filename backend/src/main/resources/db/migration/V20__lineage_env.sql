-- 血缘按环境隔离：DEV / PROD 各自维护最新上下游
alter table platform.lineage_edge
    add column if not exists env varchar(16) not null default 'DEV';

-- 同一版本可以同时发布到 DEV 与 PROD，唯一键必须带上 env
drop index if exists platform.ux_lineage_edge;
create unique index if not exists ux_lineage_edge
    on platform.lineage_edge (workflow_id, version_no, task_code,
                              source_table, target_table,
                              source_column, target_column, edge_type, origin,
                              env, coalesce(run_id, ''));

create index if not exists ix_lineage_edge_env_origin
    on platform.lineage_edge (env, origin, occurred_at desc);
