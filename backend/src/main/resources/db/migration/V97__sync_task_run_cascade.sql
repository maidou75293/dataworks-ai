-- P2-26：删除同步任务时同步清理运行记录。
-- 先清历史孤儿（任务已删但 run 还在），再加级联外键；之后删除任务自动收尾。
delete from platform.sync_task_run r
 where not exists (select 1 from platform.sync_task t where t.id = r.task_id);

alter table platform.sync_task_run
    drop constraint if exists sync_task_run_task_fk;
alter table platform.sync_task_run
    add constraint sync_task_run_task_fk
        foreign key (task_id) references platform.sync_task (id) on delete cascade;
