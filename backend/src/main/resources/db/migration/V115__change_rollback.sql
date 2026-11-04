-- V115：变更单回滚闭环（D53，2026-10-26）
--
-- 高风险变更此前只有「执行前备份表」（V105），没有回滚语句、没有回滚入口，
-- ALTER 类（删列/改类型）连备份都没有。本迁移给 platform.change_request 补三列：
--   * inverse_sql   —— 回滚语句（提交时生成；DROP/TRUNCATE 的备份表名在执行后回填）。
--                      为空 = 不可自动生成回滚。
--   * irreversible  —— 数据无法自动还原（DROP/MODIFY COLUMN、DELETE/UPDATE 等）。
--                      true 的单子审批前必须显式勾选「已知不可逆」，否则 400。
--   * rollback_of   —— 回滚单指向原变更单 id；回滚单走同一套审批，不许自动回滚。
--
-- 回滚：alter table platform.change_request drop column inverse_sql,
--       drop column irreversible, drop column rollback_of;（无历史数据依赖）

alter table platform.change_request
    add column if not exists inverse_sql text;
alter table platform.change_request
    add column if not exists irreversible boolean not null default false;
alter table platform.change_request
    add column if not exists rollback_of bigint;

-- 「同一原单只允许一张在途回滚单」的查重走这个索引
create index if not exists idx_change_request_rollback_of
    on platform.change_request (tenant_id, rollback_of);
