-- 任务依赖：上游采集任务最近一次运行成功，下游才允许被调度触发。
alter table platform.sync_task
    add column if not exists depends_on_task_id bigint
        references platform.sync_task (id) on delete set null;

create index if not exists idx_sync_task_depends
    on platform.sync_task (depends_on_task_id);
