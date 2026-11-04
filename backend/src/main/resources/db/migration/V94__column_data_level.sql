-- 列级数据分级（4a）：数据等级从「表」下沉到「列」。
--
-- 规则：
--   L1/L2 列一律明文（不套 mask_rule）；
--   L3/L4 列无有效授权时按 mask_rule 输出，未配置规则时 L3 默认 TOKEN、L4 默认 MASK。
--   表级等级不再手工设置，由列等级推导（无列时回落到 stored 值）。
--
-- 兼容：只加列/改默认值，不更新历史行；已有表的 L3 保持不变。
alter table platform.managed_column
    add column if not exists data_level varchar(8) not null default 'L2';

alter table platform.managed_table
    alter column data_level set default 'L2';

-- 历史兼容：已经配置了脱敏规则（MASK/TOKEN/FULL/PARTIAL/HASH/NULL）的列提升到 L3，
-- 否则 V94 的默认 L2 会让这些历史敏感列直接变明文。
update platform.managed_column
   set data_level = 'L3'
 where data_level = 'L2'
   and mask_rule not in ('NONE', 'PLAIN');

alter table platform.managed_column
    drop constraint if exists managed_column_level_check;
alter table platform.managed_column
    add constraint managed_column_level_check
        check (data_level in ('L1', 'L2', 'L3', 'L4'));
