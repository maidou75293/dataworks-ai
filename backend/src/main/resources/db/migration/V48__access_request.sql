-- 权限申请：租户 → 纳管的库/表 → 申请读取/写入 → 负责人审批
-- 演示数据：另一个租户 tenant_b 的用户在 tenant_b schema 下建了两张表

alter table platform.app_user
    add column if not exists tenant varchar(64) not null default 'default';

create table if not exists platform.managed_table (
    id          bigserial primary key,
    tenant      varchar(64)  not null,
    catalog     varchar(64)  not null,
    schema_name varchar(128) not null,
    table_name  varchar(128) not null,
    owner_user  varchar(64)  not null,
    description text,
    created_at  timestamptz  not null default now(),
    unique (tenant, catalog, schema_name, table_name)
);

create table if not exists platform.access_request (
    id               bigserial primary key,
    requester        varchar(64)  not null,
    requester_tenant varchar(64)  not null default 'default',
    resource_type    varchar(16)  not null,
    catalog          varchar(64)  not null,
    schema_name      varchar(128) not null,
    table_name       varchar(128),
    permission       varchar(16)  not null,
    reason           text,
    status           varchar(16)  not null default 'PENDING',
    approver         varchar(64),
    comment          text,
    created_at       timestamptz  not null default now(),
    decided_at       timestamptz,
    constraint access_request_type check (resource_type in ('DATABASE', 'TABLE')),
    constraint access_request_perm check (permission in ('READ', 'WRITE')),
    constraint access_request_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

create index if not exists idx_access_request_requester
    on platform.access_request (requester, status);
create index if not exists idx_access_request_status
    on platform.access_request (status);

-- 另一个租户的物理表：tenant_b.orders / tenant_b.customers
create schema if not exists tenant_b;

create table if not exists tenant_b.orders (
    id         bigserial primary key,
    customer   varchar(64)    not null,
    amount     numeric(12, 2) not null,
    created_at timestamptz    not null default now()
);

create table if not exists tenant_b.customers (
    id      bigserial primary key,
    name    varchar(64) not null,
    region  varchar(32) not null
);

insert into tenant_b.orders (customer, amount)
select v.customer, v.amount
from (values ('租户B客户A', 120.00::numeric), ('租户B客户B', 88.50::numeric))
    as v(customer, amount)
where not exists (select 1 from tenant_b.orders);

insert into tenant_b.customers (name, region)
select v.name, v.region
from (values ('租户B客户A', '华东'), ('租户B客户B', '华南'))
    as v(name, region)
where not exists (select 1 from tenant_b.customers);

insert into platform.managed_table (tenant, catalog, schema_name, table_name, owner_user, description)
values
    ('tenant_b', 'PG', 'tenant_b', 'orders', 'tenant_b', '租户B的订单表（权限申请演示）'),
    ('tenant_b', 'PG', 'tenant_b', 'customers', 'tenant_b', '租户B的客户表（权限申请演示）'),
    ('default', 'PG', 'platform', 'demo_metric', 'admin', '平台示例指标表')
on conflict (tenant, catalog, schema_name, table_name) do nothing;
