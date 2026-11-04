-- 任务编码大小写不敏感唯一：MyJob 和 myjob 之前能共存，列表里看起来是重复任务。
-- 现有数据已确认无冲突（迁移前跑过 group by lower(code)）。
create unique index if not exists uq_flink_job_code_ci
    on platform.flink_sql_job (tenant_id, lower(code));
