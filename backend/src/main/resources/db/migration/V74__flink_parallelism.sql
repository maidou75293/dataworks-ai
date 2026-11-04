-- 流开发补运行参数：并行度。之前只能写在 SQL 里（SET 'parallelism.default'），
-- 页面上没有地方配，作业默认 1 并发。
alter table platform.flink_sql_job
    add column if not exists parallelism int not null default 1;

alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_parallelism_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_parallelism_check check (parallelism between 1 and 128);
