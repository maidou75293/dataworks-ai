-- 流任务：PROD 侧单独记录「从什么时候开始重启」。
-- DEV 已有 restarting_since；PROD 与 DEV 可能同时在跑，不能共用一个时间戳，
-- 否则一方的重启窗口会把另一方直接判失败。对账据此在 2 分钟后收敛为 FAILED。
alter table platform.flink_sql_job
    add column if not exists prod_restarting_since timestamptz;

-- 数据质量：区分「规则执行成功但数据不达标(OK)」与「规则根本没执行成功(ERROR)」。
-- 以前只有 passed 一列，数据源不可达也会写成 passed=false，页面趋势、告警、
-- 运维汇总都把它当成数据质量问题，结果不可信。
alter table platform.quality_result
    add column if not exists exec_status varchar(16) not null default 'OK';

create index if not exists idx_quality_result_exec_status
    on platform.quality_result (exec_status, created_at desc);
