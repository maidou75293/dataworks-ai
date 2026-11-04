-- 停止后保留「最后一次运行的作业 ID」：metrics / logs / graph 停止后仍可回看。
-- flink_job_id 只表示「当前在跑」，last_flink_job_id 表示「最近一次提交过」。
alter table platform.flink_sql_job
    add column if not exists last_flink_job_id varchar(64),
    add column if not exists last_job_at       timestamptz;
