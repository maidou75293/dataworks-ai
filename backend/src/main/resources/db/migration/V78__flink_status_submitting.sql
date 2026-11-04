-- 提交占位状态 SUBMITTING：并发点两次「运行」时只有一个请求能进 Flink。
-- V68 的 CHECK 只允许 DRAFT/RUNNING/STOPPED/FAILED，不放开的话占位更新直接违反约束
-- （表现为 409「数据冲突：违反唯一性或外键约束」，很容易误判成编码重复）。
alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_status_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_status_check
        check (status in ('DRAFT', 'SUBMITTING', 'RUNNING', 'STOPPED', 'FAILED'));
