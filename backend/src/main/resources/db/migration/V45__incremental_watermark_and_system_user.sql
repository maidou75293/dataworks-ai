-- P1-1/P1-2：增量水位改为「运行前快照上界」。
-- 运行时把上界写入 pending_value；成功后只把水位推进到这个上界，
-- 不再在作业结束后取 max()（那会把作业期间新写入的行算进水位而漏读）。
alter table platform.sync_task_watermark
    add column if not exists pending_value text,
    add column if not exists pending_job varchar(128);

-- P1-5：文件归档按本次作业登记的文件处理，避免并发运行互相搬走对方未读的文件。
-- batch_id 标记「本次运行登记过」，job_name 标记「属于哪次作业」，归档只动本作业的行。
alter table platform.sync_task_file_state
    add column if not exists batch_id varchar(64);
create index if not exists idx_sync_task_file_state_job
    on platform.sync_task_file_state (task_id, job_name, status);
create index if not exists idx_sync_task_file_state_batch
    on platform.sync_task_file_state (task_id, batch_id, status);

-- P1-27：MCP/Agent 内部调用方使用显式系统账号；未知用户名不再跳过 RBAC。
insert into platform.app_user (username, display_name, role, status, source)
select 'mcp-client', 'MCP 系统账号', 'DEVELOPER', 'ACTIVE', 'SYSTEM'
where not exists (select 1 from platform.app_user where username = 'mcp-client');
