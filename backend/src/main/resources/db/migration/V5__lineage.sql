-- 表级血缘（best-effort）：发布工作流版本时解析 SQL 任务得到。
-- target_table 为空串表示只读（SELECT / 质量校验）。
create table if not exists platform.lineage_edge (
    id bigserial primary key,
    workflow_id bigint not null references platform.workflow (id) on delete cascade,
    version_no integer not null,
    task_code varchar(64) not null,
    source_table varchar(255) not null,
    target_table varchar(255) not null default '',
    created_at timestamptz not null default now()
);

create unique index if not exists ux_lineage_edge
    on platform.lineage_edge (workflow_id, version_no, task_code, source_table, target_table);

create index if not exists ix_lineage_edge_source on platform.lineage_edge (source_table);
create index if not exists ix_lineage_edge_target on platform.lineage_edge (target_table);
