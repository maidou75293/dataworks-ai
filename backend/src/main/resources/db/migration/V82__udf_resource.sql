-- v447：UDF 资源管理 + 派生表 tenant_id 回填。
--
-- 背景：V56 给 project/workflow/datasource 等加了 tenant_id，但派生表
-- （workflow_version / wf_instance / sync_task / data_service / quality_rule /
-- alert_rule / lineage_edge）的写入没有带上它，新行一律落在默认值 'default'。
-- 结果是 tenant_b 自己建的服务/任务/规则/运行实例在按 tenant_id 过滤的列表里
-- **看不见自己的数据**。这里统一回填；查询侧同时改为按项目归属判定，
-- 回填只是让派生列与事实一致，不再作为唯一的判权依据。

update platform.workflow_version v
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = v.workflow_id and v.tenant_id <> w.tenant_id;

update platform.wf_instance i
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = i.workflow_id and i.tenant_id <> w.tenant_id;

update platform.lineage_edge e
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = e.workflow_id and e.tenant_id <> w.tenant_id;

update platform.sync_task t
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = t.project_id and t.tenant_id <> p.tenant_id;

update platform.data_service s
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = s.project_id and s.tenant_id <> p.tenant_id;

update platform.quality_rule q
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = q.project_id and q.tenant_id <> p.tenant_id;

update platform.alert_rule r
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = r.project_id and r.tenant_id <> p.tenant_id;

-- ---------- UDF 资源 ----------

-- 一个 jar 每次上传是一行（version 自增）；active 表示当前启用的版本。
create table if not exists platform.udf_resource (
    id                bigserial    primary key,
    project_id        bigint       not null references platform.project (id) on delete cascade,
    tenant_id         varchar(64)  not null default 'default',
    name              varchar(64)  not null,
    type              varchar(16)  not null default 'jar',
    version           integer      not null,
    size_bytes        bigint       not null,
    checksum          varchar(64)  not null,
    storage_key       varchar(512) not null,
    download_token    varchar(64)  not null,
    original_filename varchar(255),
    uploader          varchar(64)  not null,
    active            boolean      not null default false,
    created_at        timestamptz  not null default now(),
    unique (project_id, name, version)
);

create unique index if not exists uq_udf_resource_active
    on platform.udf_resource (project_id, name) where active;

create index if not exists idx_udf_resource_project
    on platform.udf_resource (tenant_id, project_id);

-- 函数注册：name 是用户填的名字，full_name 是加了项目前缀后的引擎内名字。
-- Doris 函数名全局唯一，必须带 p{projectId}_ 前缀，否则不同项目会互相覆盖。
create table if not exists platform.udf_function (
    id           bigserial    primary key,
    project_id   bigint       not null references platform.project (id) on delete cascade,
    tenant_id    varchar(64)  not null default 'default',
    resource_name varchar(64) not null,
    engine       varchar(16)  not null,
    name         varchar(64)  not null,
    full_name    varchar(128) not null,
    class_name   varchar(255) not null,
    return_type  varchar(64)  not null,
    arg_types    varchar(255) not null default '',
    status       varchar(16)  not null default 'DRAFT',
    created_by   varchar(64),
    created_at   timestamptz  not null default now(),
    published_by varchar(64),
    published_at timestamptz,
    updated_at   timestamptz  not null default now(),
    constraint udf_function_engine_check check (engine in ('DORIS', 'FLINK')),
    constraint udf_function_status_check check (status in ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    unique (project_id, engine, name)
);

create index if not exists idx_udf_function_project
    on platform.udf_function (tenant_id, project_id);

insert into platform.permission_catalog (code, name, group_name, danger) values
  ('udf:write',   '上传/编辑/删除 UDF 资源', '函数管理', '中'),
  ('udf:publish', '注册/停用 UDF 函数',     '函数管理', '高')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, p.code
from platform.role r, (values ('udf:write'), ('udf:publish')) p(code)
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;
