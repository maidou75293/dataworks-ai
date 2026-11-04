-- 开发 / 生产分离：数据服务与数据采集都要「先测试、再发布」，生产配置不可直接改。
--
-- 数据服务：
--   data_service                = 开发态（可随意改，改完冒烟测试失效）
--   data_service_release        = 生产态快照（/api/ds/{code} 只读这份，改开发态不影响线上）
--   smoke_hash                  = 最近一次冒烟测试通过的开发态内容指纹
--
-- 数据采集（sync_task）：
--   released = false 为开发态（可编辑、可手动试运行）；
--   released = true  为生产态（配置冻结，调度器只跑已发布任务）。
--   发布条件：最近一次修改之后有过一次成功运行（last_success_at 非空）。

alter table platform.data_service add column if not exists smoke_hash varchar(64);
alter table platform.data_service add column if not exists smoke_ok_at timestamptz;
alter table platform.data_service add column if not exists smoke_by varchar(128);

create table if not exists platform.data_service_release (
    id            bigserial primary key,
    service_id    bigint not null references platform.data_service(id) on delete cascade,
    version       int not null,
    method        varchar(8) not null,
    datasource_id bigint,
    sql_text      text not null,
    params_json   text,
    published_at  timestamptz not null default now(),
    published_by  varchar(128),
    unique (service_id, version)
);

create index if not exists idx_ds_release_service
    on platform.data_service_release (service_id, version desc);

-- 已发布的服务补一条生产快照：升级后线上行为保持不变
insert into platform.data_service_release
    (service_id, version, method, datasource_id, sql_text, params_json,
     published_at, published_by)
select s.id, s.version, s.method, s.datasource_id, s.sql_text, s.params_json,
       coalesce(s.published_at, now()), s.created_by
from platform.data_service s
where s.status = 'PUBLISHED'
on conflict (service_id, version) do nothing;

alter table platform.sync_task add column if not exists released boolean not null default false;
alter table platform.sync_task add column if not exists released_at timestamptz;
alter table platform.sync_task add column if not exists released_by varchar(128);

-- 已经在跑周期的采集任务视为已发布，避免升级后调度突然停掉
update platform.sync_task
   set released = true,
       released_at = coalesce(last_success_at, now())
 where schedule_enabled = true;
