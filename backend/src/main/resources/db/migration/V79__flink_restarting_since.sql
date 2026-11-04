-- 长时间 RESTARTING 视为失败：Flink 默认重启策略可能无限重试，
-- 启动即失败的作业会一直「运行中」。记录第一次进入重启的时间，超过阈值由对账任务收敛为 FAILED。
alter table platform.flink_sql_job
    add column if not exists restarting_since timestamptz;
