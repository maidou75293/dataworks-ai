-- V99：数据源查询能力（单引擎 Doris，2026-10-25）
--
-- SQL 控制台只有 Doris 一条执行链路；其他数据源要么是「入湖/导入的来源」，
-- 要么是 JDBC 源（可在 Doris 里通过多源目录 join，但执行仍在 Doris）。
-- 这里把「能不能用 SQL 查」变成数据源上的显式属性，供查询前置校验与补全使用：
--   engine    doris / kafka / fileset / jdbc
--   queryable 只有 kafka 与文件类为 false，其余为 true
--
-- 回填口径（按 ds_type，大小写不敏感）：
--   DORIS                                   -> doris  / true
--   KAFKA                                   -> kafka  / false
--   S3FILE / LOCALFILE / FILESET / FILE / SFTPFILE -> fileset / false
--   POSTGRESQL / MYSQL / CLICKHOUSE 等 JDBC -> jdbc   / true
--
-- 幂等：ADD COLUMN IF NOT EXISTS；回填是 ds_type 的确定映射，重复执行结果一致。
-- 列刚加出来时所有行都是默认值，因此首次回填不会覆盖任何人工设置。

alter table platform.datasource
    add column if not exists queryable boolean not null default true;
alter table platform.datasource
    add column if not exists engine varchar(16) not null default 'doris';

comment on column platform.datasource.queryable is
    '是否支持 SQL 查询（V99）：Kafka / 文件类为 false，仅可入湖或导入';
comment on column platform.datasource.engine is
    '执行引擎分类（V99）：doris / kafka / fileset / jdbc；平台的 SQL 控制台固定走 doris';

-- ---------------------------------------------------------------- 1. 按类型回填

update platform.datasource
   set engine = case
           when upper(ds_type) = 'DORIS' then 'doris'
           when upper(ds_type) = 'KAFKA' then 'kafka'
           when upper(ds_type) in ('S3FILE', 'LOCALFILE', 'FILESET', 'FILE',
                                   'SFTPFILE', 'LOCAL_FILE', 'S3') then 'fileset'
           else 'jdbc'
       end,
       queryable = case
           when upper(ds_type) in ('KAFKA', 'S3FILE', 'LOCALFILE', 'FILESET', 'FILE',
                                   'SFTPFILE', 'LOCAL_FILE', 'S3') then false
           else true
       end;

-- ---------------------------------------------------------------- 2. 取值约束

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.datasource'::regclass
           and conname = 'datasource_engine_check'
    ) then
        alter table platform.datasource
            add constraint datasource_engine_check
            check (engine in ('doris', 'kafka', 'fileset', 'jdbc'));
    end if;
end $$;

-- 查询前置校验会按租户取一次数据源能力，走 (tenant_id, engine) 前缀
create index if not exists idx_datasource_tenant_engine
    on platform.datasource (tenant_id, engine);
