-- 调度失败重试计数：一次周期触发内用掉几次重试；新的周期触发时归零。
alter table platform.sync_task
    add column if not exists schedule_retry_count integer not null default 0;
