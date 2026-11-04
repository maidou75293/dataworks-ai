-- 流开发上线模型（对齐数据服务/BI 的「冒烟 → 发布快照」）：
--   flink_sql_job 是 DEV 开发态；冒烟记录 SQL 指纹，发布生成不可变快照；
--   线上启停跑快照，不跑编辑态 SQL。
alter table platform.flink_sql_job
    add column if not exists smoke_hash      varchar(64),
    add column if not exists smoke_at        timestamptz,
    add column if not exists smoke_by        varchar(128),
    add column if not exists published_version int,
    add column if not exists published_at    timestamptz,
    add column if not exists published_by    varchar(128),
    add column if not exists prod_job_id     varchar(64),
    add column if not exists prod_session    varchar(64),
    add column if not exists prod_status     varchar(16) not null default 'OFFLINE';

create table if not exists platform.flink_sql_release (
    id            bigserial primary key,
    job_id        bigint      not null references platform.flink_sql_job (id) on delete cascade,
    version       int         not null,
    sql_text      text        not null,
    session_mode  varchar(16) not null default 'ISOLATED',
    created_at    timestamptz not null default now(),
    created_by    varchar(128),
    unique (job_id, version)
);

create index if not exists idx_flink_release_job
    on platform.flink_sql_release (job_id, version desc);

insert into platform.permission_catalog (code, name, group_name, danger) values
  ('flink:publish', '发布/下线 FlinkSQL 流任务', '实时开发', '高')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, 'flink:publish'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;
