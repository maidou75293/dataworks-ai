-- 校验结果落库：和冒烟一样按 SQL 指纹判定「对当前这版 SQL 有效」，
-- 刷新页面/重新打开任务时状态还在；SQL 一改就自动失效。
alter table platform.flink_sql_job
    add column if not exists validate_hash varchar(64),
    add column if not exists validate_at   timestamptz,
    add column if not exists validate_by   varchar(128);
