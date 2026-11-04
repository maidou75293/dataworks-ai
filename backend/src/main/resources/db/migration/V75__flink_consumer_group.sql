-- 流开发补任务级消费组：Kafka 源在 FlinkSQL 里要求 properties.group.id，
-- 脚本里用 KAFKA_GROUP_ID 占位符（提交时由后端替换），库里的 SQL 不落具体环境。
-- 注意：迁移文件里的 ${...} 会被 Flyway 当占位符解析，注释里也不要写美元花括号。
alter table platform.flink_sql_job
    add column if not exists consumer_group varchar(249);

alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_consumer_group_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_consumer_group_check
        check (consumer_group is null or length(consumer_group) between 1 and 249);
