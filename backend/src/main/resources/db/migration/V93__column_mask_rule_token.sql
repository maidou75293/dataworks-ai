-- v479：列脱敏规则扩展 MASK / TOKEN / PLAIN（输出层处理，不改 SQL、不建视图）。
-- 旧枚举保留，已有数据不受影响。
alter table platform.managed_column drop constraint if exists managed_column_mask_check;
alter table platform.managed_column add constraint managed_column_mask_check
    check (mask_rule in ('NONE', 'PLAIN', 'MASK', 'TOKEN', 'FULL', 'PARTIAL', 'HASH', 'NULL'));
