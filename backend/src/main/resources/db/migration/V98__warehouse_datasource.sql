-- V98：数仓统一为数据源（2026-10-25）
--
-- 语义变化：
--   1) 项目只引用一个 type=DORIS 的 platform.datasource（warehouse_datasource_id）；
--      空 = 平台内置 Doris。真正执行仍解析到 warehouse_connection，内置连接 id 为空。
--   2) warehouse_connection 增加「默认存储」（项目不感知，存储归属数据源）：
--      default_storage_type(none/hdfs/s3) / default_storage_endpoint / default_storage_path。
--   3) project 上的 storage_type / storage_dev_path / storage_prod_path /
--      hdfs_connection_id / s3_connection_id 保留但标记 DEPRECATED，后端不再读取。
--
-- 本迁移不回填存储：只补内置 Doris 数据源与 project.warehouse_datasource_id。
-- 幂等：重复执行不会重复插入内置数据源，也不会覆盖已写好的数据源存储。

-- ---------------------------------------------------------------- 1. 表结构

alter table platform.project
    add column if not exists warehouse_datasource_id bigint
        references platform.datasource (id) on delete set null;

alter table platform.warehouse_connection
    add column if not exists default_storage_type varchar(16) not null default 'none';
alter table platform.warehouse_connection
    add column if not exists default_storage_endpoint varchar(512);
alter table platform.warehouse_connection
    add column if not exists default_storage_path varchar(512);

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.warehouse_connection'::regclass
           and conname = 'warehouse_connection_default_storage_type_check'
    ) then
        alter table platform.warehouse_connection
            add constraint warehouse_connection_default_storage_type_check
            check (default_storage_type in ('none', 'hdfs', 's3'));
    end if;
end $$;

comment on column platform.project.warehouse_datasource_id is
    '项目使用的数仓数据源（type=DORIS）；空 = 平台内置 Doris';
comment on column platform.project.storage_type is
    'DEPRECATED：存储已归数仓连接（warehouse_connection.default_storage_type），此列仅为兼容保留';
comment on column platform.project.storage_dev_path is
    'DEPRECATED：存储已归数仓连接（warehouse_connection.default_storage_path）';
comment on column platform.project.storage_prod_path is
    'DEPRECATED：存储已归数仓连接（warehouse_connection.default_storage_path）';
comment on column platform.project.hdfs_connection_id is
    'DEPRECATED：存储已归数仓连接，此列仅为兼容保留';
comment on column platform.project.s3_connection_id is
    'DEPRECATED：存储已归数仓连接，此列仅为兼容保留';

create index if not exists idx_project_warehouse_datasource
    on platform.project (warehouse_datasource_id);

-- ---------------------------------------------------------------- 2. 内置 Doris 数据源

do $$
declare
    v_builtin_ds bigint;
    v_owner_project bigint;
begin
    -- 优先复用现成的 DORIS 数据源（生产库 id=19，jdbc 指向 doris-fe）
    select id into v_builtin_ds
      from platform.datasource
     where id = 19 and ds_type = 'DORIS';

    if v_builtin_ds is null then
        select id into v_builtin_ds
          from platform.datasource
         where ds_type = 'DORIS'
           and coalesce(config_json ->> 'builtin', 'false') = 'true'
         order by id
         limit 1;
    end if;

    if v_builtin_ds is null then
        select id into v_builtin_ds
          from platform.datasource
         where ds_type = 'DORIS'
           and jdbc_url ilike '%doris-fe%'
         order by id
         limit 1;
    end if;

    if v_builtin_ds is null then
        -- 内置数据源仍需要 project_id（历史表结构 NOT NULL）：挂到最早的项目上。
        -- V2 基线至少有一个 demo 项目，因此正常安装不会走到「找不到项目」。
        select id into v_owner_project from platform.project order by id limit 1;
        if v_owner_project is not null then
            insert into platform.datasource
                (project_id, name, ds_type, jdbc_url, username, password, config_json, tenant_id)
            values
                (v_owner_project, '内置 Doris', 'DORIS',
                 'jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/',
                 'dataworks', '', '{"builtin": true}'::json,
                 (select tenant_id from platform.project where id = v_owner_project))
            returning id into v_builtin_ds;
        end if;
    end if;

    if v_builtin_ds is not null then
        update platform.datasource
           set name = '内置 Doris',
               config_json = (coalesce(config_json::jsonb, '{}'::jsonb) || '{"builtin": true}'::jsonb)::json
         where id = v_builtin_ds;
        -- 回填：所有还没选数据源的项目统一指向内置 Doris
        update platform.project
           set warehouse_datasource_id = v_builtin_ds
         where warehouse_datasource_id is null;
    end if;
end $$;
