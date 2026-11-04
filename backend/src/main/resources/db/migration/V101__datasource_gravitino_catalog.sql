-- V101：数据源 → Gravitino Catalog 同步（2026-10-25）
--
-- 口径：
--   1) 只有 type=DORIS 的数据源会注册到 Gravitino（provider=jdbc-doris）；
--      Catalog 名 = <tenant>_<slug(数据源名)>，由后端生成并落 gravitino_catalog。
--   2) 同步失败不回滚数据源：保留用户配置，把可诊断原因写进 gravitino_message。
--   3) 状态：
--        unknown  未同步 / 旧数据
--        synced   已创建或已更新（ALTER）
--        error    最近一次同步失败
--        disabled metadata.source=direct，按开关跳过同步
--   4) metadata.source 是平台级开关（platform.setting）：gravitino（默认）/ direct。
--      本期只落开关与管理接口，不改变元数据读取链路（第二批再切）。
--
-- 幂等：ADD COLUMN IF NOT EXISTS；setting 用 ON CONFLICT DO NOTHING；
-- 约束先查 pg_constraint 再建，重复执行不会失败。

-- ---------------------------------------------------------------- 1. 数据源扩展

alter table platform.datasource
    add column if not exists gravitino_catalog varchar(128);
alter table platform.datasource
    add column if not exists gravitino_status varchar(16) not null default 'unknown';
alter table platform.datasource
    add column if not exists gravitino_message text;
alter table platform.datasource
    add column if not exists gravitino_synced_at timestamptz;

comment on column platform.datasource.gravitino_catalog is
    'V101：DORIS 数据源在 Gravitino 中的 Catalog 名（<tenant>_<slug(name)>）';
comment on column platform.datasource.gravitino_status is
    'V101：Gravitino 同步状态 unknown / synced / error / disabled';
comment on column platform.datasource.gravitino_message is
    'V101：最近一次同步失败的可诊断原因（截断 1000 字）';
comment on column platform.datasource.gravitino_synced_at is
    'V101：最近一次 CREATE/ALTER Gravitino Catalog 成功时间';

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.datasource'::regclass
           and conname = 'datasource_gravitino_status_check'
    ) then
        alter table platform.datasource
            add constraint datasource_gravitino_status_check
            check (gravitino_status in ('unknown', 'synced', 'error', 'disabled'));
    end if;
end $$;

-- 列表/巡检按租户 + 类型找 DORIS 数据源
create index if not exists idx_datasource_tenant_gravitino
    on platform.datasource (tenant_id, ds_type)
 where ds_type = 'DORIS';

-- ---------------------------------------------------------------- 2. 元数据来源开关

insert into platform.setting (setting_key, setting_value, updated_by)
values ('metadata.source', 'gravitino', 'migration')
on conflict (setting_key) do nothing;

-- ---------------------------------------------------------------- 3. 旧数据

-- 内置 Doris 由平台迁移维护，不参与数据源 ↔ Catalog 同步；标成 disabled 而不是
-- unknown，页面才不会提示用户去「同步」一个根本不该同步的内置源。
update platform.datasource
   set gravitino_status = 'disabled',
       gravitino_message = coalesce(gravitino_message,
           '内置 Doris 由平台迁移维护，不参与 Gravitino Catalog 同步')
 where upper(ds_type) = 'DORIS'
   and coalesce(config_json ->> 'builtin', 'false') = 'true'
   and gravitino_catalog is null
   and gravitino_synced_at is null;

-- 其余「没有同步过」的 DORIS 数据源标成 unknown，不猜测 Catalog 名：
-- 名称/租户的映射必须由后端按当前规则生成，迁移里硬编码会写错。
update platform.datasource
   set gravitino_status = 'unknown',
       gravitino_message = coalesce(gravitino_message,
           'V101 之前创建的数据源尚未同步，保存一次即可注册 Catalog')
 where upper(ds_type) = 'DORIS'
   and coalesce(config_json ->> 'builtin', 'false') <> 'true'
   and gravitino_catalog is null
   and gravitino_status = 'unknown'
   and gravitino_synced_at is null;
