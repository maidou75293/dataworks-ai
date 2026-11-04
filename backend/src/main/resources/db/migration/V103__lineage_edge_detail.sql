-- V103：血缘边补 catalog / db / confidence（2026-10-26）
--
-- 节点 ID 统一为 catalog.db.table；表级/列级解析都需要知道层级，
-- 只有 source_table 一个字符串时无法区分「库.表」和「表」。
--
-- confidence：
--   measured  由引擎计划（Doris EXPLAIN / Flink job plan）确认过
--   inferred  静态解析（jsqlparser / 正则兜底），可能有误，前端需标注
--
-- 回填只拆分层级字段，不改写 source_table / target_table：历史行里的完整名字
-- 仍可读，新的写入统一写三段式。

alter table platform.lineage_edge
    add column if not exists source_catalog varchar(128) not null default '',
    add column if not exists source_db      varchar(128) not null default '',
    add column if not exists target_catalog varchar(128) not null default '',
    add column if not exists target_db      varchar(128) not null default '',
    add column if not exists confidence     varchar(16)  not null default 'inferred';

comment on column platform.lineage_edge.confidence is
    'measured = 引擎计划确认；inferred = 静态解析推断（V103）';
comment on column platform.lineage_edge.source_catalog is
    '节点 ID 第一段；历史行按 source_table 的点分段回填，单段默认 doris';

-- 历史行回填：>=3 段 → catalog.db.table；2 段 → db.table；1 段 → table
update platform.lineage_edge
   set source_catalog = case
           when array_length(string_to_array(source_table, '.'), 1) >= 3
               then split_part(source_table, '.', 1)
           else 'doris' end,
       source_db = case
           when array_length(string_to_array(source_table, '.'), 1) >= 3
               then split_part(source_table, '.', 2)
           when array_length(string_to_array(source_table, '.'), 1) = 2
               then split_part(source_table, '.', 1)
           else '' end
 where source_catalog = '' and source_table <> '';

update platform.lineage_edge
   set target_catalog = case
           when array_length(string_to_array(target_table, '.'), 1) >= 3
               then split_part(target_table, '.', 1)
           else 'doris' end,
       target_db = case
           when array_length(string_to_array(target_table, '.'), 1) >= 3
               then split_part(target_table, '.', 2)
           when array_length(string_to_array(target_table, '.'), 1) = 2
               then split_part(target_table, '.', 1)
           else '' end
 where target_catalog = '' and target_table <> '';

-- 按节点查上下游：catalog.db.table 三段等值
create index if not exists ix_lineage_target_node
    on platform.lineage_edge (tenant_id, target_catalog, target_db, target_table);

create index if not exists ix_lineage_source_node
    on platform.lineage_edge (tenant_id, source_catalog, source_db, source_table);

-- 置信度筛选（前端默认显示 measured + inferred，可单独筛）
create index if not exists ix_lineage_confidence
    on platform.lineage_edge (tenant_id, confidence, occurred_at desc);
