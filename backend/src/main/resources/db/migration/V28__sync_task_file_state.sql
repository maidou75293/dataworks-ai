-- 文件级增量：记录每个任务见过/处理过的文件。
-- 唯一键包含 size：同名文件内容重传（大小变了）算新版本，会再处理一次。
create table if not exists platform.sync_task_file_state (
    id           bigserial primary key,
    task_id      bigint       not null references platform.sync_task (id) on delete cascade,
    file_path    varchar(1024) not null,
    file_size    bigint       not null default 0,
    file_mtime   timestamptz,
    status       varchar(16)  not null default 'PENDING',
    job_name     varchar(128),
    archived_path varchar(1024),
    discovered_at timestamptz not null default now(),
    processed_at timestamptz,
    constraint uk_sync_task_file_state unique (task_id, file_path, file_size)
);

create index if not exists idx_sync_task_file_state_pending
    on platform.sync_task_file_state (task_id, status);
