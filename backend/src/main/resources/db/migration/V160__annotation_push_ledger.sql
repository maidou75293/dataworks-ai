-- REQ-FW-1 · 数据飞轮回流①：「表/查询结果 → mos 标注数据集」的进环台账。
-- 契约 dabce8a §3.3：中台只登记 ds_id@version，不二次落数据文件（单一事实源在对方：
--   mos/platform/backend/db.py:43 datasets.version TEXT；freeze 快照 api/datasets.py:1216）。
-- 防重推：UNIQUE (ds_id, version) + 服务层 ON CONFLICT DO UPDATE —— 同版本重复推覆盖不增行。
-- version 用 varchar 并归一化 NOT NULL DEFAULT '0'：对方 version 是 TEXT、freeze 前为 NULL，
--   若列留 NULL，PG 唯一索引视 NULL 互不相等，幂等闸门直接破防——这是本表存在的第一理由，不能省。
-- 号段说明：V150–159 已被 REQ-UNIFY-1（mos:access 权限点）占用，本迁移取 V160（卡面指令）。

create table if not exists platform.annotation_push_ledger (
    id          bigint generated always as identity primary key,
    tenant_code varchar(64),
    ds_id       varchar(64)  not null,
    version     varchar(32)  not null default '0',
    source_desc varchar(512) not null,
    q_column    varchar(128) not null,
    a_column    varchar(128) not null,
    ref_column  varchar(128),
    row_count   integer      not null,
    pushed_by   varchar(64),
    pushed_at   timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uq_annotation_push_ds_version unique (ds_id, version)
);

create index if not exists ix_annotation_push_tenant
    on platform.annotation_push_ledger (tenant_code);

comment on table platform.annotation_push_ledger is
    '数据入环台账（REQ-FW-1）：一行=一个 ds_id@version 的最近一次推送摘要；数据本体只在对方';
