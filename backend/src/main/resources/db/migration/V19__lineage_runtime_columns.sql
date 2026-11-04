-- 血缘升级：列级、来源（发布期/运行期）、运行标识
alter table platform.lineage_edge
    add column if not exists source_column varchar(128) not null default '',
    add column if not exists target_column varchar(128) not null default '',
    add column if not exists edge_type varchar(8) not null default 'TABLE',
    add column if not exists origin varchar(16) not null default 'PUBLISH',
    add column if not exists run_id varchar(64),
    add column if not exists occurred_at timestamptz not null default now();

-- 原唯一索引不含列级/来源，重建；run_id 为空时用空串参与唯一性
drop index if exists platform.ux_lineage_edge;
create unique index if not exists ux_lineage_edge
    on platform.lineage_edge (workflow_id, version_no, task_code,
                              source_table, target_table,
                              source_column, target_column, edge_type, origin,
                              coalesce(run_id, ''));

create index if not exists ix_lineage_edge_origin
    on platform.lineage_edge (origin, occurred_at desc);
