-- D3 表生命周期（2026-10-26）
-- 目标：热（在线）→ 冷（Doris Storage Policy 自动）→ 到期删分区（本表配置驱动）
-- 说明：归档导出/parquet 转换按用户要求不做。

alter table platform.managed_table
    add column if not exists retention_days    int         not null default 1095,
    add column if not exists cold_after_days   int         not null default 90,
    add column if not exists lifecycle_status  varchar(16) not null default 'ACTIVE';

comment on column platform.managed_table.retention_days is '保留天数，默认 3 年；超过即由定时任务删过期分区';
comment on column platform.managed_table.cold_after_days is '转冷天数（对应 Doris Storage Policy），仅作展示与策略选择依据';
comment on column platform.managed_table.lifecycle_status is 'ACTIVE / EXPIRING（30 天内到期）/ DELETED';

create table if not exists platform.lifecycle_job_log (
    id             bigserial primary key,
    table_id       bigint,
    tenant         varchar(64),
    catalog        varchar(64),
    schema_name    varchar(128),
    table_name     varchar(128),
    partition_name varchar(128),
    row_count      bigint,
    action         varchar(24)  not null,   -- EXPIRE_MARK / DROP_PARTITION / SKIP / ERROR
    detail         text,
    created_at     timestamptz  not null default now()
);

create index if not exists idx_lifecycle_log_table on platform.lifecycle_job_log (table_id, created_at desc);
