-- 数据分级与脱敏（2026-10-24）
--
-- 设计见 docs/权限模型抽象设计-20261024.md §10：
--   表级 L1 公开 / L2 内部 / L3 敏感 / L4 机密；未定级默认 L3（宁严勿松）
--   字段级 sensitivity（PII/PHONE/ID_CARD/EMAIL/NAME/AMOUNT/NORMAL）+ mask_rule
--
-- 查询网关有两种模式，默认 LOG（只判定不拦截，用于上线前观察）：
--   OFF     不判定（等价升级前行为）
--   LOG     判定并记录「本会拦截/本会脱敏」，但原样返回
--   ENFORCE 真的拦截与脱敏
--
-- 回滚：drop platform.managed_column / platform.setting；
--       alter table platform.managed_table drop column data_level, level_updated_*；
--       delete from platform.permission_catalog where code='column:unmask'。

alter table platform.managed_table
    add column if not exists data_level varchar(8) not null default 'L3',
    add column if not exists level_updated_at timestamptz,
    add column if not exists level_updated_by varchar(64);

alter table platform.managed_table
    drop constraint if exists managed_table_level_check;
alter table platform.managed_table
    add constraint managed_table_level_check
        check (data_level in ('L1', 'L2', 'L3', 'L4'));

create table if not exists platform.managed_column (
    id          bigserial primary key,
    table_id    bigint      not null references platform.managed_table(id) on delete cascade,
    column_name varchar(128) not null,
    data_type   varchar(64),
    sensitivity varchar(16) not null default 'NORMAL',
    mask_rule   varchar(16) not null default 'NONE',
    updated_at  timestamptz not null default now(),
    updated_by  varchar(64),
    unique (table_id, column_name),
    constraint managed_column_mask_check
        check (mask_rule in ('NONE', 'FULL', 'PARTIAL', 'HASH', 'NULL')),
    constraint managed_column_sensitivity_check
        check (sensitivity in ('NORMAL', 'PII', 'PHONE', 'ID_CARD', 'EMAIL', 'NAME', 'AMOUNT'))
);

create index if not exists idx_managed_column_table on platform.managed_column (table_id);

-- 通用运行时设置（助手开关在 agent_setting，这张表给数据网关等通用开关用）
create table if not exists platform.setting (
    setting_key   varchar(64) primary key,
    setting_value text        not null,
    updated_at    timestamptz not null default now(),
    updated_by    varchar(64)
);

insert into platform.setting (setting_key, setting_value, updated_by)
values ('data_guard_mode', 'LOG', 'migration')
on conflict (setting_key) do nothing;

-- 新权限点：看到未脱敏明文（默认只 ADMIN，可在角色页发给 SECURITY_ADMIN 等）
insert into platform.permission_catalog (code, name, group_name, danger)
values ('column:unmask', '查看未脱敏明文', '数据安全', '高')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

insert into platform.role_permission (role_id, permission)
select r.id, 'column:unmask'
from platform.role r
where r.code = 'ADMIN' and r.builtin
on conflict (role_id, permission) do nothing;
