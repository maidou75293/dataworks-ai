-- 失败原因：Prefect 的状态消息（如
-- "Flow run encountered an exception: UndefinedTable: relation ... does not exist"）。
-- 实例和任务两级都落库，页面直接展示，不用再让用户去日志里翻。
alter table platform.wf_instance add column if not exists error_message text;
alter table platform.task_instance add column if not exists error_message text;
