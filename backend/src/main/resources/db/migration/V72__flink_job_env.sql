-- 流开发对齐数据开发：任务区分 DEV / PROD 环境。
-- 当前部署只有一个 Flink 会话集群，两个环境都跑在同一个集群上（文档已注明）；
-- 字段先落库、树/页面按环境展示，后续接独立 PROD 集群时不用再改表。
alter table platform.flink_sql_job
    add column if not exists env varchar(8) not null default 'DEV';

alter table platform.flink_sql_job
    drop constraint if exists flink_sql_job_env_check;

alter table platform.flink_sql_job
    add constraint flink_sql_job_env_check check (env in ('DEV', 'PROD'));
