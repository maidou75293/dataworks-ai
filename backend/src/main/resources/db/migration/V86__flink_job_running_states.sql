-- v450 P1-2：作业状态要区分「启动中 / 重启中 / 停止中」。原 CHECK 只允许
-- DRAFT/SUBMITTING/RUNNING/STOPPED/FAILED，status 接口写 STARTING/RESTARTING 会
-- 直接 400「字段取值不合法」。
alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_status_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_status_check
    check (status in ('DRAFT', 'SUBMITTING', 'STARTING', 'RUNNING', 'RESTARTING',
                      'STOPPING', 'STOPPED', 'FAILED'));
