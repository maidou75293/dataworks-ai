-- V100：Iceberg 湖数据源（Doris 外部 Catalog，2026-10-25）
--
-- 形态：
--   1) 数据源 ds_type = 'ICEBERG'，engine = 'lake'，queryable = true；
--      config_json 保存 catalog / catalogType / warehouse / endpoint / region / uri，
--      AK/SK 仍走 datasource.username / password（password 加密，不回显）。
--   2) 每建/改一次数据源，后端用 Doris 管理员账号执行：
--        CREATE CATALOG / ALTER CATALOG ... SET PROPERTIES
--        GRANT SELECT_PRIV ON `<catalog>`.`<db>`.* TO '<service-account>'@'%'
--        GRANT SELECT_PRIV ON `<catalog>`.*.* TO '<service-account>'@'%'
--      平台业务账号（doris.user，默认 dataworks）只有 SELECT，没有 CREATE CATALOG 权限。
--   3) 授权结果落 lake_catalog_grant，便于页面展示「已授权的库」与刷新时对账；
--      删除数据源时级联清理。
--
-- 状态：
--   lake_status = unknown（未检测）/ available（可用）/ error（不可用，原因写入 message）
--   连通性检测 = SHOW DATABASES FROM <catalog>（能触发 Iceberg/S3 元数据加载）。
--
-- 幂等：ADD COLUMN IF NOT EXISTS；约束先 drop 再 add；回填是按 ds_type 的确定映射。

-- ---------------------------------------------------------------- 1. 数据源扩展

alter table platform.datasource
    add column if not exists lake_catalog varchar(128);
alter table platform.datasource
    add column if not exists lake_status varchar(16) not null default 'unknown';
alter table platform.datasource
    add column if not exists lake_status_message text;
alter table platform.datasource
    add column if not exists lake_synced_at timestamptz;

comment on column platform.datasource.lake_catalog is
    'V100：Iceberg 数据源在 Doris 中的 Catalog 名称（ds_type=ICEBERG）';
comment on column platform.datasource.lake_status is
    'V100：湖 Catalog 状态 unknown / available / error';
comment on column platform.datasource.lake_status_message is
    'V100：最近一次探测/下发失败的可诊断原因（截断 1000 字）';
comment on column platform.datasource.lake_synced_at is
    'V100：最近一次 CREATE/ALTER/REFRESH CATALOG 成功时间';

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.datasource'::regclass
           and conname = 'datasource_lake_status_check'
    ) then
        alter table platform.datasource
            add constraint datasource_lake_status_check
            check (lake_status in ('unknown', 'available', 'error'));
    end if;
end $$;

-- engine 增加 lake（V99 的约束只允许 doris/kafka/fileset/jdbc）
alter table platform.datasource drop constraint if exists datasource_engine_check;
alter table platform.datasource
    add constraint datasource_engine_check
    check (engine in ('doris', 'kafka', 'fileset', 'jdbc', 'lake'));

update platform.datasource
   set engine = 'lake', queryable = true
 where upper(ds_type) = 'ICEBERG';

create index if not exists idx_datasource_tenant_lake
    on platform.datasource (tenant_id, ds_type)
 where ds_type = 'ICEBERG';

-- ---------------------------------------------------------------- 2. 授权对账表

create table if not exists platform.lake_catalog_grant (
    id            bigserial primary key,
    datasource_id bigint       not null
                  references platform.datasource (id) on delete cascade,
    tenant_id     varchar(64)  not null,
    catalog_name  varchar(128) not null,
    database_name varchar(128) not null,
    principal     varchar(128) not null,
    granted_at    timestamptz  not null default now(),
    unique (datasource_id, database_name, principal)
);

comment on table platform.lake_catalog_grant is
    'V100：湖 Catalog 授权对账（每次 refresh 按 SHOW DATABASES FROM catalog 重建）';

create index if not exists idx_lake_catalog_grant_ds
    on platform.lake_catalog_grant (datasource_id);
