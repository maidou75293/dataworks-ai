-- 数仓连接：Doris / HDFS / S3 的内置或外部连接。
--
-- 项目用两个可空外键表达四组合：
--   doris_connection_id = NULL → 内置 Doris
--   hdfs_connection_id  = NULL → 内置 HDFS（三节点原生）
-- 两个都非空 / 一个非空 / 都为空，就是 Doris 内/外 × HDFS 内/外的四种组合。
--
-- 密码列存 SecretCipher 的密文，接口永不回显。

create table if not exists platform.warehouse_connection (
    id                 bigserial primary key,
    tenant_id          varchar(64)  not null,
    name               varchar(128) not null,
    type               varchar(16)  not null,
    endpoint           varchar(512) not null,
    username           varchar(128),
    encrypted_password text,
    status             varchar(16)  not null default 'ACTIVE',
    created_by         varchar(128),
    created_at         timestamptz  not null default now(),
    constraint warehouse_connection_type_check check (type in ('doris', 'hdfs', 's3')),
    constraint warehouse_connection_name_uq unique (tenant_id, name)
);

create index if not exists warehouse_connection_tenant_idx
    on platform.warehouse_connection (tenant_id);

alter table platform.project
    add column if not exists doris_connection_id bigint
        references platform.warehouse_connection (id);
alter table platform.project
    add column if not exists hdfs_connection_id bigint
        references platform.warehouse_connection (id);
alter table platform.project
    add column if not exists s3_connection_id bigint
        references platform.warehouse_connection (id);
