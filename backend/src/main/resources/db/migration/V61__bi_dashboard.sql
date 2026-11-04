-- 数据可视化（BI）：看板 = 一组图表（每个图表一条只读 SQL）。
-- 与数据服务/采集一致：开发态可改，冒烟测试通过后才能发布；
-- 生产态是快照（bi_dashboard_release.snapshot），改开发态不影响线上。

create table if not exists platform.bi_dashboard (
    id           bigserial primary key,
    project_id   bigint not null references platform.project(id) on delete cascade,
    code         varchar(64) not null,
    name         varchar(128) not null,
    description  text,
    status       varchar(16) not null default 'DRAFT',
    version      int not null default 0,
    tenant_id    varchar(64) not null default 'default',
    smoke_hash   varchar(64),
    smoke_ok_at  timestamptz,
    smoke_by     varchar(128),
    created_by   varchar(128),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    published_at timestamptz,
    unique (tenant_id, project_id, code)
);

create index if not exists idx_bi_dashboard_project
    on platform.bi_dashboard (tenant_id, project_id);

create table if not exists platform.bi_chart (
    id            bigserial primary key,
    dashboard_id  bigint not null references platform.bi_dashboard(id) on delete cascade,
    chart_type    varchar(16) not null default 'TABLE',
    name          varchar(128) not null,
    datasource_id bigint references platform.datasource(id) on delete restrict,
    sql_text      text not null,
    x_column      varchar(128),
    y_column      varchar(128),
    series_column varchar(128),
    sort_order    int not null default 0
);

create index if not exists idx_bi_chart_dashboard
    on platform.bi_chart (dashboard_id, sort_order, id);

create table if not exists platform.bi_dashboard_release (
    id            bigserial primary key,
    dashboard_id  bigint not null references platform.bi_dashboard(id) on delete cascade,
    version       int not null,
    snapshot      jsonb not null,
    published_at  timestamptz not null default now(),
    published_by  varchar(128),
    unique (dashboard_id, version)
);

insert into platform.permission_catalog (code, name, group_name, danger) values
  ('bi:write',   '建/改数据看板', '数据可视化', '中'),
  ('bi:publish', '发布/下线数据看板', '数据可视化', '中')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, p.code
from platform.role r, (values ('bi:write'), ('bi:publish')) p(code)
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;
