-- UDF：Python 资源 + 多级文件夹（CC-B，2026-10-25）。
--
-- 路由表最初写的是 V83，但基线已经用到 V86；V87 又与 2026-10-25 17:31 事故中已应用到
-- 共享库的迁移号冲突（Flyway checksum 不匹配导致后端 CrashLoop）。因此本文件拟用下一空号 V89
-- （当前共享库 schemaVersion=88，待主控确认），不再复用 V87。
--
-- 兼容：存量资源一律 language='java'、type='jar'、folder_id=null（根目录），行为不变。

create table if not exists platform.udf_folder (
    id          bigserial    primary key,
    project_id  bigint       not null references platform.project (id) on delete cascade,
    tenant_id   varchar(64)  not null default 'default',
    parent_id   bigint       references platform.udf_folder (id) on delete cascade,
    name        varchar(64)  not null,
    path        varchar(1024) not null,
    created_by  varchar(64),
    created_at  timestamptz  not null default now()
);

-- PostgreSQL 的 UNIQUE 把 NULL 当成互不相等；根目录（parent_id is null）要用 coalesce 才能同层去重。
create unique index if not exists uq_udf_folder_name
    on platform.udf_folder (project_id, coalesce(parent_id, 0), lower(name));
create unique index if not exists uq_udf_folder_path
    on platform.udf_folder (project_id, path);
create index if not exists idx_udf_folder_project
    on platform.udf_folder (tenant_id, project_id, parent_id);
create index if not exists idx_udf_folder_path
    on platform.udf_folder (project_id, path);

alter table platform.udf_resource
    add column if not exists folder_id bigint;
alter table platform.udf_resource
    add column if not exists language varchar(16) not null default 'java';
-- 解析结果：Python 顶层函数候选 / requirements / wheel 包名等；不参与执行判定，只给界面和排障用。
alter table platform.udf_resource
    add column if not exists metadata jsonb not null default '{}'::jsonb;

alter table platform.udf_resource
    drop constraint if exists udf_resource_folder_fk;
alter table platform.udf_resource
    add constraint udf_resource_folder_fk
        foreign key (folder_id) references platform.udf_folder (id) on delete set null;

alter table platform.udf_resource
    drop constraint if exists udf_resource_type_check;
alter table platform.udf_resource
    add constraint udf_resource_type_check
        check (type in ('jar', 'py', 'zip', 'whl', 'txt'));

alter table platform.udf_resource
    drop constraint if exists udf_resource_language_check;
alter table platform.udf_resource
    add constraint udf_resource_language_check
        check (language in ('java', 'python'));

-- 存量 jar：明确回填，避免默认值依赖迁移执行时的列默认行为。
update platform.udf_resource set type = 'jar' where type is null or type = '';
update platform.udf_resource set language = 'java' where language is null or language = '';

create index if not exists idx_udf_resource_folder
    on platform.udf_resource (project_id, folder_id, name);

alter table platform.udf_function
    add column if not exists language varchar(16) not null default 'java';
update platform.udf_function set language = 'java' where language is null or language = '';

alter table platform.udf_function
    drop constraint if exists udf_function_language_check;
alter table platform.udf_function
    add constraint udf_function_language_check
        check (language in ('java', 'python'));
