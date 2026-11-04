-- 多副本调度防重：一次周期触发的“占用”时间。
-- 每个副本在触发前先做条件更新抢占用，抢不到就跳过，避免同一分钟提交两份作业。
alter table platform.sync_task
    add column if not exists schedule_claimed_at timestamptz;
