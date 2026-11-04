-- 项目存储后端三选一：doris（默认，存量行为）/ hdfs / s3(MinIO)。
--
-- 存量项目全部回填 doris：不传 storageType 的老接口、老脚本行为完全不变。
-- 路径字段只对 hdfs/s3 有意义：
--   hdfs → /warehouse/<projectCode>/dev|prd
--   s3   → s3://<bucket>/<projectCode>/dev|prd/
-- 幂等：重复执行不会报错，也不会覆盖已有取值。

alter table platform.project add column if not exists storage_type varchar(16) not null default 'doris';
alter table platform.project add column if not exists storage_dev_path varchar(512);
alter table platform.project add column if not exists storage_prod_path varchar(512);

update platform.project set storage_type = 'doris'
 where storage_type is null or storage_type = '';

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.project'::regclass
           and conname = 'project_storage_type_check'
    ) then
        alter table platform.project
            add constraint project_storage_type_check
            check (storage_type in ('doris', 'hdfs', 's3'));
    end if;
end $$;
