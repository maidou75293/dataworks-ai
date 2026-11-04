-- 流任务的会话模式：
--   ISOLATED（默认）= 每次提交新建一个 SQL Gateway 会话，停止时关闭；
--   SHARED          = 复用平台级共享会话，作业之间的 CREATE TABLE 等 DDL 可以共用。
alter table platform.flink_sql_job
    add column if not exists session_mode varchar(16) not null default 'ISOLATED';

alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_session_mode_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_session_mode_check
        check (session_mode in ('ISOLATED', 'SHARED'));
