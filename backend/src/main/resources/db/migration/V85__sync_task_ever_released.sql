-- v449 P1-4：下线过的同步任务仍可被手动 run（真实起 K8s 作业）。
-- released 无法区分「从未发布（允许试运行）」与「发布后又下线（应拒绝）」，
-- 这里保留「曾经发布过」的标记：publish 置 true，offline 不清除。
alter table platform.sync_task
    add column if not exists ever_released boolean not null default false;

update platform.sync_task set ever_released = true where released = true;
update platform.sync_task set ever_released = true where released_by is not null;
